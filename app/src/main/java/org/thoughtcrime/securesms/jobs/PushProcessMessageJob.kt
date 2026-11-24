package org.thoughtcrime.securesms.jobs

import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.signal.core.models.ServiceId
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.groups.GroupChangeBusyException
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.network.exceptions.PushNetworkException
import org.thoughtcrime.securesms.calls.permissions.CallMessageQueueReceipt
import org.thoughtcrime.securesms.database.SignalDatabase.Companion.groups
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.impl.BackoffUtil
import org.thoughtcrime.securesms.jobmanager.impl.ChangeNumberConstraint
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.messages.BatchCache
import org.thoughtcrime.securesms.messages.MessageContentProcessor
import org.thoughtcrime.securesms.messages.MessageDecryptor
import org.thoughtcrime.securesms.messages.SignalServiceProtoUtil.groupId
import org.thoughtcrime.securesms.recipients.RecipientIds
import org.thoughtcrime.securesms.util.GroupUtil
import org.thoughtcrime.securesms.util.SignalLocalMetrics
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.api.crypto.protos.CompleteMessage
import org.whispersystems.signalservice.api.groupsv2.NoCredentialForRedemptionTimeException
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.Envelope
import org.whispersystems.signalservice.internal.util.Util
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import org.whispersystems.signalservice.api.crypto.protos.EnvelopeMetadata as EnvelopeMetadataProto

class PushProcessMessageJob private constructor(
  parameters: Parameters,
  private val envelope: Envelope,
  private val content: Content,
  private val metadata: EnvelopeMetadata,
  private val serverDeliveredTimestamp: Long,
  private val callQueueReceipt: CallMessageQueueReceipt? = null
) : BaseJob(parameters) {

  override fun shouldTrace() = true

  override fun serialize(): ByteArray {
    return CompleteMessage(
      envelope = envelope.encodeByteString(),
      content = content.encodeByteString(),
      metadata = EnvelopeMetadataProto(
        sourceServiceId = ByteString.of(*metadata.sourceServiceId.toByteArray()),
        sourceE164 = metadata.sourceE164,
        sourceDeviceId = metadata.sourceDeviceId,
        sealedSender = metadata.sealedSender,
        groupId = if (metadata.groupId != null) metadata.groupId!!.toByteString() else null,
        destinationServiceId = ByteString.of(*metadata.destinationServiceId.toByteArray()),
        ciphertextMessageType = metadata.ciphertextMessageType
      ),
      serverDeliveredTimestamp = serverDeliveredTimestamp,
      callQueueProcess = callQueueReceipt?.process,
      callQueueGeneration = callQueueReceipt?.generation,
      callQueueReceivedAt = callQueueReceipt?.receivedAt
    ).encode()
  }

  override fun getFactoryKey(): String {
    return KEY
  }

  public override fun onRun() {
    if (isCallingMessage(content) && callQueueReceipt?.queueDelayMillis() == null) {
      Log.i(TAG, "Dropping unstamped, expired or revoked deferred calling message.")
      return
    }
    val processor = MessageContentProcessor.create(context)
    processor.process(envelope, content, metadata, serverDeliveredTimestamp, callQueueReceipt = callQueueReceipt)
  }

  public override fun onShouldRetry(e: Exception): Boolean {
    return e is PushNetworkException ||
      e is NoCredentialForRedemptionTimeException ||
      e is GroupChangeBusyException
  }

  override fun getNextRunAttemptBackoff(pastAttemptCount: Int, exception: Exception): Long {
    return if (exception is GroupChangeBusyException) {
      BackoffUtil.exponentialBackoff(pastAttemptCount, GROUP_BUSY_MAX_BACKOFF.inWholeMilliseconds)
    } else {
      super.getNextRunAttemptBackoff(pastAttemptCount, exception)
    }
  }

  override fun onFailure() = Unit

  class Factory : Job.Factory<PushProcessMessageJob?> {
    override fun create(parameters: Parameters, data: ByteArray?): PushProcessMessageJob {
      return try {
        val completeMessage = CompleteMessage.ADAPTER.decode(data!!)
        PushProcessMessageJob(
          parameters = parameters,
          envelope = Envelope.ADAPTER.decode(completeMessage.envelope.toByteArray()),
          content = Content.ADAPTER.decode(completeMessage.content.toByteArray()),
          metadata = EnvelopeMetadata(
            sourceServiceId = ServiceId.parseOrThrow(completeMessage.metadata.sourceServiceId.toByteArray()),
            sourceE164 = completeMessage.metadata.sourceE164,
            sourceDeviceId = completeMessage.metadata.sourceDeviceId,
            sealedSender = completeMessage.metadata.sealedSender,
            groupId = completeMessage.metadata.groupId?.toByteArray(),
            destinationServiceId = ServiceId.parseOrThrow(completeMessage.metadata.destinationServiceId.toByteArray()),
            ciphertextMessageType = completeMessage.metadata.ciphertextMessageType ?: CiphertextMessage.WHISPER_TYPE
          ),
          serverDeliveredTimestamp = completeMessage.serverDeliveredTimestamp,
          callQueueReceipt = CallMessageQueueReceipt.fromPersisted(completeMessage.callQueueProcess, completeMessage.callQueueGeneration, completeMessage.callQueueReceivedAt)
        )
      } catch (e: IOException) {
        throw AssertionError(e)
      }
    }
  }

  companion object {
    const val KEY = "PushProcessMessageJobV2"
    const val QUEUE_PREFIX = "__PUSH_PROCESS_JOB__"

    private val TAG = Log.tag(PushProcessMessageJob::class.java)

    private val GROUP_BUSY_MAX_BACKOFF = 10.seconds

    /**
     * Cache to keep track of empty 1:1 processing queues. Once a 1:1 queue is empty
     * we no longer enqueue jobs on it and instead process inline. This is not
     * true for groups, as with groups we may have to do network fetches
     * to get group state up to date.
     */
    private val empty1to1QueueCache = HashSet<String>()

    @JvmStatic
    fun getQueueName(recipientId: RecipientId): String {
      return QUEUE_PREFIX + recipientId.toQueueKey()
    }

    fun processOrDefer(messageProcessor: MessageContentProcessor, result: MessageDecryptor.Result.Success, localReceiveMetric: SignalLocalMetrics.MessageReceive, batchCache: BatchCache): PushProcessMessageJob? {
      // Stamp once before inspecting/deferring the queue. Rehydrating the job must never
      // refresh the receipt or silently bind old remote bytes to a new policy generation.
      val callQueueReceipt = if (isCallingMessage(result.content)) CallMessageQueueReceipt.capture() else null
      val groupContext = GroupUtil.getGroupContextIfPresent(result.content)
      val groupId = groupContext?.groupId
      var requireNetwork = false

      val queueName: String = if (groupId != null) {
        if (groupId.isV2) {
          val localRevision = batchCache.groupRevisionCache.getOrPut(groupId) { groups.getGroupV2Revision(groupId.requireV2()) }

          if (groupContext.revision!! > localRevision) {
            Log.i(TAG, "Adding network constraint to group-related job.")
            requireNetwork = true
          }
        }
        getQueueName(RecipientIds.from(groupId))
      } else if (result.content.syncMessage != null &&
        result.content.syncMessage!!.sent != null &&
        Util.anyNotNull(result.content.syncMessage!!.sent!!.destinationServiceId, result.content.syncMessage!!.sent!!.destinationServiceIdBinary)
      ) {
        getQueueName(RecipientIds.from(ServiceId.parseOrThrow(result.content.syncMessage!!.sent!!.destinationServiceId, result.content.syncMessage!!.sent!!.destinationServiceIdBinary)))
      } else {
        getQueueName(RecipientIds.from(result.metadata.sourceServiceId))
      }

      return if (requireNetwork || !isQueueEmpty(queueName = queueName, cache = if (groupId != null) batchCache.groupQueueEmptyCache else empty1to1QueueCache)) {
        val builder = Parameters.Builder()
          .setMaxAttempts(Parameters.UNLIMITED)
          .addConstraint(ChangeNumberConstraint.KEY)
          .setQueue(queueName)
        if (requireNetwork) {
          builder.addConstraint(NetworkConstraint.KEY).setLifespan(TimeUnit.DAYS.toMillis(30))
        }
        batchCache.groupQueueEmptyCache.remove(queueName)
        PushProcessMessageJob(builder.build(), result.envelope.newBuilder().content(null).build(), result.content, result.metadata, result.serverDeliveredTimestamp, callQueueReceipt)
      } else {
        try {
          messageProcessor.process(result.envelope, result.content, result.metadata, result.serverDeliveredTimestamp, localMetric = localReceiveMetric, batchCache = batchCache, callQueueReceipt = callQueueReceipt)
        } catch (e: Exception) {
          Log.e(TAG, "Failed to process message with timestamp ${result.envelope.clientTimestamp}. Dropping.", e)
        }
        null
      }
    }

    private fun isQueueEmpty(queueName: String, cache: HashSet<String>): Boolean {
      if (cache.contains(queueName)) {
        return true
      }
      val queueEmpty = AppDependencies.jobManager.isQueueEmpty(queueName)
      if (queueEmpty) {
        cache.add(queueName)
      }
      return queueEmpty
    }

    private fun isCallingMessage(content: Content): Boolean {
      // Match MessageContentProcessor's existing content precedence. A call field ignored by
      // that dispatcher must not change persistence behavior for an ordinary data/sync message.
      return content.dataMessage == null && content.syncMessage == null && content.callMessage != null
    }
  }
}

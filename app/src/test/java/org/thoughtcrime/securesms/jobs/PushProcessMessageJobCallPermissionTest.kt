package org.thoughtcrime.securesms.jobs

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.signal.core.models.ServiceId
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.calls.permissions.CallMessageQueueReceipt
import org.thoughtcrime.securesms.calls.permissions.CallPermissionTestEnvironment
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.messages.MessageContentProcessor
import org.thoughtcrime.securesms.messages.MessageDecryptor
import org.thoughtcrime.securesms.messages.OneTimeBatchCache
import org.thoughtcrime.securesms.recipients.RecipientIds
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.api.crypto.protos.CompleteMessage
import org.whispersystems.signalservice.internal.push.CallMessage
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.Envelope
import java.time.Duration
import java.util.UUID
import org.whispersystems.signalservice.api.crypto.protos.EnvelopeMetadata as EnvelopeMetadataProto

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class PushProcessMessageJobCallPermissionTest {
  private val policy = CallPermissionTestEnvironment()
  private val processor: MessageContentProcessor = mockk(relaxed = true)
  private val envelope = Envelope(clientTimestamp = 1_000, serverTimestamp = 1_000)
  private val metadata = EnvelopeMetadata(ServiceId.ACI.from(UUID(0, 7)), null, 2, true, null, policy.accountId, 1)
  private val calling = Content(callMessage = CallMessage(iceUpdate = listOf(CallMessage.IceUpdate(id = 41, opaque = ByteString.EMPTY))))

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    policy.setUp()
    mockkStatic(AppDependencies::class, MessageContentProcessor::class, RecipientIds::class)
    mockkObject(MessageContentProcessor.Companion, RecipientIds)
    every { MessageContentProcessor.create(any()) } returns processor
    every { AppDependencies.jobManager } returns mockk { every { isQueueEmpty(any()) } returns false }
    every { RecipientIds.from(any<ServiceId>()) } returns RecipientId.from(7)
  }

  @After
  fun tearDown() {
    unmockkObject(MessageContentProcessor.Companion, RecipientIds)
    unmockkStatic(AppDependencies::class, MessageContentProcessor::class, RecipientIds::class)
    policy.tearDown()
  }

  @Test
  fun `ordinary production deferral stamps once and preserves receipt when rehydrated`() {
    val result = MessageDecryptor.Result.Success(envelope, 1_000, calling, metadata, emptyList())
    val queued = requireNotNull(PushProcessMessageJob.processOrDefer(processor, result, mockk(), OneTimeBatchCache()))
    val serialized = CompleteMessage.ADAPTER.decode(queued.serialize())
    assertNotNull(serialized.callQueueProcess)
    assertEquals(CallPermissions.generation(), serialized.callQueueGeneration)
    assertNotNull(serialized.callQueueReceivedAt)

    ShadowSystemClock.advanceBy(Duration.ofMillis(1_000))
    val restored = job(serialized)
    assertEquals(serialized, CompleteMessage.ADAPTER.decode(restored.serialize()))
    restored.onRun()
    verify(exactly = 1) { processor.process(any(), calling, any(), 1_000, any(), any(), any(), match { it.queueDelayMillis() == 1_000L }) }
  }

  @Test
  fun `unstamped and previous process calling jobs never enter content processing`() {
    job(data(calling)).onRun()
    val receipt = CallMessageQueueReceipt.capture()
    job(data(calling, receipt).copy(callQueueProcess = "previous-process")).onRun()
    verify(exactly = 0) { MessageContentProcessor.create(any()) }
  }

  @Test
  fun `expired and revoked calling jobs never enter content processing`() {
    val queued = data(calling, CallMessageQueueReceipt.capture())
    ShadowSystemClock.advanceBy(Duration.ofMillis(120_000))
    job(queued).onRun()

    val revoked = data(calling, CallMessageQueueReceipt.capture())
    CallPermissions.setAllCallingDisabled(true)
    CallPermissions.setAllCallingDisabled(false)
    job(revoked).onRun()
    verify(exactly = 0) { MessageContentProcessor.create(any()) }
  }

  @Test
  fun `legacy ordinary messages retain content processing without a calling stamp`() {
    val ordinary = Content(dataMessage = DataMessage(body = "hello", timestamp = 1_000))
    val restored = job(data(ordinary))
    CallPermissions.setAllCallingDisabled(true)
    ShadowSystemClock.advanceBy(Duration.ofMillis(120_001))
    restored.onRun()

    verify(exactly = 1) { processor.process(any(), ordinary, any(), 1_000, any(), any(), any(), null) }
    assertNull(CompleteMessage.ADAPTER.decode(restored.serialize()).callQueueProcess)
  }

  @Test
  fun `an ignored calling field preserves existing ordinary content precedence`() {
    val ordinary = calling.copy(dataMessage = DataMessage(body = "hello", timestamp = 1_000))
    val restored = job(data(ordinary))
    CallPermissions.setAllCallingDisabled(true)
    restored.onRun()

    verify(exactly = 1) { processor.process(any(), ordinary, any(), 1_000, any(), any(), any(), null) }
  }

  private fun job(serialized: CompleteMessage) = PushProcessMessageJob.Factory().create(Job.Parameters.Builder().build(), serialized.encode()).apply {
    setContext(ApplicationProvider.getApplicationContext())
  }

  private fun data(content: Content, receipt: CallMessageQueueReceipt? = null) = CompleteMessage(
    envelope = envelope.encodeByteString(),
    content = content.encodeByteString(),
    metadata = EnvelopeMetadataProto(
      sourceServiceId = metadata.sourceServiceId.toByteArray().toByteString(),
      sourceDeviceId = metadata.sourceDeviceId,
      sealedSender = metadata.sealedSender,
      destinationServiceId = metadata.destinationServiceId.toByteArray().toByteString(),
      ciphertextMessageType = metadata.ciphertextMessageType
    ),
    serverDeliveredTimestamp = 1_000,
    callQueueProcess = receipt?.process,
    callQueueGeneration = receipt?.generation,
    callQueueReceivedAt = receipt?.receivedAt
  )
}

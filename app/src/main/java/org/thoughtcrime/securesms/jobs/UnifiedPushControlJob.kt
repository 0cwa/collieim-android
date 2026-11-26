package org.thoughtcrime.securesms.jobs

import im.molly.unifiedpush.control.EndpointUpdates
import im.molly.unifiedpush.control.CorruptControlStateException
import im.molly.unifiedpush.control.ControlFrame
import im.molly.unifiedpush.control.UnifiedPushControlStore
import im.molly.unifiedpush.control.ReceiverControlState
import im.molly.unifiedpush.control.ControlFrameV4
import im.molly.unifiedpush.enrollment.EnrollmentFile
import im.molly.unifiedpush.enrollment.PairingRegistrationGate
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.keyvalue.SignalStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Sends the stored endpoint update to this account's own CollieSocket device over Signal, and sends the same blob
 * again until a `subscription_updated` status arrives at the update's epoch. Whether the update is still wanted is
 * read from the key-value store on every run, so a confirmed or replaced update ends or redirects the job.
 *
 * It holds the master-secret constraint (see [BaseJob]): a locked phone does not send. The update itself is kept in
 * the key-value store; an endpoint that arrived while locked waits in `LockedPushState` until unlock.
 */
class UnifiedPushControlJob private constructor(parameters: Parameters) : BaseJob(parameters) {

  private class AwaitingConfirmation : IOException("Endpoint update sent; waiting for the confirmation push")
  private class PairingPending : IOException("Endpoint update deferred by pairing")

  private constructor() : this(
    Parameters.Builder()
      .setQueue(KEY)
      .addConstraint(NetworkConstraint.KEY)
      .setMaxAttempts(MAX_ATTEMPTS)
      .setLifespan(TimeUnit.HOURS.toMillis(24))
      .build()
  )

  override fun serialize(): ByteArray? = null

  override fun getFactoryKey(): String = KEY

  override fun onFailure() {
    // Job cancellation means enqueue() already scheduled a replacement. A terminal retry/lifespan
    // failure is different: keep the durable update moving through another bounded job lifecycle.
    if (isCanceled()) return
    try {
      val values = SignalStore.unifiedpush
      if (UnifiedPushControlStore(values).read().update != null || ReceiverControlState.parse(values.v4ReceiverState)?.pendingEpoch != null) enqueue()
    } catch (e: CorruptControlStateException) {
      Log.w(TAG, "Endpoint update stopped: control state unavailable")
    }
  }

  override fun onShouldRetry(e: Exception): Boolean = true

  override fun getNextRunAttemptBackoff(pastAttemptCount: Int, exception: Exception): Long =
    if (exception is PairingPending) BACKOFF_FIRST_MS else min(BACKOFF_FIRST_MS shl min(pastAttemptCount - 1, 6), BACKOFF_MAX_MS)

  @Throws(Exception::class)
  public override fun onRun() {
    val lease = UnifiedPushAccountTransition.acquireLease()
    if (lease == null) {
      val registered = synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushAccountTransition.isRegisteredLocked() }
      if (!registered) return
      throw IOException("UnifiedPush account context is transitioning")
    }
    val values = SignalStore.unifiedpush
    val store = UnifiedPushControlStore(values)
    try {
      val selected = synchronized(UnifiedPushLifecycle.monitor) {
        if (!UnifiedPushLifecycle.isReadyLocked() || !UnifiedPushAccountTransition.contextStillMatchesLocked(lease.snapshot)) return
        if (PairingRegistrationGate.isRegistrationPending()) throw PairingPending()
        val pairingValues = SignalStore.unifiedpush
        val state = try {
          store.read()
        } catch (e: CorruptControlStateException) {
          Log.w(TAG, "Endpoint update stopped: control state unavailable")
          return
        }
        val receiverState = ReceiverControlState.parse(pairingValues.v4ReceiverState)
        if (receiverState?.confirmed == true && receiverState.pendingFrame != null &&
          receiverState.accountId == lease.snapshot.accountId && receiverState.instance == lease.snapshot.instance &&
          receiverState.pendingEpoch != null &&
          ControlFrameV4.matchesIdentity(receiverState.pendingFrame, receiverState.accountId, receiverState.bridgeDeviceId, receiverState.receiverId, receiverState.pendingEpoch)) {
          val endpoint = lease.snapshot.endpoint ?: return
          val subscription = try { ControlFrame.Subscription(endpoint, lease.snapshot.endpointP256dh.orEmpty(), lease.snapshot.endpointAuth.orEmpty()) }
          catch (_: EnrollmentFile.Invalid) { return }
          if (receiverState.pendingTarget?.matches(subscription) == true) return@synchronized ReceiverSelection(receiverState, receiverState.pendingFrame.copyOf())
        }
        val update = state.update ?: run {
          Log.d(TAG, "No endpoint update pending.")
          return
        }
        val device = lease.snapshot.pushDevice ?: return
        if (!ControlFrame.matchesIdentity(update.frame, lease.snapshot.accountId, device.deviceId, update.epoch)) return
        val endpoint = lease.snapshot.endpoint ?: return
        val subscription = try {
          ControlFrame.Subscription(endpoint, lease.snapshot.endpointP256dh.orEmpty(), lease.snapshot.endpointAuth.orEmpty())
        } catch (e: EnrollmentFile.Invalid) {
          return
        }
        if (!update.sameTarget(subscription)) return

        val pairing = pairingValues.pairingEndpointBinding
        if (pairing == null && PairingRegistrationGate.isPairingInstance(lease.snapshot.instance)) return
        if (pairing != null && (state.activeImportId == null || state.activeInstance != pairing.instance ||
            state.activeAccountId != pairing.accountId || state.activeDeviceId != device.deviceId ||
            lease.snapshot.localDeviceId != pairing.deviceId || lease.snapshot.instance != pairing.instance ||
            lease.snapshot.accountId != pairing.accountId)) return

        if (state.activeImportId != null &&
          (state.activeAccountId != lease.snapshot.accountId || state.activeDeviceId != device.deviceId || state.activeInstance != lease.snapshot.instance)) return
        LegacySelection(update, device)
      }

      if (selected is ReceiverSelection) {
        try {
          AppDependencies.signalServiceMessageSender.sendControlMessage(
            lease.snapshot.accountId, lease.snapshot.localDeviceId, selected.state.bridgeDeviceId, selected.frame,
          )
        } catch (e: Exception) {
          throw if (e is IOException) e else IOException("Receiver endpoint update send failed", e)
        }
        synchronized(UnifiedPushLifecycle.monitor) {
          val current = ReceiverControlState.parse(values.v4ReceiverState) ?: return
          values.v4ReceiverState = ReceiverControlState.recordSent(current, selected.frame, System.currentTimeMillis() / 1000).serialize()
        }
        Log.i(TAG, "Receiver endpoint update for epoch ${selected.state.pendingEpoch} sent.")
        throw AwaitingConfirmation()
      }
      selected as LegacySelection
      try {
        AppDependencies.signalServiceMessageSender.sendControlMessage(
          lease.snapshot.accountId,
          lease.snapshot.localDeviceId,
          selected.device.deviceId,
          selected.update.frame,
        )
      } catch (e: Exception) {
        EndpointUpdates.recordAttempt(store, selected.update, lease.generation, System.currentTimeMillis() / 1000, e.javaClass.simpleName)
        // BaseJob turns a RuntimeException into a crash; a failed send is only a reason to try again.
        throw if (e is IOException) e else IOException("Endpoint update send failed", e)
      }
      EndpointUpdates.recordAttempt(store, selected.update, lease.generation, System.currentTimeMillis() / 1000, null)
      Log.i(TAG, "Endpoint update for epoch ${selected.update.epoch} sent (attempt ${selected.update.attempts + 1}).")
      throw AwaitingConfirmation()
    } finally {
      lease.close()
    }
  }

  class Factory : Job.Factory<UnifiedPushControlJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?) = UnifiedPushControlJob(parameters)
  }

  private sealed interface Selection
  private data class LegacySelection(val update: im.molly.unifiedpush.control.ControlUpdate, val device: im.molly.unifiedpush.model.MollySocketDevice) : Selection
  private data class ReceiverSelection(val state: ReceiverControlState, val frame: ByteArray) : Selection

  companion object {
    private val TAG = Log.tag(UnifiedPushControlJob::class.java)
    const val KEY = "UnifiedPushControlJob"
    private const val MAX_ATTEMPTS = 24
    private val BACKOFF_FIRST_MS = TimeUnit.SECONDS.toMillis(30)
    private val BACKOFF_MAX_MS = TimeUnit.MINUTES.toMillis(30)

    /**
     * Queues a send of the stored update now. Instances already in the queue are cancelled first, so an instance
     * waiting out a backoff is replaced. There is deliberately no instance limit: a job that is running when this is
     * called is still counted until it exits, and a limit would drop the replacement, leaving the new update unsent.
     * The queue is serial, so the replacement runs after the running job, and every run sends whatever update is
     * stored when it starts. At most one running and one waiting job exist, because each call cancels the others.
     */
    @JvmStatic
    fun enqueue() {
      AppDependencies.jobManager.cancelAllInQueue(KEY)
      AppDependencies.jobManager.add(UnifiedPushControlJob())
    }
  }
}

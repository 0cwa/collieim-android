package org.thoughtcrime.securesms.jobs

import im.molly.unifiedpush.UnifiedPushDistributor
import im.molly.unifiedpush.control.ControlFrame
import im.molly.unifiedpush.control.EndpointUpdates
import im.molly.unifiedpush.control.UnifiedPushControlStore
import im.molly.unifiedpush.control.CorruptControlStateException
import im.molly.unifiedpush.enrollment.EnrollmentFile
import im.molly.unifiedpush.health.PushHealth
import im.molly.unifiedpush.health.PushHealthActions
import org.signal.core.util.eventbus.EventBus
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.events.PushServiceEvent
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.keyvalue.SignalStore
import java.util.concurrent.TimeUnit

/**
 * Tests the push path end to end: sends a control ping (an endpoint update that repeats the current subscription at
 * a new epoch, see [EndpointUpdates.begin]) and waits up to [PushHealth.PING_TIMEOUT_MS] for CollieSocket's
 * confirmation to arrive as a push. The verdict names the hop that failed and is stored for the settings screen.
 *
 * @param manual True when the user asked for the test: the verdict is stored even when nothing could be sent.
 */
class UnifiedPushHealthJob private constructor(private val manual: Boolean, parameters: Parameters) : BaseJob(parameters) {

  private constructor(manual: Boolean) : this(
    manual,
    Parameters.Builder()
      .setQueue(KEY)
      .setMaxInstancesForQueue(1)
      .addConstraint(NetworkConstraint.KEY)
      .setMaxAttempts(1)
      .setLifespan(TimeUnit.MINUTES.toMillis(10))
      .build()
  )

  override fun serialize(): ByteArray? = JsonJobData.Builder().putBoolean(KEY_MANUAL, manual).serialize()

  override fun getFactoryKey(): String = KEY

  /** The settings screen shows "testing" until it hears from the job, so every way out ends with an event. */
  override fun onFailure() = EventBus.default.post(PushServiceEvent)

  override fun onShouldRetry(e: Exception): Boolean = false

  @Throws(Exception::class)
  public override fun onRun() {
    try {
      runPing()
    } finally {
      EventBus.default.post(PushServiceEvent)
    }
  }

  private fun runPing() {
    val values = SignalStore.unifiedpush
    val store = UnifiedPushControlStore(values)
    val state = try {
      store.read()
    } catch (e: CorruptControlStateException) {
      Log.w(TAG, "Push test unavailable: control state unavailable")
      if (manual) PushHealthActions.onPing(PushHealth.Hop.UNAVAILABLE)
      return
    }
    val aci = SignalStore.account.aci
    val device = values.device
    val endpoint = values.endpoint
    val distributorActive = distributorActive()

    if (aci == null || device == null || endpoint == null || !distributorActive || state.key == null || state.activeEpoch <= 0) {
      // Nothing can be sent. A missing distributor or endpoint is a verdict of its own; the rest is "unavailable",
      // which is shown but never counted as a failure.
      val hop = PushHealth.diagnose(null, distributorActive, endpoint != null)
      if (manual || hop == PushHealth.Hop.DISTRIBUTOR) PushHealthActions.onPing(hop)
      return
    }

    val subscription = try {
      ControlFrame.Subscription(endpoint, values.endpointP256dh.orEmpty(), values.endpointAuth.orEmpty())
    } catch (e: EnrollmentFile.Invalid) {
      PushHealthActions.onPing(PushHealth.Hop.UNAVAILABLE)
      return
    }
    val update = EndpointUpdates.reconcile(store, aci.toString(), device.deviceId, subscription, System.currentTimeMillis() / 1000, force = true).update
    if (update == null) {
      PushHealthActions.onPing(PushHealth.Hop.UNAVAILABLE)
      return
    }
    // Replaces an instance waiting out a backoff, so the first send is inside the timeout.
    UnifiedPushControlJob.enqueue()

    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val current = try {
        store.read().update
      } catch (e: CorruptControlStateException) {
        Log.w(TAG, "Push test stopped: control state unavailable")
        return
      }
      if (current == null || current.epoch != update.epoch) {
        // The epoch is no longer pending. The receiver recorded the success when the confirmation arrived; otherwise a
        // newer endpoint update replaced the ping and there is no verdict.
        val currentState = try {
          store.read()
        } catch (e: CorruptControlStateException) {
          Log.w(TAG, "Push test stopped: control state unavailable")
          return
        }
        if (currentState.activeEpoch < update.epoch) Log.i(TAG, "The ping was replaced by a newer endpoint update; no verdict.")
        return
      }
      Thread.sleep(POLL_MS)
    }

    val hop = PushHealth.diagnose(store.read().update, distributorActive(), SignalStore.unifiedpush.endpoint != null)
    PushHealthActions.onPing(hop)
  }

  class Factory : Job.Factory<UnifiedPushHealthJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?) =
      UnifiedPushHealthJob(JsonJobData.deserialize(serializedData).getBoolean(KEY_MANUAL), parameters)
  }

  companion object {
    private val TAG = Log.tag(UnifiedPushHealthJob::class.java)
    const val KEY = "UnifiedPushHealthJob"
    private const val KEY_MANUAL = "manual"
    private const val POLL_MS = 500L

    /** How long a ping may wait for its confirmation. A test may shorten it. */
    @JvmStatic
    @androidx.annotation.VisibleForTesting
    var timeoutMs: Long = PushHealth.PING_TIMEOUT_MS

    /** Whether a distributor app is registered and answering. A test replaces it, as no distributor is installed there. */
    @JvmStatic
    @androidx.annotation.VisibleForTesting
    var distributorActive: () -> Boolean = UnifiedPushDistributor::checkIfActive

    @JvmStatic
    fun enqueue(manual: Boolean) {
      AppDependencies.jobManager.add(UnifiedPushHealthJob(manual))
    }
  }
}

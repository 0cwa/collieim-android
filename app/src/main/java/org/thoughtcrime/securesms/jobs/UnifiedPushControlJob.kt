package org.thoughtcrime.securesms.jobs

import im.molly.unifiedpush.control.EndpointUpdates
import im.molly.unifiedpush.control.UnifiedPushControlStore
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
    if (UnifiedPushControlStore(SignalStore.unifiedpush).read().update != null) enqueue()
  }

  override fun onShouldRetry(e: Exception): Boolean = true

  override fun getNextRunAttemptBackoff(pastAttemptCount: Int, exception: Exception): Long =
    min(BACKOFF_FIRST_MS shl min(pastAttemptCount - 1, 6), BACKOFF_MAX_MS)

  @Throws(Exception::class)
  public override fun onRun() {
    val values = SignalStore.unifiedpush
    val store = UnifiedPushControlStore(values)
    val update = store.read().update
    if (update == null) {
      Log.d(TAG, "No endpoint update pending.")
      return
    }
    val device = values.device ?: return

    try {
      AppDependencies.signalServiceMessageSender.sendControlMessage(device.deviceId, update.frame)
    } catch (e: Exception) {
      EndpointUpdates.recordAttempt(store, update.epoch, System.currentTimeMillis() / 1000, e.javaClass.simpleName)
      // BaseJob turns a RuntimeException into a crash; a failed send is only a reason to try again.
      throw if (e is IOException) e else IOException("Endpoint update send failed", e)
    }
    // Only bookkeeping, and only for this epoch: a newer update that replaced it meanwhile is not touched.
    EndpointUpdates.recordAttempt(store, update.epoch, System.currentTimeMillis() / 1000, null)
    Log.i(TAG, "Endpoint update for epoch ${update.epoch} sent (attempt ${update.attempts + 1}).")
    throw AwaitingConfirmation()
  }

  class Factory : Job.Factory<UnifiedPushControlJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?) = UnifiedPushControlJob(parameters)
  }

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

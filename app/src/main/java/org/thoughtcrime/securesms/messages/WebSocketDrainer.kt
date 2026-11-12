package org.thoughtcrime.securesms.messages

import android.content.Context
import android.os.PowerManager
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import org.signal.core.util.ServiceUtil
import org.signal.core.util.Stopwatch
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JobTracker
import org.thoughtcrime.securesms.jobmanager.JobTracker.JobListener
import org.thoughtcrime.securesms.jobs.MarkerJob
import org.thoughtcrime.securesms.jobs.PushProcessMessageJob
import org.thoughtcrime.securesms.util.NetworkUtil
import org.thoughtcrime.securesms.util.PowerManagerCompat
import org.thoughtcrime.securesms.util.WakeLockUtil
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

enum class UnifiedPushFetchResult { DRAINED, RETRYABLE_FAILURE, REVOKED }

/**
 * Forces the websocket to stay open until all messages have been drained and processed or until a user-specified timeout has been hit.
 */
object WebSocketDrainer {
  private val TAG = Log.tag(WebSocketDrainer::class.java)

  private const val KEEP_ALIVE_TOKEN = "WebsocketStrategy"
  private const val WAKELOCK_TAG = "websocket-strategy"

  private val QUEUE_TIMEOUT = 30.seconds.inWholeMilliseconds

  private val NO_NETWORK_WEBSOCKET_TIMEOUT = 5.seconds.inWholeMilliseconds

  @JvmStatic
  @WorkerThread
  fun blockUntilDrainedAndProcessed(): Boolean {
    return blockUntilDrainedAndProcessed(1.minutes.inWholeMilliseconds)
  }

  /**
   * Blocks until the websocket is drained and all resulting processing jobs have finished, or until the [requestedWebsocketDrainTimeoutMs] has been hit.
   * Note: the timeout specified here is only for draining the websocket. There is currently a non-configurable timeout for waiting for the job queues.
   * Also, if it is discovered that it's unlikely that we'll be able to fetch messages (i.e. no network), then the timeout may be reduced.
   */
  @WorkerThread
  fun blockUntilDrainedAndProcessed(requestedWebsocketDrainTimeoutMs: Long, keepAliveToken: String = KEEP_ALIVE_TOKEN): Boolean {
    Log.d(TAG, "blockUntilDrainedAndProcessed() requestedWebsocketDrainTimeout: $requestedWebsocketDrainTimeoutMs ms")

    val context = AppDependencies.application
    val powerManager = ServiceUtil.getPowerManager(context)

    val doze = PowerManagerCompat.isDeviceIdleMode(powerManager)
    val network = NetworkUtil.isConnected(context)

    if (doze || !network) {
      Log.w(TAG, "We may be operating in a constrained environment. Doze: $doze Network: $network.")
    }

    val initialTimeout = if (!network) {
      Log.w(TAG, "Network is unavailable. Starting with reduced websocket timeout of $NO_NETWORK_WEBSOCKET_TIMEOUT ms.")
      NO_NETWORK_WEBSOCKET_TIMEOUT
    } else {
      requestedWebsocketDrainTimeoutMs
    }

    val wakeLock = WakeLockUtil.acquire(AppDependencies.application, PowerManager.PARTIAL_WAKE_LOCK, requestedWebsocketDrainTimeoutMs + QUEUE_TIMEOUT, WAKELOCK_TAG)

    return try {
      drainAndProcess(context, initialTimeout, requestedWebsocketDrainTimeoutMs, keepAliveToken)
    } finally {
      WakeLockUtil.release(wakeLock, WAKELOCK_TAG)
    }
  }

  /**
   * Hands an authorized UnifiedPush fetch to the ordinary receive observer. This method never starts
   * or keeps alive the websocket itself; only MessageRetrievalThread may consume the demand and
   * register its private token. The lease ends at handoff, before ordinary message processing drains.
   */
  @JvmStatic
  @WorkerThread
  fun blockUntilUnifiedPushFetchDrainedAndProcessed(
    authorization: UnifiedPushAccountTransition.FetchAuthorization,
    requestedWebsocketDrainTimeoutMs: Long,
  ): UnifiedPushFetchResult {
    val lease = UnifiedPushAccountTransition.acquireFetchLease(authorization)
      ?: return UnifiedPushFetchResult.REVOKED
    val admission = UnifiedPushAccountTransition.registerFetchAdmission(lease, authorization)
    if (admission == null) {
      lease.close()
      return UnifiedPushFetchResult.REVOKED
    }

    var wakeLock: PowerManager.WakeLock? = null
    var queueListener: QueueFindingJobListener? = null
    var decryptionListener: Runnable? = null
    var decryptionDone: CountDownLatch? = null
    var observer: IncomingMessageObserver? = null
    val jobMarkerListeners = mutableListOf<JobListener>()
    var result = UnifiedPushFetchResult.RETRYABLE_FAILURE

    try {
      val context = AppDependencies.application
      val jobManager = AppDependencies.jobManager
      val incomingMessageObserver = AppDependencies.incomingMessageObserver
      observer = incomingMessageObserver
      val queueFindingListener = QueueFindingJobListener()
      queueListener = queueFindingListener
      jobManager.addListener(
        { job: Job -> job.parameters.queue?.startsWith(PushProcessMessageJob.QUEUE_PREFIX) ?: false },
        queueFindingListener
      )

      val decryptionLatch = CountDownLatch(1)
      decryptionDone = decryptionLatch
      var decryptionsDrained = false
      val listener = object : Runnable {
        override fun run() {
          decryptionsDrained = true
          decryptionLatch.countDown()
          incomingMessageObserver.removeDecryptionDrainedListener(this)
        }
      }
      decryptionListener = listener
      incomingMessageObserver.addDecryptionDrainedListener(listener)
      admission.addTransitionCancellationWaiter(decryptionLatch)

      val powerManager = ServiceUtil.getPowerManager(context)
      val doze = PowerManagerCompat.isDeviceIdleMode(powerManager)
      val network = NetworkUtil.isConnected(context)
      if (doze || !network) {
        Log.w(TAG, "UnifiedPush fetch may be constrained. Doze: $doze Network: $network.")
      }
      val timeoutMs = if (network) requestedWebsocketDrainTimeoutMs else NO_NETWORK_WEBSOCKET_TIMEOUT
      val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
      wakeLock = WakeLockUtil.acquire(context, PowerManager.PARTIAL_WAKE_LOCK, timeoutMs + QUEUE_TIMEOUT, WAKELOCK_TAG)

      // Listeners are installed before waking the observer so a fast receive cannot be missed.
      incomingMessageObserver.notifyUnifiedPushFetchStateChanged()
      val (handoffState, wasInterrupted) = awaitFetchAdmission(admission, timeoutMs)
      if (admission.transitionRevoked || handoffState == UnifiedPushLifecycle.FetchAdmissionState.REVOKED) {
        result = UnifiedPushFetchResult.REVOKED
      } else if (handoffState != UnifiedPushLifecycle.FetchAdmissionState.ADMITTED || wasInterrupted) {
        result = UnifiedPushFetchResult.RETRYABLE_FAILURE
      } else {
        // The observer has registered its token outside the lifecycle monitor. A transition that
        // starts now can proceed; this waiter no longer owns credentialed socket admission.
        lease.close()

        if (admission.transitionRevoked) {
          result = UnifiedPushFetchResult.REVOKED
        } else if (!awaitSignalOrTransition(admission, decryptionLatch, deadline)) {
          result = if (admission.transitionRevoked) UnifiedPushFetchResult.REVOKED else UnifiedPushFetchResult.RETRYABLE_FAILURE
        } else if (!decryptionsDrained) {
          result = if (admission.transitionRevoked) UnifiedPushFetchResult.REVOKED else UnifiedPushFetchResult.RETRYABLE_FAILURE
        } else {
          val queues = queueFindingListener.getQueues()
          Log.d(TAG, "UnifiedPush fetch discovered ${queues.size} processing queue(s): $queues")
          var allQueuesDrained = true
          for (queue in queues) {
            if (admission.transitionRevoked) {
              allQueuesDrained = false
              break
            }

            val markerJob = MarkerJob(queue)
            val markerDone = CountDownLatch(1)
            val markerListener = object : JobListener {
              override fun onStateChanged(job: Job, jobState: JobTracker.JobState) {
                if (jobState.isComplete) markerDone.countDown()
              }
            }
            jobMarkerListeners += markerListener
            admission.addTransitionCancellationWaiter(markerDone)
            jobManager.addListener(markerJob.id, markerListener)
            try {
              jobManager.add(markerJob)
              if (!awaitSignalOrTransition(admission, markerDone, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(QUEUE_TIMEOUT))) {
                allQueuesDrained = false
                break
              }
            } finally {
              jobManager.removeListener(markerListener)
              jobMarkerListeners.remove(markerListener)
              admission.removeTransitionCancellationWaiter(markerDone)
            }
          }

          result = when {
            admission.transitionRevoked -> UnifiedPushFetchResult.REVOKED
            allQueuesDrained -> UnifiedPushFetchResult.DRAINED
            else -> UnifiedPushFetchResult.RETRYABLE_FAILURE
          }
        }
      }
    } catch (e: InterruptedException) {
      Log.w(TAG, "Interrupted while waiting for UnifiedPush fetch handoff/drain.", e)
      result = if (admission.transitionRevoked) UnifiedPushFetchResult.REVOKED else UnifiedPushFetchResult.RETRYABLE_FAILURE
    } finally {
      lease.close()
      observer?.let { incoming -> decryptionListener?.let(incoming::removeDecryptionDrainedListener) }
      jobMarkerListeners.forEach(AppDependencies.jobManager::removeListener)
      queueListener?.let(AppDependencies.jobManager::removeListener)
      decryptionDone?.let(admission::removeTransitionCancellationWaiter)
      UnifiedPushAccountTransition.finishFetchAdmission(admission)
      wakeLock?.let { WakeLockUtil.release(it, WAKELOCK_TAG) }
    }

    return result
  }

  private fun awaitFetchAdmission(
    admission: UnifiedPushLifecycle.FetchAdmission,
    timeoutMs: Long,
  ): Pair<UnifiedPushLifecycle.FetchAdmissionState, Boolean> {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    var interrupted = false

    while (true) {
      val remainingMs = TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(0))
      try {
        if (!admission.awaitHandoff(remainingMs)) {
          UnifiedPushLifecycle.timeoutFetchAdmission(admission)
        }
      } catch (e: InterruptedException) {
        interrupted = true
        UnifiedPushLifecycle.timeoutFetchAdmission(admission)
      }

      var state = admission.state
      if (state == UnifiedPushLifecycle.FetchAdmissionState.CLAIMED) {
        // Once the observer claims the request, keep its lease until token registration settles.
        // Interruption cannot let account publication overtake that local socket-start call.
        while (state == UnifiedPushLifecycle.FetchAdmissionState.CLAIMED) {
          try {
            admission.awaitHandoffCompletion()
          } catch (e: InterruptedException) {
            interrupted = true
          }
          state = admission.state
        }
      }

      if (interrupted) {
        Log.w(TAG, "Interrupted while awaiting UnifiedPush observer admission.")
      }
      return state to interrupted
    }
  }

  private fun awaitSignalOrTransition(
    admission: UnifiedPushLifecycle.FetchAdmission,
    signal: CountDownLatch,
    deadline: Long,
  ): Boolean {
    if (admission.transitionRevoked) return false
    val remainingNanos = (deadline - System.nanoTime()).coerceAtLeast(0)
    val signalled = try {
      signal.await(remainingNanos, TimeUnit.NANOSECONDS)
    } catch (e: InterruptedException) {
      Log.w(TAG, "Interrupted while waiting for UnifiedPush receive work.", e)
      false
    }
    return signalled && !admission.transitionRevoked
  }

  /**
   * This drains the socket and listens for any processing jobs that were enqueued during that time.
   *
   * For every job queue that got a processing job, we'll add a [MarkerJob] and wait for it to finish
   * so that we know the queue has been drained.
   */
  @WorkerThread
  private fun drainAndProcess(context: Context, initialTimeout: Long, fullTimeout: Long, keepAliveToken: String): Boolean {
    val stopwatch = Stopwatch("websocket-strategy")

    val jobManager = AppDependencies.jobManager
    val queueListener = QueueFindingJobListener()

    jobManager.addListener(
      { job: Job -> job.parameters.queue?.startsWith(PushProcessMessageJob.QUEUE_PREFIX) ?: false },
      queueListener
    )

    val successfullyDrained = blockUntilWebsocketDrained(context, initialTimeout, fullTimeout, keepAliveToken)
    if (!successfullyDrained) {
      return false
    }

    stopwatch.split("decryptions-drained")

    val processQueues: Set<String> = queueListener.getQueues()
    Log.d(TAG, "Discovered " + processQueues.size + " queue(s): " + processQueues)

    for (queue in processQueues) {
      val queueDrained = blockUntilJobQueueDrained(queue, QUEUE_TIMEOUT)
      if (!queueDrained) {
        return false
      }
    }

    stopwatch.split("process-drained")
    stopwatch.stop(TAG)
    return true
  }

  private fun blockUntilWebsocketDrained(context: Context, initialTimeoutMs: Long, fullTimeoutMs: Long, keepAliveToken: String): Boolean {
    try {
      val latch = CountDownLatch(1)
      var success = false
      AppDependencies.authWebSocket.registerKeepAliveToken(keepAliveToken)

      AppDependencies.incomingMessageObserver.addDecryptionDrainedListener(object : Runnable {
        override fun run() {
          success = true
          latch.countDown()
          AppDependencies.incomingMessageObserver.removeDecryptionDrainedListener(this)
        }
      })

      try {
        val startTime = System.currentTimeMillis()

        if (latch.await(initialTimeoutMs, TimeUnit.MILLISECONDS)) {
          return success
        }

        // If the first latch times out, check if we can wait longer if network came back
        if (initialTimeoutMs < fullTimeoutMs && NetworkUtil.isConnected(context)) {
          val remaining = ((startTime + fullTimeoutMs) - System.currentTimeMillis()).coerceAtLeast(0)
          Log.i(TAG, "Network returned, extending wait timeout for another $remaining ms.")
          if (latch.await(remaining, TimeUnit.MILLISECONDS)) {
            return success
          }
        }

        Log.w(TAG, "Hit timeout while waiting for decryptions to drain!")
        return success
      } catch (e: InterruptedException) {
        Log.w(TAG, "Interrupted!", e)
        return false
      }
    } finally {
      AppDependencies.authWebSocket.removeKeepAliveToken(keepAliveToken)
    }
  }

  private fun blockUntilJobQueueDrained(queue: String, timeoutMs: Long): Boolean {
    val startTime = System.currentTimeMillis()
    val jobManager = AppDependencies.jobManager
    val markerJob = MarkerJob(queue)
    val jobState = jobManager.runSynchronously(markerJob, timeoutMs)

    if (!jobState.isPresent) {
      Log.w(TAG, "Timed out waiting for $queue job(s) to finish!")
      return false
    }

    val endTime = System.currentTimeMillis()
    val duration = endTime - startTime

    Log.d(TAG, "Waited $duration ms for the $queue job(s) to finish.")
    return true
  }

  private class QueueFindingJobListener : JobListener {
    private val queues: MutableSet<String> = HashSet()

    @AnyThread
    override fun onStateChanged(job: Job, jobState: JobTracker.JobState) {
      synchronized(queues) {
        job.parameters.queue?.let { queue ->
          queues += queue
        }
      }
    }

    fun getQueues(): Set<String> {
      synchronized(queues) {
        return HashSet(queues)
      }
    }
  }
}

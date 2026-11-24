package org.thoughtcrime.securesms.jobmanager;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;


import org.signal.core.util.ThreadUtil;
import org.signal.core.util.logging.Log;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * Schedules future runs on an in-app handler. Intended to be used in combination with a persistent
 * {@link Scheduler} to improve responsiveness when the app is open.
 *
 * This should only schedule runs when all constraints are met. Because this only works when the
 * app is foregrounded, jobs that don't have their constraints met will be run when the relevant
 * {@link ConstraintObserver} is triggered.
 *
 * Similarly, this does not need to schedule retries with no delay, as this doesn't provide any
 * persistence, and other mechanisms will take care of that.
 */
class InAppScheduler implements Scheduler {

  private static final String TAG = Log.tag(InAppScheduler.class);

  private final JobManager jobManager;
  private final Handler     handler;
  private final LongSupplier clock;
  private @Nullable Runnable pendingWakeup;
  private long wakeupAt = Long.MAX_VALUE;

  InAppScheduler(@NonNull JobManager jobManager) {
    this(jobManager, createHandler(), SystemClock::uptimeMillis);
  }

  @VisibleForTesting
  InAppScheduler(@NonNull JobManager jobManager, @NonNull Handler handler, @NonNull LongSupplier clock) {
    this.jobManager = jobManager;
    this.handler    = handler;
    this.clock      = clock;
  }

  private static Handler createHandler() {
    HandlerThread handlerThread = new HandlerThread("InAppScheduler", ThreadUtil.PRIORITY_BACKGROUND_THREAD);
    handlerThread.start();
    return new Handler(handlerThread.getLooper());
  }

  @Override
  public synchronized void schedule(long delay, @NonNull List<Constraint> constraints) {
    if (delay > 0 && constraints.stream().allMatch(Constraint::isMet)) {
      long now = clock.getAsLong();
      long nextWakeupAt = delay > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delay;
      if (pendingWakeup != null && wakeupAt <= nextWakeupAt) {
        return;
      }

      if (pendingWakeup != null) {
        handler.removeCallbacks(pendingWakeup);
      }

      Log.i(TAG, "Scheduling a retry in " + delay + " ms.");
      wakeupAt = nextWakeupAt;
      pendingWakeup = new Runnable() {
        @Override
        public void run() {
          synchronized (InAppScheduler.this) {
            if (pendingWakeup != this) {
              return;
            }
            pendingWakeup = null;
            wakeupAt = Long.MAX_VALUE;
          }

          // Draining runnable jobs rearms the next future deadline from the job store.
          Log.i(TAG, "Triggering a job retry.");
          jobManager.wakeUp();
        }
      };
      handler.postDelayed(pendingWakeup, delay);
    }
  }
}

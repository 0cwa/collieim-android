package org.thoughtcrime.securesms.jobmanager;

import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.os.PersistableBundle;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;

import java.util.stream.Collectors;

import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.service.KeyCachingService;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

@RequiresApi(26)
public final class JobSchedulerScheduler implements Scheduler {

  private static final String TAG = Log.tag(JobSchedulerScheduler.class);

  static final String WAKEUP_AT         = "wakeup_at";
  static final String WAKEUP_GENERATION = "wakeup_generation";

  private final Application          application;
  private final Executor             executor;
  private final LongSupplier         clock;
  private final Map<Integer, String> finishedGenerations = new HashMap<>();

  JobSchedulerScheduler(@NonNull Application application) {
    this(application, SignalExecutors.BOUNDED, System::currentTimeMillis);
  }

  @VisibleForTesting
  JobSchedulerScheduler(@NonNull Application application, @NonNull Executor executor, @NonNull LongSupplier clock) {
    this.application = application;
    this.executor    = executor;
    this.clock       = clock;
  }

  @RequiresApi(26)
  @Override
  public void schedule(long delay, @NonNull List<Constraint> constraints) {
    long wakeupAt = deadlineFor(delay);
    executor.execute(() -> scheduleAt(wakeupAt, constraints));
  }

  /** Persist the successor before releasing the currently running system job's lifetime. */
  boolean scheduleSynchronously(long delay, @NonNull List<Constraint> constraints) {
    return scheduleAt(deadlineFor(delay), constraints);
  }

  private long deadlineFor(long delay) {
    long now = clock.getAsLong();
    return delay > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + Math.max(0, delay);
  }

  /** Keep one persistent wakeup per constraint group, always at its earliest requested deadline. */
  private synchronized boolean scheduleAt(long wakeupAt, @NonNull List<Constraint> constraints) {
    JobScheduler jobScheduler = application.getSystemService(JobScheduler.class);
    String constraintNames = constraints.stream()
                                        .map(Constraint::getJobSchedulerKeyPart).filter(Objects::nonNull)
                                        .sorted()
                                        .collect(Collectors.joining("-"));
    int jobId = constraintNames.hashCode();
    JobInfo pending = jobScheduler.getPendingJob(jobId);

    if (pending != null && !Objects.equals(finishedGenerations.get(jobId), pending.getExtras().getString(WAKEUP_GENERATION, ""))) {
      if (!pending.getExtras().containsKey(WAKEUP_AT)) {
        // An older app version left no absolute deadline. Wake once now, then rebuild future
        // wakeups from the durable job rows rather than guessing how much of its delay remains.
        wakeupAt = Math.min(wakeupAt, clock.getAsLong());
      } else if (pending.getExtras().getLong(WAKEUP_AT) <= wakeupAt) {
        // An overdue wakeup can still be waiting for an OS constraint (for example, network).
        // Only its completion callback may consume it; a later timer must not postpone it.
        return true;
      }
    }

    PersistableBundle extras = new PersistableBundle();
    extras.putLong(WAKEUP_AT, wakeupAt);
    extras.putString(WAKEUP_GENERATION, UUID.randomUUID().toString());

    Log.i(TAG, String.format(Locale.US, "JobScheduler enqueue of %s (%d)", constraintNames, jobId));

    JobInfo.Builder jobInfoBuilder = new JobInfo.Builder(jobId, new ComponentName(application, SystemService.class))
                                                .setMinimumLatency(Math.max(0, wakeupAt - clock.getAsLong()))
                                                .setPersisted(true)
                                                .setExtras(extras);

    for (Constraint constraint : constraints) {
      constraint.applyToJobInfo(jobInfoBuilder);
    }

    if (jobScheduler.schedule(jobInfoBuilder.build()) == JobScheduler.RESULT_SUCCESS) {
      finishedGenerations.remove(jobId);
      return true;
    } else {
      Log.w(TAG, "Unable to schedule persistent wakeup " + jobId);
      return false;
    }
  }

  /** jobFinished is asynchronous; ignore only that exact generation while rearming its successor. */
  synchronized void onWakeupFinished(int jobId, @NonNull PersistableBundle extras) {
    JobInfo pending = application.getSystemService(JobScheduler.class).getPendingJob(jobId);
    String generation = extras.getString(WAKEUP_GENERATION, "");
    if (pending != null && generation.equals(pending.getExtras().getString(WAKEUP_GENERATION, ""))) {
      finishedGenerations.put(jobId, generation);
    }
  }

  synchronized void onWakeupRetrying(int jobId, @NonNull PersistableBundle extras) {
    finishedGenerations.remove(jobId, extras.getString(WAKEUP_GENERATION, ""));
  }

  @RequiresApi(api = 26)
  public static class SystemService extends JobService {

    private final Map<Integer, ActiveWakeup> activeWakeups = new HashMap<>();
    private final Map<Integer, CompletedWakeup> completedWakeups = new HashMap<>();

    @Override
    public boolean onStartJob(JobParameters params) {
      if (KeyCachingService.isLocked()) {
        Log.d(TAG, "JobManager will not wake up: app is locked.");
        return false;
      }

      JobManager jobManager = AppDependencies.getJobManager();

      Log.i(TAG, "Waking due to job: " + params.getJobId());

      ActiveWakeup wakeup = new ActiveWakeup(jobManager, params);
      synchronized (activeWakeups) {
        ActiveWakeup previous = activeWakeups.put(params.getJobId(), wakeup);
        completedWakeups.remove(params.getJobId());
        if (previous != null) {
          previous.jobManager.removeOnEmptyQueueListener(previous);
        }
      }
      jobManager.addOnEmptyQueueListener(wakeup);

      jobManager.wakeUp();

      return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
      synchronized (activeWakeups) {
        String generation = params.getExtras().getString(WAKEUP_GENERATION, "");
        ActiveWakeup wakeup = activeWakeups.get(params.getJobId());
        if (wakeup != null && Objects.equals(wakeup.parameters.getExtras().getString(WAKEUP_GENERATION, ""), generation)) {
          if (wakeup.finishing) {
            return wakeup.needsReschedule != null && wakeup.needsReschedule;
          }
          activeWakeups.remove(params.getJobId());
          wakeup.jobManager.removeOnEmptyQueueListener(wakeup);
        } else {
          CompletedWakeup completed = completedWakeups.get(params.getJobId());
          if (completed != null && completed.generation.equals(generation)) {
            return completed.needsReschedule;
          }
        }
      }
      // Delayed rows can remain after an OS stop. Let Android retry this wakeup without
      // opening the encrypted job store here; replacing a running job ignores this value.
      return true;
    }

    private final class ActiveWakeup implements JobManager.EmptyQueueListener {
      private final JobManager    jobManager;
      private final JobParameters parameters;
      private boolean finishing;
      private Boolean needsReschedule;

      private ActiveWakeup(@NonNull JobManager jobManager, @NonNull JobParameters parameters) {
        this.jobManager = jobManager;
        this.parameters = parameters;
      }

      @Override
      public void onQueueEmpty() {
        synchronized (activeWakeups) {
          if (activeWakeups.get(parameters.getJobId()) != this || finishing) {
            return;
          }
          finishing = true;
          // Claim completion through synchronous rearming, so onStopJob cannot turn this
          // same generation into a framework retry while it is being consumed.
          jobManager.removeOnEmptyQueueListener(this);
          jobManager.onSystemJobFinished(parameters, retry -> {
            synchronized (activeWakeups) {
              needsReschedule = retry;
            }
            jobFinished(parameters, retry);
            synchronized (activeWakeups) {
              if (activeWakeups.get(parameters.getJobId()) == this) {
                activeWakeups.remove(parameters.getJobId());
                completedWakeups.put(parameters.getJobId(), new CompletedWakeup(parameters.getExtras().getString(WAKEUP_GENERATION, ""), retry));
              }
            }
          });
        }
      }
    }

    private static final class CompletedWakeup {
      private final String generation;
      private final boolean needsReschedule;

      private CompletedWakeup(@NonNull String generation, boolean needsReschedule) {
        this.generation = generation;
        this.needsReschedule = needsReschedule;
      }
    }
  }
}

package org.thoughtcrime.securesms.gcm;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.messages.WebSocketDrainer;
import org.thoughtcrime.securesms.messages.UnifiedPushFetchResult;
import org.thoughtcrime.securesms.service.KeyCachingService;
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition;
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition.FetchAuthorization;
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle;
import org.signal.core.util.AppForegroundObserver;
import org.signal.core.util.ServiceUtil;

/**
 * Pulls down messages. Used when we fail to pull down messages in {@link FcmReceiveService}.
 */
@RequiresApi(26)
public class FcmJobService extends JobService {

  private static final String TAG = Log.tag(FcmJobService.class);

  private static final int ORDINARY_ID = 1337;
  private static final int UNIFIED_PUSH_ID = 1338;
  private static final String EXTRA_UP_AUTHORIZED = "up_fetch_authorized";
  private static final String EXTRA_UP_PROCESS = "up_fetch_process";
  private static final String EXTRA_UP_GENERATION = "up_fetch_generation";
  private static final String EXTRA_UP_ACCOUNT = "up_fetch_account";
  private static final String EXTRA_UP_LOCAL_DEVICE = "up_fetch_local_device";
  private static final String EXTRA_UP_PUSH_DEVICE = "up_fetch_push_device";
  private static final String EXTRA_UP_INSTANCE = "up_fetch_instance";

  @RequiresApi(26)
  public static void schedule(@NonNull Context context) {
    schedule(context, null);
  }

  @RequiresApi(26)
  public static void schedule(@NonNull Context context, FetchAuthorization authorization) {
    int jobId = authorization == null ? ORDINARY_ID : UNIFIED_PUSH_ID;
    JobInfo.Builder jobInfoBuilder = new JobInfo.Builder(jobId, new ComponentName(context, FcmJobService.class))
                                                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                                                .setBackoffCriteria(0, JobInfo.BACKOFF_POLICY_LINEAR)
                                                .setPersisted(true);

    if (authorization != null) {
      PersistableBundle extras = new PersistableBundle();
      extras.putBoolean(EXTRA_UP_AUTHORIZED, true);
      extras.putString(EXTRA_UP_PROCESS, authorization.getProcessInstanceId());
      extras.putLong(EXTRA_UP_GENERATION, authorization.getGeneration());
      extras.putString(EXTRA_UP_ACCOUNT, authorization.getAccountId());
      extras.putInt(EXTRA_UP_LOCAL_DEVICE, authorization.getLocalDeviceId());
      extras.putInt(EXTRA_UP_PUSH_DEVICE, authorization.getPushDeviceId());
      extras.putString(EXTRA_UP_INSTANCE, authorization.getInstance());
      jobInfoBuilder.setExtras(extras);
    }

    ServiceUtil.getJobScheduler(context).schedule(jobInfoBuilder.build());
  }

  @Override
  public boolean onStartJob(JobParameters params) {
    Log.d(TAG, "onStartJob()");

    if (KeyCachingService.isLocked() || AppForegroundObserver.isForegrounded()) {
      Log.i(TAG, "App is locked or foregrounded. No need to run.");
      return false;
    }

    PersistableBundle extras = params.getExtras();
    FetchAuthorization authorization = null;
    if (extras != null && extras.getBoolean(EXTRA_UP_AUTHORIZED, false)) {
      if (!extras.containsKey(EXTRA_UP_PROCESS) || !extras.containsKey(EXTRA_UP_GENERATION) ||
          !extras.containsKey(EXTRA_UP_ACCOUNT) || !extras.containsKey(EXTRA_UP_LOCAL_DEVICE) ||
          !extras.containsKey(EXTRA_UP_PUSH_DEVICE) || !extras.containsKey(EXTRA_UP_INSTANCE)) {
        Log.w(TAG, "Dropping malformed UnifiedPush fetch authorization.");
        return false;
      }
      String processInstanceId = extras.getString(EXTRA_UP_PROCESS);
      String accountId = extras.getString(EXTRA_UP_ACCOUNT);
      String instance = extras.getString(EXTRA_UP_INSTANCE);
      if (processInstanceId == null || accountId == null || instance == null) {
        Log.w(TAG, "Dropping malformed UnifiedPush fetch authorization.");
        return false;
      }
      authorization = new FetchAuthorization(
          processInstanceId,
          extras.getLong(EXTRA_UP_GENERATION),
          accountId,
          extras.getInt(EXTRA_UP_LOCAL_DEVICE),
          extras.getInt(EXTRA_UP_PUSH_DEVICE),
          instance);
    }

    if (authorization != null) {
      // Reject stale persisted work before handing it to the executor. The worker still reacquires
      // the authorization at its actual fetch boundary because a transition may begin afterward.
      UnifiedPushLifecycle.Lease<UnifiedPushAccountTransition.AccountContext> validationLease =
          UnifiedPushAccountTransition.acquireFetchLease(authorization);
      if (validationLease == null) {
        Log.i(TAG, "Dropping stale UnifiedPush fallback before worker scheduling.");
        return false;
      }
      validationLease.close();
    }

    final FetchAuthorization operationAuthorization = authorization;

    SignalExecutors.UNBOUNDED.execute(() -> {
      if (operationAuthorization == null) {
        boolean success = WebSocketDrainer.blockUntilDrainedAndProcessed();
        if (success) {
          Log.i(TAG, "Successfully retrieved messages.");
          FcmFetchManager.cancelMayHaveMessagesNotification(this);
        } else {
          Log.w(TAG, "Failed to retrieve messages. Scheduling a retry.");
        }
        jobFinished(params, !success);
        return;
      }

      UnifiedPushFetchResult result = WebSocketDrainer.blockUntilUnifiedPushFetchDrainedAndProcessed(operationAuthorization, 1 * 60 * 1000L);
      if (result == UnifiedPushFetchResult.DRAINED) {
        Log.i(TAG, "Successfully retrieved messages through observer admission.");
        FcmFetchManager.cancelMayHaveMessagesNotification(this);
        jobFinished(params, false);
      } else if (result == UnifiedPushFetchResult.RETRYABLE_FAILURE) {
        UnifiedPushLifecycle.Lease<UnifiedPushAccountTransition.AccountContext> retryLease =
            UnifiedPushAccountTransition.acquireFetchLease(operationAuthorization);
        if (retryLease == null) {
          Log.i(TAG, "Dropping UnifiedPush retry after its account context changed.");
          jobFinished(params, false);
        } else {
          retryLease.close();
          Log.w(TAG, "Failed to retrieve messages. Scheduling a retry.");
          jobFinished(params, true);
        }
      } else {
        Log.i(TAG, "UnifiedPush fallback was revoked; no retry will be scheduled.");
        jobFinished(params, false);
      }
    });

    return true;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    Log.d(TAG, "onStopJob()");
    return true;
  }
}

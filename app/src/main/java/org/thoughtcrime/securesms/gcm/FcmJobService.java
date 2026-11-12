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

    UnifiedPushLifecycle.Lease<UnifiedPushAccountTransition.AccountContext> lease = null;
    PersistableBundle extras = params.getExtras();
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
      FetchAuthorization authorization = new FetchAuthorization(
          processInstanceId,
          extras.getLong(EXTRA_UP_GENERATION),
          accountId,
          extras.getInt(EXTRA_UP_LOCAL_DEVICE),
          extras.getInt(EXTRA_UP_PUSH_DEVICE),
          instance);
      lease = UnifiedPushAccountTransition.acquireFetchLease(authorization);
      if (lease == null) {
        Log.i(TAG, "Dropping UnifiedPush fallback after its account context changed.");
        return false;
      }
    }

    final UnifiedPushLifecycle.Lease<UnifiedPushAccountTransition.AccountContext> operationLease = lease;

    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        boolean success = WebSocketDrainer.blockUntilDrainedAndProcessed();

        if (success) {
          Log.i(TAG, "Successfully retrieved messages.");
          FcmFetchManager.cancelMayHaveMessagesNotification(this);
          jobFinished(params, false);
        } else {
          Log.w(TAG, "Failed to retrieve messages. Scheduling a retry.");
          jobFinished(params, true);
        }
      } finally {
        if (operationLease != null) operationLease.close();
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

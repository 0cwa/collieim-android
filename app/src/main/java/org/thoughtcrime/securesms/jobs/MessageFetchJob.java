package org.thoughtcrime.securesms.jobs;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JsonJobData;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.messages.WebSocketDrainer;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.service.GenericForegroundService;
import org.signal.core.util.AppForegroundObserver;
import org.signal.core.util.UnableToStartException;
import org.signal.network.exceptions.PushNetworkException;
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition;
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition.FetchAuthorization;
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fetches messages from the service, posting a foreground service if possible if the app is in the background.
 */
public final class MessageFetchJob extends BaseJob {

  public static final String KEY = "PushNotificationReceiveJob";
  public static final String UNIFIED_PUSH_KEY = "UnifiedPushMessageFetchJob";

  private static final String TAG = Log.tag(MessageFetchJob.class);
  private static final String EXTRA_UP_AUTHORIZED = "up_fetch_authorized";
  private static final String EXTRA_UP_PROCESS = "up_fetch_process";
  private static final String EXTRA_UP_GENERATION = "up_fetch_generation";
  private static final String EXTRA_UP_ACCOUNT = "up_fetch_account";
  private static final String EXTRA_UP_LOCAL_DEVICE = "up_fetch_local_device";
  private static final String EXTRA_UP_PUSH_DEVICE = "up_fetch_push_device";
  private static final String EXTRA_UP_INSTANCE = "up_fetch_instance";

  private final FetchAuthorization fetchAuthorization;
  private final boolean invalidFetchAuthorization;
  private final String factoryKey;

  public MessageFetchJob() {
    this(defaultParameters(), null, false, KEY);
  }

  private MessageFetchJob(Job.Parameters parameters) {
    this(parameters, null, false, KEY);
  }

  private MessageFetchJob(Job.Parameters parameters, FetchAuthorization authorization, boolean invalidAuthorization, String factoryKey) {
    super(parameters);
    this.fetchAuthorization = authorization;
    this.invalidFetchAuthorization = invalidAuthorization;
    this.factoryKey = factoryKey;
  }

  public static MessageFetchJob forUnifiedPush(FetchAuthorization authorization) {
    return new MessageFetchJob(defaultParameters(), authorization, false, UNIFIED_PUSH_KEY);
  }

  public static boolean isUnifiedPushSerializedData(@Nullable byte[] serializedData) {
    return JsonJobData.deserialize(serializedData).getBooleanOrDefault(EXTRA_UP_AUTHORIZED, false);
  }

  private static Job.Parameters defaultParameters() {
    return new Job.Parameters.Builder()
        .addConstraint(NetworkConstraint.KEY)
        .setQueue("__notification_received")
        .setMaxAttempts(3)
        .setMaxInstancesForFactory(1)
        .build();
  }

  @Override
  public @Nullable byte[] serialize() {
    if (fetchAuthorization == null && !invalidFetchAuthorization) return null;
    JsonJobData.Builder data = new JsonJobData.Builder().putBoolean(EXTRA_UP_AUTHORIZED, true);
    if (fetchAuthorization != null) {
      data.putString(EXTRA_UP_PROCESS, fetchAuthorization.getProcessInstanceId())
          .putLong(EXTRA_UP_GENERATION, fetchAuthorization.getGeneration())
          .putString(EXTRA_UP_ACCOUNT, fetchAuthorization.getAccountId())
          .putInt(EXTRA_UP_LOCAL_DEVICE, fetchAuthorization.getLocalDeviceId())
          .putInt(EXTRA_UP_PUSH_DEVICE, fetchAuthorization.getPushDeviceId())
          .putString(EXTRA_UP_INSTANCE, fetchAuthorization.getInstance());
    }
    return data.serialize();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return factoryKey;
  }

  @Override
  public void onRun() throws IOException {
    if (invalidFetchAuthorization) {
      Log.w(TAG, "Dropping malformed UnifiedPush fetch authorization.");
      return;
    }

    UnifiedPushLifecycle.Lease<UnifiedPushAccountTransition.AccountContext> lease =
        fetchAuthorization == null ? null : UnifiedPushAccountTransition.acquireFetchLease(fetchAuthorization);
    if (fetchAuthorization != null && lease == null) {
      Log.i(TAG, "Dropping UnifiedPush fallback after its account context changed.");
      return;
    }

    boolean success;
    try {
      try (ForegroundServiceController controller = ForegroundServiceController.create(context)) {
        success = controller.awaitResult();
      }

      if (success) {
        Log.i(TAG, "Successfully pulled messages.");
      } else {
        throw new PushNetworkException("Failed to pull messages.");
      }
    } finally {
      if (lease != null) lease.close();
    }
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception e) {
    Log.w(TAG, e);
    return e instanceof PushNetworkException;
  }

  @Override
  public void onFailure() {
  }

  /**
   * Controls whether or not the ForegroundService is started, based off of whether the application process is
   * in the foreground or background.
   */
  private static final class ForegroundServiceController implements AppForegroundObserver.Listener, AutoCloseable {

    private final @NonNull Context context;
    private final @NonNull AtomicReference<AutoCloseable> notificationController = new AtomicReference<>();

    private volatile boolean isRunning;

    private ForegroundServiceController(@NonNull Context context) {
      this.context = context;
    }

    @Override
    public void onForeground() {
      if (!isRunning) {
        return;
      }

      closeNotificationController();
    }

    @Override
    public void onBackground() {
      if (!isRunning) {
        return;
      }

      if (notificationController.get() != null) {
        Log.w(TAG, "Already displaying or displayed a foreground notification.");
        return;
      }

      if (Build.VERSION.SDK_INT < 31) {
        notificationController.set(GenericForegroundService.startForegroundTaskDelayed(context, context.getString(R.string.BackgroundMessageRetriever_checking_for_messages), 300, R.drawable.ic_notification));
      } else {
        try {
          notificationController.set(GenericForegroundService.startForegroundTask(context, context.getString(R.string.BackgroundMessageRetriever_checking_for_messages), NotificationChannels.getInstance().OTHER, R.drawable.ic_notification));
        } catch (UnableToStartException e) {
          Log.w(TAG, "Failed to start foreground service. Running without a foreground service.");
        }
      }
    }

    @Override
    public void close() {
      AppForegroundObserver.removeListener(this);
      closeNotificationController();
    }

    public boolean awaitResult() {
      isRunning = true;

      boolean success;
      try {
        success = WebSocketDrainer.blockUntilDrainedAndProcessed();
      } finally {
        isRunning = false;
      }

      return success;
    }

    private void closeNotificationController() {
      AutoCloseable controller = notificationController.get();
      if (controller == null) {
        return;
      }

      try {
        controller.close();
      } catch (Exception e) {
        Log.w(TAG, "Exception thrown while closing notification controller", e);
      }
    }

    static ForegroundServiceController create(@NonNull Context context) {
      ForegroundServiceController instance = new ForegroundServiceController(context);
      AppForegroundObserver.addListener(instance);

      return instance;
    }
  }

  public static final class Factory implements Job.Factory<MessageFetchJob> {
    @Override
    public @NonNull MessageFetchJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      return MessageFetchJob.create(parameters, serializedData, KEY);
    }
  }

  /** Factory for newly separated UnifiedPush fetches; the legacy factory remains registered for persisted jobs. */
  public static final class UnifiedPushFactory implements Job.Factory<MessageFetchJob> {
    @Override
    public @NonNull MessageFetchJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      return MessageFetchJob.create(parameters, serializedData, UNIFIED_PUSH_KEY);
    }
  }

  private static MessageFetchJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData, @NonNull String factoryKey) {
    JsonJobData data = JsonJobData.deserialize(serializedData);
    if (!data.getBooleanOrDefault(EXTRA_UP_AUTHORIZED, false)) {
      return new MessageFetchJob(parameters, null, factoryKey.equals(UNIFIED_PUSH_KEY), factoryKey);
    }

    if (!data.hasString(EXTRA_UP_PROCESS) || !data.hasLong(EXTRA_UP_GENERATION) ||
        !data.hasString(EXTRA_UP_ACCOUNT) || !data.hasInt(EXTRA_UP_LOCAL_DEVICE) ||
        !data.hasInt(EXTRA_UP_PUSH_DEVICE) || !data.hasString(EXTRA_UP_INSTANCE)) {
      return new MessageFetchJob(parameters, null, true, factoryKey);
    }

    FetchAuthorization authorization = new FetchAuthorization(
        data.getString(EXTRA_UP_PROCESS),
        data.getLong(EXTRA_UP_GENERATION),
        data.getString(EXTRA_UP_ACCOUNT),
        data.getInt(EXTRA_UP_LOCAL_DEVICE),
        data.getInt(EXTRA_UP_PUSH_DEVICE),
        data.getString(EXTRA_UP_INSTANCE));
    return new MessageFetchJob(parameters, authorization, false, factoryKey);
  }
}

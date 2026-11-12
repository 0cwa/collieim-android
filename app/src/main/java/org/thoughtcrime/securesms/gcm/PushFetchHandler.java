package org.thoughtcrime.securesms.gcm;

import android.content.Context;
import android.os.Build;
import org.signal.core.util.logging.Log;
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition.FetchAuthorization;
import org.thoughtcrime.securesms.util.SignalLocalMetrics;

/** Shared message-fetch scheduling for FCM and UnifiedPush. */
public final class PushFetchHandler {
  private static final String TAG = Log.tag(PushFetchHandler.class);
  private PushFetchHandler() {}

  public static void handleReceivedNotification(Context context, boolean highPriority) {
    handleReceivedNotification(context, highPriority, null);
  }

  /** Optional authorization is present only for a fetch originating from an accepted UnifiedPush event. */
  public static void handleReceivedNotification(Context context, boolean highPriority, FetchAuthorization authorization) {
    try {
      if (highPriority) {
        FcmFetchManager.startForegroundService(context);
      } else if (Build.VERSION.SDK_INT < 26) {
        FcmFetchManager.startBackgroundService(context);
      }
    } catch (Exception e) {
      Log.w(TAG, "Failed to start service.", e);
      SignalLocalMetrics.FcmServiceStartFailure.onFcmFailedToStart();
    }
    FcmFetchManager.enqueueFetch(context, highPriority, authorization);
  }
}

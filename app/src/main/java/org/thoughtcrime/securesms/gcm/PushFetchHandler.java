package org.thoughtcrime.securesms.gcm;

import android.content.Context;
import android.os.Build;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.util.SignalLocalMetrics;

/** Shared message-fetch scheduling for FCM and UnifiedPush. */
public final class PushFetchHandler {
  private static final String TAG = Log.tag(PushFetchHandler.class);
  private PushFetchHandler() {}

  public static void handleReceivedNotification(Context context, boolean highPriority) {
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
    FcmFetchManager.enqueueFetch(context, highPriority);
  }
}

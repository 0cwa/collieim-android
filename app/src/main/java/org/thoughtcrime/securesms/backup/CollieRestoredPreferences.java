package org.thoughtcrime.securesms.backup;

import android.content.Context;

import androidx.annotation.NonNull;

import org.signal.core.util.logging.Log;
import org.signal.storage.keyvalue.PlainTextKeyValueStore;
import org.thoughtcrime.securesms.ScreenLockController;
import org.thoughtcrime.securesms.apkupdate.ApkUpdateRefreshListener;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.notifications.NotificationChannels;

/**
 * Collie: applies restored V1 backup preferences to the running process.
 */
public final class CollieRestoredPreferences {

  private CollieRestoredPreferences() {}

  public static void applyAfterRestore(@NonNull Context context) {
    // The process was started with the pre-restore preferences. Apply the restored lock without
    // requiring a restart or treating the ongoing restore as a successful biometric unlock.
    ScreenLockController.enableAutoLock(PlainTextKeyValueStore.getBiometricScreenLockEnabled());

    if (NotificationChannels.supported()) {
      NotificationChannels.getInstance().updateMessageVibrate(SignalStore.settings().isMessageVibrateEnabled());
    }

    if (!SignalStore.molly().getLogEnabled()) {
      Log.disableAndWipeLogs();
    }

    if (SignalStore.molly().getUpdateApkEnabled()) {
      ApkUpdateRefreshListener.scheduleIfAllowed(context);
    }
  }
}

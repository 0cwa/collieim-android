package org.thoughtcrime.securesms.logsubmit;

import android.content.Context;

import androidx.annotation.NonNull;

import org.signal.core.util.logging.AndroidLogger;

public class LogSectionLogcat implements LogSection {

  /** Silences the View tag, which some OEM builds spam once per draw pass, while leaving every other tag at verbose. */
  private static final String FILTER_SPEC = "View:S *:V";

  @Override
  public @NonNull String getTitle() {
    return "LOGCAT";
  }

  @Override
  public @NonNull CharSequence getContent(@NonNull Context context) {
<<<<<<< MOLLY
    CharSequence logs = AndroidLogger.getLogcatDump();
    return logs != null ? logs : "Failed to get logcat dump";
  }
=======
    try {
      final Process        process        = Runtime.getRuntime().exec("logcat -d " + FILTER_SPEC);
      final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
      final StringBuilder  log            = new StringBuilder();
      final String         separator      = System.lineSeparator();
>>>>>>> SIGNAL

  @Override
  @NonNull public CharSequence getContentLocked(@NonNull Context context) {
    return getContent(context);
  }
}

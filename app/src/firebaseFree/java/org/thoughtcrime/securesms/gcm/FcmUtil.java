package org.thoughtcrime.securesms.gcm;

import android.content.Context;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/** Firebase is absent from this build. No Firebase installation is created here. */
public final class FcmUtil {
  private FcmUtil() {}
  public static boolean isSupported() { return false; }
  public static Optional<String> getToken(Context context) { return Optional.empty(); }
  public static Optional<String> getTokenWithoutAvailCheck(Context context) { return Optional.empty(); }
  public static void deleteFirebaseInstallationId(Context context) throws IOException {}
  public static void deleteInstallationForPrivacySettings() throws InterruptedException, ExecutionException {}
}

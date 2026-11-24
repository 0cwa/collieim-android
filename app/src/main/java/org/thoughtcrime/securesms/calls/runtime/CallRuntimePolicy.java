/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.runtime;

import org.thoughtcrime.securesms.calls.permissions.CallPermissionSettings;
import org.thoughtcrime.securesms.calls.permissions.CallPermissions;
import org.thoughtcrime.securesms.keyvalue.SignalStore;

/** Startup scheduling only. All operation authorization remains in CallPermissions. */
public final class CallRuntimePolicy {
  private CallRuntimePolicy() {}

  public static boolean isOnDemand() {
    try {
      return SignalStore.callPermissions().isOnDemandCallingEnabled();
    } catch (Exception unavailable) {
      // An unreadable preference must not accidentally enable background native startup.
      return true;
    }
  }

  public static boolean shouldInitializeAtStartup() {
    CallPermissionSettings settings = CallPermissions.settings();
    return settings.getAvailable() && !settings.getAllCallingDisabled() && !isOnDemand();
  }

  public static CallRuntime.Mode mode(boolean allowColdStart) {
    return allowColdStart || !isOnDemand() ? CallRuntime.Mode.START_IF_AUTHORIZED : CallRuntime.Mode.IF_RUNNING;
  }
}

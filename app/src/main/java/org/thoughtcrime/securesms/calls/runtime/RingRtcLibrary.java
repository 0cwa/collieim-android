/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.runtime;

import android.content.Context;

import org.signal.remoteconfig.RemoteConfig;
import org.signal.ringrtc.CallManager;
import org.thoughtcrime.securesms.ringrtc.RingRtcLogger;

import java.util.HashMap;
import java.util.Map;

/** The only production entry to CallManager.initialize, which is once-only for this native pin. */
public final class RingRtcLibrary {
  private static volatile boolean initialized;
  private static Throwable failure;

  private RingRtcLibrary() {}

  /** Passive: referencing this class does not initialize CallManager or load its shared library. */
  public static boolean isInitialized() {
    return initialized;
  }

  public static synchronized void initialize(Context context) {
    if (initialized) return;
    if (failure != null) throw new IllegalStateException("Calling initialization previously failed", failure);

    try {
      Map<String, String> fieldTrials = new HashMap<>();
      if (RemoteConfig.callingFieldTrialAnyAddressPortsKillSwitch()) {
        fieldTrials.put("RingRTC-AnyAddressPortsKillSwitch", "Enabled");
      }
      CallManager.initialize(context, new RingRtcLogger(), fieldTrials);
      initialized = true;
    } catch (RuntimeException | LinkageError | AssertionError error) {
      failure = error;
      throw error;
    }
  }
}

/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.runtime;

import java.util.function.LongSupplier;

/** Preserves signaling freshness across runtime startup and queueing without using wall time. */
public final class CallMessageAge {
  private final long ageAtReceiptMillis;
  private final long receivedAt;
  private final LongSupplier elapsedRealtime;

  private CallMessageAge(long ageAtReceiptMillis, LongSupplier elapsedRealtime) {
    this.ageAtReceiptMillis = ageAtReceiptMillis < 0 ? Long.MAX_VALUE : ageAtReceiptMillis;
    this.elapsedRealtime = elapsedRealtime;
    this.receivedAt = elapsedRealtime.getAsLong();
  }

  public static CallMessageAge fromMillis(long ageMillis, LongSupplier elapsedRealtime) {
    return new CallMessageAge(ageMillis, elapsedRealtime);
  }

  public static CallMessageAge fromSeconds(long ageSeconds, LongSupplier elapsedRealtime) {
    return fromMillis(ageSeconds < 0 || ageSeconds > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE : ageSeconds * 1000, elapsedRealtime);
  }

  public static CallMessageAge fromTimestamps(long received, long delivered, LongSupplier elapsedRealtime) {
    return fromMillis(received <= 0 || delivered < received ? Long.MAX_VALUE : delivered - received, elapsedRealtime);
  }

  public long seconds() {
    long now = elapsedRealtime.getAsLong();
    if (receivedAt < 0 || now < receivedAt) return Long.MAX_VALUE / 1000;
    long elapsed = now - receivedAt;
    long total = ageAtReceiptMillis > Long.MAX_VALUE - elapsed ? Long.MAX_VALUE : ageAtReceiptMillis + elapsed;
    return total / 1000;
  }
}

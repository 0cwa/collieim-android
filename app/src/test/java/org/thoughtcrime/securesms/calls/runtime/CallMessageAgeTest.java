/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.runtime;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.assertEquals;

public class CallMessageAgeTest {
  private final AtomicLong clock = new AtomicLong(1000);

  @Test
  public void startupDelayConsumesNativeOfferFreshness() {
    CallMessageAge age = CallMessageAge.fromTimestamps(10000, 69000, clock::get);
    clock.addAndGet(2000);
    assertEquals(61, age.seconds());
  }

  @Test
  public void fractionalAgeIsNotLostAcrossQueueBoundaries() {
    CallMessageAge age = CallMessageAge.fromMillis(59900, clock::get);
    clock.addAndGet(200);
    assertEquals(60, age.seconds());
  }

  @Test
  public void repeatedReadsDoNotResetOrDoubleCountDelay() {
    CallMessageAge age = CallMessageAge.fromMillis(1000, clock::get);
    clock.addAndGet(2000);
    assertEquals(3, age.seconds());
    assertEquals(3, age.seconds());
    clock.addAndGet(1000);
    assertEquals(4, age.seconds());
  }

  @Test
  public void legacySecondsAreAgedToo() {
    CallMessageAge age = CallMessageAge.fromSeconds(59, clock::get);
    clock.addAndGet(2000);
    assertEquals(61, age.seconds());
  }

  @Test
  public void clockReversalCannotMakeDataFreshAgain() {
    CallMessageAge age = CallMessageAge.fromMillis(0, clock::get);
    clock.set(999);
    assertEquals(Long.MAX_VALUE / 1000, age.seconds());
  }

  @Test
  public void ageAdditionSaturatesInsteadOfOverflowing() {
    CallMessageAge age = CallMessageAge.fromMillis(Long.MAX_VALUE - 1, clock::get);
    clock.addAndGet(2000);
    assertEquals(Long.MAX_VALUE / 1000, age.seconds());
  }

  @Test
  public void invalidOrOversizedSecondsFailClosed() {
    assertEquals(Long.MAX_VALUE / 1000, CallMessageAge.fromSeconds(Long.MAX_VALUE, clock::get).seconds());
    assertEquals(Long.MAX_VALUE / 1000, CallMessageAge.fromSeconds(-1, clock::get).seconds());
    assertEquals(Long.MAX_VALUE / 1000, CallMessageAge.fromMillis(-1, clock::get).seconds());
  }

  @Test
  public void invalidServerTimestampOrderFailsClosed() {
    assertEquals(Long.MAX_VALUE / 1000, CallMessageAge.fromTimestamps(0, 100, clock::get).seconds());
    assertEquals(Long.MAX_VALUE / 1000, CallMessageAge.fromTimestamps(101, 100, clock::get).seconds());
    assertEquals(0, CallMessageAge.fromTimestamps(100, 100, clock::get).seconds());
  }
}

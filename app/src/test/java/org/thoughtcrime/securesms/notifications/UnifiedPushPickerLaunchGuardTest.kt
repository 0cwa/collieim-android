package org.thoughtcrime.securesms.notifications

import io.reactivex.rxjava3.subjects.BehaviorSubject
import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedPushPickerLaunchGuardTest {
  @Test fun `replayed vitals check cannot relaunch an outstanding picker after recreation`() {
    val checks = BehaviorSubject.createDefault(Unit)
    val first = UnifiedPushPickerLaunchGuard()
    var launches = 0
    val oldSubscription = checks.subscribe { if (first.tryLaunch()) launches++ }
    assertEquals(1, launches)

    val recreated = UnifiedPushPickerLaunchGuard()
    recreated.restore(first.outstanding, first.promptHandled)
    oldSubscription.dispose()
    val newSubscription = checks.subscribe { if (recreated.tryLaunch()) launches++ }
    assertEquals(1, launches)

    recreated.onResult() // Includes cancellation.
    checks.onNext(Unit)
    assertEquals(1, launches)
    recreated.onOtherVitalsState()
    checks.onNext(Unit)
    assertEquals(2, launches)
    newSubscription.dispose()
  }
  @Test fun `result before replay does not relaunch the same prompt`() {
    val first = UnifiedPushPickerLaunchGuard()
    assertEquals(true, first.tryLaunch())
    val recreated = UnifiedPushPickerLaunchGuard()
    recreated.restore(first.outstanding, first.promptHandled)
    recreated.onResult()
    val checks = BehaviorSubject.createDefault(Unit)
    var launches = 0
    val subscription = checks.subscribe { if (recreated.tryLaunch()) launches++ }
    assertEquals(0, launches)
    recreated.onOtherVitalsState()
    checks.onNext(Unit)
    assertEquals(1, launches)
    subscription.dispose()
  }

}

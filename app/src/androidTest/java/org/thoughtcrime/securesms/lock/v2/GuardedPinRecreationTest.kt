/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.lock.v2

import android.app.Activity
import android.view.View
import android.widget.EditText
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.Navigation
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testing.DisableAnimationsRule
import org.thoughtcrime.securesms.testing.SignalActivityRule

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class GuardedPinRecreationTest {
  @get:Rule(order = 0)
  val animations = DisableAnimationsRule()

  @get:Rule(order = 1)
  val harness = SignalActivityRule(othersCount = 1, networkEnabled = false)

  @Test
  fun verificationReopensTheOriginalConfirmationAndRetainsItsSaveOwner() = withGuardedPinFlow { scenario ->
    authorize()
    openConfirmation(scenario)
    var original: ConfirmSvrPinViewModel? = null
    scenario.onActivity { original = confirmationModel(it) }

    scenario.recreate()
    assertVerificationIsTheOnlyActiveScreen(scenario)
    authorize()

    scenario.onActivity { activity ->
      val nav = Navigation.findNavController(activity, R.id.nav_host_fragment)
      assertEquals(R.id.confirmKbsPinFragment, nav.currentDestination?.id)
      val args = ConfirmSvrPinFragmentArgs.fromBundle(requireNotNull(nav.currentBackStackEntry?.arguments))
      assertEquals(NEW_PIN, args.userEntry.toString())
      assertSame(original, confirmationModel(activity))
    }
  }

  @Test
  fun cancelDuringRecreationFinishesWithoutStartingThePinFlow() = withGuardedPinFlow { scenario ->
    authorize()
    openConfirmation(scenario)

    scenario.recreate()
    assertVerificationIsTheOnlyActiveScreen(scenario)
    onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click())

    assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
  }

  private fun openConfirmation(scenario: ActivityScenario<CreateSvrPinActivity>) {
    scenario.onActivity { activity ->
      val action = CreateSvrPinFragmentDirections.actionConfirmPin().apply {
        userEntry = SvrPin.from(NEW_PIN)
        isPinChange = true
      }
      Navigation.findNavController(activity, R.id.nav_host_fragment).navigate(action)
    }
  }

  private fun confirmationModel(activity: CreateSvrPinActivity): ConfirmSvrPinViewModel {
    val owner = requireNotNull(activity.confirmationViewModelOwner())
    val factory = ConfirmSvrPinViewModel.Factory(SvrPin.from(NEW_PIN), PinKeyboardType.NUMERIC)
    return ViewModelProvider(owner, factory)[ConfirmSvrPinViewModel::class.java]
  }

  private fun assertVerificationIsTheOnlyActiveScreen(scenario: ActivityScenario<CreateSvrPinActivity>) {
    scenario.onActivity { activity ->
      assertNull(activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment))
      assertNull(activity.findViewById<View>(R.id.edit_kbs_pin_input))
    }
  }

  private fun authorize() {
    onView(isAssignableFrom(EditText::class.java)).inRoot(isDialog()).perform(replaceText(EXISTING_PIN))
    onView(withText(android.R.string.ok)).inRoot(isDialog()).perform(click())
  }

  private fun withGuardedPinFlow(block: (ActivityScenario<CreateSvrPinActivity>) -> Unit) {
    val previousGuard = SignalStore.pin.recoveryPinGuardEnabled
    val previousPin = SignalStore.svr.pin
    val previousLock = SignalStore.svr.isRegistrationLockEnabled
    SignalStore.svr.setPin(EXISTING_PIN)
    SignalStore.pin.recoveryPinGuardEnabled = true
    try {
      val intent = CreateSvrPinActivity.getIntentForPinChangeFromSettings(harness.context)
      ActivityScenario.launchActivityForResult<CreateSvrPinActivity>(intent).use(block)
    } finally {
      if (previousPin == null) {
        SignalStore.svr.clearRegistrationLockAndPin()
      } else {
        SignalStore.svr.setPin(previousPin)
      }
      SignalStore.svr.isRegistrationLockEnabled = previousLock
      SignalStore.pin.recoveryPinGuardEnabled = previousGuard
    }
  }

  companion object {
    private const val EXISTING_PIN = "14785"
    private const val NEW_PIN = "745902"
  }
}

package im.molly.unifiedpush.enrollment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import im.molly.unifiedpush.components.settings.app.notifications.RelayDisclosureDialogs
import im.molly.unifiedpush.control.ControlState
import im.molly.unifiedpush.model.MollySocket
import im.molly.unifiedpush.model.MollySocketDevice
import im.molly.unifiedpush.receiver.EncryptedPush
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testing.DisableAnimationsRule
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.testing.actions.scrollToDescendant
import org.thoughtcrime.securesms.util.TextSecurePreferences
import java.io.File
import java.io.FileOutputStream

/** Real settings routes with synthetic account data only; `ep_screenshot` optionally saves each changed screen. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class RelayDisclosureUiTest {
  @get:Rule val harness = SignalActivityRule(othersCount = 0, networkEnabled = false)
  @get:Rule val animations = DisableAnimationsRule()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context = instrumentation.targetContext
  private val values get() = SignalStore.unifiedpush
  private val screenshotPrefix = InstrumentationRegistry.getArguments().getString("ep_screenshot")
  private lateinit var export: File
  private lateinit var exportedBytes: ByteArray
  private lateinit var controlBefore: ControlState
  private val device = MollySocketDevice(2, "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL")

  @Before
  fun seedManualEnrollment() {
    values.enabled = true
    values.airGapped = true
    values.mollySocketUrl = null
    values.vapidPublicKey = "v".repeat(87)
    values.device = device
    values.setEndpointRegistration(
      "https://push.example.invalid/synthetic-endpoint",
      "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
      "BTBZMqHH6r4Tts7J_aSIgg",
      "default",
    )
    values.mutateControl { ControlState() }
    values.replayState = EncryptedPush.ReplayState(System.currentTimeMillis() / 1000, emptyList()).serialize()
    export = EnrollmentExporter.exportWithEpoch(context, null, ExportMode.NEW_INTENT, isDistributorActive = { true }).file
    exportedBytes = export.readBytes()
    controlBefore = values.controlState
    assertNotNull("Synthetic registration must be ready for disclosure tests", RelayDisclosure.capture(context))
  }

  @After
  fun removeSyntheticExport() {
    EnrollmentExporter.removeFiles(context)
  }

  @Test
  fun knownAndManualRelayDialogsCanBeCancelled() = withSettings { scenario ->
    screenshot("01-settings")
    val relayUrl = "https://relay.example.invalid:9443/notifications"
    var confirmed = 0
    scenario.onActivity { activity ->
      RelayDisclosureDialogs.showRelay(activity, MollySocket.WebServer("v".repeat(87), relayUrl)) { confirmed++ }
    }
    onView(withId(android.R.id.message)).check(matches(withText(containsString(relayUrl))))
    assertTrustVisible()
    screenshot("02-web-relay-trust")
    cancelDialog()
    assertEquals(0, confirmed)

    scenario.onActivity { activity ->
      RelayDisclosureDialogs.showRelay(activity, MollySocket.AirGapped("v".repeat(87))) { confirmed++ }
    }
    onView(withId(android.R.id.message)).check(matches(withText(containsString(context.getString(R.string.RelayDisclosureDialog__manual_destination)))))
    onView(withId(android.R.id.message)).check(matches(withText(not(containsString(relayUrl)))))
    assertTrustVisible()
    screenshot("03-manual-relay-trust")
    cancelDialog()
    assertEquals(0, confirmed)
    assertAuthorizationUnchanged()
  }

  @Test
  fun actualCopyAndAllExportModesRequireConfirmation() = withSettings { scenario ->
    val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))
    var originalClip: ClipData? = null
    scenario.onActivity {
      originalClip = clipboard.primaryClip
      clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "clipboard unchanged"))
    }
    try {
      clickSetting(R.string.UnifiedPushSettingsFragment__server_parameters)
      assertTrustVisible()
      screenshot("04-clipboard-trust")
      cancelDialog()
      scenario.onActivity { assertEquals("clipboard unchanged", clipboard.primaryClip!!.getItemAt(0).text.toString()) }
      assertAuthorizationUnchanged()

      for (mode in ExportMode.entries) {
        clickSetting(R.string.UnifiedPushSettingsFragment__export_enrollment)
        val choice = when (mode) {
          ExportMode.NEW_INTENT -> R.string.UnifiedPushSettingsFragment__export_new_intent
          ExportMode.RECOVERY -> R.string.UnifiedPushSettingsFragment__export_recovery_intent
          ExportMode.RERENDER -> R.string.UnifiedPushSettingsFragment__export_rerender_intent
        }
        onView(withText(choice)).perform(click())
        if (mode == ExportMode.RECOVERY) onView(withText(R.string.UnifiedPushSettingsFragment__export_continue)).perform(click())
        if (mode == ExportMode.RERENDER) {
          onView(withText(context.getString(R.string.UnifiedPushSettingsFragment__saved_export_label, 1, 1L))).perform(click())
        } else {
          onView(withText(R.string.UnifiedPushSettingsFragment__export_enrollment_export)).perform(click())
        }
        assertTrustVisible()
        onView(withId(android.R.id.message)).check(matches(withText(containsString(context.getString(R.string.RelayDisclosureDialog__export_message)))))
        screenshot("05-export-trust-${mode.name.lowercase()}")
        cancelDialog()
        assertAuthorizationUnchanged()
      }

      // A configuration change after opening confirmation cannot authorize a different clipboard payload.
      clickSetting(R.string.UnifiedPushSettingsFragment__server_parameters)
      values.endpoint = "https://push.example.invalid/replaced-endpoint"
      onView(withText(R.string.RelayDisclosureDialog__copy_confirm)).perform(click())
      scenario.onActivity { assertEquals("clipboard unchanged", clipboard.primaryClip!!.getItemAt(0).text.toString()) }
      assertAuthorizationUnchanged()
    } finally {
      scenario.onActivity {
        originalClip?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
      }
    }
  }

  @Test
  fun guidanceDistinguishesLocalCleanupFromSignalUnlink() = withSettings {
    scrollToDescendant(R.id.recycler, withText(R.string.UnifiedPushSettingsFragment__relay_trust_summary))
    onView(withText(R.string.UnifiedPushSettingsFragment__relay_trust_summary)).check(matches(isDisplayed()))
    screenshot("06-settings-guidance")
    clickSetting(R.string.UnifiedPushSettingsFragment__remove_relay_access_title)
    onView(withText(R.string.UnifiedPushSettingsFragment__remove_relay_access_message)).check(matches(isDisplayed()))
    screenshot("07-remove-relay-access")
    onView(withText(android.R.string.ok)).perform(click())
    assertAuthorizationUnchanged()
  }

  private fun withSettings(block: (ActivityScenario<AppSettingsActivity>) -> Unit) {
    val secureBefore = TextSecurePreferences.isScreenSecurityEnabled(context)
    TextSecurePreferences.setScreenSecurityEnabled(context, false)
    try {
      ActivityScenario.launch<AppSettingsActivity>(AppSettingsActivity.notifications(context)).use { scenario ->
        var windowWasSecure = false
        scenario.onActivity { activity ->
          windowWasSecure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
          activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
          (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment)
            .navController.navigate(R.id.notificationUnifiedPushFragment)
        }
        try {
          instrumentation.waitForIdleSync()
          block(scenario)
        } finally {
          TextSecurePreferences.setScreenSecurityEnabled(context, secureBefore)
          scenario.onActivity { activity ->
            if (secureBefore || windowWasSecure) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
          }
        }
      }
    } finally {
      TextSecurePreferences.setScreenSecurityEnabled(context, secureBefore)
    }
  }

  private fun clickSetting(title: Int) {
    scrollToDescendant(R.id.recycler, withText(title))
    onView(withText(title)).perform(click())
  }

  private fun assertTrustVisible() {
    onView(withId(android.R.id.message)).check(matches(withText(containsString(context.getString(R.string.RelayDisclosureDialog__trust_message)))))
    onView(withText(android.R.string.cancel)).check(matches(isDisplayed()))
  }

  private fun cancelDialog() {
    onView(withText(android.R.string.cancel)).perform(click())
  }

  private fun assertAuthorizationUnchanged() {
    assertEquals(device, values.device)
    assertEquals(controlBefore, values.controlState)
    assertArrayEquals(exportedBytes, export.readBytes())
  }

  private fun screenshot(suffix: String) {
    val prefix = screenshotPrefix ?: return
    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
      FileOutputStream(File(context.getExternalFilesDir(null), "$prefix-$suffix.png")).use {
        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
      }
    } finally {
      bitmap.recycle()
    }
  }
}

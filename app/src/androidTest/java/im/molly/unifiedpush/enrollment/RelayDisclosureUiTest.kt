package im.molly.unifiedpush.enrollment

import com.fasterxml.jackson.databind.ObjectMapper
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import im.molly.unifiedpush.components.settings.app.notifications.RelayDisclosureDialogs
import im.molly.unifiedpush.components.settings.app.notifications.UnifiedPushSettingsFragment
import im.molly.unifiedpush.control.ControlState
import im.molly.unifiedpush.control.ReceiverControlState
import im.molly.unifiedpush.control.Target
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
  private var previousReceiverState: String? = null
  private val device = MollySocketDevice(2, "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL")

  @Before
  fun seedManualEnrollment() {
    previousReceiverState = values.v4ReceiverState
    values.v4ReceiverState = null
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
    values.v4ReceiverState = previousReceiverState
  }

  @Test
  fun addReceiverConsentShowsFourRowsAndExactDeviceWording() = withSettings { scenario ->
    val offer = ObjectMapper().readTree(
      checkNotNull(javaClass.getResourceAsStream("/colliesocket-pairing-v1/fixtures/sealed-vectors.json")).use { it.readBytes() },
    )[0]["offer"].deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("intent", "add_receiver")
    val url = "https://push.example.test/pairing/v1/submit"
    val wrapper = """{"collie_pairing_transport":1,"submit_url":"$url","bridge_device_id":2,"offer":$offer}"""
    scenario.onActivity { activity ->
      val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
      (host.childFragmentManager.primaryNavigationFragment as UnifiedPushSettingsFragment).showPairingConsentFixtureForTest(wrapper)
    }
    val expectedTitle = "Add this device to “Collie Lab”?"
    val expectedMessage = "Push server: https://push.example.test\nDistributor: Collie Pack\nEncrypted request will be sent to: $url\n\nAdds this device to your existing notification relay without linking another Signal device."
    onView(withText(expectedTitle)).check(matches(isDisplayed()))
    onView(withId(android.R.id.message)).check(matches(withText(expectedMessage)))
    onView(withText(android.R.string.cancel)).check(matches(isDisplayed()))
    onView(withText(R.string.PairingV1__add_receiver_consent_confirm)).check(matches(isDisplayed()))
    screenshot("08-add-receiver-consent")
    cancelDialog()
  }

  @Test
  fun confirmedV4ReceiverAppearsInLinkedRelaySettings() {
    val fixture = ObjectMapper().readTree(
      checkNotNull(javaClass.getResourceAsStream("/colliesocket-encrypted-push-v4/fixtures/receiver.json")).use { it.readBytes() },
    )
    val sub = fixture["subscription"]
    val previousDeviceId = SignalStore.account.deviceId
    SignalStore.account.deviceId = 3
    val instance = sub["instance"].asText()
    val endpoint = sub["endpoint"].asText()
    try {
      values.v4ReceiverState = ReceiverControlState(
        receiverId = fixture["receiver_id"].asText(), importId = fixture["request_id"].asText(),
        accountId = SignalStore.account.aci.toString(), bridgeDeviceId = 2, instance = instance,
        hostLabel = "Collie Lab", controlKey = fixture["control_key"].asText(), epoch = 1,
        target = Target(endpoint, sub["p256dh"].asText(), sub["auth"].asText()), confirmed = true,
      ).serialize()
      values.setPairingEndpointRegistration(
        endpoint, sub["p256dh"].asText(), sub["auth"].asText(),
        org.thoughtcrime.securesms.keyvalue.UnifiedPushValues.PairingEndpointBinding(
          instance = instance, distributorId = "org.collie.pack", vapidPublicKey = values.vapidPublicKey.orEmpty(),
          providerOrigin = "https://push.example.test", endpointOrigin = checkNotNull(PairingV1.canonicalOrigin(endpoint)),
          sessionId = fixture["request_id"].asText(), expiresAt = System.currentTimeMillis() / 1000 + 600,
          accountId = SignalStore.account.aci.toString(), deviceId = SignalStore.account.deviceId,
          hostLabel = "Collie Lab", importId = fixture["request_id"].asText(),
        ),
      )
      withSettings {
        onView(withText(R.string.UnifiedPushSettingsFragment__status)).check(matches(isDisplayed()))
        onView(withText(R.string.PairingV1__notification_relay)).check(matches(isDisplayed()))
        onView(withText("Collie Lab · https://push.example.test")).check(matches(isDisplayed()))
        onView(withText(R.string.UnifiedPushSettingsFragment__distributor_app)).check(matches(isDisplayed()))
        onView(withText(R.string.UnifiedPushSettingsFragment__test_push)).check(matches(isDisplayed()))
          .check(matches(isEnabled()))
        onView(withText(R.string.PairingV1__waiting_confirmation)).check(doesNotExist())
        screenshot("09-v4-linked-settings")
      }
    } finally {
      values.v4ReceiverState = null
      SignalStore.account.deviceId = previousDeviceId
    }
  }

  @Test
  fun knownAndManualRelayDialogsCanBeCancelled() = withSettings { scenario ->
    screenshot("01-settings")
    val relayOrigin = "https://relay.example.invalid:9443/"
    val relayUrl = "${relayOrigin}notifications"
    var confirmed = 0
    scenario.onActivity { activity ->
      RelayDisclosureDialogs.showRelay(activity, MollySocket.WebServer("v".repeat(87), relayUrl)) { confirmed++ }
    }
    onView(withId(android.R.id.message)).check(matches(withText(containsString(relayOrigin))))
    onView(withId(android.R.id.message)).check(matches(withText(not(containsString(relayUrl)))))
    assertTrustVisible()
    screenshot("02-web-relay-trust")
    cancelDialog()
    assertEquals(0, confirmed)

    scenario.onActivity { activity ->
      RelayDisclosureDialogs.showRelay(activity, MollySocket.AirGapped("v".repeat(87))) { confirmed++ }
    }
    onView(withId(android.R.id.message)).check(matches(withText(containsString(context.getString(R.string.RelayDisclosureDialog__manual_destination)))))
    onView(withId(android.R.id.message)).check(matches(withText(not(containsString(relayOrigin)))))
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
    val secureBefore = SignalStore.settings.isScreenSecurityEnabled
    SignalStore.settings.isScreenSecurityEnabled = false
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
          SignalStore.settings.isScreenSecurityEnabled = secureBefore
          scenario.onActivity { activity ->
            if (secureBefore || windowWasSecure) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
          }
        }
      }
    } finally {
      SignalStore.settings.isScreenSecurityEnabled = secureBefore
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

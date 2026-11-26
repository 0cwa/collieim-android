package im.molly.unifiedpush.enrollment

import com.fasterxml.jackson.databind.ObjectMapper
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
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
  private var previousControlState: ControlState? = null
  private var previousPendingReceiverState: String? = null
  private var replayBefore: String? = null
  private var previousVapid: String? = null
  private var previousEndpoint: String? = null
  private var previousP256dh: String? = null
  private var previousAuth: String? = null
  private var previousInstance: String? = null
  private var previousPairingBinding: org.thoughtcrime.securesms.keyvalue.UnifiedPushValues.PairingEndpointBinding? = null
  private var previousDevice: MollySocketDevice? = null
  private var previousEnabled = false
  private var previousAirGapped = false
  private var previousSocketUrl: String? = null
  private var previousDeliveryMethod = org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod.UNIFIEDPUSH
  private var previousReceiverState: String? = null
  private val device = MollySocketDevice(2, "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL")

  @Before
  fun seedManualEnrollment() {
    previousReceiverState = values.v4ReceiverState
    previousPendingReceiverState = values.v4PendingReceiverState
    previousControlState = values.controlStateRead.stateOrNull
    replayBefore = values.replayState
    previousVapid = values.vapidPublicKey
    previousEndpoint = values.endpoint
    previousP256dh = values.endpointP256dh
    previousAuth = values.endpointAuth
    previousInstance = values.endpointInstance
    previousPairingBinding = values.pairingEndpointBinding
    previousDevice = values.device
    previousEnabled = values.enabled
    previousAirGapped = values.airGapped
    previousSocketUrl = values.mollySocketUrl
    previousDeliveryMethod = SignalStore.settings.preferredNotificationMethod
    values.v4ReceiverState = null
    values.v4PendingReceiverState = null
    values.enabled = true
    values.airGapped = true
    SignalStore.settings.preferredNotificationMethod =
      org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod.UNIFIEDPUSH
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
    values.setEndpointRegistration(previousEndpoint, previousP256dh, previousAuth, previousInstance)
    previousPairingBinding?.let { values.setPairingEndpointRegistration(previousEndpoint, previousP256dh, previousAuth, it) }
    values.vapidPublicKey = previousVapid
    values.device = previousDevice
    values.enabled = previousEnabled
    values.airGapped = previousAirGapped
    values.mollySocketUrl = previousSocketUrl
    values.mutateControl { previousControlState ?: ControlState() }
    values.replayState = replayBefore
    values.v4PendingReceiverState = previousPendingReceiverState
    values.v4ReceiverState = previousReceiverState
    SignalStore.settings.preferredNotificationMethod = previousDeliveryMethod
  }

  @Test
  fun addReceiverConsentShowsFourRowsAndExactDeviceWording() {
    val previousDeviceId = SignalStore.account.deviceId
    SignalStore.account.deviceId = 3
    try {
      withSettings { scenario ->
        val offer = ObjectMapper().readTree(
          fixture("colliesocket-pairing-v1/fixtures/sealed-vectors.json"),
        )[0]["offer"].deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("intent", "add_receiver")
          .put("expires_at", System.currentTimeMillis() / 1000 + 600)
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
    } finally {
      SignalStore.account.deviceId = previousDeviceId
    }
  }

  @Test
  fun confirmedV4ReceiverAppearsInLinkedRelaySettings() {
    val fixture = ObjectMapper().readTree(
      fixture("colliesocket-encrypted-push-v4/fixtures/receiver.json"),
    )
    val sub = fixture["subscription"]
    val previousDeviceId = SignalStore.account.deviceId
    val distributor = instrumentation.context.packageName
    val connector = context.getSharedPreferences("unifiedpush.connector", Context.MODE_PRIVATE)
    val savedKey = "unifiedpush.distributor"
    val ackKey = "unifiedpush.distributor_ack"
    val previousDistributor = connector.getString(savedKey, null)
    val previousAck = connector.getBoolean(ackKey, false).takeIf { connector.contains(ackKey) }
    SignalStore.account.deviceId = 3
    val instance = sub["instance"].asText()
    val endpoint = sub["endpoint"].asText()
    try {
      check(distributor in org.unifiedpush.android.connector.UnifiedPush.getDistributors(context))
      check(connector.edit().putString(savedKey, distributor).putBoolean(ackKey, true).commit())
      values.v4ReceiverState = ReceiverControlState(
        receiverId = fixture["receiver_id"].asText(), importId = fixture["request_id"].asText(),
        accountId = SignalStore.account.aci.toString(), bridgeDeviceId = 2, instance = instance,
        hostLabel = "Collie Lab", controlKey = fixture["control_key"].asText(), epoch = 1,
        target = Target(endpoint, sub["p256dh"].asText(), sub["auth"].asText()), confirmed = true,
      ).serialize()
      values.setPairingEndpointRegistration(
        endpoint, sub["p256dh"].asText(), sub["auth"].asText(),
        org.thoughtcrime.securesms.keyvalue.UnifiedPushValues.PairingEndpointBinding(
          instance = instance, distributorId = distributor, vapidPublicKey = values.vapidPublicKey.orEmpty(),
          providerOrigin = "https://push.example.test", endpointOrigin = checkNotNull(PairingV1.canonicalOrigin(endpoint)),
          sessionId = fixture["request_id"].asText(), expiresAt = System.currentTimeMillis() / 1000 + 600,
          accountId = SignalStore.account.aci.toString(), deviceId = SignalStore.account.deviceId,
          hostLabel = "Collie Lab", importId = fixture["request_id"].asText(),
        ),
      )
      withSettings {
        onView(withText(R.string.UnifiedPushSettingsFragment__status)).check(matches(isDisplayed()))
        onView(withText(R.string.PairingV1__status_connected)).check(matches(isDisplayed()))
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
      check(connector.edit().putString(savedKey, previousDistributor).apply {
        if (previousAck == null) remove(ackKey) else putBoolean(ackKey, previousAck)
      }.commit())
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
    val name = "$prefix-$suffix.png"
    val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/collie-relay-disclosure/"
    val resolver = context.contentResolver
    try {
      resolver.delete(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
        arrayOf(name, relativePath),
      )
      val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }))
      try {
        checkNotNull(resolver.openOutputStream(uri)).use {
          check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
      } catch (failure: Exception) {
        resolver.delete(uri, null, null)
        throw failure
      }
    } finally {
      bitmap.recycle()
    }
  }

  private fun fixture(path: String): ByteArray = instrumentation.context.assets.open(path).use { it.readBytes() }
}

/** Makes the synthetic distributor discoverable only from the instrumentation APK. */
class RelayDisclosureFixtureDistributor : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) = Unit
}

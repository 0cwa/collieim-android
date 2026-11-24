package org.thoughtcrime.securesms.linkdevice

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.camera.CameraScreenState
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.linkdevice.LinkDeviceSettingsState.QrCodeState

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LinkDeviceCodeScreenTest {

  companion object {
    private const val CODE = "sgnl://linkdevice?uuid=temporary&pub_key=BdDF6-_UPrbtdNGGRtLqDie8khmxlEEMrN8t_mNF1m5V&capabilities=backup5,nopni2"
  }

  @get:Rule val compose = createComposeRule()
  @get:Rule val coreUiDependencies = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val clipboard: ClipboardManager get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

  @Test
  fun `form starts neutral and does not paste until asked`() {
    clipboard.setPrimaryClip(ClipData.newPlainText("test", CODE))
    showManual()

    compose.onNodeWithText(text(R.string.DeviceProvisioningActivity_continue)).assertIsNotEnabled()
    compose.onNodeWithText(CODE).assertDoesNotExist()
    compose.onNodeWithText(text(R.string.LinkDeviceCode__invalid_code)).assertDoesNotExist()
  }

  @Test
  fun `explicit paste enables continue without submitting and trims the copied code`() {
    clipboard.setPrimaryClip(ClipData.newPlainText("test", " \n$CODE\n "))
    var submitted: String? = null
    showManual { submitted = it }

    compose.onNodeWithText(text(R.string.LinkDeviceCode__paste_code)).performScrollTo().performClick()
    compose.runOnIdle { assertNull(submitted) }
    compose.onNodeWithText(text(R.string.DeviceProvisioningActivity_continue)).assertIsEnabled().performClick()
    compose.runOnIdle { assertEquals(CODE, submitted) }
  }

  @Test
  fun `invalid input explains how to recover and cannot continue`() {
    showManual()
    compose.onNode(hasSetTextAction()).performTextReplacement("not a code")
    compose.onNodeWithText(text(R.string.LinkDeviceCode__invalid_code)).performScrollTo().assertIsDisplayed()
    compose.onNodeWithText(text(R.string.DeviceProvisioningActivity_continue)).assertIsNotEnabled()

    compose.onNode(hasSetTextAction()).performTextReplacement(CODE)
    compose.onNodeWithText(text(R.string.LinkDeviceCode__invalid_code)).assertDoesNotExist()
    compose.onNodeWithText(text(R.string.DeviceProvisioningActivity_continue)).assertIsEnabled()
  }

  @Test
  fun `empty clipboard gives a useful instruction`() {
    clipboard.setPrimaryClip(ClipData.newPlainText("test", ""))
    showManual()
    compose.onNodeWithText(text(R.string.LinkDeviceCode__paste_code)).performScrollTo().performClick()
    compose.onNodeWithText(text(R.string.LinkDeviceCode__clipboard_empty)).performScrollTo().assertIsDisplayed()
    compose.onNodeWithText(text(R.string.DeviceProvisioningActivity_continue)).assertIsNotEnabled()
  }

  @Test
  fun `temporary code is not restored as saved screen state`() {
    val restoration = StateRestorationTester(compose)
    restoration.setContent { SignalTheme { LinkDeviceManualEntryScreen(onLinkNewDeviceWithUrl = {}) } }
    compose.onNode(hasSetTextAction()).performTextReplacement(CODE)
    compose.onNodeWithText(text(R.string.DeviceProvisioningActivity_continue)).assertIsEnabled()

    restoration.emulateSavedInstanceStateRestore()

    compose.onNodeWithText(CODE).assertDoesNotExist()
    compose.onNodeWithText(text(R.string.DeviceProvisioningActivity_continue)).assertIsNotEnabled()
  }

  @Test
  fun `scanner offers code entry without camera permission`() {
    var requestedPermission = 0
    var requestedCodeEntry = 0
    compose.setContent {
      SignalTheme {
        LinkDeviceQrScanScreen(
          hasPermission = false,
          onRequestPermissions = { requestedPermission++ },
          cameraState = CameraScreenState(),
          cameraEmitter = {},
          onLinkWithCode = { requestedCodeEntry++ }
        )
      }
    }

    compose.onNodeWithText(text(R.string.LinkDeviceCode__link_with_code)).assertIsDisplayed().performClick()
    compose.runOnIdle {
      assertEquals(1, requestedCodeEntry)
      assertEquals(0, requestedPermission)
    }
  }

  @Test
  fun `manual code without history support still needs confirmation`() {
    var approved = 0
    var dismissed = 0
    compose.setContent {
      SignalTheme {
        AddLinkDeviceScreen(
          state = state(QrCodeState.VALID_WITHOUT_SYNC, manual = true),
          onQrCodeApproved = { approved++ },
          onQrCodeDismissed = { dismissed++ }
        )
      }
    }
    compose.runOnIdle { assertEquals(0, approved) }
    compose.onNodeWithText(text(R.string.device_list_fragment__link_new_device)).performClick()
    compose.runOnIdle {
      assertEquals(1, approved)
      assertEquals(0, dismissed)
    }
  }

  @Test
  fun `canceling confirmation does not approve the device`() {
    var approved = 0
    var dismissed = 0
    compose.setContent {
      SignalTheme {
        LinkDeviceConfirmation(QrCodeState.VALID_WITHOUT_SYNC, { approved++ }, { dismissed++ }, {})
      }
    }
    compose.onNodeWithText(text(android.R.string.cancel)).performClick()
    compose.runOnIdle {
      assertEquals(0, approved)
      assertEquals(1, dismissed)
    }
  }

  @Test
  fun `manual code with history support offers the transfer choice once`() {
    val current = mutableStateOf(state(QrCodeState.VALID_WITH_SYNC, manual = true))
    var choices = 0
    var approved = 0
    compose.setContent {
      SignalTheme {
        AddLinkDeviceScreen(current.value, onQrCodeApproved = { approved++ }, onShowTransferChoices = { choices++ })
      }
    }
    compose.runOnIdle { current.value = current.value.copy(isInternalUser = true) }
    compose.runOnIdle {
      assertEquals(1, choices)
      assertEquals(0, approved)
    }
  }

  @Test
  fun `outdated device is rejected in the manual flow`() {
    var approved = 0
    compose.setContent {
      SignalTheme {
        AddLinkDeviceScreen(state(QrCodeState.OUTDATED_DEVICE, manual = true), onQrCodeApproved = { approved++ })
      }
    }
    compose.onNodeWithText(text(R.string.AddLinkDeviceFragment__the_device_you_are_trying_to_link_is_out_of_date)).assertIsDisplayed()
    compose.onNodeWithText(text(R.string.device_list_fragment__link_new_device)).assertDoesNotExist()
    compose.runOnIdle { assertEquals(0, approved) }
  }

  private fun showManual(onContinue: (String) -> Unit = {}) {
    compose.setContent { SignalTheme { LinkDeviceManualEntryScreen(onLinkNewDeviceWithUrl = onContinue) } }
  }

  private fun state(qrState: QrCodeState, manual: Boolean) = LinkDeviceSettingsState(
    qrCodeState = qrState,
    linkWithoutQrCode = manual,
    canLinkDevices = true,
    seenQrEducationSheet = true
  )

  private fun text(id: Int): String = context.getString(id)
}

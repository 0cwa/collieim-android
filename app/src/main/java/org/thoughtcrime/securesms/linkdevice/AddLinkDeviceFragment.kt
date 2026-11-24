package org.thoughtcrime.securesms.linkdevice

import android.Manifest
import android.annotation.SuppressLint
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import org.signal.camera.CameraScreenEvents
import org.signal.camera.CameraScreenState
import org.signal.camera.CameraScreenViewModel
import org.signal.core.ui.compose.ComposeFragment
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Dialogs
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.permissions.Permissions
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.linkdevice.LinkDeviceSettingsState.QrCodeState
import org.thoughtcrime.securesms.util.VibrateUtil
import org.thoughtcrime.securesms.util.navigation.safeNavigate
import org.signal.core.ui.R as CoreUiR
import org.signal.mediasend.R as MediaSendR

/**
 * Links a device using its QR code or the same provisioning code pasted as text.
 */
class AddLinkDeviceFragment : ComposeFragment() {

  override val autofillEnabled: Boolean = false

  companion object {
    private const val VIBRATE_DURATION_MS = 50
  }

  private val viewModel: LinkDeviceViewModel by activityViewModels()

  @OptIn(ExperimentalPermissionsApi::class)
  @Composable
  override fun FragmentContent() {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cameraViewModel: CameraScreenViewModel? = if (state.linkWithoutQrCode) null else viewModel { CameraScreenViewModel() }
    val cameraState = cameraViewModel?.state?.value ?: CameraScreenState()
    val context = LocalContext.current
    val navController: NavController by remember { mutableStateOf(findNavController()) }
    val cameraPermissionState: PermissionState = rememberPermissionState(permission = Manifest.permission.CAMERA)

    LaunchedEffect(cameraViewModel) {
      cameraViewModel?.qrCodeDetected?.collect { data ->
        if (VibrateUtil.isHapticFeedbackEnabled(requireContext())) {
          VibrateUtil.vibrate(requireContext(), VIBRATE_DURATION_MS)
        }
        viewModel.onQrCodeScanned(data)
      }
    }

    val onClose: () -> Unit = {
      viewModel.onQrCodeDismissed()
      navController.popBackStack()
    }
    BackHandler(onBack = onClose)

    AddLinkDeviceScreen(
      state = state,
      cameraState = cameraState,
      cameraEmitter = { cameraViewModel?.onEvent(it) },
      onClose = onClose,
      hasPermissions = cameraPermissionState.status.isGranted,
      onRequestPermissions = { askPermissions() },
      onSwitchCamera = { cameraViewModel?.onEvent(CameraScreenEvents.SwitchCamera(context)) },
      onLinkNewDeviceWithUrl = viewModel::onQrCodeScanned,
      onLinkWithCode = { viewModel.requestLinkWithoutQrCode(true) },
      onQrCodeApproved = {
        viewModel.addDevice(shouldSync = false)
        navController.popBackStack()
      },
      onQrCodeDismissed = { viewModel.onQrCodeDismissed() },
      onShowTransferChoices = { navController.safeNavigate(R.id.action_addLinkDeviceFragment_to_linkDeviceSyncBottomSheet) }
    )
  }

  private fun askPermissions() {
    Permissions.with(this)
      .request(Manifest.permission.CAMERA)
      .ifNecessary()
      .withPermanentDenialDialog(getString(MediaSendR.string.CameraXFragment_signal_needs_camera_access_scan_qr_code), null, MediaSendR.string.CameraXFragment_allow_access_camera, MediaSendR.string.CameraXFragment_to_scan_qr_codes, parentFragmentManager)
      .onAnyDenied { Toast.makeText(requireContext(), MediaSendR.string.CameraXFragment_signal_needs_camera_access_scan_qr_code, Toast.LENGTH_LONG).show() }
      .execute()
  }

  @SuppressLint("MissingSuperCall")
  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
  }
}

@Composable
internal fun AddLinkDeviceScreen(
  state: LinkDeviceSettingsState,
  cameraState: CameraScreenState = CameraScreenState(),
  cameraEmitter: (CameraScreenEvents) -> Unit = {},
  onClose: () -> Unit = {},
  hasPermissions: Boolean = false,
  onLinkNewDeviceWithUrl: (String) -> Unit = {},
  onLinkWithCode: () -> Unit = {},
  onRequestPermissions: () -> Unit = {},
  onSwitchCamera: () -> Unit = {},
  onQrCodeApproved: () -> Unit = {},
  onQrCodeDismissed: () -> Unit = {},
  onShowTransferChoices: () -> Unit = {}
) {
  LinkDeviceConfirmation(
    qrCodeState = state.qrCodeState,
    onQrCodeApproved = onQrCodeApproved,
    onQrCodeDismissed = onQrCodeDismissed,
    onShowTransferChoices = onShowTransferChoices
  )

  Scaffolds.Settings(
    title = if (state.linkWithoutQrCode) stringResource(id = R.string.LinkDeviceCode__link_with_code) else "",
    onNavigationClick = onClose,
    navigationIcon = ImageVector.vectorResource(id = R.drawable.ic_x),
    navigationContentDescription = stringResource(id = CoreUiR.string.CloseButton__content_description),
    actions = {
      if (!state.linkWithoutQrCode && hasPermissions) {
        IconButton(onClick = onSwitchCamera) {
          Icon(painterResource(id = R.drawable.symbol_switch_24), contentDescription = null)
        }
      }
    }
  ) { contentPadding: PaddingValues ->
    if (!state.linkWithoutQrCode) {
      LinkDeviceQrScanScreen(
        hasPermission = hasPermissions,
        onRequestPermissions = onRequestPermissions,
        cameraState = cameraState,
        cameraEmitter = cameraEmitter,
        onLinkWithCode = onLinkWithCode,
        modifier = Modifier.padding(contentPadding)
      )
    } else {
      LinkDeviceManualEntryScreen(
        onLinkNewDeviceWithUrl = onLinkNewDeviceWithUrl,
        modifier = Modifier.padding(contentPadding)
      )
    }
  }
}

@Composable
internal fun LinkDeviceConfirmation(
  qrCodeState: QrCodeState,
  onQrCodeApproved: () -> Unit,
  onQrCodeDismissed: () -> Unit,
  onShowTransferChoices: () -> Unit
) {
  when (qrCodeState) {
    QrCodeState.NONE -> Unit
    QrCodeState.VALID_WITH_SYNC -> {
      LaunchedEffect(qrCodeState) { onShowTransferChoices() }
    }
    QrCodeState.VALID_WITHOUT_SYNC -> {
      Dialogs.SimpleAlertDialog(
        title = stringResource(R.string.DeviceProvisioningActivity_link_this_device),
        body = stringResource(R.string.AddLinkDeviceFragment__this_device_will_see_your_groups_contacts),
        confirm = stringResource(R.string.device_list_fragment__link_new_device),
        onConfirm = onQrCodeApproved,
        dismiss = stringResource(android.R.string.cancel),
        // SimpleAlertDialog invokes onDismiss before onConfirm. Clear the pending
        // code only when the user cancels, so approval can consume it first.
        onDismissRequest = onQrCodeDismissed,
        onDeny = onQrCodeDismissed
      )
    }
    QrCodeState.INVALID, QrCodeState.OUTDATED_DEVICE -> {
      Dialogs.SimpleAlertDialog(
        title = stringResource(R.string.AddLinkDeviceFragment__linking_device_failed),
        body = stringResource(
          if (qrCodeState == QrCodeState.INVALID) R.string.LinkDeviceCode__invalid_code else R.string.AddLinkDeviceFragment__the_device_you_are_trying_to_link_is_out_of_date
        ),
        confirm = stringResource(android.R.string.ok),
        onConfirm = { },
        onDismiss = onQrCodeDismissed
      )
    }
  }
}

@DayNightPreviews
@Composable
private fun LinkDeviceAddScreenPreview() {
  Previews.Preview {
    AddLinkDeviceScreen(
      state = LinkDeviceSettingsState()
    )
  }
}

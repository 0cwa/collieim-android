package org.thoughtcrime.securesms.linkdevice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.signal.camera.CameraCaptureMode
import org.signal.camera.CameraScreen
import org.signal.camera.CameraScreenEvents
import org.signal.camera.CameraScreenState
import org.signal.core.ui.compose.Buttons
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.qr.QrCrosshair
import org.signal.mediasend.R as MediaSendR

/**
 * A screen that allows you to scan a QR code to link a device
 */
@Composable
fun LinkDeviceQrScanScreen(
  hasPermission: Boolean,
  onRequestPermissions: () -> Unit,
  cameraState: CameraScreenState,
  cameraEmitter: (CameraScreenEvents) -> Unit,
  onLinkWithCode: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .fillMaxHeight()
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f, true)
        .background(Color.Black)
    ) {
      if (hasPermission) {
        CameraScreen(
          state = cameraState,
          emitter = cameraEmitter,
          enableQrScanning = true,
          captureMode = CameraCaptureMode.ImageOnly,
          roundCorners = false,
          fillViewport = true,
          modifier = Modifier.fillMaxSize()
        ) {
          QrCrosshair(modifier = Modifier.fillMaxSize())

          Text(
            text = stringResource(R.string.AddLinkDeviceFragment__scan_the_qr_code),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
              .align(Alignment.TopCenter)
              .padding(top = 24.dp)
              .background(color = Color.Black.copy(alpha = 0.5f), shape = CircleShape)
              .padding(horizontal = 16.dp, vertical = 8.dp)
          )
        }
      } else {
        Column(
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier
            .align(Alignment.Center)
            .padding(48.dp)
        ) {
          Text(
            text = stringResource(MediaSendR.string.CameraXFragment_to_scan_qr_code_allow_camera),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White
          )
          Buttons.MediumTonal(
            colors = ButtonDefaults.filledTonalButtonColors(),
            onClick = onRequestPermissions
          ) {
            Text(stringResource(MediaSendR.string.CameraXFragment_allow_access))
          }
        }
      }
    }

    Buttons.LargeTonal(
      onClick = onLinkWithCode,
      modifier = Modifier
        .align(Alignment.CenterHorizontally)
        .padding(16.dp)
    ) {
      Text(stringResource(R.string.LinkDeviceCode__link_with_code))
    }
  }
}

@DayNightPreviews
@Composable
private fun LinkDeviceQrScanScreenPreview() {
  Previews.Preview {
    LinkDeviceQrScanScreen(
      hasPermission = true,
      onRequestPermissions = {},
      cameraState = CameraScreenState(),
      cameraEmitter = {},
      onLinkWithCode = {}
    )
  }
}

@DayNightPreviews
@Composable
private fun LinkDeviceQrScanScreenNoPermissionPreview() {
  Previews.Preview {
    LinkDeviceQrScanScreen(
      hasPermission = false,
      onRequestPermissions = {},
      cameraState = CameraScreenState(),
      cameraEmitter = {},
      onLinkWithCode = {}
    )
  }
}

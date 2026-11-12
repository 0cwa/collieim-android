package im.molly.unifiedpush.components.settings.app.notifications

import im.molly.unifiedpush.health.PushHealth
import im.molly.unifiedpush.model.MollySocketDevice
import im.molly.unifiedpush.model.RegistrationStatus

data class Distributor(
  val applicationId: String,
  val name: String,
)

data class UnifiedPushSettingsState(
  val airGapped: Boolean,
  val device: MollySocketDevice?,
  val aci: String?,
  val registrationStatus: RegistrationStatus,
  val registrationFailed: Boolean,
  val distributors: List<Distributor>,
  val selected: Int,
  val selectedNotAck: Boolean,
  val endpoint: String?,
  val mollySocketUrl: String?,
  val pushFallback: Boolean = false,
  val pushTestResult: PushHealth.Hop? = null,
  val pushTestResultAt: Long = 0,
  val pushTestRunning: Boolean = false,
  val controlStateCorrupt: Boolean = false,
  val savedExports: List<SavedExport> = emptyList(),
  val pendingExportCount: Int = 0,
  val hasLegacyPendingExport: Boolean = false,
) {
  val serverParameters: String?
    get() {
      return if (aci != null && device != null && endpoint != null) {
        "connection add $aci ${device.deviceId} ${device.password} $endpoint"
      } else null
    }
}

data class SavedExport(val requestId: String, val epoch: Long, val eligible: Boolean) {
  override fun toString() = "SavedExport(epoch=$epoch, eligible=$eligible)"
}

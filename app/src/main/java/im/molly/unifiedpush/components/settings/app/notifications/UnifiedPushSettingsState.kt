package im.molly.unifiedpush.components.settings.app.notifications

import im.molly.unifiedpush.health.PushHealth
import im.molly.unifiedpush.enrollment.RelayDisclosure
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
  val relayDisclosure: RelayDisclosure.Snapshot? = null,
  val pairingHost: String? = null,
  val pairingOrigin: String? = null,
  val encryptedEnrollment: Boolean = false,
  val awaitingPushConfirmation: Boolean = false,
)

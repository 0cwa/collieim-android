package im.molly.unifiedpush.components.settings.app.notifications

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import im.molly.unifiedpush.control.ControlReset
import im.molly.unifiedpush.control.ReceiverControlState
import im.molly.unifiedpush.enrollment.PairingV1
import im.molly.unifiedpush.enrollment.PairingTransport
import im.molly.unifiedpush.enrollment.RelayDisclosure
import im.molly.unifiedpush.health.PushHealth
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition
import im.molly.unifiedpush.model.MollySocket
import im.molly.unifiedpush.UnifiedPushDistributor
import org.signal.core.util.Util.writeTextToClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.signal.storage.keyvalue.KeyValuePersistenceException
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.UnifiedPushHealthJob
import org.thoughtcrime.securesms.jobs.UnifiedPushRefreshJob
import org.thoughtcrime.securesms.keyvalue.SettingsValues.ForceWebsocketMode
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.util.livedata.Store
import org.unifiedpush.android.connector.UnifiedPush
import kotlin.coroutines.resume

class UnifiedPushSettingsViewModel(private val application: Application) : ViewModel() {

  // Keep confirmation bound to the original state across same-process scanner recreation.
  internal var scanDisclosure: RelayDisclosure.Snapshot? = null
  internal var pairingFlowActive = false
    private set
  private var handledInitialPairingOffer: String? = null
  private var distributorForNextPairing: String? = null
  internal data class PendingPairingConsent(
    val scan: PairingTransport.Scan,
    val disclosure: RelayDisclosure.Snapshot,
    val distributor: Distributor? = null,
  )
  internal var pendingPairingConsent: PendingPairingConsent? = null
  private val pairingCoordinator = PairingV1Coordinator(application)

  internal fun claimInitialPairingOffer(offer: String): Boolean {
    pairingFlowActive = true
    if (handledInitialPairingOffer == offer) return false
    handledInitialPairingOffer = offer
    return true
  }

  internal fun enterPairingFlow() {
    pairingFlowActive = true
  }

  internal sealed interface PairingUi {
    data object Working : PairingUi
    data class Paired(val hostLabel: String) : PairingUi
    data object AwaitingPush : PairingUi
    data class Offline(val sessionId: String, val hadSubmitUrl: Boolean) : PairingUi
    data class Error(val message: String) : PairingUi
  }

  private val pairingResult = MutableLiveData<PairingUi?>()
  internal val pairingState: LiveData<PairingUi?> = pairingResult
  internal val pairingBusy: Boolean get() = pairingResult.value == PairingUi.Working

  internal fun consumePairingResult() {
    if (!pairingBusy) pairingResult.value = null
  }

  internal fun parsePairingScan(json: String): PairingTransport.Scan = pairingCoordinator.parseScan(json)

  internal fun evaluatePairingOffer(offer: PairingV1.Offer, distributorId: String): PairingV1Coordinator.OfferPlan =
    pairingCoordinator.evaluateOffer(offer, distributorId)

  internal fun pairingDistributors(): List<Distributor> = UnifiedPush.getDistributors(application).map { id ->
    val label = try {
      application.packageManager.getApplicationLabel(application.packageManager.getApplicationInfo(id, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
      id
    }
    Distributor(id, label)
  }

  internal fun pairingSelectedDistributor(): String? =
    distributorForNextPairing ?: UnifiedPush.getSavedDistributor(application)

  internal fun hasExistingBridgeSetup(): Boolean = pairingCoordinator.hasExistingBridgeSetup()

  internal fun pair(
    scan: PairingTransport.Scan,
    disclosure: RelayDisclosure.Snapshot,
    distributorId: String,
    approvedOrigin: String?,
    replaceApproved: Boolean,
  ) {
    if (pairingBusy) return
    pairingResult.value = PairingUi.Working
    refresh()
    viewModelScope.launch {
      try {
        val result = pairAfterApproval(scan, disclosure, distributorId, approvedOrigin, replaceApproved)
        if (result is PairingUi.Paired) distributorForNextPairing = null
        pairingResult.value = result
        refresh()
      } catch (cancelled: CancellationException) {
        pairingCoordinator.cancelOfferAttempt(scan.offer.sessionId)
        throw cancelled
      }
    }
  }

  private suspend fun pairAfterApproval(
    scan: PairingTransport.Scan,
    disclosure: RelayDisclosure.Snapshot,
    distributorId: String,
    approvedOrigin: String?,
    replaceApproved: Boolean,
  ): PairingUi {
    val offer = scan.offer
    if (System.currentTimeMillis() / 1000 >= offer.expiresAt) return pairingError(R.string.PairingV1__offer_expired)
    if (offer.intent == "pair_bridge" && !replaceApproved && hasExistingBridgeSetup()) {
      return pairingError(R.string.UnifiedPushSettingsFragment__disclosure_stale)
    }

    var approved: RelayDisclosure.Snapshot? = null
    RelayDisclosure.runIfCurrent(disclosure) {
      if (distributorId in UnifiedPush.getDistributors(application)) {
        val previous = disclosure.configuration.distributor
        val hasPreviousEndpoint = previous != null && disclosure.account.endpoint != null
        approved = when {
          distributorId == previous || hasPreviousEndpoint -> disclosure
          UnifiedPushDistributor.saveDistributor(application, distributorId) -> RelayDisclosure.capture(application)
          else -> null
        }
      }
    }
    var snapshot = approved ?: return pairingError(R.string.PairingV1__endpoint_changed)
    var plan = pairingCoordinator.evaluateOffer(offer, distributorId)
    if (plan is PairingV1Coordinator.OfferPlan.Register) {
      val registration = plan.details
      val registered = withTimeoutOrNull(25_000L) {
        suspendCancellableCoroutine { continuation ->
          val started = pairingCoordinator.registerForOffer(registration, snapshot) { result ->
            if (continuation.isActive) continuation.resume(result.isSuccess)
          }
          if (!started && continuation.isActive) continuation.resume(false)
        }
      }
      if (registered != true) {
        pairingCoordinator.cancelOfferAttempt(offer.sessionId)
        return pairingError(R.string.PairingV1__registration_failed)
      }
      val afterRegistration = RelayDisclosure.capture(application)
      if (afterRegistration == null ||
        afterRegistration.account.accountId != snapshot.account.accountId ||
        afterRegistration.account.localDeviceId != snapshot.account.localDeviceId ||
        afterRegistration.configuration != snapshot.configuration) {
        pairingCoordinator.cancelOfferAttempt(offer.sessionId)
        return pairingError(R.string.PairingV1__endpoint_changed)
      }
      snapshot = afterRegistration
      plan = pairingCoordinator.evaluateOffer(offer, distributorId)
    }
    if (plan is PairingV1Coordinator.OfferPlan.Failed) {
      pairingCoordinator.cancelOfferAttempt(offer.sessionId)
      return PairingUi.Error(plan.message)
    }
    val consent = (plan as? PairingV1Coordinator.OfferPlan.Ready)?.consent
    if (consent == null || consent.distributorId != distributorId ||
      approvedOrigin != null && consent.providerOrigin != approvedOrigin) {
      pairingCoordinator.cancelOfferAttempt(offer.sessionId)
      return pairingError(R.string.PairingV1__endpoint_changed)
    }

    return withContext(Dispatchers.IO) {
      val sealed = pairingCoordinator.sealAfterConsent(offer, snapshot, consent, replaceApproved, scan.bridgeDeviceId)
      if (sealed is PairingV1Coordinator.SealResult.Failed) {
        pairingCoordinator.cancelOfferAttempt(offer.sessionId)
        return@withContext PairingUi.Error(sealed.message)
      }
      val session = (sealed as PairingV1Coordinator.SealResult.Ready).session
      when (val submitted = pairingCoordinator.submit(session, scan.submitUrl)) {
        PairingTransport.Result.Accepted -> PairingUi.Paired(offer.hostLabel)
        PairingTransport.Result.AwaitingPush -> PairingUi.AwaitingPush
        PairingTransport.Result.Offline -> PairingUi.Offline(session.id, scan.submitUrl != null)
        else -> {
          pairingCoordinator.cancelOfferAttempt(offer.sessionId)
          pairingError(when (submitted) {
            PairingTransport.Result.Stale -> R.string.PairingV1__endpoint_changed
            PairingTransport.Result.UnsafeDestination -> R.string.PairingV1__invalid_submit_url
            is PairingTransport.Result.Rejected -> when (submitted.code) {
              "session_expired" -> R.string.PairingV1__offer_expired
              "session_consumed" -> R.string.PairingV1__offer_used
              "invalid_session" -> R.string.PairingV1__session_unavailable
              "capacity_exceeded", "queue_full", "state_unavailable" -> R.string.PairingV1__server_busy
              "invalid_endpoint" -> R.string.PairingV1__server_endpoint_rejected
              "epoch_conflict", "idempotency_conflict" -> R.string.PairingV1__relay_conflict
              else -> R.string.PairingV1__request_rejected
            }
            else -> R.string.PairingV1__receipt_invalid
          })
        }
      }
    }
  }

  private fun pairingError(@StringRes message: Int): PairingUi.Error = PairingUi.Error(application.getString(message))

  internal fun sharePairingRequest(context: Context, sessionId: String): Boolean = pairingCoordinator.shareRequest(context, sessionId)

  internal fun closeOfflinePairing(sessionId: String) {
    consumePairingResult()
    viewModelScope.launch {
      try {
        withContext(Dispatchers.IO) { pairingCoordinator.closeOfflinePairing(sessionId) }
      } catch (_: KeyValuePersistenceException) {
        Toast.makeText(application, R.string.PairingV1__recovery_blocked, Toast.LENGTH_LONG).show()
      }
      refresh()
    }
  }

  override fun onCleared() {
    pairingCoordinator.clear()
    super.onCleared()
  }

  private val store = Store(getState())

  val state: LiveData<UnifiedPushSettingsState> = store.stateLiveData
  fun refresh() {
    store.update { getState() }
  }

  fun updateRegistration(pingOnRegister: Boolean = false) {
    AppDependencies.jobManager.add(UnifiedPushRefreshJob(pingOnRegister))
  }

  private fun getState(): UnifiedPushSettingsState = synchronized(UnifiedPushLifecycle.monitor) {
    val distributorIds = UnifiedPush.getDistributors(application)
    val saved = UnifiedPush.getSavedDistributor(application)
    val ack = saved != null && UnifiedPush.getAckDistributor(application) == saved

    val selected = distributorIds.indexOfFirst { it == saved }

    val distributors = distributorIds.map { appId ->
      val name = try {
        val ai = if (Build.VERSION.SDK_INT >= 33) {
          application.packageManager.getApplicationInfo(
            appId, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
          )
        } else {
          application.packageManager.getApplicationInfo(appId, 0)
        }
        application.packageManager.getApplicationLabel(ai)
      } catch (e: PackageManager.NameNotFoundException) {
        appId
      } as String

      Distributor(
        applicationId = appId,
        name = name,
      )
    }

    val mollySocketUrl = SignalStore.unifiedpush.mollySocketUrl

    val controlRead = SignalStore.unifiedpush.controlStateRead.stateOrNull
    val pairing = SignalStore.unifiedpush.pairingEndpointBinding
    val receiverJson = SignalStore.unifiedpush.v4ReceiverState
    val receiver = ReceiverControlState.parse(receiverJson)
    val pendingReceiver = ReceiverControlState.parse(SignalStore.unifiedpush.v4PendingReceiverState)
    val matchingConfirmedV4 = pairing != null && receiver?.confirmed == true && receiver.importId == pairing.importId &&
      receiver.instance == pairing.instance && receiver.accountId == pairing.accountId && pairing.deviceId == SignalStore.account.deviceId
    val awaitingV3 = pairing != null && !matchingConfirmedV4 &&
      (controlRead == null || controlRead.activeInstance != pairing.instance || pairing.importId != null && controlRead.activeImportId != pairing.importId)
    val awaitingV4 = pendingReceiver != null && pairing?.importId == pendingReceiver.importId ||
      receiver != null && (!receiver.confirmed || receiver.pendingEpoch != null)
    UnifiedPushSettingsState(
      airGapped = SignalStore.unifiedpush.airGapped,
      device = SignalStore.unifiedpush.device?.takeUnless(SignalStore.unifiedpush::isProvisioningPending),
      aci = SignalStore.account.aci?.toString(),
      registrationStatus = SignalStore.unifiedpush.registrationStatus,
      registrationFailed = SignalStore.unifiedpush.registrationFailed,
      distributors = distributors,
      selected = selected,
      selectedNotAck = !ack,
      endpoint = SignalStore.unifiedpush.endpoint,
      mollySocketUrl = mollySocketUrl,
      pairingHost = pairing?.hostLabel ?: receiver?.hostLabel,
      pairingOrigin = pairing?.endpointOrigin ?: receiver?.target?.endpoint?.let(PairingV1::canonicalOrigin),
      encryptedEnrollment = pairing != null || controlRead?.key != null || receiverJson != null,
      awaitingPushConfirmation = awaitingV3 || awaitingV4,
      pushFallback = SignalStore.unifiedpush.health.fallback,
      pushTestResult = PushHealth.Hop.fromCode(SignalStore.unifiedpush.health.lastResult),
      pushTestResultAt = SignalStore.unifiedpush.health.lastResultAt,
      // Only for as long as the job can run: it ends within the timeout, and a missing verdict must not leave "testing" up.
      pushTestRunning = pushTestStartedAt > 0 && SignalStore.unifiedpush.health.lastResultAt * 1000 < pushTestStartedAt &&
        System.currentTimeMillis() - pushTestStartedAt < UnifiedPushHealthJob.timeoutMs + TEST_GRACE_MS,
      controlStateCorrupt = SignalStore.unifiedpush.hasCorruptControlState,
      relayDisclosure = RelayDisclosure.capture(application),
    )
  }

  private var pushTestStartedAt = 0L

  /** Runs the end-to-end push test; the verdict arrives through [PushServiceEvent] and is shown on the screen. */
  fun testPush() {
    pushTestStartedAt = System.currentTimeMillis()
    UnifiedPushHealthJob.enqueue(manual = true)
    refresh()
  }

  fun setUnifiedPushDistributor(distributor: String) {
    val staged = synchronized(UnifiedPushLifecycle.monitor) {
      if (KeyCachingService.isLocked() || SignalStore.unifiedpush.pairingEndpointBinding == null) return@synchronized false
      distributorForNextPairing = distributor.takeUnless { it == UnifiedPush.getSavedDistributor(application) }
      true
    }
    if (staged) {
      if (distributorForNextPairing != null) {
        Toast.makeText(application, R.string.PairingV1__scan_to_switch_distributor, Toast.LENGTH_LONG).show()
      }
      return
    }
    val changed = synchronized(UnifiedPushLifecycle.monitor) {
      if (!UnifiedPushDistributor.saveDistributor(application, distributor)) return@synchronized false
      if (!KeyCachingService.isLocked() && SignalStore.unifiedpush.pairingEndpointBinding == null) {
        SignalStore.unifiedpush.setEndpointRegistration(null, null, null, null)
      }
      true
    }
    if (!changed) return
    refresh()
    updateRegistration()
  }

  fun retryRegistration() {
    if (KeyCachingService.isLocked()) return
    val pairing = synchronized(UnifiedPushLifecycle.monitor) { SignalStore.unifiedpush.pairingEndpointBinding }
    if (pairing != null) {
      synchronized(UnifiedPushLifecycle.monitor) {
        val values = SignalStore.unifiedpush
        val state = values.controlStateRead.stateOrNull
        val accountContext = UnifiedPushAccountTransition.captureContextLocked()
        val exactRoute = !KeyCachingService.isLocked() && values.pairingEndpointBinding == pairing &&
          values.endpointInstance == pairing.instance && values.endpoint != null &&
          values.endpointP256dh != null && values.endpointAuth != null &&
          PairingV1.canonicalOrigin(values.endpoint!!) == pairing.endpointOrigin &&
          SignalStore.account.aci?.toString() == pairing.accountId && SignalStore.account.deviceId == pairing.deviceId &&
          UnifiedPush.getSavedDistributor(application) == pairing.distributorId &&
          UnifiedPush.getAckDistributor(application) == pairing.distributorId &&
          state != null && state.activeImportSeq > 0 && state.activeImportId != null && state.activeKeyId != null && state.activeEpoch > 0 &&
          state.activeInstance == pairing.instance && state.activeAccountId == pairing.accountId &&
          accountContext != null && accountContext.pushDevice != null &&
          state.activeDeviceId == accountContext.pushDevice.deviceId &&
          accountContext.instance == pairing.instance && accountContext.accountId == pairing.accountId
        if (exactRoute) {
          values.registrationRetryScheduled = false
          runCatching {
            UnifiedPush.registerApp(application, pairing.instance, "CollieSocket pairing", pairing.vapidPublicKey)
          }.isSuccess
        } else false
      }
      return
    }
    SignalStore.unifiedpush.registrationRetryScheduled = false
    updateRegistration()
  }

  fun setMollySocket(mollySocket: MollySocket, disclosure: RelayDisclosure.Snapshot?) {
    viewModelScope.launch {
      val completed = RelayDisclosure.selectRelayAndSchedule(disclosure, mollySocket) { authorization ->
          AppDependencies.jobManager.add(UnifiedPushRefreshJob(authorization))
        }
      currentCoroutineContext().ensureActive()
      if (!completed) {
        showStaleDisclosure()
      }
      refresh()
    }
  }

  fun pingMollySocket() {
    refresh()
    updateRegistration(pingOnRegister = true)
  }

  fun copyAciToClipboard(context: Context) {
    val aci = state.value?.aci ?: ""
    writeTextToClipboard(context, "Account ID", aci)
  }

  private fun showStaleDisclosure() {
    Toast.makeText(application, R.string.UnifiedPushSettingsFragment__disclosure_stale, Toast.LENGTH_LONG).show()
  }

  /** Clears unreadable local control state only after the user confirms server-side removal. */
  fun recoverCorruptControlState() {
    try {
      ControlReset.recoverCorrupt(application)
    } catch (_: KeyValuePersistenceException) {
      Toast.makeText(application, R.string.UnifiedPushSettingsFragment__control_state_reset_failed, Toast.LENGTH_LONG).show()
    }
    refresh()
  }

  class Factory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return requireNotNull(modelClass.cast(UnifiedPushSettingsViewModel(application)))
    }
  }

  companion object {
    private const val TEST_GRACE_MS = 15_000L
  }
}

fun UnifiedPushValues.updateMollySocket(mollySocket: MollySocket) {
  airGapped = mollySocket is MollySocket.AirGapped
  mollySocketUrl = (mollySocket as? MollySocket.WebServer)?.url

  val changed = vapidPublicKey != mollySocket.vapid
  vapidPublicKey = mollySocket.vapid

  if (changed) {
    vapidKeySynced = false
    endpoint = null
    // The old server's keys and epochs no longer apply. Keep the import sequence so switching back cannot lower a retained server floor.
    ControlReset.discardForServerChange(AppDependencies.application)
    // The push health verdicts and the WebSocket mode they caused belong to the server that is gone.
    PushHealth.reset(health)
    if (SignalStore.settings.forceWebsocketMode == ForceWebsocketMode.ENABLED_AUTOMATICALLY) {
      SignalStore.settings.forceWebsocketMode = ForceWebsocketMode.DISABLED_AUTOMATICALLY
    }
  }

  lastReceivedTime = 0
}

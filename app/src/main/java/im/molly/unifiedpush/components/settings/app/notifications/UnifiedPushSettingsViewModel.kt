package im.molly.unifiedpush.components.settings.app.notifications

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import im.molly.unifiedpush.control.ControlReset
import im.molly.unifiedpush.control.ReceiverControlState
import im.molly.unifiedpush.enrollment.PairingV1
import im.molly.unifiedpush.enrollment.EnrollmentExporter
import im.molly.unifiedpush.enrollment.PairingTransport
import im.molly.unifiedpush.enrollment.EnrollmentFile
import im.molly.unifiedpush.enrollment.ExportMode
import im.molly.unifiedpush.enrollment.RelayDisclosure
import im.molly.unifiedpush.health.PushHealth
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition
import im.molly.unifiedpush.model.MollySocket
import im.molly.unifiedpush.UnifiedPushDistributor
import org.signal.core.util.Util.writeTextToClipboard
import org.signal.core.util.concurrent.SignalExecutors
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
import org.signal.core.util.ThreadUtil
import java.io.IOException
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.UnifiedPushHealthJob
import org.thoughtcrime.securesms.jobs.UnifiedPushRefreshJob
import org.thoughtcrime.securesms.keyvalue.SettingsValues.ForceWebsocketMode
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.util.livedata.Store
import org.unifiedpush.android.connector.UnifiedPush
import java.util.UUID
import kotlin.coroutines.resume

private data class ExportSummary(val file: java.io.File?, val epoch: Long, val highestUsed: Long, val collides: Boolean)

class UnifiedPushSettingsViewModel(private val application: Application) : ViewModel() {

  // Keep confirmation bound to the original state across same-process scanner recreation.
  internal var scanDisclosure: RelayDisclosure.Snapshot? = null
  internal var pendingPairingSaveId: String? = null
  internal var pairingFlowActive = false
    private set
  private var handledInitialPairingOffer: String? = null
  private var distributorForNextPairing: String? = null
  internal data class PendingPairingConsent(
    val scan: PairingTransport.Scan,
    val disclosure: RelayDisclosure.Snapshot,
    val distributor: Distributor? = null,
    var serverEpoch: String = "",
    var allowRecovery: Boolean = false,
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

  internal fun pair(
    scan: PairingTransport.Scan,
    disclosure: RelayDisclosure.Snapshot,
    distributorId: String,
    approvedOrigin: String?,
    serverEpoch: Long?,
    allowRecovery: Boolean,
  ) {
    if (pairingBusy) return
    pairingResult.value = PairingUi.Working
    refresh()
    viewModelScope.launch {
      try {
        val result = pairAfterApproval(scan, disclosure, distributorId, approvedOrigin, serverEpoch, allowRecovery)
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
    serverEpoch: Long?,
    allowRecovery: Boolean,
  ): PairingUi {
    val offer = scan.offer
    if (System.currentTimeMillis() / 1000 >= offer.expiresAt) return pairingError(R.string.PairingV1__offer_expired)

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
      val sealed = pairingCoordinator.sealAfterConsent(offer, snapshot, consent, serverEpoch, allowRecovery, scan.bridgeDeviceId)
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

  internal fun completePairingSave(uri: Uri?) {
    val sessionId = pendingPairingSaveId
    pendingPairingSaveId = null
    if (uri == null) {
      pairingResult.value = pairingResult.value
      return
    }
    viewModelScope.launch {
      val saved = sessionId != null && withContext(Dispatchers.IO) { pairingCoordinator.writeRequest(application, sessionId, uri) }
      if (saved) consumePairingResult()
      else {
        Toast.makeText(application, R.string.PairingV1__file_action_failed, Toast.LENGTH_LONG).show()
        pairingResult.value = pairingResult.value
      }
    }
  }

  internal fun cancelOfflinePairing(sessionId: String) {
    pairingCoordinator.cancelOfferAttempt(sessionId)
    consumePairingResult()
  }

  override fun onCleared() {
    pairingCoordinator.clear()
    super.onCleared()
  }

  private val store = Store(getState())

  val state: LiveData<UnifiedPushSettingsState> = store.stateLiveData
  internal val exportState: LiveData<ExportState?> = pendingExport

  internal data class ExportState(
    val id: String,
    val accountId: String,
    val deviceId: Int,
    val generation: Long,
    val message: String? = null,
  ) {
    fun belongsToCurrentAccount() = synchronized(UnifiedPushLifecycle.monitor) {
      UnifiedPushLifecycle.isCurrentGeneration(generation) && !KeyCachingService.isLocked() &&
        SignalStore.account.isRegistered && !SignalStore.account.isUnauthorizedReceived &&
        accountId == SignalStore.account.aci?.toString() && deviceId == SignalStore.account.deviceId
    }
  }

  private data class PendingDestinationExport(
    val serverEpochText: String,
    val mode: ExportMode,
    val requestId: String?,
    val disclosure: RelayDisclosure.Snapshot,
  )

  private var pendingDestinationExport: PendingDestinationExport? = null

  internal fun isExportPending(): Boolean = pendingDestinationExport != null ||
    pendingExport.value?.let { it.message == null || it.belongsToCurrentAccount() } == true

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
      enrollmentEpoch = controlRead?.activeEpoch ?: receiver?.epoch ?: 0L,
      pushFallback = SignalStore.unifiedpush.health.fallback,
      pushTestResult = PushHealth.Hop.fromCode(SignalStore.unifiedpush.health.lastResult),
      pushTestResultAt = SignalStore.unifiedpush.health.lastResultAt,
      // Only for as long as the job can run: it ends within the timeout, and a missing verdict must not leave "testing" up.
      pushTestRunning = pushTestStartedAt > 0 && SignalStore.unifiedpush.health.lastResultAt * 1000 < pushTestStartedAt &&
        System.currentTimeMillis() - pushTestStartedAt < UnifiedPushHealthJob.timeoutMs + TEST_GRACE_MS,
      controlStateCorrupt = SignalStore.unifiedpush.hasCorruptControlState,
      savedExports = controlRead?.intents?.map { SavedExport(it.requestId, it.epoch, it.eligible) } ?: emptyList(),
      pendingExportCount = controlRead?.pendingIntentCount ?: 0,
      hasLegacyPendingExport = controlRead?.legacyPending != null,
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

  fun copyParamsToClipboard(context: Context, disclosure: RelayDisclosure.Snapshot?) {
    if (!RelayDisclosure.runIfCurrent(disclosure) {
        val account = checkNotNull(disclosure).account
        val device = account.pushDevice
        val endpoint = account.endpoint
        if (device != null && endpoint != null && !SignalStore.unifiedpush.isProvisioningPending(device)) {
          writeTextToClipboard(context, "Server parameters", "connection add ${account.accountId} ${device.deviceId} ${device.password} $endpoint")
        }
      }) {
      showStaleDisclosure()
    }
  }

  fun copyAciToClipboard(context: Context) {
    val aci = state.value?.aci ?: ""
    writeTextToClipboard(context, "Account ID", aci)
  }

  /** Holds the confirmed action while Android asks where to save the CollieSocket enrollment file. */
  @MainThread
  fun prepareEnrollmentExport(
    serverEpochText: String,
    mode: ExportMode,
    requestId: String? = null,
    disclosure: RelayDisclosure.Snapshot?,
  ): String? {
    if (isExportPending()) return null
    if (disclosure == null || !RelayDisclosure.runIfCurrent(disclosure) {}) {
      showStaleDisclosure()
      return null
    }
    pendingDestinationExport = PendingDestinationExport(serverEpochText, mode, requestId, disclosure)
    return EnrollmentExporter.FILE_NAME
  }

  /** Starts the export only after the user selects a document; cancelling leaves enrollment state unchanged. */
  @MainThread
  fun completeEnrollmentExport(destination: Uri?) {
    val request = pendingDestinationExport
    pendingDestinationExport = null
    if (destination != null) {
      if (request == null) {
        Toast.makeText(application, R.string.UnifiedPushSettingsFragment__export_enrollment_interrupted, Toast.LENGTH_LONG).show()
        return
      }
      exportEnrollment(application, request.serverEpochText, request.mode, request.requestId, request.disclosure, destination)
    }
  }

  /** Writes the enrollment off the main thread and reports its result. */
  @MainThread
  fun exportEnrollment(
    context: Context,
    serverEpochText: String,
    mode: ExportMode,
    requestId: String? = null,
    disclosure: RelayDisclosure.Snapshot?,
    destination: Uri? = null,
  ) {
    if (isExportPending()) return
    if (disclosure == null || !RelayDisclosure.runIfCurrent(disclosure) {}) return showStaleDisclosure()
    val request = ExportState(UUID.randomUUID().toString(), disclosure.account.accountId, disclosure.account.localDeviceId, disclosure.generation)
    pendingExport.value = request
    val app = context.applicationContext
    SignalExecutors.BOUNDED.execute {
      var successMessage: String? = null
      var documentWriteStarted = false
      val failureMessage = try {
        val lease = UnifiedPushLifecycle.acquireLease(request.generation) { request.takeIf { it.belongsToCurrentAccount() } }
          ?: throw EnrollmentFile.Invalid("not_ready")
        val export = lease.use {
          fun authorizedEpoch(): Long? {
            // An authorized invalid epoch still removes the old password file.
            EnrollmentExporter.removeFiles(app)
            return serverEpochText.trim().takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: throw EnrollmentFile.Invalid("invalid_epoch") }
          }
          if (destination != null) {
            val document = RelayDisclosure.exportIfCurrent(disclosure, mode, requestId) {
              EnrollmentExporter.prepareDocument(app, authorizedEpoch(), mode, requestId)
            }
            if (!request.belongsToCurrentAccount() || !RelayDisclosure.runIfCurrent(disclosure) {}) {
              throw EnrollmentFile.Invalid("disclosure_changed")
            }
            documentWriteStarted = true
            document.writeTo(app, destination)
            if (!request.belongsToCurrentAccount() || !RelayDisclosure.runIfCurrent(disclosure) {}) {
              throw IOException("Enrollment authorization changed during document write")
            }
            ExportSummary(null, document.epoch, document.highestUsed, document.collides)
          } else {
            RelayDisclosure.exportIfCurrent(disclosure, mode, requestId) {
              val written = EnrollmentExporter.exportWithEpoch(app, authorizedEpoch(), mode, requestId)
              if (!request.belongsToCurrentAccount()) {
                EnrollmentExporter.removeFiles(app)
                throw EnrollmentFile.Invalid("disclosure_changed")
              }
              ExportSummary(written.file, written.epoch, written.highestUsed, written.collides)
            }
          }
        }
        successMessage = if (destination != null && export.collides) {
          app.getString(R.string.UnifiedPushSettingsFragment__export_enrollment_saved_collides, export.epoch, export.highestUsed)
        } else if (destination != null) {
          app.getString(R.string.UnifiedPushSettingsFragment__export_enrollment_saved)
        } else if (export.collides) {
          app.getString(R.string.UnifiedPushSettingsFragment__export_enrollment_done_collides, checkNotNull(export.file).absolutePath, export.epoch, export.highestUsed)
        } else {
          app.getString(R.string.UnifiedPushSettingsFragment__export_enrollment_done, checkNotNull(export.file).absolutePath)
        }
        null
      } catch (e: EnrollmentFile.Invalid) {
        app.getString(
          when (e.code) {
            "disclosure_changed" -> R.string.UnifiedPushSettingsFragment__disclosure_stale
            "not_ready" -> R.string.UnifiedPushSettingsFragment__export_enrollment_not_ready
            "invalid_epoch" -> R.string.UnifiedPushSettingsFragment__export_enrollment_invalid_epoch
            "unsupported_android" -> R.string.UnifiedPushSettingsFragment__export_enrollment_unsupported_android
            "state_unavailable" -> R.string.UnifiedPushSettingsFragment__export_enrollment_state_unavailable
            "recovery_ack_required" -> R.string.UnifiedPushSettingsFragment__export_enrollment_recovery_ack_required
            "pending_limit" -> R.string.UnifiedPushSettingsFragment__export_enrollment_pending_limit
            "sequence_exhausted" -> R.string.UnifiedPushSettingsFragment__export_enrollment_sequence_exhausted
            "same_epoch_conflict" -> R.string.UnifiedPushSettingsFragment__export_enrollment_same_epoch_conflict
            "intent_unavailable" -> R.string.UnifiedPushSettingsFragment__export_enrollment_intent_unavailable
            else -> R.string.UnifiedPushSettingsFragment__export_enrollment_failed
          }
        )
      } catch (e: IOException) {
        app.getString(
          if (destination != null) R.string.UnifiedPushSettingsFragment__export_enrollment_failed_destination
          else R.string.UnifiedPushSettingsFragment__export_enrollment_failed
        )
      } catch (e: SecurityException) {
        app.getString(
          if (destination != null) R.string.UnifiedPushSettingsFragment__export_enrollment_failed_destination
          else R.string.UnifiedPushSettingsFragment__export_enrollment_failed
        )
      } finally {
        if (destination != null) EnrollmentExporter.removeFiles(app)
      }
      ThreadUtil.runOnMain {
        if (!request.belongsToCurrentAccount()) {
          pendingExport.value = null
          if (documentWriteStarted) {
            Toast.makeText(app, R.string.UnifiedPushSettingsFragment__export_enrollment_failed_destination, Toast.LENGTH_LONG).show()
          }
        } else if (successMessage != null) {
          pendingExport.value = request.copy(message = successMessage)
        } else {
          pendingExport.value = null
          Toast.makeText(app, failureMessage, Toast.LENGTH_LONG).show()
        }
      }
    }
  }

  private fun showStaleDisclosure() {
    Toast.makeText(application, R.string.UnifiedPushSettingsFragment__disclosure_stale, Toast.LENGTH_LONG).show()
  }

  /** Clears unreadable local control state only after the user confirms server-side removal. */
  fun recoverCorruptControlState() {
    runPersistedControlAction { ControlReset.recoverCorrupt(application) }
  }

  fun forgetExport(requestId: String) {
    runPersistedControlAction { EnrollmentExporter.forgetIntent(requestId) }
  }

  fun forgetLegacyExport() {
    runPersistedControlAction {
      im.molly.unifiedpush.control.EndpointUpdates.forgetLegacyPending(
        im.molly.unifiedpush.control.UnifiedPushControlStore(SignalStore.unifiedpush)
      )
    }
  }

  private inline fun runPersistedControlAction(action: () -> Unit) {
    val persistenceFailed = try {
      action()
      false
    } catch (_: KeyValuePersistenceException) {
      true
    }
    if (persistenceFailed) {
      Toast.makeText(application, R.string.UnifiedPushSettingsFragment__export_enrollment_failed, Toast.LENGTH_LONG).show()
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

    // An export can finish after the settings screen is closed. Keep its unread result in memory.
    private val pendingExport = MutableLiveData<ExportState?>()

    internal fun unreadExport(): ExportState? = pendingExport.value?.takeIf { it.message != null && it.belongsToCurrentAccount() }

    internal fun acknowledgeExportResult(id: String) {
      if (unreadExport()?.id == id) pendingExport.value = null
    }
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

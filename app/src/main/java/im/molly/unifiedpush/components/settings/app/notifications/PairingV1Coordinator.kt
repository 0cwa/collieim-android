package im.molly.unifiedpush.components.settings.app.notifications

import android.content.Context
import android.net.Uri
import androidx.core.app.ShareCompat
import im.molly.unifiedpush.UnifiedPushDistributor
import im.molly.unifiedpush.control.ControlFrame
import im.molly.unifiedpush.control.ReceiverControlState
import im.molly.unifiedpush.control.EndpointReconciler
import im.molly.unifiedpush.control.Target
import im.molly.unifiedpush.enrollment.EnrollmentExporter
import im.molly.unifiedpush.enrollment.EnrollmentFile
import im.molly.unifiedpush.enrollment.ExportMode
import im.molly.unifiedpush.enrollment.PairingV1
import im.molly.unifiedpush.enrollment.PairingTransport
import okhttp3.HttpUrl
import im.molly.unifiedpush.enrollment.PairingRegistrationGate
import im.molly.unifiedpush.enrollment.PairingDistributorRegistration
import im.molly.unifiedpush.enrollment.RelayDisclosure
import im.molly.unifiedpush.enrollment.ReceiverEnrollmentV4
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition
import im.molly.unifiedpush.receiver.LockedPushState
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.jobs.UnifiedPushRefreshJob
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.service.KeyCachingService
import org.unifiedpush.android.connector.UnifiedPush
import org.signal.core.util.concurrent.SignalExecutors
import kotlinx.coroutines.runBlocking
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Owns the one in-memory phone-side pairing attempt. Request and receipt keys are never persisted. */
internal class PairingV1Coordinator(private val context: Context) {

  data class ConsentDetails(
    val offer: PairingV1.Offer,
    val distributorId: String,
    val distributorLabel: String,
    val providerOrigin: String,
    val accountId: String,
    val deviceId: Int,
    internal val endpoint: String,
    internal val p256dh: String,
    internal val auth: String,
    internal val instance: String,
  ) {
    override fun toString() = "PairingV1Coordinator.ConsentDetails(sessionId=${offer.sessionId}, distributorId=$distributorId, providerOrigin=$providerOrigin)"
  }

  data class RegistrationDetails(
    val offer: PairingV1.Offer,
    val distributorId: String,
    val distributorLabel: String,
    val currentOrigin: String?,
    val accountId: String,
  )

  sealed interface OfferPlan {
    data class Ready(val consent: ConsentDetails) : OfferPlan
    data class Register(val details: RegistrationDetails) : OfferPlan
    data class Failed(val message: String) : OfferPlan
  }

  sealed interface SealResult {
    data class Ready(val session: Session) : SealResult
    data class Failed(val message: String) : SealResult
  }

  data class Session internal constructor(
    val id: String,
    val offer: PairingV1.Offer,
    val requestJson: String,
    val deadlineSeconds: Long,
    internal val sealed: PairingV1.SealedRequest,
    internal val accountId: String,
    internal val deviceId: Int,
    internal val endpoint: String,
    internal val endpointP256dh: String,
    internal val endpointAuth: String,
    internal val instance: String,
    internal val distributorId: String,
    internal val vapidPublicKey: String,
    internal val disclosure: RelayDisclosure.Snapshot,
    internal val importRequestId: String,
    internal val importKeyId: String,
    internal val importEpoch: Long,
    internal val importDeviceId: Int,
    internal val importTarget: im.molly.unifiedpush.control.Target,
    internal val receiverStateJson: String? = null,
  ) {
    override fun toString() = "PairingV1Coordinator.Session(id=$id, deadlineSeconds=$deadlineSeconds)"
  }

  private var activeSession: Session? = null
  private var requestDispatched = false
  @Volatile private var registeringSessionId: String? = null

  /** Reserved intent is rejected before reading or changing registration state. */
  fun parseScan(json: String, nowSeconds: Long = System.currentTimeMillis() / 1000): PairingTransport.Scan {
    return PairingTransport.parse(json, nowSeconds)
  }

  /** Capture the complete destination shown in consent, including the live subscription and distributor. */
  fun consentDetails(offer: PairingV1.Offer): ConsentDetails? = synchronized(UnifiedPushLifecycle.monitor) {
    if (!isUsableAccountLocked()) return@synchronized null
    val tentative = PairingRegistrationGate.current()?.takeIf {
      it.binding.sessionId == offer.sessionId && it.binding.vapidPublicKey == offer.vapidPublicKey &&
        it.binding.providerOrigin == offer.providerOrigin && it.claimedEndpoint != null && pairingScopeMatchesLocked(it.binding)
    }
    val distributorId = tentative?.binding?.distributorId
      ?: UnifiedPush.getAckDistributor(context)?.takeIf { it == UnifiedPush.getSavedDistributor(context) }
      ?: return@synchronized null
    val endpoint = tentative?.claimedEndpoint?.url ?: SignalStore.unifiedpush.endpoint ?: return@synchronized null
    val origin = PairingV1.canonicalOrigin(endpoint) ?: return@synchronized null
    if (offer.providerOrigin != null && offer.providerOrigin != origin) return@synchronized null
    if (!hasOfferVapidBindingLocked(offer, distributorId)) return@synchronized null
    val target = canonicalTarget(
      endpoint,
      tentative?.claimedEndpoint?.p256dh ?: SignalStore.unifiedpush.endpointP256dh,
      tentative?.claimedEndpoint?.auth ?: SignalStore.unifiedpush.endpointAuth,
    ) ?: return@synchronized null
    val appLabel = try {
      context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(distributorId, 0)).toString()
    } catch (_: Exception) { distributorId }
    ConsentDetails(
      offer, distributorId, appLabel, origin, SignalStore.account.aci.toString(), SignalStore.account.deviceId,
      target.endpoint, target.p256dh, target.auth,
      tentative?.binding?.instance ?: (SignalStore.unifiedpush.endpointInstance ?: "default"),
    )
  }

  /** Chooses the safe first-use path without starting a registration. */
  fun evaluateOffer(offer: PairingV1.Offer, selectedDistributor: String? = null): OfferPlan = synchronized(UnifiedPushLifecycle.monitor) {
    if (System.currentTimeMillis() / 1000 >= offer.expiresAt) {
      return@synchronized OfferPlan.Failed(context.getString(R.string.PairingV1__offer_expired))
    }
    if (!isUsableAccountLocked()) return@synchronized OfferPlan.Failed(context.getString(R.string.PairingV1__endpoint_unavailable))
    if (isPromotedOfferLocked(offer)) return@synchronized OfferPlan.Failed(context.getString(R.string.PairingV1__offer_used))
    consentDetails(offer)?.takeIf { selectedDistributor == null || it.distributorId == selectedDistributor }?.let {
      return@synchronized OfferPlan.Ready(it)
    }
    val distributors = UnifiedPush.getDistributors(context)
    val values = SignalStore.unifiedpush
    val endpoint = values.endpoint
    val origin = endpoint?.let { PairingV1.canonicalOrigin(it) }
    val savedDistributor = UnifiedPush.getSavedDistributor(context)
    val distributorId = selectedDistributor ?: savedDistributor
    if (distributorId == null || distributorId !in distributors) {
      return@synchronized OfferPlan.Failed(context.getString(R.string.PairingV1__select_distributor_first))
    }
    val active = savedDistributor == distributorId && UnifiedPush.getAckDistributor(context) == distributorId
    if (endpoint != null && active && origin == null) {
      return@synchronized OfferPlan.Failed(context.getString(R.string.PairingV1__endpoint_unavailable))
    }
    if (offer.providerOrigin != null && active && origin != null && origin != offer.providerOrigin) {
      return@synchronized OfferPlan.Failed(context.getString(R.string.PairingV1__provider_server_mismatch, offer.hostLabel, offer.providerOrigin))
    }
    if (endpoint != null && active && hasOfferVapidBindingLocked(offer, distributorId)) {
      consentDetails(offer)?.let { return@synchronized OfferPlan.Ready(it) }
    }
    val label = try {
      context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(distributorId, 0)).toString()
    } catch (_: Exception) { distributorId }
    OfferPlan.Register(RegistrationDetails(offer, distributorId, label, origin.takeIf { active }, SignalStore.account.aci.toString()))
  }

  /** Starts a new isolated instance only after the caller has shown and received explicit registration consent. */
  fun registerForOffer(
    details: RegistrationDetails,
    disclosure: RelayDisclosure.Snapshot?,
    onComplete: (Result<PairingRegistrationGate.Endpoint>) -> Unit,
  ): Boolean {
    val snapshot = disclosure ?: return false
    if (System.currentTimeMillis() / 1000 >= details.offer.expiresAt) return false
    registeringSessionId = details.offer.sessionId
    var eligible = false
    val current = RelayDisclosure.runIfCurrent(snapshot) {
      synchronized(UnifiedPushLifecycle.monitor) {
        if (System.currentTimeMillis() / 1000 >= details.offer.expiresAt) return@synchronized
        val previousDistributor = snapshot.configuration.distributor ?: return@synchronized
        if (!isUsableAccountLocked() || details.distributorId !in UnifiedPush.getDistributors(context)) return@synchronized
        val origin = SignalStore.unifiedpush.endpoint?.let { PairingV1.canonicalOrigin(it) }
        if (previousDistributor == details.distributorId && details.offer.providerOrigin != null && SignalStore.unifiedpush.endpoint != null &&
          UnifiedPushDistributor.checkIfActive() && origin != null && origin != details.offer.providerOrigin) return@synchronized
        eligible = true
      }
    }
    if (!current || !eligible) return false

    SignalExecutors.BOUNDED.execute {
      var registration: PairingRegistrationGate.Binding? = null
      try {
        runBlocking {
          UnifiedPushAccountTransition.withTransition(
            expectedGeneration = snapshot.generation,
          ) {
            val started = synchronized(UnifiedPushLifecycle.monitor) {
              val endpoint = SignalStore.unifiedpush.endpoint
              val origin = endpoint?.let { PairingV1.canonicalOrigin(it) }
              val config = snapshot.configuration
              val previousDistributor = config.distributor ?: return@synchronized false
              val account = UnifiedPushAccountTransition.captureContextLocked()
              val approvedAccount = snapshot.account
              val accountCurrent = account != null && account.accountId == approvedAccount.accountId &&
                account.localDeviceId == approvedAccount.localDeviceId && account.endpoint == approvedAccount.endpoint &&
                account.endpointP256dh == approvedAccount.endpointP256dh && account.endpointAuth == approvedAccount.endpointAuth &&
                account.instance == approvedAccount.instance &&
                LockedPushState.peekPending(context) == null &&
                !KeyCachingService.isLocked() && !SignalStore.account.isUnauthorizedReceived &&
                SignalStore.account.isRegistered && SignalStore.account.aci?.toString() == details.accountId &&
                approvedAccount.accountId == details.accountId
              val configCurrent = SignalStore.unifiedpush.airGapped == config.manual &&
                SignalStore.unifiedpush.mollySocketUrl == config.url &&
                SignalStore.unifiedpush.vapidPublicKey == config.vapid &&
                SignalStore.settings.preferredNotificationMethod == config.deliveryMethod &&
                UnifiedPush.getSavedDistributor(context) == previousDistributor
              val providerCurrent = previousDistributor != details.distributorId || details.offer.providerOrigin == null || endpoint == null ||
                !UnifiedPushDistributor.checkIfActive() || origin == details.offer.providerOrigin
              val acknowledged = UnifiedPush.getAckDistributor(context)
              val distributorCurrent = if (previousDistributor == details.distributorId) {
                acknowledged == null || acknowledged == previousDistributor
              } else endpoint != null && acknowledged == previousDistributor
              if (registeringSessionId != details.offer.sessionId ||
                System.currentTimeMillis() / 1000 >= details.offer.expiresAt || !accountCurrent || !configCurrent ||
                !providerCurrent || !distributorCurrent || details.distributorId !in UnifiedPush.getDistributors(context)) {
                false
              } else {
                val created = PairingRegistrationGate.begin(
                  details.distributorId,
                  details.offer,
                  SignalStore.account.aci?.toString() ?: return@synchronized false,
                  SignalStore.account.deviceId,
                  lifecycleGeneration = UnifiedPushLifecycle.currentGeneration(),
                  previousEndpoint = SignalStore.unifiedpush.endpoint,
                  previousP256dh = SignalStore.unifiedpush.endpointP256dh,
                  previousAuth = SignalStore.unifiedpush.endpointAuth,
                  previousInstance = SignalStore.unifiedpush.endpointInstance ?: "default",
                  previousDistributorId = previousDistributor,
                  onComplete = onComplete,
                ) ?: return@synchronized false
                registration = created
                try {
                  PairingDistributorRegistration.register(context, created)
                  true
                } catch (_: Exception) {
                  PairingRegistrationGate.fail(created.instance)
                  discardCandidate(created)
                  false
                }
              }
            }
            if (!started && registration == null) onComplete(Result.failure(IllegalStateException("Pairing registration approval is stale")))
          }
        }
      } catch (_: Exception) {
        registration?.let {
          PairingRegistrationGate.fail(it.instance)
          discardCandidate(it)
        } ?: onComplete(Result.failure(IllegalStateException("Pairing registration could not start")))
      }
    }
    return true
  }

  /**
   * Called only from the explicit positive consent action. It rechecks the captured disclosure and live
   * endpoint/distributor under the lifecycle monitor immediately before creating any enrollment intent.
   */
  @Synchronized
  fun sealAfterConsent(
    offer: PairingV1.Offer,
    disclosure: RelayDisclosure.Snapshot?,
    consented: ConsentDetails,
    serverEpoch: Long?,
    allowRecovery: Boolean,
    bridgeDeviceId: Int? = null,
  ): SealResult {
    if (System.currentTimeMillis() / 1000 >= offer.expiresAt) return SealResult.Failed(context.getString(R.string.PairingV1__offer_expired))
    if (offer != consented.offer) return SealResult.Failed(context.getString(R.string.PairingV1__offer_changed))
    if (synchronized(UnifiedPushLifecycle.monitor) { isUsableAccountLocked() && isPromotedOfferLocked(offer) }) {
      return SealResult.Failed(context.getString(R.string.PairingV1__offer_used))
    }
    activeSession?.takeIf { it.offer == offer && sessionStillCurrent(it) }?.let { return SealResult.Ready(it) }

    var outcome: SealResult = SealResult.Failed(context.getString(R.string.PairingV1__endpoint_changed))
    var maySeal = false
    val disclosureCurrent = RelayDisclosure.runIfCurrent(disclosure) {
      synchronized(UnifiedPushLifecycle.monitor) {
        val current = consentDetails(offer)
        if (isUsableAccountLocked() && isPromotedOfferLocked(offer)) {
          outcome = SealResult.Failed(context.getString(R.string.PairingV1__offer_used))
        } else if (!isUsableAccountLocked() || current == null || current.endpoint != consented.endpoint ||
          current.p256dh != consented.p256dh || current.auth != consented.auth || current.instance != consented.instance ||
          current.accountId != consented.accountId || current.deviceId != consented.deviceId ||
          current.providerOrigin != consented.providerOrigin || current.distributorId != consented.distributorId) {
          outcome = SealResult.Failed(context.getString(R.string.PairingV1__endpoint_changed))
        } else if (!hasOfferVapidBindingLocked(offer, consented.distributorId)) {
          outcome = SealResult.Failed(context.getString(R.string.PairingV1__vapid_registration_unavailable))
        } else {
          maySeal = true
        }
      }
    }
    if (!disclosureCurrent) return SealResult.Failed(context.getString(R.string.UnifiedPushSettingsFragment__disclosure_stale))
    // exportIfCurrent takes its locks before UnifiedPushLifecycle.monitor. Keep this call outside
    // runIfCurrent's monitor section to preserve the enrollment exporter's global lock order.
    return if (!maySeal) outcome else if (offer.intent == "add_receiver") {
      createReceiverRequest(offer, disclosure, consented, bridgeDeviceId)
    } else createSealedRequest(offer, disclosure, consented, serverEpoch, allowRecovery)
  }

  private fun createReceiverRequest(
    offer: PairingV1.Offer,
    disclosure: RelayDisclosure.Snapshot?,
    consented: ConsentDetails,
    bridgeDeviceId: Int?,
  ): SealResult {
    var sealedCandidate: PairingV1.SealedRequest? = null
    return try {
      val snapshot = disclosure ?: throw EnrollmentFile.Invalid("disclosure_changed")
      val deviceId = bridgeDeviceId?.takeIf { it >= 2 } ?: throw EnrollmentFile.Invalid("invalid_enrollment")
      val requestId = ReceiverEnrollmentV4.newUuid()
      val receiverId = ReceiverEnrollmentV4.newUuid()
      val controlKey = EnrollmentFile.newControlKey()
      val enrollment = ReceiverEnrollmentV4.render(ReceiverEnrollmentV4.Input(
        requestId = requestId,
        accountId = consented.accountId,
        bridgeDeviceId = deviceId,
        endpoint = consented.endpoint,
        p256dh = consented.p256dh,
        auth = consented.auth,
        instance = consented.instance,
        receiverId = receiverId,
        controlKey = controlKey,
        label = ReceiverEnrollmentV4.sanitizedLabel(offer.hostLabel),
      ))
      val sealed = PairingV1.seal(offer, enrollment, consented.endpoint)
      sealedCandidate = sealed
      val receiverState = ReceiverControlState(
        receiverId = receiverId,
        importId = requestId,
        accountId = consented.accountId,
        bridgeDeviceId = deviceId,
        instance = consented.instance,
        hostLabel = ReceiverEnrollmentV4.sanitizedLabel(offer.hostLabel),
        controlKey = controlKey,
        epoch = 1,
        target = Target(consented.endpoint, consented.p256dh, consented.auth),
      ).serialize()
      val created = Session(
        id = offer.sessionId,
        offer = offer,
        requestJson = sealed.json,
        deadlineSeconds = offer.expiresAt,
        sealed = sealed,
        accountId = consented.accountId,
        deviceId = consented.deviceId,
        endpoint = consented.endpoint,
        endpointP256dh = consented.p256dh,
        endpointAuth = consented.auth,
        instance = consented.instance,
        distributorId = consented.distributorId,
        vapidPublicKey = offer.vapidPublicKey,
        disclosure = snapshot,
        importRequestId = requestId,
        importKeyId = "",
        importEpoch = 1,
        importDeviceId = deviceId,
        importTarget = Target(consented.endpoint, consented.p256dh, consented.auth),
        receiverStateJson = receiverState,
      )
      if (!persistReceiverAttempt(created, receiverState)) throw EnrollmentFile.Invalid("disclosure_changed")
      activeSession?.sealed?.receiptKey?.fill(0)
      activeSession = created
      requestDispatched = false
      sealedCandidate = null
      SealResult.Ready(created)
    } catch (e: EnrollmentFile.Invalid) {
      sealedCandidate?.receiptKey?.fill(0)
      SealResult.Failed(context.getString(if (e.code == "disclosure_changed") R.string.UnifiedPushSettingsFragment__disclosure_stale else R.string.PairingV1__request_failed))
    } catch (_: Exception) {
      sealedCandidate?.receiptKey?.fill(0)
      SealResult.Failed(context.getString(R.string.PairingV1__request_failed))
    }
  }

  /** Persist the v4 identity before either HTTP submission or the encrypted Share/Save fallback. */
  private fun persistReceiverAttempt(session: Session, receiverStateJson: String): Boolean {
    val receiver = ReceiverControlState.parse(receiverStateJson) ?: return false
    var persisted = false
    val disclosureCurrent = RelayDisclosure.runIfCurrent(session.disclosure) {
      synchronized(UnifiedPushLifecycle.monitor) {
        if (!sessionBindingMatchesLocked(session) || System.currentTimeMillis() / 1000 >= session.deadlineSeconds) return@synchronized
        val gate = PairingRegistrationGate.current()
        val candidate = gate?.takeIf {
          it.binding.sessionId == session.offer.sessionId && it.binding.instance == session.instance &&
            it.binding.accountId == session.accountId && it.binding.deviceId == session.deviceId
        }
        if (gate != null && candidate == null) return@synchronized
        val priorReceiver = SignalStore.unifiedpush.v4ReceiverState
        try {
          SignalStore.unifiedpush.commitV4ReceiverState(receiver.serialize())
          if (!LockedPushState.savePairingConfirmation(context, LockedPushState.PairingConfirmation(
              session.offer.sessionId, session.instance, session.distributorId, session.vapidPublicKey,
              session.offer.providerOrigin, session.deadlineSeconds, session.offer.hostLabel, session.importRequestId,
              "", session.importEpoch, RelayDisclosure.pairingScopeDigest(session.disclosure), receiverId = receiver.receiverId,
            ))) {
            SignalStore.unifiedpush.commitV4ReceiverState(priorReceiver)
            return@synchronized
          }
          if (candidate != null) {
            val endpoint = candidate.claimedEndpoint ?: run {
              LockedPushState.clearPairingConfirmation(context, session.offer.sessionId)
              SignalStore.unifiedpush.commitV4ReceiverState(priorReceiver)
              return@synchronized
            }
            val bound = PairingRegistrationGate.bindImport(
              candidate.binding.instance,
              PairingRegistrationGate.ImportBinding(
                session.importRequestId, "", session.importEpoch, endpoint.url,
                endpoint.p256dh.orEmpty(), endpoint.auth.orEmpty(), session.accountId, session.deviceId,
                session.instance, receiver.receiverId,
              ),
            )
            if (!bound) {
              LockedPushState.clearPairingConfirmation(context, session.offer.sessionId)
              SignalStore.unifiedpush.commitV4ReceiverState(priorReceiver)
              return@synchronized
            }
          }
          // The receiver must reject plaintext immediately, including while this request is shared for later import.
          LockedPushState.setEncryptedEnrollment(context, true)
          persisted = true
        } catch (_: Exception) {
          LockedPushState.clearPairingConfirmation(context, session.offer.sessionId)
          runCatching { SignalStore.unifiedpush.commitV4ReceiverState(priorReceiver) }
        }
      }
    }
    return disclosureCurrent && persisted
  }

  private fun createSealedRequest(
    offer: PairingV1.Offer,
    disclosure: RelayDisclosure.Snapshot?,
    consented: ConsentDetails,
    serverEpoch: Long?,
    allowRecovery: Boolean,
  ): SealResult {
    var sealedCandidate: PairingV1.SealedRequest? = null
    return try {
      var created: Session? = null
      val approvedSnapshot = disclosure ?: throw EnrollmentFile.Invalid("disclosure_changed")
      val lease = UnifiedPushAccountTransition.acquireLease(approvedSnapshot.generation)
        ?: throw EnrollmentFile.Invalid("disclosure_changed")
      lease.use {
        var snapshot = approvedSnapshot
        if (SignalStore.unifiedpush.device == null) {
          if (UnifiedPushRefreshJob().provisionBridgeCredential(context, lease) == null) throw EnrollmentFile.Invalid("not_ready")
          snapshot = RelayDisclosure.capture(context) ?: throw EnrollmentFile.Invalid("disclosure_changed")
          if (snapshot.account.accountId != approvedSnapshot.account.accountId ||
            snapshot.account.localDeviceId != approvedSnapshot.account.localDeviceId ||
            snapshot.configuration != approvedSnapshot.configuration || !activeBindingStillMatches(consented)) {
            throw EnrollmentFile.Invalid("disclosure_changed")
          }
        }
        RelayDisclosure.exportIfCurrent(snapshot, ExportMode.NEW_INTENT, null) {
          if (System.currentTimeMillis() / 1000 >= offer.expiresAt) throw EnrollmentFile.Invalid("offer_expired")
          val target = Target(consented.endpoint, consented.p256dh, consented.auth)
          val importedIntent = EnrollmentExporter.exportForPairing(
          context = context,
          isDistributorActive = { activeBindingStillMatches(consented) },
          target = target,
          instance = consented.instance,
          serverEpoch = serverEpoch,
          allowRecovery = allowRecovery,
          validateBeforeCommit = { intent ->
            if (System.currentTimeMillis() / 1000 >= offer.expiresAt) throw EnrollmentFile.Invalid("offer_expired")
            val disclosureStillCurrent = RelayDisclosure.runIfCurrent(snapshot) {}
            val values = SignalStore.unifiedpush
            val device = values.device
            val current = consentDetails(offer)
            if (!disclosureStillCurrent || device == null || current == null ||
              current.endpoint != consented.endpoint || current.p256dh != consented.p256dh || current.auth != consented.auth ||
              current.providerOrigin != consented.providerOrigin || current.distributorId != consented.distributorId ||
              current.instance != consented.instance || current.accountId != consented.accountId || current.deviceId != consented.deviceId ||
              !hasOfferVapidBindingLocked(offer, consented.distributorId) ||
              intent.accountId != SignalStore.account.aci?.toString() || intent.deviceId != device.deviceId ||
              intent.password != device.password || intent.target != target ||
              intent.instance != consented.instance || intent.accountId != consented.accountId ||
              !UnifiedPushDistributor.checkIfActive()) {
              throw EnrollmentFile.Invalid("disclosure_changed")
            }
          },
          ) { candidate, bridgeCredential, enrollment ->
          val sealed = PairingV1.seal(offer, enrollment, consented.endpoint)
          try {
            if (System.currentTimeMillis() / 1000 >= offer.expiresAt) throw EnrollmentFile.Invalid("offer_expired")
            val values = SignalStore.unifiedpush
            val device = values.device ?: throw EnrollmentFile.Invalid("not_ready")
            if (!activeBindingStillMatches(consented) || candidate.accountId != consented.accountId ||
              candidate.deviceId != bridgeCredential.deviceId || bridgeCredential.deviceId != device.deviceId ||
              candidate.password != bridgeCredential.password || candidate.password != device.password ||
              candidate.target != target ||
              candidate.instance != consented.instance ||
              SignalStore.account.aci?.toString() != consented.accountId || SignalStore.account.deviceId != consented.deviceId ||
              !hasOfferVapidBindingLocked(offer, consented.distributorId)) {
              throw EnrollmentFile.Invalid("disclosure_changed")
            }
            val accountId = SignalStore.account.aci?.toString() ?: throw EnrollmentFile.Invalid("not_ready")
            created = Session(
              id = offer.sessionId,
              offer = offer,
              requestJson = sealed.json,
              deadlineSeconds = offer.expiresAt,
              sealed = sealed,
              accountId = accountId,
              deviceId = SignalStore.account.deviceId,
              endpoint = consented.endpoint,
              endpointP256dh = consented.p256dh,
              endpointAuth = consented.auth,
              instance = consented.instance,
              distributorId = consented.distributorId,
              vapidPublicKey = offer.vapidPublicKey,
              disclosure = snapshot,
              importRequestId = candidate.requestId,
              importKeyId = candidate.keyId,
              importEpoch = candidate.epoch,
              importDeviceId = candidate.deviceId,
              importTarget = candidate.target,
            )
            if (System.currentTimeMillis() / 1000 >= offer.expiresAt) throw EnrollmentFile.Invalid("offer_expired")
            sealedCandidate = sealed
          } catch (e: Exception) {
            sealed.receiptKey.fill(0)
            throw e
          }
          }
          PairingRegistrationGate.current()?.takeIf { it.binding.instance == consented.instance }?.let { pending ->
            val bound = PairingRegistrationGate.bindImport(
              pending.binding.instance,
              PairingRegistrationGate.ImportBinding(
                importedIntent.requestId, importedIntent.keyId, importedIntent.epoch,
                importedIntent.target.endpoint, importedIntent.target.p256dh, importedIntent.target.auth,
                importedIntent.accountId, importedIntent.deviceId, importedIntent.instance,
              ),
            )
            if (!bound) throw EnrollmentFile.Invalid("disclosure_changed")
          }
          if (!LockedPushState.savePairingConfirmation(context, LockedPushState.PairingConfirmation(
              offer.sessionId, consented.instance, consented.distributorId, offer.vapidPublicKey, offer.providerOrigin,
              offer.expiresAt, offer.hostLabel, importedIntent.requestId, importedIntent.keyId, importedIntent.epoch,
              RelayDisclosure.pairingScopeDigest(snapshot),
            ))) throw EnrollmentFile.Invalid("state_unavailable")
        }
      }
      val session = checkNotNull(created)
      activeSession?.sealed?.receiptKey?.fill(0)
      activeSession = session
      requestDispatched = false
      sealedCandidate = null
      SealResult.Ready(session)
    } catch (e: EnrollmentFile.Invalid) {
      sealedCandidate?.receiptKey?.fill(0)
      SealResult.Failed(context.getString(when (e.code) {
        "disclosure_changed" -> R.string.UnifiedPushSettingsFragment__disclosure_stale
        "not_ready" -> R.string.PairingV1__endpoint_unavailable
        "offer_expired" -> R.string.PairingV1__offer_expired
        "recovery_ack_required" -> R.string.UnifiedPushSettingsFragment__export_enrollment_recovery_ack_required
        "pending_limit" -> R.string.UnifiedPushSettingsFragment__export_enrollment_pending_limit
        else -> R.string.PairingV1__request_failed
      }))
    } catch (_: IOException) {
      sealedCandidate?.receiptKey?.fill(0)
      SealResult.Failed(context.getString(R.string.PairingV1__request_failed))
    } catch (_: Exception) {
      sealedCandidate?.receiptKey?.fill(0)
      SealResult.Failed(context.getString(R.string.PairingV1__request_failed))
    }
  }

  /** Authenticates an outer receipt before publishing any receipt text as accepted. */
  @Synchronized
  private fun acceptReceipt(sessionId: String, json: String): PairingTransport.Result {
    val session = activeSession?.takeIf { it.id == sessionId } ?: return PairingTransport.Result.Stale
    if (System.currentTimeMillis() / 1000 >= session.deadlineSeconds) return PairingTransport.Result.Stale
    try {
      PairingV1.openReceipt(session.offer, session.sealed, json)
    } catch (_: Exception) {
      return PairingTransport.Result.InvalidResponse
    }
    if (session.receiverStateJson != null) {
      val state = ReceiverControlState.parse(session.receiverStateJson) ?: return PairingTransport.Result.InvalidResponse
      if (KeyCachingService.isLocked()) {
        session.sealed.receiptKey.fill(0)
        activeSession = null
        registeringSessionId = null
        requestDispatched = false
        return PairingTransport.Result.AwaitingPush
      }
      val stillCurrent = synchronized(UnifiedPushLifecycle.monitor) {
        val stored = ReceiverControlState.parse(SignalStore.unifiedpush.v4ReceiverState)
        sessionBindingMatchesLocked(session) && stored != null &&
          stored.receiverId == state.receiverId && stored.importId == state.importId
      }
      if (!stillCurrent) return PairingTransport.Result.Stale
      session.sealed.receiptKey.fill(0)
      activeSession = null
      registeringSessionId = null
      requestDispatched = false
      // The receipt authenticates the POST, while enrollment_imported is the v4 proof that
      // the bridge applied this receiver. Keep the persisted identity and wait for that event.
      return PairingTransport.Result.AwaitingPush
    }
    val promoted = !KeyCachingService.isLocked() && promoteReceiptConfirmedRoute(session)
    if (!promoted && !KeyCachingService.isLocked()) return PairingTransport.Result.Stale
    session.sealed.receiptKey.fill(0)
    activeSession = null
    registeringSessionId = null
    // A matching v3 event can still confirm the durably exported candidate on unlock.
    return if (promoted) PairingTransport.Result.Accepted else PairingTransport.Result.AwaitingPush
  }

  /** Promote the isolated connector only after the receipt authenticates the exact sealed request. */
  private fun promoteReceiptConfirmedRoute(session: Session): Boolean {
    val approved = synchronized(UnifiedPushLifecycle.monitor) {
      if (System.currentTimeMillis() / 1000 >= session.deadlineSeconds || !hasExactLocalImportLocked(session)) return@synchronized null
      val tentative = PairingRegistrationGate.current()?.takeIf {
        it.binding.instance == session.instance && it.binding.sessionId == session.offer.sessionId &&
          it.binding.accountId == session.accountId && it.binding.deviceId == session.deviceId
      }
      if (tentative != null) {
        val endpoint = tentative.claimedEndpoint ?: return@synchronized null
        if (!sessionBindingMatchesLocked(session) || endpoint.url != session.endpoint || endpoint.p256dh != session.endpointP256dh ||
          endpoint.auth != session.endpointAuth || endpoint.instance != session.instance) return@synchronized null
        Triple(tentative.binding, endpoint, UnifiedPushLifecycle.currentGeneration())
      } else {
        if (!sessionBindingMatchesLocked(session)) return@synchronized null
        val current = consentDetails(session.offer)
        val persisted = SignalStore.unifiedpush.pairingEndpointBinding
        val reusable = current?.instance == session.instance && current.endpoint == session.endpoint &&
          current.p256dh == session.endpointP256dh && current.auth == session.endpointAuth &&
          (persisted == null || persisted.instance == session.instance && persisted.sessionId == session.offer.sessionId)
        if (!reusable) return@synchronized null
        Triple(null, null, UnifiedPushLifecycle.currentGeneration())
      }
    } ?: return false
    val (binding, endpoint, expectedGeneration) = approved
    if (binding == null || endpoint == null) return promoteReusedEndpoint(session)
    val promoted = try {
      runBlocking {
        UnifiedPushAccountTransition.withTransition(
          expectedGeneration = expectedGeneration,
          readyAfter = { UnifiedPushAccountTransition.hasCoherentRegisteredContext() && !KeyCachingService.isLocked() },
        ) {
          synchronized(UnifiedPushLifecycle.monitor) {
            val current = PairingRegistrationGate.current()
            val values = SignalStore.unifiedpush
            val routeStillMatches = System.currentTimeMillis() / 1000 < binding.expiresAt &&
              current?.binding == binding && current.claimedEndpoint == endpoint &&
              values.endpoint == binding.previousEndpoint && values.endpointP256dh == binding.previousP256dh &&
              values.endpointAuth == binding.previousAuth && (values.endpointInstance ?: "default") == binding.previousInstance &&
              SignalStore.account.aci?.toString() == binding.accountId && SignalStore.account.deviceId == binding.deviceId &&
              binding.matchesDistributorSelection(UnifiedPush.getSavedDistributor(context), UnifiedPush.getAckDistributor(context))
            if (routeStillMatches && hasExactLocalImportLocked(session)) {
              val origin = PairingV1.canonicalOrigin(endpoint.url)
              if (origin != null) {
                values.setPairingEndpointRegistration(
                  endpoint.url, endpoint.p256dh, endpoint.auth,
                  org.thoughtcrime.securesms.keyvalue.UnifiedPushValues.PairingEndpointBinding(
                    binding.instance, binding.distributorId, binding.vapidPublicKey, binding.providerOrigin, origin,
                    binding.sessionId, binding.expiresAt, binding.accountId, binding.deviceId, session.offer.hostLabel, session.importRequestId,
                  ),
                )
                values.enabled = true
                SignalStore.settings.preferredNotificationMethod =
                  org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod.UNIFIEDPUSH
                LockedPushState.setEncryptedEnrollment(context, true)
                LockedPushState.setProtectedPairingRoute(context, binding.instance, binding.distributorId)
                PairingDistributorRegistration.promote(context, binding)
                PairingRegistrationGate.promote(binding.instance)
                true
              } else false
            } else false
          }
        }
      }
    } catch (_: Exception) { false }
    if (promoted) {
      activateUnifiedPushAfterPromotion()
    }
    return promoted
  }

  /** Drain pre-promotion relay work before making an already active reused route permanently scoped. */
  private fun promoteReusedEndpoint(session: Session): Boolean {
    val promoted = try {
      runBlocking {
        UnifiedPushAccountTransition.withTransition(
          expectedGeneration = synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushLifecycle.currentGeneration() },
          readyAfter = { UnifiedPushAccountTransition.hasCoherentRegisteredContext() && !KeyCachingService.isLocked() },
        ) {
          synchronized(UnifiedPushLifecycle.monitor) {
            if (System.currentTimeMillis() / 1000 >= session.deadlineSeconds ||
              !sessionBindingMatchesLocked(session, duringTransition = true) || !hasExactLocalImportLocked(session) ||
              PairingRegistrationGate.isRegistrationPending()) return@synchronized false
            val values = SignalStore.unifiedpush
            val origin = PairingV1.canonicalOrigin(session.endpoint) ?: return@synchronized false
            val existing = values.pairingEndpointBinding
            val binding = existing ?: org.thoughtcrime.securesms.keyvalue.UnifiedPushValues.PairingEndpointBinding(
              session.instance, session.distributorId, session.vapidPublicKey, session.offer.providerOrigin, origin,
              session.offer.sessionId, session.deadlineSeconds, session.accountId, session.deviceId, session.offer.hostLabel, session.importRequestId,
            )
            if (existing != null && (existing.instance != session.instance || existing.sessionId != session.offer.sessionId)) {
              return@synchronized false
            }
            values.setPairingEndpointRegistration(session.endpoint, session.endpointP256dh, session.endpointAuth, binding)
            values.enabled = true
            SignalStore.settings.preferredNotificationMethod =
              org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod.UNIFIEDPUSH
            LockedPushState.setEncryptedEnrollment(context, true)
            LockedPushState.setProtectedPairingRoute(context, session.instance, session.distributorId)
            true
          }
        }
      }
    } catch (_: Exception) { false }
    if (promoted) activateUnifiedPushAfterPromotion()
    return promoted
  }

  /** Applies the existing service and network switch without running the legacy relay refresh job. */
  private fun activateUnifiedPushAfterPromotion() {
    SignalExecutors.BOUNDED.execute {
      ApplicationContext.getInstance(context).activatePushNotificationServicesAfterPairing()
    }
  }

  fun submit(session: Session, submitUrl: HttpUrl?): PairingTransport.Result {
    val ready = synchronized(this) {
      val current = activeSession === session && System.currentTimeMillis() / 1000 < session.deadlineSeconds && sessionStillCurrent(session)
      if (current && submitUrl != null) requestDispatched = true
      current
    }
    if (!ready) return PairingTransport.Result.Stale
    var receipt: PairingTransport.Result = PairingTransport.Result.InvalidResponse
    val submitted = PairingTransport.submit(submitUrl, session.requestJson) {
      receipt = acceptReceipt(session.id, it)
      receipt != PairingTransport.Result.InvalidResponse
    }
    return if (submitted == PairingTransport.Result.Accepted) receipt else submitted
  }

  @Synchronized
  fun shareRequest(context: Context, sessionId: String): Boolean {
    val session = activeSession?.takeIf { it.id == sessionId } ?: return false
    return runIfCurrentSession(session) {
      ShareCompat.IntentBuilder(context)
        .setType("application/json")
        .setText(session.requestJson)
        .setChooserTitle(R.string.PairingV1__share_request)
        .startChooser()
      requestDispatched = true
    }
  }

  fun writeRequest(context: Context, sessionId: String, uri: Uri): Boolean {
    val session = synchronized(this) {
      activeSession?.takeIf {
        it.id == sessionId && runIfCurrentSession(it) {
          // A provider may publish bytes before the write or close reports success.
          requestDispatched = true
        }
      }
    } ?: return false
    // The provider receives only the immutable sealed envelope. Provider I/O must not hold the
    // account monitor, since an external DocumentsProvider may block even while closing a file.
    try {
      val stream = context.contentResolver.openOutputStream(uri, "wt") ?: return false
      stream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(session.requestJson) }
      return true
    } catch (_: IOException) {
      return false
    } catch (_: SecurityException) {
      return false
    }
  }

  private fun runIfCurrentSession(session: Session, action: () -> Unit): Boolean {
    if (System.currentTimeMillis() / 1000 >= session.deadlineSeconds) return false
    var completed = false
    val disclosureCurrent = RelayDisclosure.runIfCurrent(session.disclosure) {
      synchronized(UnifiedPushLifecycle.monitor) {
        if (sessionBindingMatchesLocked(session) && System.currentTimeMillis() / 1000 < session.deadlineSeconds) {
          action()
          completed = true
        }
      }
    }
    return disclosureCurrent && completed
  }

  @Synchronized
  fun clear() {
    val session = activeSession
    session?.sealed?.receiptKey?.fill(0)
    activeSession = null
    // A shared request can still finish through an authenticated push after the settings screen closes.
    synchronized(UnifiedPushLifecycle.monitor) {
      val attempt = PairingRegistrationGate.current()?.takeIf { !requestDispatched && it.binding.sessionId == registeringSessionId }
      if (attempt != null) {
        PairingRegistrationGate.cancel(attempt.binding.instance)
        discardCandidate(attempt.binding)
      }
      if (!requestDispatched && session != null) {
        clearUnconfirmedReceiverAttemptLocked(session.id, session.receiverStateJson)
        LockedPushState.clearPairingConfirmation(context, session.id)
      }
      registeringSessionId = null
    }
    requestDispatched = false
  }

  @Synchronized
  fun cancelOfferAttempt(sessionId: String) {
    synchronized(UnifiedPushLifecycle.monitor) {
      if (registeringSessionId == sessionId) registeringSessionId = null
      val session = activeSession?.takeIf { it.id == sessionId }
      clearUnconfirmedReceiverAttemptLocked(sessionId, session?.receiverStateJson)
      LockedPushState.clearPairingConfirmation(context, sessionId)
      PairingRegistrationGate.current()?.takeIf { it.binding.sessionId == sessionId }?.let { attempt ->
        PairingRegistrationGate.cancel(attempt.binding.instance)
        discardCandidate(attempt.binding)
      }
    }
    if (activeSession?.offer?.sessionId == sessionId) {
      activeSession?.sealed?.receiptKey?.fill(0)
      activeSession = null
      requestDispatched = false
    }
  }

  /** Cancellation forgets an unconfirmed v4 identity; an authenticated import event wins the race. */
  private fun clearUnconfirmedReceiverAttemptLocked(sessionId: String, sessionState: String?) {
    check(Thread.holdsLock(UnifiedPushLifecycle.monitor))
    val confirmation = LockedPushState.peekPairingConfirmation(context)?.takeIf {
      it.sessionId == sessionId && it.receiverId != null
    }
    val expected = sessionState?.let { ReceiverControlState.parse(it) }
      ?: confirmation?.receiverId?.let { id -> ReceiverControlState.parse(SignalStore.unifiedpush.v4ReceiverState)?.takeIf { it.receiverId == id } }
    val stored = ReceiverControlState.parse(SignalStore.unifiedpush.v4ReceiverState)
    if (expected != null && stored?.receiverId == expected.receiverId && stored.importId == expected.importId && !stored.confirmed) {
      val values = SignalStore.unifiedpush
      values.commitV4ReceiverState(null)
      val control = values.controlStateRead.stateOrNull
      LockedPushState.setEncryptedEnrollment(context, control?.let { it.activeEpoch > 0 || it.activeImportId != null } == true)
    }
  }

  private fun activeBindingStillMatches(consented: ConsentDetails): Boolean = synchronized(UnifiedPushLifecycle.monitor) {
    consentDetails(consented.offer)?.let {
      it.endpoint == consented.endpoint && it.providerOrigin == consented.providerOrigin &&
        it.p256dh == consented.p256dh && it.auth == consented.auth && it.instance == consented.instance &&
        it.accountId == consented.accountId && it.deviceId == consented.deviceId && it.distributorId == consented.distributorId
    } == true
  }

  private fun sessionStillCurrent(session: Session): Boolean {
    var matches = false
    val disclosureCurrent = RelayDisclosure.runIfCurrent(session.disclosure) {
      synchronized(UnifiedPushLifecycle.monitor) {
        matches = sessionBindingMatchesLocked(session)
      }
    }
    return disclosureCurrent && matches
  }

  private fun sessionBindingMatchesLocked(session: Session, duringTransition: Boolean = false): Boolean {
    val usableAccount = if (duringTransition) isUsableAccountDuringTransitionLocked() else isUsableAccountLocked()
    if (!usableAccount || SignalStore.account.aci?.toString() != session.accountId ||
      SignalStore.account.deviceId != session.deviceId || !hasOfferVapidBindingLocked(session.offer, session.distributorId)) return false
    val values = SignalStore.unifiedpush
    val tentative = PairingRegistrationGate.current()?.takeIf { it.binding.sessionId == session.offer.sessionId }
    if (tentative == null && (UnifiedPush.getSavedDistributor(context) != session.distributorId ||
        UnifiedPush.getAckDistributor(context) != session.distributorId)) return false
    val endpoint = tentative?.claimedEndpoint?.url ?: values.endpoint ?: return false
    val target = canonicalTarget(
      endpoint, tentative?.claimedEndpoint?.p256dh ?: values.endpointP256dh,
      tentative?.claimedEndpoint?.auth ?: values.endpointAuth,
    ) ?: return false
    val instance = tentative?.binding?.instance ?: (values.endpointInstance ?: "default")
    val origin = PairingV1.canonicalOrigin(endpoint) ?: return false
    return (session.offer.providerOrigin == null || session.offer.providerOrigin == origin) &&
      target == session.importTarget && instance == session.instance
  }

  private fun canonicalTarget(endpoint: String, p256dh: String?, auth: String?): Target? {
    if (p256dh == null || auth == null) return null
    return try {
      val subscription = ControlFrame.Subscription(endpoint, p256dh, auth)
      Target(subscription.endpoint, subscription.p256dh, subscription.auth)
    } catch (_: EnrollmentFile.Invalid) {
      null
    }
  }

  /** The receipt has no import id; only the sealed request's exact durable intent can authorize promotion. */
  private fun hasExactLocalImportLocked(session: Session): Boolean {
    check(Thread.holdsLock(UnifiedPushLifecycle.monitor))
    val state = SignalStore.unifiedpush.controlStateRead.stateOrNull ?: return false
    fun exact(intent: im.molly.unifiedpush.control.ImportIntent): Boolean =
      intent.requestId == session.importRequestId && intent.keyId == session.importKeyId &&
        intent.epoch == session.importEpoch && intent.instance == session.instance &&
        intent.accountId == session.accountId && intent.deviceId == session.importDeviceId && intent.target == session.importTarget
    val eligible = state.intents.any { it.eligible && exact(it) }
    val active = state.activeImportSeq > 0 && state.activeImportId == session.importRequestId && state.activeKeyId == session.importKeyId &&
      state.activeEpoch == session.importEpoch && state.activeInstance == session.instance &&
      state.activeAccountId == session.accountId && state.activeDeviceId == session.importDeviceId &&
      state.serverTarget == session.importTarget
    return eligible || active
  }

  private fun isUsableAccountLocked(): Boolean =
    Thread.holdsLock(UnifiedPushLifecycle.monitor) && UnifiedPushLifecycle.isReadyLocked() &&
      !KeyCachingService.isLocked() && !SignalStore.account.isUnauthorizedReceived &&
      SignalStore.account.isRegistered && SignalStore.account.aci != null

  private fun isPromotedOfferLocked(offer: PairingV1.Offer): Boolean =
    SignalStore.unifiedpush.pairingEndpointBinding?.sessionId == offer.sessionId

  private fun isUsableAccountDuringTransitionLocked(): Boolean =
    Thread.holdsLock(UnifiedPushLifecycle.monitor) && !KeyCachingService.isLocked() &&
      !SignalStore.account.isUnauthorizedReceived && SignalStore.account.isRegistered && SignalStore.account.aci != null &&
      SignalStore.account.hasAciIdentityKey() && SignalStore.account.servicePassword != null &&
      SignalStore.account.deviceId >= SignalServiceAddress.DEFAULT_DEVICE_ID

  private fun hasOfferVapidBindingLocked(offer: PairingV1.Offer, distributorId: String): Boolean {
    check(Thread.holdsLock(UnifiedPushLifecycle.monitor))
    val values = SignalStore.unifiedpush
    val tentative = PairingRegistrationGate.current()?.takeIf { it.binding.sessionId == offer.sessionId }
    if (tentative != null) {
      val endpoint = tentative.claimedEndpoint
      return endpoint != null && pairingScopeMatchesLocked(tentative.binding) &&
        tentative.binding.vapidPublicKey == offer.vapidPublicKey &&
        tentative.binding.providerOrigin == offer.providerOrigin &&
        tentative.binding.distributorId == distributorId && endpoint.instance == tentative.binding.instance
    }
    val pairing = values.pairingEndpointBinding
    return if (pairing != null) {
      pairing.sessionId == offer.sessionId && pairing.vapidPublicKey == offer.vapidPublicKey &&
        pairing.providerOrigin == offer.providerOrigin && pairing.instance == values.endpointInstance &&
        pairing.endpointOrigin == values.endpoint?.let { PairingV1.canonicalOrigin(it) } &&
        pairing.distributorId == distributorId && pairing.accountId == SignalStore.account.aci?.toString() &&
        pairing.deviceId == SignalStore.account.deviceId && UnifiedPush.getSavedDistributor(context) == distributorId
    } else {
      !PairingRegistrationGate.isPairingInstance(values.endpointInstance ?: "") && values.vapidPublicKey == offer.vapidPublicKey
    }
  }

  private fun pairingScopeMatchesLocked(binding: PairingRegistrationGate.Binding): Boolean {
    check(Thread.holdsLock(UnifiedPushLifecycle.monitor))
    val values = SignalStore.unifiedpush
    val account = UnifiedPushAccountTransition.captureContextLocked() ?: return false
    return UnifiedPushLifecycle.currentGeneration() == binding.lifecycleGeneration &&
      values.endpoint == binding.previousEndpoint && values.endpointP256dh == binding.previousP256dh &&
      values.endpointAuth == binding.previousAuth && (values.endpointInstance ?: "default") == binding.previousInstance &&
      account.accountId == binding.accountId && account.localDeviceId == binding.deviceId &&
      binding.matchesDistributorSelection(UnifiedPush.getSavedDistributor(context), UnifiedPush.getAckDistributor(context))
  }

  private fun discardCandidate(binding: PairingRegistrationGate.Binding) {
    synchronized(UnifiedPushLifecycle.monitor) {
      PairingDistributorRegistration.discard(context, binding)
      LockedPushState.clearPairingConfirmation(context, binding.sessionId)
    }
    SignalExecutors.BOUNDED.execute { EndpointReconciler.reconcile(retryPending = true) }
  }

}

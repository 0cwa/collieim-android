package im.molly.unifiedpush.enrollment

import java.util.UUID

/** In-memory admission for one offer-bound UnifiedPush registration. A restart drops any uncommitted request. */
object PairingRegistrationGate {

  data class Binding(
    val instance: String,
    val distributorId: String,
    val vapidPublicKey: String,
    val providerOrigin: String?,
    val sessionId: String,
    val expiresAt: Long,
    val accountId: String,
    val deviceId: Int,
    val lifecycleGeneration: Long = 0,
    val previousEndpoint: String? = null,
    val previousP256dh: String? = null,
    val previousAuth: String? = null,
    val previousInstance: String = "default",
    val hostLabel: String,
    val previousDistributorId: String = distributorId,
    val disclosure: RelayDisclosure.Snapshot? = null,
  ) {
    fun matchesDistributorSelection(saved: String?, acknowledged: String?): Boolean =
      saved == previousDistributorId && acknowledged == previousDistributorId

    override fun toString() = "PairingRegistrationGate.Binding(sessionId=$sessionId, instance=$instance)"
  }

  data class Endpoint(val url: String, val p256dh: String?, val auth: String?, val instance: String) {
    override fun toString() = "PairingRegistrationGate.Endpoint(instance=$instance)"
  }

  data class BufferedEndpoint(val url: String?, val p256dh: String?, val auth: String?, val instance: String)

  /** Exact identity of the local sealed v3 import. A receipt does not carry this identity. */
  data class ImportBinding(
    val requestId: String,
    val keyId: String,
    val epoch: Long,
    val endpoint: String,
    val p256dh: String,
    val auth: String,
    val accountId: String,
    val deviceId: Int,
    val instance: String,
    val receiverId: String? = null,
  )

  data class Pending internal constructor(
    val binding: Binding,
    internal val onComplete: (Result<Endpoint>) -> Unit,
  ) {
    internal var claimedEndpoint: Endpoint? = null
    internal var completionDelivered = false
    internal var bufferedEndpoint: BufferedEndpoint? = null
    internal var bufferedUnregistration = false
    @Volatile var importBinding: ImportBinding? = null
      internal set
  }

  private val lock = Any()
  private var pending: Pending? = null

  fun begin(
    distributorId: String,
    offer: PairingV1.Offer,
    accountId: String,
    deviceId: Int,
    lifecycleGeneration: Long = 0,
    previousEndpoint: String? = null,
    previousP256dh: String? = null,
    previousAuth: String? = null,
    previousInstance: String = "default",
    previousDistributorId: String = distributorId,
    disclosure: RelayDisclosure.Snapshot? = null,
    onComplete: (Result<Endpoint>) -> Unit,
  ): Binding? {
    current()
    return synchronized(lock) {
      if (pending != null || System.currentTimeMillis() / 1000 >= offer.expiresAt) return@synchronized null
      val binding = Binding(
        instance = "pairing-v1-${UUID.randomUUID()}",
        distributorId = distributorId,
        vapidPublicKey = offer.vapidPublicKey,
        providerOrigin = offer.providerOrigin,
        sessionId = offer.sessionId,
        expiresAt = offer.expiresAt,
        accountId = accountId,
        deviceId = deviceId,
        lifecycleGeneration = lifecycleGeneration,
        previousEndpoint = previousEndpoint,
        previousP256dh = previousP256dh,
        previousAuth = previousAuth,
        previousInstance = previousInstance,
        hostLabel = offer.hostLabel,
        previousDistributorId = previousDistributorId,
        disclosure = disclosure,
      )
      pending = Pending(binding, onComplete)
      binding
    }
  }

  fun current(nowSeconds: Long = System.currentTimeMillis() / 1000): Pending? {
    val check = synchronized(lock) { currentLocked(nowSeconds) }
    check.expired?.onComplete(Result.failure(IllegalStateException("Pairing offer expired")))
    return check.active
  }

  /** Keeps a tentative connector callback separate from the active route's durable lock buffer. */
  fun bufferEndpoint(endpoint: BufferedEndpoint) {
    synchronized(lock) {
      pending?.takeIf { it.binding.instance == endpoint.instance }?.bufferedEndpoint = endpoint
    }
  }

  fun bufferUnregistration(instance: String) {
    synchronized(lock) {
      pending?.takeIf { it.binding.instance == instance }?.bufferedUnregistration = true
    }
  }

  fun takeBufferedEndpoint(): BufferedEndpoint? = synchronized(lock) {
    pending?.also { if (it.bufferedUnregistration) it.bufferedEndpoint = null }?.bufferedEndpoint.also {
      pending?.bufferedEndpoint = null
    }
  }

  fun takeBufferedUnregistration(): String? = synchronized(lock) {
    pending?.takeIf { it.bufferedUnregistration }?.also {
      it.bufferedUnregistration = false
      it.bufferedEndpoint = null
    }?.binding?.instance
  }

  /** Binds the registration to the exact intent committed by the pairing exporter. */
  fun bindImport(instance: String, identity: ImportBinding): Boolean = synchronized(lock) {
    val active = pending?.takeIf { it.binding.instance == instance } ?: return@synchronized false
    val binding = active.binding
    val endpoint = active.claimedEndpoint ?: return@synchronized false
    val validIdentity = if (identity.receiverId == null) {
      identity.keyId.isNotBlank()
    } else {
      UUID_V4.matches(identity.receiverId) && UUID_V4.matches(identity.requestId) && identity.keyId.isEmpty()
    }
    if (!validIdentity || identity.instance != instance || identity.accountId != binding.accountId ||
      identity.endpoint != endpoint.url || identity.p256dh != endpoint.p256dh || identity.auth != endpoint.auth ||
      identity.epoch <= 0 || identity.requestId.isBlank()) return@synchronized false
    active.importBinding = identity
    true
  }

  /** Restore a previously consented, durably exported candidate after process restart. */
  internal fun restore(binding: Binding, endpoint: Endpoint, identity: ImportBinding): Boolean = synchronized(lock) {
    if (pending != null || System.currentTimeMillis() / 1000 >= binding.expiresAt) return@synchronized false
    pending = Pending(binding) {}.apply {
      claimedEndpoint = endpoint
      completionDelivered = true
      importBinding = identity
    }
    true
  }

  /** Admission before the reducer still checks the saved distributor and Signal account. */
  fun allowsCandidateConfirmation(
    instance: String,
    savedDistributor: String?,
    ackDistributor: String?,
    accountId: String?,
    deviceId: Int,
  ): Boolean = synchronized(lock) {
    val active = pending?.takeIf { it.binding.instance == instance } ?: return@synchronized false
    val binding = active.binding
    val identity = active.importBinding ?: return@synchronized false
    binding.matchesDistributorSelection(savedDistributor, ackDistributor) &&
      accountId == binding.accountId && deviceId == binding.deviceId && identity.instance == instance
  }

  fun isPairingInstance(instance: String): Boolean = instance.startsWith("pairing-v1-")

  /** While locked the receiver can consult only this persisted instance gate, never SignalStore. */
  fun allowsLockedCallback(instance: String, protectedPairingInstance: String?): Boolean =
    protectedPairingInstance == null || instance == protectedPairingInstance

  fun allowsEndpointCallback(
    instance: String,
    persistedPairingInstance: String?,
    activeRouteInstance: String? = null,
  ): Boolean {
    val active = current()
    if (active != null) return active.binding.instance == instance ||
      persistedPairingInstance == instance || activeRouteInstance == instance
    if (persistedPairingInstance != null) return persistedPairingInstance == instance
    return !isPairingInstance(instance)
  }

  /** Only the exact instance, distributor, account, and offer origin may claim a callback. */
  fun claim(
    instance: String,
    savedDistributor: String?,
    ackDistributor: String?,
    accountId: String?,
    deviceId: Int,
    endpoint: Endpoint,
    nowSeconds: Long = System.currentTimeMillis() / 1000,
  ): Pending? {
    var expired: Pending? = null
    val accepted = synchronized(lock) {
      val check = currentLocked(nowSeconds)
      expired = check.expired
      val active = check.active ?: return@synchronized null
      val binding = active.binding
      if (instance != binding.instance || endpoint.instance != binding.instance || endpoint.p256dh == null || endpoint.auth == null ||
        !binding.matchesDistributorSelection(savedDistributor, ackDistributor) ||
        accountId != binding.accountId || deviceId != binding.deviceId) return@synchronized null
      val origin = PairingV1.canonicalOrigin(endpoint.url) ?: return@synchronized null
      if (binding.providerOrigin != null && binding.providerOrigin != origin) return@synchronized null
      active.claimedEndpoint = endpoint
      active
    }
    expired?.onComplete(Result.failure(IllegalStateException("Pairing offer expired")))
    return accepted
  }

  fun complete(
    instance: String,
    endpoint: Endpoint,
    success: Boolean,
    nowSeconds: Long = System.currentTimeMillis() / 1000,
  ) {
    var completion: Pair<Pending, Result<Endpoint>>? = null
    synchronized(lock) {
      val check = currentLocked(nowSeconds)
      if (check.expired != null) {
        completion = check.expired to Result.failure(IllegalStateException("Pairing offer expired"))
      } else {
        val active = check.active?.takeIf { it.binding.instance == instance }
        if (active != null) {
          if (!success || active.claimedEndpoint != endpoint) {
            pending = null
            completion = active to Result.failure(IllegalStateException("Pairing registration was not committed"))
          } else if (!active.completionDelivered) {
            active.completionDelivered = true
            completion = active to Result.success(endpoint)
          }
        }
      }
    }
    completion?.let { (active, result) -> active.onComplete(result) }
  }

  /** Removes the tentative route only after a receipt or matching import confirms it. */
  fun promote(instance: String): Pending? = synchronized(lock) {
    pending?.takeIf { it.binding.instance == instance && it.claimedEndpoint != null }?.also { pending = null }
  }

  fun fail(instance: String) {
    val active = synchronized(lock) {
      pending?.takeIf { it.binding.instance == instance }?.also { pending = null }
    } ?: return
    active.onComplete(Result.failure(IllegalStateException("Pairing registration failed")))
  }

  fun cancel(instance: String) {
    synchronized(lock) {
      if (pending?.binding?.instance == instance) pending = null
    }
  }

  fun isRegistrationPending(): Boolean = current() != null

  fun blocksLegacyRelay(hasProtectedPairingEndpoint: Boolean): Boolean =
    isRegistrationPending() || hasProtectedPairingEndpoint

  fun allowsMessageCallback(
    instance: String,
    protectedPairingInstance: String?,
    registrationPending: Boolean,
    activeRouteInstance: String? = null,
  ): Boolean {
    if (registrationPending) return instance != current()?.binding?.instance &&
      (protectedPairingInstance == instance || activeRouteInstance == instance)
    return if (protectedPairingInstance == null) !isPairingInstance(instance) else instance == protectedPairingInstance
  }

  private data class Current(val active: Pending?, val expired: Pending?)
  private val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

  /** Caller holds [lock]. Expiry callbacks run only after the lock is released. */
  private fun currentLocked(nowSeconds: Long): Current {
    val active = pending ?: return Current(null, null)
    if (nowSeconds < active.binding.expiresAt) return Current(active, null)
    pending = null
    return Current(null, active)
  }
}

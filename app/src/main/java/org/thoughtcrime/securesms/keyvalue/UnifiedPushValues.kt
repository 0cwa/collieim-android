package org.thoughtcrime.securesms.keyvalue

import im.molly.unifiedpush.control.ControlState
import im.molly.unifiedpush.control.ControlStateRead
import im.molly.unifiedpush.control.CorruptControlStateException
import im.molly.unifiedpush.health.PushHealth
import im.molly.unifiedpush.model.MollySocketDevice
import im.molly.unifiedpush.model.RegistrationStatus
import im.molly.unifiedpush.receiver.EncryptedPush
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.KeyValuePersistenceException
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.SignalStoreValues
import org.signal.storage.keyvalue.booleanValue
import org.signal.storage.keyvalue.longValue
import org.signal.storage.keyvalue.stringValue

class UnifiedPushValues(store: KeyValueStore) : SignalStoreValues(store) {

  companion object {
    private val TAG = Log.tag(UnifiedPushValues::class)

    private const val MOLLYSOCKET_DEVICE_ID = "mollysocket.deviceId"
    private const val MOLLYSOCKET_PASSWORD = "mollysocket.passwd"
    private const val MOLLYSOCKET_PROVISIONING_PENDING = "mollysocket.provisioning.pending"
    private const val RELAY_CLEANUP_ACCOUNT = "mollysocket.cleanup.account"
    private const val RELAY_CLEANUP_LOCAL_DEVICE = "mollysocket.cleanup.localDevice"
    private const val RELAY_CLEANUP_DEVICE = "mollysocket.cleanup.device"
    private const val RELAY_CLEANUP_PASSWORD = "mollysocket.cleanup.password"
    private const val RELAY_CLEANUP_UNLINK_CONFIRMED = "mollysocket.cleanup.unlinkConfirmed"
    private const val RELAY_CLEANUP_CLEAR_CONTROL_UPDATE = "mollysocket.cleanup.clearControlUpdate"
    private const val MOLLYSOCKET_STATUS = "mollysocket.status"
    private const val MOLLYSOCKET_AIR_GAPPED = "mollysocket.airGapped"
    private const val MOLLYSOCKET_URL = "mollysocket.url"
    private const val MOLLYSOCKET_VAPID = "mollysocket.vapid"
    private const val MOLLYSOCKET_VAPID_SYNCED = "mollysocket.vapid.synced"
    private const val UNIFIEDPUSH_ENABLED = "up.enabled"
    private const val UNIFIEDPUSH_ENDPOINT = "up.endpoint"
    private const val UNIFIEDPUSH_ENDPOINT_P256DH = "up.endpoint.p256dh"
    private const val UNIFIEDPUSH_ENDPOINT_AUTH = "up.endpoint.auth"
    private const val UNIFIEDPUSH_ENDPOINT_INSTANCE = "up.endpoint.instance"
    private const val UNIFIEDPUSH_PAIRING_INSTANCE = "up.pairing.instance"
    private const val UNIFIEDPUSH_PAIRING_DISTRIBUTOR = "up.pairing.distributor"
    private const val UNIFIEDPUSH_PAIRING_VAPID = "up.pairing.vapid"
    private const val UNIFIEDPUSH_PAIRING_ORIGIN = "up.pairing.origin"
    private const val UNIFIEDPUSH_PAIRING_ENDPOINT_ORIGIN = "up.pairing.endpointOrigin"
    private const val UNIFIEDPUSH_PAIRING_SESSION = "up.pairing.session"
    private const val UNIFIEDPUSH_PAIRING_EXPIRY = "up.pairing.expiry"
    private const val UNIFIEDPUSH_PAIRING_ACCOUNT = "up.pairing.account"
    private const val UNIFIEDPUSH_PAIRING_DEVICE = "up.pairing.device"
    private const val UNIFIEDPUSH_PAIRING_HOST_LABEL = "up.pairing.hostLabel"
    private const val UNIFIEDPUSH_PAIRING_IMPORT_ID = "up.pairing.importId"
    private const val UNIFIEDPUSH_ENROLLMENT_EPOCH = "up.enrollment.epoch"
    private const val UNIFIEDPUSH_PENDING_EPOCH = "up.enrollment.pendingEpoch"
    private const val UNIFIEDPUSH_REPLAY_STATE = "up.enrollment.replay"
    private const val MAX_ENROLLMENT_EPOCH = 9007199254740991L
    private const val UNIFIEDPUSH_STATUS_CODE = "up.enrollment.statusCode"
    private const val UNIFIEDPUSH_CONTROL_STATE = "up.control.state"
    private const val UNIFIEDPUSH_V4_RECEIVER_STATE = "up.v4.receiver.state"
    private const val UNIFIEDPUSH_V4_PENDING_RECEIVER_STATE = "up.v4.pendingReceiver.state"
    private val controlLock = Any()
    private const val UNIFIEDPUSH_HEALTH_FAILURES = "up.health.failures"
    private const val UNIFIEDPUSH_HEALTH_FALLBACK = "up.health.fallback"
    private const val UNIFIEDPUSH_HEALTH_RESULT = "up.health.result"
    private const val UNIFIEDPUSH_HEALTH_RESULT_AT = "up.health.resultAt"
    private const val UNIFIEDPUSH_HEALTH_MISS_AT = "up.health.missAt"
    private const val UNIFIEDPUSH_REGISTRATION_FAILED = "up.registrationFailed"
    private const val UNIFIEDPUSH_RETRY_SCHEDULED = "up.retryScheduled"
    private const val UNIFIEDPUSH_LAST_RECEIVED_TIME = "up.lastRecvTime"
    const val UNIFIEDPUSH_ACCOUNT_TRANSITION_PENDING = "up.account.transition.pending"
  }

  public override fun onFirstEverAppLaunch() = Unit

  override val keysToIncludeInBackup: List<String> get() = emptyList()

  @get:JvmName("isEnabled")
  var enabled: Boolean by booleanValue(UNIFIEDPUSH_ENABLED, false)

  var device: MollySocketDevice?
    get() {
      return MollySocketDevice(
        deviceId = getInteger(MOLLYSOCKET_DEVICE_ID, 0),
        password = getString(MOLLYSOCKET_PASSWORD, null) ?: return null,
      )
    }
    set(device) {
      store.beginWrite()
        .putInteger(MOLLYSOCKET_DEVICE_ID, device?.deviceId ?: 0)
        .putString(MOLLYSOCKET_PASSWORD, device?.password)
        .remove(MOLLYSOCKET_PROVISIONING_PENDING)
        .apply()
    }

  /** The exact credential must remain unavailable even if the write landed but its acknowledgement failed. */
  fun isProvisioningPending(device: MollySocketDevice): Boolean {
    if (!getBoolean(MOLLYSOCKET_PROVISIONING_PENDING, false)) return false
    val snapshot = store.beginRead()
    return snapshot.getBoolean(MOLLYSOCKET_PROVISIONING_PENDING, false) &&
      snapshot.getInteger(MOLLYSOCKET_DEVICE_ID, 0) == device.deviceId &&
      snapshot.getString(MOLLYSOCKET_PASSWORD, null) == device.password
  }

  @Throws(KeyValuePersistenceException::class)
  fun commitProvisionedDevice(device: MollySocketDevice) {
    store.beginWrite()
      .putInteger(MOLLYSOCKET_DEVICE_ID, device.deviceId)
      .putString(MOLLYSOCKET_PASSWORD, device.password)
      .putBoolean(MOLLYSOCKET_VAPID_SYNCED, true)
      .putBoolean(MOLLYSOCKET_PROVISIONING_PENDING, true)
      .commitAndWaitForPersistence()
  }

  /** Release only after the credential write returned successfully. A failed release must never trigger unlink. */
  @Throws(KeyValuePersistenceException::class)
  fun acknowledgeProvisionedDevice() {
    store.beginWrite().remove(MOLLYSOCKET_PROVISIONING_PENDING).commitAndWaitForPersistence()
  }

  @Throws(KeyValuePersistenceException::class)
  fun commitClearedDevice() {
    store.beginWrite()
      .putInteger(MOLLYSOCKET_DEVICE_ID, 0)
      .putString(MOLLYSOCKET_PASSWORD, null)
      .remove(MOLLYSOCKET_PROVISIONING_PENDING)
      .commitAndWaitForPersistence()
  }

  data class RelayCleanup(
    val accountId: String,
    val localDeviceId: Int,
    val device: MollySocketDevice,
    // Lifecycle generations are process-local. Startup has no in-memory invalidation to complete.
    val invalidationGeneration: Long? = null,
    val unlinkConfirmed: Boolean = true,
    // An unpublished credential that never reached storage does not own the saved control update.
    val clearControlUpdate: Boolean = true,
  ) {
    fun hasSameOwner(other: RelayCleanup): Boolean =
      accountId == other.accountId && localDeviceId == other.localDeviceId && device == other.device &&
        invalidationGeneration == other.invalidationGeneration

    override fun toString() = "RelayCleanup(localDeviceId=$localDeviceId)"
  }

  @Volatile private var activeRelayCleanup: RelayCleanup? = null

  val pendingRelayCleanup: RelayCleanup?
    get() {
      activeRelayCleanup?.let { return it }
      if (!store.containsKey(RELAY_CLEANUP_ACCOUNT)) return null
      val snapshot = store.beginRead()
      val accountId = snapshot.getString(RELAY_CLEANUP_ACCOUNT, null) ?: return null
      val password = checkNotNull(snapshot.getString(RELAY_CLEANUP_PASSWORD, null))
      return RelayCleanup(
        accountId,
        snapshot.getInteger(RELAY_CLEANUP_LOCAL_DEVICE, 0),
        MollySocketDevice(snapshot.getInteger(RELAY_CLEANUP_DEVICE, 0), password),
        // Records from before intent persistence were written only after unlink was confirmed.
        unlinkConfirmed = snapshot.getBoolean(RELAY_CLEANUP_UNLINK_CONFIRMED, true),
        clearControlUpdate = snapshot.getBoolean(RELAY_CLEANUP_CLEAR_CONTROL_UPDATE, true),
      )
    }

  /** Persist the cleanup phase before advancing to its next remote or local effect. */
  fun setRelayCleanup(cleanup: RelayCleanup, pending: Boolean) {
    val write = store.beginWrite()
    if (pending) {
      activeRelayCleanup = cleanup
      write.putString(RELAY_CLEANUP_ACCOUNT, cleanup.accountId)
        .putInteger(RELAY_CLEANUP_LOCAL_DEVICE, cleanup.localDeviceId)
        .putInteger(RELAY_CLEANUP_DEVICE, cleanup.device.deviceId)
        .putString(RELAY_CLEANUP_PASSWORD, cleanup.device.password)
        .putBoolean(RELAY_CLEANUP_UNLINK_CONFIRMED, cleanup.unlinkConfirmed)
        .putBoolean(RELAY_CLEANUP_CLEAR_CONTROL_UPDATE, cleanup.clearControlUpdate)
    } else {
      write.remove(RELAY_CLEANUP_ACCOUNT).remove(RELAY_CLEANUP_LOCAL_DEVICE)
        .remove(RELAY_CLEANUP_DEVICE).remove(RELAY_CLEANUP_PASSWORD)
        .remove(RELAY_CLEANUP_UNLINK_CONFIRMED).remove(RELAY_CLEANUP_CLEAR_CONTROL_UPDATE)
    }
    write.commitAndWaitForPersistence()
    if (!pending) activeRelayCleanup = null
  }

  fun isMollySocketDevice(deviceId: Int): Boolean =
    deviceId != 0 && getInteger(MOLLYSOCKET_DEVICE_ID, 0) == deviceId

  var registrationStatus: RegistrationStatus
    get() = RegistrationStatus.fromValue(getInteger(MOLLYSOCKET_STATUS, -1)) ?: RegistrationStatus.UNKNOWN
    set(status) {
      putInteger(MOLLYSOCKET_STATUS, status.value)
    }

  var endpoint: String? by stringValue(UNIFIEDPUSH_ENDPOINT, null)

  /** Receiver keys (base64url) and connector instance delivered with [endpoint]; null when unregistered. */
  var endpointP256dh: String? by stringValue(UNIFIEDPUSH_ENDPOINT_P256DH, null)

  var endpointAuth: String? by stringValue(UNIFIEDPUSH_ENDPOINT_AUTH, null)

  var endpointInstance: String? by stringValue(UNIFIEDPUSH_ENDPOINT_INSTANCE, null)

  /** Pairing-created routes stay isolated from any previously configured legacy relay across restarts. */
  data class PairingEndpointBinding(
    val instance: String,
    val distributorId: String,
    val vapidPublicKey: String,
    val providerOrigin: String?,
    val endpointOrigin: String,
    val sessionId: String,
    val expiresAt: Long,
    val accountId: String,
    val deviceId: Int,
    val hostLabel: String? = null,
    val importId: String? = null,
  ) {
    override fun toString() = "PairingEndpointBinding(sessionId=$sessionId, instance=$instance)"
  }

  /** A route and its owner metadata to publish with an authenticated v4 promotion. */
  data class PairingEndpointRegistration(
    val endpoint: String,
    val p256dh: String?,
    val auth: String?,
    val binding: PairingEndpointBinding,
  )

  val pairingEndpointBinding: PairingEndpointBinding?
    get() {
      val snapshot = store.beginRead()
      val instance = snapshot.getString(UNIFIEDPUSH_PAIRING_INSTANCE, null) ?: return null
      val distributor = snapshot.getString(UNIFIEDPUSH_PAIRING_DISTRIBUTOR, null) ?: return null
      val vapid = snapshot.getString(UNIFIEDPUSH_PAIRING_VAPID, null) ?: return null
      val session = snapshot.getString(UNIFIEDPUSH_PAIRING_SESSION, null) ?: return null
      val account = snapshot.getString(UNIFIEDPUSH_PAIRING_ACCOUNT, null) ?: return null
      val origin = snapshot.getString(UNIFIEDPUSH_PAIRING_ORIGIN, null)
      val endpointOrigin = snapshot.getString(UNIFIEDPUSH_PAIRING_ENDPOINT_ORIGIN, null) ?: return null
      val expiry = snapshot.getLong(UNIFIEDPUSH_PAIRING_EXPIRY, 0L)
      val deviceId = snapshot.getInteger(UNIFIEDPUSH_PAIRING_DEVICE, 0)
      if (expiry <= 0L || deviceId <= 0) return null
      return PairingEndpointBinding(
        instance, distributor, vapid, origin, endpointOrigin, session, expiry, account, deviceId,
        snapshot.getString(UNIFIEDPUSH_PAIRING_HOST_LABEL, null),
        snapshot.getString(UNIFIEDPUSH_PAIRING_IMPORT_ID, null),
      )
    }

  /** Publishes one connector route tuple so readers never combine a new URL with old key material or instance. */
  fun setEndpointRegistration(endpoint: String?, p256dh: String?, auth: String?, instance: String?) {
    store.beginWrite()
      .putString(UNIFIEDPUSH_ENDPOINT, endpoint)
      .putString(UNIFIEDPUSH_ENDPOINT_P256DH, p256dh)
      .putString(UNIFIEDPUSH_ENDPOINT_AUTH, auth)
      .putString(UNIFIEDPUSH_ENDPOINT_INSTANCE, instance)
      .remove(UNIFIEDPUSH_PAIRING_INSTANCE)
      .remove(UNIFIEDPUSH_PAIRING_DISTRIBUTOR)
      .remove(UNIFIEDPUSH_PAIRING_VAPID)
      .remove(UNIFIEDPUSH_PAIRING_ORIGIN)
      .remove(UNIFIEDPUSH_PAIRING_ENDPOINT_ORIGIN)
      .remove(UNIFIEDPUSH_PAIRING_SESSION)
      .remove(UNIFIEDPUSH_PAIRING_EXPIRY)
      .remove(UNIFIEDPUSH_PAIRING_ACCOUNT)
      .remove(UNIFIEDPUSH_PAIRING_DEVICE)
      .remove(UNIFIEDPUSH_PAIRING_HOST_LABEL)
      .remove(UNIFIEDPUSH_PAIRING_IMPORT_ID)
      .apply()
  }

  fun setPairingEndpointRegistration(
    endpoint: String?, p256dh: String?, auth: String?, binding: PairingEndpointBinding,
  ) {
    store.beginWrite()
      .putString(UNIFIEDPUSH_ENDPOINT, endpoint)
      .putString(UNIFIEDPUSH_ENDPOINT_P256DH, p256dh)
      .putString(UNIFIEDPUSH_ENDPOINT_AUTH, auth)
      .putString(UNIFIEDPUSH_ENDPOINT_INSTANCE, binding.instance)
      .putString(UNIFIEDPUSH_PAIRING_INSTANCE, binding.instance)
      .putString(UNIFIEDPUSH_PAIRING_DISTRIBUTOR, binding.distributorId)
      .putString(UNIFIEDPUSH_PAIRING_VAPID, binding.vapidPublicKey)
      .putString(UNIFIEDPUSH_PAIRING_ORIGIN, binding.providerOrigin)
      .putString(UNIFIEDPUSH_PAIRING_ENDPOINT_ORIGIN, binding.endpointOrigin)
      .putString(UNIFIEDPUSH_PAIRING_SESSION, binding.sessionId)
      .putLong(UNIFIEDPUSH_PAIRING_EXPIRY, binding.expiresAt)
      .putString(UNIFIEDPUSH_PAIRING_ACCOUNT, binding.accountId)
      .putInteger(UNIFIEDPUSH_PAIRING_DEVICE, binding.deviceId)
      .putString(UNIFIEDPUSH_PAIRING_HOST_LABEL, binding.hostLabel)
      .putString(UNIFIEDPUSH_PAIRING_IMPORT_ID, binding.importId)
      .apply()
  }

  /**
   * Epoch of the encrypted enrollment the server has proven it imported (0: none). While it is set the receiver
   * accepts no plaintext wake. It is set only when an event proves it, never by an export.
   */
  var enrollmentEpoch: Long
    get() = controlState.activeEpoch
    set(value) {
      mutateControl {
        if (it.activeImportId != null && value < 1) throw IllegalArgumentException("An active v3 enrollment needs a positive epoch")
        it.copy(activeEpoch = value, highestEpoch = maxOf(it.highestEpoch, value))
      }
    }

  /** Compatibility view for a pending candidate or migrated unknown export. */
  var pendingEnrollmentEpoch: Long
    get() = controlState.pendingEpoch
    set(value) {
      mutateControl {
        val old = it.legacyPending
        it.copy(legacyPending = when {
          value <= 0 -> null
          else -> im.molly.unifiedpush.control.LegacyPending(old?.key, value, old?.target)
        })
      }
    }

  /** Encrypted push replay state (clock high-water mark and live event ids), see [im.molly.unifiedpush.receiver.EncryptedPush]. */
  var replayState: String? by stringValue(UNIFIEDPUSH_REPLAY_STATE, null)

  /** Sealed receiver identity and retryable receiver-specific control frame; stored in encrypted key-value state. */
  var v4ReceiverState: String? by stringValue(UNIFIEDPUSH_V4_RECEIVER_STATE, null)

  /** Replacement candidate is separate so cancellation cannot remove the currently active v4 receiver. */
  var v4PendingReceiverState: String? by stringValue(UNIFIEDPUSH_V4_PENDING_RECEIVER_STATE, null)

  /** Durable fail-closed marker for identity writes that may have stopped before coherent registration completed. */
  val hasIncompleteAccountTransition: Boolean
    get() = getBoolean(UNIFIEDPUSH_ACCOUNT_TRANSITION_PENDING, false)

  /** Acknowledges the marker write before identity mutation or readiness publication. */
  fun setIncompleteAccountTransition(pending: Boolean) {
    store.beginWrite()
      .putBoolean(UNIFIEDPUSH_ACCOUNT_TRANSITION_PENDING, pending)
      .commitAndWaitForPersistence()
  }

  /** Typed view of the stored record. Corrupt serialized data never falls through to the legacy epoch keys. */
  val controlStateRead: ControlStateRead
    get() = when (val stored = ControlState.parse(getString(UNIFIEDPUSH_CONTROL_STATE, null))) {
      ControlStateRead.Absent -> {
        val active = getLong(UNIFIEDPUSH_ENROLLMENT_EPOCH, 0)
        val pending = getLong(UNIFIEDPUSH_PENDING_EPOCH, 0)
        when {
          active !in 0L..MAX_ENROLLMENT_EPOCH || pending !in 0L..MAX_ENROLLMENT_EPOCH -> ControlStateRead.Corrupt
          active == 0L && pending == 0L -> ControlStateRead.Absent
          else -> ControlStateRead.Legacy(
            ControlState(
              activeEpoch = active,
              highestEpoch = maxOf(active, pending),
              legacyPending = pending.takeIf { it > 0 }?.let { im.molly.unifiedpush.control.LegacyPending(null, it, null) },
            )
          )
        }
      }
      else -> stored
    }

  /** Keys, epochs, the server subscription and the unconfirmed endpoint update as one record. */
  val controlState: ControlState
    get() = controlStateRead.stateOrNull ?: throw CorruptControlStateException()

  val hasCorruptControlState: Boolean
    get() = controlStateRead == ControlStateRead.Corrupt

  val controlKey: String? get() = controlState.key

  /**
   * The only writer of the control state. One lock, the state re-read inside it, one durable write that also carries
   * [replay], the encrypted-push replay state, when given. Returns the state that was written.
   */
  fun mutateControl(replay: String? = null, block: (ControlState) -> ControlState): ControlState {
    return synchronized(UnifiedPushLifecycle.monitor) {
      mutateControl(replay, replayIfAbsent = null, v4ReceiverState = null, block = block)
    }
  }

  /** Atomically persists a v4 receiver transition with the event replay record that authorized it. */
  fun mutateControlWithReceiverAndReplay(
    replay: String,
    receiverState: String,
    clearPendingV4ReceiverState: Boolean = false,
    pairingEndpointRegistration: PairingEndpointRegistration? = null,
  ): ControlState {
    return synchronized(UnifiedPushLifecycle.monitor) {
      mutateControl(replay, replayIfAbsent = null, v4ReceiverState = receiverState,
        clearPendingV4ReceiverState = clearPendingV4ReceiverState,
        pairingEndpointRegistration = pairingEndpointRegistration) { it }
    }
  }

  /** Commits [replayIfAbsent] with the state change only when replay has not been initialized yet. */
  fun mutateControlWithReplayIfAbsent(replayIfAbsent: String, block: (ControlState) -> ControlState): ControlState {
    return synchronized(UnifiedPushLifecycle.monitor) {
      mutateControl(replay = null, replayIfAbsent = replayIfAbsent, v4ReceiverState = null, block = block)
    }
  }

  /** Acknowledged standalone write for a consented but not-yet-confirmed receiver request. */
  fun commitV4ReceiverState(receiverState: String?) {
    synchronized(UnifiedPushLifecycle.monitor) {
      store.beginWrite().putString(UNIFIEDPUSH_V4_RECEIVER_STATE, receiverState).commitAndWaitForPersistence()
    }
  }

  /** Writes a pending receiver and initializes replay protection in the same acknowledged enrollment write. */
  fun commitV4ReceiverAttempt(receiverState: String, replayIfAbsent: String) {
    synchronized(UnifiedPushLifecycle.monitor) {
      synchronized(controlLock) {
        if (EncryptedPush.ReplayState.parse(replayIfAbsent) == null) throw ReplayStateUnavailableException()
        val oldReplay = getString(UNIFIEDPUSH_REPLAY_STATE, null)
        if (oldReplay != null && EncryptedPush.ReplayState.parse(oldReplay) == null) throw ReplayStateUnavailableException()
        if (oldReplay == null && controlStateRead !is ControlStateRead.Absent && controlStateRead !is ControlStateRead.Current) {
          throw ReplayStateUnavailableException()
        }
        val write = store.beginWrite().putString(UNIFIEDPUSH_V4_PENDING_RECEIVER_STATE, receiverState)
        if (oldReplay == null) write.putString(UNIFIEDPUSH_REPLAY_STATE, replayIfAbsent)
        write.commitAndWaitForPersistence()
      }
    }
  }

  /** Atomically publishes the promoted receiver and discards only its pending candidate. */
  fun commitV4ReceiverStates(active: String?, pending: String?) {
    synchronized(UnifiedPushLifecycle.monitor) {
      val write = store.beginWrite().putString(UNIFIEDPUSH_V4_RECEIVER_STATE, active)
      if (pending == null) write.remove(UNIFIEDPUSH_V4_PENDING_RECEIVER_STATE)
      else write.putString(UNIFIEDPUSH_V4_PENDING_RECEIVER_STATE, pending)
      write.commitAndWaitForPersistence()
    }
  }

  private fun mutateControl(
    replay: String?,
    replayIfAbsent: String?,
    v4ReceiverState: String?,
    clearPendingV4ReceiverState: Boolean = false,
    pairingEndpointRegistration: PairingEndpointRegistration? = null,
    block: (ControlState) -> ControlState,
  ): ControlState {
    synchronized(controlLock) {
      val loaded = controlStateRead
      val old = loaded.stateOrNull ?: throw CorruptControlStateException()
      val existingReplay = if (replayIfAbsent != null) getString(UNIFIEDPUSH_REPLAY_STATE, null) else null
      if (replayIfAbsent != null) {
        if (existingReplay == null && loaded !is ControlStateRead.Absent) throw ReplayStateUnavailableException()
        if (existingReplay != null && EncryptedPush.ReplayState.parse(existingReplay) == null) throw ReplayStateUnavailableException()
      }
      val next = block(old)
      if (ControlState.parse(next.serialize()) !is ControlStateRead.Current) {
        throw IllegalArgumentException("Invalid Unified Push control state")
      }
      val migration = loaded is ControlStateRead.Legacy
      val missingReplay = replayIfAbsent != null && existingReplay == null
      if (next == old && replay == null && v4ReceiverState == null && !clearPendingV4ReceiverState &&
        pairingEndpointRegistration == null && !missingReplay && !migration) return old
      val write = store.beginWrite().putString(UNIFIEDPUSH_CONTROL_STATE, next.serialize())
      if (migration) write.remove(UNIFIEDPUSH_ENROLLMENT_EPOCH).remove(UNIFIEDPUSH_PENDING_EPOCH)
      if (replay != null) write.putString(UNIFIEDPUSH_REPLAY_STATE, replay)
      else if (missingReplay) write.putString(UNIFIEDPUSH_REPLAY_STATE, replayIfAbsent)
      if (v4ReceiverState != null) write.putString(UNIFIEDPUSH_V4_RECEIVER_STATE, v4ReceiverState)
      if (clearPendingV4ReceiverState) write.remove(UNIFIEDPUSH_V4_PENDING_RECEIVER_STATE)
      pairingEndpointRegistration?.let { registration ->
        val binding = registration.binding
        write.putString(UNIFIEDPUSH_ENDPOINT, registration.endpoint)
          .putString(UNIFIEDPUSH_ENDPOINT_P256DH, registration.p256dh)
          .putString(UNIFIEDPUSH_ENDPOINT_AUTH, registration.auth)
          .putString(UNIFIEDPUSH_ENDPOINT_INSTANCE, binding.instance)
          .putString(UNIFIEDPUSH_PAIRING_INSTANCE, binding.instance)
          .putString(UNIFIEDPUSH_PAIRING_DISTRIBUTOR, binding.distributorId)
          .putString(UNIFIEDPUSH_PAIRING_VAPID, binding.vapidPublicKey)
          .putString(UNIFIEDPUSH_PAIRING_ORIGIN, binding.providerOrigin)
          .putString(UNIFIEDPUSH_PAIRING_ENDPOINT_ORIGIN, binding.endpointOrigin)
          .putString(UNIFIEDPUSH_PAIRING_SESSION, binding.sessionId)
          .putLong(UNIFIEDPUSH_PAIRING_EXPIRY, binding.expiresAt)
          .putString(UNIFIEDPUSH_PAIRING_ACCOUNT, binding.accountId)
          .putInteger(UNIFIEDPUSH_PAIRING_DEVICE, binding.deviceId)
          .putString(UNIFIEDPUSH_PAIRING_HOST_LABEL, binding.hostLabel)
          .putString(UNIFIEDPUSH_PAIRING_IMPORT_ID, binding.importId)
      }
      write.commitAndWaitForPersistence()
      return next
    }
  }

  /** Push health, see [im.molly.unifiedpush.health.PushHealth]. */
  val health = object : PushHealth.HealthStore {
    override var failures: Int
      get() = getInteger(UNIFIEDPUSH_HEALTH_FAILURES, 0)
      set(value) = putInteger(UNIFIEDPUSH_HEALTH_FAILURES, value)
    override var fallback: Boolean
      get() = getBoolean(UNIFIEDPUSH_HEALTH_FALLBACK, false)
      set(value) = putBoolean(UNIFIEDPUSH_HEALTH_FALLBACK, value)
    override var lastResult: String?
      get() = getString(UNIFIEDPUSH_HEALTH_RESULT, null)
      set(value) = putString(UNIFIEDPUSH_HEALTH_RESULT, value)
    override var lastResultAt: Long
      get() = getLong(UNIFIEDPUSH_HEALTH_RESULT_AT, 0)
      set(value) = putLong(UNIFIEDPUSH_HEALTH_RESULT_AT, value)
    override var lastMissAt: Long
      get() = getLong(UNIFIEDPUSH_HEALTH_MISS_AT, 0)
      set(value) = putLong(UNIFIEDPUSH_HEALTH_MISS_AT, value)
  }

  /** Forgets every control key, epoch and update: a different server, or a new enrollment lifecycle. */
  fun clearControl() {
    synchronized(UnifiedPushLifecycle.monitor) {
      synchronized(controlLock) {
        // Callers use this only after deliberately removing/replacing the server enrollment. Keep replay data intact.
        clearControlLocked()
      }
    }
  }

  /** Clears provider-specific keys/epochs when the server changes without lowering the registration import counter. */
  fun clearControlForServerChange(): Boolean = synchronized(UnifiedPushLifecycle.monitor) {
    synchronized(controlLock) {
      val loaded = controlStateRead
      val old = loaded.stateOrNull ?: return@synchronized false
      val next = ControlState(importSequence = old.importSequence)
      if (old == next && loaded !is ControlStateRead.Legacy) return@synchronized true
      store.beginWrite()
        .putString(UNIFIEDPUSH_CONTROL_STATE, next.serialize())
        .remove(UNIFIEDPUSH_ENROLLMENT_EPOCH)
        .remove(UNIFIEDPUSH_PENDING_EPOCH)
        .commitAndWaitForPersistence()
      true
    }
  }

  /** Explicit recovery after the operator removed the server enrollment. Does nothing if the record is no longer corrupt. */
  fun clearCorruptControlAfterServerRemoval(): Boolean = synchronized(UnifiedPushLifecycle.monitor) {
    synchronized(controlLock) {
      if (ControlState.parse(getString(UNIFIEDPUSH_CONTROL_STATE, null)) != ControlStateRead.Corrupt) return@synchronized false
      clearControlLocked()
      true
    }
  }

  private fun clearControlLocked() {
    // Keep first-setup typed as Absent. A serialized empty record after an explicit reset is different: it proves
    // that enrollment state once existed, so missing replay history must never be bootstrapped over it.
    if (controlStateRead is ControlStateRead.Absent) return
    store.beginWrite()
      .putString(UNIFIEDPUSH_CONTROL_STATE, ControlState().serialize())
      .remove(UNIFIEDPUSH_ENROLLMENT_EPOCH)
      .remove(UNIFIEDPUSH_PENDING_EPOCH)
      .commitAndWaitForPersistence()
  }

  /** Last generic status code the server reported in an accepted encrypted push status event. */
  var statusCode: String? by stringValue(UNIFIEDPUSH_STATUS_CODE, null)

  var registrationFailed: Boolean by booleanValue(UNIFIEDPUSH_REGISTRATION_FAILED, false)

  var registrationRetryScheduled: Boolean by booleanValue(UNIFIEDPUSH_RETRY_SCHEDULED, false)

  var airGapped: Boolean by booleanValue(MOLLYSOCKET_AIR_GAPPED, false)

  var mollySocketUrl: String? by stringValue(MOLLYSOCKET_URL, null)

  var vapidPublicKey:  String? by stringValue(MOLLYSOCKET_VAPID, null)

  var vapidKeySynced: Boolean by booleanValue(MOLLYSOCKET_VAPID_SYNCED, true)

  var lastReceivedTime: Long by longValue(UNIFIEDPUSH_LAST_RECEIVED_TIME, 0)

  val isAvailableOrAirGapped: Boolean
    get() = enabled && registrationStatus == RegistrationStatus.REGISTERED
}

class ReplayStateUnavailableException : IllegalStateException("Encrypted push replay state is unavailable")

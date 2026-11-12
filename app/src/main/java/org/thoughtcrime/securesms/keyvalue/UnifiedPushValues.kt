package org.thoughtcrime.securesms.keyvalue

import im.molly.unifiedpush.control.ControlState
import im.molly.unifiedpush.model.MollySocketDevice
import im.molly.unifiedpush.model.RegistrationStatus
import org.signal.core.util.logging.Log
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
    private const val UNIFIEDPUSH_ENROLLMENT_EPOCH = "up.enrollment.epoch"
    private const val UNIFIEDPUSH_PENDING_EPOCH = "up.enrollment.pendingEpoch"
    private const val UNIFIEDPUSH_REPLAY_STATE = "up.enrollment.replay"
    private const val UNIFIEDPUSH_STATUS_CODE = "up.enrollment.statusCode"
    private const val UNIFIEDPUSH_CONTROL_STATE = "up.control.state"
    private val controlLock = Any()
    private const val UNIFIEDPUSH_REGISTRATION_FAILED = "up.registrationFailed"
    private const val UNIFIEDPUSH_RETRY_SCHEDULED = "up.retryScheduled"
    private const val UNIFIEDPUSH_LAST_RECEIVED_TIME = "up.lastRecvTime"
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
        .apply()
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

  /**
   * Epoch of the encrypted enrollment the server has proven it imported (0: none). While it is set the receiver
   * accepts no plaintext wake. It is set only when an event proves it, never by an export.
   */
  var enrollmentEpoch: Long
    get() = controlState.activeEpoch
    set(value) { mutateControl { it.copy(activeEpoch = value) } }

  /** Subscription epoch written into the last enrollment export that no event has confirmed yet (0: none). */
  var pendingEnrollmentEpoch: Long
    get() = controlState.pendingEpoch
    set(value) { mutateControl { it.copy(pendingEpoch = value) } }

  /** Encrypted push replay state (clock high-water mark and live event ids), see [im.molly.unifiedpush.receiver.EncryptedPush]. */
  var replayState: String? by stringValue(UNIFIEDPUSH_REPLAY_STATE, null)

  /**
   * The control state: keys, epochs, the subscription the server holds and the unconfirmed endpoint update, as one
   * record. Before it was first written it is read from the two epoch keys older versions used.
   */
  val controlState: ControlState
    get() = ControlState.parse(getString(UNIFIEDPUSH_CONTROL_STATE, null))
      ?: ControlState(activeEpoch = getLong(UNIFIEDPUSH_ENROLLMENT_EPOCH, 0), pendingEpoch = getLong(UNIFIEDPUSH_PENDING_EPOCH, 0))

  val controlKey: String? get() = controlState.key

  /**
   * The only writer of the control state. One lock, the state re-read inside it, one durable write that also carries
   * [replay], the encrypted-push replay state, when given. Returns the state that was written.
   */
  fun mutateControl(replay: String? = null, block: (ControlState) -> ControlState): ControlState {
    synchronized(controlLock) {
      val old = controlState
      val next = block(old)
      if (next == old && replay == null) return old
      val write = store.beginWrite().putString(UNIFIEDPUSH_CONTROL_STATE, next.serialize())
      if (replay != null) write.putString(UNIFIEDPUSH_REPLAY_STATE, replay)
      write.commit()
      return next
    }
  }

  /** Forgets every control key, epoch and update: a different server, or a new enrollment lifecycle. */
  fun clearControl() {
    mutateControl { ControlState() }
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

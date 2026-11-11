package org.thoughtcrime.securesms.keyvalue

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
    private const val UNIFIEDPUSH_CONTROL_KEY = "up.control.key"
    private const val UNIFIEDPUSH_PENDING_CONTROL_KEY = "up.control.pendingKey"
    private const val UNIFIEDPUSH_CONTROL_HIGHEST_EPOCH = "up.control.highestEpoch"
    private const val UNIFIEDPUSH_CONTROL_UPDATE = "up.control.update"
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
   * accepts no plaintext wake. It is set only by [commitReceived], never by an export.
   */
  var enrollmentEpoch: Long by longValue(UNIFIEDPUSH_ENROLLMENT_EPOCH, 0)

  /** Subscription epoch written into the last enrollment export that no event has confirmed yet (0: none). */
  var pendingEnrollmentEpoch: Long by longValue(UNIFIEDPUSH_PENDING_EPOCH, 0)

  /** Encrypted push replay state (clock high-water mark and live event ids), see [im.molly.unifiedpush.receiver.EncryptedPush]. */
  var replayState: String? by stringValue(UNIFIEDPUSH_REPLAY_STATE, null)

  /** Writes the replay state durably before returning, so it is on disk before the event's effect starts. */
  fun commitReplayState(value: String) {
    store.beginWrite().putString(UNIFIEDPUSH_REPLAY_STATE, value).commit()
  }

  /**
   * Like [commitReplayState], and in the same write makes the pending epoch the active one. The export that was
   * pending also made a fresh control key; the server has now imported it, so it replaces the old key and any
   * endpoint update built with the old key is dropped (the import carried the current endpoint).
   */
  fun commitReceived(value: String, promoteEpoch: Long) {
    val write = store.beginWrite()
      .putString(UNIFIEDPUSH_REPLAY_STATE, value)
      .putLong(UNIFIEDPUSH_ENROLLMENT_EPOCH, promoteEpoch)
      .putLong(UNIFIEDPUSH_PENDING_EPOCH, 0)
    val newKey = getString(UNIFIEDPUSH_PENDING_CONTROL_KEY, null)
    if (newKey != null) {
      write.putString(UNIFIEDPUSH_CONTROL_KEY, newKey)
        .putString(UNIFIEDPUSH_PENDING_CONTROL_KEY, null)
        .putString(UNIFIEDPUSH_CONTROL_UPDATE, null)
        .putLong(UNIFIEDPUSH_CONTROL_HIGHEST_EPOCH, promoteEpoch)
    }
    write.commit()
  }

  /**
   * Control key of the enrollment the server has imported (null: endpoint updates over Signal are not available).
   * The key an export just wrote waits in [pendingControlKey] until the server proves it imported that file.
   */
  var controlKey: String? by stringValue(UNIFIEDPUSH_CONTROL_KEY, null)

  var pendingControlKey: String? by stringValue(UNIFIEDPUSH_PENDING_CONTROL_KEY, null)

  /** The highest subscription epoch this phone has put into an endpoint update, sent or not. */
  var controlHighestEpoch: Long by longValue(UNIFIEDPUSH_CONTROL_HIGHEST_EPOCH, 0)

  /** The unconfirmed endpoint update (JSON, see [im.molly.unifiedpush.control.ControlUpdate]); null when none. */
  var controlUpdate: String? by stringValue(UNIFIEDPUSH_CONTROL_UPDATE, null)

  /** Durably records a new unconfirmed update and the highest epoch it used, in one write. */
  fun commitControlUpdate(update: String, highestEpoch: Long) {
    store.beginWrite()
      .putString(UNIFIEDPUSH_CONTROL_UPDATE, update)
      .putLong(UNIFIEDPUSH_CONTROL_HIGHEST_EPOCH, highestEpoch)
      .commit()
  }

  /** The server confirmed the update at [epoch]: that epoch is active and nothing is pending any more. */
  fun commitControlConfirmed(replay: String, epoch: Long) {
    store.beginWrite()
      .putString(UNIFIEDPUSH_REPLAY_STATE, replay)
      .putLong(UNIFIEDPUSH_ENROLLMENT_EPOCH, epoch)
      .putString(UNIFIEDPUSH_CONTROL_UPDATE, null)
      .commit()
  }

  /** Forgets every control key and update: a different server, or a new enrollment lifecycle. */
  fun clearControl() {
    store.beginWrite()
      .putString(UNIFIEDPUSH_CONTROL_KEY, null)
      .putString(UNIFIEDPUSH_PENDING_CONTROL_KEY, null)
      .putString(UNIFIEDPUSH_CONTROL_UPDATE, null)
      .putLong(UNIFIEDPUSH_CONTROL_HIGHEST_EPOCH, 0)
      .commit()
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

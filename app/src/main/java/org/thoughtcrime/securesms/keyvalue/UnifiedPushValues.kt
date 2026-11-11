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

  /** Subscription epoch written into the last enrollment export (0: none yet). */
  var enrollmentEpoch: Long by longValue(UNIFIEDPUSH_ENROLLMENT_EPOCH, 0)

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

package im.molly.unifiedpush.receiver

import android.content.Context
import org.signal.core.util.crypto.KeyStoreHelper
import org.signal.core.util.logging.Log

/**
 * The two pieces of push state the receiver needs while the app is locked, when SignalStore cannot be read:
 * whether the enrollment is an encrypted one (a flag, no secret), and a new endpoint that arrived while locked,
 * sealed with the Android Keystore key the app already uses for such data until the next unlock.
 */
object LockedPushState {

  private val TAG = Log.tag(LockedPushState::class.java)
  private const val PREFS = "collie_locked_push"
  private const val ENCRYPTED = "encrypted_enrollment"
  private const val PENDING = "pending_endpoint"
  private const val REGISTRATION_ID = "pending_registration_id"
  private const val REGISTRATION_READY = "pending_registration_ready"
  private const val WRITE_IN_PROGRESS = "endpoint_write_in_progress"
  private const val PUSH_ARRIVED = "last_push_arrived"
  private const val SEPARATOR = '\n'

  /** A null URL is the persisted unregister tombstone used while account state is locked or transitioning. */
  data class Endpoint(val url: String?, val p256dh: String?, val auth: String?, val instance: String)

  @JvmStatic
  fun isEncryptedEnrollment(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENCRYPTED, false)

  @JvmStatic
  fun setEncryptedEnrollment(context: Context, encrypted: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENCRYPTED, encrypted).apply()
  }

  /**
   * When a push last reached the phone, kept here because it can be written while locked. A push that is dropped while
   * locked is still proof that push works, and message delivery that follows the unlock is not a miss.
   */
  @JvmStatic
  fun recordPushArrived(context: Context, nowMs: Long) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(PUSH_ARRIVED, nowMs).apply()
  }

  @JvmStatic
  fun lastPushArrived(context: Context): Long =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(PUSH_ARRIVED, 0)

  /** Keeps only the newest endpoint. Never throws: a failure here is the same as the old behavior of dropping it. */
  @JvmStatic
  fun savePending(context: Context, endpoint: Endpoint) {
    trySavePending(context, endpoint)
  }

  /** A write-ahead fence makes interrupted or uncertain writes fail closed after restart. */
  fun beginEndpointWrite(context: Context): Boolean = try {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(WRITE_IN_PROGRESS, true).commit()
  } catch (e: Throwable) {
    Log.w(TAG, "Could not fence endpoint write.", e)
    false
  }

  fun finishEndpointWrite(context: Context): Boolean = try {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(WRITE_IN_PROGRESS).commit()
  } catch (e: Throwable) {
    Log.w(TAG, "Could not finish endpoint write.", e)
    false
  }

  fun hasUncertainWrite(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return prefs.getBoolean(WRITE_IN_PROGRESS, false) ||
      (prefs.getString(REGISTRATION_ID, null) != null && !prefs.getBoolean(REGISTRATION_READY, false))
  }

  /** Returns false if sealing or durable persistence fails. */
  fun trySavePending(context: Context, endpoint: Endpoint): Boolean {
    return try {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PENDING, seal(endpoint)).commit()
    } catch (e: Throwable) {
      Log.w(TAG, "Could not keep the endpoint received while locked.", e)
      false
    }
  }

  /** Atomically stage the tombstone and a distinct registration intent before changing the SDK provider. */
  fun trySaveSelection(context: Context, endpoint: Endpoint, id: String): Boolean = try {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putString(PENDING, seal(endpoint))
      .putString(REGISTRATION_ID, id)
      .putBoolean(REGISTRATION_READY, false)
      .commit()
  } catch (e: Throwable) {
    Log.w(TAG, "Could not keep the distributor selection.", e)
    false
  }

  data class PendingRegistration(val id: String, val ready: Boolean)

  /** Caller serializes reads and updates with UnifiedPushLifecycle.monitor. */
  fun pendingRegistration(context: Context): PendingRegistration? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val id = prefs.getString(REGISTRATION_ID, null) ?: return null
    return PendingRegistration(id, prefs.getBoolean(REGISTRATION_READY, false))
  }

  fun markRegistrationReady(context: Context, id: String): Boolean {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return prefs.getString(REGISTRATION_ID, null) == id && prefs.edit().putBoolean(REGISTRATION_READY, true).commit()
  }

  fun clearRegistrationIfCurrent(context: Context, id: String): Boolean {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return prefs.getString(REGISTRATION_ID, null) == id && prefs.edit().remove(REGISTRATION_ID).remove(REGISTRATION_READY).commit()
  }

  private fun seal(endpoint: Endpoint): String {
    val fields = listOf(endpoint.url.orEmpty(), endpoint.p256dh.orEmpty(), endpoint.auth.orEmpty(), endpoint.instance)
    return KeyStoreHelper.seal(fields.joinToString(SEPARATOR.toString()).toByteArray(Charsets.UTF_8)).serialize()
  }

  /** Returns and removes the endpoint saved while locked, if any. */
  @JvmStatic
  fun takePending(context: Context): Endpoint? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val value = prefs.getString(PENDING, null) ?: return null
    prefs.edit().remove(PENDING).commit()
    return try {
      val fields = String(KeyStoreHelper.unseal(KeyStoreHelper.SealedData.fromString(value)), Charsets.UTF_8).split(SEPARATOR)
      if (fields.size != 4) null else Endpoint(fields[0].ifEmpty { null }, fields[1].ifEmpty { null }, fields[2].ifEmpty { null }, fields[3])
    } catch (e: Throwable) {
      Log.w(TAG, "Could not read the endpoint received while locked.", e)
      null
    }
  }

  /** Presence is separate from decryption success; unreadable pending data must not be bypassed. */
  fun hasPending(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(PENDING)

  /** Reads the sealed endpoint without consuming it so a failed durable application can be retried. */
  fun peekPending(context: Context): Endpoint? {
    val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PENDING, null) ?: return null
    return try {
      val fields = String(KeyStoreHelper.unseal(KeyStoreHelper.SealedData.fromString(value)), Charsets.UTF_8).split(SEPARATOR)
      if (fields.size != 4) null else Endpoint(fields[0].ifEmpty { null }, fields[1].ifEmpty { null }, fields[2].ifEmpty { null }, fields[3])
    } catch (e: Throwable) {
      Log.w(TAG, "Could not read the endpoint received while locked.", e)
      null
    }
  }

  /** Removes pending state only after the route tuple and control update have been applied. */
  fun clearPending(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PENDING).commit()
  }
}

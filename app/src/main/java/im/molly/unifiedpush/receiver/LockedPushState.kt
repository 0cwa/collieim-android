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
    try {
      val fields = listOf(endpoint.url.orEmpty(), endpoint.p256dh.orEmpty(), endpoint.auth.orEmpty(), endpoint.instance)
      val sealed = KeyStoreHelper.seal(fields.joinToString(SEPARATOR.toString()).toByteArray(Charsets.UTF_8))
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PENDING, sealed.serialize()).commit()
    } catch (e: Throwable) {
      Log.w(TAG, "Could not keep the endpoint received while locked.", e)
    }
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
}

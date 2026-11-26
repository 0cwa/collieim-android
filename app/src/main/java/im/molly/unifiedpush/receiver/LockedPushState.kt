package im.molly.unifiedpush.receiver

import android.content.Context
import org.json.JSONObject
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
  private const val PROTECTED_PAIRING_INSTANCE = "protected_pairing_instance"
  private const val PROTECTED_PAIRING_DISTRIBUTOR = "protected_pairing_distributor"
  private const val PUSH_ARRIVED = "last_push_arrived"
  private const val PAIRING_CONFIRMATION = "pending_pairing_confirmation"
  private const val SEPARATOR = '\n'

  /** A null URL is the persisted unregister tombstone used while account state is locked or transitioning. */
  data class Endpoint(val url: String?, val p256dh: String?, val auth: String?, val instance: String)

  /** No receipt key or registration credential is kept outside SignalStore. */
  internal data class PairingConfirmation(
    val sessionId: String,
    val instance: String,
    val distributor: String,
    val vapid: String,
    val providerOrigin: String?,
    val expiresAt: Long,
    val hostLabel: String,
    val importId: String,
    val keyId: String,
    val epoch: Long,
    val scopeDigest: String,
    val message: String? = null,
    val receiverId: String? = null,
  ) {
    fun matches(event: EncryptedPush.Event): Boolean =
      event.instance == instance && event.importId == importId && event.epoch == epoch &&
        event.kind == EncryptedPush.Kind.STATUS && event.code == EncryptedPush.CODE_ENROLLMENT_IMPORTED &&
        if (receiverId == null) event.version == 3 && event.keyId == keyId
        else event.version == 4 && event.receiverId == receiverId
  }

  internal fun savePairingConfirmation(context: Context, pending: PairingConfirmation): Boolean = try {
    val json = JSONObject()
      .put("session", pending.sessionId).put("instance", pending.instance).put("distributor", pending.distributor)
      .put("vapid", pending.vapid).put("provider", pending.providerOrigin).put("expires", pending.expiresAt)
      .put("host", pending.hostLabel).put("import", pending.importId).put("key", pending.keyId)
      .put("epoch", pending.epoch).put("scope", pending.scopeDigest).put("message", pending.message)
      .put("receiver", pending.receiverId)
    val sealed = KeyStoreHelper.seal(json.toString().toByteArray(Charsets.UTF_8))
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PAIRING_CONFIRMATION, sealed.serialize()).commit()
  } catch (_: Exception) {
    Log.w(TAG, "Could not retain the pairing confirmation.")
    false
  }

  internal fun peekPairingConfirmation(context: Context): PairingConfirmation? {
    val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PAIRING_CONFIRMATION, null) ?: return null
    return try {
      val json = JSONObject(String(KeyStoreHelper.unseal(KeyStoreHelper.SealedData.fromString(value)), Charsets.UTF_8))
      PairingConfirmation(
        json.getString("session"), json.getString("instance"), json.getString("distributor"), json.getString("vapid"),
        json.optString("provider").ifEmpty { null }, json.getLong("expires"), json.getString("host"),
        json.getString("import"), json.getString("key"), json.getLong("epoch"), json.getString("scope"),
        json.optString("message").ifEmpty { null }, json.optString("receiver").ifEmpty { null },
      )
    } catch (_: Exception) {
      Log.w(TAG, "Could not read the retained pairing confirmation.")
      null
    }
  }

  /** Called under the lifecycle monitor so an old cancellation cannot erase a newer approval. */
  internal fun clearPairingConfirmation(context: Context, sessionId: String) {
    if (peekPairingConfirmation(context)?.sessionId == sessionId) {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PAIRING_CONFIRMATION).commit()
    }
  }

  @JvmStatic
  fun isEncryptedEnrollment(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENCRYPTED, false)

  @JvmStatic
  fun setEncryptedEnrollment(context: Context, encrypted: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENCRYPTED, encrypted).apply()
  }

  /** Minimal plaintext routing gate used only to stop a stale default callback replacing a scoped route while locked. */
  @JvmStatic
  fun protectedPairingInstance(context: Context): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PROTECTED_PAIRING_INSTANCE, null)

  @JvmStatic
  fun setProtectedPairingRoute(context: Context, instance: String?, distributor: String?) {
    val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
    if (instance == null || distributor == null) {
      editor.remove(PROTECTED_PAIRING_INSTANCE).remove(PROTECTED_PAIRING_DISTRIBUTOR)
    } else {
      editor.putString(PROTECTED_PAIRING_INSTANCE, instance).putString(PROTECTED_PAIRING_DISTRIBUTOR, distributor)
    }
    editor.commit()
  }

  @JvmStatic
  fun protectedPairingDistributor(context: Context): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PROTECTED_PAIRING_DISTRIBUTOR, null)

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

  /** Removes the pending endpoint after it has been applied. */
  fun clearPending(context: Context) {
    discardPending(context)
  }

  /** Requires a durable clear before a different relay is selected. */
  fun discardPending(context: Context): Boolean {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PENDING).commit()
  }
}

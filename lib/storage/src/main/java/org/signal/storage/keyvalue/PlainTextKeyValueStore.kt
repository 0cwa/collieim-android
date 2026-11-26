package org.signal.storage.keyvalue

import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit

/**
 * There are some values that you can't store in the normal encrypted [KeyValueStore].
 * Usually, it's because the value you're storing is needed to *open* the database that backs the store,
 * or is otherwise related to the state of the database itself. Molly supplies its preferences
 * with the existing plaintext filter for values needed before unlock.
 *
 * MOLLY: Most values are encrypted by the app's SecurePreferenceManager. Non-sensitive settings needed before database
 * unlock, including theme and dynamic colors, are deliberately stored in plaintext through the per-key encryption filter.
 */
object PlainTextKeyValueStore {

  const val SMS_MIGRATION_ID_OFFSET = "sms_migration_id_offset"
  private const val DATABASE_ENCRYPTED_SECRET = "pref_database_encrypted_secret"
  private const val DATABASE_UNENCRYPTED_SECRET = "pref_database_unencrypted_secret"
  private const val ATTACHMENT_ENCRYPTED_SECRET = "pref_attachment_encrypted_secret"
  private const val ATTACHMENT_UNENCRYPTED_SECRET = "pref_attachment_unencrypted_secret"
  private const val APP_MIGRATION_VERSION = "pref_app_migration_version"
  private const val LAST_VERSION_CODE = "last_version_code"

  const val LANGUAGE = "pref_language"
  const val THEME = "pref_theme"
  const val DYNAMIC_COLORS_ENABLED = "pref_dynamic_colors"

  /**
   * Collie: stable ID of a built-in color palette, absent for the Collie palette. [DYNAMIC_COLORS_ENABLED] stays the
   * authoritative System choice, so older versions, which ignore this key, keep showing System or Collie.
   */
  const val THEME_PALETTE = "pref_theme_palette"
  const val SYSTEM_EMOJI_PREFERRED = "pref_system_emoji"
  const val PASSPHRASE_LOCK_ENABLED = "pref_passphrase_lock"
  const val PASSPHRASE_LOCK_NOTIFICATIONS_ENABLED = "pref_passphrase_lock_notifications"
  const val BIOMETRIC_SCREEN_LOCK_ENABLED = "pref_biometric_screen_lock"
  const val DIRECTORY_REFRESH_TIME = "pref_directory_refresh_time"
  const val REFRESH_FCM_TOKEN_REQUIRED = "pref_refresh_fcm_token"

  /**
   * MOLLY: Legacy key that old installs wrote as a raw int while it was exempt from encryption. Only the V1 misc migration
   * reads it, so it must stay in [org.thoughtcrime.securesms.util.SecurePreferenceManager]'s encryption filter, or that
   * migration throws ClassCastException trying to decrypt it as a string.
   */
  const val FIRST_INSTALL_VERSION = "pref_first_install_version"

  private lateinit var sharedPrefs: SharedPreferences
  private var currentVersionCode: Int = 0

  internal val legacyPreferences: SharedPreferences
    get() = sharedPrefs

  /**
   * Must be called from [android.app.Application.attachBaseContext] before anything else has a chance to read from
   * this store.
   */
  @JvmStatic
  fun init(preferences: SharedPreferences, currentVersionCode: Int) {
    sharedPrefs = preferences
    this.currentVersionCode = currentVersionCode
  }

  /** Stores the ID offset that was determined during the big migration that moved all SMS messages into the MMS table. */
  @JvmStatic
  var smsMigrationIdOffset: Long
    get() = sharedPrefs.getLong(SMS_MIGRATION_ID_OFFSET, -1)
    set(value) = sharedPrefs.edit(commit = true) { putLong(SMS_MIGRATION_ID_OFFSET, value) }

  /** The keystore-sealed secret used to open the main database. */
  @JvmStatic
  var databaseEncryptedSecret: String?
    get() = sharedPrefs.getString(DATABASE_ENCRYPTED_SECRET, null)
    set(value) = sharedPrefs.edit(commit = true) { putString(DATABASE_ENCRYPTED_SECRET, value) }

  /** Legacy plaintext database secret, only present until it's been sealed into [databaseEncryptedSecret]. */
  @JvmStatic
  var databaseLegacyUnencryptedSecret: String?
    get() = sharedPrefs.getString(DATABASE_UNENCRYPTED_SECRET, null)
    set(value) = sharedPrefs.edit(commit = true) { putString(DATABASE_UNENCRYPTED_SECRET, value) }

  /** The keystore-sealed secret used to decrypt attachments on disk. */
  @JvmStatic
  var attachmentEncryptedSecret: String?
    get() = sharedPrefs.getString(ATTACHMENT_ENCRYPTED_SECRET, null)
    set(value) = sharedPrefs.edit(commit = true) { putString(ATTACHMENT_ENCRYPTED_SECRET, value) }

  /** Legacy plaintext attachment secret, only present until it's been sealed into [attachmentEncryptedSecret]. */
  @JvmStatic
  var attachmentLegacyUnencryptedSecret: String?
    get() = sharedPrefs.getString(ATTACHMENT_UNENCRYPTED_SECRET, null)
    set(value) = sharedPrefs.edit(commit = true) { putString(ATTACHMENT_UNENCRYPTED_SECRET, value) }

  /** The last app migration version that ran to completion. */
  @JvmStatic
  var appMigrationVersion: Int
    get() = sharedPrefs.getInt(APP_MIGRATION_VERSION, 1)
    set(value) = sharedPrefs.edit(commit = true) { putInt(APP_MIGRATION_VERSION, value) }

  /** The version code the app was last launched at. Defaults to the version code passed to [init]. */
  @JvmStatic
  var lastVersionCode: Int
    get() = sharedPrefs.getInt(LAST_VERSION_CODE, currentVersionCode)
    set(value) = sharedPrefs.edit(commit = true) { putInt(LAST_VERSION_CODE, value) }

  /** The user's chosen app language, or "zz" to follow the system. */
  @JvmStatic
  var language: String
    get() = sharedPrefs.getString(LANGUAGE, "zz") ?: "zz"
    set(value) = sharedPrefs.edit(commit = true) { putString(LANGUAGE, value) }

  @JvmStatic
  var theme: String
    get() = sharedPrefs.getString(THEME, if (Build.VERSION.SDK_INT >= 29) "system" else "light") ?: "light"
    set(value) = sharedPrefs.edit(commit = true) { putString(THEME, value) }

  @JvmStatic
  var passphraseLockEnabled: Boolean
    get() = sharedPrefs.getBoolean(PASSPHRASE_LOCK_ENABLED, true)
    set(value) = sharedPrefs.edit(commit = true) { putBoolean(PASSPHRASE_LOCK_ENABLED, value) }

  @JvmStatic
  var lockNotificationsEnabled: Boolean
    get() = sharedPrefs.getBoolean(PASSPHRASE_LOCK_NOTIFICATIONS_ENABLED, true)
    set(value) = sharedPrefs.edit(commit = true) { putBoolean(PASSPHRASE_LOCK_NOTIFICATIONS_ENABLED, value) }

  @JvmStatic
  var biometricScreenLockEnabled: Boolean
    get() = sharedPrefs.getBoolean(BIOMETRIC_SCREEN_LOCK_ENABLED, false)
    set(value) = sharedPrefs.edit(commit = true) { putBoolean(BIOMETRIC_SCREEN_LOCK_ENABLED, value) }

  @JvmStatic
  var shouldRefreshFcmToken: Boolean
    get() = sharedPrefs.getBoolean(REFRESH_FCM_TOKEN_REQUIRED, false)
    set(value) = sharedPrefs.edit(commit = true) { putBoolean(REFRESH_FCM_TOKEN_REQUIRED, value) }

  /** When the next contact directory refresh is scheduled. */
  @JvmStatic
  var directoryRefreshTime: Long
    get() = sharedPrefs.getLong(DIRECTORY_REFRESH_TIME, 0)
    set(value) = sharedPrefs.edit(commit = true) { putLong(DIRECTORY_REFRESH_TIME, value) }

  @JvmStatic
  fun clearDirectoryRefreshTime() {
    sharedPrefs.edit(commit = true) { remove(DIRECTORY_REFRESH_TIME) }
  }

  @JvmStatic
  var isDynamicColorsEnabled: Boolean
    get() = sharedPrefs.getBoolean(DYNAMIC_COLORS_ENABLED, false)
    set(value) = sharedPrefs.edit(commit = true) { putBoolean(DYNAMIC_COLORS_ENABLED, value) }

  /** The stored palette ID, kept as-is even when this version does not know it. Null means Collie. */
  @JvmStatic
  val themePalette: String?
    get() = try {
      sharedPrefs.getString(THEME_PALETTE, null)
    } catch (e: ClassCastException) {
      null
    }

  /**
   * Commits the theme mode and System flag together. Turning the System flag on clears a stored palette, so choosing
   * System and then turning it off returns to Collie. Mode changes and unchanged flags keep the stored palette.
   */
  @JvmStatic
  fun setAppearance(theme: String, dynamicColorsEnabled: Boolean) {
    val enablingDynamicColors = dynamicColorsEnabled && !isDynamicColorsEnabled
    sharedPrefs.edit(commit = true) {
      putString(THEME, theme)
      putBoolean(DYNAMIC_COLORS_ENABLED, dynamicColorsEnabled)
      if (enablingDynamicColors) {
        remove(THEME_PALETTE)
      }
    }
  }

  /** Commits an explicit palette choice: System sets the flag and clears the ID; Collie or a palette clears the flag. */
  @JvmStatic
  fun setPalette(dynamicColorsEnabled: Boolean, paletteId: String?) {
    sharedPrefs.edit(commit = true) {
      putBoolean(DYNAMIC_COLORS_ENABLED, dynamicColorsEnabled)
      if (paletteId == null) {
        remove(THEME_PALETTE)
      } else {
        putString(THEME_PALETTE, paletteId)
      }
    }
  }

  /** Collie: the stored palette ID for the backup record, or null for Collie. */
  @JvmStatic
  fun getThemePaletteToBackup(): String? = themePalette

  @JvmStatic
  var isSystemEmojiPreferred: Boolean
    get() = sharedPrefs.getBoolean(SYSTEM_EMOJI_PREFERRED, false)
    set(value) = sharedPrefs.edit(commit = true) { putBoolean(SYSTEM_EMOJI_PREFERRED, value) }

  /**
   * MOLLY: Settings without a V1 SignalStore mirror retain their preference-based backup records. Dynamic colors use
   * this path deliberately. Only keys that are set are returned, in backup order. The app turns them into backup
   * records (FullBackupExporter.getPlainTextPreferencesToSaveToBackup), because this module cannot see the backup protos.
   */
  @JvmStatic
  fun getBooleanValuesToBackup(): Map<String, Boolean> {
    val values = LinkedHashMap<String, Boolean>()
    for (key in BOOTSTRAP_BOOLEAN_KEYS_TO_BACKUP) {
      if (sharedPrefs.contains(key)) {
        values[key] = sharedPrefs.getBoolean(key, false)
      }
    }
    return values
  }

  /** MOLLY: The stored language for the backup record, or null when the user never chose one. */
  @JvmStatic
  fun getLanguageToBackup(): String? {
    return if (sharedPrefs.contains(LANGUAGE)) sharedPrefs.getString(LANGUAGE, "zz") else null
  }

  private val BOOTSTRAP_BOOLEAN_KEYS_TO_BACKUP = listOf(
    BIOMETRIC_SCREEN_LOCK_ENABLED,
    PASSPHRASE_LOCK_NOTIFICATIONS_ENABLED,
    DYNAMIC_COLORS_ENABLED,
  )
}

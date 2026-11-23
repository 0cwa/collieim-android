package org.thoughtcrime.securesms.keyvalue

import android.content.Context
import androidx.core.content.edit
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.util.SecurePreferenceManager

/**
 * Collie values that must be readable before the database is unlocked, kept beside Molly's
 * [PlainTextKeyValueStore] instead of in Molly's files. Molly's [MollyValues] holds the settings that are only needed
 * after unlock.
 */
object CollieBootstrapValues {

  /**
   * Mirror of [MiscellaneousValues.firstInstallVersion] so locked debug logs can report it. It uses the legacy
   * [PlainTextKeyValueStore.FIRST_INSTALL_VERSION] key in the secure preferences, as earlier Collie releases did.
   */
  @JvmStatic
  fun getFirstInstallVersion(context: Context): Int {
    return SecurePreferenceManager.getSecurePreferences(context).getInt(PlainTextKeyValueStore.FIRST_INSTALL_VERSION, -1)
  }

  @JvmStatic
  fun setFirstInstallVersion(context: Context, version: Int) {
    SecurePreferenceManager.getSecurePreferences(context).edit { putInt(PlainTextKeyValueStore.FIRST_INSTALL_VERSION, version) }
  }
}

/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.storage.keyvalue

import android.content.Context
import org.signal.core.util.logging.Log

class ApkUpdateValues(store: KeyValueStore, context: Context) : SignalStoreValues(store) {
  companion object {
    private val TAG = Log.tag(ApkUpdateValues::class.java)

    private const val DOWNLOAD_ID = "apk_update.download_id"
    private const val DIGEST = "apk_update.digest"
    private const val LAST_SUCCESSFUL_CHECK = "apk_update.last_successful_check"
    private const val REFRESH_TIME = "apk_update.refresh_time"
    private const val INDEX_TIMESTAMP = "apk_update.index_timestamp"
    private const val AVAILABLE_VERSION_CODE = "apk_update.available_version_code"
    private const val AVAILABLE_VERSION_NAME = "apk_update.available_version_name"
    private const val LAST_NOTIFIED_VERSION = "apk_update.last_notified_version"
    private const val LAST_CHECK_FAILED = "apk_update.last_check_failed"
  }

  init {
    if (!store.containsKey(REFRESH_TIME)) {
      migrateFromSharedPrefsV1(context)
    }
  }

  /** Do not alter. If you need to migrate more stuff, create a new method. */
  private fun migrateFromSharedPrefsV1(context: Context) {
    Log.i(TAG, "[V1] Migrating apk update values from shared prefs.")

    putLong(REFRESH_TIME, LegacySharedPrefs.getLong(context, "pref_update_apk_refresh_time", 0))
  }

  /** When the next APK update check is scheduled. See [lastSuccessfulCheck] for when one last succeeded. */
  var refreshTime: Long by longValue(REFRESH_TIME, 0)

  public override fun onFirstEverAppLaunch() = Unit
  override val keysToIncludeInBackup: List<String>
    get() = emptyList()

  val downloadId: Long by longValue(DOWNLOAD_ID, -2)
  val digest: ByteArray? get() = store.getBlob(DIGEST, null)
  var lastSuccessfulCheck: Long by longValue(LAST_SUCCESSFUL_CHECK, 0)

  val lastIndexTimestamp: Long by longValue(INDEX_TIMESTAMP, 0)
  val availableVersionCode: Long by longValue(AVAILABLE_VERSION_CODE, 0)
  val availableVersionName: String? by stringValue(AVAILABLE_VERSION_NAME, null)
  var lastNotifiedVersionCode: Long by longValue(LAST_NOTIFIED_VERSION, 0)
  var lastCheckFailed: Boolean by booleanValue(LAST_CHECK_FAILED, false)

  fun setAvailableUpdate(timestamp: Long, versionCode: Long, versionName: String?) {
    store.beginWrite()
      .putLong(INDEX_TIMESTAMP, timestamp)
      .putLong(AVAILABLE_VERSION_CODE, versionCode)
      .putString(AVAILABLE_VERSION_NAME, versionName)
      .commit()
  }

  fun setDownloadAttributes(id: Long, digest: ByteArray?) {
    Log.d(TAG, "Saving download attributes. id: $id")

    store
      .beginWrite()
      .putLong(DOWNLOAD_ID, id)
      .putBlob(DIGEST, digest)
      .commit()
  }

  fun clearDownloadAttributes() {
    Log.d(TAG, "Clearing download attributes.")

    store
      .beginWrite()
      .putLong(DOWNLOAD_ID, -1)
      .putBlob(DIGEST, null)
      .commit()
  }
}

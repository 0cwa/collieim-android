package org.thoughtcrime.securesms.jobs

import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import org.signal.core.util.eventbus.EventBus
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.apkupdate.ApkUpdateNotifications
import org.thoughtcrime.securesms.apkupdate.CollieUpdateIndex
import org.thoughtcrime.securesms.events.ApkUpdateEvent
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.net.Networking
import org.thoughtcrime.securesms.util.TextSecurePreferences
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Checks the signed Collie repository and notifies users. APK downloads are user initiated. */
class ApkUpdateJob private constructor(parameters: Parameters) : BaseJob(parameters) {
  companion object {
    const val KEY = "UpdateApkJob"
    private val TAG = Log.tag(ApkUpdateJob::class.java)

    internal fun newClient(): OkHttpClient = OkHttpClient.Builder()
      .socketFactory(Networking.socketFactory)
      .proxySelector(Networking.proxySelectorForSocks)
      .dns(Networking.dns)
      .followRedirects(false)
      .followSslRedirects(false)
      .callTimeout(30, TimeUnit.SECONDS)
      .build()
  }

  constructor() : this(
    Parameters.Builder()
      .setQueue(KEY)
      .setMaxInstancesForFactory(2)
      .addConstraint(NetworkConstraint.KEY)
      .setMaxAttempts(2)
      .build()
  )

  override fun serialize(): ByteArray? = null
  override fun getFactoryKey(): String = KEY

  @Throws(IOException::class)
  public override fun onRun() {
    if (!BuildConfig.MANAGE_COLLIE_UPDATES || BuildConfig.APPLICATION_ID != CollieUpdateIndex.PACKAGE_NAME) return
    if (!TextSecurePreferences.isUpdateApkEnabled(context)) return

    val values = SignalStore.apkUpdate
    val client = newClient()
    val entryRequest = Request.Builder().url("${CollieUpdateIndex.REPOSITORY_URL}/entry.jar").build()
    val index = client.newCall(entryRequest).execute().use { response ->
      val body = response.body
      if (!response.isSuccessful || body == null || body.contentLength() > CollieUpdateIndex.MAX_ARCHIVE_BYTES) {
        throw IOException("Failed to fetch signed Collie update entry")
      }
      body.byteStream().use { CollieUpdateIndex.readEntry(it, context.cacheDir, values.lastIndexTimestamp) }
    }
    if (!TextSecurePreferences.isUpdateApkEnabled(context)) return
    val indexRequest = Request.Builder().url("${CollieUpdateIndex.REPOSITORY_URL}/index-v2.json").build()
    val result = client.newCall(indexRequest).execute().use { response ->
      val body = response.body
      if (!response.isSuccessful || body == null || body.contentLength() > index.size) {
        throw IOException("Failed to fetch Collie update index")
      }
      body.byteStream().use { CollieUpdateIndex.readIndex(it, index, BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toSet()) }
    }

    // Do not publish a result after the user has disabled checks while the request was running.
    if (!TextSecurePreferences.isUpdateApkEnabled(context)) return

    val update = result.update
    val candidateChanged = values.availableVersionCode != (update?.versionCode ?: 0L) || values.availableVersionName != update?.versionName
    values.setAvailableUpdate(result.timestamp, update?.versionCode ?: 0, update?.versionName)
    values.clearDownloadAttributes()
    values.lastSuccessfulCheck = System.currentTimeMillis()
    values.lastCheckFailed = false
    if (candidateChanged || update == null) {
      ApkUpdateNotifications.dismissUpdatePrompt(context)
    }
    if (update != null) {
      if (update.versionCode > values.lastNotifiedVersionCode) {
        if (ApkUpdateNotifications.showUpdateAvailable(context, update.versionName)) {
          values.lastNotifiedVersionCode = update.versionCode
        }
      }
    }
    EventBus.default.post(ApkUpdateEvent)
  }

  public override fun onShouldRetry(e: Exception): Boolean = e is IOException

  override fun onFailure() {
    Log.w(TAG, "Collie update check failed")
    SignalStore.apkUpdate.lastCheckFailed = true
    EventBus.default.post(ApkUpdateEvent)
  }

  class Factory : Job.Factory<ApkUpdateJob?> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): ApkUpdateJob = ApkUpdateJob(parameters)
  }
}

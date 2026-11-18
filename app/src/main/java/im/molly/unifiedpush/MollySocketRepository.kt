package im.molly.unifiedpush

import androidx.annotation.WorkerThread
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonMappingException
import im.molly.unifiedpush.model.ConnectionRequest
import im.molly.unifiedpush.model.ConnectionResult
import im.molly.unifiedpush.model.LinkStatus
import im.molly.unifiedpush.model.MollySocketDevice
import im.molly.unifiedpush.model.Response
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.signal.core.util.Base64
import org.signal.core.util.JsonUtils
import org.signal.core.util.Util
import org.signal.core.util.ThreadUtil
import org.signal.core.util.crypto.DeviceNameCipher
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.util.KeyHelper
import org.thoughtcrime.securesms.AppCapabilities
import org.thoughtcrime.securesms.crypto.ReentrantSessionLock
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.dependencies.AppDependencies.keysApi
import org.signal.storage.keyvalue.PhoneNumberPrivacyValues.PhoneNumberDiscoverabilityMode
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.linkdevice.LinkDeviceRepository
import org.thoughtcrime.securesms.push.AccountManagerFactory
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.registration.data.RegistrationRepository
import org.whispersystems.signalservice.api.NetworkResultUtil
import org.whispersystems.signalservice.api.account.AccountAttributes
import org.whispersystems.signalservice.api.crypto.SignalSessionBuilder
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.internal.push.DeviceLimitExceededException
import java.io.IOException
import java.net.MalformedURLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException

object MollySocketRepository {

  private val TAG = Log.tag(MollySocketRepository::class)

  private val MEDIA_TYPE_JSON = "application/json; charset=utf-8".toMediaType()

  private const val DEVICE_NAME = "MollySocket"

  @Throws(IOException::class, DeviceLimitExceededException::class)
  fun createDevice(): MollySocketDevice {
    Log.d(TAG, "Creating device for MollySocket")

    val password = Util.getSecret(18)
    val deviceId = verifyNewDevice(password)

    return MollySocketDevice(
      deviceId = deviceId,
      password = password,
    )
  }

  @Throws(IOException::class, DeviceLimitExceededException::class)
  private fun verifyNewDevice(password: String): Int {
    val fetchResult = AppDependencies.linkDeviceApi.getDeviceVerificationCode()
    val verificationCode = fetchResult.successOrThrow()

    val registrationId = KeyHelper.generateRegistrationId(false)
    val encryptedDeviceName = DeviceNameCipher.encryptDeviceName(
      DEVICE_NAME.toByteArray(), SignalStore.account.aciIdentityKey
    )

    val notDiscoverable = SignalStore.phoneNumberPrivacy.phoneNumberDiscoverabilityMode == PhoneNumberDiscoverabilityMode.NOT_DISCOVERABLE

    val accountAttributes = AccountAttributes(
      signalingKey = null,
      registrationId = registrationId,
      fetchesMessages = true,
      registrationLock = null,
      unidentifiedAccessKey = null,
      unrestrictedUnidentifiedAccess = true,
      capabilities = AppCapabilities.getCapabilities(storageCapable = false),
      discoverableByPhoneNumber = !notDiscoverable,
      name = Base64.encodeWithPadding(encryptedDeviceName),
      pniRegistrationId = SignalStore.account.pniRegistrationId,
      recoveryPassword = null
    )

    val aciPreKeyCollection = RegistrationRepository.generateSignedAndLastResortPreKeys(SignalStore.account.aciIdentityKey, SignalStore.account.aciPreKeys)
    val pniPreKeyCollection = RegistrationRepository.generateSignedAndLastResortPreKeys(SignalStore.account.pniIdentityKey, SignalStore.account.pniPreKeys)

    val accountManager = AccountManagerFactory.getInstance().createForDeviceLink(AppDependencies.application, password)

    return accountManager.finishNewDeviceRegistration(
      verificationCode.verificationCode,
      accountAttributes,
      aciPreKeyCollection, pniPreKeyCollection,
      null
    ).also { deviceId ->
      SignalStore.account.isMultiDevice = true
      loadPreKeys(deviceId)
    }
  }

  /**
   * We need to load prekeys, else we get a bug if MollySocket is the only linked device:
   * - SignalStore.account.isMultiDevice is set to `true` => we try to [sendSyncMessage][org.whispersystems.signalservice.api.SignalServiceMessageSender.sendSyncMessage]
   * - [SignalServiceAccountDataStore.containsSession] returns false for this device
   * - So, [getEncryptedMessage][org.whispersystems.signalservice.api.SignalServiceMessageSender.getEncryptedMessage] removes this device => it returns a OutgoingPushMessageList with an empty message list
   * - [sendMessage][org.whispersystems.signalservice.api.SignalServiceMessageSender.sendMessage] fails with NonSuccessfulResponseCodeException: [400],
   *     we don't get the Mismatched device exception [409] which allow handling ExtraDevices/MissingDevices.
   * - We're stuck in a loop where we can't send a message.
   */
  private fun loadPreKeys(deviceId: Int) {
    val recipient = SignalServiceAddress(Recipient.self().requireAci())
    val localProtocolAddress = SignalProtocolAddress(recipient.identifier, deviceId)
    val aciStore = AppDependencies.protocolStore.aci()
    val preKeys = NetworkResultUtil.toPreKeysLegacy(
      keysApi.getPreKeysSync(
        destination = recipient, sealedSenderAccess = null, deviceId = deviceId
      )
    )
    preKeys.forEach { preKey ->
      val preKeyAddress = SignalProtocolAddress(recipient.identifier, preKey.deviceId)
      val sessionBuilder = SignalSessionBuilder(
        ReentrantSessionLock.INSTANCE,
        SessionBuilder(aciStore, preKeyAddress, localProtocolAddress)
      )
      sessionBuilder.process(preKey)
    }
  }

  @WorkerThread
  @Throws(IOException::class)
  fun removeDevice(device: MollySocketDevice) {
    ThreadUtil.assertNotMainThread()
    if (!runBlocking { LinkDeviceRepository.removeDevice(device.deviceId) }) {
      throw IOException("Failed to remove MollySocket linked device")
    }
  }

  @WorkerThread
  fun getDeviceStatus(device: MollySocketDevice): LinkStatus {
    ThreadUtil.assertNotMainThread()
    return when (device.isLinked()) {
      true -> LinkStatus.LINKED
      false -> LinkStatus.NOT_LINKED
      else -> LinkStatus.UNKNOWN
    }
  }

  private fun MollySocketDevice.isLinked(): Boolean? {
    return runBlocking { LinkDeviceRepository.loadDevices() }?.any {
      // A linked device can change its own name, and an unreadable name has a fallback value.
      // Neither means its credential was revoked: keep tracking it until its ID is gone.
      it.id == deviceId
    }
  }

  fun discoverMollySocketServer(url: HttpUrl): Boolean {
    try {
      val request = Request.Builder().url(url).build()
      val client = AppDependencies.okHttpClient.newBuilder().build()
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          Log.d(TAG, "Unexpected code: $response")
          return false
        }
        val body = response.body
        JsonUtils.fromJson(body.byteStream(), Response::class.java)
      }
      Log.d(TAG, "URL is OK")
    } catch (e: Exception) {
      Log.d(TAG, "Exception: $e")
      return when (e) {
        is MalformedURLException,
        is JsonParseException,
        is JsonMappingException,
        is JsonProcessingException -> false

        else -> throw IOException("Can not check server status")
      }
    }
    return true
  }

  @Throws(IOException::class)
  fun registerDeviceOnServer(
    url: HttpUrl,
    device: MollySocketDevice,
    endpoint: String,
    ping: Boolean = false,
    accountId: String,
  ): ConnectionResult? {
    val requestData = ConnectionRequest(
      uuid = accountId,
      deviceId = device.deviceId,
      password = device.password,
      endpoint = endpoint,
      ping = ping,
    )

    val postBody = JsonUtils.toJson(requestData).toRequestBody(MEDIA_TYPE_JSON)
    val request = Request.Builder().url(url).post(postBody).build()
    val client = AppDependencies.okHttpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()

    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        Log.d(TAG, "Unexpected code: $response")
        return null
      }
      val body = response.body
      val resp = JsonUtils.fromJson(body.byteStream(), Response::class.java)

      val status = resp.mollySocket.status
      Log.d(TAG, "Status: $status")

      return status
    }
  }

  /**
   * Enqueues one credential request through the account handoff and keeps it registered through response parsing.
   * [consume] must only parse the response. Its caller must separately guard publication against later rejection or
   * account/route replacement; receiving a response does not authorize publishing it.
   */
  internal fun <T> executeBoundCall(
    client: okhttp3.OkHttpClient,
    request: Request,
    handoff: UnifiedPushLifecycle.CredentialSendHandoff,
    accountEpoch: Long,
    validUnderLifecycleMonitor: () -> Boolean,
    consume: (okhttp3.Response) -> T,
  ): T? {
    val result = CompletableFuture<okhttp3.Response>()
    val callbackDelivered = CountDownLatch(1)
    val call = client.newCall(request)
    val tracked = object : UnifiedPushLifecycle.CredentialSendHandoff.Call {
      override fun enqueue() {
        call.enqueue(object : okhttp3.Callback {
          override fun onFailure(call: okhttp3.Call, e: IOException) {
            try { result.completeExceptionally(e) } finally { callbackDelivered.countDown() }
          }
          override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            try { if (!result.complete(response)) response.close() } finally { callbackDelivered.countDown() }
          }
        })
      }

      override fun cancel() {
        call.cancel()
      }
    }
    if (!handoff.start(accountEpoch, tracked, validUnderLifecycleMonitor)) return null
    try {
      val response = try {
        result.get()
      } catch (e: InterruptedException) {
        // A cancelled OkHttp call may still be queued or finishing on its dispatcher. Keep the
        // handoff registered until its callback arrives, and close any response racing interruption.
        result.whenComplete { response, _ -> response?.close() }
        result.cancel(false)
        call.cancel()
        Thread.currentThread().interrupt()
        throw IOException("Credential request was interrupted", e)
      } catch (e: ExecutionException) {
        throw (e.cause as? IOException ?: IOException("Credential request failed", e.cause))
      }
      return response.use(consume)
    } finally {
      // Completion of the future can wake this thread before the OkHttp callback has returned.
      // Keep the call registered through callback delivery even if this thread is interrupted.
      var interrupted = Thread.interrupted()
      while (true) {
        try { callbackDelivered.await(); break } catch (_: InterruptedException) { interrupted = true }
      }
      try { handoff.finish(tracked) } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
  }
}

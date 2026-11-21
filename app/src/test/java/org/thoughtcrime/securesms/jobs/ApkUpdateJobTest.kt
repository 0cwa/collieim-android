package org.thoughtcrime.securesms.jobs

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.ApkUpdateValues
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.apkupdate.ApkUpdateNotifications
import org.thoughtcrime.securesms.apkupdate.CollieUpdateIndex
import org.thoughtcrime.securesms.apkupdate.CollieUpdateProfile
import org.thoughtcrime.securesms.keyvalue.MollyValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.net.Networking
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.thoughtcrime.securesms.util.TextSecurePreferences
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class ApkUpdateJobTest {
  private val values = mockk<ApkUpdateValues>(relaxUnitFun = true)
  private val mollyValues = mockk<MollyValues>(relaxUnitFun = true)
  private val client = mockk<OkHttpClient>()
  private val call = mockk<Call>()
  private var enabled = true
  private var result = CollieUpdateIndex.Result(1000, CollieUpdateIndex.Update(999999, "8.35.2", CollieUpdateProfile.STABLE))

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    mockkObject(SignalStore, ApkUpdateNotifications)
    mockkStatic(TextSecurePreferences::class)
    every { SignalStore.apkUpdate } returns values
    every { values.lastIndexTimestamp } returns 900
    every { values.availableVersionCode } returns 0
    every { values.availableVersionName } returns null
    every { values.lastNotifiedVersionCode } returns 0
    every { TextSecurePreferences.isUpdateApkEnabled(any()) } answers { enabled }
    // The updater switch lives in MollyValues since the Molly 8.34 merge.
    every { SignalStore.molly } returns mollyValues
    every { mollyValues.updateApkEnabled } answers { enabled }
    every { ApkUpdateNotifications.showUpdateAvailable(any(), any()) } returns true
    every { ApkUpdateNotifications.dismissUpdatePrompt(any()) } returns Unit
  }

  @After
  fun tearDown() = unmockkAll()

  @Test
  fun `metadata client uses Collie proxy DNS and socket policy without redirects`() {
    val networkClient = ApkUpdateJob.newClient()
    assertSame(Networking.socketFactory, networkClient.socketFactory)
    assertSame(Networking.proxySelectorForSocks, networkClient.proxySelector)
    assertSame(Networking.dns, networkClient.dns)
    assertFalse(networkClient.followRedirects)
    assertFalse(networkClient.followSslRedirects)
  }

  @Test
  fun `disabled checks do not construct a network client`() {
    enabled = false
    mockkObject(ApkUpdateJob.Companion)
    job().onRun()
    verify(exactly = 0) { ApkUpdateJob.newClient() }
    verify(exactly = 0) { values.setAvailableUpdate(any(), any(), any()) }
  }

  @Test
  fun `unsupported package identities cannot construct a network client or publish state`() {
    mockkObject(ApkUpdateJob.Companion)
    every { ApkUpdateJob.newClient() } throws AssertionError("An unsupported package must not check a production feed")
    for (applicationId in listOf("im.collie.app.staging", "im.collie.app.imo", "im.collie.app.trailblazer.staging", "im.molly.app", "")) {
      job(applicationId).onRun()
    }
    verify(exactly = 0) { ApkUpdateJob.newClient() }
    verify(exactly = 0) { values.setAvailableUpdate(any(), any(), any()) }
    verify(exactly = 0) { ApkUpdateNotifications.showUpdateAvailable(any(), any()) }
  }

  @Test
  fun `Beta fetches only its fixed feed and verifies it under the Beta identity`() {
    val profile = CollieUpdateProfile.TRAILBLAZER_BETA
    result = CollieUpdateIndex.Result(1000, CollieUpdateIndex.Update(999999, "8.36.0-beta1", profile))
    stubNetworkAndVerification(profile)
    val requests = mutableListOf<Request>()
    every { client.newCall(capture(requests)) } returns call

    job(profile.packageName).onRun()

    assertEquals(listOf("${profile.repositoryUrl}/entry.jar", "${profile.repositoryUrl}/index-v2.json"), requests.map { it.url.toString() })
    verify(exactly = 1) { CollieUpdateIndex.readEntry(profile, any(), any(), any()) }
    verify(exactly = 1) { CollieUpdateIndex.readIndex(profile, any(), any(), any(), any(), any()) }
    verify { ApkUpdateNotifications.showUpdateAvailable(any(), result.update!!) }
  }

  @Test
  fun `verified candidate is stored and only an allowed notification advances the notified version`() {
    stubNetworkAndVerification()
    every { ApkUpdateNotifications.showUpdateAvailable(any(), any()) } returns false
    job().onRun()
    verify { values.setAvailableUpdate(1000, 999999, "8.35.2") }
    verify(exactly = 0) { values.lastNotifiedVersionCode = any() }
    every { ApkUpdateNotifications.showUpdateAvailable(any(), any()) } returns true
    job().onRun()
    verify { values.lastNotifiedVersionCode = 999999 }
  }

  @Test
  fun `withdrawn newer release dismisses its notification when a lower candidate is selected`() {
    every { values.availableVersionCode } returns 999999
    every { values.availableVersionName } returns "8.35.2"
    every { values.lastNotifiedVersionCode } returns 999999
    result = CollieUpdateIndex.Result(1000, CollieUpdateIndex.Update(999998, "8.35.1", CollieUpdateProfile.STABLE))
    stubNetworkAndVerification()
    job().onRun()
    verify { values.setAvailableUpdate(1000, 999998, "8.35.1") }
    verify(exactly = 1) { ApkUpdateNotifications.dismissUpdatePrompt(any()) }
    verify(exactly = 0) { ApkUpdateNotifications.showUpdateAvailable(any(), any()) }
  }

  @Test
  fun `candidate change dismisses old notification even when replacement is suppressed`() {
    every { values.availableVersionCode } returns 999998
    every { values.availableVersionName } returns "8.35.1"
    every { ApkUpdateNotifications.showUpdateAvailable(any(), any()) } returns false
    stubNetworkAndVerification()
    job().onRun()
    verifyOrder {
      ApkUpdateNotifications.dismissUpdatePrompt(any())
      ApkUpdateNotifications.showUpdateAvailable(any(), result.update!!)
    }
    verify(exactly = 0) { values.lastNotifiedVersionCode = any() }
  }

  @Test
  fun `failed authentication cannot change update state or display a release`() {
    stubNetworkAndVerification()
    every { CollieUpdateIndex.readIndex(any(), any(), any(), any(), any(), any()) } throws IOException("Bad signature")
    assertThrows(IOException::class.java) { job().onRun() }
    verify(exactly = 0) { values.setAvailableUpdate(any(), any(), any()) }
    verify(exactly = 0) { values.lastSuccessfulCheck = any() }
    verify(exactly = 0) { ApkUpdateNotifications.showUpdateAvailable(any(), any()) }
  }

  @Test
  fun `disabling checks during a request prevents result publication`() {
    stubNetworkAndVerification()
    every { CollieUpdateIndex.readIndex(any(), any(), any(), any(), any(), any()) } answers {
      enabled = false
      result
    }
    job().onRun()
    verify(exactly = 0) { values.setAvailableUpdate(any(), any(), any()) }
    verify(exactly = 0) { ApkUpdateNotifications.showUpdateAvailable(any(), any()) }
  }

  @Test
  fun `installed release clears the candidate and old notification`() {
    result = CollieUpdateIndex.Result(1000, null)
    stubNetworkAndVerification()
    job().onRun()
    verify { values.setAvailableUpdate(1000, 0, null) }
    verify { ApkUpdateNotifications.dismissUpdatePrompt(any()) }
    verify(exactly = 0) { ApkUpdateNotifications.showUpdateAvailable(any(), any()) }
  }

  private fun job(applicationId: String = BuildConfig.APPLICATION_ID) = ApkUpdateJob(applicationId).apply { setContext(ApplicationProvider.getApplicationContext()) }

  private fun stubNetworkAndVerification(profile: CollieUpdateProfile = CollieUpdateProfile.STABLE) {
    mockkObject(ApkUpdateJob.Companion, CollieUpdateIndex)
    every { ApkUpdateJob.newClient() } returns client
    every { client.newCall(any()) } returns call
    every { call.execute() } answers {
      Response.Builder()
        .request(Request.Builder().url("${profile.repositoryUrl}/entry.jar").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(byteArrayOf(1).toResponseBody("application/java-archive".toMediaType()))
        .build()
    }
    every { CollieUpdateIndex.readEntry(profile, any(), any(), any()) } returns CollieUpdateIndex.Index(1000, 1, "00".repeat(32))
    every { CollieUpdateIndex.readIndex(profile, any(), any(), any(), any(), any()) } answers { result }
  }
}

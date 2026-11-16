package org.thoughtcrime.securesms.jobs

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import im.molly.unifiedpush.MollySocketRepository
import im.molly.unifiedpush.UnifiedPushDistributor
import im.molly.unifiedpush.enrollment.RelayDisclosure
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import im.molly.unifiedpush.model.LinkStatus
import im.molly.unifiedpush.model.MollySocketDevice
import im.molly.unifiedpush.model.RegistrationStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class UnifiedPushDisclosureRefreshJobTest {
  @get:Rule val signalStore = MockSignalStoreRule()

  private val application: Application get() = ApplicationProvider.getApplicationContext()
  private val values = UnifiedPushValues(KeyValueStore(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet())))
  private var accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
  private val device = MollySocketDevice(2, "SYNTHETIC-RELAY-PASSWORD")
  private val relayUrl = "https://relay.example.invalid/"
  private val vapid = "fixture-vapid"

  @Before fun setUp() {
    Log.initialize(SystemOutLogger())
    UnifiedPushLifecycle.resetForTests()
    mockkObject(AppDependencies, MollySocketRepository, UnifiedPushDistributor)
    mockkStatic(AppDependencies::class, KeyCachingService::class, UnifiedPushDistributor::class)
    every { AppDependencies.application } returns application
    every { AppDependencies.isInitialized } returns false
    every { AppDependencies.resetNetwork(any()) } returns Unit
    every { KeyCachingService.isLocked() } returns false
    every { SignalStore.unifiedpush } returns values
    val aci = mockk<ServiceId.ACI>()
    every { aci.toString() } answers { accountId }
    every { signalStore.account.aci } returns aci
    every { signalStore.account.isRegistered } returns true
    every { signalStore.account.isUnauthorizedReceived } returns false
    every { signalStore.account.hasAciIdentityKey() } returns true
    every { signalStore.account.servicePassword } returns "SYNTHETIC-SERVICE-PASSWORD"
    every { signalStore.account.deviceId } returns 1
    every { signalStore.settings.preferredNotificationMethod } returns NotificationDeliveryMethod.UNIFIEDPUSH
    values.enabled = true
    values.airGapped = false
    values.mollySocketUrl = relayUrl
    values.vapidPublicKey = vapid
    values.vapidKeySynced = true
    values.device = device
    values.endpoint = "https://push.example.invalid/endpoint"
    values.endpointP256dh = "SYNTHETIC-ENDPOINT-PUBLIC-KEY"
    values.endpointAuth = "SYNTHETIC-ENDPOINT-AUTH"
    values.registrationStatus = RegistrationStatus.BAD_RESPONSE
    every { UnifiedPushDistributor.registerApp(any()) } returns Unit
    every { UnifiedPushDistributor.checkIfActive() } returns true
    every { MollySocketRepository.getDeviceStatus(device) } returns LinkStatus.LINKED
    every { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) } returns null
  }

  @After fun tearDown() {
    unmockkStatic(AppDependencies::class, KeyCachingService::class, UnifiedPushDistributor::class)
    unmockkObject(AppDependencies, MollySocketRepository, UnifiedPushDistributor)
  }

  @Test fun `authorized setup refresh registers only its selected relay`() {
    run(UnifiedPushRefreshJob(authorization()))
    verify(exactly = 1) { MollySocketRepository.registerDeviceOnServer(match { it.toString() == relayUrl }, device, values.endpoint!!, false) }
  }

  @Test fun `queued setup for a previous account is dropped before any registration`() {
    val job = UnifiedPushRefreshJob(authorization())
    accountId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    run(job)
    verifyNoCredentialedWork()
  }

  @Test fun `every destination and process binding is checked before registration`() {
    val authorization = authorization()
    val stale = listOf(
      authorization.copy(processId = "previous-process"),
      authorization.copy(localDeviceId = 2),
      authorization.copy(url = "https://other-relay.example.invalid"),
      authorization.copy(vapid = "other-vapid"),
      authorization.copy(manual = true),
      authorization.copy(deliveryMethod = NotificationDeliveryMethod.WEBSOCKET),
    )
    stale.forEach { run(UnifiedPushRefreshJob(it)) }
    verifyNoCredentialedWork()
  }

  @Test fun `ordinary endpoint transition does not cancel authorized setup`() = runBlocking {
    val job = UnifiedPushRefreshJob(authorization())
    UnifiedPushAccountTransition.withTransition { values.endpoint = "https://push.example.invalid/renewed" }
    run(job)
    verify(exactly = 1) { MollySocketRepository.registerDeviceOnServer(any(), device, "https://push.example.invalid/renewed", false) }
  }

  @Test fun `temporarily unready lifecycle retains the existing bounded job retry`() {
    val job = UnifiedPushRefreshJob(authorization()).apply { setContext(application) }
    UnifiedPushLifecycle.resetForTests(initiallyReady = false)
    val failure = runCatching { job.onRun() }.exceptionOrNull()
    assertTrue(failure is IOException)
    assertTrue(job.onShouldRetry(failure as IOException))
    verify(exactly = 0) { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) }
    UnifiedPushLifecycle.resetForTests()
    job.onRun()
    verify(exactly = 1) { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) }
  }

  @Test fun `serialized setup keeps its binding without serializing a credential`() {
    val bytes = checkNotNull(UnifiedPushRefreshJob(authorization()).serialize())
    assertFalse(bytes.toString(Charsets.UTF_8).contains(device.password))
    assertFalse(bytes.toString(Charsets.UTF_8).contains("SYNTHETIC-ENDPOINT-AUTH"))
    val restored = UnifiedPushRefreshJob.Factory().create(Job.Parameters.Builder().build(), bytes)
    values.mollySocketUrl = "https://replacement-relay.example.invalid"
    run(restored)
    verify(exactly = 0) { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) }
  }

  @Test fun `existing background and legacy serialized refreshes continue without a prompt`() {
    run(UnifiedPushRefreshJob())
    val legacy = JsonJobData.Builder().putBoolean("ping", false).putBoolean("fromNewEndpoint", false).serialize()
    run(UnifiedPushRefreshJob.Factory().create(Job.Parameters.Builder().build(), legacy))
    verify(exactly = 2) { MollySocketRepository.registerDeviceOnServer(any(), device, values.endpoint!!, false) }
  }

  private fun run(job: UnifiedPushRefreshJob) {
    job.setContext(application)
    job.onRun()
  }

  private fun verifyNoCredentialedWork() {
    verify(exactly = 0) { UnifiedPushDistributor.registerApp(any()) }
    verify(exactly = 0) { MollySocketRepository.getDeviceStatus(any()) }
    verify(exactly = 0) { MollySocketRepository.createDevice() }
    verify(exactly = 0) { MollySocketRepository.removeDevice(any()) }
    verify(exactly = 0) { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) }
  }

  private fun authorization() = RelayDisclosure.RefreshAuthorization(
    processId = UnifiedPushLifecycle.processInstanceId(),
    accountId = accountId,
    localDeviceId = 1,
    manual = false,
    url = relayUrl,
    vapid = vapid,
    deliveryMethod = NotificationDeliveryMethod.UNIFIEDPUSH,
  )
}

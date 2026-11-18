package im.molly.unifiedpush.receiver

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.signal.core.util.crypto.KeyStoreHelper
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.keyvalue.SignalStore

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class LockedPushStateTest {
  @get:Rule val signalStore = MockSignalStoreRule()

  @Test
  fun `startup closes pending barrier without waiting for keystore`() {
    Log.initialize(SystemOutLogger())
    val context = ApplicationProvider.getApplicationContext<Application>()
    val prefs = context.getSharedPreferences("collie_locked_push", Context.MODE_PRIVATE)
    prefs.edit().putString("pending_endpoint", "opaque-record").commit()
    every { SignalStore.unifiedpush.hasIncompleteAccountTransition } returns false
    every { SignalStore.account.isRegistered } returns false
    mockkStatic(KeyStoreHelper::class)
    try {
      UnifiedPushLifecycle.resetForTests()
      UnifiedPushAccountTransition.initializeFromSignalStore(context)
      assertTrue(synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushLifecycle.hasPendingInvalidationLocked() })
      assertFalse(UnifiedPushLifecycle.isReady())
      verify(exactly = 0) { KeyStoreHelper.unseal(any()) }
    } finally {
      unmockkStatic(KeyStoreHelper::class)
      prefs.edit().clear().commit()
      UnifiedPushLifecycle.resetForTests()
    }
  }

  @Test
  fun `startup keeps an incomplete endpoint transition closed without a pending record`() {
    Log.initialize(SystemOutLogger())
    val context = ApplicationProvider.getApplicationContext<Application>()
    val prefs = context.getSharedPreferences("collie_locked_push", Context.MODE_PRIVATE)
    prefs.edit().clear().commit()
    every { SignalStore.unifiedpush.hasIncompleteAccountTransition } returns false
    every { SignalStore.unifiedpush.hasIncompleteEndpointTransition } returns true
    every { SignalStore.account.isRegistered } returns false
    try {
      UnifiedPushLifecycle.resetForTests()
      UnifiedPushAccountTransition.initializeFromSignalStore(context)
      assertTrue(synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushLifecycle.hasPendingInvalidationLocked() })
      assertFalse(UnifiedPushLifecycle.isReady())
    } finally {
      UnifiedPushLifecycle.resetForTests()
    }
  }

  @Test
  fun `legacy sealed record keeps a stable token and cannot acknowledge a replacement`() {
    Log.initialize(SystemOutLogger())
    val context = ApplicationProvider.getApplicationContext<Application>()
    val prefs = context.getSharedPreferences("collie_locked_push", Context.MODE_PRIVATE)
    val sealed = mockk<KeyStoreHelper.SealedData>()
    mockkStatic(KeyStoreHelper::class)
    mockkStatic(KeyStoreHelper.SealedData::class)
    try {
      every { KeyStoreHelper.SealedData.fromString(any()) } returns sealed
      every { KeyStoreHelper.unseal(sealed) } returns "https://push.invalid\np\na\ndefault".toByteArray()
      prefs.edit().putString("pending_endpoint", "first-ciphertext").commit()
      val first = checkNotNull(LockedPushState.peekPendingVersioned(context))
      assertEquals(first, LockedPushState.peekPendingVersioned(context))
      assertEquals(LockedPushState.Endpoint("https://push.invalid", "p", "a", "default"), first.endpoint)
      prefs.edit().putString("pending_endpoint", "replacement-ciphertext").commit()
      assertFalse(LockedPushState.clearPendingVersioned(context, first.token))
      assertTrue(LockedPushState.hasPending(context))
      val replacement = checkNotNull(LockedPushState.peekPendingVersioned(context))
      assertTrue(first.token != replacement.token)
      assertTrue(LockedPushState.clearPendingVersioned(context, replacement.token))
    } finally {
      prefs.edit().clear().commit()
      unmockkStatic(KeyStoreHelper.SealedData::class)
      unmockkStatic(KeyStoreHelper::class)
    }
  }

  @Test
  fun `failed sealing reports failure without replacing an existing event`() {
    Log.initialize(SystemOutLogger())
    val context = ApplicationProvider.getApplicationContext<Application>()
    val prefs = context.getSharedPreferences("collie_locked_push", Context.MODE_PRIVATE)
    prefs.edit().putString("pending_endpoint", "existing-sealed-record").commit()
    mockkStatic(KeyStoreHelper::class)
    try {
      every { KeyStoreHelper.seal(any()) } throws AssertionError("synthetic keystore failure")
      assertFalse(LockedPushState.savePendingVersioned(context, "new-event", LockedPushState.Endpoint("https://example.invalid", null, null, "instance")))
      assertEquals("existing-sealed-record", prefs.getString("pending_endpoint", null))
    } finally {
      unmockkStatic(KeyStoreHelper::class)
      prefs.edit().clear().commit()
    }
  }

  @Test
  fun `acknowledging an older event leaves an identical successor pending`() {
    Log.initialize(SystemOutLogger())
    val context = ApplicationProvider.getApplicationContext<Application>()
    val prefs = context.getSharedPreferences("collie_locked_push", Context.MODE_PRIVATE)
    val records = mutableMapOf<String, Pair<KeyStoreHelper.SealedData, ByteArray>>()
    mockkStatic(KeyStoreHelper::class)
    mockkStatic(KeyStoreHelper.SealedData::class)
    try {
      every { KeyStoreHelper.seal(any()) } answers {
        val bytes = firstArg<ByteArray>()
        val encoded = "synthetic-${records.size}"
        val sealed = mockk<KeyStoreHelper.SealedData>()
        every { sealed.serialize() } returns encoded
        records[encoded] = sealed to bytes
        sealed
      }
      every { KeyStoreHelper.SealedData.fromString(any()) } answers { records.getValue(firstArg()).first }
      every { KeyStoreHelper.unseal(any()) } answers { records.values.single { it.first === firstArg<KeyStoreHelper.SealedData>() }.second }

      val first = LockedPushState.Endpoint("https://first.invalid", null, null, "instance")
      val second = first.copy()
      assertTrue(LockedPushState.savePendingVersioned(context, "first", first))
      assertTrue(LockedPushState.savePendingVersioned(context, "second", second))
      assertFalse(LockedPushState.clearPendingVersioned(context, "first"))
      assertEquals(LockedPushState.PendingEndpoint("second", second), LockedPushState.peekPendingVersioned(context))
      assertTrue(LockedPushState.clearPendingVersioned(context, "second"))
      assertFalse(LockedPushState.hasPending(context))
    } finally {
      prefs.edit().clear().commit()
      unmockkStatic(KeyStoreHelper.SealedData::class)
      unmockkStatic(KeyStoreHelper::class)
    }
  }

  @Test
  fun `acknowledgement racing a new durable event cannot erase the successor`() {
    Log.initialize(SystemOutLogger())
    val context = ApplicationProvider.getApplicationContext<Application>()
    val prefs = context.getSharedPreferences("collie_locked_push", Context.MODE_PRIVATE)
    val records = java.util.concurrent.ConcurrentHashMap<String, Pair<KeyStoreHelper.SealedData, ByteArray>>()
    val sealingSuccessor = java.util.concurrent.CountDownLatch(1)
    val releaseSuccessor = java.util.concurrent.CountDownLatch(1)
    val clearStarted = java.util.concurrent.CountDownLatch(1)
    val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
    mockkStatic(KeyStoreHelper::class)
    mockkStatic(KeyStoreHelper.SealedData::class)
    try {
      every { KeyStoreHelper.seal(any()) } answers {
        val bytes = firstArg<ByteArray>()
        val token = String(bytes, Charsets.UTF_8).substringBefore('\n')
        if (token == "successor") {
          sealingSuccessor.countDown()
          check(releaseSuccessor.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        val sealed = mockk<KeyStoreHelper.SealedData>()
        every { sealed.serialize() } returns token
        records[token] = sealed to bytes
        sealed
      }
      every { KeyStoreHelper.SealedData.fromString(any()) } answers { records.getValue(firstArg()).first }
      every { KeyStoreHelper.unseal(any()) } answers { records.values.single { it.first === firstArg<KeyStoreHelper.SealedData>() }.second }
      val endpoint = LockedPushState.Endpoint("https://example.invalid", "p", "a", "instance")
      assertTrue(LockedPushState.savePendingVersioned(context, "original", endpoint))
      val save = workers.submit<Boolean> { LockedPushState.savePendingVersioned(context, "successor", endpoint) }
      assertTrue(sealingSuccessor.await(5, java.util.concurrent.TimeUnit.SECONDS))
      val clear = workers.submit<Boolean> {
        clearStarted.countDown()
        LockedPushState.clearPendingVersioned(context, "original")
      }
      assertTrue(clearStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
      releaseSuccessor.countDown()
      assertTrue(save.get(5, java.util.concurrent.TimeUnit.SECONDS))
      assertFalse(clear.get(5, java.util.concurrent.TimeUnit.SECONDS))
      assertEquals(LockedPushState.PendingEndpoint("successor", endpoint), LockedPushState.peekPendingVersioned(context))
    } finally {
      releaseSuccessor.countDown()
      workers.shutdownNow()
      workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
      prefs.edit().clear().commit()
      unmockkStatic(KeyStoreHelper.SealedData::class)
      unmockkStatic(KeyStoreHelper::class)
    }
  }

  @Test
  fun `pending record with wrong preference type remains present and cannot be acknowledged`() {
    Log.initialize(SystemOutLogger())
    val context = ApplicationProvider.getApplicationContext<Application>()
    val prefs = context.getSharedPreferences("collie_locked_push", Context.MODE_PRIVATE)
    prefs.edit().putInt("pending_endpoint", 7).commit()
    try {
      assertNull(LockedPushState.peekPendingVersioned(context))
      assertTrue(LockedPushState.hasPending(context))
      assertFalse(LockedPushState.clearPendingVersioned(context, "some-token"))
      assertTrue(LockedPushState.hasPending(context))
    } finally {
      prefs.edit().clear().commit()
    }
  }

  @Test
  fun `malformed pending record remains present and cannot be acknowledged`() {
    Log.initialize(SystemOutLogger())
    val context = ApplicationProvider.getApplicationContext<Application>()
    val prefs = context.getSharedPreferences("collie_locked_push", Context.MODE_PRIVATE)
    prefs.edit().putString("pending_endpoint", "not a sealed record").commit()
    try {
      assertNull(LockedPushState.peekPendingVersioned(context))
      assertTrue(LockedPushState.hasPending(context))
      assertTrue(!LockedPushState.clearPendingVersioned(context, "some-token"))
      assertTrue(LockedPushState.hasPending(context))
    } finally {
      prefs.edit().clear().commit()
    }
  }
}

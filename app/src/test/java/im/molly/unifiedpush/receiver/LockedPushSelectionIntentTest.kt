package im.molly.unifiedpush.receiver

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.crypto.KeyStoreHelper
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.testutil.SystemOutLogger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class LockedPushSelectionIntentTest {
  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private val tombstone = LockedPushState.Endpoint(null, null, null, "default")

  @Before fun setup() {
    Log.initialize(SystemOutLogger())
    mockkStatic(KeyStoreHelper::class)
    val sealed = mockk<KeyStoreHelper.SealedData>()
    every { sealed.serialize() } returns "fixture-sealed"
    every { KeyStoreHelper.seal(any()) } returns sealed
  }

  @After fun cleanup() = unmockkStatic(KeyStoreHelper::class)

  @Test fun `older worker cannot clear a newer registration intent`() {
    assertTrue(LockedPushState.trySaveSelection(context, tombstone, "first"))
    assertTrue(LockedPushState.markRegistrationReady(context, "first"))
    assertTrue(LockedPushState.trySaveSelection(context, tombstone, "second"))
    assertFalse(LockedPushState.clearRegistrationIfCurrent(context, "first"))
    assertEquals(LockedPushState.PendingRegistration("second", false), LockedPushState.pendingRegistration(context))
    assertFalse(LockedPushState.markRegistrationReady(context, "first"))
    assertTrue(LockedPushState.markRegistrationReady(context, "second"))
    assertTrue(LockedPushState.clearRegistrationIfCurrent(context, "second"))
    assertNull(LockedPushState.pendingRegistration(context))
  }

  @Test fun `endpoint events retain the independent registration intent`() {
    assertTrue(LockedPushState.trySaveSelection(context, tombstone, "first"))
    assertTrue(LockedPushState.trySavePending(context, LockedPushState.Endpoint("https://push.example.invalid/new", "p", "a", "default")))
    assertEquals(LockedPushState.PendingRegistration("first", false), LockedPushState.pendingRegistration(context))
  }

  @Test fun `seal failure does not create an intent`() {
    every { KeyStoreHelper.seal(any()) } throws IllegalStateException("fixture")
    assertFalse(LockedPushState.trySaveSelection(context, tombstone, "first"))
    assertNull(LockedPushState.pendingRegistration(context))
  }
  @Test fun `failed commit can mutate preferences and remains fenced`() {
    val real = context.getSharedPreferences("collie_locked_push", Context.MODE_PRIVATE)
    var commitCount = 0
    val fakePrefs = object : SharedPreferences by real {
      override fun edit(): SharedPreferences.Editor {
        val editor = real.edit()
        return object : SharedPreferences.Editor by editor {
          override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            editor.putString(key, value); return this
          }
          override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            editor.putBoolean(key, value); return this
          }
          override fun remove(key: String?): SharedPreferences.Editor {
            editor.remove(key); return this
          }
          override fun commit(): Boolean {
            val written = editor.commit()
            commitCount++
            return written && commitCount != 2
          }
        }
      }
    }
    val fakeContext = object : ContextWrapper(context) {
      override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = fakePrefs
    }
    assertTrue(LockedPushState.beginEndpointWrite(fakeContext))
    assertFalse(LockedPushState.trySaveSelection(fakeContext, tombstone, "rejected"))
    assertEquals(LockedPushState.PendingRegistration("rejected", false), LockedPushState.pendingRegistration(fakeContext))
    assertTrue(LockedPushState.hasUncertainWrite(fakeContext))
  }

}

package org.thoughtcrime.securesms.providers

import android.content.pm.ProviderInfo
import android.net.Uri
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.unmockkObject
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
import org.signal.core.util.PartAuthorityUris
import org.signal.core.util.crypto.AttachmentSecret
import org.signal.core.util.crypto.AttachmentSecretProvider
import org.signal.core.util.logging.Log
import org.signal.storage.database.DatabaseSecret
import org.signal.storage.database.DatabaseSecretProvider
import org.signal.storage.database.SqlCipherLibraryLoader
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.service.KeyCachingService
import java.lang.reflect.Field

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AvatarProviderLoggingTest {
  private val logFields: List<Field> = listOf("activeLogger", "initializedLogger", "internalCheck", "alwaysRedact")
    .map { Log::class.java.getDeclaredField(it).apply { isAccessible = true } }

  private lateinit var previousLogState: List<Any?>

  @Before
  fun setUp() {
    previousLogState = logFields.map { it.get(Log) }

    mockkStatic(KeyCachingService::class)
    every { KeyCachingService.isLocked() } returns false

    mockkObject(PartAuthorityUris)
    every { PartAuthorityUris.init(any()) } just Runs

    mockkObject(SqlCipherLibraryLoader)
    every { SqlCipherLibraryLoader.load() } just Runs

    mockkObject(DatabaseSecretProvider)
    every { DatabaseSecretProvider.getOrCreateDatabaseSecret(any()) } returns mockk<DatabaseSecret>(relaxed = true)

    mockkStatic(AttachmentSecretProvider::class)
    val attachmentSecretProvider = mockk<AttachmentSecretProvider>()
    every { AttachmentSecretProvider.getInstance(any(), any()) } returns attachmentSecretProvider
    every { attachmentSecretProvider.getOrCreateAttachmentSecret() } returns mockk<AttachmentSecret>(relaxed = true)

    mockkObject(SignalDatabase.Companion)
    every { SignalDatabase.init(any(), any(), any()) } just Runs
    every { SignalDatabase.instance } returns null

    mockkObject(SignalStore.Companion)
    every { SignalStore.init(any()) } just Runs

    mockkObject(AppDependencies)
    every { AppDependencies.isInitialized } returns true
  }

  @After
  fun tearDown() {
    unmockkStatic(KeyCachingService::class)
    unmockkObject(PartAuthorityUris)
    unmockkObject(SqlCipherLibraryLoader)
    unmockkObject(DatabaseSecretProvider)
    unmockkStatic(AttachmentSecretProvider::class)
    unmockkObject(SignalDatabase.Companion)
    unmockkObject(SignalStore.Companion)
    unmockkObject(AppDependencies)

    logFields.zip(previousLogState).forEach { (field, value) -> field.set(Log, value) }
  }

  @Test
  fun `provider initialization preserves disabled logging and redaction`() {
    val firstLogger = CapturingLogger()
    val secondLogger = CapturingLogger()
    Log.configure({ false }, false, true, firstLogger, secondLogger)

    assertNull(createProvider().getType(avatarUri()))

    assertTrue(Log.alwaysRedact)
    Log.i("AvatarProviderLoggingTest", "marker +12025550145")
    assertTrue(firstLogger.messages.isEmpty())
    assertTrue(secondLogger.messages.isEmpty())
  }

  @Test
  fun `provider initialization preserves redaction and every configured sink`() {
    val firstLogger = CapturingLogger()
    val secondLogger = CapturingLogger()
    Log.configure({ false }, true, true, firstLogger, secondLogger)

    assertNull(createProvider().getType(avatarUri()))
    firstLogger.messages.clear()
    secondLogger.messages.clear()

    val marker = "avatar-provider-marker +12025550145"
    Log.i("AvatarProviderLoggingTest", marker)

    assertTrue(Log.alwaysRedact)
    assertEquals(1, firstLogger.messages.size)
    assertEquals(1, secondLogger.messages.size)
    assertEquals(firstLogger.messages.single(), secondLogger.messages.single())
    assertTrue(firstLogger.messages.single()!!.contains("avatar-provider-marker"))
    assertFalse(firstLogger.messages.single()!!.contains("+12025550145"))
  }

  private fun createProvider(): AvatarProvider {
    val appContext = mockk<ApplicationContext>(relaxed = true)
    every { appContext.applicationContext } returns appContext

    return AvatarProvider().apply {
      attachInfo(
        appContext,
        ProviderInfo().apply {
          authority = "${BuildConfig.APPLICATION_ID}.avatar"
          exported = false
        }
      )
    }
  }

  private fun avatarUri(): Uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.avatar/avatar/1")

  private class CapturingLogger : Log.Logger() {
    val messages = mutableListOf<String?>()

    override fun v(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) { messages += message }
    override fun d(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) { messages += message }
    override fun i(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) { messages += message }
    override fun w(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) { messages += message }
    override fun e(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) { messages += message }
    override fun flush() = Unit
    override fun clear() { messages.clear() }
  }
}

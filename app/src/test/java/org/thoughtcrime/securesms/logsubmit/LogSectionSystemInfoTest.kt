package org.thoughtcrime.securesms.logsubmit

import android.app.Application
import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.storage.StorageManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.util.AppSignatureUtil
import org.thoughtcrime.securesms.util.SecurePreferenceManager
import org.thoughtcrime.securesms.util.TextSecurePreferences
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class LogSectionSystemInfoTest {
  private val context: Application = ApplicationProvider.getApplicationContext()

  @Before
  fun setUp() {
    val preferences = context.getSharedPreferences("locked-sysinfo-test", 0)
    preferences.edit().clear().putInt(TextSecurePreferences.FIRST_INSTALL_VERSION, 123).commit()
    mockkStatic(SecurePreferenceManager::class, GoogleApiAvailability::class, AppSignatureUtil::class)
    every { SecurePreferenceManager.getSecurePreferences(any()) } returns preferences
    val availability = mockk<GoogleApiAvailability>()
    every { GoogleApiAvailability.getInstance() } returns availability
    every { availability.isGooglePlayServicesAvailable(any()) } returns ConnectionResult.SERVICE_MISSING
    every { AppSignatureUtil.getAppSignature(any()) } returns "test"
    val storage = mockk<StorageManager>()
    val stats = mockk<StorageStatsManager>()
    every { storage.getUuidForPath(any()) } returns UUID(0, 0)
    every { stats.getTotalBytes(any()) } returns 1000L
    every { stats.getFreeBytes(any()) } returns 500L
    shadowOf(context).setSystemService(Context.STORAGE_SERVICE, storage)
    shadowOf(context).setSystemService(Context.STORAGE_STATS_SERVICE, stats)
  }

  @After
  fun tearDown() {
    unmockkStatic(SecurePreferenceManager::class, GoogleApiAvailability::class, AppSignatureUtil::class)
  }

  @Test
  fun `locked system info reads the first install version without SignalStore`() {
    val content = LogSectionSystemInfo().getContentLocked(context).toString()

    assertTrue(content.contains("First Version     : 123"))
  }
}

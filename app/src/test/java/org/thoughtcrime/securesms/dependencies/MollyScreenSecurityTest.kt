package org.thoughtcrime.securesms.dependencies

import android.app.Application
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [31])
class MollyScreenSecurityTest {
  @get:Rule
  val signalStore = MockSignalStoreRule(setOf(SettingsValues::class))

  @Before
  fun setUp() {
    mockkStatic(KeyCachingService::class)
    every { KeyCachingService.isLocked() } returns false
    every { signalStore.settings.isScreenSecurityEnabled } returns false
    ScreenLockController.enableAutoLock(false)
  }

  @After
  fun tearDown() {
    ScreenLockController.enableAutoLock(false)
    unmockkStatic(KeyCachingService::class)
  }

  @Test
  fun `screen security is enforced for automatic screen lock on older Android`() {
    assertFalse(CoreUiDependenciesProvider.provideIsScreenSecurityEnabled())
    ScreenLockController.enableAutoLock(true)
    assertTrue(CoreUiDependenciesProvider.provideIsScreenSecurityEnabled())
  }

  @Test
  fun `locked database enforces screen security even when the setting is off`() {
    every { KeyCachingService.isLocked() } returns true
    assertTrue(CoreUiDependenciesProvider.provideIsScreenSecurityEnabled())
  }
}

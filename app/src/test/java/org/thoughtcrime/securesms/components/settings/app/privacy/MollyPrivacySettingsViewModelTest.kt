package org.thoughtcrime.securesms.components.settings.app.privacy

import android.app.Application
import android.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.Observer
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.keyvalue.AccountValues
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.util.SecurePreferenceManager
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.thoughtcrime.securesms.util.livedata.LiveDataRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MollyPrivacySettingsViewModelTest {
  private val dependencies = MockAppDependenciesRule()
  private val signalStore = MockSignalStoreRule(setOf(AccountValues::class, SettingsValues::class))

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(LiveDataRule()).around(dependencies).around(signalStore)

  private val context: Application = ApplicationProvider.getApplicationContext()
  private val mollyPreferences by lazy { context.getSharedPreferences("molly-privacy-test", 0) }

  @Before
  fun setUp() {
    mollyPreferences.edit().clear().commit()
    PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    mockkStatic(SecurePreferenceManager::class)
    every { SecurePreferenceManager.getSecurePreferences(any()) } returns mollyPreferences
  }

  @After
  fun tearDown() {
    unmockkStatic(SecurePreferenceManager::class)
  }

  @Test
  fun `block unknown is saved in Molly preferences rather than Android defaults`() {
    val viewModel = PrivacySettingsViewModel(mollyPreferences, PrivacySettingsRepository())

    val enabled = CountDownLatch(1)
    val disabled = CountDownLatch(1)
    var sawEnabled = false
    val observer = Observer<PrivacySettingsState> { state ->
      if (state.blockUnknown) {
        sawEnabled = true
        enabled.countDown()
      } else if (sawEnabled) {
        disabled.countDown()
      }
    }
    viewModel.state.observeForever(observer)
    try {
      viewModel.setBlockUnknownEnabled(true)
      assertTrue(enabled.await(5, TimeUnit.SECONDS))
      assertTrue(TextSecurePreferences.isBlockUnknownEnabled(context))
      assertFalse(PreferenceManager.getDefaultSharedPreferences(context).getBoolean("pref_block_unknown", false))

      viewModel.setBlockUnknownEnabled(false)
      assertTrue(disabled.await(5, TimeUnit.SECONDS))
      assertFalse(TextSecurePreferences.isBlockUnknownEnabled(context))
    } finally {
      viewModel.state.removeObserver(observer)
    }
  }
}

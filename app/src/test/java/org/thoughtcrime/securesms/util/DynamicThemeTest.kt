/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.util.TypedValue
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.signal.core.util.ConfigurationUtil
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.testutil.EmptyLogger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [31, 35])
class DynamicThemeTest {

  private lateinit var context: Application
  private val activities = mutableListOf<ActivityController<ThemedActivity>>()

  @Before
  fun setUp() {
    Log.initialize(EmptyLogger())
    ShadowBuild.setManufacturer("Google")
    context = ApplicationProvider.getApplicationContext()
    val preferences = context.getSharedPreferences("dynamic-theme-test", Context.MODE_PRIVATE)
    preferences.edit().clear().commit()
    mockkStatic(SecurePreferenceManager::class)
    every { SecurePreferenceManager.getSecurePreferences(any()) } returns preferences
  }

  @After
  fun tearDown() {
    activities.asReversed().forEach { it.close() }
    unmockkStatic(SecurePreferenceManager::class)
  }

  @Test
  @Config(qualifiers = "notnight")
  fun `returning to a light activity applies dynamic colors without restarting the app`() {
    assertPaletteChangeOnResume(initialDynamicColors = false)
  }

  @Test
  @Config(qualifiers = "night")
  fun `returning to a dark activity applies dynamic colors without restarting the app`() {
    assertPaletteChangeOnResume(initialDynamicColors = false)
  }

  @Test
  @Config(qualifiers = "notnight")
  fun `returning to a light activity restores the Collie palette without restarting the app`() {
    assertPaletteChangeOnResume(initialDynamicColors = true)
  }

  @Test
  @Config(qualifiers = "night")
  fun `returning to a dark activity restores the Collie palette without restarting the app`() {
    assertPaletteChangeOnResume(initialDynamicColors = true)
  }

  @Test
  fun `unchanged Collie palette does not recreate a retained activity`() {
    assertUnchangedPaletteOnResume(dynamicColors = false)
  }

  @Test
  fun `unchanged dynamic palette does not recreate a retained activity`() {
    assertUnchangedPaletteOnResume(dynamicColors = true)
  }

  @Test
  @Config(sdk = [30])
  fun `unsupported Android versions keep the Collie palette without recreating`() {
    val controller = createActivity(dynamicColors = false)
    val original = controller.get()
    controller.pause().stop()

    TextSecurePreferences.setDynamicColorsEnabled(context, true)
    resume(controller)

    assertFalse(DynamicTheme.isDynamicColorsAvailable())
    assertSame(original, controller.get())
    assertFalse(controller.get().isUsingDynamicColors())
  }

  @Test
  fun `a newly created activity clears cached layouts when the palette changes`() {
    createActivity(dynamicColors = false).pause().stop()
    val cachedInflater = mockk<CachedInflater>(relaxed = true)
    mockkStatic(CachedInflater::class)
    every { CachedInflater.from(any()) } returns cachedInflater

    try {
      createActivity(dynamicColors = true)

      verify(exactly = 1) { cachedInflater.clear() }
    } finally {
      unmockkStatic(CachedInflater::class)
    }
  }

  private fun assertPaletteChangeOnResume(initialDynamicColors: Boolean) {
    val controller = createActivity(dynamicColors = initialDynamicColors)
    val original = controller.get()
    val nightMode = ConfigurationUtil.getNightModeConfiguration(original)
    original.selectedTab = "Archive"
    assertEquals(initialDynamicColors, original.isUsingDynamicColors())
    controller.pause().stop()

    TextSecurePreferences.setDynamicColorsEnabled(context, !initialDynamicColors)
    resume(controller)

    val recreated = controller.get()
    assertNotSame(original, recreated)
    assertSame(original.application, recreated.application)
    assertEquals("Archive", recreated.selectedTab)
    assertEquals(nightMode, ConfigurationUtil.getNightModeConfiguration(recreated))
    assertEquals(!initialDynamicColors, recreated.isUsingDynamicColors())

    controller.pause().stop()
    resume(controller)
    assertSame(recreated, controller.get())
  }

  private fun assertUnchangedPaletteOnResume(dynamicColors: Boolean) {
    val controller = createActivity(dynamicColors)
    val original = controller.get()
    controller.pause().stop()

    resume(controller)

    assertSame(original, controller.get())
    assertEquals(dynamicColors, controller.get().isUsingDynamicColors())
  }

  private fun createActivity(dynamicColors: Boolean): ActivityController<ThemedActivity> {
    TextSecurePreferences.setDynamicColorsEnabled(context, dynamicColors)
    return Robolectric.buildActivity(ThemedActivity::class.java).setup().also { activities += it }
  }

  private fun resume(controller: ActivityController<ThemedActivity>) {
    controller.restart().start().resume()
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun Activity.isUsingDynamicColors(): Boolean {
    val value = TypedValue()
    assertTrue(theme.resolveAttribute(org.signal.core.ui.R.attr.dynamic_colors, value, true))
    return value.data != 0
  }

  class ThemedActivity : Activity() {
    private val dynamicTheme = DynamicNoActionBarTheme()
    var selectedTab: String = "Chats"

    override fun onCreate(savedInstanceState: Bundle?) {
      dynamicTheme.onCreate(this)
      super.onCreate(savedInstanceState)
      selectedTab = savedInstanceState?.getString("selected-tab") ?: "Chats"
    }

    override fun onResume() {
      super.onResume()
      dynamicTheme.onResume(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
      super.onSaveInstanceState(outState)
      outState.putString("selected-tab", selectedTab)
    }
  }
}

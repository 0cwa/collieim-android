/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.TypedValue
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.signal.core.ui.compose.theme.ExtendedColors
import org.signal.core.ui.compose.theme.LocalSnackbarColors
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.ui.compose.theme.SnackbarColors
import org.signal.core.ui.compose.theme.UserPalettes
import org.signal.core.ui.compose.theme.withPureBlack
import org.signal.core.ui.permissions.RationaleDialog
import org.signal.core.ui.util.ThemeUtil
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.testutil.EmptyLogger
import com.google.android.material.R as MaterialR
import org.signal.core.ui.R as CoreUiR

/**
 * The stored pilot palette across Activity creation and return, off-Activity lookups, special windows and Compose.
 * Host JVM results: Robolectric resolves the real merged resources at each configured API level.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [27, 31, 35])
class UserPaletteThemeTest {

  @get:Rule
  val compose = createComposeRule()

  private lateinit var context: Application
  private val activities = mutableListOf<ActivityController<*>>()
  private val pilot = UserPalettes.byId(PILOT)!!

  @Before
  fun setUp() {
    Log.initialize(EmptyLogger())
    ShadowBuild.setManufacturer("Google")
    context = ApplicationProvider.getApplicationContext()
    val preferences = context.getSharedPreferences("user-palette-theme-test", Context.MODE_PRIVATE)
    preferences.edit().clear().commit()
    PlainTextKeyValueStore.init(preferences, 0)
  }

  @After
  fun tearDown() {
    activities.asReversed().forEach { it.pause().stop().destroy() }
    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
  }

  @Test
  fun `generated palette IDs match between Views and Compose`() {
    assertEquals(GeneratedPaletteOverlays.IDS, UserPalettes.all.map { it.id })
    assertEquals(0, GeneratedPaletteOverlays.overlay("collie", dark = false))
    assertEquals(0, GeneratedPaletteOverlays.overlay(null, dark = true))
  }

  @Test
  @Config(qualifiers = "notnight")
  fun `stored pilot applies its light roles with matching Compose roles`() {
    assertStoredPilot(dark = false)
  }

  @Test
  @Config(qualifiers = "night")
  fun `stored pilot applies its dark roles with matching Compose roles`() {
    assertStoredPilot(dark = true)
  }

  @Test
  @Config(qualifiers = "notnight")
  fun `Collie to pilot to System to Collie recreates on return and restores Collie bindings`() {
    val controller = createActivity<NoActionBarActivity>()
    val collieFab = attrColor(controller.get(), CoreUiR.attr.extended_action_button_container_color)
    val collieBar = statusBar(controller.get())
    assertNull(paletteMarker(controller.get()))
    assertEquals(ContextCompat.getColor(context, CoreUiR.color.collie_red), collieFab)

    select(PILOT)
    val pilotActivity = returnTo(controller)
    assertEquals(PILOT, paletteMarker(pilotActivity))
    assertEquals(pilot.light.secondaryContainer.toArgb(), attrColor(pilotActivity, CoreUiR.attr.extended_action_button_container_color))
    assertEquals(pilot.light.surface.toArgb(), statusBar(pilotActivity))

    select(ThemePalettes.SYSTEM)
    val systemActivity = returnTo(controller)
    assertNull(paletteMarker(systemActivity))
    assertEquals(DynamicTheme.isDynamicColorsAvailable(), isDynamic(systemActivity))
    assertEquals(null, PlainTextKeyValueStore.themePalette)

    select(ThemePalettes.COLLIE)
    // Without System colors support, System already rendered Collie, so the retained activity is kept.
    val collieActivity = if (DynamicTheme.isDynamicColorsAvailable()) returnTo(controller) else systemActivity.also { controller.pause().stop(); resume(controller) }
    assertSame(collieActivity, controller.get())
    assertNull(paletteMarker(collieActivity))
    assertEquals(collieFab, attrColor(collieActivity, CoreUiR.attr.extended_action_button_container_color))
    assertEquals(collieBar, statusBar(collieActivity))
    assertEquals(ContextCompat.getColor(context, CoreUiR.color.molly_primary_light), attrColor(collieActivity, MaterialR.attr.colorPrimary))

    // An unchanged choice keeps the retained activity.
    controller.pause().stop()
    resume(controller)
    assertSame(collieActivity, controller.get())
  }

  @Test
  @Config(sdk = [27], qualifiers = "night")
  fun `pilot to Collie on API 27 restores inherited roles`() {
    select(PILOT)
    val controller = createActivity<NoActionBarActivity>()
    assertEquals(pilot.dark.primary.toArgb(), attrColor(controller.get(), MaterialR.attr.colorPrimary))

    select(ThemePalettes.COLLIE)
    val collie = returnTo(controller)

    assertNull(paletteMarker(collie))
    assertEquals(ContextCompat.getColor(collie, CoreUiR.color.molly_primary_dark), attrColor(collie, MaterialR.attr.colorPrimary))
    assertEquals(attrColor(collie, MaterialR.attr.colorPrimary), attrColor(collie, CoreUiR.attr.extended_action_button_container_color))
  }

  @Test
  @Config(sdk = [27])
  fun `System flag on an unsupported device falls back to Collie even with a stored palette`() {
    PlainTextKeyValueStore.setPalette(dynamicColorsEnabled = true, paletteId = PILOT)
    val activity = createActivity<NoActionBarActivity>().get()

    assertNull(ThemePalettes.activePaletteId())
    assertEquals(ThemePalettes.SYSTEM, ThemePalettes.selectedId())
    assertNull(paletteMarker(activity))
    assertTrue(!isDynamic(activity))
  }

  @Test
  fun `unknown stored palette shows Collie and stays stored`() {
    PlainTextKeyValueStore.setPalette(dynamicColorsEnabled = false, paletteId = "from_a_newer_version")
    val activity = createActivity<NoActionBarActivity>().get()

    assertNull(paletteMarker(activity))
    assertEquals(ThemePalettes.COLLIE, ThemePalettes.selectedId())
    assertEquals("from_a_newer_version", PlainTextKeyValueStore.themePalette)
  }

  @Test
  @Config(qualifiers = "notnight")
  fun `media preview uses the pilot dark roles in light mode and keeps its window`() {
    val collie = createActivity<MediaPreviewActivity>().get()
    val collieBackground = windowBackground(collie)
    val collieStatusBar = statusBar(collie)

    select(PILOT)
    val activity = createActivity<MediaPreviewActivity>().get()

    assertEquals(PILOT, paletteMarker(activity))
    assertEquals(pilot.dark.surface.toArgb(), attrColor(activity, MaterialR.attr.colorSurface))
    assertEquals(pilot.dark.primary.toArgb(), attrColor(activity, MaterialR.attr.colorPrimary))
    assertEquals(collieBackground, windowBackground(activity))
    assertEquals(collieStatusBar, statusBar(activity))
  }

  @Test
  fun `opted-out windows keep Collie roles`() {
    select(PILOT)
    val activity = createActivity<OptedOutActivity>().get()

    assertNull(paletteMarker(activity))
    assertEquals(ContextCompat.getColor(activity, CoreUiR.color.collie_red), attrColor(activity, CoreUiR.attr.extended_action_button_container_color))
  }

  @Test
  fun `media viewer and conversation settings stay translucent with the pilot`() {
    select(PILOT)
    val viewer = createActivity<MediaViewerActivity>().get()
    val settings = createActivity<ConversationSettingsActivity>().get()

    for (activity in listOf(viewer, settings)) {
      assertEquals(PILOT, paletteMarker(activity))
      assertTrue(activity.javaClass.simpleName, booleanAttr(activity, android.R.attr.windowIsTranslucent))
    }
    assertEquals(0, windowBackground(viewer))
    assertEquals(ContextCompat.getColor(context, R.color.transparent_black_20), windowBackground(settings))
  }

  @Test
  @Config(qualifiers = "notnight")
  fun `off-Activity lookups follow forced dark and the pilot`() {
    select(PILOT)
    assertEquals(pilot.light.tertiary.toArgb(), DynamicTheme.resolveColor(context, MaterialR.attr.colorTertiary))

    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    assertEquals(pilot.dark.tertiary.toArgb(), DynamicTheme.resolveColor(context, MaterialR.attr.colorTertiary))

    select(ThemePalettes.COLLIE)
    assertEquals(ContextCompat.getColor(context, CoreUiR.color.molly_tertiary_dark), DynamicTheme.resolveColor(context, MaterialR.attr.colorTertiary))
  }

  @Test
  @Config(qualifiers = "notnight")
  fun `cached layouts are keyed by the palette marker`() {
    val collie = createActivity<NoActionBarActivity>().get()
    val collieKey = DynamicTheme.themeKey(collie)

    select(PILOT)
    val pilotActivity = createActivity<NoActionBarActivity>().get()

    assertNotEquals(collieKey, DynamicTheme.themeKey(pilotActivity))
    assertTrue(DynamicTheme.themeKey(pilotActivity).contains("|$PILOT|"))
  }

  @Test
  @Config(qualifiers = "notnight")
  fun `forced-dark Compose content in a light pilot activity uses the pilot dark roles`() {
    select(PILOT)
    val activity = createActivity<NoActionBarActivity>().get()
    var scheme: ColorScheme? = null

    compose.setContent {
      CompositionLocalProvider(LocalContext provides activity) {
        SignalTheme(isDarkMode = true, incognitoKeyboardEnabled = false) {
          scheme = MaterialTheme.colorScheme
        }
      }
    }
    compose.waitForIdle()

    assertSchemeEquals(pilot.dark, scheme!!)
  }

  @Test
  @Config(qualifiers = "night")
  fun `dark pilot reaches permission dialogs and rounded sheets`() {
    PlainTextKeyValueStore.setPalette(dynamicColorsEnabled = false, paletteId = PILOT)
    val activity = createActivity<NoActionBarActivity>().get()
    val rationale = RationaleDialog.createFor(activity, "Permission")
    val roundedSheet = BottomSheetDialog(activity, R.style.Widget_Signal_FixedRoundedCorners)

    for (context in listOf(rationale.context, roundedSheet.context)) {
      assertEquals(PILOT, paletteMarker(context))
      assertEquals(pilot.dark.primary.toArgb(), attrColor(context, MaterialR.attr.colorPrimary))
      assertEquals(pilot.dark.surface.toArgb(), attrColor(context, MaterialR.attr.colorSurface))
    }
  }

  @Test
  @Config(qualifiers = "night")
  fun `dark pilot reaches the default bottom sheet`() {
    PlainTextKeyValueStore.setPalette(dynamicColorsEnabled = false, paletteId = PILOT)
    val activity = createActivity<NoActionBarActivity>().get()
    val sheet = BottomSheetDialog(activity)

    assertEquals(PILOT, paletteMarker(sheet.context))
    assertEquals(pilot.dark.primary.toArgb(), attrColor(sheet.context, MaterialR.attr.colorPrimary))
    assertEquals(pilot.dark.surface.toArgb(), attrColor(sheet.context, MaterialR.attr.colorSurface))
  }

  @Test
  @Config(qualifiers = "notnight")
  fun `every built-in palette applies matching light roles in Views and Compose`() {
    assertEveryPalette(dark = false)
  }

  @Test
  @Config(qualifiers = "night")
  fun `every built-in palette applies matching dark roles in Views and Compose`() {
    assertEveryPalette(dark = true)
  }

  @Test
  @Config(qualifiers = "notnight")
  fun `switching straight between built-in palettes recreates with the new roles`() {
    val controller = createActivity<NoActionBarActivity>()
    var previous: Activity = controller.get()

    for (palette in UserPalettes.all + UserPalettes.all.first()) {
      select(palette.id)
      if (paletteMarker(previous) == palette.id) continue
      val activity = returnTo(controller)
      assertEquals(palette.id, paletteMarker(activity))
      assertEquals(palette.light.primary.toArgb(), attrColor(activity, MaterialR.attr.colorPrimary))
      assertEquals(palette.light.surface.toArgb(), statusBar(activity))
      previous = activity
    }
  }

  @Test
  @Config(qualifiers = "night")
  fun `pure black darkens only base surfaces for Collie, System and every palette`() {
    PlainTextKeyValueStore.isPureBlackEnabled = true
    val choices = listOf(ThemePalettes.COLLIE, ThemePalettes.SYSTEM) + UserPalettes.all.map { it.id }
    val activities = choices.associateWith { id ->
      select(id)
      val base = createActivity<NoActionBarActivity>().get()
      PlainTextKeyValueStore.isPureBlackEnabled = false
      val reference = createActivity<NoActionBarActivity>().get()
      PlainTextKeyValueStore.isPureBlackEnabled = true
      base to reference
    }
    val schemes = mutableMapOf<String, Pair<ColorScheme, ColorScheme>>()

    for ((id, pair) in activities) {
      val (activity, reference) = pair
      assertTrue(id, pureBlackMarker(activity))
      assertEquals(id, paletteMarker(reference), paletteMarker(activity))
      for (attr in BLACK_ATTRS) {
        assertEquals("$id attr $attr", BLACK, attrColor(activity, attr))
      }
      assertEquals(id, BLACK, statusBar(activity))
      assertEquals(id, BLACK, windowBackground(activity))
      for (attr in KEPT_ATTRS) {
        assertEquals("$id attr $attr", attrColor(reference, attr), attrColor(activity, attr))
      }
      assertTrue(id, contrast(attrColor(activity, MaterialR.attr.colorOnSurface), BLACK) >= 4.5)
      assertTrue(id, contrast(attrColor(activity, MaterialR.attr.colorOnSurfaceVariant), BLACK) >= 4.5)
    }

    compose.setContent {
      for ((id, pair) in activities) {
        CompositionLocalProvider(LocalContext provides pair.first) {
          SignalTheme(isDarkMode = true, incognitoKeyboardEnabled = false) { val black = MaterialTheme.colorScheme
            CompositionLocalProvider(LocalContext provides pair.second) {
              SignalTheme(isDarkMode = true, incognitoKeyboardEnabled = false) { schemes[id] = black to MaterialTheme.colorScheme }
            }
          }
        }
      }
    }
    compose.waitForIdle()

    for ((id, pair) in schemes) {
      val (black, reference) = pair
      val activity = activities.getValue(id).first
      // Pure black changes exactly the base roles of the same choice's Compose scheme.
      assertSchemeEquals(reference.withPureBlack(), black)
      // View and Compose agree for Collie and the presets. System on API 34+ uses Android's own Compose scheme, whose
      // parity with the View theme is the existing System behavior and outside this check.
      if (id != ThemePalettes.SYSTEM || Build.VERSION.SDK_INT < 34) {
        for ((attr, role) in ROLE_ATTRS) {
          assertEquals("$id Compose role for attr $attr", attrColor(activity, attr), role(black).toArgb())
        }
      } else {
        for (attr in BLACK_ATTRS) {
          assertEquals("$id attr $attr", BLACK, attrColor(activity, attr))
        }
      }
      assertEquals(id, androidx.compose.ui.graphics.Color.Black, black.surface)
      assertEquals(id, reference.surfaceContainer, black.surfaceContainer)
      assertEquals(id, reference.surfaceContainerHighest, black.surfaceContainerHighest)
      assertEquals(id, reference.primary, black.primary)
    }
  }

  @Test
  @Config(qualifiers = "notnight")
  fun `pure black leaves light activities, forced-dark Compose and special windows alone`() {
    select(PILOT)
    PlainTextKeyValueStore.isPureBlackEnabled = true
    val light = createActivity<NoActionBarActivity>().get()
    val preview = createActivity<MediaPreviewActivity>().get()
    var forcedDark: ColorScheme? = null

    assertTrue(!pureBlackMarker(light))
    assertEquals(pilot.light.surface.toArgb(), attrColor(light, MaterialR.attr.colorSurface))
    assertTrue(!pureBlackMarker(preview))
    assertEquals(pilot.dark.surface.toArgb(), attrColor(preview, MaterialR.attr.colorSurface))

    compose.setContent {
      CompositionLocalProvider(LocalContext provides light) {
        SignalTheme(isDarkMode = true, incognitoKeyboardEnabled = false) { forcedDark = MaterialTheme.colorScheme }
      }
    }
    compose.waitForIdle()
    assertSchemeEquals(pilot.dark, forcedDark!!)
  }

  @Test
  @Config(qualifiers = "night")
  fun `pure black skips always-dark and opted-out windows in dark mode`() {
    select(PILOT)
    PlainTextKeyValueStore.isPureBlackEnabled = true
    val preview = createActivity<MediaPreviewActivity>().get()
    val optedOut = createActivity<OptedOutActivity>().get()

    for (activity in listOf(preview, optedOut)) {
      assertTrue(activity.javaClass.simpleName, !pureBlackMarker(activity))
      assertNotEquals(activity.javaClass.simpleName, BLACK, attrColor(activity, MaterialR.attr.colorSurface))
    }
  }

  @Test
  @Config(qualifiers = "night")
  fun `turning pure black on and off recreates a retained dark activity on return`() {
    select(PILOT)
    val controller = createActivity<NoActionBarActivity>()
    val key = DynamicTheme.themeKey(controller.get())

    PlainTextKeyValueStore.isPureBlackEnabled = true
    val black = returnTo(controller)
    assertTrue(pureBlackMarker(black))
    assertEquals(BLACK, attrColor(black, MaterialR.attr.colorSurface))
    assertNotEquals(key, DynamicTheme.themeKey(black))

    PlainTextKeyValueStore.isPureBlackEnabled = false
    val restored = returnTo(controller)
    assertTrue(!pureBlackMarker(restored))
    assertEquals(pilot.dark.surface.toArgb(), attrColor(restored, MaterialR.attr.colorSurface))
    assertEquals(key, DynamicTheme.themeKey(restored))
  }

  @Test
  @Config(qualifiers = "notnight")
  fun `pure black keeps a light activity when only light mode is effective`() {
    val controller = createActivity<NoActionBarActivity>()
    val original = controller.get()

    PlainTextKeyValueStore.isPureBlackEnabled = true
    controller.pause().stop()
    resume(controller)

    assertSame(original, controller.get())
  }

  @Test
  @Config(qualifiers = "notnight")
  fun `off-Activity lookups apply pure black only at an effective dark mode`() {
    select(PILOT)
    PlainTextKeyValueStore.isPureBlackEnabled = true
    assertEquals(pilot.light.surface.toArgb(), DynamicTheme.resolveColor(context, MaterialR.attr.colorSurface))

    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    assertEquals(BLACK, DynamicTheme.resolveColor(context, MaterialR.attr.colorSurface))
    assertEquals(pilot.dark.surfaceContainer.toArgb(), DynamicTheme.resolveColor(context, MaterialR.attr.colorSurfaceContainer))
  }

  private fun assertEveryPalette(dark: Boolean) {
    val activities = UserPalettes.all.associate { palette ->
      select(palette.id)
      palette.id to createActivity<NoActionBarActivity>().get()
    }
    val schemes = mutableMapOf<String, ColorScheme>()

    for (palette in UserPalettes.all) {
      val activity = activities.getValue(palette.id)
      val expected = palette.colorScheme(dark)
      assertEquals(palette.id, paletteMarker(activity))
      for ((attr, role) in ROLE_ATTRS) {
        assertEquals("${palette.id} attr $attr", role(expected).toArgb(), attrColor(activity, attr))
      }
      assertEquals(expected.surface.toArgb(), statusBar(activity))
      assertEquals(listOf(expected.primary, expected.secondaryContainer, expected.tertiary, expected.surface), palette.swatch(dark))
    }

    compose.setContent {
      for ((id, activity) in activities) {
        CompositionLocalProvider(LocalContext provides activity) {
          SignalTheme(isDarkMode = dark, incognitoKeyboardEnabled = false) { schemes[id] = MaterialTheme.colorScheme }
        }
      }
    }
    compose.waitForIdle()

    for (palette in UserPalettes.all) {
      assertSchemeEquals(palette.colorScheme(dark), schemes.getValue(palette.id))
    }
  }

  private fun pureBlackMarker(context: Context): Boolean {
    val value = TypedValue()
    return context.theme.resolveAttribute(CoreUiR.attr.collie_pure_black, value, true) && value.data != 0
  }

  private fun contrast(a: Int, b: Int): Double {
    val la = androidx.core.graphics.ColorUtils.calculateLuminance(a)
    val lb = androidx.core.graphics.ColorUtils.calculateLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
  }

  private fun assertStoredPilot(dark: Boolean) {
    PlainTextKeyValueStore.setPalette(dynamicColorsEnabled = false, paletteId = PILOT)
    val activity = createActivity<NoActionBarActivity>().get()
    val expected = pilot.colorScheme(dark)
    var scheme: ColorScheme? = null
    var extended: ExtendedColors? = null
    var snackbar: SnackbarColors? = null

    assertEquals(PILOT, paletteMarker(activity))
    for ((attr, role) in ROLE_ATTRS) {
      assertEquals("attr $attr", role(expected).toArgb(), attrColor(activity, attr))
    }
    assertEquals(expected.surface.toArgb(), statusBar(activity))
    assertEquals(expected.primary.toArgb(), attrColor(activity, R.attr.signal_button_primary))
    assertEquals(expected.onSurfaceVariant.toArgb(), attrColor(activity, R.attr.signal_icon_tint_secondary))
    assertEquals(expected.surfaceContainer.toArgb(), attrColor(activity, R.attr.signal_background_dialog))
    assertEquals(expected.surfaceContainerLow.toArgb(), attrColor(activity, R.attr.signal_background_dialog_secondary))
    assertEquals(expected.secondaryContainer.toArgb(), attrColor(activity, CoreUiR.attr.extended_action_button_container_color))
    assertEquals(expected.surfaceContainerHigh.toArgb(), attrColor(activity, R.attr.navbar_container_color))

    compose.setContent {
      CompositionLocalProvider(LocalContext provides activity) {
        SignalTheme(isDarkMode = dark, incognitoKeyboardEnabled = false) {
          scheme = MaterialTheme.colorScheme
          extended = SignalTheme.colors
          snackbar = LocalSnackbarColors.current
        }
      }
    }
    compose.waitForIdle()

    for ((attr, role) in ROLE_ATTRS) {
      assertEquals("Compose role for attr $attr", attrColor(activity, attr), role(scheme!!).toArgb())
    }
    assertEquals(expected.surfaceContainerLow, extended!!.colorSurface1)
    assertEquals(expected.surfaceContainerHighest, extended!!.colorSurface4)
    assertEquals(if (dark) expected.surfaceBright else expected.surfaceDim, extended!!.colorSurface5)
    assertEquals(expected.inverseSurface, snackbar!!.color)
    assertEquals(expected.inversePrimary, snackbar!!.actionColor)
  }

  private fun assertSchemeEquals(expected: ColorScheme, actual: ColorScheme) {
    for ((attr, role) in ROLE_ATTRS) {
      assertEquals("role for attr $attr", role(expected).toArgb(), role(actual).toArgb())
    }
  }

  private fun select(paletteId: String) {
    when (paletteId) {
      ThemePalettes.SYSTEM -> PlainTextKeyValueStore.setPalette(dynamicColorsEnabled = true, paletteId = null)
      ThemePalettes.COLLIE -> PlainTextKeyValueStore.setPalette(dynamicColorsEnabled = false, paletteId = null)
      else -> PlainTextKeyValueStore.setPalette(dynamicColorsEnabled = false, paletteId = paletteId)
    }
  }

  private fun returnTo(controller: ActivityController<out Activity>): Activity {
    val original = controller.get()
    controller.pause().stop()
    resume(controller)
    val recreated = controller.get()
    assertNotSame(original, recreated)
    return recreated
  }

  private inline fun <reified T : Activity> createActivity(): ActivityController<T> {
    return Robolectric.buildActivity(T::class.java).setup().also { activities += it }
  }

  private fun resume(controller: ActivityController<out Activity>) {
    controller.restart().start().resume()
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun attrColor(context: Context, attr: Int): Int = ThemeUtil.getThemedColor(context, attr)

  /** The style's status bar color. Below API 35 the window also uses it; API 35 enforces transparent edge-to-edge bars. */
  private fun statusBar(activity: Activity): Int {
    val color = attrColor(activity, android.R.attr.statusBarColor)
    if (Build.VERSION.SDK_INT < 35) {
      assertEquals(color, activity.window.statusBarColor)
    }
    return color
  }

  private fun paletteMarker(context: Context): String? {
    val value = TypedValue()
    return if (context.theme.resolveAttribute(CoreUiR.attr.collie_palette, value, true)) value.string?.toString() else null
  }

  private fun isDynamic(activity: Activity): Boolean {
    val value = TypedValue()
    assertTrue(activity.theme.resolveAttribute(CoreUiR.attr.dynamic_colors, value, true))
    return value.data != 0
  }

  private fun booleanAttr(activity: Activity, attr: Int): Boolean {
    val value = TypedValue()
    return activity.theme.resolveAttribute(attr, value, true) && value.data != 0
  }

  private fun windowBackground(activity: Activity): Int {
    val array = activity.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
    try {
      val value = TypedValue()
      assertTrue(array.getValue(0, value))
      return if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
        value.data
      } else {
        assertNotNull(value.resourceId)
        ContextCompat.getColor(activity, value.resourceId)
      }
    } finally {
      array.recycle()
    }
  }

  abstract class ThemedActivity : Activity() {
    abstract val dynamicTheme: DynamicTheme

    override fun onCreate(savedInstanceState: Bundle?) {
      dynamicTheme.onCreate(this)
      super.onCreate(savedInstanceState)
    }

    override fun onResume() {
      super.onResume()
      dynamicTheme.onResume(this)
    }
  }

  class NoActionBarActivity : ThemedActivity() {
    override val dynamicTheme: DynamicTheme = DynamicNoActionBarTheme()
  }

  class MediaPreviewActivity : ThemedActivity() {
    override val dynamicTheme: DynamicTheme = DynamicMediaPreviewTheme()
  }

  class MediaViewerActivity : ThemedActivity() {
    override val dynamicTheme: DynamicTheme = DynamicMediaViewerTheme()
  }

  class ConversationSettingsActivity : ThemedActivity() {
    override val dynamicTheme: DynamicTheme = DynamicConversationSettingsTheme()
  }

  /** Mirrors WallpaperCropActivity's theme, which opts out of System colors and palettes. */
  class OptedOutActivity : ThemedActivity() {
    override val dynamicTheme: DynamicTheme = object : DynamicTheme() {
      override fun getRegularTheme(): Int = R.style.Signal_DayNight_WallpaperCropper
      override fun getDynamicTheme(): Int = getRegularTheme()
      override fun getPalettePolicy(): PalettePolicy = PalettePolicy.NONE
    }
  }

  companion object {
    private const val PILOT = "teal_vibrant"
    private const val BLACK = 0xFF000000.toInt()

    /** Base surfaces that Pure black replaces. */
    private val BLACK_ATTRS = listOf(
      android.R.attr.colorBackground,
      MaterialR.attr.colorSurface,
      MaterialR.attr.colorSurfaceDim,
      MaterialR.attr.colorSurfaceContainerLowest
    )

    /** Elevated containers, accents and foregrounds that Pure black keeps. */
    private val KEPT_ATTRS = listOf(
      MaterialR.attr.colorSurfaceContainerLow,
      MaterialR.attr.colorSurfaceContainer,
      MaterialR.attr.colorSurfaceContainerHigh,
      MaterialR.attr.colorSurfaceContainerHighest,
      MaterialR.attr.colorPrimary,
      MaterialR.attr.colorOnSurface,
      MaterialR.attr.colorOnSurfaceVariant,
      R.attr.signal_background_dialog,
      R.attr.navbar_container_color
    )

    /** Every theme role the generator maps to a Compose role, with that role. */
    private val ROLE_ATTRS: List<Pair<Int, (ColorScheme) -> androidx.compose.ui.graphics.Color>> = listOf(
      MaterialR.attr.colorPrimary to { it.primary },
      MaterialR.attr.colorOnPrimary to { it.onPrimary },
      MaterialR.attr.colorPrimaryContainer to { it.primaryContainer },
      MaterialR.attr.colorOnPrimaryContainer to { it.onPrimaryContainer },
      MaterialR.attr.colorPrimaryInverse to { it.inversePrimary },
      MaterialR.attr.colorSecondary to { it.secondary },
      MaterialR.attr.colorOnSecondary to { it.onSecondary },
      MaterialR.attr.colorSecondaryContainer to { it.secondaryContainer },
      MaterialR.attr.colorOnSecondaryContainer to { it.onSecondaryContainer },
      MaterialR.attr.colorTertiary to { it.tertiary },
      MaterialR.attr.colorOnTertiary to { it.onTertiary },
      MaterialR.attr.colorTertiaryContainer to { it.tertiaryContainer },
      MaterialR.attr.colorOnTertiaryContainer to { it.onTertiaryContainer },
      MaterialR.attr.colorError to { it.error },
      MaterialR.attr.colorOnError to { it.onError },
      MaterialR.attr.colorErrorContainer to { it.errorContainer },
      MaterialR.attr.colorOnErrorContainer to { it.onErrorContainer },
      android.R.attr.colorBackground to { it.background },
      MaterialR.attr.colorOnBackground to { it.onBackground },
      MaterialR.attr.colorSurface to { it.surface },
      MaterialR.attr.colorOnSurface to { it.onSurface },
      MaterialR.attr.colorSurfaceVariant to { it.surfaceVariant },
      MaterialR.attr.colorOnSurfaceVariant to { it.onSurfaceVariant },
      MaterialR.attr.colorSurfaceInverse to { it.inverseSurface },
      MaterialR.attr.colorOnSurfaceInverse to { it.inverseOnSurface },
      MaterialR.attr.colorSurfaceDim to { it.surfaceDim },
      MaterialR.attr.colorSurfaceBright to { it.surfaceBright },
      MaterialR.attr.colorSurfaceContainerLowest to { it.surfaceContainerLowest },
      MaterialR.attr.colorSurfaceContainerLow to { it.surfaceContainerLow },
      MaterialR.attr.colorSurfaceContainer to { it.surfaceContainer },
      MaterialR.attr.colorSurfaceContainerHigh to { it.surfaceContainerHigh },
      MaterialR.attr.colorSurfaceContainerHighest to { it.surfaceContainerHighest },
      MaterialR.attr.colorOutline to { it.outline },
      MaterialR.attr.colorOutlineVariant to { it.outlineVariant },
    )
  }
}

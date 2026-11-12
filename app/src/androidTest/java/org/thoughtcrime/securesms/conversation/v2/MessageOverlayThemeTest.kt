/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Process
import android.util.TypedValue
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.core.ui.util.ThemeUtil
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.database.MessageType
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.reactions
import org.thoughtcrime.securesms.main.MainDetailRoute
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.net.Networking
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testing.DisableAnimationsRule
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.testing.actions.scrollToDescendant
import org.thoughtcrime.securesms.util.DynamicTheme
import org.thoughtcrime.securesms.util.TextSecurePreferences
import java.io.File
import kotlin.math.abs
import org.signal.core.ui.R as CoreUiR

/** Renders a real message long press, including its native menu and Compose reaction overlay. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class MessageOverlayThemeTest {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 1)

  @get:Rule
  val animations = DisableAnimationsRule()

  @get:Rule
  val compose = createEmptyComposeRule()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context get() = harness.context
  private lateinit var previousTheme: SettingsValues.Theme
  private var previousDynamicColors = false
  private var previousScreenSecurity = true
  private var previousBiometricLock = false
  private var previousAutoLock = false
  private var previousLockAtStart = false
  private var previousNightMode = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED
  private var previousNetworkEnabled = false

  @Before
  fun savePreferences() {
    previousTheme = SignalStore.settings.theme
    previousDynamicColors = SignalStore.settings.isDynamicColorsEnabled
    previousScreenSecurity = SignalStore.settings.isScreenSecurityEnabled
    previousBiometricLock = TextSecurePreferences.isBiometricScreenLockEnabled(context)
    previousAutoLock = ScreenLockController.autoLock
    previousLockAtStart = ScreenLockController.lockScreenAtStart
    previousNightMode = AppCompatDelegate.getDefaultNightMode()
    previousNetworkEnabled = Networking.isEnabled

    AppDependencies.networkManager.setNetworkEnabled(false)
    SignalStore.settings.isScreenSecurityEnabled = false
    TextSecurePreferences.setBiometricScreenLockEnabled(context, false)
    ScreenLockController.enableAutoLock(false)
    ScreenLockController.lockScreenAtStart = false
  }

  @After
  fun restorePreferences() {
    instrumentation.runOnMainSync {
      SignalStore.settings.setTheme(previousTheme, previousDynamicColors)
      SignalStore.settings.isScreenSecurityEnabled = previousScreenSecurity
      TextSecurePreferences.setBiometricScreenLockEnabled(context, previousBiometricLock)
      ScreenLockController.enableAutoLock(previousAutoLock)
      ScreenLockController.lockScreenAtStart = previousLockAtStart
      AppCompatDelegate.setDefaultNightMode(previousNightMode)
      AppDependencies.networkManager.setNetworkEnabled(previousNetworkEnabled)
    }
  }

  @Test
  fun lightMessageOverlayUsesTheSelectedPalette() {
    assertOverlayPalettes(SettingsValues.Theme.LIGHT)
  }

  @Test
  fun darkMessageOverlayUsesTheSelectedPalette() {
    assertOverlayPalettes(SettingsValues.Theme.DARK)
  }

  private fun assertOverlayPalettes(theme: SettingsValues.Theme) {
    assertTrue(DynamicTheme.isDynamicColorsAvailable())
    val recipientId = harness.others.single()
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(recipientId))
    val timestamp = System.currentTimeMillis()
    val message = IncomingMessage(
      type = MessageType.NORMAL,
      from = recipientId,
      sentTimeMillis = timestamp,
      serverTimeMillis = timestamp,
      receivedTimeMillis = timestamp,
      body = MESSAGE_BODY
    )
    val messageId = SignalDatabase.messages.insertMessageInbox(message, threadId).get().messageId
    SignalDatabase.reactions.addReaction(
      MessageId(messageId),
      ReactionRecord(SignalStore.emoji.reactions.first(), harness.self.id, timestamp, timestamp)
    )
    SignalDatabase.threads.setRead(threadId)
    val conversationIntent = ConversationIntents.createBuilder(context, recipientId, threadId).blockingGet().build()
    val processId = Process.myPid()

    for (dynamicColors in listOf(false, true)) {
      instrumentation.runOnMainSync {
        SignalStore.settings.setTheme(theme, dynamicColors)
        DynamicTheme.setDefaultDayNightMode(context)
      }

      ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        lateinit var activity: MainActivity
        scenario.onActivity {
          activity = it
          val value = TypedValue()
          assertTrue(it.theme.resolveAttribute(CoreUiR.attr.dynamic_colors, value, true))
          assertEquals(dynamicColors, value.data != 0)
          // This supported warm navigation route leaves the launch intent intact, so
          // ActivityScenario can observe the activity's later lifecycle events.
          it.startActivity(
            MainActivity.clearTopAndOpenDetail(
              it,
              MainDetailRoute.Conversation(ConversationIntents.readArgsFromBundle(conversationIntent.extras!!))
            )
          )
        }

        scrollToDescendant(R.id.conversation_item_recycler, withText(MESSAGE_BODY), timeoutMs = 20_000)
        compose.waitForIdle()
        val before = screenshot()
        try {
          onView(withText(MESSAGE_BODY)).perform(longClick())
          compose.waitForIdle()

          val menuBounds = Rect()
          onView(withId(R.id.signal_context_menu_list))
            .inRoot(isPlatformPopup())
            .check(matches(isDisplayed()))
            .check { view, noMatch ->
              if (noMatch != null) throw noMatch
              val location = IntArray(2)
              view.getLocationOnScreen(location)
              menuBounds.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
            }

          val after = screenshot()
          try {
            saveScreenshot(after, "message-overlay-${theme.name.lowercase()}-dynamic-$dynamicColors")
            assertOverlayPixels(activity, before, after, menuBounds)
            assertEquals("Changing palettes must not restart the app process", processId, Process.myPid())
          } finally {
            after.recycle()
          }
        } finally {
          before.recycle()
        }
      }
    }
  }

  private fun assertOverlayPixels(activity: MainActivity, before: Bitmap, after: Bitmap, menuBounds: Rect) {
    assertEquals(before.width, after.width)
    assertEquals(before.height, after.height)
    val shade = ThemeUtil.getThemedColor(activity, R.attr.reactions_overlay_shade_color)
    val lightShade = ContextCompat.getColor(activity, R.color.reactions_screen_light_shade_color)
    val indicator = ThemeUtil.getThemedColor(activity, R.attr.reactions_overlay_selected_emoji_background)
    val menu = ThemeUtil.getThemedColor(activity, R.attr.context_menu_container_color)
    var scrimPixels = 0
    var indicatorPixels = 0
    var menuPixels = 0
    var menuSamples = 0
    var samples = 0

    for (y in 0 until after.height step 2) {
      for (x in 0 until after.width step 2) {
        val actual = after.getPixel(x, y)
        val original = before.getPixel(x, y)
        if (menuBounds.contains(x, y)) {
          menuSamples++
          if (colorsMatch(menu, actual)) menuPixels++
        } else {
          samples++
          if (!colorsMatch(original, actual)) {
            val expected = ColorUtils.compositeColors(shade, ColorUtils.compositeColors(lightShade, original))
            if (colorsMatch(expected, actual)) scrimPixels++
            if (colorsMatch(indicator, actual)) indicatorPixels++
          }
        }
      }
    }

    assertTrue("The real message overlay should apply the selected translucent scrim: $scrimPixels/$samples", scrimPixels > samples / 8)
    assertTrue("The selected reaction should render its themed indicator", indicatorPixels > 10)
    assertTrue("The real message popup should render its themed background: $menuPixels/$menuSamples", menuSamples > 0 && menuPixels > menuSamples / 4)
  }

  private fun colorsMatch(expected: Int, actual: Int): Boolean {
    return abs(Color.red(expected) - Color.red(actual)) <= 2 &&
      abs(Color.green(expected) - Color.green(actual)) <= 2 &&
      abs(Color.blue(expected) - Color.blue(actual)) <= 2
  }

  private fun screenshot(): Bitmap {
    compose.waitForIdle()
    return checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Could not capture the message overlay" }
  }

  private fun saveScreenshot(bitmap: Bitmap, name: String) {
    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
      ?.let(::File) ?: File(context.getExternalFilesDir(null), "theme-regression")
    assertTrue(output.isDirectory || output.mkdirs())
    File(output, "$name.png").outputStream().use { stream ->
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
    }
  }

  companion object {
    private const val MESSAGE_BODY = "Theme regression message for issue 83"
  }
}

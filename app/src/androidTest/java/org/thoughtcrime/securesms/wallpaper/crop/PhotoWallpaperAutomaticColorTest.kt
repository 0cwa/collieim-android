/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.wallpaper.crop

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.core.models.recipients.RecipientId
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.conversation.colors.ChatColors
import org.thoughtcrime.securesms.conversation.colors.ChatColorsPalette
import org.thoughtcrime.securesms.conversation.colors.ui.ChatColorSelectionRepository
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.net.Networking
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testing.DisableAnimationsRule
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.testing.actions.scrollToDescendant
import org.thoughtcrime.securesms.util.DynamicTheme
import org.thoughtcrime.securesms.wallpaper.ChatWallpaperPreviewActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class PhotoWallpaperAutomaticColorTest {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 1)

  @get:Rule
  val animations = DisableAnimationsRule()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()

  @Test
  fun photoColorReachesThePickerPreviewAndReadableOutgoingBubbles() {
    val recipientId = harness.others.single()
    val recipient = Recipient.resolved(recipientId)
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
    val previousTheme = SignalStore.settings.theme
    val previousDynamic = SignalStore.settings.isDynamicColorsEnabled
    val previousSecurity = SignalStore.settings.isScreenSecurityEnabled
    val previousAutoLock = ScreenLockController.autoLock
    val previousLockAtStart = ScreenLockController.lockScreenAtStart
    val previousNetwork = Networking.isEnabled
    val previousNightMode = AppCompatDelegate.getDefaultNightMode()
    val fallback = ChatColorsPalette.Bubbles.autoDefault
    val message = OutgoingMessage.text(recipient, MESSAGE, expiresIn = 0, sentTimeMillis = System.currentTimeMillis())
    val messageId = SignalDatabase.messages.insertMessageOutbox(message, threadId).messageId
    SignalDatabase.messages.markAsSent(messageId)
    SignalDatabase.recipients.setColor(recipientId, fallback)

    try {
      AppDependencies.networkManager.setNetworkEnabled(false)
      SignalStore.settings.isScreenSecurityEnabled = false
      ScreenLockController.enableAutoLock(false)
      ScreenLockController.lockScreenAtStart = false
      setTheme(SettingsValues.Theme.LIGHT)

      assertRenderedBubble(recipientId, threadId, fallback.asSingleColor(), "before-automatic")

      val wallpaper = WallpaperCropRepository(recipientId).setWallPaper(yellowWallpaper())
      val color = wallpaper.autoChatColors
      assertEquals(ChatColors.Id.Auto, color.id)
      assertNotEquals(fallback.asSingleColor(), color.asSingleColor())
      assertEquals(color.asSingleColor(), Recipient.resolved(recipientId).chatColors.asSingleColor())

      val picker = CompletableFuture<ChatColors>()
      ChatColorSelectionRepository.create(harness.context, recipientId).getChatColors { picker.complete(it) }
      assertEquals(color.asSingleColor(), picker.get(10, TimeUnit.SECONDS).asSingleColor())

      val preview = ChatWallpaperPreviewActivity.create(harness.context, wallpaper, recipientId, false)
      ActivityScenario.launch<ChatWallpaperPreviewActivity>(preview).use {
        onView(withId(R.id.preview_bubble_2)).check(matches(isDisplayed()))
        onView(withId(R.id.colorizer)).check { view, noMatch ->
          if (noMatch != null) throw noMatch
          assertEquals(color.asSingleColor(), (view.background as ColorDrawable).color)
        }
        screenshot("photo-wallpaper-preview").recycle()
      }

      assertRenderedBubble(recipientId, threadId, color.asSingleColor(), "after-automatic-light")
      setTheme(SettingsValues.Theme.DARK)
      assertRenderedBubble(recipientId, threadId, color.asSingleColor(), "after-automatic-dark")

      SignalDatabase.recipients.setColor(recipientId, ChatColorsPalette.Bubbles.BLUE)
      assertEquals(ChatColorsPalette.Bubbles.BLUE, Recipient.resolved(recipientId).chatColors)
      SignalDatabase.recipients.setColor(recipientId, fallback)
      assertEquals(color.asSingleColor(), Recipient.resolved(recipientId).chatColors.asSingleColor())

      SignalDatabase.recipients.setWallpaper(recipientId, null, true)
      assertEquals(fallback.asSingleColor(), Recipient.resolved(recipientId).chatColors.asSingleColor())
    } finally {
      SignalDatabase.recipients.setWallpaper(recipientId, null, true)
      SignalDatabase.messages.deleteMessage(messageId)
      instrumentation.runOnMainSync {
        SignalStore.settings.setTheme(previousTheme, previousDynamic)
        SignalStore.settings.isScreenSecurityEnabled = previousSecurity
        ScreenLockController.enableAutoLock(previousAutoLock)
        ScreenLockController.lockScreenAtStart = previousLockAtStart
        AppCompatDelegate.setDefaultNightMode(previousNightMode)
        AppDependencies.networkManager.setNetworkEnabled(previousNetwork)
      }
    }
  }

  private fun setTheme(theme: SettingsValues.Theme) {
    instrumentation.runOnMainSync {
      SignalStore.settings.setTheme(theme, false)
      DynamicTheme.setDefaultDayNightMode(harness.context)
    }
  }

  private fun assertRenderedBubble(recipientId: RecipientId, threadId: Long, color: Int, capture: String) {
    val intent = ConversationIntents.createBuilderSync(harness.context, recipientId, threadId).build()
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      scenario.onActivity {
        // Keep the launcher intent so ActivityScenario can observe teardown.
        val launcherIntent = it.intent
        instrumentation.callActivityOnNewIntent(it, Intent(intent))
        it.intent = launcherIntent
      }
      scrollToDescendant(R.id.conversation_item_recycler, withText(MESSAGE), timeoutMs = 20_000)
      val bounds = Rect()
      onView(allOf(withId(R.id.body_bubble), hasDescendant(withText(MESSAGE)))).check { view, noMatch ->
        if (noMatch != null) throw noMatch
        val body = view.findViewById<TextView>(R.id.conversation_item_body)
        assertTrue(ColorUtils.calculateContrast(body.currentTextColor, color) >= 4.5)
        assertTrue(ColorUtils.calculateContrast(body.linkTextColors.defaultColor, color) >= 4.5)
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        bounds.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
      }
      val image = screenshot(capture)
      try {
        var matches = 0
        for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(image.height) step 2) {
          for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(image.width) step 2) {
            if (image.getPixel(x, y) == color) matches++
          }
        }
        assertTrue("Outgoing bubble should contain the selected color in $capture ($matches pixels)", matches > 30)
      } finally {
        image.recycle()
      }
    }
  }

  private fun screenshot(name: String): Bitmap {
    instrumentation.waitForIdleSync()
    val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
      ?.let(::File) ?: File(harness.context.getExternalFilesDir(null), "theme-regression")
    assertTrue(output.isDirectory || output.mkdirs())
    File(output, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    return image
  }

  private fun yellowWallpaper(): ByteArray {
    val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(247, 203, 40)) }
    return try {
      ByteArrayOutputStream().use {
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        it.toByteArray()
      }
    } finally {
      bitmap.recycle()
    }
  }

  companion object {
    private const val MESSAGE = "Photo wallpaper contrast: https://example.org/"
  }
}

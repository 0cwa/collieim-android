package org.thoughtcrime.securesms.conversation.colors

import android.app.Application
import android.content.res.Configuration
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.model.databaseprotos.ChatColor
import org.thoughtcrime.securesms.wallpaper.GradientChatWallpaper
import org.thoughtcrime.securesms.wallpaper.SingleColorChatWallpaper
import org.thoughtcrime.securesms.wallpaper.UriChatWallpaper

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class CollieChatColorsTest {
  @Test
  fun `automatic photo and sunset wallpapers use Collie Redwood`() {
    val photo = UriChatWallpaper(Uri.parse("content://wallpaper/example"), 0f)
    assertEquals(ChatColorsPalette.Bubbles.autoDefault, photo.autoChatColors)
    assertEquals(ChatColorsPalette.Bubbles.autoDefault, GradientChatWallpaper.SUNSET.autoChatColors)
  }

  @Test
  fun `automatic navy wallpaper uses blue`() {
    assertEquals(ChatColorsPalette.Bubbles.BLUE.withId(ChatColors.Id.Auto), SingleColorChatWallpaper.NAVY.autoChatColors)
  }

  @Test
  fun `explicit saved purple choices survive deserialization`() {
    for (color in listOf(ChatColorsPalette.Bubbles.ULTRAMARINE, ChatColorsPalette.Bubbles.EMBER)) {
      assertEquals(color, ChatColors.forChatColor(color.id, color.serialize()))
    }
  }

  @Test
  fun `automatic Redwood keeps secondary message text readable in light and dark themes`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val background = ChatColorsPalette.Bubbles.autoDefault.asSingleColor()
    for (mode in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
      val config = Configuration(app.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
      }
      val themedContext = app.createConfigurationContext(config)
      for (colorId in listOf(
        R.color.conversation_item_sent_text_secondary_color,
        R.color.conversation_item_sent_text_primary_color,
        R.color.conversation_outgoing_footer_color,
        R.color.conversation_outgoing_body_color
      )) {
        val foreground = ContextCompat.getColor(themedContext, colorId)
        val visible = ColorUtils.compositeColors(foreground, background)
        assertTrue("Outgoing text must have at least 4.5:1 contrast", ColorUtils.calculateContrast(visible, background) >= 4.5)
      }
    }
  }

  @Test
  fun `malformed stored color falls back to automatic Redwood`() {
    val fallback = ChatColors.forChatColor(ChatColors.Id.BuiltIn, ChatColor.Builder().build())
    assertEquals(ChatColorsPalette.Bubbles.autoDefault, fallback)
  }
}

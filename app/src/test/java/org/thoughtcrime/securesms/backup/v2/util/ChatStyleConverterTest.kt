package org.thoughtcrime.securesms.backup.v2.util

import android.app.Application
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.archive.proto.ChatStyle
import org.thoughtcrime.securesms.backup.v2.ImportState
import org.thoughtcrime.securesms.conversation.colors.ChatColors
import org.thoughtcrime.securesms.conversation.colors.ChatColorsPalette

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class ChatStyleConverterTest {
  @Test
  fun `Ember and Ultramarine retain their backup presets after storage`() {
    val importState = mockk<ImportState>()
    val presets = listOf(ChatStyle.BubbleColorPreset.GRADIENT_EMBER, ChatStyle.BubbleColorPreset.SOLID_ULTRAMARINE)

    for (preset in presets) {
      val imported = requireNotNull(ChatStyle.Builder().bubbleColorPreset(preset).build().toLocal(importState))
      val restored = ChatColors.forChatColor(ChatColors.Id.BuiltIn, imported.serialize())
      assertEquals(preset, restored.toRemote())
    }

    val ember = ChatColorsPalette.Bubbles.EMBER
    val ultramarine = ChatColorsPalette.Bubbles.ULTRAMARINE
    assertNotEquals(ember, ultramarine)
    ember.getColors().forEach { assertEquals(ultramarine.singleColor, it) }
  }
}

package org.thoughtcrime.securesms.backup.v2.util

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.archive.proto.ChatStyle
import org.thoughtcrime.securesms.backup.v2.BackupMode
import org.thoughtcrime.securesms.backup.v2.ImportState
import org.thoughtcrime.securesms.conversation.colors.ChatColors
import org.thoughtcrime.securesms.conversation.colors.ChatColorsPalette
import org.thoughtcrime.securesms.database.SignalDatabase

class ChatStyleColorTest {
  private val importState: ImportState = mockk()
  private val database: SignalDatabase = mockk()
  private val backupMode = BackupMode.LOCAL

  @Test
  fun `automatic color survives export and import as automatic copper`() {
    val auto = ChatColorsPalette.Bubbles.autoDefault
    val exported = ChatStyleConverter.constructRemoteChatStyle(database, auto, ChatColors.Id.Auto, null, backupMode)!!
    assertNotNull(exported.autoBubbleColor)
    assertEquals(ChatColors.forColor(ChatColors.Id.Auto, 0xFF96390C.toInt()), exported.toLocal(importState))
  }

  @Test
  fun `explicit ultramarine remains selectable and survives export and import`() {
    val ultramarine = ChatColorsPalette.Bubbles.ULTRAMARINE
    assertTrue(ChatColorsPalette.Bubbles.all.contains(ultramarine))
    val exported = ChatStyleConverter.constructRemoteChatStyle(database, ultramarine, ChatColors.Id.BuiltIn, null, backupMode)!!
    assertEquals(ChatStyle.BubbleColorPreset.SOLID_ULTRAMARINE, exported.bubbleColorPreset)
    assertEquals(ultramarine, exported.toLocal(importState))
  }
}

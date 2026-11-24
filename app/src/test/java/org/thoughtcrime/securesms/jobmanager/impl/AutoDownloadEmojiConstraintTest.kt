package org.thoughtcrime.securesms.jobmanager.impl

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoDownloadEmojiConstraintTest {

  @Test
  fun `scheduler identity separates emoji downloads from unconstrained jobs`() {
    val context = mockk<Context>()
    every { context.applicationContext } returns context
    val constraint = AutoDownloadEmojiConstraint.Factory(context).create()

    assertEquals(AutoDownloadEmojiConstraint.KEY, constraint.jobSchedulerKeyPart)
  }
}

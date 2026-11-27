/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import android.content.Context
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.testing.SignalActivityRule

/** Renders the Stable release UI from an offline prepared-release fixture without touching app state. */
@RunWith(AndroidJUnit4::class)
class StableReleaseNotesScreenshotTest {
  @get:Rule val harness = SignalActivityRule(othersCount = 0, networkEnabled = false)
  @get:Rule val compose = createComposeRule()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context get() = instrumentation.targetContext

  @Test
  fun stableReleaseActionOpensWhatsNewHighlightsAndCapturesBothScreens() {
    val notes = ProjectLog.notes(
      releaseFiles = listOf(
        "version: 8.36.3-3\ndate: 2026-11-26\nhighlights: ${CollieReleaseNotes.STABLE_8_36_3_3}\n\nSynthetic offline Stable release fixture."
      ),
      unreleasedFiles = emptyList(),
      installedVersion = "8.36.3-3",
      prepared = true
    )
    assertEquals(ProjectLog.CurrentKind.RELEASE, notes.current.kind)
    assertEquals(CollieReleaseNotes.STABLE_8_36_3_3, notes.current.highlightsId)

    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        activity.setContent {
          SignalTheme {
            var showWhatsNew by remember { mutableStateOf(false) }
            if (showWhatsNew) {
              WhatsNewContent(
                notes = notes,
                startTab = WhatsNewTab.THIS_UPDATE,
                onBack = {},
                onReleaseAction = {},
                onFullNotes = {},
                onSupport = {},
                onOpenPullRequest = {},
                onHighlightsSeen = {}
              )
            } else {
              Column(Modifier.fillMaxSize().padding(24.dp)) {
                ProblemBanner(
                  problem = Problem.RELEASE_ACTION,
                  onFix = { showWhatsNew = true },
                  onDismiss = null,
                  contentPadding = PaddingValues(0.dp),
                  releaseAction = CollieReleaseNotes.edition(notes.current.highlightsId)?.action
                )
              }
            }
          }
        }
      }

      val action = requireNotNull(CollieReleaseNotes.edition(notes.current.highlightsId)?.action)
      compose.onNodeWithText(context.getString(action.title)).assertIsDisplayed()
      saveScreenshot("stable-release-action")

      compose.onNodeWithText(context.getString(action.button)).assertIsDisplayed().performClick()
      compose.onNodeWithText(context.getString(R.string.CollieWhatsNew__heading)).assertIsDisplayed()
      compose.onNodeWithText(context.getString(R.string.CollieWhatsNew__stable_setup_title)).assertIsDisplayed()
      saveScreenshot("stable-whats-new-highlights")
    }
  }

  private fun saveScreenshot(name: String) {
    compose.waitForIdle()
    val bitmap = instrumentation.uiAutomation.takeScreenshot()
    val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/collie-release-notes/"
    try {
      val resolver = context.contentResolver
      resolver.delete(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
        arrayOf("$name.png", relativePath)
      )
      val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      })) { "Could not create $name screenshot" }
      resolver.openOutputStream(uri).use { stream ->
        checkNotNull(stream) { "Could not open $name screenshot" }
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Could not encode $name screenshot" }
      }
      check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
    } finally {
      bitmap.recycle()
    }
  }
}

package org.thoughtcrime.securesms.mediapreview

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.core.models.media.TransformProperties
import org.signal.mediaviewer.MediaViewerScreenTestTags
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.attachments.UriAttachment
import org.thoughtcrime.securesms.components.AudioView
import org.thoughtcrime.securesms.components.DocumentView
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.conversation.ConversationItem
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.MmsHelper
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mediaoverview.MediaOverviewActivity
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.testing.DisableAnimationsRule
import org.thoughtcrime.securesms.testing.SignalActivityRule
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class AttachmentCategoryRemovalProofTest {
  @get:Rule val harness = SignalActivityRule(othersCount = 0, networkEnabled = false)
  @get:Rule val animations = DisableAnimationsRule()
  @get:Rule val compose = createEmptyComposeRule()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context get() = harness.context
  private val messageIds = mutableListOf<Long>()
  private var previousScreenSecurity = true
  private var previousBiometricLock = false
  private var previousAutoLock = false
  private var previousLockAtStart = false

  @Before
  fun allowScreenshotsInDisposableFixture() {
    previousScreenSecurity = SignalStore.settings.isScreenSecurityEnabled
    previousBiometricLock = PlainTextKeyValueStore.biometricScreenLockEnabled
    previousAutoLock = ScreenLockController.autoLock
    previousLockAtStart = ScreenLockController.lockScreenAtStart
    instrumentation.runOnMainSync {
      SignalStore.settings.isScreenSecurityEnabled = false
      PlainTextKeyValueStore.biometricScreenLockEnabled = false
      ScreenLockController.enableAutoLock(false)
      ScreenLockController.lockScreenAtStart = false
    }
  }

  @After
  fun restoreFixtureState() {
    messageIds.forEach { SignalDatabase.messages.deleteMessage(it) }
    instrumentation.runOnMainSync {
      SignalStore.settings.isScreenSecurityEnabled = previousScreenSecurity
      PlainTextKeyValueStore.biometricScreenLockEnabled = previousBiometricLock
      ScreenLockController.enableAutoLock(previousAutoLock)
      ScreenLockController.lockScreenAtStart = previousLockAtStart
    }
  }

  @Test
  fun mixedAlbumKeepsItsVideoAndMessageAfterImageRemoval() {
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(harness.self)
    val caption = "Proof caption for the mixed album"
    val messageId = seedMessage(
      threadId,
      caption,
      attachment(asset("images/theme_sample.png"), "image/png", "album-image.png", 160, 90, "Proof image tile"),
      attachment(asset("videos/theme_sample.mp4"), "video/mp4", "album-video.mp4", 160, 90, "Proof video tile")
    )
    val original = SignalDatabase.attachments.getAttachmentsForMessage(messageId)
    val imageId = original.single { it.contentType == "image/png" }.attachmentId
    val videoId = original.single { it.contentType == "video/mp4" }.attachmentId
    assertTrue(SignalDatabase.attachments.isAttachmentLocallyRemovable(imageId))

    openConversation(threadId) {
      val album = conversationRow(caption)
      val imageCell = allOf(withId(R.id.album_cell_1), isDescendantOfA(album))
      val videoCell = allOf(withId(R.id.album_cell_2), isDescendantOfA(album))
      awaitDisplayed(imageCell)
      awaitDisplayed(videoCell)
      onView(allOf(withId(R.id.play_overlay), isDescendantOfA(videoCell))).check(matches(isDisplayed()))
      onView(imageCell).perform(click())
      awaitComposeTag(MediaViewerScreenTestTags.OVERFLOW_BUTTON)
      compose.onNodeWithTag(MediaViewerScreenTestTags.OVERFLOW_BUTTON).performClick()
      compose.onNodeWithTag(MediaViewerScreenTestTags.MENU_REMOVE_ATTACHMENT).assertIsDisplayed().performClick()
      awaitComposeTag(MediaViewerScreenTestTags.REMOVE_ATTACHMENT_DIALOG)
      compose.onNodeWithTag(MediaViewerScreenTestTags.REMOVE_ATTACHMENT_DIALOG).assertIsDisplayed()
      capture("attachment-category-mixed-album-confirmation")
      compose.onNodeWithTag(MediaViewerScreenTestTags.REMOVE_ATTACHMENT_BUTTON).performClick()

      val removedMarker = allOf(withId(R.id.thumbnail_removed_label), isDescendantOfA(imageCell))
      awaitDisplayed(removedMarker)
      onView(withText(caption)).check(matches(isDisplayed()))
      onView(allOf(withId(R.id.play_overlay), isDescendantOfA(videoCell))).check(matches(isDisplayed()))
      onView(allOf(withId(R.id.thumbnail_removed_label), isDescendantOfA(videoCell), isDisplayed())).check(doesNotExist())
      val removed = checkNotNull(SignalDatabase.attachments.getAttachment(imageId))
      val kept = checkNotNull(SignalDatabase.attachments.getAttachment(videoId))
      assertTrue(removed.isLocallyRemoved)
      assertNull(removed.uri)
      assertFalse(kept.isLocallyRemoved)
      assertTrue(kept.uri != null)
      assertEquals(caption, SignalDatabase.messages.getMessageRecordOrNull(messageId)?.body)
      capture("attachment-category-mixed-album-retained")
    }
  }

  @Test
  fun audioTabRemovesOnlyAudioAndRetainsTheMessage() {
    removeFromOverview(
      tab = R.string.MediaOverviewActivity_Audio,
      fileName = "offline-test-audio.wav",
      mimeType = "audio/wav",
      bytes = silentWav(),
      label = "audio"
    )
  }

  @Test
  fun filesTabRemovesOnlyDocumentAndRetainsTheMessage() {
    removeFromOverview(
      tab = R.string.MediaOverviewActivity_Files,
      fileName = "offline-test-document.txt",
      mimeType = "text/plain",
      bytes = "Synthetic local document for the attachment proof.".toByteArray(),
      label = "document"
    )
  }

  private fun removeFromOverview(@StringRes tab: Int, fileName: String, mimeType: String, bytes: ByteArray, label: String) {
    val caption = "Proof message text for the $label attachment"
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(harness.self)
    val messageId = seedMessage(threadId, caption, attachment(bytes, mimeType, fileName))
    val attachmentId = SignalDatabase.attachments.getAttachmentsForMessage(messageId).single().attachmentId
    assertTrue(SignalDatabase.attachments.isAttachmentLocallyRemovable(attachmentId))

    ActivityScenario.launch<MediaOverviewActivity>(MediaOverviewActivity.forThread(context, threadId)).use {
      onView(allOf(withText(tab), isDescendantOfA(withId(R.id.tab_layout)))).perform(click())
      val fileRowText = allOf(withText(fileName), isDescendantOfA(withId(R.id.media_grid)))
      awaitDisplayed(fileRowText)
      onView(allOf(fileRowText, isDisplayed())).perform(longClick())

      val remove = onView(withText(R.string.CollieAttachmentRemoval_action)).inRoot(isPlatformPopup())
      remove.check(matches(isDisplayed()))
      capture("attachment-category-$label-menu")
      remove.perform(click())

      onView(withText(R.string.CollieAttachmentRemoval_confirm)).inRoot(isDialog()).check(matches(isDisplayed()))
      onView(withText(R.string.CollieAttachmentRemoval_remove)).inRoot(isDialog()).perform(click())

      val exactResult = context.getString(R.string.CollieAttachmentRemoval_result, 1, 1)
      compose.waitUntil(15_000) {
        compose.onAllNodes(hasText(exactResult)).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
      }
      compose.onNodeWithText(context.getString(R.string.CollieAttachmentRemoval_complete)).assertIsDisplayed()
      compose.onNodeWithText(exactResult).assertIsDisplayed()
      compose.onNodeWithText(context.getString(android.R.string.ok)).performClick()
      awaitDisplayed(withId(R.id.no_images))
      onView(allOf(fileRowText, isDisplayed())).check(doesNotExist())
    }

    val removed = checkNotNull(SignalDatabase.attachments.getAttachment(attachmentId))
    assertTrue(removed.isLocallyRemoved)
    assertNull(removed.uri)
    assertEquals(caption, SignalDatabase.messages.getMessageRecordOrNull(messageId)?.body)
    openConversation(threadId) {
      val row = conversationRow(caption)
      val document = allOf(isAssignableFrom(DocumentView::class.java), isDescendantOfA(row))
      val marker = allOf(withText(R.string.CollieAttachmentRemoval_removed), isDescendantOfA(document))
      awaitDisplayed(marker)
      onView(withText(caption)).check(matches(isDisplayed()))
      onView(allOf(isAssignableFrom(AudioView::class.java), isDescendantOfA(row), isDisplayed())).check(doesNotExist())
      capture("attachment-category-$label-retained")
    }
  }

  private fun conversationRow(caption: String): Matcher<View> =
    allOf(isAssignableFrom(ConversationItem::class.java), hasDescendant(withText(caption)))

  private fun openConversation(threadId: Long, block: () -> Unit) {
    val intent = ConversationIntents.createBuilderSync(context, harness.self.id, threadId).build()
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    try {
      block()
    } finally {
      instrumentation.runOnMainSync {
        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)
          .flatMap(lifecycle::getActivitiesInStage)
          .filterIsInstance<MainActivity>()
          .forEach { it.finish() }
      }
    }
  }

  private fun awaitDisplayed(matcher: Matcher<View>) {
    val deadline = SystemClock.uptimeMillis() + 20_000
    while (true) {
      try {
        onView(allOf(matcher, isDisplayed())).check(matches(isDisplayed()))
        return
      } catch (error: NoMatchingViewException) {
        if (SystemClock.uptimeMillis() >= deadline) throw error
      }
      SystemClock.sleep(50)
    }
  }

  private fun awaitComposeTag(tag: String) {
    compose.waitUntil(15_000) {
      compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }
  }

  private fun seedMessage(threadId: Long, caption: String, vararg attachments: UriAttachment): Long {
    val messageId = MmsHelper.insert(
      OutgoingMessage(
        threadRecipient = harness.self,
        sentTimeMillis = System.currentTimeMillis(),
        body = caption,
        isSecure = true,
        attachments = attachments.toList()
      ),
      threadId
    )
    messageIds += messageId
    SignalDatabase.messages.markAsSent(messageId, true)
    return messageId
  }

  private fun asset(name: String): ByteArray = instrumentation.context.assets.open(name).use { it.readBytes() }

  private fun attachment(bytes: ByteArray, mimeType: String, name: String, width: Int = 0, height: Int = 0, caption: String? = null): UriAttachment {
    val uri = AppDependencies.blobs.forData(bytes).withMimeType(mimeType).createForSingleSessionInMemory()
    return UriAttachment(
      dataUri = uri,
      contentType = mimeType,
      transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
      size = bytes.size.toLong(),
      width = width,
      height = height,
      fileName = name,
      fastPreflightId = null,
      voiceNote = false,
      borderless = false,
      videoGif = false,
      quote = false,
      quoteTargetContentType = null,
      caption = caption,
      stickerLocator = null,
      blurHash = null,
      audioHash = null,
      transformProperties = TransformProperties.empty()
    )
  }

  private fun silentWav(): ByteArray {
    val sampleRate = 8_000
    val dataBytes = sampleRate * 2
    return ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
      .put("RIFF".toByteArray(Charsets.US_ASCII))
      .putInt(dataBytes + 36)
      .put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
      .putInt(16)
      .putShort(1)
      .putShort(1)
      .putInt(sampleRate)
      .putInt(sampleRate * 2)
      .putShort(2)
      .putShort(16)
      .put("data".toByteArray(Charsets.US_ASCII))
      .putInt(dataBytes)
      .array()
  }

  private fun capture(name: String) {
    val automation = instrumentation.uiAutomation
    automation.waitForIdle(500, 5_000)
    val bitmap = checkNotNull(automation.takeScreenshot())
    val resolver = context.contentResolver
    try {
      val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/collie-settings-navigation/")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }))
      checkNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
      resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    } finally {
      bitmap.recycle()
    }
  }
}

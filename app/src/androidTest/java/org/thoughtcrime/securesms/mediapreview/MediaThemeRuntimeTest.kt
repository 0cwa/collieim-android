/*
 * Copyright 2026 Collie Messenger
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediapreview

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.ColorStateListDrawable
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.descendants
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.onView
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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.material.shape.MaterialShapeDrawable
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.sameInstance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.core.models.media.TransformProperties
import org.signal.core.ui.util.ThemeUtil
import org.signal.mediaviewer.MediaViewerScreenTestTags
import org.signal.mediaviewer.MediaViewerState
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.signal.video.VideoPlayer
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.attachments.UriAttachment
import org.thoughtcrime.securesms.components.ThumbnailView
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.conversation.ConversationItem
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.MmsHelper
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.main.MainActivity
import org.thoughtcrime.securesms.mediaoverview.MediaOverviewActivity
import org.thoughtcrime.securesms.mediaviewer.MediaViewerActivity
import org.thoughtcrime.securesms.mediaviewer.MediaViewerViewModel
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.testing.DisableAnimationsRule
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.testing.actions.scrollToDescendant
import org.thoughtcrime.securesms.util.DynamicTheme
import java.io.File
import kotlin.math.roundToInt
import org.signal.core.ui.R as CoreUiR
import org.signal.uicomponents.R as UiComponentsR
import org.signal.video.R as VideoR

/** Real media decoding, activity recreation, and screenshots on Android 15; no account/network media. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class MediaThemeRuntimeTest {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 0, networkEnabled = false)

  @get:Rule
  val animations = DisableAnimationsRule()

  @get:Rule
  val compose = createEmptyComposeRule()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context get() = harness.context
  private val messageIds = mutableListOf<Long>()
  private lateinit var previousTheme: SettingsValues.Theme
  private var previousDynamicColors = false
  private var previousScreenSecurity = true
  private var previousBiometricLock = false
  private var previousAutoLock = false
  private var previousLockAtStart = false
  private var previousNightMode = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED
  private var pausedVideo: VideoPlayer? = null

  @Before
  fun setUp() {
    previousTheme = SignalStore.settings.theme
    previousDynamicColors = SignalStore.settings.isDynamicColorsEnabled
    previousScreenSecurity = SignalStore.settings.isScreenSecurityEnabled
    previousBiometricLock = PlainTextKeyValueStore.biometricScreenLockEnabled
    previousAutoLock = ScreenLockController.autoLock
    previousLockAtStart = ScreenLockController.lockScreenAtStart
    previousNightMode = AppCompatDelegate.getDefaultNightMode()
    assertTrue("Dynamic Colors must be available on this test device", DynamicTheme.isDynamicColorsAvailable())

    SignalStore.settings.isScreenSecurityEnabled = false
    PlainTextKeyValueStore.biometricScreenLockEnabled = false
    ScreenLockController.enableAutoLock(false)
    ScreenLockController.lockScreenAtStart = false
  }

  @After
  fun tearDown() {
    instrumentation.runOnMainSync {
      SignalStore.settings.setTheme(previousTheme, previousDynamicColors)
      SignalStore.settings.isScreenSecurityEnabled = previousScreenSecurity
      PlainTextKeyValueStore.biometricScreenLockEnabled = previousBiometricLock
      ScreenLockController.enableAutoLock(previousAutoLock)
      ScreenLockController.lockScreenAtStart = previousLockAtStart
      AppCompatDelegate.setDefaultNightMode(previousNightMode)
    }
    messageIds.forEach { SignalDatabase.messages.deleteMessage(it) }
    pausedVideo = null
  }

  @Test
  fun composeImageUpdatesItsLightPaletteWithoutRestarting() {
    assertComposePaletteRoundTrip(Sample.IMAGE, SettingsValues.Theme.LIGHT)
  }

  @Test
  fun composeVideoUpdatesItsDarkPaletteWithoutRestarting() {
    assertComposePaletteRoundTrip(Sample.VIDEO, SettingsValues.Theme.DARK)
  }

  @Test
  fun individualAttachmentRemovalPreservesTheMessage() {
    selectTheme(SettingsValues.Theme.LIGHT, dynamic = false)
    val intent = mediaIntent(Sample.IMAGE, MediaViewerActivity::class.java)
    val messageId = messageIds.single()
    val attachmentId = SignalDatabase.attachments.getAttachmentsForMessage(messageId).single().attachmentId
    assertTrue(SignalDatabase.attachments.isAttachmentLocallyRemovable(attachmentId))

    ActivityScenario.launch<MediaViewerActivity>(intent).use { scenario ->
      awaitReady(scenario, false, "attachment-removal", composeContent = true) { activity ->
        val state = ViewModelProvider(activity)[MediaViewerViewModel::class.java].state.value
        decodedMediaReady(activity, Sample.IMAGE) && state.loadState == MediaViewerState.LoadState.MEDIA_READY && state.chromeVisible
      }
      compose.onNodeWithTag(MediaViewerScreenTestTags.CAPTION).assertIsDisplayed()
      compose.onNodeWithTag(MediaViewerScreenTestTags.OVERFLOW_BUTTON).performClick()
      compose.onNodeWithTag(MediaViewerScreenTestTags.MENU_DELETE).assertIsDisplayed()
      compose.onNodeWithTag(MediaViewerScreenTestTags.MENU_REMOVE_ATTACHMENT).assertIsDisplayed()
      captureRemovalSurface("attachment-removal-menu")
      compose.onNodeWithTag(MediaViewerScreenTestTags.MENU_REMOVE_ATTACHMENT).performClick()
      compose.waitUntil(10_000) {
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag(MediaViewerScreenTestTags.REMOVE_ATTACHMENT_DIALOG))
          .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
      }
      compose.onNodeWithTag(MediaViewerScreenTestTags.REMOVE_ATTACHMENT_DIALOG).assertIsDisplayed()
      captureRemovalSurface("attachment-removal-confirmation")
      compose.onNodeWithText(context.getString(org.signal.mediaviewer.R.string.MediaViewerScreen__cancel)).performClick()
      compose.onNodeWithTag(MediaViewerScreenTestTags.REMOVE_ATTACHMENT_DIALOG).assertDoesNotExist()
      assertTrue(SignalDatabase.attachments.isAttachmentLocallyRemovable(attachmentId))

      compose.onNodeWithTag(MediaViewerScreenTestTags.OVERFLOW_BUTTON).performClick()
      compose.onNodeWithTag(MediaViewerScreenTestTags.MENU_REMOVE_ATTACHMENT).performClick()
      compose.waitUntil(10_000) {
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag(MediaViewerScreenTestTags.REMOVE_ATTACHMENT_BUTTON))
          .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
      }
      compose.onNodeWithTag(MediaViewerScreenTestTags.REMOVE_ATTACHMENT_BUTTON).performClick()
      compose.waitUntil(10_000) {
        SignalDatabase.attachments.getAttachment(attachmentId)?.isLocallyRemoved == true
      }
      assertEquals(CAPTION, SignalDatabase.messages.getMessageRecordOrNull(messageId)?.body)
      val removed = checkNotNull(SignalDatabase.attachments.getAttachment(attachmentId))
      assertTrue(removed.isLocallyRemoved)
      assertTrue(removed.uri == null)
    }
  }

  @Test
  fun bulkAttachmentRemovalPreservesBothMessages() {
    selectTheme(SettingsValues.Theme.LIGHT, dynamic = false)
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(harness.self)
    val captions = listOf("Attachment proof image caption", "Attachment proof video caption")
    val now = System.currentTimeMillis()
    val messages = listOf(
      seedMedia(Sample.IMAGE, threadId, now - 1_000, captions[0]),
      seedMedia(Sample.VIDEO, threadId, now, captions[1])
    )
    val attachments = messages.map { SignalDatabase.attachments.getAttachmentsForMessage(it).single().attachmentId }

    ActivityScenario.launch<MediaOverviewActivity>(MediaOverviewActivity.forThread(context, threadId)).use { scenario ->
      var firstThumbnail: ThumbnailView? = null
      compose.waitUntil(20_000) {
        scenario.onActivity { activity ->
          val thumbnails = activity.findViewById<ViewGroup>(R.id.pager).descendants
            .filterIsInstance<ThumbnailView>()
            .filter { it.id == R.id.image && it.isShown && it.isLongClickable && it.getGlobalVisibleRect(Rect()) }
            .toList()
          if (thumbnails.size == attachments.size) firstThumbnail = thumbnails.first()
        }
        firstThumbnail != null
      }
      onView(sameInstance<View>(checkNotNull(firstThumbnail))).perform(longClick())
      onView(withText(R.string.CallContextMenu__select)).inRoot(isPlatformPopup()).perform(click())
      val actionBar = isDescendantOfA(withId(R.id.media_overview_bottom_action_bar))
      onView(allOf(withText(R.string.MediaOverviewActivity_select_all), actionBar)).perform(click())
      val removeFiles = onView(allOf(withText(R.string.CollieAttachmentRemoval_short_action), actionBar))
      removeFiles.check(matches(isDisplayed()))
      captureRemovalSurface("bulk-attachment-selection")

      removeFiles.perform(click())
      val confirmation = context.resources.getQuantityString(
        R.plurals.CollieAttachmentRemoval_confirmation,
        attachments.size,
        attachments.size
      )
      onView(withText(confirmation)).inRoot(isDialog()).check(matches(isDisplayed()))
      captureRemovalSurface("bulk-attachment-confirmation")
      onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click())
      onView(withText(R.string.CollieAttachmentRemoval_confirm)).check(doesNotExist())
      messages.zip(captions).forEach { (id, caption) ->
        assertEquals(caption, SignalDatabase.messages.getMessageRecordOrNull(id)?.body)
      }
      attachments.forEach { id ->
        assertTrue(SignalDatabase.attachments.isAttachmentLocallyRemovable(id))
        assertTrue(SignalDatabase.attachments.getAttachment(id)?.uri != null)
      }

      // Cancellation leaves the two-item selection available for another explicit confirmation.
      removeFiles.perform(click())
      onView(withText(confirmation)).inRoot(isDialog()).check(matches(isDisplayed()))
      onView(withText(R.string.CollieAttachmentRemoval_remove)).inRoot(isDialog()).perform(click())

      val expectedResult = context.getString(R.string.CollieAttachmentRemoval_result, attachments.size, attachments.size)
      compose.waitUntil(20_000) {
        compose.onAllNodes(androidx.compose.ui.test.hasText(expectedResult))
          .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
      }
      compose.onNodeWithText(context.getString(R.string.CollieAttachmentRemoval_complete)).assertIsDisplayed()
      compose.onNodeWithText(expectedResult).assertIsDisplayed()
      captureRemovalSurface("bulk-attachment-complete")
      compose.onNodeWithText(context.getString(android.R.string.ok)).performClick()

      messages.zip(captions).forEach { (id, caption) ->
        assertEquals(caption, SignalDatabase.messages.getMessageRecordOrNull(id)?.body)
      }
      attachments.forEach { id ->
        val removed = checkNotNull(SignalDatabase.attachments.getAttachment(id))
        assertTrue(removed.isLocallyRemoved)
        assertTrue(removed.uri == null)
      }
      compose.waitUntil(15_000) {
        var emptyGallery = false
        scenario.onActivity { activity ->
          emptyGallery = activity.findViewById<ViewGroup>(R.id.pager).descendants.any {
            it.id == R.id.no_images && it.isShown && it.getGlobalVisibleRect(Rect())
          }
        }
        emptyGallery
      }
      onView(allOf(withId(R.id.no_images), isDisplayed())).check(matches(isDisplayed()))
      onView(allOf(withId(R.id.image), isDisplayed())).check(doesNotExist())
      captureRemovalSurface("bulk-attachment-gallery-refreshed")
    }

    val conversation = ConversationIntents.createBuilder(context, harness.self.id, threadId).blockingGet().build()
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      scenario.onActivity { it.startActivity(conversation) }
      captions.forEachIndexed { index, caption ->
        val row = allOf(isAssignableFrom(ConversationItem::class.java), hasDescendant(withText(caption)))
        val marker = allOf(withId(R.id.thumbnail_removed_label), isDescendantOfA(row), isDisplayed())
        scrollToDescendant(R.id.conversation_item_recycler, withText(caption), timeoutMs = 20_000)
        onView(withText(caption)).check(matches(isDisplayed()))
        onView(marker).check(matches(isDisplayed()))
        captureRemovalSurface("bulk-attachment-retained-message-${index + 1}")
      }
    }
  }

  private fun captureRemovalSurface(name: String) {
    compose.waitForIdle()
    instrumentation.uiAutomation.waitForIdle(500, 5_000)
    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    val resolver = context.contentResolver
    try {
      val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/collie-settings-navigation/")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }))
      checkNotNull(resolver.openOutputStream(uri)).use { stream ->
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
      }
      resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    } finally {
      bitmap.recycle()
    }
  }

  private fun assertComposePaletteRoundTrip(sample: Sample, appTheme: SettingsValues.Theme) {
    selectTheme(appTheme, dynamic = false)
    val processId = Process.myPid()
    val intent = mediaIntent(sample, MediaViewerActivity::class.java)
    val dark = appTheme == SettingsValues.Theme.DARK
    ActivityScenario.launch<MediaViewerActivity>(intent).use { scenario ->
      var previousActivity: Activity? = null
      for ((index, dynamic) in listOf(false, true, false).withIndex()) {
        val captureName = "media-compose-${sample.name.lowercase()}-$index-dynamic-$dynamic"
        if (index > 0) {
          changeRetainedPalette(scenario, appTheme, dynamic)
        }
        val activity = awaitReady(scenario, dynamic, captureName, composeContent = true) { media ->
          val state = ViewModelProvider(media)[MediaViewerViewModel::class.java].state.value
          decodedMediaReady(media, sample) && state.loadState == MediaViewerState.LoadState.MEDIA_READY && state.currentCaption?.text == CAPTION && state.chromeVisible
        }
        assertSame(harness.application, activity.application)
        assertEquals(processId, Process.myPid())
        if (previousActivity != null) assertNotSame(previousActivity, activity)

        val surface = if (dynamic) {
          ContextCompat.getColor(activity, if (dark) android.R.color.system_surface_dark else android.R.color.system_surface_light)
        } else {
          Color.parseColor(if (dark) "#24130D" else "#FFF8F0")
        }
        scenario.onActivity { media ->
          assertEquals(if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO, media.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
          assertTrue(ThemeUtil.getThemedBoolean(media, android.R.attr.windowIsTranslucent))
          assertEquals(Color.TRANSPARENT, ThemeUtil.getThemedColor(media, android.R.attr.windowBackground))
          assertEquals(surface, backgroundColor(media.findViewById(R.id.background_scrim)))
        }
        compose.onNodeWithTag(if (sample == Sample.VIDEO) MediaViewerScreenTestTags.PAGE_VIDEO else MediaViewerScreenTestTags.PAGE_IMAGE).assertIsDisplayed()
        compose.onNodeWithTag(MediaViewerScreenTestTags.CAPTION).assertIsDisplayed()
        if (sample == Sample.VIDEO) {
          compose.onNodeWithTag(MediaViewerScreenTestTags.PLAY_PAUSE_BUTTON).assertIsDisplayed()
        }
        val toolbarY = compose.onNodeWithTag(MediaViewerScreenTestTags.BACK_BUTTON).fetchSemanticsNode().boundsInWindow.center.y.toInt()
        val captionY = compose.onNodeWithTag(MediaViewerScreenTestTags.CAPTION).fetchSemanticsNode().boundsInWindow.center.y.toInt()
        assertScreenshot(activity, captureName, sample, surface, intArrayOf(toolbarY, captionY), composeContent = true)
        previousActivity = activity
      }
    }
  }

  private fun selectTheme(theme: SettingsValues.Theme, dynamic: Boolean) {
    instrumentation.runOnMainSync {
      SignalStore.settings.setTheme(theme, dynamic)
      DynamicTheme.setDefaultDayNightMode(context)
    }
  }

  private fun <T : Activity> changeRetainedPalette(scenario: ActivityScenario<T>, theme: SettingsValues.Theme, dynamic: Boolean) {
    scenario.moveToState(Lifecycle.State.CREATED)
    instrumentation.runOnMainSync { SignalStore.settings.setTheme(theme, dynamic) }
    scenario.moveToState(Lifecycle.State.RESUMED)
  }

  private fun <T : Activity> awaitReady(scenario: ActivityScenario<T>, dynamic: Boolean, captureName: String, composeContent: Boolean = false, ready: (T) -> Boolean): T {
    val deadline = SystemClock.uptimeMillis() + 20_000
    var lastState = "Activity not observed"
    while (SystemClock.uptimeMillis() < deadline) {
      // The Compose test clock only drives recomposition when the test synchronizes with it.
      if (composeContent) compose.waitForIdle()
      var found: T? = null
      scenario.onActivity { activity ->
        val appliedDynamic = ThemeUtil.getThemedBoolean(activity, CoreUiR.attr.dynamic_colors)
        val mediaReady = ready(activity)
        lastState = "dynamic=$appliedDynamic, ready=$mediaReady" + if (activity is MediaViewerActivity) {
          ", ${ViewModelProvider(activity)[MediaViewerViewModel::class.java].state.value}"
        } else {
          ""
        }
        if (appliedDynamic == dynamic && mediaReady) {
          found = activity
        }
      }
      found?.let {
        instrumentation.waitForIdleSync()
        return it
      }
      SystemClock.sleep(50)
    }
    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
      try {
        saveScreenshot(bitmap, "$captureName-readiness-failure")
      } finally {
        bitmap.recycle()
      }
    }
    error("Media did not decode and display the requested palette (dynamic=$dynamic): $lastState")
  }

  @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
  private fun decodedMediaReady(activity: Activity, sample: Sample): Boolean {
    if (sample == Sample.IMAGE) {
      return activity.findViewById<SubsamplingScaleImageView>(UiComponentsR.id.subsampling_image_view)?.isImageLoaded == true
    }
    val video = findVideoPlayer(activity) ?: return false
    val player = video.findViewById<PlayerView>(VideoR.id.video_view)?.player
    return video.duration > 0 && player?.playbackState == Player.STATE_READY
  }

  private fun seedMedia(sample: Sample, threadId: Long, timestamp: Long, caption: String): Long {
    val bytes = instrumentation.context.assets.open(sample.asset).use { it.readBytes() }
    val uri = AppDependencies.blobs.forData(bytes).withMimeType(sample.mimeType).createForSingleSessionInMemory()
    val attachment = UriAttachment(
      dataUri = uri,
      contentType = sample.mimeType,
      transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
      size = bytes.size.toLong(),
      width = sample.width,
      height = sample.height,
      fileName = sample.asset.substringAfterLast('/'),
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
    val messageId = MmsHelper.insert(
      OutgoingMessage(
        threadRecipient = harness.self,
        sentTimeMillis = timestamp,
        body = caption,
        isSecure = true,
        attachments = listOf(attachment)
      ),
      threadId
    )
    messageIds += messageId
    SignalDatabase.messages.markAsSent(messageId, true)
    return messageId
  }

  private fun mediaIntent(sample: Sample, activityClass: Class<out Activity>): Intent {
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(harness.self)
    val messageId = seedMedia(sample, threadId, System.currentTimeMillis(), CAPTION)
    val stored = SignalDatabase.attachments.getAttachmentsForMessage(messageId).single()
    val record = checkNotNull(SignalDatabase.media.getViewableGalleryMediaForAttachment(threadId, stored.attachmentId.id))
    val intent = MediaIntentFactory.intentFromMediaRecord(context, record, leftIsRecent = false, allMediaInRail = false)
    val args = MediaIntentFactory.requireArguments(intent.extras!!).skipSharedElementTransition(true)
    // Exercise both real viewer implementations irrespective of the rollout flag.
    return MediaIntentFactory.create(context, args).setClass(context, activityClass)
  }

  private fun backgroundColor(view: View): Int = when (val background = view.background) {
    is ColorDrawable -> background.color
    is ColorStateListDrawable -> background.colorStateList.defaultColor
    is MaterialShapeDrawable -> background.fillColor!!.defaultColor
    else -> error("Unexpected background $background")
  }

  private fun assertScreenshot(activity: Activity, name: String, sample: Sample, surface: Int, sampleYs: IntArray, chromeUnderlayY: Int? = null, composeContent: Boolean = false) {
    val bitmap = captureDecodedMedia(activity, name, sample, composeContent)
    try {
      saveScreenshot(bitmap, name)
      val chrome = if (chromeUnderlayY != null) {
        // The legacy video SurfaceView can cover the letterbox with black. Sample the clear
        // letterbox below the toolbar and composite the exact 222-alpha chrome over it.
        val underlay = bitmap.getPixel(bitmap.width - 2, chromeUnderlayY)
        assertTrue("Letterbox must use the themed backdrop or the black video surface", underlay == surface || underlay == Color.BLACK)
        ColorUtils.compositeColors(ColorUtils.setAlphaComponent(surface, 222), underlay)
      } else {
        surface
      }
      // Landscape fixtures leave the toolbar and caption over the backdrop. Sample their clear
      // right edge to check the rendered chrome as well as the View/resource assertions above.
      for (y in sampleYs) {
        assertTrue("Chrome must lie within the captured screen", y in 0 until bitmap.height)
        val actual = bitmap.getPixel(bitmap.width - 2, y)
        assertEquals(Color.red(chrome).toDouble(), Color.red(actual).toDouble(), 2.0)
        assertEquals(Color.green(chrome).toDouble(), Color.green(actual).toDouble(), 2.0)
        assertEquals(Color.blue(chrome).toDouble(), Color.blue(actual).toDouble(), 2.0)
      }
    } finally {
      bitmap.recycle()
    }
  }

  private fun captureDecodedMedia(activity: Activity, name: String, sample: Sample, composeContent: Boolean): Bitmap {
    val deadline = SystemClock.uptimeMillis() + 5_000
    while (true) {
      // A decoded bitmap can be ready before its next draw. Keep driving Compose while waiting
      // for the fixture itself to appear; a load-state or caption alone cannot prove rendering.
      if (composeContent) compose.waitForIdle()
      instrumentation.waitForIdleSync()
      var points = emptyList<PatternPoint>()
      var sampledVideo: VideoPlayer? = null
      instrumentation.runOnMainSync {
        if (sample == Sample.VIDEO) sampledVideo = findVideoPlayer(activity)
        points = fixturePoints(activity, sample)
      }
      val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
      val pixels = points.map { point ->
        if (point.x in 0 until bitmap.width && point.y in 0 until bitmap.height) bitmap.getPixel(point.x, point.y) else Color.TRANSPARENT
      }
      // Both fixtures have red, green, and blue vertical bands. Check two interior rows so a
      // blank frame, placeholder, or covering dialog cannot pass. Channel dominance is stable
      // across SDR video color conversion; exact theme/chrome RGB assertions remain separate.
      val fixtureVisible = points.size == 6 && points.indices.all { index ->
        val channels = intArrayOf(Color.red(pixels[index]), Color.green(pixels[index]), Color.blue(pixels[index]))
        channels[points[index].dominantChannel] >= 192 && channels.indices.all { channel ->
          channel == points[index].dominantChannel || channels[channel] <= 64
        }
      }
      if (fixtureVisible) {
        var pausedCurrentVideo = sample != Sample.VIDEO
        if (sample == Sample.VIDEO) {
          instrumentation.runOnMainSync {
            val video = findVideoPlayer(activity)
            if (video != null && video === sampledVideo) {
              if (pausedVideo !== video) {
                // Freeze only after this surface has displayed the fixture. Every frame is identical.
                video.pause()
                pausedVideo = video
              } else {
                pausedCurrentVideo = true
              }
            }
          }
        }
        // Re-capture after pausing so the saved screenshot still proves the frame is visible.
        if (pausedCurrentVideo) return bitmap
      }
      if (SystemClock.uptimeMillis() >= deadline) {
        try {
          saveScreenshot(bitmap, "$name-frame-failure")
        } finally {
          bitmap.recycle()
        }
        error("The ${sample.name.lowercase()} fixture's RGB bands were not visible: ${points.zip(pixels).joinToString { (point, color) -> "$point=${Integer.toHexString(color)}" }}")
      }
      bitmap.recycle()
      SystemClock.sleep(50)
    }
  }

  private fun fixturePoints(activity: Activity, sample: Sample): List<PatternPoint> {
    val location = IntArray(2)
    val points = mutableListOf<PatternPoint>()
    if (sample == Sample.IMAGE) {
      val image = activity.findViewById<SubsamplingScaleImageView>(UiComponentsR.id.subsampling_image_view) ?: return emptyList()
      if (!image.isShown || !image.isImageLoaded) return emptyList()
      image.getLocationOnScreen(location)
      for (row in listOf(0.25f, 0.75f)) {
        for (channel in 0..2) {
          val point = image.sourceToViewCoord(sample.width * (channel + 0.5f) / 3f, sample.height * row) ?: return emptyList()
          points += PatternPoint(location[0] + point.x.roundToInt(), location[1] + point.y.roundToInt(), channel)
        }
      }
    } else {
      val frame = findVideoPlayer(activity)?.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame) ?: return emptyList()
      if (!frame.isShown || frame.width == 0 || frame.height == 0) return emptyList()
      frame.getLocationOnScreen(location)
      for (row in listOf(0.25f, 0.75f)) {
        for (channel in 0..2) {
          points += PatternPoint(location[0] + (frame.width * (channel + 0.5f) / 3f).roundToInt(), location[1] + (frame.height * row).roundToInt(), channel)
        }
      }
    }
    return points
  }

  private fun findVideoPlayer(activity: Activity): VideoPlayer? =
    (activity.window.decorView as ViewGroup).descendants.filterIsInstance<VideoPlayer>().firstOrNull { it.isShown }

  private data class PatternPoint(val x: Int, val y: Int, val dominantChannel: Int)

  private fun saveScreenshot(bitmap: Bitmap, name: String) {
    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
      ?.let(::File) ?: File(context.getExternalFilesDir(null), "theme-regression")
    assertTrue(output.isDirectory || output.mkdirs())
    File(output, "$name.png").outputStream().use { stream ->
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
    }
  }

  private enum class Sample(val asset: String, val mimeType: String, val width: Int, val height: Int) {
    IMAGE("images/theme_sample.png", "image/png", 160, 90),
    // Matching RGB bands, one-second H.264 baseline with explicit SDR BT.709 color metadata.
    VIDEO("videos/theme_sample.mp4", "video/mp4", 160, 90)
  }

  companion object {
    private const val CAPTION = "Theme regression media"
  }
}

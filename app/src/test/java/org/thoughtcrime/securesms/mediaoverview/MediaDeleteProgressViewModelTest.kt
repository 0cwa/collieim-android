package org.thoughtcrime.securesms.mediaoverview

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.signal.core.models.database.AttachmentId
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.Cdn
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.MediaTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.jobs.MultiDeviceDeleteSyncJob
import org.thoughtcrime.securesms.testing.CoroutineDispatcherRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.thoughtcrime.securesms.util.AttachmentUtil

@OptIn(ExperimentalCoroutinesApi::class)
class MediaDeleteProgressViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val attachments = mockk<AttachmentTable>()

  @get:Rule
  val dispatcherRule = CoroutineDispatcherRule(dispatcher)

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    Dispatchers.setMain(dispatcher)
    mockkObject(SignalDatabase.Companion)
    every { SignalDatabase.instance } returns mockk {
      every { attachmentTable } returns attachments
    }
    mockkStatic(AttachmentUtil::class)
    mockkObject(MultiDeviceDeleteSyncJob.Companion)
    every { MultiDeviceDeleteSyncJob.enqueueMessageDeletes(any()) } just Runs
  }

  @After
  fun tearDown() {
    unmockkAll()
    Dispatchers.resetMain()
  }

  @Test
  fun `bulk removal reports successes skipped items and failures without syncing deletion`() = runTest(dispatcher) {
    every { attachments.removeAttachmentLocally(AttachmentId(1)) } returns true
    every { attachments.removeAttachmentLocally(AttachmentId(2)) } returns false
    every { attachments.removeAttachmentLocally(AttachmentId(3)) } throws IllegalStateException("unavailable storage")
    every { attachments.removeAttachmentLocally(AttachmentId(4)) } returns true
    val viewModel = MediaDeleteProgressViewModel()

    viewModel.start((1L..4L).map(::record), MediaDeleteProgressViewModel.Operation.REMOVE_ATTACHMENTS)
    advanceUntilIdle()

    assertThat(viewModel.state.value).isEqualTo(
      MediaDeleteProgressViewModel.State(
        processed = 4, total = 4, hasStarted = true, isDone = true,
        operation = MediaDeleteProgressViewModel.Operation.REMOVE_ATTACHMENTS,
        removed = 2, skipped = 1, failed = 1
      )
    )
    verify(exactly = 0) { AttachmentUtil.deleteAttachments(any(), any()) }
    verify(exactly = 0) { MultiDeviceDeleteSyncJob.enqueueMessageDeletes(any()) }
    verify(exactly = 0) { MultiDeviceDeleteSyncJob.enqueueAttachmentDelete(any(), any()) }
  }

  @Test
  fun `confirmation snapshot is stable and duplicate attachment ids are removed once`() = runTest(dispatcher) {
    every { attachments.removeAttachmentLocally(AttachmentId(1)) } returns true
    val selected = mutableListOf(record(1), record(1))
    val viewModel = MediaDeleteProgressViewModel()

    assertThat(viewModel.start(selected, MediaDeleteProgressViewModel.Operation.REMOVE_ATTACHMENTS)).isTrue()
    selected.clear()
    selected += record(2)
    assertThat(viewModel.start(selected, MediaDeleteProgressViewModel.Operation.REMOVE_ATTACHMENTS)).isFalse()
    advanceUntilIdle()

    verify(exactly = 1) { attachments.removeAttachmentLocally(AttachmentId(1)) }
    verify(exactly = 0) { attachments.removeAttachmentLocally(AttachmentId(2)) }
    assertThat(viewModel.state.value.total).isEqualTo(1)
    assertThat(viewModel.state.value.removed).isEqualTo(1)
  }

  @Test
  fun `ordinary delete retains its message deletion and sync behavior`() = runTest(dispatcher) {
    val deleted = mockk<MessageRecord>()
    every { AttachmentUtil.deleteAttachments(any(), any()) } answers {
      secondArg<(Int) -> Unit>().invoke(1)
      setOf(deleted)
    }
    val viewModel = MediaDeleteProgressViewModel()

    viewModel.start(listOf(record(1)))
    advanceUntilIdle()

    verify(exactly = 1) { MultiDeviceDeleteSyncJob.enqueueMessageDeletes(setOf(deleted)) }
    verify(exactly = 0) { attachments.removeAttachmentLocally(any()) }
    assertThat(viewModel.state.value.isDone).isTrue()
    assertThat(viewModel.state.value.failed).isEqualTo(0)
  }

  private fun record(id: Long): MediaTable.MediaRecord = mockk {
    every { attachment } returns DatabaseAttachment(
      attachmentId = AttachmentId(id),
      mmsId = 1,
      hasData = true,
      hasThumbnail = false,
      contentType = "image/jpeg",
      transferProgress = AttachmentTable.TRANSFER_PROGRESS_DONE,
      size = 100,
      fileName = null,
      cdn = Cdn.CDN_0,
      location = null,
      key = null,
      digest = null,
      incrementalDigest = null,
      incrementalMacChunkSize = 0,
      fastPreflightId = null,
      voiceNote = false,
      borderless = false,
      videoGif = false,
      width = 100,
      height = 100,
      quote = false,
      caption = null,
      stickerLocator = null,
      blurHash = null,
      audioHash = null,
      transformProperties = null,
      displayOrder = 0,
      uploadTimestamp = 0,
      dataHash = null,
      archiveCdn = null,
      thumbnailRestoreState = AttachmentTable.ThumbnailRestoreState.NONE,
      archiveTransferState = AttachmentTable.ArchiveTransferState.NONE,
      archiveThumbnailTransferState = AttachmentTable.ArchiveTransferState.NONE,
      uuid = null,
      quoteTargetContentType = null,
      metadata = null
    )
  }
}

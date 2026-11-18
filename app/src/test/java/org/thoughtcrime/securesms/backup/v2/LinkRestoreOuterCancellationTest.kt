package org.thoughtcrime.securesms.backup.v2

import android.app.Application
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.backup.MessageBackupKey
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.backup.RestoreState
import org.thoughtcrime.securesms.jobmanager.impl.DataRestoreConstraint
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.service.BackupProgressService
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.whispersystems.signalservice.api.link.TransferArchiveResponse
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class LinkRestoreOuterCancellationTest {
  @get:Rule val store = MockSignalStoreRule()
  @get:Rule val dependencies = MockAppDependenciesRule()
  private val controller = mockk<BackupProgressService.Controller>(relaxed = true)
  private val response = TransferArchiveResponse(cdn = 1, key = "synthetic")
  private val backupKey = mockk<MessageBackupKey>()

  @Before fun setup() {
    Log.initialize(SystemOutLogger())
    every { SignalStore.backup.restoreState } returns RestoreState.NONE
    every { SignalStore.backup.totalRestorableAttachmentSize } returns 0L
    mockkObject(ArchiveRestoreProgress)
    every { ArchiveRestoreProgress.onRestorePending() } just Runs
    every { ArchiveRestoreProgress.onRestoreCanceled() } just Runs
    mockkObject(BackupProgressService.Companion)
    every { BackupProgressService.start(any(), any()) } returns controller
    mockkObject(BackupRepository, recordPrivateCalls = true)
  }

  @After fun cleanup() {
    unmockkObject(BackupRepository)
    unmockkObject(BackupProgressService.Companion)
    unmockkObject(ArchiveRestoreProgress)
  }

  @Test fun `outer link API propagates simulated committed cancellation and releases progress service`() {
    val committed = ImportCommittedAfterCancellationException(CancellationException("synthetic cancellation"))
    every { BackupRepository["restoreLinkAndSyncBackup"](response, backupKey, controller, any<() -> Boolean>()) } throws committed

    assertThrows(ImportCommittedAfterCancellationException::class.java) {
      runBlocking { BackupRepository.restoreLinkAndSyncBackup(response, backupKey) }
    }
    verify(exactly = 1) { controller.close() }
    verify(exactly = 1) { ArchiveRestoreProgress.onRestoreCanceled() }
    assertFalse(DataRestoreConstraint.isRestoringData)
  }
}

package org.thoughtcrime.securesms.registration.v2

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.testutil.EmptyLogger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class LocalBackupFolderScanTest {
  companion object {
    @BeforeClass
    @JvmStatic
    fun initLogger() = Log.initialize(EmptyLogger())
  }

  @After
  fun tearDown() = unmockkAll()

  @Test
  fun `selected folder lists timestamped backup candidates regardless of prefix in date order`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val rootUri = Uri.parse("content://test/backups")
    val folder = mockk<DocumentFile>()
    val names = listOf(
      "signal-2026-01-01-12-00-00.backup",
      "collie-2026-02-01-12-00-00.backup",
      "molly-foss-continued-2026-03-01-12-00-00.backup",
      "other-app-2026-04-01-12-00-00.backup",
      "collie-2026-02-30-12-00-00.backup",
      "collie-2026-01-01-12-00-00.txt",
      "unrelated.backup",
      "-2026-01-01-12-00-00.backup"
    )
    val files = names.map { name ->
      val file = mockk<DocumentFile>()
      every { file.isDirectory } returns false
      every { file.isFile } returns true
      every { file.name } returns name
      every { file.uri } returns Uri.parse("content://test/backups/$name")
      every { file.length() } returns 123L
      file
    }.toTypedArray()
    mockkStatic(DocumentFile::class)
    every { DocumentFile.fromTreeUri(context, rootUri) } returns folder
    every { folder.listFiles() } returns files

    assertThat(AppRegistrationStorageController(context).scanLocalBackupFolder(rootUri).map { it.name }).isEqualTo(
      listOf(
        "other-app-2026-04-01-12-00-00.backup",
        "molly-foss-continued-2026-03-01-12-00-00.backup",
        "collie-2026-02-01-12-00-00.backup",
        "signal-2026-01-01-12-00-00.backup"
      )
    )
  }
}

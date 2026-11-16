package org.thoughtcrime.securesms.devicetransfer.newdevice

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.AccountEntropyPool
import org.signal.core.util.crypto.AttachmentSecret
import org.signal.core.util.crypto.AttachmentSecretProvider
import org.signal.core.util.eventbus.EventBus
import org.signal.core.util.logging.Log
import org.signal.devicetransfer.NewDeviceRestoreStatus
import org.thoughtcrime.securesms.AppInitialization
import org.thoughtcrime.securesms.backup.FullBackupImporter
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class NewDeviceServerTaskTest {
  @get:Rule val signalStore = MockSignalStoreRule()
  private val context: Application = ApplicationProvider.getApplicationContext()
  private val database = mockk<SQLiteDatabase>()
  private val secret = mockk<AttachmentSecret>()
  private val provider = mockk<AttachmentSecretProvider>()
  private val channels = mockk<NotificationChannels>()

  @Before fun setup() {
    Log.initialize(SystemOutLogger())
    mockkStatic(SignalDatabase::class, AttachmentSecretProvider::class, FullBackupImporter::class, NotificationChannels::class, AppInitialization::class)
    mockkObject(SignalDatabase.Companion)
    every { SignalDatabase.backupDatabase } returns database
    every { SignalStore.account.accountEntropyPool } returns AccountEntropyPool("a".repeat(64))
    every { SignalStore.backup.v1BackupPassphrase = any() } answers { }
    every { AttachmentSecretProvider.getInstance(any(), any()) } returns provider
    every { provider.orCreateAttachmentSecret } returns secret
    justRun { SignalDatabase.runPostBackupRestoreTasks(database) }
    every { NotificationChannels.getInstance() } returns channels
    justRun { channels.restoreContactNotificationChannels() }
    justRun { AppInitialization.onPostBackupRestore(any()) }
  }

  @After fun cleanup() {
    unmockkObject(SignalDatabase.Companion)
    unmockkStatic(SignalDatabase::class, AttachmentSecretProvider::class, FullBackupImporter::class, NotificationChannels::class, AppInitialization::class)
  }

  @Test fun `failed import reports failure without a completion event`() {
    every { FullBackupImporter.importFile(any(), any(), any(), any<InputStream>(), any(), any()) } throws IOException("synthetic")
    val states = mutableListOf<NewDeviceRestoreStatus.State>()
    val subscription = EventBus.default.subscribe<NewDeviceRestoreStatus> { states += it.state }
    try { NewDeviceServerTask().run(context, ByteArrayInputStream(byteArrayOf())) } finally { subscription.unsubscribe() }
    assertEquals(listOf(NewDeviceRestoreStatus.State.FAILURE_UNKNOWN), states)
  }

  @Test fun `foreign key violation reports failure without a completion event`() {
    every { FullBackupImporter.importFile(any(), any(), any(), any<InputStream>(), any(), any()) } throws FullBackupImporter.ForeignKeyViolationException(emptyList())
    val states = mutableListOf<NewDeviceRestoreStatus.State>()
    val subscription = EventBus.default.subscribe<NewDeviceRestoreStatus> { states += it.state }
    try { NewDeviceServerTask().run(context, ByteArrayInputStream(byteArrayOf())) } finally { subscription.unsubscribe() }
    assertEquals(listOf(NewDeviceRestoreStatus.State.FAILURE_FOREIGN_KEY), states)
  }

  @Test fun `version downgrade reports failure without a completion event`() {
    val constructor = FullBackupImporter.DatabaseDowngradeException::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    constructor.isAccessible = true
    val error = constructor.newInstance(1, 2)
    every { FullBackupImporter.importFile(any(), any(), any(), any<InputStream>(), any(), any()) } throws error
    val states = mutableListOf<NewDeviceRestoreStatus.State>()
    val subscription = EventBus.default.subscribe<NewDeviceRestoreStatus> { states += it.state }
    try { NewDeviceServerTask().run(context, ByteArrayInputStream(byteArrayOf())) } finally { subscription.unsubscribe() }
    assertEquals(listOf(NewDeviceRestoreStatus.State.FAILURE_VERSION_DOWNGRADE), states)
  }

  @Test fun `post-restore initialization exception propagates without a completion event`() {
    justRun { FullBackupImporter.importFile(any(), any(), any(), any<InputStream>(), any(), any()) }
    every { AppInitialization.onPostBackupRestore(any()) } throws IllegalStateException("synthetic")
    val states = mutableListOf<NewDeviceRestoreStatus.State>()
    val subscription = EventBus.default.subscribe<NewDeviceRestoreStatus> { states += it.state }
    try {
      assertThrows(IllegalStateException::class.java) { NewDeviceServerTask().run(context, ByteArrayInputStream(byteArrayOf())) }
    } finally { subscription.unsubscribe() }
    assertEquals(emptyList<NewDeviceRestoreStatus.State>(), states)
  }

  @Test fun `successful import reports completion`() {
    justRun { FullBackupImporter.importFile(any(), any(), any(), any<InputStream>(), any(), any()) }
    val states = mutableListOf<NewDeviceRestoreStatus.State>()
    val subscription = EventBus.default.subscribe<NewDeviceRestoreStatus> { states += it.state }
    try { NewDeviceServerTask().run(context, ByteArrayInputStream(byteArrayOf())) } finally { subscription.unsubscribe() }
    assertEquals(listOf(NewDeviceRestoreStatus.State.RESTORE_COMPLETE), states)
  }
}

package org.thoughtcrime.securesms.backup.v2

import android.app.Application
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.archive.stream.BackupImportReader
import org.signal.core.util.logging.Log
import org.signal.core.models.ServiceId
import org.signal.core.models.recipients.RecipientId
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.RecipientTable
import org.thoughtcrime.securesms.database.model.RecipientRecord
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.testutil.DirectExecutor
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class BackupImportBoundaryTest {
  @get:Rule val signalStore = MockSignalStoreRule()
  @get:Rule val appDependencies = MockAppDependenciesRule()
  private lateinit var values: UnifiedPushValues

  @Before fun setup() {
    Log.initialize(SystemOutLogger())
    values = UnifiedPushValues(KeyValueStore(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet()), DirectExecutor()))
    every { SignalStore.unifiedpush } returns values
    every { SignalStore.backup.hasInvalidBackupVersion = any() } answers { }
    UnifiedPushLifecycle.resetForTests()
  }

  @After fun cleanup() = UnifiedPushLifecycle.resetForTests()

  private fun invokeActualBoundary(mode: BackupMode, reader: BackupImportReader, matchCurrentAccount: Boolean = false): ImportResult {
    val method = BackupRepository::class.java.getDeclaredMethod(
      "import", BackupImportReader::class.java, BackupRepository.SelfData::class.java, BackupMode::class.java, BackupRepository.ImportFinalization::class.java, Function0::class.java
    )
    method.isAccessible = true
    val finalization = if (mode == BackupMode.LOCAL) BackupRepository.ImportFinalization.STAGED_REGISTRATION else BackupRepository.ImportFinalization.CONTINUE_SAME_ACCOUNT
    val self = mockk<BackupRepository.SelfData>()
    val aci = mockk<ServiceId.ACI>()
    every { self.aci } returns aci
    if (matchCurrentAccount) every { SignalStore.account.aci } returns aci
    every { aci.toString() } returns "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    return method.invoke(BackupRepository, reader, self, mode, finalization, { false }) as ImportResult
  }

  @Test fun `local invalid archive keeps the existing staging marker`() {
    val reader = mockk<BackupImportReader>()
    every { reader.getHeader() } returns null
    justRun { reader.close() }
    assertSame(ImportResult.Failure, invokeActualBoundary(BackupMode.LOCAL, reader))
    verify(exactly = 1) { reader.getHeader() }
    assertTrue(values.hasIncompleteAccountTransition)
    assertFalse(UnifiedPushLifecycle.isReady())
  }

  private fun coherentAccount(password: () -> String = { "synthetic-service-password" }) {
    val aci = mockk<ServiceId.ACI>()
    val identity = mockk<IdentityKeyPair>()
    val publicKey = mockk<IdentityKey>()
    every { aci.toString() } returns "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    every { SignalStore.account.isRegistered } returns true
    every { SignalStore.account.hasAciIdentityKey() } returns true
    every { SignalStore.account.aci } returns aci
    every { SignalStore.account.pni } returns null
    every { SignalStore.account.e164 } returns null
    every { SignalStore.account.deviceId } returns 1
    every { SignalStore.account.servicePassword } answers { password() }
    every { SignalStore.account.aciIdentityKey } returns identity
    every { identity.publicKey } returns publicKey
    every { publicKey.serialize() } returns byteArrayOf(1, 2, 3)
  }

  @Test fun `successful same-account operation reopens its own new marker`() {
    coherentAccount()
    val result = BackupRepository.runV2ImportBoundary(BackupRepository.ImportFinalization.CONTINUE_SAME_ACCOUNT, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") {
      assertTrue(values.hasIncompleteAccountTransition)
      assertFalse(UnifiedPushLifecycle.isReady())
      ImportResult.Success(1, RecipientId.from(9))
    }
    assertTrue(result is ImportResult.Success)
    assertFalse(values.hasIncompleteAccountTransition)
    assertTrue(UnifiedPushLifecycle.isReady())
  }

  @Test fun `link restore retains both markers on failure`() {
    coherentAccount()
    assertThrows(ImportRecoveryRequiredException::class.java) {
      BackupRepository.runV2ImportBoundary(BackupRepository.ImportFinalization.CONTINUE_SAME_ACCOUNT, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", linkRestore = true) {
        assertTrue(values.hasIncompleteAccountTransition)
        assertTrue(values.hasIncompleteLinkRestore)
        ImportResult.Failure
      }
    }
    assertTrue(values.hasIncompleteAccountTransition)
    assertTrue(values.hasIncompleteLinkRestore)
  }

  @Test fun `link restore clears its own markers after success`() {
    coherentAccount()
    val result = BackupRepository.runV2ImportBoundary(BackupRepository.ImportFinalization.CONTINUE_SAME_ACCOUNT, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", linkRestore = true) {
      assertTrue(values.hasIncompleteLinkRestore)
      ImportResult.Success(1, RecipientId.from(9))
    }
    assertTrue(result is ImportResult.Success)
    assertFalse(values.hasIncompleteAccountTransition)
    assertFalse(values.hasIncompleteLinkRestore)
  }

  @Test fun `link restore never takes over an inherited marker`() {
    coherentAccount()
    values.setLinkRestoreTransition(true)
    var entered = false
    assertThrows(ImportRecoveryRequiredException::class.java) {
      BackupRepository.runV2ImportBoundary(BackupRepository.ImportFinalization.CONTINUE_SAME_ACCOUNT, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", linkRestore = true) {
        entered = true
        ImportResult.Success(1, RecipientId.from(9))
      }
    }
    assertFalse(entered)
    assertTrue(values.hasIncompleteAccountTransition)
    assertTrue(values.hasIncompleteLinkRestore)
  }

  @Test fun `registration transition cannot clear another restore marker`() {
    coherentAccount()
    values.setLinkRestoreTransition(true)
    assertThrows(IllegalStateException::class.java) {
      kotlinx.coroutines.runBlocking {
        im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition.withTransition(completesAccountRegistration = true) { Unit }
      }
    }
    assertTrue(values.hasIncompleteAccountTransition)
    assertTrue(values.hasIncompleteLinkRestore)
    assertFalse(UnifiedPushLifecycle.isReady())
  }

  @Test fun `success outcome remains committed while successor owns its marker`() {
    coherentAccount()
    val result = im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition.withTransitionBlockingResult(linkRestore = true, readyAfter = { true }) { "imported" }
    val started = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    val inspected = java.util.concurrent.CountDownLatch(1)
    val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
    val required = java.util.concurrent.atomic.AtomicReference<Boolean>()
    val writer = kotlin.concurrent.thread {
      try {
        im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition.withTransitionBlocking(readyAfter = { true }) {
          started.countDown()
          check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
      } catch (failure: Throwable) { failures.add(failure) }
    }
    try {
      assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
      assertTrue(values.hasIncompleteAccountTransition)
      assertFalse(result.retainedMarker)
      val reader = kotlin.concurrent.thread {
        try {
          required.set(kotlinx.coroutines.runBlocking { im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition.isLinkRestoreRecoveryRequired() })
        } catch (failure: Throwable) { failures.add(failure) } finally { inspected.countDown() }
      }
      try {
        assertFalse(inspected.await(150, java.util.concurrent.TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(inspected.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse(required.get())
      } finally { release.countDown(); reader.join(5000) }
    } finally { release.countDown(); writer.join(5000) }
    assertTrue(failures.isEmpty())
  }

  @Test fun `different imported database self keeps admission closed`() {
    coherentAccount()
    val expected = mockk<ServiceId.ACI>()
    val other = mockk<ServiceId.ACI>()
    val recipients = mockk<RecipientTable>()
    val record = mockk<RecipientRecord>()
    val id = RecipientId.from(9)
    every { record.aci } returns other
    every { recipients.getRecord(id) } returns record
    mockkObject(SignalDatabase.Companion)
    try {
      every { SignalDatabase.recipients } returns recipients
      assertThrows(ImportRecoveryRequiredException::class.java) {
        BackupRepository.runV2ImportBoundary(BackupRepository.ImportFinalization.CONTINUE_SAME_ACCOUNT, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") {
          BackupRepository.verifyImportedSelf(ImportResult.Success(1, id), expected)
        }
      }
    } finally { unmockkObject(SignalDatabase.Companion) }
    assertTrue(values.hasIncompleteAccountTransition)
    assertFalse(UnifiedPushLifecycle.isReady())
  }

  @Test fun `different archive account cannot enter the destructive operation`() {
    coherentAccount()
    var entered = false
    assertThrows(ImportRecoveryRequiredException::class.java) {
      BackupRepository.runV2ImportBoundary(BackupRepository.ImportFinalization.CONTINUE_SAME_ACCOUNT, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb") {
        entered = true
        ImportResult.Success(1, RecipientId.from(9))
      }
    }
    assertFalse(entered)
    assertTrue(values.hasIncompleteAccountTransition)
    assertFalse(UnifiedPushLifecycle.isReady())
  }

  @Test fun `missing initial account cannot enter the same-account destructive operation`() {
    every { SignalStore.account.isRegistered } returns false
    var entered = false
    assertThrows(ImportRecoveryRequiredException::class.java) {
      BackupRepository.runV2ImportBoundary(BackupRepository.ImportFinalization.CONTINUE_SAME_ACCOUNT, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") {
        entered = true
        ImportResult.Success(1, RecipientId.from(9))
      }
    }
    assertFalse(entered)
    assertTrue(values.hasIncompleteAccountTransition)
    assertFalse(UnifiedPushLifecycle.isReady())
  }

  @Test fun `destructive operation waits for previously admitted push work`() {
    coherentAccount()
    val lease = checkNotNull(UnifiedPushLifecycle.acquireLease { "account-a" })
    val entered = java.util.concurrent.CountDownLatch(1)
    val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    val worker = kotlin.concurrent.thread {
      try {
        BackupRepository.runV2ImportBoundary(BackupRepository.ImportFinalization.CONTINUE_SAME_ACCOUNT, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") {
          entered.countDown()
          ImportResult.Success(1, RecipientId.from(9))
        }
      } catch (t: Throwable) { failure.set(t) }
    }
    try {
      val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
      while (!values.hasIncompleteAccountTransition && worker.isAlive && System.nanoTime() < deadline) Thread.yield()
      assertTrue(values.hasIncompleteAccountTransition)
      assertFalse(entered.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
    } finally { lease.close() }
    worker.join(5000)
    assertFalse(worker.isAlive)
    assertTrue(failure.get() == null)
    assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
    assertFalse(values.hasIncompleteAccountTransition)
    assertTrue(UnifiedPushLifecycle.isReady())
  }

  @Test fun `value failure and exception retain the marker`() {
    coherentAccount()
    assertThrows(ImportRecoveryRequiredException::class.java) {
      BackupRepository.runV2ImportBoundary(BackupRepository.ImportFinalization.CONTINUE_SAME_ACCOUNT, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") { ImportResult.Failure }
    }
    assertTrue(values.hasIncompleteAccountTransition)
    assertFalse(UnifiedPushLifecycle.isReady())
    assertThrows(ImportRecoveryRequiredException::class.java) {
      BackupRepository.runV2ImportBoundary(BackupRepository.ImportFinalization.CONTINUE_SAME_ACCOUNT, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") { throw IllegalStateException("synthetic") }
    }
    assertTrue(values.hasIncompleteAccountTransition)
    assertFalse(UnifiedPushLifecycle.isReady())
  }

  @Test fun `changed credential or inherited marker cannot reopen the boundary`() {
    var password = "synthetic-service-password"
    coherentAccount { password }
    assertThrows(ImportRecoveryRequiredException::class.java) {
      BackupRepository.runV2ImportBoundary(BackupRepository.ImportFinalization.CONTINUE_SAME_ACCOUNT, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") {
        password = "synthetic-replacement-password"
        ImportResult.Success(1, RecipientId.from(9))
      }
    }
    assertTrue(values.hasIncompleteAccountTransition)
    assertFalse(UnifiedPushLifecycle.isReady())
    assertThrows(ImportRecoveryRequiredException::class.java) {
      BackupRepository.runV2ImportBoundary(BackupRepository.ImportFinalization.CONTINUE_SAME_ACCOUNT, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") {
        ImportResult.Success(1, RecipientId.from(9))
      }
    }
    assertTrue(values.hasIncompleteAccountTransition)
    assertFalse(UnifiedPushLifecycle.isReady())
  }

  @Test fun `reader close failure after successful import keeps the transition closed`() {
    coherentAccount()
    val reader = mockk<BackupImportReader>()
    every { reader.close() } throws java.io.IOException("synthetic close failure")
    assertThrows(ImportRecoveryRequiredException::class.java) {
      BackupRepository.runV2ImportBoundary(BackupRepository.ImportFinalization.CONTINUE_SAME_ACCOUNT, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") {
        BackupRepository.runV2OwnedReader(reader) { ImportResult.Success(1, RecipientId.from(9)) }
      }
    }
    verify(exactly = 1) { reader.close() }
    assertTrue(values.hasIncompleteAccountTransition)
    assertFalse(UnifiedPushLifecycle.isReady())
  }

  @Test fun `interruption before import completion is not reported as committed`() {
    coherentAccount()
    val entered = java.util.concurrent.CountDownLatch(1)
    val waiting = java.util.concurrent.CountDownLatch(1)
    val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    val worker = kotlin.concurrent.thread {
      try {
        BackupRepository.runV2ImportBoundary(BackupRepository.ImportFinalization.CONTINUE_SAME_ACCOUNT, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") {
          entered.countDown()
          waiting.await()
          ImportResult.Success(1, RecipientId.from(9))
        }
      } catch (t: Throwable) { failure.set(t) }
    }
    try {
      assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
      worker.interrupt()
      worker.join(5000)
      assertFalse(worker.isAlive)
      assertTrue(failure.get() is InterruptedException || failure.get() is java.util.concurrent.CancellationException)
      assertFalse(failure.get() is ImportCommittedAfterCancellationException)
      assertTrue(values.hasIncompleteAccountTransition)
      assertFalse(UnifiedPushLifecycle.isReady())
    } finally { waiting.countDown(); worker.join(5000) }
  }

  @Test fun `caller interruption after commit reports a distinct committed cancellation`() {
    coherentAccount()
    val fakeValues = mockk<UnifiedPushValues>()
    val pending = java.util.concurrent.atomic.AtomicBoolean(false)
    val clearing = java.util.concurrent.CountDownLatch(1)
    val releaseClear = java.util.concurrent.CountDownLatch(1)
    val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    val interruptPreserved = java.util.concurrent.atomic.AtomicBoolean(false)
    every { SignalStore.unifiedpush } returns fakeValues
    every { fakeValues.hasIncompleteAccountTransition } answers { pending.get() }
    every { fakeValues.hasIncompleteLinkRestore } returns false
    every { fakeValues.setIncompleteAccountTransition(any()) } answers {
      val value = firstArg<Boolean>()
      if (!value) {
        clearing.countDown()
        check(releaseClear.await(5, java.util.concurrent.TimeUnit.SECONDS))
      }
      pending.set(value)
    }
    val worker = kotlin.concurrent.thread {
      try {
        BackupRepository.runV2ImportBoundary(BackupRepository.ImportFinalization.CONTINUE_SAME_ACCOUNT, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") {
          ImportResult.Success(1, RecipientId.from(9))
        }
      } catch (t: Throwable) { failure.set(t); interruptPreserved.set(Thread.currentThread().isInterrupted) }
    }
    try {
      assertTrue(clearing.await(5, java.util.concurrent.TimeUnit.SECONDS))
      worker.interrupt()
    } finally { releaseClear.countDown() }
    worker.join(5000)
    assertFalse(worker.isAlive)
    assertTrue(failure.get() is ImportCommittedAfterCancellationException)
    assertTrue(interruptPreserved.get())
    assertFalse(pending.get())
    assertTrue(UnifiedPushLifecycle.isReady())
  }

  @Test fun `staged successful registration never reopens admission`() {
    BackupRepository.runV2ImportBoundary(BackupRepository.ImportFinalization.STAGED_REGISTRATION, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") {
      ImportResult.Success(1, RecipientId.from(9))
    }
    assertTrue(values.hasIncompleteAccountTransition)
    assertFalse(UnifiedPushLifecycle.isReady())
  }

  @Test fun `invalid nonlocal headers do not begin a destructive transition`() {
    for (mode in listOf(BackupMode.REMOTE, BackupMode.LINK_SYNC, BackupMode.PLAINTEXT_EXPORT)) {
      val reader = mockk<BackupImportReader>()
      every { reader.getHeader() } returns null
      justRun { reader.close() }
      assertSame(ImportResult.Failure, invokeActualBoundary(mode, reader, matchCurrentAccount = true))
      verify(exactly = 1) { reader.getHeader() }
      assertFalse(values.hasIncompleteAccountTransition)
      assertTrue(UnifiedPushLifecycle.isReady())
    }
  }
}

/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import arrow.core.left
import arrow.core.right
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.signal.core.util.logging.Log
import org.signal.libsignal.zkgroup.VerificationFailedException
import org.signal.network.service.ArchiveError
import org.signal.remoteconfig.RemoteConfig
import org.thoughtcrime.securesms.backup.v2.BackupRepository
import org.thoughtcrime.securesms.backup.v2.MessageBackupTier
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.io.IOException

class BackupTierDowngradeCheckJobTest {

  companion object {
    private val FREE_TIER = MessageBackupTier.FREE.toBackupLevel()
    private const val RETRY_BACKOFF_CAP_MS = 1_000L
  }

  @get:Rule
  val signalStore = MockSignalStoreRule()

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())

    every { signalStore.account.isRegistered } returns true
    every { signalStore.account.isPrimaryDevice } returns false
    every { signalStore.backup.backupTier } returns MessageBackupTier.PAID

    mockkObject(BackupRepository)
    mockkObject(RemoteConfig)
    // Job.defaultBackoff() calls the Java @JvmStatic getter, not the object mock.
    mockkStatic(RemoteConfig::class)
    every { RemoteConfig.defaultMaxBackoff } returns RETRY_BACKOFF_CAP_MS
  }

  @After
  fun tearDown() {
    unmockkObject(BackupRepository)
    unmockkStatic(RemoteConfig::class)
    unmockkObject(RemoteConfig)
  }

  @Test
  fun `given the service says paid, when I run, then I keep the paid tier`() {
    every { BackupRepository.getBackupTierWithoutDowngrade() } returns MessageBackupTier.PAID.right()

    val result = BackupTierDowngradeCheckJob.create(FREE_TIER).run()

    assertTrue(result.isSuccess)
    verify { signalStore.backup.backupTier = MessageBackupTier.PAID }
  }

  @Test
  fun `given the service says free, when I run, then I downgrade to free`() {
    every { BackupRepository.getBackupTierWithoutDowngrade() } returns MessageBackupTier.FREE.right()

    val result = BackupTierDowngradeCheckJob.create(FREE_TIER).run()

    assertTrue(result.isSuccess)
    verify { signalStore.backup.backupTier = MessageBackupTier.FREE }
  }

  @Test
  fun `given a network error, when I run, then I retry and leave our tier alone`() {
    every { BackupRepository.getBackupTierWithoutDowngrade() } returns ArchiveError.NetworkError(IOException()).left()

    val result = BackupTierDowngradeCheckJob.create(FREE_TIER).run()

    assertTrue(result.isRetry)
    // The package-private accessor exposes the actual retry interval without changing the app API.
    val backoff = Job.Result::class.java.getDeclaredMethod("getBackoffInterval")
      .apply { isAccessible = true }
      .invoke(result) as Long
    assertTrue("Retry backoff must honor the configured cap with jitter", backoff in (RETRY_BACKOFF_CAP_MS * 3 / 4) until (RETRY_BACKOFF_CAP_MS * 5 / 4))
    verify(exactly = 1) { RemoteConfig.defaultMaxBackoff }
    verify(exactly = 0) { signalStore.backup.backupTier = any() }
  }

  @Test
  fun `given the service rejects our credential, when I run, then I take the tier from the record`() {
    every { BackupRepository.getBackupTierWithoutDowngrade() } returns ArchiveError.CredentialError.Unauthorized().left()

    val result = BackupTierDowngradeCheckJob.create(FREE_TIER).run()

    assertTrue(result.isSuccess)
    verify { signalStore.backup.backupTier = MessageBackupTier.FREE }
  }

  @Test
  fun `given the service has no backup for us and the record had no tier, when I run, then I clear our tier`() {
    every { BackupRepository.getBackupTierWithoutDowngrade() } returns ArchiveError.CredentialError.NotFound().left()

    val result = BackupTierDowngradeCheckJob.create(null).run()

    assertTrue(result.isSuccess)
    verify { signalStore.backup.backupTier = null }
  }

  @Test
  fun `given we cannot verify our own credential, when I run, then I take the tier from the record`() {
    every { BackupRepository.getBackupTierWithoutDowngrade() } returns ArchiveError.CredentialError.ZkVerificationFailed(VerificationFailedException()).left()

    val result = BackupTierDowngradeCheckJob.create(null).run()

    assertTrue(result.isSuccess)
    verify { signalStore.backup.backupTier = null }
  }

  @Test
  fun `given a job carrying a tier, when I serialize and restore it, then I keep that tier`() {
    every { BackupRepository.getBackupTierWithoutDowngrade() } returns ArchiveError.CredentialError.NotFound().left()

    val job = BackupTierDowngradeCheckJob.create(FREE_TIER)
    val restored = BackupTierDowngradeCheckJob.Factory().create(job.parameters, job.serialize())

    val result = restored.run()

    assertTrue(result.isSuccess)
    verify { signalStore.backup.backupTier = MessageBackupTier.FREE }
  }

  @Test
  fun `given we are not registered, when I run, then I leave our tier alone`() {
    every { signalStore.account.isRegistered } returns false

    val result = BackupTierDowngradeCheckJob.create(FREE_TIER).run()

    assertTrue(result.isSuccess)
    verify(exactly = 0) { signalStore.backup.backupTier = any() }
  }

  @Test
  fun `given we are the primary, when I run, then I leave our tier alone`() {
    every { signalStore.account.isPrimaryDevice } returns true

    val result = BackupTierDowngradeCheckJob.create(FREE_TIER).run()

    assertTrue(result.isSuccess)
    verify(exactly = 0) { signalStore.backup.backupTier = any() }
  }
}

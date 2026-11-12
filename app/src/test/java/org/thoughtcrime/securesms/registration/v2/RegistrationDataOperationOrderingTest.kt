/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.registration.v2

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.contentproviders.BlobProvider
import org.signal.registration.proto.AccountData
import org.signal.registration.proto.RegistrationData
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.registration.util.RegistrationUtil
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class RegistrationDataOperationOrderingTest {

  @get:Rule
  val signalStore = MockSignalStoreRule()

  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  private val context: Application = ApplicationProvider.getApplicationContext()
  private val blobs = mutableMapOf<Uri, ByteArray>()
  private var blobUri: String? = null
  private var blobCounter = 0
  private lateinit var controller: AppRegistrationStorageController

  @Before
  fun setUp() {
    every { SignalStore.registration.inProgressRegistrationDataBlobUri } answers { blobUri }
    every { SignalStore.registration.inProgressRegistrationDataBlobUri = any() } answers { blobUri = firstArg() }

    every { AppDependencies.blobs.forData(any<ByteArray>()) } answers {
      val bytes = firstArg<ByteArray>()
      mockk<BlobProvider.MemoryBlobBuilder> {
        every { createForMultipleSessionsOnDisk(any()) } answers {
          val uri = Uri.parse("memoryblob://registration/${blobCounter++}")
          blobs[uri] = bytes
          uri
        }
      }
    }
    every { AppDependencies.blobs.getStream(any(), any()) } answers {
      val uri = secondArg<Uri>()
      blobs[uri]?.let { ByteArrayInputStream(it) } ?: throw IOException("No blob for $uri")
    }
    every { AppDependencies.blobs.delete(any(), any()) } answers {
      blobs.remove(secondArg<Uri>())
    }

    mockkStatic(RegistrationUtil::class)
    justRun { RegistrationUtil.maybeMarkRegistrationComplete() }
    controller = AppRegistrationStorageController(context)
  }

  @After
  fun tearDown() {
    unmockkAll()
  }

  @Test
  fun `restore planning waits for the production commit to persist its committed flag`() {
    runBlocking {
      controller.updateInProgressRegistrationData {
        accountData = AccountData(aci = "test-aci", servicePassword = "test-password")
        accountDataCommitted = false
      }
    }

    val accountApplyEntered = CountDownLatch(1)
    val allowAccountApplyToFinish = CountDownLatch(1)
    controller.applyAccountDataForCommit = { _, _, _, _ ->
      accountApplyEntered.countDown()
      check(allowAccountApplyToFinish.await(5, TimeUnit.SECONDS))
    }

    val commitExecutor = Executors.newSingleThreadExecutor()
    val restorePlanExecutor = Executors.newSingleThreadExecutor()
    val restorePlanRequested = CountDownLatch(1)
    val restorePlanThread = AtomicReference<Thread>()
    try {
      val commit = commitExecutor.submit { runBlocking { controller.commitRegistrationData() } }
      assertThat(accountApplyEntered.await(5, TimeUnit.SECONDS)).isTrue()

      val plan = restorePlanExecutor.submit<V1RestoreRegistrationPlan> {
        restorePlanThread.set(Thread.currentThread())
        restorePlanRequested.countDown()
        runBlocking {
          controller.withRegistrationDataOperationLock {
            controller.readV1RestorePlanWhileOperationLocked()
          }
        }
      }
      assertThat(restorePlanRequested.await(5, TimeUnit.SECONDS)).isTrue()
      awaitCondition("restore planning should wait behind the production account commit") {
        restorePlanThread.get().state == Thread.State.WAITING || restorePlanThread.get().state == Thread.State.TIMED_WAITING
      }
      assertThat(plan.isDone).isFalse()

      allowAccountApplyToFinish.countDown()
      commit.get(5, TimeUnit.SECONDS)
      assertThat(plan.get(5, TimeUnit.SECONDS).excludeKeyTables).isTrue()
      assertThat(runBlocking { controller.readInProgressRegistrationData().accountDataCommitted }).isTrue()
    } finally {
      allowAccountApplyToFinish.countDown()
      commitExecutor.shutdownNow()
      restorePlanExecutor.shutdownNow()
    }
  }

  private fun awaitCondition(description: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition() && System.nanoTime() < deadline) Thread.yield()
    assertThat(condition()).isTrue()
  }
}

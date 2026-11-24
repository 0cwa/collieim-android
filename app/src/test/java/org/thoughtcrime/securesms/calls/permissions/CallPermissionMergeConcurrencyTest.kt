/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.permissions

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.recipients.Recipient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Production mutations with a controlled database writer, including a deliberately stale UI recipient. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class CallPermissionMergeConcurrencyTest {
  private val environment = CallPermissionTestEnvironment()
  private val executor = Executors.newSingleThreadExecutor()

  @Before
  fun setUp() = environment.setUp()

  @After
  fun tearDown() {
    executor.shutdownNow()
    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
    environment.tearDown()
  }

  @Test
  fun `merge sees a restriction whose settings write completed first`() {
    val primary = environment.recipient(1)
    val secondary = environment.recipient(2)
    CallPermissions.setOverride(secondary, CallPermission.BLOCK)

    environment.mergeRecipients(primary.id, secondary.id)

    assertEquals(CallPermission.BLOCK, CallPermissions.getOverride(primary))
    assertEquals(CallPermission.BLOCK, environment.storedPolicy().override(CallContext.DIRECT, secondary.id.toLong()))
  }

  @Test
  fun `restriction racing a no-op merge waits then targets the canonical recipient`() {
    val primary = environment.recipient(1)
    val secondary = environment.recipient(2)
    val setterEntered = CountDownLatch(1)
    lateinit var setter: Future<*>
    environment.onNextPolicyRead {
      // The merge already owns the database writer and has read an INHERIT/INHERIT snapshot.
      environment.onNextRecipientTransaction { setterEntered.countDown() }
      setter = executor.submit { CallPermissions.setOverride(secondary, CallPermission.BLOCK) }
      assertTrue(setterEntered.await(5, TimeUnit.SECONDS))
      assertFalse(setter.isDone)
    }

    environment.mergeRecipients(primary.id, secondary.id)
    setter.get(5, TimeUnit.SECONDS)

    // A live-recipient cache that has not processed the remap must not redirect this write.
    assertEquals(secondary.id, Recipient.resolved(secondary.id).id)
    assertEquals(CallPermission.BLOCK, CallPermissions.getOverride(primary))
    assertEquals(CallPermission.INHERIT, environment.storedPolicy().override(CallContext.DIRECT, secondary.id.toLong()))
  }

  @Test
  fun `settings action queued before a merge resolves its identity after acquiring the writer`() {
    val primary = environment.recipient(1)
    val secondary = environment.recipient(2)
    val setterEntered = CountDownLatch(1)
    val releaseSetter = CountDownLatch(1)
    environment.onNextRecipientTransaction {
      setterEntered.countDown()
      assertTrue(releaseSetter.await(5, TimeUnit.SECONDS))
    }
    val setter = executor.submit { CallPermissions.setOverride(secondary, CallPermission.BLOCK) }
    try {
      assertTrue(setterEntered.await(5, TimeUnit.SECONDS))
      environment.mergeRecipients(primary.id, secondary.id)
    } finally {
      releaseSetter.countDown()
    }
    setter.get(5, TimeUnit.SECONDS)

    assertEquals(CallPermission.BLOCK, CallPermissions.getOverride(primary))
    assertEquals(CallPermission.INHERIT, environment.storedPolicy().override(CallContext.DIRECT, secondary.id.toLong()))
  }
}

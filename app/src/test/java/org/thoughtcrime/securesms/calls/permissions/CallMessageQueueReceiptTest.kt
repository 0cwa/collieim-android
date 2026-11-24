package org.thoughtcrime.securesms.calls.permissions

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class CallMessageQueueReceiptTest {
  private val policy = CallPermissionTestEnvironment()

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    policy.setUp()
  }

  @After
  fun tearDown() = policy.tearDown()

  @Test
  fun `serialization retains original age and expiry across job rehydration`() {
    val original = CallMessageQueueReceipt.capture()
    ShadowSystemClock.advanceBy(Duration.ofMillis(90_000))
    val restored = CallMessageQueueReceipt.fromPersisted(original.process, original.generation, original.receivedAt)!!
    assertEquals(90_000L, restored.queueDelayMillis())

    ShadowSystemClock.advanceBy(Duration.ofMillis(30_000))
    assertNull(original.queueDelayMillis())
    assertNull(restored.queueDelayMillis())
  }

  @Test
  fun `missing partial and previous process stamps cannot restore calling work`() {
    val current = CallMessageQueueReceipt.capture()
    assertNull(CallMessageQueueReceipt.fromPersisted(null, null, null))
    assertNull(CallMessageQueueReceipt.fromPersisted(current.process, null, current.receivedAt))
    assertNull(CallMessageQueueReceipt.fromPersisted(current.process, current.generation, null))
    assertNull(CallMessageQueueReceipt.fromPersisted("previous-process", current.generation, current.receivedAt))
    assertNull(CallMessageQueueReceipt.fromPersisted(current.process, 0, current.receivedAt))
    assertNull(CallMessageQueueReceipt.fromPersisted(current.process, current.generation, -1))
    assertNull(CallMessageQueueReceipt.fromPersisted(current.process, current.generation, current.receivedAt + 1)!!.queueDelayMillis())
  }

  @Test
  fun `revocation remains effective after calling is enabled again`() {
    val receipt = CallMessageQueueReceipt.capture()
    assertNotNull(receipt.queueDelayMillis())
    CallPermissions.setAllCallingDisabled(true)
    CallPermissions.setAllCallingDisabled(false)
    assertNull(receipt.queueDelayMillis())
  }

  @Test
  fun `direct admission cannot acquire a new generation after queue validation`() {
    val recipient = policy.recipient(7)
    val receipt = CallMessageQueueReceipt.capture()
    assertNotNull(receipt.queueDelayMillis())
    CallPermissions.setAllCallingDisabled(true)
    CallPermissions.setAllCallingDisabled(false)

    assertNull(CallPermissions.admitDirect(recipient.id, 41, 2, DirectMessageKind.OFFER, 0, receipt.generation))
    assertNotNull(CallPermissions.admitDirect(recipient.id, 41, 2, DirectMessageKind.OFFER, 0))
  }

  @Test
  fun `opaque admission cannot acquire a new generation after queue validation`() {
    val group = policy.group(8)
    val sender = policy.recipient(7)
    val receipt = CallMessageQueueReceipt.capture()
    assertNotNull(receipt.queueDelayMillis())
    CallPermissions.setAllCallingDisabled(true)
    CallPermissions.setAllCallingDisabled(false)

    assertNull(CallPermissions.authorizeGroup(group.id, sender.id, 41, 2, GroupMessageKind.RING, 0, receipt.generation))
    assertNotNull(CallPermissions.authorizeGroup(group.id, sender.id, 41, 2, GroupMessageKind.RING, 0))
  }
}

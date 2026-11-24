package org.thoughtcrime.securesms.jobs

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.groups.v2.admission.GroupAdmission
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.SystemOutLogger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class GroupCallUpdateSendJobCallPermissionTest {
  private val groupId = RecipientId.from(9)

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    mockkObject(CallPermissions, Recipient)
    mockkStatic(CallPermissions::class, GroupAdmission::class)
    every { GroupAdmission.isHeld(groupId) } returns false
    every { CallPermissions.generation() } returns 1
  }

  @After
  fun tearDown() = unmockkAll()

  @Test
  fun `revoked final update exits before profile or message transport even if defaults allow groups`() {
    every { CallPermissions.isGroupCleanupPermitValid("revoked", groupId) } returns false
    every { CallPermissions.canReceiveGroup(groupId) } returns true
    assertTrue(restored("revoked").run().isSuccess)
    verify(exactly = 0) { Recipient.self() }
    verify(exactly = 0) { CallPermissions.canReceiveGroup(any()) }
  }

  @Test
  fun `exact outgoing group cleanup is allowed after hangup despite blocked incoming group default`() {
    every { CallPermissions.isGroupCleanupPermitValid("exact-group", groupId) } returns true
    every { CallPermissions.canReceiveGroup(groupId) } returns false
    val self = mockk<Recipient>()
    every { Recipient.self() } returns self
    every { self.isRegistered } returns false
    restored("exact-group").run()
    verify(exactly = 1) { Recipient.self() }
    verify(exactly = 0) { CallPermissions.canReceiveGroup(any()) }
  }

  @Test
  fun `a held group cannot send an update with a valid cleanup permit`() {
    every { GroupAdmission.isHeld(groupId) } returns true
    every { CallPermissions.isGroupCleanupPermitValid("exact-group", groupId) } returns true

    assertTrue(restored("exact-group").run().isSuccess)

    verify(exactly = 0) { Recipient.self() }
  }

  @Test
  fun `legacy queued update cannot borrow a later outgoing session`() {
    every { CallPermissions.canReceiveGroup(groupId) } returns false
    every { CallPermissions.canContinueCall(groupId) } returns true
    assertTrue(restored(null).run().isSuccess)
    verify(exactly = 0) { Recipient.self() }
    verify(exactly = 0) { CallPermissions.canContinueCall(any()) }
  }

  @Test
  fun `cleanup permit remains attached to its exact group when serialized`() {
    val serialized = restored("old-process-permit").serialize()
    val data = JsonJobData.deserialize(serialized)
    assertEquals("old-process-permit", data.getString("call_cleanup_permit"))
    every { CallPermissions.isGroupCleanupPermitValid("old-process-permit", groupId) } returns false
    val reloaded = GroupCallUpdateSendJob.Factory().create(Job.Parameters.Builder().build(), serialized)
    assertTrue(reloaded.run().isSuccess)
    verify(exactly = 1) { CallPermissions.isGroupCleanupPermitValid("old-process-permit", groupId) }
    verify(exactly = 0) { Recipient.self() }
  }

  private fun restored(permit: String?): GroupCallUpdateSendJob {
    val builder = JsonJobData.Builder()
      .putString("recipient_id", groupId.serialize())
      .putString("era_id", "era")
      .putString("recipients", RecipientId.toSerializedList(listOf(RecipientId.from(4))))
      .putInt("initial_recipient_count", 1)
    if (permit != null) builder.putString("call_cleanup_permit", permit)
    return GroupCallUpdateSendJob.Factory().create(Job.Parameters.Builder().build(), builder.serialize())
  }
}

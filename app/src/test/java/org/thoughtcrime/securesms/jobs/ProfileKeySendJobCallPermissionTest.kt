package org.thoughtcrime.securesms.jobs

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.calls.permissions.CallContext
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.SystemOutLogger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class ProfileKeySendJobCallPermissionTest {
  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    mockkObject(CallPermissions, Recipient)
  }

  @After
  fun tearDown() = unmockkAll()

  @Test
  fun `revoked call-specific profile job exits before reading profile or dispatching transport`() {
    every { CallPermissions.isSharingPermitValid("revoked") } returns false
    val job = restored(thread = 9, permit = "revoked")
    assertTrue(job.run().isSuccess)
    verify(exactly = 0) { Recipient.self() }
  }

  @Test
  fun `legacy restored call-link profile job has no live consent and is discarded`() {
    assertTrue(restored(thread = -1, permit = null).run().isSuccess)
    verify(exactly = 0) { Recipient.self() }
    verify(exactly = 0) { CallPermissions.isSharingPermitValid(any()) }
  }

  @Test
  fun `call permit survives job serialization so restart can reject it`() {
    val original = restored(thread = 9, permit = "old-process-permit")
    val serialized = JsonJobData.deserialize(original.serialize())
    assertEquals("old-process-permit", serialized.getString("call_sharing_permit"))
    every { CallPermissions.isSharingPermitValid("old-process-permit") } returns false
    val reloaded = ProfileKeySendJob.Factory().create(Job.Parameters.Builder().build(), original.serialize())
    assertTrue(reloaded.run().isSuccess)
    verify(exactly = 0) { Recipient.self() }
  }

  @Test
  fun `ordinary messaging profile jobs do not consult call policy`() {
    val self = mockk<Recipient>()
    every { Recipient.self() } returns self
    every { self.isRegistered } returns false
    restored(thread = 9, permit = null).run()
    verify(exactly = 1) { Recipient.self() }
    verify(exactly = 0) { CallPermissions.isSharingPermitValid(any()) }
  }

  @Test
  fun `factories require the exact direct or link session before creating profile work`() {
    val recipient = mockk<Recipient>()
    val id = RecipientId.from(9)
    every { recipient.resolve() } returns recipient
    every { recipient.id } returns id
    every { CallPermissions.createSharingPermit(CallContext.DIRECT, id) } returns null
    every { CallPermissions.createSharingPermit(CallContext.CALL_LINK, id) } returns null
    assertNull(ProfileKeySendJob.createForCall(recipient, false))
    assertNull(ProfileKeySendJob.createForCallLinks(id, listOf(RecipientId.from(4))))
  }

  private fun restored(thread: Long, permit: String?): ProfileKeySendJob {
    val builder = JsonJobData.Builder().putLong("thread", thread).putString("recipients", RecipientId.toSerializedList(listOf(RecipientId.from(4))))
    if (permit != null) builder.putString("call_sharing_permit", permit)
    return ProfileKeySendJob.Factory().create(Job.Parameters.Builder().build(), builder.serialize())
  }
}

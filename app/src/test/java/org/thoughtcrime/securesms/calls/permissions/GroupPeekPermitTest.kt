package org.thoughtcrime.securesms.calls.permissions

import android.app.Application
import io.mockk.every
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.testutil.SystemOutLogger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class GroupPeekPermitTest {
  private val environment = CallPermissionTestEnvironment()

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    environment.setUp()
    assertTrue("The fixture must expose the registered account's call policy", CallPermissions.isCallingAllowed())
  }

  @After
  fun tearDown() = environment.tearDown()

  @Test
  fun `remote peek queued before restriction cannot resume after reallowing`() {
    val group = environment.group(9)
    val sender = environment.recipient(1)
    val permit = CallPermissions.createGroupPeekPermit(group.id, sender.id)!!
    assertTrue(CallPermissions.isGroupPeekPermitValid(permit, group.id, sender.id))
    CallPermissions.setDefault(CallContext.GROUP, CallPermission.BLOCK)
    CallPermissions.setDefault(CallContext.GROUP, CallPermission.ALLOW)
    assertFalse(CallPermissions.isGroupPeekPermitValid(permit, group.id, sender.id))
    assertNotNull(CallPermissions.createGroupPeekPermit(group.id, sender.id))
  }

  @Test
  fun `peek permits remain bound to group sender current block and process`() {
    val group = environment.group(9)
    val otherGroup = environment.group(10)
    val sender = environment.recipient(1)
    val otherSender = environment.recipient(2)
    val permit = CallPermissions.createGroupPeekPermit(group.id, sender.id)!!
    assertFalse(CallPermissions.isGroupPeekPermitValid(permit, otherGroup.id, sender.id))
    assertFalse(CallPermissions.isGroupPeekPermitValid(permit, group.id, otherSender.id))
    val oldProcess = permit.split('|').toMutableList().also { it[1] = "another-process" }.joinToString("|")
    assertFalse(CallPermissions.isGroupPeekPermitValid(oldProcess, group.id, sender.id))
    assertFalse(CallPermissions.isGroupPeekPermitValid(null, group.id, sender.id))
    every { sender.isBlocked } returns true
    assertFalse(CallPermissions.isGroupPeekPermitValid(permit, group.id, sender.id))
  }

  @Test
  fun `blocked group cannot cause passive peeks except within its explicit outgoing session`() {
    val group = environment.group(9)
    val sender = environment.recipient(1)
    CallPermissions.setDefault(CallContext.GROUP, CallPermission.BLOCK)
    assertNull(CallPermissions.createGroupPeekPermit(group.id, sender.id))
    assertTrue(CallPermissions.beginOutgoing(group.id))
    val permit = CallPermissions.createGroupPeekPermit(group.id, sender.id)!!
    assertTrue(CallPermissions.isGroupPeekPermitValid(permit, group.id, sender.id))
    CallPermissions.endAllSessions()
    assertFalse(CallPermissions.isGroupPeekPermitValid(permit, group.id, sender.id))
  }

  @Test
  fun `announcement admin demotion invalidates a ringing peek without changing group data permission`() {
    val group = environment.group(9)
    val sender = environment.recipient(1)
    val record = SignalDatabase.groups.getGroup(group.id).get()
    every { record.isAnnouncementGroup } returns true
    every { record.isAdmin(sender) } returns true
    assertNotNull(CallPermissions.authorizeGroup(group.id, sender.id, 41, 2, GroupMessageKind.RING, 0))
    val dataPermit = CallPermissions.createGroupPeekPermit(group.id, sender.id)!!
    assertTrue(CallPermissions.isIncomingGroupRingCurrent(group.id, 41, sender.id))

    every { record.isAdmin(sender) } returns false

    assertFalse(CallPermissions.isIncomingGroupRingCurrent(group.id, 41, sender.id))
    assertTrue(CallPermissions.isGroupPeekPermitValid(dataPermit, group.id, sender.id))
  }

  @Test
  fun `ring recheck respects current membership and blocked identities`() {
    val group = environment.group(9)
    val sender = environment.recipient(1)
    val record = SignalDatabase.groups.getGroup(group.id).get()
    assertNotNull(CallPermissions.authorizeGroup(group.id, sender.id, 41, 2, GroupMessageKind.RING, 0))
    assertTrue(CallPermissions.isIncomingGroupRingCurrent(group.id, 41, sender.id))

    every { record.memberLevel(sender) } returns GroupTable.MemberLevel.NOT_A_MEMBER
    assertFalse(CallPermissions.isIncomingGroupRingCurrent(group.id, 41, sender.id))
    every { record.memberLevel(sender) } returns GroupTable.MemberLevel.FULL_MEMBER
    every { sender.isBlocked } returns true
    assertFalse(CallPermissions.isIncomingGroupRingCurrent(group.id, 41, sender.id))
    every { sender.isBlocked } returns false
    every { group.isBlocked } returns true
    assertFalse(CallPermissions.isIncomingGroupRingCurrent(group.id, 41, sender.id))
  }
}

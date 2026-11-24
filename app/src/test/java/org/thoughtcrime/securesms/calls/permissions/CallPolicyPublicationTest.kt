/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.permissions

import android.app.Application
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the production facade across a completed write between its policy read and decision. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class CallPolicyPublicationTest {
  private val environment = CallPermissionTestEnvironment()

  @Before
  fun setUp() = environment.setUp()

  @After
  fun tearDown() = environment.tearDown()

  @Test
  fun `old direct allow cannot receive a new generation after a completed block write`() {
    val caller = environment.recipient(1)
    environment.onNextPolicyRead { CallPermissions.setDefault(CallContext.DIRECT, CallPermission.BLOCK) }

    assertNull(CallPermissions.admitDirect(caller.id, 9, 2, DirectMessageKind.OFFER, 0))
    assertNull(CallPermissions.admitDirect(caller.id, 10, 2, DirectMessageKind.OFFER, 0))
  }

  @Test
  fun `old group allow cannot receive a new generation after a completed block write`() {
    val group = environment.group(10)
    val caller = environment.recipient(1)
    environment.onNextPolicyRead { CallPermissions.setDefault(CallContext.GROUP, CallPermission.BLOCK) }

    assertNull(CallPermissions.authorizeGroup(group.id, caller.id, 9, 2, GroupMessageKind.RING, 0))
    assertNull(CallPermissions.authorizeGroup(group.id, caller.id, 10, 2, GroupMessageKind.RING, 0))
  }

  @Test
  fun `global disable completed during a read prevents outgoing session creation`() {
    val recipient = environment.recipient(1)
    environment.onNextPolicyRead { CallPermissions.setAllCallingDisabled(true) }

    assertFalse(CallPermissions.beginOutgoing(recipient.id))
    assertFalse(CallPermissions.canContinueCall(recipient.id))
  }

  @Test
  fun `queued ticket is invalid when revocation completes during validation read`() {
    val recipient = environment.recipient(1)
    val ticket = CallPermissions.admitDirect(recipient.id, 9, 2, DirectMessageKind.OFFER, 0)
    assertNotNull(ticket)
    environment.onNextPolicyRead { CallPermissions.setDefault(CallContext.DIRECT, CallPermission.BLOCK) }

    assertFalse(CallPermissions.isAuthorized(ticket!!))
    assertNull(CallPermissions.admitDirect(recipient.id, 9, 2, DirectMessageKind.ICE, 0))
  }
}

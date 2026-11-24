/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.permissions

import android.app.Application
import io.mockk.every
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class CallSharingPermitTest {
  private val environment = CallPermissionTestEnvironment()

  @Before
  fun setUp() = environment.setUp()

  @After
  fun tearDown() = environment.tearDown()

  @Test
  fun `trusted PNI response permit survives normal teardown but remains recipient scoped`() {
    val recipient = environment.recipient(1)
    assertNotNull(CallPermissions.admitDirect(recipient.id, 9, 2, DirectMessageKind.OFFER, 0))
    val permit = CallPermissions.createSharingPermit(CallContext.DIRECT, recipient.id)!!

    CallPermissions.endCall(recipient.id, 9)

    assertTrue(CallPermissions.isSharingPermitValid(permit, recipient.id))
    assertFalse(CallPermissions.isSharingPermitValid(permit, environment.recipient(2).id))
  }

  @Test
  fun `permission or account revocation cancels direct profile sharing queued before teardown`() {
    val recipient = environment.recipient(1)
    assertNotNull(CallPermissions.admitDirect(recipient.id, 9, 2, DirectMessageKind.OFFER, 0))
    val permit = CallPermissions.createSharingPermit(CallContext.DIRECT, recipient.id)!!
    CallPermissions.endCall(recipient.id, 9)

    CallPermissions.setDefault(CallContext.DIRECT, CallPermission.BLOCK)
    assertFalse(CallPermissions.isSharingPermitValid(permit, recipient.id))

    assertTrue(CallPermissions.beginOutgoing(recipient.id))
    val outgoingPermit = CallPermissions.createSharingPermit(CallContext.DIRECT, recipient.id)!!
    CallPermissions.onAccountRegistrationChanged()
    assertFalse(CallPermissions.isSharingPermitValid(outgoingPermit, recipient.id))
  }

  @Test
  fun `call link sharing permit ends with its exact session`() {
    val link = environment.recipient(3)
    every { link.isIndividual } returns false
    every { link.isCallLink } returns true
    assertTrue(CallPermissions.beginOutgoing(link.id))
    val permit = CallPermissions.createSharingPermit(CallContext.CALL_LINK, link.id)!!
    assertTrue(CallPermissions.isSharingPermitValid(permit))

    CallPermissions.endAllSessions()

    assertFalse(CallPermissions.isSharingPermitValid(permit))
  }
}

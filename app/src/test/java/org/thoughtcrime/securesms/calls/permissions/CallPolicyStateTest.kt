/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.permissions

import org.junit.Assert.*
import org.junit.Test

class CallPolicyStateTest {
  @Test
  fun `new installations allow ordinary direct and group calls`() {
    val state = CallPolicyState()
    assertTrue(state.allowsIncoming(CallContext.DIRECT, 1))
    assertTrue(state.allowsIncoming(CallContext.GROUP, 2))
    assertFalse(state.allCallingDisabled)
  }

  @Test
  fun `inherit follows later default changes`() {
    val state = CallPolicyState().withOverride(CallContext.DIRECT, 1, CallPermission.INHERIT)
    assertTrue(state.allowsIncoming(CallContext.DIRECT, 1))
    assertFalse(state.copy(directDefault = CallPermission.BLOCK).allowsIncoming(CallContext.DIRECT, 1))
    assertTrue(state.overrides.isEmpty())
  }

  @Test
  fun `direct and group permissions do not cross contexts`() {
    val state = CallPolicyState(directDefault = CallPermission.BLOCK, groupDefault = CallPermission.BLOCK)
      .withOverride(CallContext.DIRECT, 1, CallPermission.ALLOW)
      .withOverride(CallContext.GROUP, 2, CallPermission.ALLOW)
    assertFalse(state.allowsIncoming(CallContext.GROUP, 1))
    assertFalse(state.allowsIncoming(CallContext.DIRECT, 2))
    assertFalse(state.allowsIncoming(CallContext.CALL_LINK, 2))
  }

  @Test
  fun `absolute prohibition wins over every allow`() {
    val state = CallPolicyState(allCallingDisabled = true)
      .withOverride(CallContext.DIRECT, 1, CallPermission.ALLOW)
      .withOverride(CallContext.GROUP, 2, CallPermission.ALLOW)
    assertFalse(state.allowsIncoming(CallContext.DIRECT, 1))
    assertFalse(state.allowsIncoming(CallContext.GROUP, 2))
  }

  @Test
  fun `merge blocks both canonical and rollback identities`() {
    val state = CallPolicyState().withOverride(CallContext.DIRECT, 1, CallPermission.ALLOW)
      .withOverride(CallContext.DIRECT, 2, CallPermission.BLOCK).merge(1, 2)
    assertEquals(CallPermission.BLOCK, state.override(CallContext.DIRECT, 1))
    assertEquals(CallPermission.BLOCK, state.override(CallContext.DIRECT, 2))
  }

  @Test
  fun `merge never replaces inherit with an allow grant`() {
    val state = CallPolicyState(directDefault = CallPermission.BLOCK)
      .withOverride(CallContext.DIRECT, 2, CallPermission.ALLOW).merge(1, 2)
    assertEquals(CallPermission.INHERIT, state.override(CallContext.DIRECT, 1))
    assertFalse(state.allowsIncoming(CallContext.DIRECT, 1))
  }

  @Test
  fun `account and restore reset removes grants but retains restrictions`() {
    val state = CallPolicyState(account = "old", allCallingDisabled = true)
      .withOverride(CallContext.DIRECT, 1, CallPermission.ALLOW)
      .withOverride(CallContext.GROUP, 2, CallPermission.BLOCK).resetRecipients("new")
    assertEquals("new", state.account)
    assertTrue(state.overrides.isEmpty())
    assertTrue(state.allCallingDisabled)
    assertEquals(CallPermission.BLOCK, state.groupDefault)
    assertEquals(CallPermission.ALLOW, state.directDefault)
  }
}

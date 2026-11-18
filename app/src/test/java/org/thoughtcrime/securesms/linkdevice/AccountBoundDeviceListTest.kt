package org.thoughtcrime.securesms.linkdevice

import im.molly.unifiedpush.lifecycle.StructuralAccountOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AccountBoundDeviceListTest {
  private lateinit var gate: StructuralAccountOwnership

  @Before fun setup() {
    gate = StructuralAccountOwnership(initiallyReady = true)
  }

  @Test fun `selection only accepts an open lease for the account which fetched the list`() {
    val source = checkNotNull(gate.acquire { "account-a" })
    val mutable = mutableListOf(Device(5, "device", 1, 2, 3))
    val list = checkNotNull(AccountBoundDeviceList.capture(source, mutable))
    mutable.clear()
    assertEquals(5, list.selectedId(source, 5))
    assertNull(list.selectedId(source, 6))
    source.close()
    assertNull(list.selectedId(source, 5))
    assertNull(AccountBoundDeviceList.capture(source, list.devices))
  }

  @Test fun `old displayed selection cannot remove the same numeric id after account replacement`() {
    val source = checkNotNull(gate.acquire { "account-a" })
    val list = checkNotNull(AccountBoundDeviceList.capture(source, listOf(Device(5, "old", 1, 2, 3))))
    val writer = checkNotNull(gate.beginChange())
    assertNull(AccountBoundDeviceList.capture(source, list.devices))
    assertNull(list.selectedId(source, 5))
    source.close()
    writer.run({ true }) { Unit }
    val successor = checkNotNull(gate.acquire { "account-b" })
    try {
      assertNull(list.selectedId(successor, 5))
      assertNotNull(AccountBoundDeviceList.capture(successor, listOf(Device(5, "new", 1, 2, 3)))?.selectedId(successor, 5))
    } finally { successor.close() }
  }

  @Test fun `returning to the same account cannot revive an old list`() {
    val source = checkNotNull(gate.acquire { "account-a" })
    val list = checkNotNull(AccountBoundDeviceList.capture(source, listOf(Device(5, "old", 1, 2, 3))))
    source.close()
    checkNotNull(gate.beginChange()).run({ true }) { Unit }
    checkNotNull(gate.beginChange()).run({ true }) { Unit }
    val sameIdentity = checkNotNull(gate.acquire { "account-a" })
    try { assertNull(list.selectedId(sameIdentity, 5)) } finally { sameIdentity.close() }
  }

  @Test fun `an equal epoch from another owner cannot authorize the list`() {
    val source = checkNotNull(gate.acquire { "account-a" })
    val list = checkNotNull(AccountBoundDeviceList.capture(source, listOf(Device(5, "old", 1, 2, 3))))
    val other = StructuralAccountOwnership(initiallyReady = true)
    val otherLease = checkNotNull(other.acquire { "account-a" })
    try { assertNull(list.selectedId(otherLease, 5)) } finally { otherLease.close(); source.close() }
  }
}

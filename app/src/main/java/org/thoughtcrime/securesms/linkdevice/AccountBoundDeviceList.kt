package org.thoughtcrime.securesms.linkdevice

import im.molly.unifiedpush.lifecycle.StructuralAccountOwnership

/**
 * A displayed list bound to the account lease that fetched it. The caller must keep the new lease
 * open throughout the actual removal; checking a selection alone does not pin a later request.
 * This type is not wired to the UI until all account writers participate in the ownership gate.
 */
internal class AccountBoundDeviceList private constructor(
  private val owner: StructuralAccountOwnership,
  private val epoch: Long,
  val devices: List<Device>,
) {
  fun selectedId(lease: StructuralAccountOwnership.Lease<*>, id: Int): Int? =
    id.takeIf { lease.owner === owner && lease.epoch == epoch && lease.isCurrent() && devices.any { device -> device.id == it } }

  companion object {
    fun capture(
      lease: StructuralAccountOwnership.Lease<*>,
      devices: List<Device>,
    ): AccountBoundDeviceList? {
      val owner = lease.owner
      if (!lease.isCurrent()) return null
      return AccountBoundDeviceList(owner, lease.epoch, devices.toList())
    }
  }
}

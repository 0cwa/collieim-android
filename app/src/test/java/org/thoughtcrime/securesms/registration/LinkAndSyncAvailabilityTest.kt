package org.thoughtcrime.securesms.registration

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.signal.core.util.billing.OneTimePurchaseApi
import org.signal.registration.NetworkController
import org.signal.registration.PreExistingRegistrationData
import org.signal.registration.RegistrationRepository
import org.signal.registration.StorageController
import org.thoughtcrime.securesms.util.Environment

class LinkAndSyncAvailabilityTest {
  private val network = mockk<NetworkController>(relaxed = true)
  private val storage = mockk<StorageController>(relaxed = true)

  private fun repository() = RegistrationRepository(
    context = mockk<Context>(relaxed = true),
    networkController = network,
    storageController = storage,
    isLinkAndSyncAvailable = Environment.IS_LINK_AND_SYNC_AVAILABLE,
    signalLoginPurchaseApi = OneTimePurchaseApi.Empty
  )

  @Test
  fun `clean linked device advertises history transfer`() = runTest {
    coEvery { storage.getPreExistingRegistrationData() } returns null
    every { network.startLinkDeviceProvisioning(any()) } returns emptyFlow()

    repository().startLinkDeviceProvisioning().toList()

    verify { network.startLinkDeviceProvisioning(true) }
  }

  @Test
  fun `existing registration does not advertise history transfer`() = runTest {
    coEvery { storage.getPreExistingRegistrationData() } returns mockk<PreExistingRegistrationData>(relaxed = true)
    every { network.startLinkDeviceProvisioning(any()) } returns emptyFlow()

    repository().startLinkDeviceProvisioning().toList()

    verify { network.startLinkDeviceProvisioning(false) }
  }
}

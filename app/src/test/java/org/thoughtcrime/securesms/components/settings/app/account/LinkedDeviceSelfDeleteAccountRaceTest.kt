package org.thoughtcrime.securesms.components.settings.app.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.thoughtcrime.securesms.components.settings.app.account.LinkedDeviceAccountSettingsState.OneTimeEvent
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Characterizes current unsafe behavior with synthetic identities; no real unlink or wipe is performed. */
class LinkedDeviceSelfDeleteAccountRaceTest {
  @Test fun `account switch after confirmation targets the successor device and emits wipe`() = runBlocking {
    val currentDevice = AtomicInteger(3)
    val removed = AtomicInteger(-1)
    val events = mutableListOf<OneTimeEvent?>()
    val viewModel = LinkedDeviceAccountSettingsViewModel(
      selfDeviceId = { currentDevice.get() },
      removeDevice = { id -> removed.set(id); true },
    )
    viewModel.applyEvent(
      LinkedDeviceAccountSettingsState(showDeleteConfirmationDialog = true),
      LinkedDeviceAccountSettingsEvent.DeleteConfirmed,
    ) { state ->
      events += state.oneTimeEvent
      if (state.deleting && state.oneTimeEvent == null) currentDevice.set(7)
    }
    // This deliberately records the present bug; the future ownership fix must reverse these assertions.
    assertEquals(7, removed.get())
    assertEquals(OneTimeEvent.WipeData, events.last())
  }

  @Test fun `account switch while unlink is suspended still emits unbound wipe`() = runBlocking {
    val currentAccount = AtomicReference("account-a")
    val entered = CompletableDeferred<Unit>()
    val resume = CompletableDeferred<Unit>()
    val targetAtStart = AtomicReference<String>()
    val events = mutableListOf<OneTimeEvent?>()
    val viewModel = LinkedDeviceAccountSettingsViewModel(
      selfDeviceId = { 3 },
      removeDevice = {
        targetAtStart.set(currentAccount.get())
        entered.complete(Unit)
        resume.await()
        true
      },
    )
    val operation = async {
      viewModel.applyEvent(
        LinkedDeviceAccountSettingsState(showDeleteConfirmationDialog = true),
        LinkedDeviceAccountSettingsEvent.DeleteConfirmed,
      ) { events += it.oneTimeEvent }
    }
    withTimeout(5_000) { entered.await() }
    currentAccount.set("account-b")
    resume.complete(Unit)
    withTimeout(5_000) { operation.await() }
    assertEquals("account-a", targetAtStart.get())
    assertEquals("account-b", currentAccount.get())
    assertEquals(OneTimeEvent.WipeData, events.last())
  }
}

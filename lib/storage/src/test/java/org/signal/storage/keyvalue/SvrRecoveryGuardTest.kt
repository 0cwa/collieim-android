package org.signal.storage.keyvalue

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SvrRecoveryGuardTest {
  private val storage = MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet())

  @Test
  fun `opt out clears protection without a UI completion callback`() {
    val store = configuredStore()
    SvrValues(store) { error("Master key is not needed for removal") }.optOut()

    val restored = newStore()
    val svr = SvrValues(restored) { error("Master key is not needed for removal") }
    assertTrue(svr.hasOptedOut())
    assertFalse(svr.hasPin())
    assertNull(svr.pin)
    assertFalse(restored.getBoolean(PinValues.RECOVERY_PIN_GUARD_ENABLED, false))
  }

  @Test
  fun `registration reset clears protection with the PIN`() {
    SvrValues(configuredStore()) { error("Master key is not needed for removal") }.clearRegistrationLockAndPin()

    val restored = newStore()
    assertFalse(SvrValues(restored) { error("Master key is not needed for removal") }.hasPin())
    assertFalse(restored.getBoolean(PinValues.RECOVERY_PIN_GUARD_ENABLED, false))
  }

  private fun configuredStore(): KeyValueStore = newStore().also {
    it.beginWrite()
      .putString("kbs.pin", "test-pin")
      .putString("kbs.registration_lock_local_pin_hash", "test-hash")
      .putBoolean(PinValues.RECOVERY_PIN_GUARD_ENABLED, true)
      .commit()
  }

  private fun newStore() = KeyValueStore(storage) { it.run() }
}

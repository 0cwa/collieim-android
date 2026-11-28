/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.backup.v2.barkup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.thoughtcrime.securesms.keyvalue.CollieBarkupValues
import org.thoughtcrime.securesms.testutil.DirectExecutor

class BarkupEnrollmentTest {
  private val values = CollieBarkupValues(KeyValueStore(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet()), DirectExecutor()))
  private val api = barkupApi("https://backup.example")
  private val credentials = BarkupCredentials("A1234567890B", "test-token")

  @Test
  fun `disconnect invalidates an in-flight offer result before any credential write`() {
    val previous = values.beginPairing("account:1")
    assertTrue(values.enable(previous, api, credentials) { "account:1" })
    val owner = values.enrollment("account:1")?.id
    val inFlight = values.beginPairing("account:1")
    var revoked: String? = null
    values.disable { revoked = it }

    assertEquals(owner, revoked)
    assertFalse(values.enable(inFlight, api, credentials) { "account:1" })
    assertNull(values.enrollment("account:1"))
    assertTrue(values.keysToIncludeInBackup.isEmpty())
  }

  @Test
  fun `only the latest pairing for the unchanged account and device may install credentials`() {
    val stale = values.beginPairing("account:1")
    val latest = values.beginPairing("account:1")

    assertFalse(values.enable(stale, api, credentials) { "account:1" })
    assertFalse(values.enable(latest, api, credentials) { "account:2" })
    assertTrue(values.enable(latest, api, credentials) { "account:1" })
    assertNull(values.enrollment("account:2"))
    assertEquals("backup.example", values.summary("account:1")?.host)
  }
}

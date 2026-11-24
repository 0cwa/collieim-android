/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.KeyValueDataSet
import org.thoughtcrime.securesms.backup.proto.KeyValue
import org.thoughtcrime.securesms.keyvalue.CallPermissionValues
import org.thoughtcrime.securesms.testutil.SnapshotKeyValuePersistentStorage
import org.thoughtcrime.securesms.testutil.SystemOutLogger

/** A legacy backup can name arbitrary keys, so exporter exclusion alone is insufficient. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class CallPolicyBackupImportTest {
  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
  }

  @Test
  fun `an archived policy cannot overwrite the local restriction record`() {
    val storage = SnapshotKeyValuePersistentStorage(KeyValueDataSet().apply { putString(CallPermissionValues.RECORD, "local restricted record") })
    val imported = KeyValue.Builder().key(CallPermissionValues.RECORD).stringValue("restored grant record").build()

    FullBackupImporter.processKeyValue(imported, storage)

    assertEquals("local restricted record", storage.getDataSet().getNonNullString(CallPermissionValues.RECORD))
  }

  @Test
  fun `reserved call-policy keys cannot arrive through old or malformed archives`() {
    val storage = SnapshotKeyValuePersistentStorage()
    for (key in listOf(CallPermissionValues.RECORD, CallPermissionValues.KEY_PREFIX + "future_version")) {
      FullBackupImporter.processKeyValue(KeyValue.Builder().key(key).booleanValue(false).build(), storage)
      assertFalse(storage.getDataSet().containsKey(key))
    }

    FullBackupImporter.processKeyValue(KeyValue.Builder().key("ordinary.preference").stringValue("restored").build(), storage)
    assertEquals("restored", storage.getDataSet().getNonNullString("ordinary.preference"))
  }
}

/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.groups.v2.admission

import android.app.Application
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.storage.keyvalue.KeyValueStore
import org.thoughtcrime.securesms.testutil.SnapshotKeyValuePersistentStorage

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class GroupAdmissionValuesTest {
  private val account = "11111111-1111-4111-8111-111111111111"
  private val otherAccount = "22222222-2222-4222-8222-222222222222"
  private val group = "group"
  private val storage = SnapshotKeyValuePersistentStorage()
  private val membership = AdmissionMembership(MembershipKind.FULL, "member:5", 5, 5)

  @Test
  fun `absent state preserves Signal behavior but corrupt state requires review`() {
    assertThat(values().read(account).settings.mode).isEqualTo(GroupAdmissionMode.SIGNAL_DEFAULT)
    assertThat(values().read(account).isHeld(group)).isFalse()

    store().beginWrite().putString(GroupAdmissionValues.KEY, "broken document").commitAndWaitForPersistence()

    assertThat(values().read(account).settings.mode).isEqualTo(GroupAdmissionMode.ASK_EVERY_TIME)
    assertThat(values().read(account).isHeld(group)).isTrue()
  }

  @Test
  fun `settings and episode decisions survive a fresh store with no shared cache`() {
    values().update(account) {
      it.copy(
        settings = GroupAdmissionSettings(GroupAdmissionMode.SELECTED_PEOPLE, setOf(otherAccount)),
        records = mapOf(group to AdmissionRecord(membership, AdmissionStatus.AUTOMATIC, "generation"))
      )
    }

    val restored = values().read(account)
    assertThat(restored.settings).isEqualTo(GroupAdmissionSettings(GroupAdmissionMode.SELECTED_PEOPLE, setOf(otherAccount)))
    assertThat(restored.records[group]?.membership).isEqualTo(membership)
    assertThat(restored.isHeld(group)).isFalse()
  }

  @Test
  fun `failed persistence does not publish permission to either the cache or a new store`() {
    val values = values()
    values.update(account) { it.copy(settings = GroupAdmissionSettings(GroupAdmissionMode.ASK_EVERY_TIME)) }
    storage.nextWriteFailure = IllegalStateException("disk full")

    assertThrows(RuntimeException::class.java) {
      values.update(account) { it.copy(records = mapOf(group to AdmissionRecord(membership, AdmissionStatus.LOCAL_CONSENT))) }
    }

    assertThat(values.read(account).isHeld(group)).isTrue()
    assertThat(values().read(account).isHeld(group)).isTrue()
    assertThat(values.read(account).records).isEmpty()
  }

  @Test
  fun `interrupted group-table write remains held after process restart`() {
    values().update(account) {
      it.copy(records = mapOf(group to AdmissionRecord(membership, AdmissionStatus.AUTOMATIC, pendingWrite = "uncommitted")))
    }

    assertThat(values().read(account).isHeld(group)).isTrue()
  }

  @Test
  fun `permissions and operation intents cannot cross accounts`() {
    values().update(account) {
      it.copy(
        settings = GroupAdmissionSettings(GroupAdmissionMode.SELECTED_PEOPLE, setOf(otherAccount)),
        records = mapOf(group to AdmissionRecord(membership, AdmissionStatus.LOCAL_CONSENT)),
        intents = mapOf(group to AdmissionIntent("operation", ConsentKind.JOIN, null, 6))
      )
    }

    val switched = values().read(otherAccount)
    assertThat(switched.settings).isEqualTo(GroupAdmissionSettings())
    assertThat(switched.records).isEmpty()
    assertThat(switched.intents).isEmpty()
  }

  @Test
  fun `restore retains policy but requires review of completed grants and clears all operation intents`() {
    val values = values()
    values.update(account) {
      it.copy(
        settings = GroupAdmissionSettings(GroupAdmissionMode.ASK_EVERY_TIME),
        records = mapOf(
          group to AdmissionRecord(membership, AdmissionStatus.LOCAL_CONSENT),
          "other" to AdmissionRecord(membership, AdmissionStatus.AUTOMATIC, pendingWrite = "unfinished")
        ),
        intents = mapOf(group to AdmissionIntent("operation", ConsentKind.JOIN, null, 6))
      )
    }

    values.onPostBackupRestore()

    val restored = values().read(account)
    assertThat(restored.settings.mode).isEqualTo(GroupAdmissionMode.ASK_EVERY_TIME)
    assertThat(restored.isHeld(group)).isTrue()
    assertThat(restored.isHeld("other")).isTrue()
    assertThat(restored.intents).isEmpty()
    assertThat(restored.records.values.all { it.pendingWrite == null }).isTrue()
  }

  @Test
  fun `a backup with an independently exported membership cannot reuse a later completed grant`() {
    // The key-value snapshot can be newer than the group-table snapshot. Do not assume they describe the same add.
    values().update(account) {
      it.copy(records = mapOf(group to AdmissionRecord(AdmissionMembership(MembershipKind.FULL, "member:9", 9, 9), AdmissionStatus.EXISTING_GROUP)))
    }

    values().onPostBackupRestore()

    assertThat(values().read(account).isHeld(group)).isTrue()
  }

  @Test
  fun `malformed granted membership cannot enable group use`() {
    values().update(account) { it.copy(records = mapOf(group to AdmissionRecord(membership, AdmissionStatus.AUTOMATIC))) }
    val document = JSONObject(store().getString(GroupAdmissionValues.KEY, null)!!)
    document.getJSONObject("records").getJSONObject(group).put("episode", "member:12")
    store().beginWrite().putString(GroupAdmissionValues.KEY, document.toString()).commitAndWaitForPersistence()

    assertThat(values().read(account).isHeld(group)).isTrue()
    assertThat(values().read(account).settings.mode).isEqualTo(GroupAdmissionMode.ASK_EVERY_TIME)
  }

  @Test
  fun `local backup includes the whole account-bound document`() {
    assertThat(values().keysToIncludeInBackup).isEqualTo(listOf(GroupAdmissionValues.KEY))
  }

  private fun store() = KeyValueStore(storage) { it.run() }
  private fun values() = GroupAdmissionValues(store())
}

/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.groups.v2.admission

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.ServiceId.PNI
import org.signal.core.ui.permissions.Permissions
import org.signal.core.util.ThreadUtil
import org.signal.core.util.groups.GroupChangeFailedException
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.storage.keyvalue.KeyValuePersistenceException
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storageservice.storage.protos.groups.local.DecryptedApproveMember
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroup
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroupChange
import org.signal.storageservice.storage.protos.groups.local.DecryptedMember
import org.signal.storageservice.storage.protos.groups.local.DecryptedPendingMember
import org.signal.storageservice.storage.protos.groups.local.DecryptedRequestingMember
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.RecipientDatabaseTestUtils
import org.thoughtcrime.securesms.database.RecipientTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.groupRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.groups.v2.processing.GroupStateDiff
import org.thoughtcrime.securesms.keyvalue.AccountValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.thoughtcrime.securesms.testutil.SnapshotKeyValuePersistentStorage
import org.whispersystems.signalservice.api.push.ServiceIds
import java.util.Optional
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class GroupAdmissionTest {
  private val selfAci = ACI.from(UUID.fromString("11111111-1111-4111-8111-111111111111"))
  private val selfPni = PNI.from(UUID.fromString("22222222-2222-4222-8222-222222222222"))
  private val alice = ACI.from(UUID.fromString("33333333-3333-4333-8333-333333333333"))
  private val self = ServiceIds(selfAci, selfPni)
  private val masterKey = GroupMasterKey(ByteArray(32) { 1 })
  private val groupId = GroupId.v2(masterKey)
  private val storage = SnapshotKeyValuePersistentStorage()
  private val values = GroupAdmissionValues(KeyValueStore(storage) { it.run() })
  private val accountValues = mockk<AccountValues>()
  private val groups = mockk<GroupTable>()
  private val recipients = mockk<RecipientTable>()
  private var current: DecryptedGroup? = null
  private var previousAssertions = true

  @Before
  fun setUp() {
    previousAssertions = ThreadUtil.enforceAssertions
    ThreadUtil.enforceAssertions = false
    mockkObject(SignalStore)
    mockkStatic(SignalStore::class)
    every { SignalStore.isInitialized } returns true
    every { SignalStore.account } returns accountValues
    every { SignalStore.groupAdmission } returns values
    every { accountValues.aci } returns selfAci
    every { accountValues.getServiceIds() } returns self

    mockkObject(SignalDatabase)
    every { SignalDatabase.groups } returns groups
    every { SignalDatabase.recipients } returns recipients
    every { recipients.getByGroupId(any()) } returns Optional.empty()
    every { groups.getGroup(groupId) } answers { current?.let { groupRecord(masterKey, it) } ?: Optional.empty() }
    every { groups.getAllGroupV2Ids() } returns emptyList()

    mockkStatic(AppDependencies::class)
    every { AppDependencies.application } returns ApplicationProvider.getApplicationContext<Application>()

    mockkObject(Recipient)
    mockkStatic(Recipient::class)
    every { Recipient.externalPush(any<ServiceId>()) } returns RecipientDatabaseTestUtils.createRecipient(resolved = true, serviceId = alice)
    every { Recipient.externalGroupExact(any()) } answers { RecipientDatabaseTestUtils.createRecipient(resolved = true, groupId = firstArg(), groupType = RecipientTable.RecipientType.GV2, profileSharing = true) }
  }

  @After
  fun tearDown() {
    ThreadUtil.enforceAssertions = previousAssertions
    unmockkAll()
  }

  @Test
  fun `ordinary default processing introduces no ledger or holds`() {
    val full = full(5)
    observe(null, full, added(full))
    assertThat(GroupAdmission.isHeld(groupId)).isFalse()
    assertThat(values.read(selfAci.toString()).records.isEmpty()).isTrue()
  }

  @Test
  fun `allowed full membership stays held until the actual group write completes`() {
    settings(GroupAdmissionMode.SELECTED_PEOPLE, setOf(alice.toString()))
    val full = full(5)
    val pending = GroupAdmission.prepareStateUpdate(groupId, self, GroupStateDiff(null, full, added(full)), full)

    assertThat(GroupAdmission.isHeld(groupId)).isTrue()
    assertThat(GroupAdmission.shouldEnableProfileSharing(groupId)).isFalse()
    current = full
    GroupAdmission.completeStateUpdate(pending)

    assertThat(GroupAdmission.isHeld(groupId)).isFalse()
    assertThat(GroupAdmission.shouldEnableProfileSharing(groupId)).isTrue()
  }

  @Test
  fun `failed completion cannot publish an automatic grant`() {
    settings(GroupAdmissionMode.SELECTED_PEOPLE, setOf(alice.toString()))
    val full = full(5)
    val pending = GroupAdmission.prepareStateUpdate(groupId, self, GroupStateDiff(null, full, added(full)), full)
    current = full
    storage.nextWriteFailure = IllegalStateException("disk full")

    assertThrows(KeyValuePersistenceException::class.java) { GroupAdmission.completeStateUpdate(pending) }

    assertThat(GroupAdmission.isHeld(groupId)).isTrue()
    assertThat(GroupAdmissionValues(KeyValueStore(storage) { it.run() }).read(selfAci.toString()).isHeld(groupId.toString())).isTrue()
  }

  @Test
  fun `an allowed inviter's pending PNI invitation remains held after a remote acceptance`() {
    settings(GroupAdmissionMode.SELECTED_PEOPLE, setOf(alice.toString()))
    val invited = pending()
    observe(null, invited, null)
    assertThat(GroupAdmission.isHeld(groupId)).isTrue()

    val full = full(6)
    observe(invited, full, DecryptedGroupChange(revision = 6, editorServiceIdBytes = selfPni.toByteString(), promotePendingPniAciMembers = full.members))

    assertThat(GroupAdmission.isHeld(groupId)).isTrue()
    assertThat(GroupAdmission.shouldEnableProfileSharing(groupId)).isFalse()
  }

  @Test
  fun `an explicit PNI invitation acceptance authorizes only its confirmed promotion`() {
    settings(GroupAdmissionMode.ASK_EVERY_TIME)
    val invited = pending()
    observe(null, invited, null)
    val consent = GroupAdmission.beginAccept(groupId, GroupAdmission.consentToken(groupId))
    GroupAdmission.beforeMutation(groupId, 6)
    val full = full(6)
    observe(invited, full, DecryptedGroupChange(revision = 6, editorServiceIdBytes = selfPni.toByteString(), promotePendingPniAciMembers = full.members))
    GroupAdmission.endOperation(consent)

    assertThat(GroupAdmission.isHeld(groupId)).isFalse()
    assertThat(GroupAdmission.shouldEnableProfileSharing(groupId)).isTrue()
  }

  @Test
  fun `accepting a full membership does not authorize a re-add even when both snapshots contain self`() {
    settings(GroupAdmissionMode.ASK_EVERY_TIME)
    val first = full(5)
    observe(null, first, added(first))
    val oldToken = GroupAdmission.consentToken(groupId)
    val consent = GroupAdmission.beginAccept(groupId, oldToken)
    GroupAdmission.endOperation(consent)
    assertThat(GroupAdmission.isHeld(groupId)).isFalse()

    val readded = full(10)
    observe(first, readded, added(readded))

    assertThat(GroupAdmission.isHeld(groupId)).isTrue()
    assertThat(GroupAdmission.consentToken(groupId)).isNotEqualTo(oldToken)
    assertThrows(GroupChangeFailedException::class.java) { GroupAdmission.beginAccept(groupId, oldToken) }
  }

  @Test
  fun `a held membership needs the token from the displayed request`() {
    settings(GroupAdmissionMode.ASK_EVERY_TIME)
    val full = full(5)
    observe(null, full, added(full))

    assertThrows(GroupChangeFailedException::class.java) { GroupAdmission.beginAccept(groupId, null) }
    assertThat(GroupAdmission.isHeld(groupId)).isTrue()
  }

  @Test
  fun `turning restrictions off does not implicitly accept requests already held`() {
    settings(GroupAdmissionMode.ASK_EVERY_TIME)
    val full = full(5)
    observe(null, full, added(full))

    GroupAdmission.setMode(GroupAdmissionMode.SIGNAL_DEFAULT)

    assertThat(GroupAdmission.getSettings().mode).isEqualTo(GroupAdmissionMode.SIGNAL_DEFAULT)
    assertThat(GroupAdmission.isHeld(groupId)).isTrue()
  }

  @Test
  fun `enabling restrictions preserves accepted full groups but never releases held or pending groups`() {
    val heldKey = GroupMasterKey(ByteArray(32) { 2 })
    val heldId = GroupId.v2(heldKey)
    val pendingKey = GroupMasterKey(ByteArray(32) { 3 })
    val pendingId = GroupId.v2(pendingKey)
    current = full(5)
    every { groups.getAllGroupV2Ids() } returns listOf(groupId, heldId, pendingId)
    every { groups.getGroup(heldId) } returns groupRecord(heldKey, full(6))
    every { groups.getGroup(pendingId) } returns groupRecord(pendingKey, pending())
    mockkStatic(RecipientUtil::class)
    // Old sharing/history can still say yes; the independent held decision must take precedence.
    every { RecipientUtil.isMessageRequestAccepted(any<Recipient>()) } returns true
    values.update(selfAci.toString()) {
      it.copy(records = mapOf(heldId.toString() to AdmissionRecord(GroupAdmissionEvidence.membership(full(6), self), AdmissionStatus.HELD)))
    }

    GroupAdmission.setMode(GroupAdmissionMode.ASK_EVERY_TIME)

    assertThat(GroupAdmission.isHeld(groupId)).isFalse()
    assertThat(GroupAdmission.isHeld(heldId)).isTrue()
    assertThat(GroupAdmission.isHeld(pendingId)).isTrue()
  }

  @Test
  fun `successful direct join can confirm the exact membership after snapshot-only fetch`() {
    settings(GroupAdmissionMode.ASK_EVERY_TIME)
    val consent = GroupAdmission.beginJoin(groupId, false)
    GroupAdmission.beforeMutation(groupId, 5)
    val full = full(5)
    observe(null, full, null)
    assertThat(GroupAdmission.isHeld(groupId)).isTrue()

    GroupAdmission.confirmLocalMembership(consent, full, added(full, selfAci))
    GroupAdmission.endOperation(consent)

    assertThat(GroupAdmission.isHeld(groupId)).isFalse()
  }

  @Test
  fun `unverified placeholder cannot confirm an already-member join`() {
    settings(GroupAdmissionMode.ASK_EVERY_TIME)
    val consent = GroupAdmission.beginJoin(groupId, false)

    GroupAdmission.confirmExistingMembership(consent, full(0).copy(isPlaceholderGroup = true))

    assertThat(GroupAdmission.isHeld(groupId)).isTrue()
  }

  @Test
  fun `a failed join followed by an ambiguous server update still requires review`() {
    settings(GroupAdmissionMode.ASK_EVERY_TIME)
    val consent = GroupAdmission.beginJoin(groupId, false)
    GroupAdmission.beforeMutation(groupId, 5)
    GroupAdmission.endOperation(consent)
    val full = full(5)
    observe(null, full, added(full, selfAci))

    assertThat(GroupAdmission.isHeld(groupId)).isTrue()
  }

  @Test
  fun `request consent retains the real timestamp and permits later administrator approval`() {
    settings(GroupAdmissionMode.ASK_EVERY_TIME)
    val consent = GroupAdmission.beginJoin(groupId, true)
    GroupAdmission.beforeMutation(groupId, 5)
    val requested = DecryptedGroup(revision = 5, requestingMembers = listOf(DecryptedRequestingMember(aciBytes = selfAci.toByteString(), timestamp = 1234)))
    val requestChange = DecryptedGroupChange(revision = 5, editorServiceIdBytes = selfAci.toByteString(), newRequestingMembers = requested.requestingMembers)
    current = requested
    GroupAdmission.confirmLocalMembership(consent, requested, requestChange)
    GroupAdmission.endOperation(consent)
    assertThat(values.read(selfAci.toString()).records[groupId.toString()]?.membership?.episode).isEqualTo("request:1234")

    val approved = full(8)
    observe(requested, approved, DecryptedGroupChange(revision = 8, editorServiceIdBytes = alice.toByteString(), promoteRequestingMembers = listOf(DecryptedApproveMember(aciBytes = selfAci.toByteString()))))

    assertThat(GroupAdmission.isHeld(groupId)).isFalse()
  }

  @Test
  fun `contacts permission loss prevents automatic admission despite a cached contact URI`() {
    settings(GroupAdmissionMode.DEVICE_CONTACTS)
    every { Recipient.externalPush(any<ServiceId>()) } returns RecipientDatabaseTestUtils.createRecipient(resolved = true, serviceId = alice, systemContactUri = "content://contacts/1")
    mockkStatic(Permissions::class)
    every { Permissions.hasAll(any(), Manifest.permission.READ_CONTACTS) } returns false
    val full = full(5)
    observe(null, full, added(full))

    assertThat(GroupAdmission.isHeld(groupId)).isTrue()
  }

  private fun settings(mode: GroupAdmissionMode, people: Set<String> = emptySet()) {
    values.update(selfAci.toString()) { it.copy(settings = GroupAdmissionSettings(mode, people)) }
  }

  private fun full(revision: Int) = DecryptedGroup(revision = revision, members = listOf(DecryptedMember(aciBytes = selfAci.toByteString(), joinedAtRevision = revision)))

  private fun pending() = DecryptedGroup(revision = 5, pendingMembers = listOf(DecryptedPendingMember(serviceIdBytes = selfPni.toByteString(), addedByAci = alice.toByteString(), timestamp = 100)))

  private fun added(group: DecryptedGroup, editor: ACI = alice) = DecryptedGroupChange(revision = group.revision, editorServiceIdBytes = editor.toByteString(), newMembers = group.members)

  private fun observe(previous: DecryptedGroup?, updated: DecryptedGroup, change: DecryptedGroupChange?) {
    val prepared = GroupAdmission.prepareStateUpdate(groupId, self, GroupStateDiff(previous, updated, change), updated)
    current = updated
    GroupAdmission.completeStateUpdate(prepared)
  }
}

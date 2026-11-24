package org.thoughtcrime.securesms.calls.permissions

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.signal.core.models.ServiceId
import org.signal.core.models.recipients.RecipientId
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.RecipientTable
import org.thoughtcrime.securesms.database.SQLiteDatabase
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.GroupRecord
import org.thoughtcrime.securesms.database.model.RecipientRecord
import org.thoughtcrime.securesms.keyvalue.AccountValues
import org.thoughtcrime.securesms.keyvalue.CallPermissionValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Runs the production facade and session engine while replacing only disk and recipient lookup. */
internal class CallPermissionTestEnvironment {
  val accountId = ServiceId.ACI.from(UUID.fromString("10000000-0000-0000-0000-000000000001"))
  val pniId = ServiceId.PNI.from(UUID.fromString("20000000-0000-0000-0000-000000000001"))
  private val account: AccountValues = mockk()
  private val values: CallPermissionValues = mockk()
  private val recipients = HashMap<RecipientId, Recipient>()
  private val recipientTable: RecipientTable = mockk()
  private val groupTable: GroupTable = mockk()
  private val groupRecords = HashMap<RecipientId, GroupRecord>()
  private val database: SQLiteDatabase = mockk()
  private val recipientTransactionLock = Any()
  private val recipientRemaps = HashMap<RecipientId, RecipientId>()
  private val nextPolicyRead = AtomicReference<(() -> Unit)?>(null)
  private val nextRecipientTransaction = AtomicReference<(() -> Unit)?>(null)
  @Volatile private var state = CallPolicyState(account = accountId.toString())

  fun setUp() {
    mockkStatic(SignalStore::class, Recipient::class, SignalDatabase::class)
    // Kotlin calls the companion directly; mocking only the Java static bridge leaves
    // Recipient.resolved executing its real recipient-cache lookup during stub recording.
    mockkObject(SignalStore.Companion, Recipient.Companion, SignalDatabase.Companion)
    every { SignalStore.isInitialized } returns true
    every { SignalStore.account } returns account
    every { SignalStore.callPermissions } returns values
    every { account.aci } returns accountId
    every { account.pni } returns pniId
    every { account.deviceId } returns 1
    every { account.isRegistered } returns true
    every { account.isUnauthorizedReceived } returns false
    every { account.isMultiDevice } returns false
    every { account.isPrimaryDevice } returns true
    every { values.read() } answers {
      val snapshot = state
      nextPolicyRead.getAndSet(null)?.invoke()
      snapshot
    }
    every { values.update(any()) } answers {
      state = firstArg<(CallPolicyState) -> CallPolicyState>()(state)
      state
    }
    every { Recipient.resolved(any()) } answers { recipient(firstArg<RecipientId>().toLong()) }
    every { SignalDatabase.recipients } returns recipientTable
    every { SignalDatabase.groups } returns groupTable
    every { groupTable.getGroup(any<RecipientId>()) } answers { Optional.ofNullable(groupRecords[firstArg<RecipientId>()]) }
    every { SignalDatabase.runInTransaction<Unit>(any()) } answers {
      nextRecipientTransaction.getAndSet(null)?.invoke()
      synchronized(recipientTransactionLock) {
        firstArg<(SQLiteDatabase) -> Unit>()(database)
      }
    }
    every { recipientTable.getRecord(any()) } answers {
      check(Thread.holdsLock(recipientTransactionLock))
      var canonical = firstArg<RecipientId>()
      while (recipientRemaps[canonical] != null) canonical = recipientRemaps.getValue(canonical)
      val recipient = recipient(canonical.toLong())
      mockk<RecipientRecord> {
        every { id } returns canonical
        every { recipientType } returns if (recipient.isPushV2Group) RecipientTable.RecipientType.GV2 else RecipientTable.RecipientType.INDIVIDUAL
      }
    }
    CallPermissions.setRevocationListener(Runnable {})
    CallPermissions.endAllSessions()
  }

  fun tearDown() {
    CallPermissions.setRevocationListener(Runnable {})
    CallPermissions.endAllSessions()
    unmockkObject(SignalStore.Companion, Recipient.Companion, SignalDatabase.Companion)
    unmockkStatic(SignalStore::class, Recipient::class, SignalDatabase::class)
  }

  /** Delivers the old immutable read result after an interleaved policy write has completed. */
  fun onNextPolicyRead(action: () -> Unit) { nextPolicyRead.set(action) }

  fun onNextRecipientTransaction(action: () -> Unit) { nextRecipientTransaction.set(action) }

  /** Models the recipient database's single-writer transaction, retaining a deliberately stale UI cache. */
  fun mergeRecipients(primary: RecipientId, secondary: RecipientId) {
    SignalDatabase.runInTransaction {
      CallPermissions.onRecipientMerge(primary, secondary)
      recipientRemaps[secondary] = primary
    }
  }

  fun storedPolicy(): CallPolicyState = state

  fun group(id: Long): Recipient {
    val recipient = recipient(id)
    every { recipient.isIndividual } returns false
    every { recipient.isPushV2Group } returns true
    every { recipient.isGroup } returns true
    val record = mockk<GroupRecord> {
      every { isActive } returns true
      every { isAnnouncementGroup } returns false
      every { memberLevel(any()) } returns GroupTable.MemberLevel.FULL_MEMBER
    }
    // Keep the lookup key outside MockK's recording scope. Recipient.id is itself mocked;
    // nesting that getter inside a getGroup stub can record a matcher instead of its stored ID.
    groupRecords[recipient.id] = record
    check(Recipient.resolved(recipient.id) === recipient)
    check(recipient.isPushV2Group && !recipient.isIndividual)
    check(SignalDatabase.groups.getGroup(recipient.id).orElse(null) === record)
    check(record.isActive)
    return recipient
  }

  fun recipient(id: Long): Recipient {
    val recipientId = RecipientId.from(id)
    val serviceId = ServiceId.ACI.from(UUID(0, id))
    return recipients.getOrPut(recipientId) {
      mockk<Recipient> {
        every { this@mockk.id } returns recipientId
        every { isIndividual } returns true
        every { isSelf } returns false
        every { isBlocked } returns false
        every { isPushV2Group } returns false
        every { isGroup } returns false
        every { isCallLink } returns false
        every { aci } returns Optional.of(serviceId)
        every { pni } returns Optional.empty()
        every { resolve() } answers { this@mockk }
      }
    }
  }
}

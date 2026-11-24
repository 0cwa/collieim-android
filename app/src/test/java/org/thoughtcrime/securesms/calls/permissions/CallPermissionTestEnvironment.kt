package org.thoughtcrime.securesms.calls.permissions

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.signal.core.models.ServiceId
import org.signal.core.models.recipients.RecipientId
import org.thoughtcrime.securesms.keyvalue.AccountValues
import org.thoughtcrime.securesms.keyvalue.CallPermissionValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import java.util.Optional
import java.util.UUID

/** Runs the production facade and session engine while replacing only disk and recipient lookup. */
internal class CallPermissionTestEnvironment {
  val accountId = ServiceId.ACI.from(UUID.fromString("10000000-0000-0000-0000-000000000001"))
  val pniId = ServiceId.PNI.from(UUID.fromString("20000000-0000-0000-0000-000000000001"))
  private val account: AccountValues = mockk()
  private val values: CallPermissionValues = mockk()
  private val recipients = HashMap<RecipientId, Recipient>()
  private var state = CallPolicyState(account = accountId.toString())

  fun setUp() {
    mockkStatic(SignalStore::class, Recipient::class)
    mockkObject(SignalStore.Companion)
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
    every { values.read() } answers { state }
    every { values.update(any()) } answers {
      state = firstArg<(CallPolicyState) -> CallPolicyState>()(state)
      state
    }
    every { Recipient.resolved(any()) } answers { recipient(firstArg<RecipientId>().toLong()) }
    CallPermissions.setRevocationListener(Runnable {})
    CallPermissions.endAllSessions()
  }

  fun tearDown() {
    CallPermissions.setRevocationListener(Runnable {})
    CallPermissions.endAllSessions()
    unmockkObject(SignalStore.Companion)
    unmockkStatic(SignalStore::class, Recipient::class)
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
        every { isCallLink } returns false
        every { aci } returns Optional.of(serviceId)
        every { pni } returns Optional.empty()
        every { resolve() } answers { this@mockk }
      }
    }
  }
}

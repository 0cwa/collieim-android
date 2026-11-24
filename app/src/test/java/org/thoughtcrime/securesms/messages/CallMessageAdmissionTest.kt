package org.thoughtcrime.securesms.messages

import android.app.Application
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.calls.permissions.CallPermission
import org.thoughtcrime.securesms.calls.permissions.CallPermissionTestEnvironment
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.calls.permissions.DirectMessageKind
import org.thoughtcrime.securesms.calls.permissions.GroupCallAdmission
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.ProfileKeySendJob
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.internal.push.CallMessage
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.Envelope
import java.util.UUID

/** The ingress tests count dependency acquisition, not merely the eventual native invocation. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, instrumentedPackages = ["org.signal.ringrtc"])
class CallMessageAdmissionTest {
  private val policy = CallPermissionTestEnvironment()
  private val blockedId = RecipientId.from(9)
  private lateinit var sender: Recipient
  private val envelope = Envelope(clientTimestamp = 1_000, serverTimestamp = 1_000)
  private val metadata = EnvelopeMetadata(ServiceId.ACI.from(UUID(0, 9)), null, 2, true, null, policy.accountId, 1)

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    policy.setUp()
    sender = policy.recipient(9)
    CallPermissions.setOverride(sender, CallPermission.BLOCK)
    mockkStatic(AppDependencies::class, RecipientUtil::class, GroupCallAdmission::class, ProfileKeySendJob::class)
    mockkObject(CallPolicyRejector, GroupCallAdmission)
    every { AppDependencies.signalCallManager } returns mockk(relaxed = true)
    every { RecipientUtil.shareProfileIfFirstSecureMessage(any()) } just Runs
    every { RecipientUtil.isCallRequestAccepted(any<Recipient>()) } returns true
    every { ProfileKeySendJob.createForCall(any(), any()) } returns null
    every { CallPolicyRejector.declineOffer(any(), any(), any(), any(), any()) } just Runs
    every { CallPolicyRejector.recordMissedCall(any(), any(), any(), any()) } just Runs
    every { GroupCallAdmission.admit(any(), any(), any(), any()) } returns null
  }

  @After
  fun tearDown() {
    unmockkObject(CallPolicyRejector, GroupCallAdmission)
    unmockkStatic(AppDependencies::class, RecipientUtil::class, GroupCallAdmission::class, ProfileKeySendJob::class)
    policy.tearDown()
  }

  @Test
  fun `all denied categories avoid acquiring the native manager`() {
    deniedMessages().forEach { process(it) }

    verify(exactly = 0) { AppDependencies.signalCallManager }
    verify(exactly = 0) { RecipientUtil.shareProfileIfFirstSecureMessage(any()) }
  }

  @Test
  fun `another contacts authorized call never opens admission for blocked callers`() {
    check(CallPermissions.beginOutgoing(RecipientId.from(7)))
    check(CallPermissions.bindOutgoing(RecipientId.from(7), 41))
    deniedMessages().forEach { process(it) }

    verify(exactly = 0) { AppDependencies.signalCallManager }
    verify(exactly = 0) { RecipientUtil.shareProfileIfFirstSecureMessage(any()) }
  }

  @Test
  fun `blocked PNI offer does not share a profile`() {
    val pniMetadata = EnvelopeMetadata(metadata.sourceServiceId, null, 2, true, null, policy.pniId, 1)
    CallMessageProcessor.process(sender, envelope, Content(callMessage = deniedMessages().first()), pniMetadata, 1_000)

    verify(exactly = 0) { AppDependencies.signalCallManager }
    verify(exactly = 0) { RecipientUtil.shareProfileIfFirstSecureMessage(any()) }
  }

  @Test
  fun `explicit outgoing call permits only its own continuation despite incoming block`() {
    check(CallPermissions.beginOutgoing(blockedId))
    check(CallPermissions.bindOutgoing(blockedId, 41))
    process(CallMessage(iceUpdate = listOf(CallMessage.IceUpdate(id = 42, opaque = ByteString.EMPTY))))
    verify(exactly = 0) { AppDependencies.signalCallManager }

    process(CallMessage(iceUpdate = listOf(CallMessage.IceUpdate(id = 41, opaque = ByteString.EMPTY))))
    verify(exactly = 1) { AppDependencies.signalCallManager.receivedIceCandidates(any(), any(), any()) }
  }

  @Test
  fun `authenticated envelope identity must match the resolved recipient`() {
    check(CallPermissions.beginOutgoing(blockedId))
    check(CallPermissions.bindOutgoing(blockedId, 41))
    val wrongSender = EnvelopeMetadata(ServiceId.ACI.from(UUID(0, 7)), null, 2, true, null, metadata.destinationServiceId, 1)
    val ice = CallMessage(iceUpdate = listOf(CallMessage.IceUpdate(id = 41, opaque = ByteString.EMPTY)))
    CallMessageProcessor.process(sender, envelope, Content(callMessage = ice), wrongSender, 1_000)

    verify(exactly = 0) { AppDependencies.signalCallManager }
  }

  @Test
  fun `existing trust refusal is managed and releases its admission slot`() {
    CallPermissions.setOverride(sender, CallPermission.ALLOW)
    every { RecipientUtil.isCallRequestAccepted(any<Recipient>()) } returns false
    process(deniedMessages().first())

    verify(exactly = 0) { AppDependencies.signalCallManager }
    verify(exactly = 0) { RecipientUtil.shareProfileIfFirstSecureMessage(any()) }
    verify(exactly = 1) { CallPolicyRejector.recordMissedCall(sender, 41, 1_000, false) }
    assertNotNull(CallPermissions.admitDirect(RecipientId.from(7), 42, 1, DirectMessageKind.OFFER, 0))
  }

  @Test
  fun `trusted PNI handshake preserves profile reply without constructing RingRTC`() {
    CallPermissions.setOverride(sender, CallPermission.ALLOW)
    val pniMetadata = EnvelopeMetadata(metadata.sourceServiceId, null, 2, true, null, policy.pniId, 1)
    CallMessageProcessor.process(sender, envelope, Content(callMessage = deniedMessages().first()), pniMetadata, 1_000)

    verify(exactly = 0) { AppDependencies.signalCallManager }
    verify(exactly = 1) { RecipientUtil.shareProfileIfFirstSecureMessage(sender) }
    verify(exactly = 1) { ProfileKeySendJob.createForCall(sender, false) }
    verify(exactly = 1) { CallPolicyRejector.recordMissedCall(sender, 41, 1_000, false) }
  }

  @Test
  fun `an old account envelope is not newly admitted after account replacement`() {
    CallPermissions.setOverride(sender, CallPermission.ALLOW)
    val oldAccount = EnvelopeMetadata(metadata.sourceServiceId, null, 2, true, null, ServiceId.ACI.from(UUID(0, 99)), 1)
    CallMessageProcessor.process(sender, envelope, Content(callMessage = deniedMessages().first()), oldAccount, 1_000)

    verify(exactly = 0) { AppDependencies.signalCallManager }
    verify(exactly = 0) { RecipientUtil.shareProfileIfFirstSecureMessage(any()) }
  }

  private fun process(message: CallMessage) = CallMessageProcessor.process(sender, envelope, Content(callMessage = message), metadata, 1_000)

  private fun deniedMessages() = listOf(
    CallMessage(offer = CallMessage.Offer(id = 41, type = CallMessage.Offer.Type.OFFER_AUDIO_CALL, opaque = ByteString.EMPTY)),
    CallMessage(answer = CallMessage.Answer(id = 41, opaque = ByteString.EMPTY)),
    CallMessage(iceUpdate = listOf(CallMessage.IceUpdate(id = 41, opaque = ByteString.EMPTY))),
    CallMessage(hangup = CallMessage.Hangup(id = 41)),
    CallMessage(busy = CallMessage.Busy(id = 41)),
    CallMessage(opaque = CallMessage.Opaque(data_ = ByteString.EMPTY))
  )
}

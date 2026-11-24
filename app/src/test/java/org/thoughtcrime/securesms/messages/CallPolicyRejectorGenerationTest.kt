package org.thoughtcrime.securesms.messages

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.calls.permissions.CallMessageQueueReceipt
import org.thoughtcrime.securesms.calls.permissions.CallPermissionTestEnvironment
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.crypto.SealedSenderAccessUtil
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.api.messages.calls.HangupMessage
import org.whispersystems.signalservice.api.messages.calls.SignalServiceCallMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.internal.push.CallMessage
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.Envelope
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, instrumentedPackages = ["org.signal.ringrtc"])
class CallPolicyRejectorGenerationTest {
  private val policy = CallPermissionTestEnvironment()
  private val transport: SignalServiceMessageSender = mockk()
  private val metadata = EnvelopeMetadata(ServiceId.ACI.from(UUID(0, 7)), null, 2, true, null, policy.accountId, 1)
  private lateinit var sender: Recipient

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    policy.setUp()
    sender = policy.recipient(7)
    mockkStatic(AppDependencies::class, RecipientUtil::class, SealedSenderAccessUtil::class)
    every { AppDependencies.signalServiceMessageSender } returns transport
    every { AppDependencies.signalCallManager } throws AssertionError("Refusal must not construct RingRTC")
    every { RecipientUtil.isCallRequestAccepted(any<Recipient>()) } returns true
    every { RecipientUtil.shareProfileIfFirstSecureMessage(any()) } throws AssertionError("Refusal must not share a profile")
    every { SealedSenderAccessUtil.getSealedSenderAccessFor(any<Recipient>()) } returns null
  }

  @After
  fun tearDown() {
    unmockkStatic(AppDependencies::class, RecipientUtil::class, SealedSenderAccessUtil::class)
    policy.tearDown()
  }

  @Test
  fun `revoked queued offer cannot acquire a new generation for refusal side effects`() {
    val receipt = CallMessageQueueReceipt.capture()
    assertNotNull(receipt.queueDelayMillis())
    CallPermissions.setAllCallingDisabled(true)
    CallPermissions.setAllCallingDisabled(false)

    // Model revocation after dequeue validation but before the refusal is scheduled.
    CallPolicyRejector.declineOffer(sender, metadata, 41, 0, expectedGeneration = receipt.generation)

    verify(exactly = 0) { RecipientUtil.isCallRequestAccepted(any<Recipient>()) }
    verify(exactly = 0) { AppDependencies.signalServiceMessageSender }
    verify(exactly = 0) { AppDependencies.signalCallManager }
  }

  @Test
  fun `new disabled incoming offer is refused through Signal transport without native or profile work`() {
    CallPermissions.setAllCallingDisabled(true)
    val receipt = CallMessageQueueReceipt.capture()
    assertNotNull(receipt.queueDelayMillis())
    val sent = CountDownLatch(1)
    val message = AtomicReference<SignalServiceCallMessage>()
    every { transport.sendCallMessage(any<SignalServiceAddress>(), any(), any()) } answers {
      message.set(thirdArg())
      sent.countDown()
    }
    val offer = CallMessage(offer = CallMessage.Offer(id = 41, type = CallMessage.Offer.Type.OFFER_AUDIO_CALL, opaque = ByteString.EMPTY))

    CallMessageProcessor.process(sender, Envelope(clientTimestamp = 1_000, serverTimestamp = 1_000), Content(callMessage = offer), metadata, 1_000, receipt)

    assertTrue("Managed refusal did not reach the Signal transport", sent.await(5, TimeUnit.SECONDS))
    assertEquals(41L, message.get().hangupMessage.get().id)
    assertEquals(HangupMessage.Type.NORMAL, message.get().hangupMessage.get().type)
    assertEquals(2, message.get().destinationDeviceId.get())
    verify(exactly = 0) { AppDependencies.signalCallManager }
    verify(exactly = 0) { RecipientUtil.shareProfileIfFirstSecureMessage(any()) }
  }
}

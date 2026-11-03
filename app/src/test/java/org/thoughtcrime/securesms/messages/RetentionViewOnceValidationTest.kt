package org.thoughtcrime.securesms.messages

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.MessageTypes
import org.thoughtcrime.securesms.keyvalue.RateLimitValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import org.thoughtcrime.securesms.util.RemoteConfig
import org.thoughtcrime.securesms.util.MediaUtil
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.Envelope
import org.whispersystems.signalservice.internal.push.SyncMessage

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class RetentionViewOnceValidationTest {
  @get:Rule val recipients = RecipientTestRule()

  @Test fun `incoming view once validation rejects malformed and retains valid media`() {
    val peer = Recipient.resolved(recipients.createRecipient("Bob"))
    val cache = mockk<BatchCache>(relaxed = true)
    every { cache.getGroupInfo(any()) } returns (null to null)
    every { recipients.signalStore.settings.isKeepViewOnceMessages } returns true
    every { recipients.signalStore.settings.isTypingIndicatorsEnabled } returns false
    every { RemoteConfig.maxAttachmentCount } returns 32
    val metadata = EnvelopeMetadata(peer.requireAci(), null, 1, true, null, recipients.selfAci, 0)
    val context = ApplicationProvider.getApplicationContext<Application>()
    val invalid = DataMessage(timestamp = 7000L, isViewOnce = true, body = "malformed")
    DataMessageProcessor.process(context, peer, peer, Envelope(clientTimestamp = 7000L, serverTimestamp = 7000L), Content(dataMessage = invalid), metadata, 7001L, null, null, cache)
    val rejected = SignalDatabase.messages.getMessageFor(7000L, peer.id)
    assertNotNull(rejected)
    assertEquals(true, MessageTypes.isInvalidMessageType(rejected!!.type))

    val pointer = AttachmentPointer(cdnKey = "test", cdnNumber = 3, key = ByteArray(64).toByteString(), contentType = "image/jpeg", size = 1)
    val valid = DataMessage(timestamp = 8000L, isViewOnce = true, attachments = listOf(pointer))
    DataMessageProcessor.process(context, peer, peer, Envelope(clientTimestamp = 8000L, serverTimestamp = 8000L), Content(dataMessage = valid), metadata, 8001L, null, null, cache)
    val accepted = SignalDatabase.messages.getMessageFor(8000L, peer.id)
    assertNotNull(accepted)
    assertEquals(false, accepted!!.isViewOnce)
    assertEquals(1, SignalDatabase.attachments.getAttachmentsForMessage(accepted.id).size)
  }

  @Test fun `invalid sent view once is not stored but valid sent view once is retained`() {
    val peer = Recipient.resolved(recipients.createRecipient("Alice"))
    val self = Recipient.resolved(recipients.self)
    val cache = mockk<BatchCache>(relaxed = true)
    every { recipients.signalStore.settings.isKeepViewOnceMessages } returns true
    every { RemoteConfig.maxAttachmentCount } returns 32
    every { SignalStore.rateLimit } returns mockk<RateLimitValues> { every { needsRecaptcha() } returns false }
    val metadata = EnvelopeMetadata(recipients.selfAci, null, 1, true, null, recipients.selfAci, 0)
    val context = ApplicationProvider.getApplicationContext<Application>()

    fun sync(timestamp: Long, data: DataMessage) {
      val sent = SyncMessage.Sent(destinationServiceId = peer.requireAci().toString(), timestamp = timestamp, message = data)
      SyncMessageProcessor.process(context, self, peer, Envelope(clientTimestamp = timestamp, serverTimestamp = timestamp), Content(syncMessage = SyncMessage(sent = sent)), metadata, null, cache)
    }

    val invalidPointer = AttachmentPointer(cdnKey = "test", cdnNumber = 3, key = ByteArray(64).toByteString(), contentType = "application/pdf", size = 1)
    sync(5000L, DataMessage(timestamp = 5000L, isViewOnce = true, attachments = listOf(invalidPointer)))
    assertNull(SignalDatabase.messages.getMessageFor(5000L, recipients.self))

    sync(5500L, DataMessage(timestamp = 5500L, isViewOnce = true))
    assertNull(SignalDatabase.messages.getMessageFor(5500L, recipients.self))
    every { recipients.signalStore.settings.isKeepViewOnceMessages } returns false
    sync(5750L, DataMessage(timestamp = 5750L, isViewOnce = true))
    assertNull(SignalDatabase.messages.getMessageFor(5750L, recipients.self))
    verify(exactly = 3) { recipients.signalStore.misc.lastSyncMessageSeenTimeMs = any() }
    every { recipients.signalStore.settings.isKeepViewOnceMessages } returns true

    val pointer = AttachmentPointer(cdnKey = "test", cdnNumber = 3, key = ByteArray(64).toByteString(), contentType = "image/jpeg", size = 1)
    sync(6000L, DataMessage(timestamp = 6000L, isViewOnce = true, attachments = listOf(pointer)))
    val record = SignalDatabase.messages.getMessageFor(6000L, recipients.self)
    assertNotNull(record)
    assertEquals(false, record!!.isViewOnce)
    assertEquals(1, SignalDatabase.attachments.getAttachmentsForMessage(record.id).size)
  }

  @Test fun `linked device open after disabling retention preserves only previously kept media`() {
    val peer = Recipient.resolved(recipients.createRecipient("Carol"))
    val cache = mockk<BatchCache>(relaxed = true)
    every { cache.getGroupInfo(any()) } returns (null to null)
    every { recipients.signalStore.misc.lastCdsForegroundSyncTime } returns System.currentTimeMillis()
    every { RemoteConfig.cdsForegroundSyncInterval } returns 3_600_000L
    every { recipients.signalStore.settings.isTypingIndicatorsEnabled } returns false
    every { RemoteConfig.maxAttachmentCount } returns 32
    val metadata = EnvelopeMetadata(peer.requireAci(), null, 1, true, null, recipients.selfAci, 0)
    val context = ApplicationProvider.getApplicationContext<Application>()
    val pointer = AttachmentPointer(cdnKey = "test", cdnNumber = 3, key = ByteArray(64).toByteString(), contentType = "image/jpeg", size = 1)

    listOf(true, false).forEachIndexed { index, keep ->
      val timestamp = 9000L + index
      every { recipients.signalStore.settings.isKeepViewOnceMessages } returns keep
      val message = DataMessage(timestamp = timestamp, isViewOnce = true, attachments = listOf(pointer))
      DataMessageProcessor.process(context, peer, peer, Envelope(clientTimestamp = timestamp, serverTimestamp = timestamp), Content(dataMessage = message), metadata, timestamp + 1, null, null, cache)
      val record = SignalDatabase.messages.getMessageFor(timestamp, peer.id)!!
      assertEquals(!keep, record.isViewOnce)

      every { recipients.signalStore.settings.isKeepViewOnceMessages } returns false
      val open = SyncMessage.ViewOnceOpen(senderAci = peer.requireAci().toString(), timestamp = timestamp)
      SyncMessageProcessor.process(context, peer, peer, Envelope(clientTimestamp = timestamp + 10, serverTimestamp = timestamp + 10), Content(syncMessage = SyncMessage(viewOnceOpen = open)), metadata, null, cache)

      val attachment = SignalDatabase.attachments.getAttachmentsForMessage(record.id).single()
      assertEquals(if (keep) "image/jpeg" else MediaUtil.VIEW_ONCE, attachment.contentType)
    }
  }
}

package org.thoughtcrime.securesms.messages

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.util.EarlyMessageCache
import org.thoughtcrime.securesms.util.EarlyMessageCacheEntry
import org.thoughtcrime.securesms.util.RemoteConfig
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.Envelope
import org.whispersystems.signalservice.internal.push.SyncMessage

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class RetentionProcessorTest {
  @get:Rule val recipients = RecipientTestRule()

  @Test fun `retention suppresses message expiry but leaves timer processing unchanged`() {
    val retained = processIncoming("Alice", 3000L, true)
    val ordinary = processIncoming("Bob", 4000L, false)
    assertEquals(0L, retained.first)
    assertEquals(60_000L, ordinary.first)
    assertEquals(60L, retained.second)
    assertEquals(ordinary.second, retained.second)
  }

  @Test fun `explicit timer update is processed while retention is enabled`() {
    val id = recipients.createRecipient("Carol")
    val sender = Recipient.resolved(id)
    val cache = mockk<BatchCache>(relaxed = true)
    every { cache.getGroupInfo(any()) } returns (null to null)
    every { recipients.signalStore.settings.isKeepExpiringMessages } returns true
    every { recipients.signalStore.misc.lastCdsForegroundSyncTime } returns System.currentTimeMillis()
    every { RemoteConfig.cdsForegroundSyncInterval } returns 3_600_000L
    val message = DataMessage(timestamp = 5000L, flags = DataMessage.Flags.EXPIRATION_TIMER_UPDATE.value, expireTimer = 90)
    DataMessageProcessor.process(ApplicationProvider.getApplicationContext(), sender, sender, Envelope(clientTimestamp = 5000L, serverTimestamp = 5000L), Content(dataMessage = message), metadata(sender), 5001L, null, null, cache)
    assertEquals(90L, SignalDatabase.recipients.getExpiresInSeconds(id))
  }

  @Test fun `ignored peer deletion preserves message and missing targets are queued`() {
    val id = recipients.createRecipient("Dave")
    val sender = Recipient.resolved(id)
    recipients.insertIncomingMessage(id)
    every { recipients.signalStore.settings.isIgnoreRemoteDelete } returns true
    every { RemoteConfig.regularDeleteThreshold } returns 86_400L
    every { RemoteConfig.adminDeleteThreshold } returns 86_400L
    val cache = mockk<BatchCache>(relaxed = true)
    val earlyCache = EarlyMessageCache()
    every { AppDependencies.earlyMessageCache } returns earlyCache
    val context = ApplicationProvider.getApplicationContext<Application>()
    val envelope = Envelope(clientTimestamp = 200L, serverTimestamp = 200L)
    val delete = DataMessage(timestamp = 200L, delete = DataMessage.Delete(targetSentTimestamp = 100L))
    DataMessageProcessor.handleRemoteDelete(context, envelope, delete, id, null, cache)
    assertEquals(false, SignalDatabase.messages.getMessageFor(100L, id)!!.isRemoteDelete)

    val missing = DataMessage(timestamp = 201L, delete = DataMessage.Delete(targetSentTimestamp = 99L))
    val early = EarlyMessageCacheEntry(envelope, Content(dataMessage = missing), metadata(sender), 201L)
    assertNull(SignalDatabase.messages.getMessageFor(99L, id))
    DataMessageProcessor.handleRemoteDelete(context, envelope, missing, id, early, cache)
    assertEquals(listOf(early), earlyCache.retrieve(id, 99L))
    verify(exactly = 1) { cache.requiresEarlyMessageProcessing() }
  }

  @Test fun `default remote deletion applies and linked device deletion remains effective`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val cache = mockk<BatchCache>(relaxed = true)
    every { RemoteConfig.regularDeleteThreshold } returns 86_400L
    every { RemoteConfig.adminDeleteThreshold } returns 86_400L
    val envelope = Envelope(clientTimestamp = 200L, serverTimestamp = 200L)
    val message = DataMessage(timestamp = 200L, delete = DataMessage.Delete(targetSentTimestamp = 100L))

    val peer = recipients.createRecipient("Frank")
    recipients.insertIncomingMessage(peer)
    every { recipients.signalStore.settings.isIgnoreRemoteDelete } returns false
    DataMessageProcessor.handleRemoteDelete(context, envelope, message, peer, null, cache)
    assertEquals(true, SignalDatabase.messages.getMessageFor(100L, peer)!!.isRemoteDelete)

    val other = recipients.createRecipient("Grace")
    val sentId = recipients.insertOutgoingMessage(other, sentTimeMillis = 100L)
    every { recipients.signalStore.settings.isIgnoreRemoteDelete } returns true
    DataMessageProcessor.handleRemoteDelete(context, envelope, message, recipients.self, null, cache)
    assertEquals(true, SignalDatabase.messages.getMessageRecord(sentId).isRemoteDelete)
  }

  @Test fun `retaining view once media still records linked device open`() {
    val id = recipients.createRecipient("Erin")
    val sender = Recipient.resolved(id)
    recipients.insertIncomingMessage(id)
    every { recipients.signalStore.settings.isKeepViewOnceMessages } returns true
    val cache = mockk<BatchCache>(relaxed = true)
    val open = SyncMessage.ViewOnceOpen(senderAci = sender.requireAci().toString(), timestamp = 100L)
    SyncMessageProcessor.process(ApplicationProvider.getApplicationContext(), sender, sender, Envelope(clientTimestamp = 200L, serverTimestamp = 200L), Content(syncMessage = SyncMessage(viewOnceOpen = open)), metadata(sender), null, cache)
    verify(exactly = 1) { recipients.signalStore.misc.lastSyncMessageSeenTimeMs = any() }
  }

  private fun processIncoming(name: String, timestamp: Long, keep: Boolean): Pair<Long, Long> {
    val id = recipients.createRecipient(name)
    val sender = Recipient.resolved(id)
    val cache = mockk<BatchCache>(relaxed = true)
    every { cache.getGroupInfo(any()) } returns (null to null)
    every { recipients.signalStore.settings.isKeepExpiringMessages } returns keep
    every { recipients.signalStore.settings.isTypingIndicatorsEnabled } returns false
    val message = DataMessage(timestamp = timestamp, body = "hello", expireTimer = 60)
    DataMessageProcessor.process(ApplicationProvider.getApplicationContext(), sender, sender, Envelope(clientTimestamp = timestamp, serverTimestamp = timestamp), Content(dataMessage = message), metadata(sender), timestamp + 1, null, null, cache)
    val record = SignalDatabase.messages.getMessageFor(timestamp, id)
    assertNotNull(record)
    return record!!.expiresIn to SignalDatabase.recipients.getExpiresInSeconds(id)
  }

  private fun metadata(sender: Recipient) = EnvelopeMetadata(sender.requireAci(), null, 1, true, null, recipients.selfAci, 0)
}

/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.calls.permissions

import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.appsettings.routes.AppSettingsRoute
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.calls.links.CallLinks
import org.thoughtcrime.securesms.calls.log.CallEventCache
import org.thoughtcrime.securesms.calls.runtime.CallRuntime
import org.thoughtcrime.securesms.calls.runtime.RingRtcLibrary
import org.thoughtcrime.securesms.components.settings.app.AppSettingsNavigationActivity
import org.thoughtcrime.securesms.components.settings.conversation.ConversationSettingsActivity
import org.thoughtcrime.securesms.database.CallTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobs.GroupRingCleanupJob
import org.thoughtcrime.securesms.jobs.RefreshCallLinkDetailsJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.linkpreview.LinkPreviewRepository
import org.thoughtcrime.securesms.messages.CallMessageProcessor
import org.thoughtcrime.securesms.messages.MessageHelper
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.service.webrtc.links.CallLinkCredentials
import org.thoughtcrime.securesms.storage.CallLinkRecordProcessor
import org.thoughtcrime.securesms.testing.MessageContentFuzzer
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.whispersystems.signalservice.api.storage.SignalCallLinkRecord
import org.whispersystems.signalservice.api.storage.StorageId
import org.whispersystems.signalservice.internal.push.CallMessage
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.SyncMessage
import org.whispersystems.signalservice.internal.storage.protos.CallLinkRecord
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Clean-process probes, each selected separately by Android Test Orchestrator. Synthetic local
 * accounts never contact the service. Engine acquisition is tested, not call interoperability or
 * call latency. Neither the native mappings nor the application singleton are reset between steps.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class ColdCallRuntimeTest {
  @get:Rule
  val harness = SignalActivityRule(othersCount = 2, createGroup = true, networkEnabled = false)

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val checkpoints = JSONArray()

  @Test
  @CallStartupForTest(onDemand = true)
  fun onDemandColdStartKeepsPassiveRoutesAndDeniedTrafficUnloaded() {
    assertTrue(SignalStore.callPermissions.isOnDemandCallingEnabled())
    assertFalse(CallPermissions.settings().allCallingDisabled)
    assertCold("after-application-and-account-setup")
    exercisePassiveRoutes()
    exerciseDeniedTraffic()
    record("on-demand-cold", onDemand = true, disabled = false)
  }

  @Test
  @CallStartupForTest(onDemand = false, allCallingDisabled = true)
  fun absoluteDisablePreventsLoadingEvenWhenOrdinaryStartupIsEnabled() {
    assertFalse(SignalStore.callPermissions.isOnDemandCallingEnabled())
    assertTrue(CallPermissions.settings().allCallingDisabled)
    assertCold("after-application-and-account-setup")
    exercisePassiveRoutes()
    exerciseDeniedTraffic()

    val manager = AppDependencies.signalCallManager
    val finished = CountDownLatch(1)
    val result = AtomicReference<CallRuntime.Result>()
    manager.withCallEngine(true, { CallPermissions.canStartCall(Recipient.resolved(harness.others.first())) }, { error("Absolute prohibition was bypassed") }) {
      result.set(it)
      finished.countDown()
    }
    assertTrue(finished.await(10, TimeUnit.SECONDS))
    assertEquals(CallRuntime.Result.DENIED, result.get())
    assertCold("denied-explicit-acquisition")
    record("all-calling-disabled", onDemand = false, disabled = true)
  }

  @Test
  @CallStartupForTest(onDemand = true)
  fun authorizedAcquisitionLoadsEngineAndRevocationBlocksFurtherWork() {
    assertCold("before-authorized-acquisition")
    val contact = Recipient.resolved(harness.others.first())
    assertTrue(CallPermissions.beginOutgoing(contact.id))
    val generation = CallPermissions.generation()
    val manager = AppDependencies.signalCallManager
    val actions = AtomicInteger()

    fun acquire(allowColdStart: Boolean): CallRuntime.Result {
      val finished = CountDownLatch(1)
      val result = AtomicReference<CallRuntime.Result>()
      manager.withCallEngine(allowColdStart, { CallPermissions.isCurrent(generation) && CallPermissions.canContinueCall(contact.id) }, { actions.incrementAndGet() }) {
        result.set(it)
        finished.countDown()
      }
      assertTrue("The local native initializer must finish", finished.await(30, TimeUnit.SECONDS))
      return requireNotNull(result.get())
    }

    assertEquals(CallRuntime.Result.DISPATCHED, acquire(true))
    assertTrue(RingRtcLibrary.isInitialized())
    assertTrue(manager.isEngineReady)
    assertEquals(CallRuntime.State.READY, manager.callRuntime.state)
    assertTrue("A successful initializer must really map the packaged RingRTC library", ringRtcMappings().isNotEmpty())
    assertEquals(CallRuntime.Result.DISPATCHED, acquire(false))
    assertEquals(2, actions.get())

    CallPermissions.setAllCallingDisabled(true)
    assertEquals(CallRuntime.Result.DENIED, acquire(true))
    assertEquals(2, actions.get())
    // Teardown/revocation cannot unload the library from this process.
    assertTrue(RingRtcLibrary.isInitialized())
    assertTrue(ringRtcMappings().isNotEmpty())
    checkpoints.put(JSONObject().put("step", "authorized-then-revoked").put("dispatched", actions.get()).put("nativeMapped", true))
    record("authorized-acquisition", onDemand = true, disabled = false)
  }

  private fun exercisePassiveRoutes() {
    val manager = AppDependencies.signalCallManager
    assertEquals(CallRuntime.State.COLD, manager.callRuntime.state)
    assertFalse(manager.isEngineReady)
    assertTrue(manager.peekInfoSnapshot.isEmpty())
    assertTrue(manager.peekInfoCache.firstOrError().blockingGet().isEmpty())
    manager.ephemeralStates().firstOrError().blockingGet()
    CallPeekState.snapshot()
    CallPeekState.observe().firstOrError().blockingGet()
    CallPermissions.settings()
    assertCold("facade-and-cached-state")

    val helper = MessageHelper(harness)
    try {
      val incoming = helper.incomingText()
      assertNotNull(SignalDatabase.messages.getMessageFor(incoming.timestamp, incoming.author))
      val outgoing = helper.outgoingText()
      assertNotNull(SignalDatabase.messages.getMessageRecordOrNull(outgoing.messageId))
      val group = helper.incomingText(destination = requireNotNull(harness.group).recipientId)
      assertNotNull(SignalDatabase.messages.getMessageFor(group.timestamp, group.author))
      assertCold("ordinary-direct-and-group-messaging")

      val callId = CallEraId.fromEra("1122334455667788")
      helper.syncCallEvent(callId, System.currentTimeMillis(), helper.alice, SyncMessage.CallEvent.Type.AUDIO_CALL, SyncMessage.CallEvent.Direction.INCOMING, SyncMessage.CallEvent.Event.ACCEPTED)
      assertEquals(CallTable.Event.ACCEPTED, requireNotNull(SignalDatabase.calls.getCallById(callId, helper.alice)).event)
      val cache = CallEventCache { it.run() }
      val subscription = cache.listenForChanges().subscribe()
      try {
        assertTrue(cache.getCallEventsCount(CallEventCache.FilterState()) > 0)
        assertTrue(cache.getCallEvents(CallEventCache.FilterState(), 20, 0).isNotEmpty())
      } finally {
        subscription.dispose()
      }
      assertCold("linked-device-call-history")

      val bytes = ByteArray(16) { (it + 1).toByte() }
      val rootKey = requireNotNull(ManagedCallLinkRootKey.fromBytes(bytes))
      val url = CallLinks.url(bytes)
      assertEquals(rootKey, CallLinks.parseUrl(url))
      assertEquals(rootKey.roomId, CallLinkCredentials(bytes, null).roomId)

      val update = SyncMessage.CallLinkUpdate(rootKey = bytes.toByteString(), adminPasskey = ByteArray(16) { 9 }.toByteString())
      val timestamp = System.currentTimeMillis()
      helper.processor.process(
        envelope = MessageContentFuzzer.envelope(timestamp),
        content = Content(syncMessage = SyncMessage(callLinkUpdate = update)),
        metadata = MessageContentFuzzer.envelopeMetadata(harness.self.id, harness.self.id, sourceDeviceId = 2),
        serverDeliveredTimestamp = timestamp + 10
      )
      assertTrue(SignalDatabase.callLinks.callLinkExists(rootKey.roomId))
      val remote = SignalCallLinkRecord(StorageId.forCallLink(ByteArray(16) { 7 }), CallLinkRecord(rootKey = bytes.toByteString(), adminPasskey = ByteArray(16) { 8 }.toByteString()))
      val processor = CallLinkRecordProcessor()
      assertFalse(processor.isInvalid(remote))
      processor.insertLocal(remote)
      assertEquals(rootKey.roomId, requireNotNull(SignalDatabase.callLinks.getCallLinkByRoomId(rootKey.roomId)).roomId)
      assertCold("link-metadata-sync-and-storage")

      val previousPreviews = SignalStore.settings.isLinkPreviewsEnabled
      try {
        SignalStore.settings.isLinkPreviewsEnabled = true
        LinkPreviewRepository().getLinkPreview(url).timeout(10, TimeUnit.SECONDS).blockingGet()
      } finally {
        SignalStore.settings.isLinkPreviewsEnabled = previousPreviews
      }
      assertTrue(RefreshCallLinkDetailsJob(update).run().isSuccess)
      manager.peekGroupCall(requireNotNull(harness.group).recipientId)
      assertTrue(GroupRingCleanupJob.Factory().create(Job.Parameters.Builder().build(), null).run().isSuccess)
      awaitRuntimeQueue()
      assertCold("link-preview-refresh-group-peek-and-cleanup")
    } finally {
      helper.tearDown()
    }

    val previousAutoLock = ScreenLockController.autoLock
    val previousLockAtStart = ScreenLockController.lockScreenAtStart
    try {
      ScreenLockController.enableAutoLock(false)
      ScreenLockController.lockScreenAtStart = false
      ActivityScenario.launch<AppSettingsNavigationActivity>(AppSettingsNavigationActivity.createIntent(harness.context, AppSettingsRoute.CollieCalls)).use {
        instrumentation.waitForIdleSync()
        assertCold("calls-settings-screen")
      }
      ActivityScenario.launch<ConversationSettingsActivity>(ConversationSettingsActivity.forGroup(harness.context, requireNotNull(harness.group).groupId)).use {
        instrumentation.waitForIdleSync()
        assertCold("group-settings-browsing")
      }
    } finally {
      ScreenLockController.enableAutoLock(previousAutoLock)
      ScreenLockController.lockScreenAtStart = previousLockAtStart
    }
  }

  private fun exerciseDeniedTraffic() {
    val contact = Recipient.resolved(harness.others.first())
    CallPermissions.setOverride(contact, CallPermission.BLOCK)
    val wasMultiDevice = SignalStore.account.isMultiDevice
    try {
      // A linked-device policy denial is transport-silent; no synthetic identity reaches a server.
      SignalStore.account.isMultiDevice = true
      val timestamp = System.currentTimeMillis()
      val envelope = MessageContentFuzzer.envelope(timestamp)
      val metadata = MessageContentFuzzer.envelopeMetadata(contact.id, harness.self.id)
      listOf(
        CallMessage(offer = CallMessage.Offer(id = 41, type = CallMessage.Offer.Type.OFFER_AUDIO_CALL, opaque = ByteString.EMPTY)),
        CallMessage(answer = CallMessage.Answer(id = 41, opaque = ByteString.EMPTY)),
        CallMessage(iceUpdate = listOf(CallMessage.IceUpdate(id = 41, opaque = ByteString.EMPTY))),
        CallMessage(hangup = CallMessage.Hangup(id = 41)),
        CallMessage(busy = CallMessage.Busy(id = 41)),
        CallMessage(opaque = CallMessage.Opaque(data_ = ByteString.EMPTY))
      ).forEach { CallMessageProcessor.process(contact, envelope, Content(callMessage = it), metadata, timestamp + 10) }
      awaitRuntimeQueue()
      assertCold("six-denied-signaling-categories")
    } finally {
      SignalStore.account.isMultiDevice = wasMultiDevice
    }
  }

  private fun awaitRuntimeQueue() {
    val finished = CountDownLatch(1)
    AppDependencies.signalCallManager.callRuntime.execute(CallRuntime.Mode.IF_RUNNING, { false }, { error("Queue barrier must not dispatch") }) { finished.countDown() }
    assertTrue(finished.await(10, TimeUnit.SECONDS))
  }

  private fun assertCold(step: String) {
    assertFalse("$step initialized RingRTC", RingRtcLibrary.isInitialized())
    assertTrue("$step mapped RingRTC", ringRtcMappings().isEmpty())
    checkpoints.put(JSONObject().put("step", step).put("nativeMapped", false).put("initialized", false))
  }

  private fun ringRtcMappings(): List<String> {
    // This pin uses compressed/extracted native libraries (useLegacyPackaging=true). Require the
    // real file so a packaging change cannot silently make a filename-only /proc probe pass.
    val nativeDirectory = File(harness.context.applicationInfo.nativeLibraryDir)
    assertTrue("The tested APK must contain extracted RingRTC", File(nativeDirectory, "libringrtc.so").isFile)
    val maps = File("/proc/self/maps").readLines()
    assertTrue("The process mapping probe must be readable", maps.isNotEmpty())
    return maps.filter { it.contains("libringrtc") || it.contains("libjingle_peerconnection") }
  }

  private fun record(name: String, onDemand: Boolean, disabled: Boolean) {
    val proof = JSONObject().put("schema", 1).put("pid", Process.myPid()).put("onDemandAtStart", onDemand)
      .put("allDisabledAtStart", disabled).put("checkpoints", checkpoints).put("realCallTimingSamples", 0)
    TestStorage().openOutputFile("call-runtime-proof/$name.json").bufferedWriter().use { it.write(proof.toString(2)) }
  }
}

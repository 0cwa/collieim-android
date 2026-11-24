/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.calls.permissions

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.appsettings.collie.CallsSettingsTestTags
import org.signal.appsettings.routes.AppSettingsRoute
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.calls.links.CallLinks
import org.thoughtcrime.securesms.calls.log.CallEventCache
import org.thoughtcrime.securesms.calls.runtime.CallRuntime
import org.thoughtcrime.securesms.calls.runtime.RingRtcLibrary
import org.thoughtcrime.securesms.components.settings.app.AppSettingsNavigationActivity
import org.thoughtcrime.securesms.components.settings.conversation.ConversationSettingsActivity
import org.thoughtcrime.securesms.components.webrtc.v2.CallIntent
import org.thoughtcrime.securesms.components.webrtc.v2.WebRtcCallActivity
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
import org.thoughtcrime.securesms.service.webrtc.SignalCallManager
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
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.signal.appsettings.R as SettingsR

/**
 * Clean-process probes, each selected separately by Android Test Orchestrator. Synthetic local
 * accounts never contact the service. Engine acquisition is tested, not call interoperability or
 * call latency. Neither the native mappings nor the application singleton are reset between steps.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class ColdCallRuntimeTest {
  @get:Rule(order = 0)
  val harness = SignalActivityRule(othersCount = 2, createGroup = true, networkEnabled = false)

  @get:Rule(order = 1)
  val compose = createEmptyComposeRule()

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

  @Test
  @CallStartupForTest(onDemand = true)
  fun pendingCallUiSurvivesStartupAndRotationThenCancelsWithoutLoading() {
    assertCold("before-pending-call-ui")
    val manager = AppDependencies.signalCallManager
    val contact = Recipient.resolved(harness.others.first())
    assertTrue(CallPermissions.canStartCall(contact))
    awaitRuntimeQueue()

    // Use the production executor and runtime. Holding its queue makes startup deterministic
    // without substituting a manager, native initializer, call screen or remote service.
    val executor = SignalCallManager::class.java.getDeclaredField("serviceExecutor").let {
      it.isAccessible = true
      it.get(manager) as Executor
    }
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val expired = AtomicBoolean()
    val previousScreenSecurity = SignalStore.settings.isScreenSecurityEnabled
    val previousAutoLock = ScreenLockController.autoLock
    val previousLockAtStart = ScreenLockController.lockScreenAtStart
    try {
      SignalStore.settings.isScreenSecurityEnabled = false
      ScreenLockController.enableAutoLock(false)
      ScreenLockController.lockScreenAtStart = false
      // This isolated test account uses no audio or camera. Avoid a microphone permission dialog
      // covering the call screen; Orchestrator clears this fixture's application data afterwards.
      instrumentation.uiAutomation.grantRuntimePermission(harness.context.packageName, Manifest.permission.RECORD_AUDIO)
      executor.execute {
        entered.countDown()
        val released = try {
          release.await(45, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          false
        }
        if (!released) {
          expired.set(true)
          manager.pendingLocalStart?.let { manager.cancelPendingLocalStart(it.attemptId) }
        }
      }
      assertTrue("The real calling queue must be held before the attempt", entered.await(5, TimeUnit.SECONDS))
      manager.startPreJoinCall(contact)
      val attempt = requireNotNull(manager.pendingLocalStart)
      assertTrue(attempt.isPreJoin)
      assertEquals(contact.id, attempt.recipientId)

      ActivityScenario.launch<WebRtcCallActivity>(
        CallIntent.Builder(harness.context).withEnableVideoIfAvailable(false).withLaunchInPip(false).build()
      ).use { scenario ->
        val starting = harness.context.getString(R.string.CallPermissions__starting_call)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(starting).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(starting).assertIsDisplayed()

        // Exercise the real main-thread grace timer that previously closed a cold call screen
        // after one second. This wait is UI lifecycle evidence, never a call connection sample.
        val gracePassed = CountDownLatch(1)
        val began = SystemClock.elapsedRealtime()
        Handler(Looper.getMainLooper()).postDelayed({ gracePassed.countDown() }, 1_250)
        assertTrue(gracePassed.await(5, TimeUnit.SECONDS))
        val waited = SystemClock.elapsedRealtime() - began
        assertTrue(waited >= 1_250)
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
        compose.onNodeWithText(starting).assertIsDisplayed()
        assertEquals(attempt.attemptId, requireNotNull(manager.pendingLocalStart).attemptId)
        assertCold("starting-ui-survived-grace").put("waitedMillis", waited)

        var original: WebRtcCallActivity? = null
        var expectedOrientation = Configuration.ORIENTATION_UNDEFINED
        scenario.onActivity { activity ->
          original = activity
          val portrait = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
          expectedOrientation = if (portrait) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
          activity.requestedOrientation = if (portrait) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.waitUntil(10_000) {
          var recreated = false
          scenario.onActivity { activity -> recreated = activity !== original && activity.resources.configuration.orientation == expectedOrientation }
          recreated
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(starting).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(starting).assertIsDisplayed()
        assertEquals(attempt.attemptId, requireNotNull(manager.pendingLocalStart).attemptId)
        assertCold("starting-ui-survived-rotation").put("recreated", true)
        capturePendingCall(scenario)

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { manager.pendingLocalStart == null && scenario.state == Lifecycle.State.DESTROYED }
        assertNull(manager.pendingLocalStart)
      }
    } finally {
      // Also cancel on assertion/launch failures, before unblocking the real initializer.
      manager.pendingLocalStart?.let { manager.cancelPendingLocalStart(it.attemptId) }
      release.countDown()
      SignalStore.settings.isScreenSecurityEnabled = previousScreenSecurity
      ScreenLockController.enableAutoLock(previousAutoLock)
      ScreenLockController.lockScreenAtStart = previousLockAtStart
    }
    awaitRuntimeQueue()
    assertFalse("The queue hold must not expire", expired.get())
    assertNull(manager.pendingLocalStart)
    assertEquals(CallRuntime.State.COLD, manager.callRuntime.state)
    assertCold("back-cancelled-before-initialization").put("pending", false).put("runtimeState", "COLD")
    record("pending-call-ui", onDemand = true, disabled = false)
  }

  private fun capturePendingCall(scenario: ActivityScenario<WebRtcCallActivity>) {
    compose.waitForIdle()
    instrumentation.waitForIdleSync()
    val committed = CountDownLatch(1)
    scenario.onActivity { activity ->
      assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
      val view = activity.window.decorView
      assertTrue(view.isAttachedToWindow && view.isHardwareAccelerated)
      view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
      view.invalidate()
    }
    assertTrue("The starting-call frame must commit before capture", committed.await(5, TimeUnit.SECONDS))
    val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
      TestStorage().openOutputFile("call-permissions-proof/13-call-starting-after-rotation.png").use { output ->
        assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
      }
    } finally {
      screenshot.recycle()
    }
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
        // The device restriction stays editable even when it is on, and only enables after
        // the asynchronous settings read. The startup row is absent from the loading snapshot.
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(CallsSettingsTestTags.DISABLE_ALL) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(CallsSettingsTestTags.SCROLLER).performScrollToNode(hasTestTag(CallsSettingsTestTags.ON_DEMAND))
        awaitLoadedSettingsRow(CallsSettingsTestTags.ON_DEMAND)
        awaitRuntimeQueue()
        assertCold("calls-settings-screen")
      }
      ActivityScenario.launch<ConversationSettingsActivity>(ConversationSettingsActivity.forGroup(harness.context, requireNotNull(harness.group).groupId)).use {
        compose.waitUntil(15_000) { compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(CallsSettingsTestTags.OVERRIDE))
        awaitLoadedSettingsRow(CallsSettingsTestTags.OVERRIDE)
        awaitRuntimeQueue()
        assertCold("group-settings-browsing")
      }
    } finally {
      ScreenLockController.enableAutoLock(previousAutoLock)
      ScreenLockController.lockScreenAtStart = previousLockAtStart
    }
  }

  private fun awaitLoadedSettingsRow(tag: String) {
    if (CallPermissions.settings().allCallingDisabled) {
      // The group override row exists even while loading. Require its loaded explanation before
      // accepting a disabled control; a loading placeholder is not proof of the settings path.
      compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() &&
          (tag != CallsSettingsTestTags.OVERRIDE || compose.onAllNodesWithText(harness.context.getString(SettingsR.string.CollieCalls__all_disabled)).fetchSemanticsNodes().isNotEmpty())
      }
      compose.onNodeWithTag(tag).assertIsNotEnabled()
    } else {
      compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    compose.onNodeWithTag(tag).assertIsDisplayed()
    compose.waitForIdle()
    instrumentation.waitForIdleSync()
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

  private fun assertCold(step: String): JSONObject {
    assertFalse("$step initialized RingRTC", RingRtcLibrary.isInitialized())
    assertTrue("$step mapped RingRTC", ringRtcMappings().isEmpty())
    val checkpoint = JSONObject().put("step", step).put("nativeMapped", false).put("initialized", false)
    checkpoints.put(checkpoint)
    return checkpoint
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

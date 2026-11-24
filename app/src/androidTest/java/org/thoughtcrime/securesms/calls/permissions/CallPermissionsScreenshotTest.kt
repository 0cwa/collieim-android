/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.calls.permissions

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.appsettings.collie.CallsSettingsTestTags
import org.signal.appsettings.collie.CollieSettingsTestTags
import org.signal.appsettings.routes.AppSettingsRoute
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.components.settings.app.AppSettingsNavigationActivity
import org.thoughtcrime.securesms.components.settings.conversation.ConversationSettingsActivity
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.profiles.ProfileName
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testing.DisableAnimationsRule
import org.thoughtcrime.securesms.testing.SignalActivityRule
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.signal.appsettings.R as SettingsR

/**
 * Actual settings navigation and durable policy writes, using synthetic recipients with networking disabled.
 * Install matching app/test APKs. API 35 screenshots are written under call-permissions-proof, including to
 * additionalTestOutputDir when supplied by the instrumentation runner. No native calls or remote accounts are used.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class CallPermissionsScreenshotTest {
  @get:Rule(order = 0)
  val animations = DisableAnimationsRule()

  @get:Rule(order = 1)
  val harness = SignalActivityRule(othersCount = 2, createGroup = true, networkEnabled = false)

  @get:Rule(order = 2)
  val compose = createEmptyComposeRule()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context get() = harness.context

  @Test
  fun callsSettingsAndContactGroupOverrides() {
    val contact = Recipient.resolved(harness.others.first())
    val testGroup = requireNotNull(harness.group)
    val group = Recipient.resolved(testGroup.recipientId)
    val previous = CallPermissions.settings()
    val previousContact = CallPermissions.getOverride(contact)
    val previousGroup = CallPermissions.getOverride(group)
    val previousScreenSecurity = SignalStore.settings.isScreenSecurityEnabled
    val previousAutoLock = ScreenLockController.autoLock
    val previousLockAtStart = ScreenLockController.lockScreenAtStart
    assertTrue("The synthetic local account must have readable call permissions", previous.available)

    try {
      SignalStore.settings.isScreenSecurityEnabled = false
      ScreenLockController.enableAutoLock(false)
      ScreenLockController.lockScreenAtStart = false
      CallPermissions.setAllCallingDisabled(false)
      CallPermissions.setDefault(CallContext.DIRECT, CallPermission.ALLOW)
      CallPermissions.setDefault(CallContext.GROUP, CallPermission.ALLOW)
      CallPermissions.setOverride(contact, CallPermission.INHERIT)
      CallPermissions.setOverride(group, CallPermission.INHERIT)
      SignalDatabase.recipients.setProfileName(contact.id, ProfileName.fromParts("Alex", ""))
      val groupState = SignalDatabase.groups.getGroup(testGroup.groupId).get().requireV2GroupProperties().decryptedGroup
      SignalDatabase.groups.update(testGroup.groupId, groupState.copy(title = "Weekend plans"), null)

      ActivityScenario.launch<AppSettingsNavigationActivity>(
        AppSettingsNavigationActivity.createIntent(context, AppSettingsRoute.Collie)
      ).use { scenario ->
        awaitTag(CollieSettingsTestTags.ROW_CALLS)
        capture(scenario, "01-collie-hardening.png")
        compose.onNodeWithTag(CollieSettingsTestTags.ROW_CALLS).performClick()
        awaitTag(CallsSettingsTestTags.DISABLE_ALL)
        awaitEnabled(CallsSettingsTestTags.DISABLE_ALL)
        scrollTo(CallsSettingsTestTags.INCOMING)
        compose.onNodeWithText(context.getString(SettingsR.string.CollieCalls__allow_incoming)).assertIsDisplayed()
        capture(scenario, "02-calls-defaults.png")

        scrollTo(CallsSettingsTestTags.DISABLE_ALL)
        awaitEnabled(CallsSettingsTestTags.DISABLE_ALL)
        compose.onNodeWithTag(CallsSettingsTestTags.DISABLE_ALL).performClick()
        compose.waitUntil(10_000) { CallPermissions.settings().allCallingDisabled }
        scrollTo(CallsSettingsTestTags.INCOMING)
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(CallsSettingsTestTags.INCOMING) and isEnabled()).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag(CallsSettingsTestTags.INCOMING).assertIsNotEnabled()
        capture(scenario, "03-calls-disabled.png")

        scrollTo(CallsSettingsTestTags.DISABLE_ALL)
        awaitEnabled(CallsSettingsTestTags.DISABLE_ALL)
        compose.onNodeWithTag(CallsSettingsTestTags.DISABLE_ALL).performClick()
        compose.waitUntil(10_000) { !CallPermissions.settings().allCallingDisabled }
        scrollTo(CallsSettingsTestTags.GROUPS)
        awaitEnabled(CallsSettingsTestTags.GROUPS)
        compose.onNodeWithTag(CallsSettingsTestTags.GROUPS).performClick()
        compose.onNodeWithText(context.getString(SettingsR.string.CollieCalls__block_group)).performClick()
        compose.waitUntil(10_000) { CallPermissions.settings().groupDefault == CallPermission.BLOCK }
        awaitEnabled(CallsSettingsTestTags.GROUPS)
        capture(scenario, "04-separate-group-default.png")
        assertEquals(CallPermission.ALLOW, CallPermissions.settings().directDefault)
      }

      ActivityScenario.launch<ConversationSettingsActivity>(ConversationSettingsActivity.forRecipient(context, contact)).use { scenario ->
        scrollToOverride()
        capture(scenario, "05-contact-inherit.png")
        compose.onNodeWithTag(CallsSettingsTestTags.OVERRIDE).performClick()
        capture(scenario, "06-contact-choices.png")
        compose.onNodeWithText(context.getString(SettingsR.string.CollieCalls__block_incoming)).performClick()
        compose.waitUntil(10_000) { CallPermissions.getOverride(contact) == CallPermission.BLOCK }
        awaitEnabled(CallsSettingsTestTags.OVERRIDE)
        capture(scenario, "07-contact-blocked.png")
      }

      ActivityScenario.launch<ConversationSettingsActivity>(ConversationSettingsActivity.forGroup(context, testGroup.groupId)).use { scenario ->
        scrollToOverride()
        compose.onNodeWithTag(CallsSettingsTestTags.OVERRIDE).performClick()
        capture(scenario, "08-group-choices.png")
        compose.onNodeWithText(context.getString(SettingsR.string.CollieCalls__allow_group)).performClick()
        compose.waitUntil(10_000) { CallPermissions.getOverride(group) == CallPermission.ALLOW }
        awaitEnabled(CallsSettingsTestTags.OVERRIDE)
        capture(scenario, "09-group-allowed.png")
        assertEquals("A group exception must not grant private-call permission", CallPermission.BLOCK, CallPermissions.getOverride(contact))
        assertEquals("The exception must not change other groups", CallPermission.BLOCK, CallPermissions.settings().groupDefault)
      }
    } finally {
      restoreEverySetting(
        { CallPermissions.setAllCallingDisabled(false) },
        { CallPermissions.setDefault(CallContext.DIRECT, previous.directDefault) },
        { CallPermissions.setDefault(CallContext.GROUP, previous.groupDefault) },
        { CallPermissions.setOverride(contact, previousContact) },
        { CallPermissions.setOverride(group, previousGroup) },
        { CallPermissions.setAllCallingDisabled(previous.allCallingDisabled) },
        { SignalStore.settings.isScreenSecurityEnabled = previousScreenSecurity },
        { ScreenLockController.enableAutoLock(previousAutoLock) },
        { ScreenLockController.lockScreenAtStart = previousLockAtStart }
      )
    }
  }

  private fun awaitTag(tag: String) {
    compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    compose.waitForIdle()
  }

  private fun scrollTo(tag: String) {
    compose.onNodeWithTag(CallsSettingsTestTags.SCROLLER).performScrollToNode(hasTestTag(tag))
    compose.waitForIdle()
  }

  private fun awaitEnabled(tag: String) {
    compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
  }

  private fun scrollToOverride() {
    compose.waitUntil(15_000) { compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
    compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(CallsSettingsTestTags.OVERRIDE))
    awaitTag(CallsSettingsTestTags.OVERRIDE)
    awaitEnabled(CallsSettingsTestTags.OVERRIDE)
  }

  private fun <T : Activity> capture(scenario: ActivityScenario<T>, name: String) {
    compose.waitUntil(10_000) {
      compose.onAllNodesWithText(context.getString(SettingsR.string.CollieCalls__saving)).fetchSemanticsNodes().isEmpty()
    }
    compose.waitForIdle()
    instrumentation.waitForIdleSync()
    compose.waitUntil(5_000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName }

    // Semantics can already expose the saved value while the previous frame still says "Saving…".
    // Require a newly committed frame; an idle UI thread alone is not a rendering barrier.
    val frameCommitted = CountDownLatch(1)
    scenario.onActivity { activity ->
      assertEquals("Screen security is disabled only for evidence capture", 0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
      val view = activity.window.decorView
      assertTrue("Evidence capture requires an attached hardware-rendered window", view.isAttachedToWindow && view.isHardwareAccelerated)
      view.viewTreeObserver.registerFrameCommitCallback { frameCommitted.countDown() }
      view.invalidate()
    }
    assertTrue("The saved settings frame must commit before capture", frameCommitted.await(5, TimeUnit.SECONDS))
    val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
      var visible = 0
      for (y in 0 until screenshot.height step 8) {
        for (x in 0 until screenshot.width step 8) {
          if (screenshot.getPixel(x, y) != Color.BLACK) visible++
        }
      }
      assertTrue("The screenshot must contain actual app content", visible > 100)
      val additionalOutput = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
      for (parent in listOfNotNull(context.getExternalFilesDir(null), additionalOutput).distinct()) {
        val directory = File(parent, "call-permissions-proof")
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, name).outputStream().use { output ->
          assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
      }
    } finally {
      screenshot.recycle()
    }
  }

  private fun restoreEverySetting(vararg operations: () -> Unit) {
    var failure: Throwable? = null
    for (operation in operations) {
      try {
        operation()
      } catch (error: Throwable) {
        if (failure == null) failure = error else failure.addSuppressed(error)
      }
    }
    failure?.let { throw it }
  }
}

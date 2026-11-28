/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.backup.v2.barkup

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.signal.remoteconfig.RemoteConfig
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.backup.v2.local.LocalArchiver
import org.thoughtcrime.securesms.backup.v2.local.SnapshotFileSystem
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.components.settings.app.AppSettingsNavigationActivity
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobs.CollieRemoteArchiveJob
import org.thoughtcrime.securesms.jobs.LocalBackupJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testing.RawFlag
import org.thoughtcrime.securesms.testing.RemoteConfigForTest
import org.thoughtcrime.securesms.testing.SignalActivityRule
import java.io.File

@RemoteConfigForTest(rawFlags = [RawFlag(RemoteConfig.APP_SETTINGS_NAVIGATION_KEY, "true")])
class BarkupSettingsDeviceTest {
  @get:Rule val harness = SignalActivityRule(othersCount = 0, networkEnabled = false)
  @get:Rule val compose = createComposeRule()

  @Test
  fun backupEntryAndQueuedJobRespectEdition() {
    val context = harness.context
    val title = context.getString(R.string.BarkupSettings__title)
    val onDevice = context.getString(R.string.RemoteBackupsSettingsFragment__on_device_backups)

    ActivityScenario.launch<AppSettingsNavigationActivity>(AppSettingsActivity.backups(context)).use { scenario ->
      await(onDevice)
      withScreenSecurityTemporarilyDisabled(scenario) {
        if (!BuildConfig.TRAILBLAZER_BETA) {
          compose.onNodeWithText(title).assertDoesNotExist()
          assertFalse(CollieRemoteArchiveJob.isEnabled())
          val factory = CollieRemoteArchiveJob.Factory()
          val export = factory.create(Job.Parameters.Builder().setQueue(LocalBackupJob.QUEUE).build(), "Eold-enrollment".toByteArray())
          val upload = factory.create(Job.Parameters.Builder().setQueue(CollieRemoteArchiveJob.TRANSFER_QUEUE).build(), "Told-enrollment".toByteArray())
          assertTrue(export.run().isSuccess)
          assertTrue(upload.run().isSuccess)
          capture("stable-on-device-backups")
          return
        }

        await(title)
        capture("trailblazer-on-device-backups")
        compose.onNodeWithText(title).performClick()
        val offer = context.getString(R.string.BarkupSettings__pairing_offer)
        await(offer)
        capture("trailblazer-barkup-pairing")
        compose.onNodeWithText(offer).performTextInput("invalid")
        compose.onNodeWithText(context.getString(R.string.BarkupSettings__invalid_offer)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
      }
    }

    val stage = File(context.noBackupFilesDir, "barkup-test")
    val archive = BarkupArchive(stage)
    try {
      archive.prepare("device-proof", "test-enrollment", emptyMap()) {}
      val snapshot = SnapshotFileSystem(context, DocumentFile.fromFile(File(stage, "pending")))
      assertTrue(LocalArchiver.canDecryptMainArchive(snapshot, SignalStore.backup.messageBackupKey))
    } finally {
      archive.clear()
    }

    val account = checkNotNull(CollieRemoteArchiveJob.account())
    val attempt = SignalStore.collieBarkup.beginPairing(account)
    assertTrue(SignalStore.collieBarkup.enable(attempt, barkupApi("https://backup.example"), BarkupCredentials("0123456789Ab", "test-device-token")) { account })
    try {
      ActivityScenario.launch<AppSettingsNavigationActivity>(AppSettingsActivity.backups(context)).use { scenario ->
        await(title)
        withScreenSecurityTemporarilyDisabled(scenario) {
          compose.onNodeWithText(title).performClick()
          await(context.getString(R.string.BarkupSettings__connected_to, "backup.example"))
          compose.onNodeWithText(context.getString(R.string.BarkupSettings__backup_now)).assertIsDisplayed()
          capture("trailblazer-barkup-connected")
          compose.onNodeWithText(context.getString(R.string.BarkupSettings__disconnect)).performClick()
          await(context.getString(R.string.BarkupSettings__not_connected))
          assertNull(SignalStore.collieBarkup.enrollment(account))
        }
      }
    } finally {
      CollieRemoteArchiveJob.disconnect()
    }
  }

  private fun await(text: String) {
    compose.waitUntil(15_000) {
      compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }
  }

  private inline fun withScreenSecurityTemporarilyDisabled(scenario: ActivityScenario<AppSettingsNavigationActivity>, block: () -> Unit) {
    val previousFlags = java.util.concurrent.atomic.AtomicInteger()
    scenario.onActivity {
      previousFlags.set(it.window.attributes.flags)
      it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
      it.window.decorView.postInvalidateOnAnimation()
    }
    try {
      block()
    } finally {
      if (previousFlags.get() and WindowManager.LayoutParams.FLAG_SECURE != 0) {
        scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
      }
    }
  }

  private fun capture(name: String) {
    compose.waitForIdle()
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    automation.waitForIdle(500, 5000)
    val image = checkNotNull(automation.takeScreenshot())
    try {
      val folder = File(checkNotNull(harness.context.getExternalFilesDir(null)), "barkup-settings")
      folder.mkdirs()
      File(folder, "$name.png").outputStream().use {
        assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
      }
    } finally {
      image.recycle()
    }
  }
}

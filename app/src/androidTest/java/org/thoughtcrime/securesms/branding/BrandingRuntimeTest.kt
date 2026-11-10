/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.branding

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.util.DynamicTheme
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.keyvalue.SignalStore
import java.io.File
import java.util.Properties

@RunWith(AndroidJUnit4::class)
class BrandingRuntimeTest {

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context = instrumentation.targetContext

  @Test
  fun allNotificationFamiliesPostWithTheirPackagedSmallIcons() {
    if (Build.VERSION.SDK_INT >= 33) {
      assertEquals(
        "POST_NOTIFICATIONS must be granted by the host fixture before instrumentation starts",
        PackageManager.PERMISSION_GRANTED,
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
      )
    }

    val manager = context.getSystemService(NotificationManager::class.java)
    val channelId = "branding-runtime-proof"
    val resourceNames = listOf(
      "ic_notification",
      "ic_notification_unlocked",
      "ic_notification_backup",
      "ic_notification_websocket"
    )
    manager.createNotificationChannel(
      NotificationChannel(channelId, "Branding proof", NotificationManager.IMPORTANCE_DEFAULT)
    )

    try {
      resourceNames.forEachIndexed { index, name ->
        val iconId = context.resources.getIdentifier(name, "drawable", context.packageName)
        assertTrue("Missing packaged drawable $name", iconId != 0)

        val notificationId = 78350 + index
        if (Build.VERSION.SDK_INT >= 33) {
          assertEquals(
            "POST_NOTIFICATIONS must be granted before posting $name",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
          )
        }

        manager.notify(
          notificationId,
          Notification.Builder(context, channelId)
            .setSmallIcon(iconId)
            .setContentTitle("Collie branding proof")
            .setContentText(name)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        )

        SystemClock.sleep(700)
        assertNotNull(
          "Notification $name should be active in Android's notification manager",
          manager.activeNotifications.firstOrNull { it.id == notificationId }
        )

        instrumentation.waitForIdleSync()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
          ?: error("Status bar should be capturable for $name")
        saveScreenshot(screenshot, "statusbar-$name.png")
        screenshot.recycle()

        manager.cancel(notificationId)
        SystemClock.sleep(300)
      }
    } finally {
      resourceNames.indices.forEach { manager.cancel(78350 + it) }
      manager.deleteNotificationChannel(channelId)
    }
  }

  /** Host cold-start flow must invoke [restoreColdStartupState] from its cleanup/finally path. */
  @Test
  fun prepareColdStartupState() {
    val restoreFile = File(context.filesDir, "branding-runtime-preferences.properties")
    check(!restoreFile.exists()) { "A prior branding runtime state restore is still pending" }

    val snapshot = Properties().apply {
      setProperty("theme", TextSecurePreferences.getTheme(context))
      setProperty("dynamicColors", TextSecurePreferences.isDynamicColorsEnabled(context).toString())
      setProperty("screenSecurity", SignalStore.settings.isScreenSecurityEnabled.toString())
      setProperty("biometricLock", TextSecurePreferences.isBiometricScreenLockEnabled(context).toString())
      setProperty("autoLock", ScreenLockController.autoLock.toString())
      setProperty("lockAtStart", ScreenLockController.lockScreenAtStart.toString())
      setProperty("appCompatNightMode", AppCompatDelegate.getDefaultNightMode().toString())
    }
    restoreFile.outputStream().use { snapshot.store(it, "Temporary local branding runtime fixture") }

    persistThemePreferences(theme = "dark", dynamicColors = false, biometricLock = true)
    DynamicTheme.setDefaultDayNightMode(context)
    check(TextSecurePreferences.getTheme(context) == "dark") { "Dark theme preference was not persisted" }
    check(!TextSecurePreferences.isDynamicColorsEnabled(context)) { "Dynamic colors were not disabled for this fixture" }
    check(TextSecurePreferences.isBiometricScreenLockEnabled(context)) { "Biometric lock preference was not persisted" }
    SignalStore.settings.isScreenSecurityEnabled = false
  }

  @Test
  fun restoreColdStartupState() {
    val restoreFile = File(context.filesDir, "branding-runtime-preferences.properties")
    check(restoreFile.exists()) { "No saved branding runtime state is available to restore" }

    val snapshot = Properties().apply { restoreFile.inputStream().use(::load) }
    try {
      persistThemePreferences(
        theme = snapshot.getProperty("theme"),
        dynamicColors = snapshot.getProperty("dynamicColors").toBoolean(),
        biometricLock = snapshot.getProperty("biometricLock").toBoolean()
      )
    } finally {
      SignalStore.settings.isScreenSecurityEnabled = snapshot.getProperty("screenSecurity").toBoolean()
      ScreenLockController.enableAutoLock(snapshot.getProperty("autoLock").toBoolean())
      ScreenLockController.lockScreenAtStart = snapshot.getProperty("lockAtStart").toBoolean()
      AppCompatDelegate.setDefaultNightMode(snapshot.getProperty("appCompatNightMode").toInt())
    }

    check(TextSecurePreferences.getTheme(context) == snapshot.getProperty("theme"))
    check(TextSecurePreferences.isDynamicColorsEnabled(context) == snapshot.getProperty("dynamicColors").toBoolean())
    check(TextSecurePreferences.isBiometricScreenLockEnabled(context) == snapshot.getProperty("biometricLock").toBoolean())
    check(SignalStore.settings.isScreenSecurityEnabled == snapshot.getProperty("screenSecurity").toBoolean())
    check(ScreenLockController.autoLock == snapshot.getProperty("autoLock").toBoolean())
    check(ScreenLockController.lockScreenAtStart == snapshot.getProperty("lockAtStart").toBoolean())
    check(AppCompatDelegate.getDefaultNightMode() == snapshot.getProperty("appCompatNightMode").toInt())
    check(restoreFile.delete()) { "Could not remove the saved branding runtime state" }
  }

  private fun saveScreenshot(bitmap: Bitmap, name: String) {
    val directory = File(context.getExternalFilesDir(null), "branding-runtime-proof")
    assertTrue("Could not create screenshot directory", directory.exists() || directory.mkdirs())
    File(directory, name).outputStream().use { output ->
      assertTrue("Could not write screenshot $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
    }
  }

  private fun persistThemePreferences(theme: String, dynamicColors: Boolean, biometricLock: Boolean) {
    val committed = TextSecurePreferences.getSharedPreferences(context)
      .edit()
      .putString(TextSecurePreferences.THEME_PREF, theme)
      .putBoolean(TextSecurePreferences.DYNAMIC_COLORS_ENABLED, dynamicColors)
      .putBoolean(TextSecurePreferences.BIOMETRIC_SCREEN_LOCK, biometricLock)
      .commit()

    check(committed) { "Could not persist branding runtime preferences synchronously" }
    check(TextSecurePreferences.getTheme(context) == theme)
    check(TextSecurePreferences.isDynamicColorsEnabled(context) == dynamicColors)
    check(TextSecurePreferences.isBiometricScreenLockEnabled(context) == biometricLock)
  }
}

@RunWith(AndroidJUnit4::class)
class BrandingNormalDarkActivityTest {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 0)

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context
    get() = harness.context

  @Test
  fun normalDarkMainActivityRenders24130D() {
    val oldTheme = TextSecurePreferences.getTheme(context)
    val oldDynamicColors = TextSecurePreferences.isDynamicColorsEnabled(context)
    val oldScreenSecurity = SignalStore.settings.isScreenSecurityEnabled
    val oldBiometricLock = TextSecurePreferences.isBiometricScreenLockEnabled(context)
    val oldAutoLock = ScreenLockController.autoLock
    val oldLockAtStart = ScreenLockController.lockScreenAtStart
    val oldAppCompatNightMode = AppCompatDelegate.getDefaultNightMode()

    try {
      commitThemePreferences("dark", dynamicColors = false, biometricLock = false)
      DynamicTheme.setDefaultDayNightMode(context)
      check(AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES)
      SignalStore.settings.isScreenSecurityEnabled = false
      ScreenLockController.enableAutoLock(false)
      ScreenLockController.lockScreenAtStart = false

      harness.launchActivity<MainActivity>().use {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)

        val screenshot = instrumentation.uiAutomation.takeScreenshot()
          ?: error("A rendered MainActivity should be capturable")
        try {
          val expected = Color.rgb(0x24, 0x13, 0x0D)
          var matchingPixels = 0
          for (y in 0 until screenshot.height step 2) {
            for (x in 0 until screenshot.width step 2) {
              if (screenshot.getPixel(x, y) == expected) matchingPixels++
            }
          }

          saveScreenshot(screenshot, "normal-dark-background-24130d.png")
          assertTrue(
            "Expected the resolved static dark MainActivity surface #24130D; matching pixels=$matchingPixels",
            matchingPixels > 10_000
          )
        } finally {
          screenshot.recycle()
        }
      }
    } finally {
      try {
        commitThemePreferences(oldTheme, oldDynamicColors, oldBiometricLock)
      } finally {
        SignalStore.settings.isScreenSecurityEnabled = oldScreenSecurity
        ScreenLockController.enableAutoLock(oldAutoLock)
        ScreenLockController.lockScreenAtStart = oldLockAtStart
        AppCompatDelegate.setDefaultNightMode(oldAppCompatNightMode)
      }
    }
  }

  private fun saveScreenshot(bitmap: Bitmap, name: String) {
    val directory = File(context.getExternalFilesDir(null), "branding-runtime-proof")
    assertTrue("Could not create screenshot directory", directory.exists() || directory.mkdirs())
    File(directory, name).outputStream().use { output ->
      assertTrue("Could not write screenshot $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
    }
  }

  private fun commitThemePreferences(theme: String, dynamicColors: Boolean, biometricLock: Boolean) {
    val committed = TextSecurePreferences.getSharedPreferences(context)
      .edit()
      .putString(TextSecurePreferences.THEME_PREF, theme)
      .putBoolean(TextSecurePreferences.DYNAMIC_COLORS_ENABLED, dynamicColors)
      .putBoolean(TextSecurePreferences.BIOMETRIC_SCREEN_LOCK, biometricLock)
      .commit()

    check(committed) { "Could not restore the previous theme preferences" }
    check(TextSecurePreferences.getTheme(context) == theme)
    check(TextSecurePreferences.isDynamicColorsEnabled(context) == dynamicColors)
    check(TextSecurePreferences.isBiometricScreenLockEnabled(context) == biometricLock)
  }
}

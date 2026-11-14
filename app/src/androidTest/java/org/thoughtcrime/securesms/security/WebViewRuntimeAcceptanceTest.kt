/*
 * Copyright 2026 Collie
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.security

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.navigation.Navigation
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.debuglogsviewer.DebugLogsViewer
import org.signal.donations.InAppPaymentType
import org.signal.donations.StripeIntentAccessor
import org.signal.registration.screens.captcha.CaptchaScreen
import org.signal.registration.screens.captcha.CaptchaScreenEvents
import org.signal.registration.screens.captcha.CaptchaState
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.SignalInstrumentationApplicationContext
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.components.settings.app.subscription.donate.paypal.PayPalConfirmationDialogFragment
import org.thoughtcrime.securesms.components.settings.app.subscription.donate.paypal.PayPalConfirmationResult
import org.thoughtcrime.securesms.components.settings.app.subscription.donate.stripe.Stripe3DSDialogFragment
import org.thoughtcrime.securesms.database.InAppPaymentTable
import org.thoughtcrime.securesms.database.model.databaseprotos.InAppPaymentData
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.ratelimit.RecaptchaProofActivity
import org.thoughtcrime.securesms.registration.ui.captcha.CaptchaFragment
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.testing.endpoints.EndpointResponse
import org.thoughtcrime.securesms.testing.endpoints.EndpointTransport
import org.thoughtcrime.securesms.testing.endpoints.MockEndpoints
import org.thoughtcrime.securesms.testing.endpoints.put
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/** Offline synthetic pages exercise the real WebView clients; no vendor request or payment is made. */
@RunWith(AndroidJUnit4::class)
class WebViewRuntimeAcceptanceTest {
  @get:Rule(order = 0) val harness = SignalActivityRule(othersCount = 0)
  private var previousScreenSecurity: Boolean? = null
  private var previousUiAutomationFlags: Int? = null
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context get() = instrumentation.targetContext
  private val html = """<html><body><h1 id="marker">Synthetic WebView page</h1><script>document.title='fixture-ready';</script><a id="callback" href="signalcaptcha://synthetic-token">Continue</a></body></html>"""

  @Before fun setup() {
    check(context.applicationContext is SignalInstrumentationApplicationContext) { "Use the normal SignalTestRunner, not -PimoTests" }
    assertEquals("Airplane mode must be enabled before app startup", 1, Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON))
    assertFalse("Wi-Fi must be disabled before app startup", context.getSystemService(WifiManager::class.java).isWifiEnabled)
    assertEquals("Device must have no default network", null, context.getSystemService(ConnectivityManager::class.java).activeNetwork)
    previousScreenSecurity = SignalStore.settings.isScreenSecurityEnabled
    SignalStore.settings.isScreenSecurityEnabled = false
    val uiAutomationInfo = instrumentation.uiAutomation.serviceInfo
    previousUiAutomationFlags = uiAutomationInfo.flags
    uiAutomationInfo.flags = uiAutomationInfo.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    instrumentation.uiAutomation.setServiceInfo(uiAutomationInfo)
    MockEndpoints.responder.clear()
  }

  @After fun cleanup() {
    try {
      MockEndpoints.responder.clear()
      SignalStore.rateLimit.onProofAccepted()
    } finally {
      try {
        previousUiAutomationFlags?.let { flags ->
          val uiAutomationInfo = instrumentation.uiAutomation.serviceInfo
          uiAutomationInfo.flags = flags
          instrumentation.uiAutomation.setServiceInfo(uiAutomationInfo)
        }
      } finally {
        previousScreenSecurity?.let { SignalStore.settings.isScreenSecurityEnabled = it }
      }
    }
  }

  @Test fun composeCaptchaEmitsSyntheticToken() {
    val event = AtomicReference<CaptchaScreenEvents>()
    val dataUrl = "data:text/html;charset=utf-8," + Uri.encode(html)
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        activity.setContent { SignalTheme { CaptchaScreen(CaptchaState(captchaUrl = dataUrl), onEvent = { event.set(it) }) } }
      }
      val web = findDisplayedWebView(scenario)
      assertFixture(web, "compose-captcha")
      clickCallback(web)
      await { event.get() == CaptchaScreenEvents.CaptchaCompleted("synthetic-token") }
    }
  }

  @Test fun legacyCaptchaEmitsTokenAndNavigatesUp() {
    val token = AtomicReference<String>()
    LegacyCaptcha.token.set(token)
    try {
      ActivityScenario.launch<AppSettingsActivity>(AppSettingsActivity.proxy(context)).use { scenario ->
        lateinit var web: WebView
        scenario.onActivity { activity ->
          val nav = Navigation.findNavController(activity, R.id.nav_host_fragment)
          if (nav.currentDestination?.id != R.id.appSettingsFragment) {
            assertTrue("proxy settings should navigate back to the root", nav.navigateUp())
          }
          nav.navigate(R.id.action_appSettingsFragment_to_chatsSettingsFragment)
          assertEquals(R.id.chatsSettingsFragment, nav.currentDestination!!.id)
          val fragment = LegacyCaptcha()
          activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment, "legacy-test").commitNow()
          Navigation.setViewNavController(fragment.requireView(), nav)
          web = fragment.requireView().findViewById(R.id.registration_captcha_web_view)
        }
        loadFixture(web)
        assertFixture(web, "legacy-captcha")
        clickCallback(web)
        await { token.get() == "synthetic-token" }
        scenario.onActivity { activity ->
          assertEquals("captcha callback should navigate up", R.id.appSettingsFragment, Navigation.findNavController(activity, R.id.nav_host_fragment).currentDestination!!.id)
        }
      }
    } finally { LegacyCaptcha.token.set(null) }
  }

  @Test fun recaptchaSubmitsSyntheticChallengeThroughFakeTransport() {
    val request = AtomicReference<String>()
    MockEndpoints.responder.put("/v1/challenge") {
      assertEquals(EndpointTransport.WEBSOCKET, it.transport)
      request.set(it.bodyString)
      EndpointResponse(200, "{}")
    }
    SignalStore.rateLimit.markNeedsRecaptcha("synthetic-challenge")
    ActivityScenario.launchActivityForResult<RecaptchaProofActivity>(Intent(context, RecaptchaProofActivity::class.java)).use { scenario ->
      lateinit var web: WebView
      scenario.onActivity { web = it.findViewById(R.id.recaptcha_webview) }
      loadFixture(web)
      assertFixture(web, "recaptcha")
      clickCallback(web)
      await { request.get() != null }
      val body = JSONObject(request.get())
      assertEquals("captcha", body.getString("type"))
      assertEquals("synthetic-challenge", body.getString("token"))
      assertEquals("synthetic-token", body.getString("captcha"))
      assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
      assertFalse(SignalStore.rateLimit.needsRecaptcha())
    }
  }

  @Test fun paypalReturnsSyntheticConfirmation() {
    val result = AtomicReference<Bundle>()
    ActivityScenario.launch<AppSettingsActivity>(AppSettingsActivity.home(context)).use { scenario ->
      lateinit var web: WebView
      scenario.onActivity { activity ->
        activity.supportFragmentManager.setFragmentResultListener(PayPalConfirmationDialogFragment.REQUEST_KEY, activity) { _, bundle -> result.set(bundle) }
        val fragment = PayPalConfirmationDialogFragment().apply {
          arguments = Bundle().apply { putParcelable("uri", Uri.parse("about:blank")) }
        }
        fragment.showNow(activity.supportFragmentManager, "paypal-test")
        web = fragment.requireView().findViewById(R.id.web_view)
      }
      loadFixture(web)
      assertFixture(web, "paypal")
      instrumentation.runOnMainSync { assertTrue(web.settings.domStorageEnabled) }
      instrumentation.runOnMainSync { web.loadDataWithBaseURL("https://signaldonations.org/return/onetime?PayerID=test-payer&token=test-token", html, "text/html", "UTF-8", null) }
      await { result.get() != null }
      val confirmation = result.get().getParcelable(PayPalConfirmationDialogFragment.REQUEST_KEY, PayPalConfirmationResult::class.java)
      assertEquals(PayPalConfirmationResult("test-payer", null, "test-token"), confirmation)
    }
  }

  @Test fun stripeReturnsSyntheticIntentAccessor() {
    val result = AtomicReference<Bundle>()
    val payment = InAppPaymentTable.InAppPayment(
      id = InAppPaymentTable.InAppPaymentId(1), type = InAppPaymentType.ONE_TIME_DONATION,
      state = InAppPaymentTable.State.CREATED, insertedAt = 0.milliseconds, updatedAt = 0.milliseconds,
      notified = false, subscriberId = null, endOfPeriod = 0.milliseconds, data = InAppPaymentData()
    )
    ActivityScenario.launch<AppSettingsActivity>(AppSettingsActivity.home(context)).use { scenario ->
      lateinit var web: WebView
      scenario.onActivity { activity ->
        activity.supportFragmentManager.setFragmentResultListener(Stripe3DSDialogFragment.REQUEST_KEY, activity) { _, bundle -> result.set(bundle) }
        val fragment = Stripe3DSDialogFragment().apply {
          arguments = Bundle().apply {
            putParcelable("uri", Uri.parse("about:blank"))
            putParcelable("return_uri", Uri.parse("https://return.invalid/complete"))
            putParcelable("waiting_for_auth_payment", payment)
          }
        }
        fragment.showNow(activity.supportFragmentManager, "stripe-test")
        web = fragment.requireView().findViewById(R.id.web_view)
      }
      loadFixture(web)
      assertFixture(web, "stripe")
      instrumentation.runOnMainSync { assertTrue(web.settings.domStorageEnabled) }
      javascript(web, "window.location.href='https://local.invalid/navigation'")
      await { currentUrl(web) == "https://local.invalid/navigation" }
      instrumentation.runOnMainSync { web.loadDataWithBaseURL("https://return.invalid/complete?payment_intent=pi_test&payment_intent_client_secret=secret_test", html, "text/html", "UTF-8", null) }
      await { result.get() != null }
      val intent = result.get().getParcelable(Stripe3DSDialogFragment.REQUEST_KEY, StripeIntentAccessor::class.java)
      assertEquals(StripeIntentAccessor(StripeIntentAccessor.ObjectType.PAYMENT_INTENT, "pi_test", "secret_test"), intent)
    }
  }

  @Test fun debugViewerLoadsAssetAndRoundTripsSyntheticLogs() {
    ActivityScenario.launch<AppSettingsActivity>(AppSettingsActivity.home(context)).use { scenario ->
      val finished = CountDownLatch(1)
      lateinit var web: WebView
      scenario.onActivity { activity ->
        web = WebView(activity)
        activity.addContentView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        DebugLogsViewer.initWebView(web, activity) { finished.countDown() }
      }
      assertTrue("bundled asset did not finish", finished.await(20, TimeUnit.SECONDS))
      assertWebSettings(web)
      await { javascript(web, "typeof appendLines === 'function' && typeof readLines === 'function'") == "true" }
      DebugLogsViewer.appendLines(web, "synthetic alpha\nsynthetic beta\n")
      assertTrue(DebugLogsViewer.readLogs(web).nextChunk(10)!!.contains("synthetic beta"))
      instrumentation.runOnMainSync { DebugLogsViewer.onSearchInput(web, "synthetic beta") }
      val position = AtomicReference<String>()
      val latch = CountDownLatch(1)
      instrumentation.runOnMainSync { DebugLogsViewer.getSearchPosition(web) { position.set(it); latch.countDown() } }
      assertTrue(latch.await(10, TimeUnit.SECONDS))
      assertEquals("1 of 1", position.get())
      screenshot(web, "debug-viewer")
    }
  }

  private fun findDisplayedWebView(scenario: ActivityScenario<ComponentActivity>): WebView {
    val found = AtomicReference<WebView>()
    await {
      scenario.onActivity { activity ->
        val candidate = findWebView(activity.window.decorView)
        val visible = Rect()
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
          activity.window.decorView.hasWindowFocus() && candidate != null && candidate.isShown &&
          candidate.getGlobalVisibleRect(visible) && visible.width() > 0 && visible.height() > 0
        ) {
          found.set(candidate)
        }
      }
      found.get() != null
    }
    return found.get()
  }

  private fun findWebView(view: View): WebView? {
    if (view is WebView) return view
    if (view is ViewGroup) {
      for (i in 0 until view.childCount) {
        findWebView(view.getChildAt(i))?.let { return it }
      }
    }
    return null
  }

  private fun loadFixture(web: WebView) {
    instrumentation.runOnMainSync {
      // Activities may already have started their production URL; cancel it before loading the
      // deterministic offline fixture so a late network error cannot replace the test page.
      web.stopLoading()
      web.loadDataWithBaseURL("https://local.invalid/", html, "text/html", "UTF-8", null)
    }
  }

  private fun assertFixture(web: WebView, name: String) {
    assertWebSettings(web)
    await { javascript(web, "document.title") == "\"fixture-ready\"" }
    assertEquals("\"Synthetic WebView page\"", javascript(web, "document.getElementById('marker').textContent"))
    screenshot(web, name)
  }

  private fun assertWebSettings(web: WebView) {
    instrumentation.runOnMainSync {
      assertFalse(web.settings.allowContentAccess)
      assertTrue(web.settings.javaScriptEnabled)
    }
  }

  private fun currentUrl(web: WebView): String? {
    val url = AtomicReference<String>()
    instrumentation.runOnMainSync { url.set(web.url) }
    return url.get()
  }

  private fun clickCallback(web: WebView) { javascript(web, "document.getElementById('callback').click()") }

  private fun javascript(web: WebView, script: String): String? {
    val result = AtomicReference<String>()
    val latch = CountDownLatch(1)
    instrumentation.runOnMainSync { web.evaluateJavascript(script) { result.set(it); latch.countDown() } }
    assertTrue("WebView JavaScript timed out", latch.await(10, TimeUnit.SECONDS))
    return result.get()
  }

  private fun screenshot(web: WebView, name: String) {
    require(name in setOf("compose-captcha", "legacy-captcha", "recaptcha", "paypal", "stripe", "debug-viewer"))
    awaitVisualState(web)
    instrumentation.runOnMainSync {
      val visible = Rect()
      assertTrue("WebView is not visible in a focused window", web.isShown && web.hasWindowFocus() && web.getGlobalVisibleRect(visible) && visible.width() > 0 && visible.height() > 0)
    }
    assertEquals("Capture must show the active app window", context.packageName, awaitActiveAppWindow())
    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Unable to capture device screen" }
    try {
      val pipe = instrumentation.uiAutomation.executeShellCommandRwe("/system/bin/dd of=/data/local/tmp/webview-acceptance/$name.png status=none")
      val drains = Executors.newFixedThreadPool(2)
      try {
        val stdout = drains.submit<ByteArray> { ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { it.readBytes() } }
        val stderr = drains.submit<ByteArray> { ParcelFileDescriptor.AutoCloseInputStream(pipe[2]).use { it.readBytes() } }
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue("Screenshot writer produced unexpected output", stdout.get(15, TimeUnit.SECONDS).isEmpty())
        val error = stderr.get(15, TimeUnit.SECONDS).decodeToString()
        assertTrue("Screenshot writer failed: $error", error.isEmpty())
      } finally {
        pipe.forEach { runCatching { it.close() } }
        drains.shutdownNow()
      }
    } finally {
      bitmap.recycle()
    }
  }

  private fun awaitVisualState(web: WebView) {
    val rendered = CountDownLatch(1)
    val frameCommitted = CountDownLatch(1)
    instrumentation.runOnMainSync {
      web.postVisualStateCallback(SystemClock.uptimeMillis(), object : WebView.VisualStateCallback() {
        override fun onComplete(requestId: Long) {
          rendered.countDown()
          web.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              web.viewTreeObserver.registerFrameCommitCallback { frameCommitted.countDown() }
              web.invalidate()
            } else {
              web.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                  web.viewTreeObserver.removeOnDrawListener(this)
                  frameCommitted.countDown()
                }
              })
              web.invalidate()
            }
          }
        }
      })
    }
    assertTrue("WebView did not reach the current visual state", rendered.await(10, TimeUnit.SECONDS))
    assertTrue("WebView frame was not committed for capture", frameCommitted.await(10, TimeUnit.SECONDS))
  }

  private fun awaitActiveAppWindow(): String? {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    var rootPackage: String? = null
    while (System.nanoTime() < deadline) {
      val topWindow = instrumentation.uiAutomation.windows.firstOrNull()
      rootPackage = if (topWindow != null && topWindow.isActive && topWindow.isFocused) {
        topWindow.root?.packageName?.toString()
      } else {
        null
      }
      if (rootPackage == context.packageName) return rootPackage
      Thread.sleep(100)
    }
    return rootPackage
  }

  private fun await(predicate: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while (System.nanoTime() < deadline) {
      if (predicate()) return
      Thread.sleep(100)
    }
    throw AssertionError("Timed out waiting for WebView acceptance condition")
  }

  class LegacyCaptcha : CaptchaFragment() {
    override fun handleCaptchaToken(token: String) { LegacyCaptcha.token.get()?.set(token) }
    companion object { val token = AtomicReference<AtomicReference<String>?>() }
  }
}

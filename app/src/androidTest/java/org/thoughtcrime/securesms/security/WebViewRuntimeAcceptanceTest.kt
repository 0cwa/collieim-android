/*
 * Copyright 2026 Collie
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.security

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.provider.Settings
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.Navigation
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
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
import org.thoughtcrime.securesms.components.settings.app.AppSettingsFragmentDirections
import org.thoughtcrime.securesms.components.settings.app.subscription.donate.paypal.PayPalConfirmationDialogFragment
import org.thoughtcrime.securesms.components.settings.app.subscription.donate.paypal.PayPalConfirmationDialogFragmentArgs
import org.thoughtcrime.securesms.components.settings.app.subscription.donate.paypal.PayPalConfirmationResult
import org.thoughtcrime.securesms.components.settings.app.subscription.donate.stripe.Stripe3DSDialogFragment
import org.thoughtcrime.securesms.components.settings.app.subscription.donate.stripe.Stripe3DSDialogFragmentArgs
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/** Offline synthetic pages exercise the real WebView clients; no vendor request or payment is made. */
@RunWith(AndroidJUnit4::class)
class WebViewRuntimeAcceptanceTest {
  @get:Rule(order = 0) val harness = SignalActivityRule(othersCount = 0)
  @get:Rule(order = 1) val compose = createComposeRule()
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context get() = instrumentation.targetContext
  private val html = """<html><body><h1 id="marker">Synthetic WebView page</h1><script>document.title='fixture-ready';</script><a id="callback" href="signalcaptcha://synthetic-token">Continue</a></body></html>"""

  @Before fun setup() {
    check(context.applicationContext is SignalInstrumentationApplicationContext) { "Use the normal SignalTestRunner, not -PimoTests" }
    assertEquals("Airplane mode must be enabled before app startup", 1, Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON))
    assertFalse("Wi-Fi must be disabled before app startup", context.getSystemService(WifiManager::class.java).isWifiEnabled)
    assertEquals("Device must have no default network", null, context.getSystemService(ConnectivityManager::class.java).activeNetwork)
    MockEndpoints.responder.clear()
  }

  @After fun cleanup() {
    MockEndpoints.responder.clear()
    SignalStore.rateLimit.onProofAccepted()
  }

  @Test fun composeCaptchaEmitsSyntheticToken() {
    val event = AtomicReference<CaptchaScreenEvents>()
    val dataUrl = "data:text/html;charset=utf-8," + Uri.encode(html)
    compose.setContent { SignalTheme { CaptchaScreen(CaptchaState(captchaUrl = dataUrl), onEvent = { event.set(it) }) } }
    val web = findDisplayedWebView()
    assertFixture(web, "compose-captcha")
    clickCallback(web)
    await { event.get() == CaptchaScreenEvents.CaptchaCompleted("synthetic-token") }
  }

  @Test fun legacyCaptchaEmitsTokenAndNavigatesUp() {
    val token = AtomicReference<String>()
    LegacyCaptcha.token.set(token)
    try {
      ActivityScenario.launch<AppSettingsActivity>(AppSettingsActivity.home(context)).use { scenario ->
        lateinit var web: WebView
        lateinit var before: String
        scenario.onActivity { activity ->
          val nav = Navigation.findNavController(activity, R.id.nav_host_fragment)
          nav.navigate(AppSettingsFragmentDirections.actionDirectToChatsSettingsFragment())
          before = nav.currentDestination!!.id.toString()
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
          assertTrue("captcha callback should navigate up", Navigation.findNavController(activity, R.id.nav_host_fragment).currentDestination!!.id.toString() != before)
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
          arguments = PayPalConfirmationDialogFragmentArgs(Uri.parse("about:blank")).toBundle()
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
          arguments = Stripe3DSDialogFragmentArgs(Uri.parse("about:blank"), Uri.parse("https://return.invalid/complete"), payment).toBundle()
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

  private fun findDisplayedWebView(): WebView {
    val found = AtomicReference<WebView>()
    onView(isAssignableFrom(WebView::class.java)).perform(object : ViewAction {
      override fun getConstraints(): Matcher<View> = isAssignableFrom(WebView::class.java)
      override fun getDescription(): String = "find the production captcha WebView"
      override fun perform(uiController: UiController, view: View) { found.set(view as WebView) }
    })
    return found.get()
  }

  private fun loadFixture(web: WebView) {
    instrumentation.runOnMainSync { web.loadDataWithBaseURL("https://local.invalid/", html, "text/html", "UTF-8", null) }
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
    instrumentation.runOnMainSync {
      assertTrue("WebView has no rendered size", web.width > 0 && web.height > 0)
      val bitmap = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
      web.draw(Canvas(bitmap))
      val dir = File(context.externalMediaDirs.first(), "webview-acceptance").apply { mkdirs() }
      File(dir, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
      bitmap.recycle()
    }
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

/*
 * Copyright 2026 Collie
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.security

import android.content.Context
import android.net.ConnectivityManager
import android.provider.Settings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.debuglogsviewer.DebugLogsViewer
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.testing.SignalActivityRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Run on an offline device; all log text and the replacement document are synthetic. */
@RunWith(AndroidJUnit4::class)
class DebugViewerBoundaryTest {
  @get:Rule val harness = SignalActivityRule(othersCount = 0)
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context get() = instrumentation.targetContext

  @Test fun viewerKeepsLogsInsideBundledDocument() {
    assertEquals(1, Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON))
    assertEquals(null, context.getSystemService(Context.CONNECTIVITY_SERVICE).let { it as ConnectivityManager }.activeNetwork)
    ActivityScenario.launch<AppSettingsActivity>(AppSettingsActivity.home(context)).use { scenario ->
      val finished = CountDownLatch(1)
      val finishedCount = AtomicInteger()
      lateinit var web: WebView
      scenario.onActivity { activity ->
        web = WebView(activity)
        activity.addContentView(web, FrameLayout.LayoutParams(-1, -1))
        DebugLogsViewer.initWebView(web, activity) { finishedCount.incrementAndGet(); finished.countDown() }
      }
      assertTrue(finished.await(20, TimeUnit.SECONDS))
      DebugLogsViewer.appendLines(web, "synthetic alpha https://viewer-boundary.invalid/test\n")
      assertTrue(DebugLogsViewer.readLogs(web).nextChunk(10)!!.contains("synthetic alpha"))
      assertEquals("\"https://viewer-boundary.invalid/test _blank\"", javascript(web, """
        (() => { let opened = ''; const original = window.open;
          window.open = (url, target) => { opened = url + ' ' + target; };
          editor.selection.moveCursorTo(0, 25); editor.execCommand('openlink');
          window.open = original; return opened; })()
      """.trimIndent()))
      // A programmatic replacement deliberately bypasses WebViewClient navigation handling.
      // It tests the separate log-delivery guard, not reachability from the bundled editor.
      instrumentation.runOnMainSync {
        web.loadDataWithBaseURL("https://viewer-boundary.invalid/", "<script>function appendLines(s){document.title=s;} function readLines(){return 'forged';}</script>", "text/html", "UTF-8", null)
      }
      await { javascript(web, "typeof appendLines === 'function' && location.href === 'https://viewer-boundary.invalid/'") == "true" }
      DebugLogsViewer.appendLines(web, "synthetic secret")
      assertEquals("\"\"", javascript(web, "document.title"))
      assertEquals(null, DebugLogsViewer.readLogs(web).nextChunk(10))
      assertEquals(1, finishedCount.get())
    }
  }

  private fun javascript(web: WebView, script: String): String? {
    val result = AtomicReference<String>()
    val latch = CountDownLatch(1)
    instrumentation.runOnMainSync { web.evaluateJavascript(script) { result.set(it); latch.countDown() } }
    assertTrue(latch.await(10, TimeUnit.SECONDS))
    return result.get()
  }

  private fun await(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while (System.nanoTime() < deadline) {
      if (condition()) return
      Thread.sleep(100)
    }
    throw AssertionError("WebView did not load the synthetic replacement document")
  }
}

package org.thoughtcrime.securesms.testing

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import java.util.concurrent.TimeUnit
import androidx.test.runner.AndroidJUnitRunner
import org.thoughtcrime.securesms.SignalInstrumentationApplicationContext

/**
 * Custom runner that replaces application with [SignalInstrumentationApplicationContext].
 *
 * Before the application is created, it reads any [RemoteConfigForTest] declared on the
 * about-to-run test (passed by the Orchestrator as the `class` argument, `pkg.Class#method`) and
 * stages the values in [TestRemoteConfig] so the app can seed them into `RemoteConfig` at startup.
 */
@Suppress("unused")
class SignalTestRunner : AndroidJUnitRunner() {
  override fun onCreate(arguments: Bundle?) {
    if (arguments?.getString("class")?.contains("org.thoughtcrime.securesms.security.WebViewRuntimeAcceptanceTest") == true) {
      enforceWebViewOffline("runner-before-application")
    }
    TestRemoteConfig.pending = parseRemoteConfig(arguments?.getString("class"))
    super.onCreate(arguments)
  }

  fun enforceWebViewOffline(stage: String) {
    val context = targetContext
    val wifi = context.getSystemService(WifiManager::class.java)
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    fun snapshot(): String {
      val networks = connectivity.allNetworks.map { network ->
        "$network=${connectivity.getNetworkCapabilities(network)}"
      }
      return "airplane=${Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, -1)} wifiState=${wifi.wifiState} active=${connectivity.activeNetwork} networks=$networks"
    }
    Log.i("WebViewOfflineProbe", "$stage before: ${snapshot()}")
    listOf("cmd connectivity airplane-mode enable", "svc wifi disable", "svc data disable").forEach { command ->
      ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    var ready: Boolean
    do {
      ready = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, -1) == 1 &&
        wifi.wifiState == WifiManager.WIFI_STATE_DISABLED && connectivity.activeNetwork == null &&
        connectivity.allNetworks.none { connectivity.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true }
      if (!ready) Thread.sleep(100)
    } while (!ready && System.nanoTime() < deadline)
    val receipt = snapshot()
    Log.i("WebViewOfflineProbe", "$stage after: $receipt")
    check(ready) { "Offline readiness failed: $stage $receipt" }
  }

  override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application {
    return super.newApplication(cl, SignalInstrumentationApplicationContext::class.java.name, context)
  }

  /**
   * Resolves [RemoteConfigForTest] annotations from the targeted test(s). [classArg] is the
   * instrumentation `class` argument: a comma-separated list of `pkg.Class` or `pkg.Class#method`.
   * Method-level flags override class-level flags for the same key. Reflection failures (e.g. a
   * whole-suite run with no `class` arg) fall back to no overrides.
   */
  private fun parseRemoteConfig(classArg: String?): Map<String, Any> {
    if (classArg.isNullOrBlank()) {
      return emptyMap()
    }

    val flags = mutableMapOf<String, Any>()

    for (entry in classArg.split(",")) {
      val (className, methodName) = entry.trim().split("#", limit = 2).let { it[0] to it.getOrNull(1) }

      try {
        // initialize = false: only read annotations, don't run the test class's static init this early.
        val testClass = Class.forName(className, false, javaClass.classLoader)
        val method = methodName?.let { name -> testClass.declaredMethods.firstOrNull { it.name == name } }

        // Class annotation first, then method annotation so method-level flags override class-level ones.
        listOfNotNull(
          testClass.getAnnotation(RemoteConfigForTest::class.java),
          method?.getAnnotation(RemoteConfigForTest::class.java)
        ).forEach { annotation ->
          annotation.flags.forEach { flags[it.flag.key] = it.value }
          annotation.rawFlags.forEach { flags[it.key] = it.value }
        }
      } catch (_: ReflectiveOperationException) {
        // Class/method not resolvable in this run; leave overrides as-is.
      }
    }

    return flags
  }
}

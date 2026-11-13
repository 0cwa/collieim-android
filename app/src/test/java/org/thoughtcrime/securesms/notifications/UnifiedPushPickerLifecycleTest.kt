package org.thoughtcrime.securesms.notifications

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.reactivex.rxjava3.subjects.BehaviorSubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises the Android activity/result lifecycle with the same guard and replay ordering as MainActivity. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [28])
class UnifiedPushPickerLifecycleTest {
  class Host : ComponentActivity() {
    private val guard = UnifiedPushPickerLaunchGuard()
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { guard.onResult() }
    override fun onCreate(state: Bundle?) {
      guard.restore(state?.getBoolean("outstanding") == true, state?.getBoolean("handled") == true)
      super.onCreate(state)
    }
    override fun onSaveInstanceState(state: Bundle) {
      state.putBoolean("outstanding", guard.outstanding)
      state.putBoolean("handled", guard.promptHandled)
      super.onSaveInstanceState(state)
    }
    fun replayPrompt() { if (guard.tryLaunch()) launcher.launch(Intent("test.picker")) }
  }

  @Test fun `replay before cancelled result does not relaunch`() = exercise(resultFirst = false)
  @Test fun `cancelled result before replay does not relaunch`() = exercise(resultFirst = true)

  private fun exercise(resultFirst: Boolean) {
    val checks = BehaviorSubject.createDefault(Unit)
    val first = Robolectric.buildActivity(Host::class.java).setup()
    val old = first.get()
    val subscription = checks.subscribe { old.replayPrompt() }
    val started = shadowOf(old).nextStartedActivityForResult
    assertEquals("test.picker", started.intent.action)
    val state = Bundle()
    first.saveInstanceState(state).pause().stop().destroy()
    subscription.dispose()
    val recreated = Robolectric.buildActivity(Host::class.java).create(state).start().resume()
    val host = recreated.get()
    if (resultFirst) host.onActivityResult(started.requestCode, Activity.RESULT_CANCELED, null)
    val replay = checks.subscribe { host.replayPrompt() }
    if (!resultFirst) host.onActivityResult(started.requestCode, Activity.RESULT_CANCELED, null)
    assertNull(shadowOf(host).nextStartedActivityForResult)
    replay.dispose()
    recreated.pause().stop().destroy()
  }
}

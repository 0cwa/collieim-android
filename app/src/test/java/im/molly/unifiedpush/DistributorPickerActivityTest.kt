package im.molly.unifiedpush

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.appcompat.app.AlertDialog
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import im.molly.unifiedpush.receiver.LockedPushState
import im.molly.unifiedpush.receiver.UnifiedPushReceiver
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.signal.core.util.logging.Log
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PublicKeySet

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class DistributorPickerActivityTest {
  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private var saved: String? = null
  private var pending: LockedPushState.Endpoint? = null
  private var registration: LockedPushState.PendingRegistration? = null

  @Before fun setup() {
    Log.initialize(SystemOutLogger())
    mockkStatic(UnifiedPush::class, KeyCachingService::class)
    mockkObject(LockedPushState, UnifiedPushDistributor)
    UnifiedPushLifecycle.resetForTests()
    saved = "org.example.old"
    pending = null
    registration = null
    every { KeyCachingService.isLocked() } returns true
    every { UnifiedPush.getDistributors(any()) } returns emptyList()
    every { UnifiedPushDistributor.savedDistributorSnapshot(any()) } answers { saved }
    every { UnifiedPush.saveDistributor(any(), any()) } answers { saved = secondArg() }
    every { LockedPushState.beginEndpointWrite(any()) } returns true
    every { LockedPushState.finishEndpointWrite(any()) } returns true
    every { LockedPushState.pendingRegistration(any()) } answers { registration }
    every { LockedPushState.clearRegistrationIfCurrent(any(), any()) } answers { registration = null; true }
    every { LockedPushState.trySavePending(any(), any()) } answers { pending = secondArg(); true }
    every { LockedPushState.trySaveSelection(any(), any(), any()) } answers { pending = secondArg(); registration = LockedPushState.PendingRegistration(thirdArg(), false); true }
    every { LockedPushState.markRegistrationReady(any(), any()) } returns true
  }

  @After fun cleanup() {
    unmockkObject(LockedPushState, UnifiedPushDistributor)
    unmockkStatic(UnifiedPush::class, KeyCachingService::class)
  }

  @Test fun `zero providers finish without prompting or selecting`() {
    val activity = launch(useDefault = false)
    assertTrue(activity.isFinishing)
    verify(exactly = 0) { UnifiedPush.saveDistributor(any(), any()) }
  }

  @Test fun `one known distributor uses chooser and records tombstone before saving`() {
    chooseKnownDistributor(listOf(context.packageName))
  }

  @Test fun `multiple known distributors still use chooser`() {
    chooseKnownDistributor(listOf(context.packageName, "org.example.other"))
  }

  @Test fun `cancelled chooser never selects a provider`() {
    addResolver()
    every { UnifiedPush.getDistributors(any()) } returns listOf(context.packageName)
    val activity = launch(useDefault = false)
    val started = shadowOf(activity).nextStartedActivityForResult
    assertEquals(Intent.ACTION_CHOOSER, started.intent.action)
    activity.onActivityResult(started.requestCode, Activity.RESULT_CANCELED, resultIntent())
    assertNull(pending)
    verify(exactly = 0) { UnifiedPush.saveDistributor(any(), any()) }
  }

  @Test fun `pending intent from an unknown package cannot be selected`() {
    addResolver()
    every { UnifiedPush.getDistributors(any()) } returns listOf("org.example.other")
    val activity = launch(useDefault = false)
    val started = shadowOf(activity).nextStartedActivityForResult
    activity.onActivityResult(started.requestCode, Activity.RESULT_OK, resultIntent())
    assertNull(pending)
    verify(exactly = 0) { UnifiedPush.saveDistributor(any(), any()) }
  }

  @Test fun `provider disappearing before selection is not saved`() {
    addResolver()
    every { UnifiedPush.getDistributors(any()) } returnsMany listOf(listOf(context.packageName), emptyList())
    val activity = launch(useDefault = false)
    val started = shadowOf(activity).nextStartedActivityForResult
    activity.onActivityResult(started.requestCode, Activity.RESULT_OK, resultIntent())
    verify(exactly = 0) { UnifiedPush.saveDistributor(any(), any()) }
  }

  @Test fun `endpoint received during the save stays newer than the tombstone`() {
    addResolver()
    every { UnifiedPush.getDistributors(any()) } returns listOf(context.packageName)
    every { UnifiedPush.saveDistributor(any(), any()) } answers {
      assertEquals(LockedPushState.Endpoint(null, null, null, "default"), pending)
      UnifiedPushReceiver().onNewEndpoint(context, PushEndpoint("https://push.example.invalid/new", PublicKeySet("p", "a")), "default")
      saved = secondArg()
    }
    val activity = launch(useDefault = false)
    val started = shadowOf(activity).nextStartedActivityForResult
    activity.onActivityResult(started.requestCode, Activity.RESULT_OK, resultIntent())
    assertEquals(LockedPushState.Endpoint("https://push.example.invalid/new", "p", "a", "default"), pending)
  }

  @Test fun `pending storage failure prevents a distributor change`() {
    addResolver()
    every { UnifiedPush.getDistributors(any()) } returns listOf(context.packageName)
    every { LockedPushState.trySaveSelection(any(), any(), any()) } returns false
    val activity = launch(useDefault = false)
    val started = shadowOf(activity).nextStartedActivityForResult
    activity.onActivityResult(started.requestCode, Activity.RESULT_OK, resultIntent())
    assertEquals("org.example.old", saved)
    verify(exactly = 0) { UnifiedPush.saveDistributor(any(), any()) }
  }

  @Test fun `partial SDK save failure is not reported as success`() {
    addResolver()
    every { UnifiedPush.getDistributors(any()) } returns listOf(context.packageName)
    every { UnifiedPush.saveDistributor(any(), any()) } answers {
      saved = secondArg()
      throw IllegalStateException("fixture")
    }
    val activity = launch(useDefault = false)
    val started = shadowOf(activity).nextStartedActivityForResult
    activity.onActivityResult(started.requestCode, Activity.RESULT_OK, resultIntent())
    assertEquals(context.packageName, saved)
    assertNull(shadowOf(activity).resultIntent)
    verify(exactly = 1) { LockedPushState.markRegistrationReady(any(), any()) }
  }

  @Test fun `same distributor is a no-op`() {
    addResolver()
    saved = context.packageName
    every { UnifiedPush.getDistributors(any()) } returns listOf(context.packageName)
    val activity = launch(useDefault = false)
    val started = shadowOf(activity).nextStartedActivityForResult
    activity.onActivityResult(started.requestCode, Activity.RESULT_OK, resultIntent())
    assertNull(pending)
    verify(exactly = 0) { UnifiedPush.saveDistributor(any(), any()) }
  }

  @Test fun `default mode sends a direct link and cancellation is a no-op`() {
    addResolver()
    every { UnifiedPush.getDistributors(any()) } returns listOf(context.packageName)
    val activity = launch(useDefault = true)
    val started = shadowOf(activity).nextStartedActivityForResult
    assertEquals(Intent.ACTION_VIEW, started.intent.action)
    activity.onActivityResult(started.requestCode, Activity.RESULT_CANCELED, null)
    assertNull(pending)
    verify(exactly = 0) { UnifiedPush.saveDistributor(any(), any()) }
  }

  @Test fun `default link success selects the known provider`() {
    addResolver()
    every { UnifiedPush.getDistributors(any()) } returns listOf(context.packageName)
    val activity = launch(useDefault = true)
    val started = shadowOf(activity).nextStartedActivityForResult
    assertEquals(Intent.ACTION_VIEW, started.intent.action)
    activity.onActivityResult(started.requestCode, Activity.RESULT_OK, resultIntent())
    assertEquals(context.packageName, saved)
    assertEquals(LockedPushState.Endpoint(null, null, null, "default"), pending)
  }

  @Test fun `missing pending intent cannot clear the route`() {
    addResolver()
    every { UnifiedPush.getDistributors(any()) } returns listOf(context.packageName)
    val activity = launch(useDefault = false)
    val started = shadowOf(activity).nextStartedActivityForResult
    activity.onActivityResult(started.requestCode, Activity.RESULT_OK, Intent())
    assertNull(pending)
    verify(exactly = 0) { UnifiedPush.saveDistributor(any(), any()) }
  }

  @Test fun `no link resolver offers installed legacy distributor`() {
    every { UnifiedPush.getDistributors(any()) } returns listOf(context.packageName)
    val activity = launch(useDefault = false)
    shadowOf(Looper.getMainLooper()).idle()
    val dialog = ShadowDialog.getLatestDialog() as AlertDialog
    val list = dialog.listView
    list.performItemClick(list.getChildAt(0), 0, list.adapter.getItemId(0))
    verify(exactly = 1) { UnifiedPush.saveDistributor(activity, context.packageName) }
  }

  @Test fun `cancelling legacy list never selects a distributor`() {
    every { UnifiedPush.getDistributors(any()) } returns listOf(context.packageName)
    launch(useDefault = false)
    shadowOf(Looper.getMainLooper()).idle()
    val dialog = ShadowDialog.getLatestDialog() as AlertDialog
    dialog.cancel()
    assertNull(pending)
    verify(exactly = 0) { UnifiedPush.saveDistributor(any(), any()) }
  }

  @Test fun `external picker cancellation is routed after activity recreation without relaunch`() {
    addResolver()
    every { UnifiedPush.getDistributors(any()) } returns listOf(context.packageName)
    val intent = UnifiedPushDefaultDistributorLinkActivity.Contract(false).createIntent(context, Unit)
    val first = Robolectric.buildActivity(UnifiedPushDefaultDistributorLinkActivity::class.java, intent)
    first.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
    val original = first.create().start().resume().get()
    val started = shadowOf(original).nextStartedActivityForResult
    val state = Bundle()
    first.saveInstanceState(state).pause().stop().destroy()
    val second = Robolectric.buildActivity(UnifiedPushDefaultDistributorLinkActivity::class.java, intent)
    second.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
    val restored = second.create(state).start().resume().get()
    assertNull(shadowOf(restored).nextStartedActivityForResult)
    restored.onActivityResult(started.requestCode, Activity.RESULT_CANCELED, null)
    assertTrue(restored.isFinishing)
    verify(exactly = 0) { UnifiedPush.saveDistributor(any(), any()) }
  }

  @Test fun `legacy list is restored after activity recreation`() {
    every { UnifiedPush.getDistributors(any()) } returns listOf(context.packageName)
    val intent = UnifiedPushDefaultDistributorLinkActivity.Contract(false).createIntent(context, Unit)
    val first = Robolectric.buildActivity(UnifiedPushDefaultDistributorLinkActivity::class.java, intent)
    first.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
    first.create().start().resume()
    shadowOf(Looper.getMainLooper()).idle()
    val state = Bundle()
    first.saveInstanceState(state).pause().stop().destroy()
    val second = Robolectric.buildActivity(UnifiedPushDefaultDistributorLinkActivity::class.java, intent)
    second.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
    val activity = second.create(state).start().resume().get()
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(activity.supportFragmentManager.findFragmentByTag("legacy_distributor_dialog") != null)
    verify(exactly = 0) { UnifiedPush.saveDistributor(any(), any()) }
  }

  @Test fun `unrelated request result cannot clear the route`() {
    addResolver()
    every { UnifiedPush.getDistributors(any()) } returns listOf(context.packageName)
    val activity = launch(useDefault = true)
    val started = shadowOf(activity).nextStartedActivityForResult
    activity.onActivityResult(started.requestCode + 1, Activity.RESULT_OK, resultIntent())
    assertNull(pending)
    verify(exactly = 0) { UnifiedPush.saveDistributor(any(), any()) }
  }

  private fun chooseKnownDistributor(distributors: List<String>) {
    addResolver()
    every { UnifiedPush.getDistributors(any()) } returns distributors
    every { UnifiedPush.saveDistributor(any(), any()) } answers {
      assertEquals(LockedPushState.Endpoint(null, null, null, "default"), pending)
      saved = secondArg()
    }
    val activity = launch(useDefault = false)
    val started = shadowOf(activity).nextStartedActivityForResult
    assertEquals(Intent.ACTION_CHOOSER, started.intent.action)
    activity.onActivityResult(started.requestCode, Activity.RESULT_OK, resultIntent())
    verify(exactly = 1) { UnifiedPush.saveDistributor(activity, context.packageName) }
  }

  private fun launch(useDefault: Boolean): UnifiedPushDefaultDistributorLinkActivity {
    val intent = UnifiedPushDefaultDistributorLinkActivity.Contract(useDefault).createIntent(context, Unit)
    val controller = Robolectric.buildActivity(UnifiedPushDefaultDistributorLinkActivity::class.java, intent)
    controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
    return controller.create().start().resume().get()
  }

  private fun addResolver() {
    val info = ResolveInfo().apply {
      activityInfo = ActivityInfo().apply { packageName = "android"; name = "Resolver"; exported = true }
    }
    shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_VIEW, Uri.parse("unifiedpush://link")), info)
  }

  private fun resultIntent(): Intent = Intent().putExtra(
    "pi", PendingIntent.getBroadcast(context, 0, Intent("fixture"), PendingIntent.FLAG_IMMUTABLE)
  )
}

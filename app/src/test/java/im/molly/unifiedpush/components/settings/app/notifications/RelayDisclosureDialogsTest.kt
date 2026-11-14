package im.molly.unifiedpush.components.settings.app.notifications

import android.app.Application
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import im.molly.unifiedpush.model.MollySocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.thoughtcrime.securesms.R

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RelayDisclosureDialogsTest {
  private lateinit var controller: ActivityController<FragmentActivity>
  private lateinit var activity: FragmentActivity
  private val address = "https://push.example.org:9443/relay?instance=one"

  @Before
  fun setUp() {
    controller = Robolectric.buildActivity(FragmentActivity::class.java)
    activity = controller.get()
    activity.setTheme(R.style.Theme_Molly_Material3_Light)
    controller.setup()
  }

  @After
  fun tearDown() {
    ShadowDialog.getLatestDialog()?.dismiss()
    controller.pause().stop().destroy()
  }

  @Test
  fun `each disclosure route explains trust before its action can run`() {
    for (show in routes()) {
      var confirmed = 0
      val dialog = show { confirmed++ }

      assertEquals(0, confirmed)
      assertTrue(message(dialog).contains(activity.getString(R.string.RelayDisclosureDialog__trust_message)))
      assertEquals(activity.getString(android.R.string.cancel), dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())

      dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals(1, confirmed)
    }
  }

  @Test
  fun `cancel button and dialog cancellation never authorize any disclosure route`() {
    for (show in routes()) {
      var confirmed = 0
      show { confirmed++ }.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals(0, confirmed)

      show { confirmed++ }.cancel()
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals(0, confirmed)
    }
  }

  @Test
  fun `known scanned relay shows its full destination`() {
    val dialog = RelayDisclosureDialogs.showRelay(activity, MollySocket.WebServer("v".repeat(87), address)) {}

    assertTrue(message(dialog).contains(address))
    assertFalse(message(dialog).contains("v".repeat(87)))
  }

  @Test
  fun `manual enrollment copy and export do not invent a destination`() {
    val manual = RelayDisclosureDialogs.showRelay(activity, MollySocket.AirGapped("v".repeat(87))) {}
    assertTrue(message(manual).contains(activity.getString(R.string.RelayDisclosureDialog__manual_destination)))
    assertFalse(message(manual).contains(address))
    manual.dismiss()

    val copy = RelayDisclosureDialogs.showCopy(activity) {}
    assertTrue(message(copy).contains(activity.getString(R.string.RelayDisclosureDialog__copy_message)))
    assertFalse(message(copy).contains(address))
    copy.dismiss()

    val export = RelayDisclosureDialogs.showExport(activity) {}
    assertTrue(message(export).contains(activity.getString(R.string.RelayDisclosureDialog__export_message)))
    assertFalse(message(export).contains(address))
  }

  private fun message(dialog: AlertDialog): String = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()

  private fun routes(): List<(() -> Unit) -> AlertDialog> = listOf(
    { action -> RelayDisclosureDialogs.showRelay(activity, MollySocket.WebServer("v".repeat(87), address), action) },
    { action -> RelayDisclosureDialogs.showRelay(activity, MollySocket.AirGapped("v".repeat(87)), action) },
    { action -> RelayDisclosureDialogs.showCopy(activity, action) },
    { action -> RelayDisclosureDialogs.showExport(activity, action) },
  )
}

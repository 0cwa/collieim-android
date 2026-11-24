package org.thoughtcrime.securesms.groups.v2.admission

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.conversation.v2.ConversationActivity
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testing.SignalActivityRule

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class HeldGroupReviewProofTest {
  @get:Rule val harness = SignalActivityRule(othersCount = 1, createGroup = true, networkEnabled = false)

  @Test
  fun heldGroupOffersReviewAndCancelKeepsItHeld() {
    val context = harness.context
    val group = checkNotNull(harness.group)
    val account = checkNotNull(SignalStore.account.aci).toString()
    val before = SignalStore.groupAdmission.read(account)
    val oldSecurity = SignalStore.settings.isScreenSecurityEnabled
    SignalStore.settings.isScreenSecurityEnabled = false
    try {
      SignalStore.groupAdmission.update(account) { state ->
        state.copy(
          settings = GroupAdmissionSettings(GroupAdmissionMode.ASK_EVERY_TIME),
          records = state.records + (group.groupId.toString() to AdmissionRecord(
            AdmissionMembership(MembershipKind.FULL, "member:0", 0, 0),
            AdmissionStatus.HELD,
          )),
        )
      }
      assertTrue(GroupAdmission.isHeld(group.groupId))
      val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(group.recipientId))
      val intent = ConversationIntents.createBuilderSync(context, group.recipientId, threadId).build()
      ActivityScenario.launch<ConversationActivity>(intent).use { scenario ->
        scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        onView(withText(R.string.MessageRequestBottomView_join_this_group_they_wont_know_youve_seen_their_messages_until_you_accept))
          .check(matches(isDisplayed()))
        capture("beta-held-group-review")
        onView(withText(R.string.MessageRequestBottomView_accept)).perform(click())
        onView(withText(R.string.MessageRequestBottomView_join_group)).inRoot(isDialog())
          .check(matches(isDisplayed()))
        capture("beta-held-group-confirmation")
        onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click())
        onView(withText(R.string.MessageRequestBottomView_accept)).check(matches(isDisplayed()))
        assertTrue(GroupAdmission.isHeld(group.groupId))
      }
    } finally {
      SignalStore.groupAdmission.update(account) { before }
      SignalStore.settings.isScreenSecurityEnabled = oldSecurity
    }
  }

  private fun capture(name: String) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    automation.waitForIdle(500, 5_000)
    val bitmap = checkNotNull(automation.takeScreenshot())
    val resolver = harness.context.contentResolver
    try {
      val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/collie-settings-navigation/")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }))
      checkNotNull(resolver.openOutputStream(uri)).use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
      }
      resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    } finally {
      bitmap.recycle()
    }
  }
}

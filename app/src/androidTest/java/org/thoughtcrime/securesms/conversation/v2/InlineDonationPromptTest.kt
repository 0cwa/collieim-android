/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.core.models.badges.Badge
import org.signal.core.models.recipients.RecipientId
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testing.InAppPaymentsRule
import org.thoughtcrime.securesms.testing.SignalActivityRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Historical release-channel donation requests remain visible without a native checkout button. */
@RunWith(AndroidJUnit4::class)
class InlineDonationPromptTest {

  @get:Rule
  val harness = SignalActivityRule()

  @get:Rule
  val iapRule = InAppPaymentsRule()

  private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Before
  fun setUp() {
    SignalStore.inAppPayments.setLastEndOfPeriod(0L)
    SignalDatabase.recipients.setBadges(Recipient.self().id, emptyList())
    Recipient.self().fresh()
  }

  @Test
  fun nonSustainer_donationButtonIsHidden() {
    seedDonationRequest()

    assertDonationButtonHidden()
  }

  @Test
  fun sustainer_donationButtonIsHidden() {
    SignalStore.inAppPayments.setLastEndOfPeriod(TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) + TimeUnit.DAYS.toSeconds(30))
    SignalDatabase.recipients.setBadges(Recipient.self().id, listOf(donorBadge()))
    Recipient.self().fresh()

    seedDonationRequest()

    assertDonationButtonHidden()
  }

  private fun assertDonationButtonHidden() {
    val activity = launchConversation()
    val donateButton = await(description = "historical donation item") {
      activity.findViewById<View>(R.id.conversation_update_action)
    }
    assertThat(runOnMainSync { donateButton.visibility }).isEqualTo(View.GONE)
  }

  private fun seedDonationRequest() {
    val recipientId: RecipientId = SignalDatabase.recipients.insertReleaseChannelRecipient()
    SignalStore.releaseChannel.setReleaseChannelRecipientId(recipientId)

    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(recipientId))
    SignalDatabase.messages.insertBoostRequestMessage(recipientId, threadId)
  }

  private fun launchConversation(): MainActivity {
    val recipientId = SignalStore.releaseChannel.releaseChannelRecipientId!!
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(recipientId))

    val conversationIntent = ConversationIntents.createBuilder(context, recipientId, threadId).blockingGet().build()
    val launchIntent = Intent(context, MainActivity::class.java).apply {
      action = ConversationIntents.ACTION
      putExtras(conversationIntent)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val app = context.applicationContext as Application
    val resumed = CountDownLatch(1)
    val holder = arrayOfNulls<MainActivity>(1)
    val callbacks = object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

      // Capture on resume rather than create: a CLEAR_TOP|SINGLE_TOP conversation intent may be
      // delivered to an existing MainActivity via onNewIntent, which never fires onActivityCreated.
      override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) {
          holder[0] = activity
          resumed.countDown()
        }
      }

      override fun onActivityStarted(activity: Activity) = Unit
      override fun onActivityPaused(activity: Activity) = Unit
      override fun onActivityStopped(activity: Activity) = Unit
      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
      override fun onActivityDestroyed(activity: Activity) = Unit
    }
    app.registerActivityLifecycleCallbacks(callbacks)

    app.startActivity(launchIntent)
    if (!resumed.await(15, TimeUnit.SECONDS)) {
      app.unregisterActivityLifecycleCallbacks(callbacks)
      error("MainActivity did not reach RESUMED within 15s")
    }
    app.unregisterActivityLifecycleCallbacks(callbacks)

    return holder[0] ?: error("MainActivity was not captured")
  }

  private fun <T> await(
    timeoutMs: Long = 15_000,
    pollMs: Long = 50,
    description: String,
    supplier: () -> T?
  ): T {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val result = runOnMainSync(supplier)
      if (result != null) return result
      Thread.sleep(pollMs)
    }
    error("Timed out after ${timeoutMs}ms waiting for $description")
  }

  private fun <T> runOnMainSync(block: () -> T): T {
    var result: Result<T> = Result.failure(IllegalStateException("runOnMainSync produced no result"))
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
    return result.getOrThrow()
  }

  private fun donorBadge(): Badge {
    return Badge(
      id = "test-donor-badge",
      category = Badge.Category.Donor,
      name = "Signal Sustainer",
      description = "",
      imageUrl = Uri.EMPTY,
      imageDensity = "xxhdpi",
      expirationTimestamp = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30),
      visible = true,
      duration = TimeUnit.DAYS.toMillis(30)
    )
  }

}

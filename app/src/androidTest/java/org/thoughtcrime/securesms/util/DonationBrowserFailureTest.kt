/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.util

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assertk.assertThat
import assertk.assertions.isFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DonationBrowserFailureTest {
  @Test fun reportsFailureWhenNoBrowserCanHandleTheLink() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val noBrowser = object : ContextWrapper(instrumentation.targetContext) {
      override fun startActivity(intent: Intent) {
        throw ActivityNotFoundException()
      }
    }
    instrumentation.runOnMainSync {
      assertThat(CommunicationActions.tryOpenBrowserLink(noBrowser, "https://liberapay.com/Collie")).isFalse()
    }
  }
}

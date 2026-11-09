/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.registration.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class RegistrationIntentsTest {

  @Test
  fun `re-registration marker is explicit and absent for fresh registration`() {
    val context = ApplicationProvider.getApplicationContext<Application>()

    val reRegistration = RegistrationIntents.newIntentForReRegistration(context)
    val newRegistration = RegistrationIntents.newIntentForNewRegistration(context)
    val reLink = RegistrationIntents.newIntentForReLinkDevice(context)

    assertTrue(reRegistration.getBooleanExtra(RegistrationActivity.IS_REREGISTRATION_EXTRA, false))
    assertFalse(newRegistration.getBooleanExtra(RegistrationActivity.IS_REREGISTRATION_EXTRA, false))
    assertFalse(reLink.getBooleanExtra(RegistrationActivity.IS_REREGISTRATION_EXTRA, false))
  }
}

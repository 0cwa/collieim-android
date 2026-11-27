/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database

import android.app.Application
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.recipients.RecipientId
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.groups.v2.admission.GroupAdmission
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.RecipientTestRule

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class RecipientTableGroupAdmissionTest {

  @get:Rule
  val recipients = RecipientTestRule()

  @Before
  fun setUp() {
    mockkStatic(GroupAdmission::class)
    every { GroupAdmission.isHeld(any<GroupId.V2>()) } returns false
  }

  @After
  fun tearDown() {
    unmockkStatic(GroupAdmission::class)
  }

  @Test
  fun heldGroupCannotEnableSharingUntilAdmissionChanges() {
    val group = recipients.createGroup()
    SignalDatabase.recipients.setProfileSharing(group.recipientId, false)
    every { GroupAdmission.isHeld(group.groupId) } returns true

    SignalDatabase.recipients.setProfileSharing(group.recipientId, true)
    assertFalse(SignalDatabase.recipients.getRecord(group.recipientId).profileSharing)

    every { GroupAdmission.isHeld(group.groupId) } returns false
    SignalDatabase.recipients.setProfileSharing(group.recipientId, true)
    assertTrue(SignalDatabase.recipients.getRecord(group.recipientId).profileSharing)
  }

  @Test
  fun heldGroupCanAlwaysDisableSharing() {
    val group = recipients.createGroup()
    every { GroupAdmission.isHeld(group.groupId) } returns true

    SignalDatabase.recipients.setProfileSharing(group.recipientId, false)

    assertFalse(SignalDatabase.recipients.getRecord(group.recipientId).profileSharing)
  }

  @Test
  fun individualSharingDoesNotResolveRecipientThroughAdmission() {
    val individual = recipients.createRecipient("Alice", profileSharing = false)

    SignalDatabase.recipients.setProfileSharing(individual, true)

    assertTrue(SignalDatabase.recipients.getRecord(individual).profileSharing)
    verify(exactly = 0) { GroupAdmission.isHeld(any<RecipientId>()) }
    verify(exactly = 0) { GroupAdmission.isHeld(any<Recipient>()) }
    verify(exactly = 0) { GroupAdmission.isHeld(any<GroupId.V2>()) }
  }
}

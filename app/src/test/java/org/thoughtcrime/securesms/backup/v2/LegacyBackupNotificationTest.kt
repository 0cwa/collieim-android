package org.thoughtcrime.securesms.backup.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyBackupNotificationTest {

  @Test
  fun `only matches untagged notification on the backup alerts channel`() {
    assertTrue(isLegacyBackupNotification(120500, null, "app_alerts", 120500, "app_alerts"))
    assertTrue(isLegacyBackupNotification(120501, null, "app_alerts", 120501, "app_alerts"))
    assertFalse(isLegacyBackupNotification(120500, "conversation", "app_alerts", 120500, "app_alerts"))
    assertFalse(isLegacyBackupNotification(120500, null, "messages", 120500, "app_alerts"))
    assertFalse(isLegacyBackupNotification(120501, null, "app_alerts", 120500, "app_alerts"))
  }
}

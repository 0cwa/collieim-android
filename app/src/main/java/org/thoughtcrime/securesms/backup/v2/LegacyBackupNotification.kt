package org.thoughtcrime.securesms.backup.v2

/**
 * Molly's previous thread-ID range could overlap the old backup alert IDs. Match both the ID and the stable alerts channel so upgrade cleanup does not
 * remove an unrelated conversation notification.
 */
internal fun isLegacyBackupNotification(
  notificationId: Int,
  tag: String?,
  channelId: String?,
  legacyId: Int,
  backupAlertsChannel: String
): Boolean = notificationId == legacyId && tag == null && channelId == backupAlertsChannel

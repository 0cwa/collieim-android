package im.molly.unifiedpush.enrollment

import android.content.Context
import android.os.Build
import im.molly.unifiedpush.UnifiedPushDistributor
import im.molly.unifiedpush.receiver.EncryptedPush
import org.signal.core.util.concurrent.SignalExecutors
import org.thoughtcrime.securesms.keyvalue.SignalStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Writes the CollieSocket enrollment file to the app-specific external files directory, which other apps
 * cannot read from Android 11 on (earlier versions let apps holding the storage permission read it, so export
 * is refused there) and which is excluded from backups (see `backup_rules.xml`). It is moved with `adb pull`.
 * It is replaced on every export and deleted on the next app start. No share intent, no logging of contents.
 */
object EnrollmentExporter {

  const val FILE_NAME = "colliesocket-enrollment.json"
  private const val TEMP_NAME = "$FILE_NAME.tmp"

  /**
   * Writes the file and returns it. [serverEpoch] is the epoch CollieSocket reports for this account, or null if
   * it is not enrolled; the file carries that + 1. Throws [EnrollmentFile.Invalid] (fixed code only) or IOException.
   * Any earlier file is removed first, so a refused export never leaves an old password file behind.
   */
  @JvmStatic
  @JvmOverloads
  fun export(context: Context, serverEpoch: Long?, isDistributorActive: () -> Boolean = UnifiedPushDistributor::checkIfActive): File {
    val dir = context.getExternalFilesDir(null)
    val temp = dir?.let { File(it, TEMP_NAME) }
    val target = dir?.let { File(it, FILE_NAME) }
    removeFiles(context)

    if (Build.VERSION.SDK_INT < 30) throw EnrollmentFile.Invalid("unsupported_android")
    if (dir == null || temp == null || target == null) throw EnrollmentFile.Invalid("storage_unavailable")
    val epoch = EnrollmentFile.nextEpoch(serverEpoch)

    val values = SignalStore.unifiedpush
    val device = values.device
    val endpoint = values.endpoint
    val p256dh = values.endpointP256dh
    val auth = values.endpointAuth
    val aci = SignalStore.account.aci
    if (aci == null || device == null || endpoint == null || p256dh == null || auth == null || !isDistributorActive()) {
      throw EnrollmentFile.Invalid("not_ready")
    }

    val draft = EnrollmentFile.Input(
      requestId = UUID.randomUUID().toString(),
      accountId = aci.toString(),
      deviceId = device.deviceId,
      password = device.password,
      endpoint = endpoint,
      p256dh = p256dh,
      auth = auth,
      instance = values.endpointInstance ?: "default",
      epoch = epoch,
    )
    val contents = EnrollmentFile.render(draft)

    try {
      temp.outputStream().use { it.write(contents.toByteArray(Charsets.UTF_8)) }
      Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally {
      temp.delete()
    }

    // The server has not imported the file yet, so nothing switches: the receiver promotes this epoch to the
    // active one when it receives a valid event at it.
    values.pendingEnrollmentEpoch = epoch
    if (values.replayState == null) {
      values.replayState = EncryptedPush.ReplayState(System.currentTimeMillis() / 1000, emptyList()).serialize()
    }
    return target
  }

  /** Deletes the export file and its temporary file now, on the calling thread. */
  @JvmStatic
  fun removeFiles(context: Context) {
    val dir = context.getExternalFilesDir(null) ?: return
    File(dir, FILE_NAME).delete()
    File(dir, TEMP_NAME).delete()
  }

  /** Removes any file left by an earlier export. Called from the application's onCreate. */
  @JvmStatic
  fun deleteStale(context: Context) {
    SignalExecutors.BOUNDED.execute {
      val dir = context.getExternalFilesDir(null) ?: return@execute
      File(dir, FILE_NAME).delete()
      File(dir, TEMP_NAME).delete()
    }
  }
}

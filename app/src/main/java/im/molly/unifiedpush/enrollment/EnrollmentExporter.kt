package im.molly.unifiedpush.enrollment

import android.content.Context
import android.os.Build
import im.molly.unifiedpush.UnifiedPushDistributor
import im.molly.unifiedpush.control.EndpointUpdates
import im.molly.unifiedpush.control.Target
import im.molly.unifiedpush.control.UnifiedPushControlStore
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

  /** The file, the epoch it carries, and the epoch the operator's input alone would have given ([entered]). */
  class Export(val file: File, val epoch: Long, val entered: Long) {
    /** True when this phone had already used a higher epoch than the operator's input implies. */
    val raised get() = epoch != entered
  }

  /**
   * Writes the file and returns it. See [exportWithEpoch].
   */
  @JvmStatic
  @JvmOverloads
  fun export(context: Context, serverEpoch: Long?, isDistributorActive: () -> Boolean = UnifiedPushDistributor::checkIfActive): File =
    exportWithEpoch(context, serverEpoch, isDistributorActive).file

  /**
   * Writes the file. [serverEpoch] is the epoch CollieSocket reports for this account, or null if it is not enrolled;
   * the file carries that + 1, or one above the highest epoch this phone has put into an endpoint update if that is
   * more, and a fresh control key. The epoch, key and subscription are recorded durably before the file is published,
   * so a crash cannot leave a file the phone does not know about. Throws [EnrollmentFile.Invalid] (fixed code only)
   * or IOException. Any earlier file is removed first, so a refused export never leaves an old password file behind.
   */
  @JvmStatic
  @JvmOverloads
  fun exportWithEpoch(context: Context, serverEpoch: Long?, isDistributorActive: () -> Boolean = UnifiedPushDistributor::checkIfActive): Export {
    val dir = context.getExternalFilesDir(null)
    val temp = dir?.let { File(it, TEMP_NAME) }
    val target = dir?.let { File(it, FILE_NAME) }
    removeFiles(context)

    if (Build.VERSION.SDK_INT < 30) throw EnrollmentFile.Invalid("unsupported_android")
    if (dir == null || temp == null || target == null) throw EnrollmentFile.Invalid("storage_unavailable")
    val entered = EnrollmentFile.nextEpoch(serverEpoch)

    val values = SignalStore.unifiedpush
    val device = values.device
    val endpoint = values.endpoint
    val p256dh = values.endpointP256dh
    val auth = values.endpointAuth
    val aci = SignalStore.account.aci
    if (aci == null || device == null || endpoint == null || p256dh == null || auth == null || !isDistributorActive()) {
      throw EnrollmentFile.Invalid("not_ready")
    }

    val store = UnifiedPushControlStore(values)
    val before = store.read()
    val epoch = EndpointUpdates.exportEpoch(serverEpoch, before)
    // Every export gets its own key: the server refuses a repeated key on a state-changing import. It replaces the
    // current key only once the server proves it imported this file, so updates keep working with the old key until.
    val controlKey = EnrollmentFile.newControlKey()
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
      controlKey = controlKey,
    )
    val contents = EnrollmentFile.render(draft)

    // The server has not imported the file yet, so nothing switches: the receiver promotes this epoch to the
    // active one when it receives a valid event at it. The export is recorded before the file exists.
    EndpointUpdates.recordExport(
      store, epoch, controlKey,
      Target(endpoint, EnrollmentFile.canonicalKey(p256dh, 65), EnrollmentFile.canonicalKey(auth, 16)),
    )
    try {
      temp.outputStream().use { it.write(contents.toByteArray(Charsets.UTF_8)) }
      Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Exception) {
      EndpointUpdates.retireExport(store, epoch, before)
      throw e
    } finally {
      temp.delete()
    }

    if (values.replayState == null) {
      values.replayState = EncryptedPush.ReplayState(System.currentTimeMillis() / 1000, emptyList()).serialize()
    }
    return Export(target, epoch, entered)
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

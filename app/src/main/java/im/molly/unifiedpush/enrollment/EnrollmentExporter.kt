package im.molly.unifiedpush.enrollment

import android.content.Context
import org.signal.core.util.concurrent.SignalExecutors
import org.thoughtcrime.securesms.keyvalue.SignalStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Writes the CollieSocket enrollment file to the app-specific external files directory, which other apps
 * cannot read and which is excluded from backups (see `backup_rules.xml`). It is moved with `adb pull`.
 * It is replaced on every export and deleted on the next app start. No share intent, no logging of contents.
 */
object EnrollmentExporter {

  const val FILE_NAME = "colliesocket-enrollment.json"
  private const val TEMP_NAME = "$FILE_NAME.tmp"

  /** Writes the file and returns it. Throws [EnrollmentFile.Invalid] (fixed code only) or IOException. */
  @JvmStatic
  fun export(context: Context): File {
    val values = SignalStore.unifiedpush
    val device = values.device
    val endpoint = values.endpoint
    val p256dh = values.endpointP256dh
    val auth = values.endpointAuth
    val aci = SignalStore.account.aci
    if (aci == null || device == null || endpoint == null || p256dh == null || auth == null) {
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
      epoch = 1,
    )
    val fingerprint = EnrollmentFile.fingerprint(draft)
    val epoch = EnrollmentFile.epochFor(values.enrollmentEpoch, values.enrollmentFingerprint, fingerprint)
    val contents = EnrollmentFile.render(draft.copy(epoch = epoch))

    val dir = context.getExternalFilesDir(null) ?: throw EnrollmentFile.Invalid("storage_unavailable")
    val temp = File(dir, TEMP_NAME)
    val target = File(dir, FILE_NAME)
    try {
      temp.outputStream().use { it.write(contents.toByteArray(Charsets.UTF_8)) }
      Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally {
      temp.delete()
    }

    values.enrollmentEpoch = epoch
    values.enrollmentFingerprint = fingerprint
    return target
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

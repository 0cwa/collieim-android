package im.molly.unifiedpush.enrollment

import android.content.Context
import android.os.Build
import im.molly.unifiedpush.UnifiedPushDistributor
import im.molly.unifiedpush.control.ControlStateRead
import im.molly.unifiedpush.control.EndpointUpdates
import im.molly.unifiedpush.control.ImportIntent
import im.molly.unifiedpush.control.Target
import im.molly.unifiedpush.control.UnifiedPushControlStore
import im.molly.unifiedpush.receiver.EncryptedPush
import org.signal.core.util.concurrent.SignalExecutors
import org.thoughtcrime.securesms.keyvalue.SignalStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Explicit choice for one v3 export action. */
enum class ExportMode { NEW_INTENT, RECOVERY, RERENDER }

/** Writes the app-private v3 enrollment file after its logical intent has been committed to SignalStore. */
object EnrollmentExporter {

  const val FILE_NAME = "colliesocket-enrollment.json"
  private const val TEMP_NAME = "$FILE_NAME.tmp"

  class Export(val file: File, val epoch: Long, val highestUsed: Long, val requestId: String) {
    val collides get() = epoch <= highestUsed
  }

  @JvmStatic
  @Synchronized
  fun exportWithEpoch(
    context: Context,
    serverEpoch: Long?,
    mode: ExportMode,
    requestId: String? = null,
    isDistributorActive: () -> Boolean = UnifiedPushDistributor::checkIfActive,
  ): Export {
    val dir = context.getExternalFilesDir(null)
    val temp = dir?.let { File(it, TEMP_NAME) }
    val output = dir?.let { File(it, FILE_NAME) }
    removeFiles(context)
    if (Build.VERSION.SDK_INT < 30) throw EnrollmentFile.Invalid("unsupported_android")
    if (dir == null || temp == null || output == null) throw EnrollmentFile.Invalid("storage_unavailable")

    val values = SignalStore.unifiedpush
    if (values.controlStateRead == ControlStateRead.Corrupt) throw EnrollmentFile.Invalid("state_unavailable")
    if (!isDistributorActive()) throw EnrollmentFile.Invalid("not_ready")
    val store = UnifiedPushControlStore(values)
    val before = store.read()

    val intent: ImportIntent
    val contents: String
    val replayIfAbsent = EncryptedPush.ReplayState(System.currentTimeMillis() / 1000, emptyList()).serialize()
    when (mode) {
      ExportMode.RERENDER -> {
        val savedId = requestId ?: throw EnrollmentFile.Invalid("intent_unavailable")
        val saved = before.intents.firstOrNull { it.requestId == savedId && it.eligible }
          ?: throw EnrollmentFile.Invalid("intent_unavailable")
        intent = saved
        contents = EnrollmentFile.render(saved.toInput())
        store.ensureReplayStateIfAbsent(replayIfAbsent)
      }
      ExportMode.NEW_INTENT, ExportMode.RECOVERY -> {
        if (requestId != null) throw EnrollmentFile.Invalid("invalid_enrollment")
        val device = values.device
        val endpoint = values.endpoint
        val p256dh = values.endpointP256dh
        val auth = values.endpointAuth
        val aci = SignalStore.account.aci
        if (aci == null || device == null || endpoint == null || p256dh == null || auth == null) throw EnrollmentFile.Invalid("not_ready")

        val epoch = EndpointUpdates.exportEpoch(serverEpoch)
        val accountId = aci.toString()
        val instance = values.endpointInstance ?: "default"
        val exported = Target(endpoint, EnrollmentFile.canonicalKey(p256dh, 65), EnrollmentFile.canonicalKey(auth, 16))
        val activeNow = mode == ExportMode.RECOVERY
        var rendered: String? = null
        intent = EndpointUpdates.recordIntent(store, fullRecovery = activeNow, replayIfAbsent = replayIfAbsent) { old, sequence ->
          if (epoch <= old.activeEpoch && !activeNow) throw EnrollmentFile.Invalid("recovery_ack_required")

          // A matching epoch alone is not enough to reuse a candidate key: the intent may belong to a different
          // Signal account, linked device, or UnifiedPush instance that happens to use the same epoch number.
          val sameEpoch = old.intents.filter {
            it.epoch == epoch && it.accountId == accountId && it.deviceId == device.deviceId && it.instance == instance
          }
          val sameEpochKey = when {
            sameEpoch.isNotEmpty() -> {
              if (sameEpoch.any { !sameExportParameters(it, accountId, device.deviceId, device.password, instance, exported) }) {
                throw EnrollmentFile.Invalid("same_epoch_conflict")
              }
              val keys = sameEpoch.map { it.controlKey }.toSet()
              val ids = sameEpoch.map { it.keyId }.toSet()
              if (keys.size != 1 || ids.size != 1) throw EnrollmentFile.Invalid("same_epoch_conflict")
              keys.single()
            }
            epoch == old.activeEpoch && old.key != null &&
              (old.activeImportId == null ||
                (old.activeAccountId == accountId && old.activeDeviceId == device.deviceId && old.activeInstance == instance)) -> {
              if (old.serverTarget != exported) throw EnrollmentFile.Invalid("same_epoch_conflict")
              old.key
            }
            epoch == old.activeEpoch && old.key == null -> throw EnrollmentFile.Invalid("same_epoch_conflict")
            else -> null
          }
          val controlKey = sameEpochKey ?: EnrollmentFile.newControlKey()
          val keyId = ControlKeyId.derive(controlKey, accountId, device.deviceId, instance)
          val candidate = ImportIntent(
            requestId = UUID.randomUUID().toString(),
            sequence = sequence,
            epoch = epoch,
            controlKey = controlKey,
            keyId = keyId,
            target = exported,
            accountId = accountId,
            deviceId = device.deviceId,
            password = device.password,
            instance = instance,
            recovery = activeNow,
          )
          // Validate immutable bytes while still inside the state transaction, before sequence/intent commit.
          rendered = EnrollmentFile.render(candidate.toInput())
          candidate
        }
        contents = checkNotNull(rendered)
      }
    }

    // New intents and their sequence are already committed. A failed file write keeps the intent for re-render.
    try {
      temp.outputStream().use { it.write(contents.toByteArray(Charsets.UTF_8)) }
      Files.move(temp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally {
      temp.delete()
    }

    return Export(output, intent.epoch, store.read().highestEpoch, intent.requestId)
  }

  /** Forget a chosen candidate only after the settings UI has warned that its file may still exist. */
  @JvmStatic
  fun forgetIntent(requestId: String): Boolean = EndpointUpdates.forgetIntent(UnifiedPushControlStore(SignalStore.unifiedpush), requestId)

  private fun ImportIntent.toInput() = EnrollmentFile.Input(
    requestId = requestId,
    accountId = accountId,
    deviceId = deviceId,
    password = password,
    endpoint = target.endpoint,
    p256dh = target.p256dh,
    auth = target.auth,
    instance = instance,
    epoch = epoch,
    controlKey = controlKey,
    importSequence = sequence,
  )

  private fun sameExportParameters(
    intent: ImportIntent,
    accountId: String,
    deviceId: Int,
    password: String,
    instance: String,
    target: Target,
  ): Boolean = intent.accountId == accountId && intent.deviceId == deviceId && intent.password == password &&
    intent.instance == instance && intent.target == target

  /** Deletes any published or temporary export now, on the calling thread. */
  @JvmStatic
  @Synchronized
  fun removeFiles(context: Context) {
    val dir = context.getExternalFilesDir(null) ?: return
    File(dir, FILE_NAME).delete()
    File(dir, TEMP_NAME).delete()
  }

  /** Removes any file left by an earlier export. Called from the application's onCreate. */
  @JvmStatic
  fun deleteStale(context: Context) {
    SignalExecutors.BOUNDED.execute {
      synchronized(EnrollmentExporter) {
        val dir = context.getExternalFilesDir(null) ?: return@synchronized
        File(dir, FILE_NAME).delete()
        File(dir, TEMP_NAME).delete()
      }
    }
  }
}

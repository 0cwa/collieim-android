package im.molly.unifiedpush.enrollment

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.annotation.VisibleForTesting
import im.molly.unifiedpush.UnifiedPushDistributor
import im.molly.unifiedpush.control.ControlStateRead
import im.molly.unifiedpush.control.EndpointUpdates
import im.molly.unifiedpush.control.ImportIntent
import im.molly.unifiedpush.control.Target
import im.molly.unifiedpush.control.UnifiedPushControlStore
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import im.molly.unifiedpush.model.MollySocketDevice
import im.molly.unifiedpush.receiver.EncryptedPush
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.ReplayStateUnavailableException
import java.io.File
import java.io.IOException
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

  internal class DocumentExport(val epoch: Long, val highestUsed: Long, private val contents: String) {
    val collides get() = epoch <= highestUsed

    fun writeTo(context: Context, destination: Uri) {
      val stream = context.contentResolver.openOutputStream(destination, "wt")
        ?: throw IOException("Could not open the selected document")
      stream.use { it.write(contents.toByteArray(Charsets.UTF_8)) }
    }
  }

  private data class PreparedExport(
    val file: File?, val json: String, val epoch: Long, val highestUsed: Long, val requestId: String,
    val intent: ImportIntent,
  )

  @JvmStatic
  @Synchronized
  fun exportWithEpoch(
    context: Context,
    serverEpoch: Long?,
    mode: ExportMode,
    requestId: String? = null,
    isDistributorActive: () -> Boolean = UnifiedPushDistributor::checkIfActive,
  ): Export {
    val prepared = export(context, serverEpoch, mode, requestId, System.currentTimeMillis() / 1000, isDistributorActive, true, validateSealed = { _, _, _ -> })
    return Export(checkNotNull(prepared.file), prepared.epoch, prepared.highestUsed, prepared.requestId)
  }

  /** Creates and validates pairing bytes entirely in memory; [validateSealed] runs before intent commit. */
  @JvmStatic
  @Synchronized
  internal fun exportForPairing(
    context: Context,
    isDistributorActive: () -> Boolean,
    validateBeforeCommit: (ImportIntent) -> Unit = {},
    target: Target,
    instance: String,
    serverEpoch: Long? = null,
    allowRecovery: Boolean = false,
    validateSealed: (ImportIntent, MollySocketDevice, String) -> Unit,
  ): ImportIntent {
    return export(
      context, serverEpoch, ExportMode.NEW_INTENT, null, System.currentTimeMillis() / 1000, isDistributorActive, false,
      validateBeforeCommit, validateSealed, PairingRoute(target, instance, allowRecovery),
    ).intent
  }

  @VisibleForTesting
  @Synchronized
  internal fun exportWithEpochAtTime(
    context: Context,
    serverEpoch: Long?,
    mode: ExportMode,
    requestId: String? = null,
    nowSeconds: Long,
    isDistributorActive: () -> Boolean = UnifiedPushDistributor::checkIfActive,
  ): Export {
    val prepared = export(context, serverEpoch, mode, requestId, nowSeconds, isDistributorActive, true, validateSealed = { _, _, _ -> })
    return Export(checkNotNull(prepared.file), prepared.epoch, prepared.highestUsed, prepared.requestId)
  }

  /** Commits the enrollment intent and prepares its immutable document before provider I/O. */
  @Synchronized
  internal fun prepareDocument(
    context: Context,
    serverEpoch: Long?,
    mode: ExportMode,
    requestId: String?,
  ): DocumentExport {
    val prepared = export(
      context, serverEpoch, mode, requestId, System.currentTimeMillis() / 1000,
      UnifiedPushDistributor::checkIfActive, false, validateSealed = { _, _, _ -> },
    )
    return DocumentExport(prepared.epoch, prepared.highestUsed, prepared.json)
  }

  private data class PairingRoute(val target: Target, val instance: String, val allowRecovery: Boolean)

  private fun export(
    context: Context,
    serverEpoch: Long?,
    mode: ExportMode,
    requestId: String?,
    nowSeconds: Long,
    isDistributorActive: () -> Boolean,
    writeToDisk: Boolean,
    validateBeforeCommit: ((ImportIntent) -> Unit)? = null,
    validateSealed: (ImportIntent, MollySocketDevice, String) -> Unit,
    pairingRoute: PairingRoute? = null,
  ): PreparedExport {
    val dir = if (writeToDisk) context.getExternalFilesDir(null) else null
    val temp = dir?.let { File(it, TEMP_NAME) }
    val output = dir?.let { File(it, FILE_NAME) }
    if (writeToDisk) {
      removeFiles(context)
      if (Build.VERSION.SDK_INT < 30) throw EnrollmentFile.Invalid("unsupported_android")
      if (dir == null || temp == null || output == null) throw EnrollmentFile.Invalid("storage_unavailable")
    }

    val values = SignalStore.unifiedpush
    if (values.controlStateRead == ControlStateRead.Corrupt) throw EnrollmentFile.Invalid("state_unavailable")
    if (!isDistributorActive()) throw EnrollmentFile.Invalid("not_ready")
    val store = UnifiedPushControlStore(values)
    val before = store.read()
    var highestUsed = before.highestEpoch

    val intent: ImportIntent
    val contents: String
    val replayIfAbsent = EncryptedPush.ReplayState(nowSeconds, emptyList()).serialize()
    when (mode) {
      ExportMode.RERENDER -> {
        val savedId = requestId ?: throw EnrollmentFile.Invalid("intent_unavailable")
        val saved = before.intents.firstOrNull { it.requestId == savedId && it.eligible }
          ?: throw EnrollmentFile.Invalid("intent_unavailable")
        synchronized(UnifiedPushLifecycle.monitor) {
          if (values.isProvisioningPending(MollySocketDevice(saved.deviceId, saved.password))) {
            throw EnrollmentFile.Invalid("not_ready")
          }
        }
        intent = saved
        contents = EnrollmentFile.render(saved.toInput())
        requireReplayStateAvailability { store.ensureReplayStateIfAbsent(replayIfAbsent) }
      }
      ExportMode.NEW_INTENT, ExportMode.RECOVERY -> {
        if (requestId != null) throw EnrollmentFile.Invalid("invalid_enrollment")
        val (device, accountId, route) = synchronized(UnifiedPushLifecycle.monitor) {
          val aci = SignalStore.account.aci
          val device = values.device
          val endpoint = pairingRoute?.target?.endpoint ?: values.endpoint
          val p256dh = pairingRoute?.target?.p256dh ?: values.endpointP256dh
          val auth = pairingRoute?.target?.auth ?: values.endpointAuth
          if (aci == null || device == null || endpoint == null || p256dh == null || auth == null ||
            values.isProvisioningPending(device)) {
            throw EnrollmentFile.Invalid("not_ready")
          }
          Triple(device, aci.toString(), Target(endpoint, p256dh, auth) to (pairingRoute?.instance ?: (values.endpointInstance ?: "default")))
        }
        val epoch = EndpointUpdates.exportEpoch(serverEpoch)
        val (target, instance) = route
        val exported = Target(target.endpoint, EnrollmentFile.canonicalKey(target.p256dh, 65), EnrollmentFile.canonicalKey(target.auth, 16))
        val newPairingInstance = pairingRoute != null && instance != (values.endpointInstance ?: "default")
        val activeNow = mode == ExportMode.RECOVERY || pairingRoute?.allowRecovery == true && epoch <= before.activeEpoch
        var rendered: String? = null
        intent = requireReplayStateAvailability {
          EndpointUpdates.recordIntent(
            store,
            fullRecovery = activeNow,
            replayIfAbsent = replayIfAbsent,
            validateCandidate = { intent -> validateBeforeCommit?.invoke(intent) },
          ) { old, sequence ->
            highestUsed = old.highestEpoch
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
                (old.activeImportId == null && !newPairingInstance ||
                  (old.activeAccountId == accountId && old.activeDeviceId == device.deviceId && old.activeInstance == instance)) -> {
                if (old.serverTarget != exported) throw EnrollmentFile.Invalid("same_epoch_conflict")
                old.key
              }
              epoch == old.activeEpoch && old.key == null && !newPairingInstance -> throw EnrollmentFile.Invalid("same_epoch_conflict")
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
            if (!writeToDisk) validateSealed(candidate, device, checkNotNull(rendered))
            candidate
          }
        }
        contents = checkNotNull(rendered)
      }
    }

    // New intents and their sequence are already committed. A failed file write keeps the intent for re-render.
    if (writeToDisk) {
      try {
        checkNotNull(temp).outputStream().use { it.write(contents.toByteArray(Charsets.UTF_8)) }
        Files.move(checkNotNull(temp).toPath(), checkNotNull(output).toPath(), StandardCopyOption.REPLACE_EXISTING)
      } finally {
        temp?.delete()
      }
    }
    return PreparedExport(if (writeToDisk) output else null, contents, intent.epoch, highestUsed, intent.requestId, intent)
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

  private inline fun <T> requireReplayStateAvailability(block: () -> T): T = try {
    block()
  } catch (_: ReplayStateUnavailableException) {
    throw EnrollmentFile.Invalid("state_unavailable")
  }

  /** Deletes any published or temporary export now, on the calling thread. */
  @JvmStatic
  @Synchronized
  fun removeFiles(context: Context) {
    val dir = context.getExternalFilesDir(null) ?: return
    File(dir, FILE_NAME).delete()
    File(dir, TEMP_NAME).delete()
  }

  /** Synchronously removes the two export files during startup, before settings can publish a new export. */
  @JvmStatic
  @Synchronized
  fun deleteStale(context: Context) {
    removeFiles(context)
  }

}

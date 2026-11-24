package im.molly.unifiedpush.components.settings.app.notifications

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import im.molly.unifiedpush.control.ControlReset
import im.molly.unifiedpush.enrollment.EnrollmentExporter
import im.molly.unifiedpush.enrollment.EnrollmentFile
import im.molly.unifiedpush.enrollment.ExportMode
import im.molly.unifiedpush.health.PushHealth
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import im.molly.unifiedpush.model.MollySocket
import im.molly.unifiedpush.UnifiedPushDistributor
import org.signal.core.util.Util.writeTextToClipboard
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.storage.keyvalue.KeyValuePersistenceException
import org.thoughtcrime.securesms.R
import org.signal.core.util.ThreadUtil
import java.io.IOException
import im.molly.unifiedpush.receiver.LockedPushState
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.UnifiedPushHealthJob
import org.thoughtcrime.securesms.jobs.UnifiedPushRefreshJob
import org.thoughtcrime.securesms.keyvalue.SettingsValues.ForceWebsocketMode
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.util.livedata.Store
import org.unifiedpush.android.connector.UnifiedPush
import java.util.UUID

class UnifiedPushSettingsViewModel(private val application: Application) : ViewModel() {

  private val store = Store(getState())

  val state: LiveData<UnifiedPushSettingsState> = store.stateLiveData
  internal val exportState: LiveData<ExportState?> = pendingExport

  internal data class ExportState(
    val id: String,
    val accountId: String,
    val deviceId: Int,
    val generation: Long,
    val message: String? = null,
  ) {
    fun belongsToCurrentAccount() = synchronized(UnifiedPushLifecycle.monitor) {
      UnifiedPushLifecycle.isCurrentGeneration(generation) && !KeyCachingService.isLocked() &&
        SignalStore.account.isRegistered && !SignalStore.account.isUnauthorizedReceived &&
        accountId == SignalStore.account.aci?.toString() && deviceId == SignalStore.account.deviceId
    }
  }

  internal fun isExportPending(): Boolean = pendingExport.value?.let { it.message == null || it.belongsToCurrentAccount() } == true

  fun refresh() {
    store.update { getState() }
  }

  fun updateRegistration(pingOnRegister: Boolean = false) {
    AppDependencies.jobManager.add(UnifiedPushRefreshJob(pingOnRegister))
  }

  private fun getState(): UnifiedPushSettingsState {
    val distributorIds = UnifiedPush.getDistributors(application)
    val saved = UnifiedPush.getSavedDistributor(application)
    val ack = saved != null && UnifiedPush.getAckDistributor(application) == saved

    val selected = distributorIds.indexOfFirst { it == saved }

    val distributors = distributorIds.map { appId ->
      val name = try {
        val ai = if (Build.VERSION.SDK_INT >= 33) {
          application.packageManager.getApplicationInfo(
            appId, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
          )
        } else {
          application.packageManager.getApplicationInfo(appId, 0)
        }
        application.packageManager.getApplicationLabel(ai)
      } catch (e: PackageManager.NameNotFoundException) {
        appId
      } as String

      Distributor(
        applicationId = appId,
        name = name,
      )
    }

    val mollySocketUrl = SignalStore.unifiedpush.mollySocketUrl

    val controlRead = SignalStore.unifiedpush.controlStateRead.stateOrNull
    return UnifiedPushSettingsState(
      airGapped = SignalStore.unifiedpush.airGapped,
      device = SignalStore.unifiedpush.device?.takeUnless(SignalStore.unifiedpush::isProvisioningPending),
      aci = SignalStore.account.aci?.toString(),
      registrationStatus = SignalStore.unifiedpush.registrationStatus,
      registrationFailed = SignalStore.unifiedpush.registrationFailed,
      distributors = distributors,
      selected = selected,
      selectedNotAck = !ack,
      endpoint = SignalStore.unifiedpush.endpoint,
      mollySocketUrl = mollySocketUrl,
      pushFallback = SignalStore.unifiedpush.health.fallback,
      pushTestResult = PushHealth.Hop.fromCode(SignalStore.unifiedpush.health.lastResult),
      pushTestResultAt = SignalStore.unifiedpush.health.lastResultAt,
      // Only for as long as the job can run: it ends within the timeout, and a missing verdict must not leave "testing" up.
      pushTestRunning = pushTestStartedAt > 0 && SignalStore.unifiedpush.health.lastResultAt * 1000 < pushTestStartedAt &&
        System.currentTimeMillis() - pushTestStartedAt < UnifiedPushHealthJob.timeoutMs + TEST_GRACE_MS,
      controlStateCorrupt = SignalStore.unifiedpush.hasCorruptControlState,
      savedExports = controlRead?.intents?.map { SavedExport(it.requestId, it.epoch, it.eligible) } ?: emptyList(),
      pendingExportCount = controlRead?.pendingIntentCount ?: 0,
      hasLegacyPendingExport = controlRead?.legacyPending != null,
    )
  }

  private var pushTestStartedAt = 0L

  /** Runs the end-to-end push test; the verdict arrives through [PushServiceEvent] and is shown on the screen. */
  fun testPush() {
    pushTestStartedAt = System.currentTimeMillis()
    UnifiedPushHealthJob.enqueue(manual = true)
    refresh()
  }

  fun setUnifiedPushDistributor(distributor: String) {
    SignalStore.unifiedpush.endpoint = null
    UnifiedPushDistributor.saveDistributor(application, distributor)
    refresh()
    updateRegistration()
  }

  fun retryRegistration() {
    SignalStore.unifiedpush.registrationRetryScheduled = false
    updateRegistration()
  }

  fun setMollySocket(mollySocket: MollySocket) {
    SignalStore.unifiedpush.updateMollySocket(mollySocket)
    refresh()
    updateRegistration()
  }

  fun pingMollySocket() {
    refresh()
    updateRegistration(pingOnRegister = true)
  }

  fun copyParamsToClipboard(context: Context) {
    synchronized(UnifiedPushLifecycle.monitor) {
      if (!UnifiedPushLifecycle.isReadyLocked() || KeyCachingService.isLocked()) return
      val snapshot = state.value ?: return
      val values = SignalStore.unifiedpush
      val device = values.device ?: return
      if (values.isProvisioningPending(device) || device != snapshot.device ||
        values.endpoint != snapshot.endpoint || SignalStore.account.aci?.toString() != snapshot.aci) return
      val parameters = snapshot.serverParameters ?: return
      writeTextToClipboard(context, "Server parameters", parameters)
    }
  }

  fun copyAciToClipboard(context: Context) {
    val aci = state.value?.aci ?: ""
    writeTextToClipboard(context, "Account ID", aci)
  }

  /** Writes the CollieSocket enrollment file off the main thread and reports its result. */
  @MainThread
  fun exportEnrollment(context: Context, serverEpochText: String, mode: ExportMode, requestId: String? = null) {
    if (isExportPending()) return
    val request = synchronized(UnifiedPushLifecycle.monitor) {
      val accountId = SignalStore.account.aci?.toString() ?: return@synchronized null
      ExportState(UUID.randomUUID().toString(), accountId, SignalStore.account.deviceId, UnifiedPushLifecycle.currentGeneration())
        .takeIf { it.belongsToCurrentAccount() }
    }
    if (request == null) {
      Toast.makeText(context.applicationContext, R.string.UnifiedPushSettingsFragment__export_enrollment_not_ready, Toast.LENGTH_LONG).show()
      return
    }
    pendingExport.value = request
    val app = context.applicationContext
    SignalExecutors.BOUNDED.execute {
      var successMessage: String? = null
      val failureMessage = try {
        val lease = UnifiedPushLifecycle.acquireLease(request.generation) { request.takeIf { it.belongsToCurrentAccount() } }
          ?: throw EnrollmentFile.Invalid("not_ready")
        val export = lease.use {
          // A refused or overflowing epoch must never leave the old password file behind.
          EnrollmentExporter.removeFiles(app)
          val serverEpoch = serverEpochText.trim().takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: throw EnrollmentFile.Invalid("invalid_epoch") }
          EnrollmentExporter.exportWithEpoch(app, serverEpoch, mode, requestId).also {
            if (!request.belongsToCurrentAccount()) EnrollmentExporter.removeFiles(app)
          }
        }
        successMessage = if (export.collides) {
          app.getString(R.string.UnifiedPushSettingsFragment__export_enrollment_done_collides, export.file.absolutePath, export.epoch, export.highestUsed)
        } else {
          app.getString(R.string.UnifiedPushSettingsFragment__export_enrollment_done, export.file.absolutePath)
        }
        null
      } catch (e: EnrollmentFile.Invalid) {
        app.getString(
          when (e.code) {
            "not_ready" -> R.string.UnifiedPushSettingsFragment__export_enrollment_not_ready
            "invalid_epoch" -> R.string.UnifiedPushSettingsFragment__export_enrollment_invalid_epoch
            "unsupported_android" -> R.string.UnifiedPushSettingsFragment__export_enrollment_unsupported_android
            "state_unavailable" -> R.string.UnifiedPushSettingsFragment__export_enrollment_state_unavailable
            "recovery_ack_required" -> R.string.UnifiedPushSettingsFragment__export_enrollment_recovery_ack_required
            "pending_limit" -> R.string.UnifiedPushSettingsFragment__export_enrollment_pending_limit
            "sequence_exhausted" -> R.string.UnifiedPushSettingsFragment__export_enrollment_sequence_exhausted
            "same_epoch_conflict" -> R.string.UnifiedPushSettingsFragment__export_enrollment_same_epoch_conflict
            "intent_unavailable" -> R.string.UnifiedPushSettingsFragment__export_enrollment_intent_unavailable
            else -> R.string.UnifiedPushSettingsFragment__export_enrollment_failed
          }
        )
      } catch (e: IOException) {
        app.getString(R.string.UnifiedPushSettingsFragment__export_enrollment_failed)
      }
      ThreadUtil.runOnMain {
        if (!request.belongsToCurrentAccount()) {
          pendingExport.value = null
        } else if (successMessage != null) {
          pendingExport.value = request.copy(message = successMessage)
        } else {
          pendingExport.value = null
          Toast.makeText(app, failureMessage, Toast.LENGTH_LONG).show()
        }
      }
    }
  }

  /** Clears unreadable local control state only after the user confirms server-side removal. */
  fun recoverCorruptControlState() {
    runPersistedControlAction { ControlReset.recoverCorrupt(application) }
  }

  fun forgetExport(requestId: String) {
    runPersistedControlAction { EnrollmentExporter.forgetIntent(requestId) }
  }

  fun forgetLegacyExport() {
    runPersistedControlAction {
      im.molly.unifiedpush.control.EndpointUpdates.forgetLegacyPending(
        im.molly.unifiedpush.control.UnifiedPushControlStore(SignalStore.unifiedpush)
      )
    }
  }

  private inline fun runPersistedControlAction(action: () -> Unit) {
    val persistenceFailed = try {
      action()
      false
    } catch (_: KeyValuePersistenceException) {
      true
    }
    if (persistenceFailed) {
      Toast.makeText(application, R.string.UnifiedPushSettingsFragment__export_enrollment_failed, Toast.LENGTH_LONG).show()
    }
    refresh()
  }

  class Factory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return requireNotNull(modelClass.cast(UnifiedPushSettingsViewModel(application)))
    }
  }

  companion object {
    private const val TEST_GRACE_MS = 15_000L

    // An export can finish after the settings screen is closed. Keep its unread result in memory.
    private val pendingExport = MutableLiveData<ExportState?>()

    internal fun unreadExport(): ExportState? = pendingExport.value?.takeIf { it.message != null && it.belongsToCurrentAccount() }

    internal fun acknowledgeExportResult(id: String) {
      if (unreadExport()?.id == id) pendingExport.value = null
    }
  }
}

fun UnifiedPushValues.updateMollySocket(mollySocket: MollySocket) {
  airGapped = mollySocket is MollySocket.AirGapped
  mollySocketUrl = (mollySocket as? MollySocket.WebServer)?.url

  val changed = vapidPublicKey != mollySocket.vapid
  vapidPublicKey = mollySocket.vapid

  if (changed) {
    vapidKeySynced = false
    endpoint = null
    // The old server's keys and epochs no longer apply. Keep the import sequence so switching back cannot lower a retained server floor.
    ControlReset.discardForServerChange(AppDependencies.application)
    // The push health verdicts and the WebSocket mode they caused belong to the server that is gone.
    PushHealth.reset(health)
    if (SignalStore.settings.forceWebsocketMode == ForceWebsocketMode.ENABLED_AUTOMATICALLY) {
      SignalStore.settings.forceWebsocketMode = ForceWebsocketMode.DISABLED_AUTOMATICALLY
    }
  }

  lastReceivedTime = 0
}

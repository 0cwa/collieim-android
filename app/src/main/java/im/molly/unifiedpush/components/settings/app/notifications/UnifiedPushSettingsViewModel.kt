package im.molly.unifiedpush.components.settings.app.notifications

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import im.molly.unifiedpush.control.ControlReset
import im.molly.unifiedpush.enrollment.EnrollmentExporter
import im.molly.unifiedpush.enrollment.EnrollmentFile
import im.molly.unifiedpush.enrollment.ExportMode
import im.molly.unifiedpush.enrollment.RelayDisclosure
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import im.molly.unifiedpush.health.PushHealth
import im.molly.unifiedpush.model.MollySocket
import im.molly.unifiedpush.UnifiedPushDistributor
import org.signal.core.util.Util.writeTextToClipboard
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.concurrent.SignalDispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import org.thoughtcrime.securesms.util.livedata.Store
import org.unifiedpush.android.connector.UnifiedPush

class UnifiedPushSettingsViewModel(private val application: Application) : ViewModel() {

  private val store = Store(getState())

  val state: LiveData<UnifiedPushSettingsState> = store.stateLiveData

  fun refresh() {
    store.update { getState() }
  }

  fun updateRegistration(pingOnRegister: Boolean = false) {
    AppDependencies.jobManager.add(UnifiedPushRefreshJob(pingOnRegister))
  }

  private fun getState(): UnifiedPushSettingsState = synchronized(UnifiedPushLifecycle.monitor) {
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
    UnifiedPushSettingsState(
      airGapped = SignalStore.unifiedpush.airGapped,
      device = SignalStore.unifiedpush.device,
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
      relayDisclosure = RelayDisclosure.capture(application),
    )
  }

  private var pushTestStartedAt = 0L

  private companion object {
    const val TEST_GRACE_MS = 15_000L
  }

  /** Runs the end-to-end push test; the verdict arrives through [PushServiceEvent] and is shown on the screen. */
  fun testPush() {
    pushTestStartedAt = System.currentTimeMillis()
    UnifiedPushHealthJob.enqueue(manual = true)
    refresh()
  }

  fun setUnifiedPushDistributor(distributor: String) {
    synchronized(UnifiedPushLifecycle.monitor) {
      SignalStore.unifiedpush.endpoint = null
      UnifiedPushDistributor.saveDistributor(application, distributor)
    }
    refresh()
    updateRegistration()
  }

  fun retryRegistration() {
    SignalStore.unifiedpush.registrationRetryScheduled = false
    updateRegistration()
  }

  fun setMollySocket(mollySocket: MollySocket, disclosure: RelayDisclosure.Snapshot?) {
    viewModelScope.launch {
      val authorization = withContext(SignalDispatchers.IO) { RelayDisclosure.selectRelay(disclosure, mollySocket) }
      if (authorization == null || !RelayDisclosure.completeSelection(authorization) {
          AppDependencies.jobManager.add(UnifiedPushRefreshJob(authorization))
        }) {
        showStaleDisclosure()
      }
      refresh()
    }
  }

  fun pingMollySocket() {
    refresh()
    updateRegistration(pingOnRegister = true)
  }

  fun copyParamsToClipboard(context: Context, disclosure: RelayDisclosure.Snapshot?) {
    if (!RelayDisclosure.runIfCurrent(disclosure) {
        val account = checkNotNull(disclosure).account
        val device = account.pushDevice
        val endpoint = account.endpoint
        if (device != null && endpoint != null) {
          writeTextToClipboard(context, "Server parameters", "connection add ${account.accountId} ${device.deviceId} ${device.password} $endpoint")
        }
      }) {
      showStaleDisclosure()
    }
  }

  fun copyAciToClipboard(context: Context) {
    val aci = state.value?.aci ?: ""
    writeTextToClipboard(context, "Account ID", aci)
  }

  /** Writes the CollieSocket enrollment file off the main thread and reports the outcome as a toast. */
  fun exportEnrollment(context: Context, serverEpochText: String, mode: ExportMode, requestId: String? = null, disclosure: RelayDisclosure.Snapshot?) {
    val app = context.applicationContext
    SignalExecutors.BOUNDED.execute {
      val message = try {
        val export = RelayDisclosure.exportIfCurrent(disclosure, mode, requestId) {
          // An authorized invalid epoch still removes the old password file; a stale action does not touch it.
          EnrollmentExporter.removeFiles(app)
          val serverEpoch = serverEpochText.trim().takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: throw EnrollmentFile.Invalid("invalid_epoch") }
          EnrollmentExporter.exportWithEpoch(app, serverEpoch, mode, requestId)
        }
        if (export.collides) {
          app.getString(R.string.UnifiedPushSettingsFragment__export_enrollment_done_collides, export.file.absolutePath, export.epoch, export.highestUsed)
        } else {
          app.getString(R.string.UnifiedPushSettingsFragment__export_enrollment_done, export.file.absolutePath)
        }
      } catch (e: EnrollmentFile.Invalid) {
        app.getString(
          when (e.code) {
            "disclosure_changed" -> R.string.UnifiedPushSettingsFragment__disclosure_stale
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
      ThreadUtil.runOnMain { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
      refresh()
    }
  }

  private fun showStaleDisclosure() {
    Toast.makeText(application, R.string.UnifiedPushSettingsFragment__disclosure_stale, Toast.LENGTH_LONG).show()
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

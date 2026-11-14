package im.molly.unifiedpush.enrollment

import android.content.Context
import im.molly.unifiedpush.components.settings.app.notifications.updateMollySocket
import im.molly.unifiedpush.control.ImportIntent
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import im.molly.unifiedpush.model.MollySocket
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.service.KeyCachingService
import org.unifiedpush.android.connector.UnifiedPush

/** Binds a user-confirmed disclosure to the account and configuration that the screen actually displayed. */
object RelayDisclosure {

  internal data class Configuration(
    val manual: Boolean,
    val url: String?,
    val vapid: String?,
    val deliveryMethod: NotificationDeliveryMethod,
    val distributor: String?,
  )

  class Snapshot internal constructor(
    internal val generation: Long,
    internal val account: UnifiedPushAccountTransition.AccountContext,
    internal val configuration: Configuration,
    internal val intents: List<ImportIntent>,
  ) {
    override fun toString() = "RelayDisclosure.Snapshot(generation=$generation)"
  }

  /** Contains no password or endpoint keys. Ordinary endpoint registration may advance the lifecycle generation. */
  data class RefreshAuthorization internal constructor(
    val processId: String,
    val accountId: String,
    val localDeviceId: Int,
    val manual: Boolean,
    val url: String?,
    val vapid: String?,
    val deliveryMethod: NotificationDeliveryMethod,
  ) {
    override fun toString() = "RelayDisclosure.RefreshAuthorization(localDeviceId=$localDeviceId)"
  }

  /** Capture alongside the rendered settings state, never at a delayed positive-button callback. */
  fun capture(context: Context = AppDependencies.application): Snapshot? = synchronized(UnifiedPushLifecycle.monitor) {
    if (!UnifiedPushLifecycle.isReadyLocked() || KeyCachingService.isLocked() || SignalStore.account.isUnauthorizedReceived) return@synchronized null
    val account = UnifiedPushAccountTransition.captureContextLocked() ?: return@synchronized null
    Snapshot(
      generation = UnifiedPushLifecycle.currentGeneration(),
      account = account,
      configuration = configuration(context),
      intents = SignalStore.unifiedpush.controlStateRead.stateOrNull?.intents?.toList() ?: emptyList(),
    )
  }

  fun runIfCurrent(snapshot: Snapshot?, action: () -> Unit): Boolean = synchronized(UnifiedPushLifecycle.monitor) {
    if (snapshot == null || !matchesLocked(snapshot, requireReadyGeneration = true)) return@synchronized false
    action()
    true
  }

  /** Drain credentialed work before changing its destination; a stale or cancelled dialog commits nothing. */
  suspend fun selectRelay(
    snapshot: Snapshot?,
    relay: MollySocket,
    deliveryMethod: NotificationDeliveryMethod? = null,
  ): RefreshAuthorization? {
    if (snapshot == null || !runIfCurrent(snapshot) {}) return null
    val caller = currentCoroutineContext()
    // Once admitted, even a cancelled screen must finish the transition and clear its incomplete marker.
    return withContext(NonCancellable) {
      try {
        UnifiedPushAccountTransition.withTransition(expectedGeneration = snapshot.generation) {
          synchronized(UnifiedPushLifecycle.monitor) {
            if (!caller.isActive || !matchesLocked(snapshot, requireReadyGeneration = false)) {
              null
            } else {
              SignalStore.unifiedpush.updateMollySocket(relay)
              if (deliveryMethod != null) SignalStore.settings.preferredNotificationMethod = deliveryMethod
              refreshAuthorizationLocked(snapshot.account)
            }
          }
        }
      } catch (_: UnifiedPushLifecycle.StaleLifecycleGenerationException) {
        null
      }
    }
  }

  /** Check inside the refresh job's lease admission, using the same monitor as account and relay changes. */
  fun matchesRefreshLocked(authorization: RefreshAuthorization): Boolean {
    check(Thread.holdsLock(UnifiedPushLifecycle.monitor))
    val values = SignalStore.unifiedpush
    return authorization.processId == UnifiedPushLifecycle.processInstanceId() &&
      !KeyCachingService.isLocked() && !SignalStore.account.isUnauthorizedReceived &&
      SignalStore.account.isRegistered && SignalStore.account.aci?.toString() == authorization.accountId &&
      SignalStore.account.deviceId == authorization.localDeviceId &&
      values.airGapped == authorization.manual && values.mollySocketUrl == authorization.url &&
      values.vapidPublicKey == authorization.vapid && SignalStore.settings.preferredNotificationMethod == authorization.deliveryMethod
  }

  /** A short, cancellable UI continuation; the caller executes this on Main and keeps the effect in [action]. */
  suspend fun completeSelection(authorization: RefreshAuthorization, action: () -> Unit): Boolean {
    val caller = currentCoroutineContext()
    repeat(100) {
      val completed = synchronized(UnifiedPushLifecycle.monitor) {
        caller.ensureActive()
        when {
          !matchesRefreshLocked(authorization) -> false
          !UnifiedPushLifecycle.isReadyLocked() -> null
          else -> {
            action()
            true
          }
        }
      }
      if (completed != null) return completed
      delay(50)
    }
    return false
  }

  /** Match the exporter's static Class → instance → lifecycle order before checking or publishing anything. */
  fun <T> exportIfCurrent(snapshot: Snapshot?, mode: ExportMode, requestId: String?, action: () -> T): T =
    synchronized(EnrollmentExporter::class.java) {
      synchronized(EnrollmentExporter) {
        synchronized(UnifiedPushLifecycle.monitor) {
          if (snapshot == null || !matchesLocked(snapshot, requireReadyGeneration = true)) throw EnrollmentFile.Invalid("disclosure_changed")
          if (mode == ExportMode.RERENDER) {
            val saved = snapshot.intents.firstOrNull { it.requestId == requestId && it.eligible }
            val current = SignalStore.unifiedpush.controlStateRead.stateOrNull?.intents?.firstOrNull { it.requestId == requestId }
            if (saved == null || saved != current || saved.accountId != snapshot.account.accountId) throw EnrollmentFile.Invalid("disclosure_changed")
          }
          action()
        }
      }
    }

  private fun matchesLocked(snapshot: Snapshot, requireReadyGeneration: Boolean): Boolean {
    check(Thread.holdsLock(UnifiedPushLifecycle.monitor))
    return (!requireReadyGeneration || UnifiedPushLifecycle.isCurrentGeneration(snapshot.generation)) &&
      !UnifiedPushLifecycle.hasPendingInvalidationLocked() && !KeyCachingService.isLocked() &&
      !SignalStore.account.isUnauthorizedReceived && UnifiedPushAccountTransition.contextStillMatchesLocked(snapshot.account) &&
      configuration(AppDependencies.application) == snapshot.configuration
  }

  private fun configuration(context: Context) = Configuration(
    manual = SignalStore.unifiedpush.airGapped,
    url = SignalStore.unifiedpush.mollySocketUrl,
    vapid = SignalStore.unifiedpush.vapidPublicKey,
    deliveryMethod = SignalStore.settings.preferredNotificationMethod,
    distributor = UnifiedPush.getSavedDistributor(context),
  )

  private fun refreshAuthorizationLocked(account: UnifiedPushAccountTransition.AccountContext) = RefreshAuthorization(
    processId = UnifiedPushLifecycle.processInstanceId(),
    accountId = account.accountId,
    localDeviceId = account.localDeviceId,
    manual = SignalStore.unifiedpush.airGapped,
    url = SignalStore.unifiedpush.mollySocketUrl,
    vapid = SignalStore.unifiedpush.vapidPublicKey,
    deliveryMethod = SignalStore.settings.preferredNotificationMethod,
  )
}

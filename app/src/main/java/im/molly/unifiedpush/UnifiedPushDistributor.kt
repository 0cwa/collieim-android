package im.molly.unifiedpush

import android.content.Context
import im.molly.unifiedpush.enrollment.PairingRegistrationGate
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.ui.SelectDistributorDialogsBuilder
import org.unifiedpush.android.connector.ui.UnifiedPushFunctions
import org.thoughtcrime.securesms.service.KeyCachingService

object UnifiedPushDistributor {

  @JvmStatic
  fun registerApp(vapid: String?) {
    synchronized(UnifiedPushLifecycle.monitor) {
      if (pairingRouteProtectedLocked()) return
      UnifiedPush.registerApp(AppDependencies.application, vapid = vapid)
    }
  }

  @JvmStatic
  fun unregisterApp() {
    synchronized(UnifiedPushLifecycle.monitor) {
      if (pairingRouteProtectedLocked()) return
      UnifiedPush.unregisterApp(AppDependencies.application)
      // MessagingReceiver.onUnregistered won't be called after the unregistration request
      SignalStore.unifiedpush.endpoint = null
    }
  }

  fun selectFirstDistributor() {
    val context = AppDependencies.application
    UnifiedPush.getDistributors(context).firstOrNull()?.also {
      saveDistributor(context, it)
    }
  }

  fun saveDistributor(context: Context, distributor: String): Boolean {
    synchronized(UnifiedPushLifecycle.monitor) {
      if (KeyCachingService.isLocked()) {
        val protectedOwner = im.molly.unifiedpush.receiver.LockedPushState.protectedPairingDistributor(context)
        if (protectedOwner != null && distributor != protectedOwner) return false
      } else {
        val pendingPairing = PairingRegistrationGate.current()?.binding
        if (pendingPairing != null && distributor != pendingPairing.distributorId) return false
        val pairing = SignalStore.unifiedpush.pairingEndpointBinding
        val scopedInstance = pairing?.instance ?: SignalStore.unifiedpush.endpointInstance?.takeIf { PairingRegistrationGate.isPairingInstance(it) }
        val scopedOwner = pairing?.distributorId ?: if (scopedInstance != null) UnifiedPush.getSavedDistributor(context) else null
        if (scopedInstance != null && distributor != scopedOwner) {
          // The instance overload is routed through the saved distributor; clear the old scoped
          // tuple before selecting the new owner so no late callback can inherit its binding.
          if (scopedOwner != null && UnifiedPush.getSavedDistributor(context) == scopedOwner) {
            try {
              UnifiedPush.unregisterApp(context, scopedInstance)
            } catch (_: Exception) {
              return false
            }
          } else {
            return false
          }
          SignalStore.unifiedpush.setEndpointRegistration(null, null, null, null)
          im.molly.unifiedpush.receiver.LockedPushState.setProtectedPairingRoute(context, null, null)
        }
        SignalStore.unifiedpush.registrationRetryScheduled = false
      }
      UnifiedPush.saveDistributor(context, distributor)
      return true
    }
  }

  @JvmStatic
  fun showSelectDistributorDialog(context: Context) {
    SelectDistributorDialogsBuilder(
      context,
      object : UnifiedPushFunctions {
        override fun getAckDistributor(): String? = UnifiedPush.getAckDistributor(context)
        override fun getDistributors(): List<String> = UnifiedPush.getDistributors(context)
        override fun registerApp(instance: String) {
          synchronized(UnifiedPushLifecycle.monitor) {
            if (pairingRouteProtectedLocked()) return
            UnifiedPush.registerApp(context, instance)
          }
        }
        override fun saveDistributor(distributor: String) { UnifiedPushDistributor.saveDistributor(context, distributor) }
        override fun tryUseDefaultDistributor(callback: (Boolean) -> Unit) = UnifiedPush.tryUseDefaultDistributor(context, callback)
      }
    ).apply {
      mayUseCurrent = false
      mayUseDefault = false
    }.run()
  }

  fun checkIfActive(): Boolean {
    return UnifiedPush.getAckDistributor(AppDependencies.application) != null
  }

  /** A default instance can never replace or unregister a pairing-scoped connector route. */
  private fun pairingRouteProtectedLocked(): Boolean {
    check(Thread.holdsLock(UnifiedPushLifecycle.monitor))
    val values = SignalStore.unifiedpush
    val pairingRoute = values.pairingEndpointBinding != null ||
      PairingRegistrationGate.isPairingInstance(values.endpointInstance ?: "")
    return PairingRegistrationGate.blocksLegacyRelay(pairingRoute)
  }
}

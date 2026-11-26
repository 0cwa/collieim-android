package im.molly.unifiedpush.enrollment

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import org.unifiedpush.android.connector.UnifiedPush

/** The connector stores one selected distributor but generates a separate token for each instance. */
internal object PairingDistributorRegistration {
  fun register(context: Context, binding: PairingRegistrationGate.Binding) {
    UnifiedPush.registerApp(
      BroadcastContext(context, binding.distributorId),
      binding.instance,
      "CollieSocket pairing ${binding.hostLabel}",
      binding.vapidPublicKey,
    )
  }

  fun discard(context: Context, binding: PairingRegistrationGate.Binding) {
    val selected = UnifiedPush.getSavedDistributor(context)
    if (selected == binding.previousDistributorId || selected == binding.distributorId) {
      unregister(context, binding.distributorId, binding.instance)
    }
  }

  /** Both the endpoint callback and an authenticated pairing confirmation have been accepted. */
  fun promote(context: Context, binding: PairingRegistrationGate.Binding) {
    if (binding.previousDistributorId == binding.distributorId) return
    // Connector 3.0.10's saveDistributor clears the acknowledgment on an owner change.
    // Publish the new owner and its already-acknowledged endpoint together.
    context.getSharedPreferences("unifiedpush.connector", Context.MODE_PRIVATE).edit()
      .putString("unifiedpush.distributor", binding.distributorId)
      .putBoolean("unifiedpush.distributor_ack", true)
      .apply()
    unregister(context, binding.previousDistributorId, binding.previousInstance)
  }

  private fun unregister(context: Context, distributor: String, instance: String) {
    runCatching { UnifiedPush.unregisterApp(BroadcastContext(context, distributor), instance) }
  }

  /** Keep the connector's tokens, encryption keys, and broadcast options. Route only its distributor intents. */
  class BroadcastContext(context: Context, var distributor: String? = null) :
    ContextWrapper(if (context is BroadcastContext) context.baseContext else context) {
    private val routesRegistration = distributor != null
    var acknowledge = true

    override fun sendBroadcast(intent: Intent) {
      if (acknowledge || intent.action != ACK_ACTION) super.sendBroadcast(route(intent))
    }

    override fun sendBroadcast(intent: Intent, receiverPermission: String?, options: Bundle?) =
      super.sendBroadcast(route(intent), receiverPermission, options)

    private fun route(intent: Intent): Intent {
      val target = distributor ?: return intent
      return when (intent.action) {
        "org.unifiedpush.android.distributor.REGISTER", "org.unifiedpush.android.distributor.UNREGISTER" ->
          if (routesRegistration) Intent(intent).setPackage(target) else intent
        ACK_ACTION -> Intent(intent).setPackage(target)
        else -> intent
      }
    }
  }

  private const val ACK_ACTION = "org.unifiedpush.android.distributor.MESSAGE_ACK"
}

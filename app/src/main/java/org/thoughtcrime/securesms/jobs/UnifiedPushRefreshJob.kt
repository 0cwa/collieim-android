package org.thoughtcrime.securesms.jobs

import im.molly.unifiedpush.MollySocketRepository
import im.molly.unifiedpush.UnifiedPushDistributor
import im.molly.unifiedpush.UnifiedPushNotificationBuilder
import im.molly.unifiedpush.enrollment.RelayDisclosure
import im.molly.unifiedpush.model.LinkStatus
import im.molly.unifiedpush.model.MollySocketDevice
import im.molly.unifiedpush.model.RegistrationStatus
import im.molly.unifiedpush.model.toRegistrationStatus
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.signal.core.util.eventbus.EventBus
import org.signal.core.util.logging.Log
import org.signal.network.exceptions.NonSuccessfulResponseCodeException
import kotlinx.coroutines.runBlocking
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.events.PushServiceEvent
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.whispersystems.signalservice.internal.push.DeviceLimitExceededException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Handles UnifiedPush registration and ensures the MollySocket status is up-to-date.
 * Unregisters if the account is not registered or UnifiedPush is disabled.
 *
 * @param testPing Send a ping notification with the registration.
 *   Used during first registration to a new MollySocket server.
 * @param fromNewEndpoint Avoid registration cycle: if the Job is run from
 *   the receiver "onNewEndpoint", it will not register to the distributor.
 */
class UnifiedPushRefreshJob private constructor(
  private val testPing: Boolean,
  private val fromNewEndpoint: Boolean,
  private val authorization: RelayDisclosure.RefreshAuthorization?,
  private val configureDelivery: Boolean,
  parameters: Parameters,
) : BaseJob(parameters) {

  constructor() : this(testPing = false)

  constructor(testPing: Boolean) : this(testPing = testPing, fromNewEndpoint = false)

  constructor(testPing: Boolean, fromNewEndpoint: Boolean) : this(testPing, fromNewEndpoint, null)

  constructor(authorization: RelayDisclosure.RefreshAuthorization, configureDelivery: Boolean = false) : this(false, false, authorization, configureDelivery)

  private constructor(testPing: Boolean, fromNewEndpoint: Boolean, authorization: RelayDisclosure.RefreshAuthorization?, configureDelivery: Boolean = false) : this(
    testPing = testPing,
    fromNewEndpoint = fromNewEndpoint,
    authorization = authorization,
    configureDelivery = configureDelivery,
    parameters = Parameters.Builder()
      .setQueue(FcmRefreshJob.QUEUE_KEY)
      .addConstraint(NetworkConstraint.KEY)
      .setMaxAttempts(3)
      .setLifespan(TimeUnit.HOURS.toMillis(6))
      // Accept a delivery change even when two routine refreshes already occupy the queue.
      .setMaxInstancesForFactory(if (configureDelivery) Parameters.UNLIMITED else 2)
      .build()
  )

  @Throws(Exception::class)
  public override fun onRun() {
    val pendingCleanup = synchronized(UnifiedPushLifecycle.monitor) {
      if (authorization != null && !RelayDisclosure.matchesRefreshLocked(authorization)) return
      SignalStore.unifiedpush.pendingRelayCleanup?.let { it to UnifiedPushLifecycle.currentGeneration() }
    }
    val cleanupGeneration = pendingCleanup?.let { (cleanup, generation) ->
      val result = finishDeviceClear(cleanup, expectedGeneration = generation)
      if (!result.value) return
      result.generation
    }
    val lease = synchronized(UnifiedPushLifecycle.monitor) {
      if (authorization != null && !RelayDisclosure.matchesRefreshLocked(authorization)) return
      UnifiedPushAccountTransition.acquireLease(cleanupGeneration)
    }
    if (lease == null) {
      val hasAccount = synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushAccountTransition.isRegisteredLocked() }
      if (hasAccount) throw IOException("UnifiedPush account context is transitioning")
      return
    }
    try {
      if (configureDelivery) {
        synchronized(UnifiedPushLifecycle.monitor) {
          if (authorization != null && !RelayDisclosure.matchesRefreshLocked(authorization)) return
          if (SignalStore.settings.preferredNotificationMethod != NotificationDeliveryMethod.UNIFIEDPUSH ||
            SignalStore.account.isLinkedDevice) return
          if (!UnifiedPushLifecycle.isReadyLocked() || UnifiedPushLifecycle.currentGeneration() != lease.generation) {
            throw IOException("UnifiedPush account context is transitioning")
          }
          ApplicationContext.getInstance(context).applyPushNotificationServicesFromRefresh()
        }
      }
      runWithLease(lease)
    } finally {
      lease.close()
    }
  }

  private fun runWithLease(lease: UnifiedPushLifecycle.Lease<UnifiedPushAccountTransition.AccountContext>) {
    val enabled = SignalStore.unifiedpush.enabled
    val currentStatus = SignalStore.unifiedpush.registrationStatus

    Log.d(TAG, "Current registration status: $currentStatus")

    if (!enabled) {
      Log.d(TAG, "UnifiedPush is disabled.")
      cleanupMollySocketDevice(lease)
      return
    }

    var currentLease = lease
    try {
      val validatedLease = validateMollySocketDevice(currentLease)
      currentLease = validatedLease

      val newStatus = checkRegistrationStatus(currentLease)
      if (currentStatus == newStatus) {
        Log.d(TAG, "Registration status unchanged.")
      } else {
        Log.d(TAG, "Updated registration status: $newStatus")
        UnifiedPushLifecycle.withCurrentLease(currentLease) { SignalStore.unifiedpush.registrationStatus = newStatus }
      }

      if (UnifiedPushLifecycle.isCurrentGeneration(currentLease.generation)) {
        when (newStatus) {
          RegistrationStatus.REGISTERED -> UnifiedPushNotificationBuilder(context).clearAlerts(clearRegistrationFailure = !SignalStore.unifiedpush.registrationFailed)
          RegistrationStatus.FORBIDDEN_ENDPOINT -> UnifiedPushNotificationBuilder(context).setNotificationMollySocketForbiddenEndpoint()
          RegistrationStatus.FORBIDDEN_UUID -> UnifiedPushNotificationBuilder(context).setNotificationMollySocketForbiddenUuid()
          RegistrationStatus.FORBIDDEN_PASSWORD -> UnifiedPushNotificationBuilder(context).setNotificationMollySocketForbiddenPassword()
          else -> {}
        }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Error checking registration status", t)

      if (currentStatus != RegistrationStatus.REGISTERED) {
        UnifiedPushLifecycle.withCurrentLease(currentLease) { SignalStore.unifiedpush.registrationStatus = RegistrationStatus.UNKNOWN }
      }

      // Re-throw the exception as IOException for retry
      when (t) {
        is IOException -> throw t
        else -> throw IOException(t)
      }
    } finally {
      currentLease.close()
      AppDependencies.resetNetwork(restartMessageObserver = true)
      EventBus.default.post(PushServiceEvent)
    }
  }

  @Throws(IOException::class)
  private fun checkRegistrationStatus(lease: UnifiedPushLifecycle.Lease<UnifiedPushAccountTransition.AccountContext>): RegistrationStatus {
    val endpoint = SignalStore.unifiedpush.endpoint
    val airGapped = SignalStore.unifiedpush.airGapped
    val mollySocketUrl = SignalStore.unifiedpush.mollySocketUrl?.toHttpUrlOrNull()
    val lastReceivedTime = SignalStore.unifiedpush.lastReceivedTime
    val vapid = SignalStore.unifiedpush.vapidPublicKey

    Log.d(TAG, "Last notification received at: $lastReceivedTime")

    if (vapid == null) {
      Log.e(TAG, "Missing VAPID public key.")
      return RegistrationStatus.PENDING
    }

    if (!fromNewEndpoint) {
      UnifiedPushDistributor.registerApp(vapid)
    }

    if (!UnifiedPushDistributor.checkIfActive() || endpoint == null) {
      Log.e(TAG, "Distributor is not active or endpoint is missing.")
      return RegistrationStatus.PENDING
    }

    if (!airGapped && mollySocketUrl?.isHttps != true) {
      Log.e(TAG, "Missing or invalid HTTPS MollySocket URL.")
      return RegistrationStatus.PENDING
    }

    val device = lease.snapshot.pushDevice ?: try {
      val created = MollySocketRepository.createDevice()
      val published = try {
        synchronized(UnifiedPushLifecycle.monitor) {
          val values = SignalStore.unifiedpush
          if (UnifiedPushLifecycle.isReadyLocked() && UnifiedPushLifecycle.currentGeneration() == lease.generation &&
            UnifiedPushAccountTransition.accountAndLocalDeviceMatchLocked(lease.snapshot) && values.device == null) {
            try {
              values.commitProvisionedDevice(created)
            } catch (e: Exception) {
              throw UnacknowledgedDeviceException(e)
            }
            // A failure here cannot revoke an already acknowledged credential that may now be visible.
            values.acknowledgeProvisionedDevice()
            true
          } else {
            false
          }
        }
      } catch (e: UnacknowledgedDeviceException) {
        removeUnpublishedDevice(created, lease, e.failure)
      }
      if (!published) {
        // The linked-device request completed for an obsolete context. Remove it while this lease still pins the old account.
        MollySocketRepository.removeDevice(created)
        return RegistrationStatus.PENDING
      }
      Log.d(TAG, "Created new MollySocket device: $created")
      created
    } catch (e: DeviceLimitExceededException) {
      Log.e(TAG, "Device limit exceeded: ${e.max} total devices already.")
      UnifiedPushNotificationBuilder(context).setNotificationDeviceLimitExceeded(e.max)
      return RegistrationStatus.PENDING
    }

    if (airGapped) {
      return if (lastReceivedTime > 0) {
        Log.d(TAG, "Air-gapped server: Device is registered.")
        RegistrationStatus.REGISTERED
      } else {
        Log.d(TAG, "Air gapped server: Manual registration required!")
        RegistrationStatus.PENDING
      }
    }

    Log.d(TAG, "Re-registering $device on MollySocket server...")

    val result = MollySocketRepository.registerDeviceOnServer(
      url = mollySocketUrl!!,
      device = device,
      endpoint = endpoint,
      ping = testPing,
    )

    Log.d(TAG, "Registration result: $result")

    return result.toRegistrationStatus()
  }

  /**
   * The store did not acknowledge [created]. Remove the remote device while [lease] still pins its account. The write
   * may have taken effect before its acknowledgement failed, so once removal is confirmed clear the record only if it
   * still belongs to the same account and matches [created] exactly, even if a route-only transition invalidated the
   * lease generation while unlinking ran. Always rethrows [failure], attaching any failed removal or clear as a
   * suppressed exception.
   */
  private fun removeUnpublishedDevice(
    created: MollySocketDevice,
    lease: UnifiedPushLifecycle.Lease<UnifiedPushAccountTransition.AccountContext>,
    failure: Exception,
  ): Nothing {
    Log.w(TAG, "Could not persist new $created, removing it.", failure)
    // An interrupted acknowledgement re-asserts the flag, which would otherwise abort the blocking removal call.
    val interrupted = Thread.interrupted()
    try {
      removeAndClearDevice(created, lease, compensating = true)
    } catch (cleanup: Exception) {
      Log.w(TAG, "Could not remove unpublished $created.", cleanup)
      failure.addSuppressed(cleanup)
    } finally {
      if (interrupted) Thread.currentThread().interrupt()
    }
    throw failure
  }

  @Throws(IOException::class)
  private fun validateMollySocketDevice(
    lease: UnifiedPushLifecycle.Lease<UnifiedPushAccountTransition.AccountContext>,
  ): UnifiedPushLifecycle.Lease<UnifiedPushAccountTransition.AccountContext> {
    val device = lease.snapshot.pushDevice ?: return lease
    val values = SignalStore.unifiedpush
    val needsReplacement = !values.vapidKeySynced || values.isProvisioningPending(device)
    var cleared = false

    var clearGeneration: Long? = null
    when (MollySocketRepository.getDeviceStatus(device)) {
      LinkStatus.LINKED -> {
        if (needsReplacement) {
          Log.w(TAG, "Relay credential needs replacement. Removing linked $device.")
          clearGeneration = removeAndClearDevice(device, lease)
          cleared = true
        }
      }

      LinkStatus.NOT_LINKED -> {
        Log.w(TAG, "$device no longer linked, will be recreated.")
        clearGeneration = clearDevice(device, lease)
        cleared = true
      }

      LinkStatus.UNKNOWN -> {
        if (needsReplacement) {
          throw IOException("Relay credential needs replacement, but cannot determine $device link status.")
        }
        return lease // Optimistically assume the device is linked
      }
    }

    if (!cleared) return lease
    val generation = clearGeneration ?: throw IOException("UnifiedPush account context changed while validating the relay device")
    return acquireLeaseAfterDeviceClear(generation)
      ?: throw IOException("UnifiedPush account context changed while validating the relay device")
  }

  /** Reacquires only the generation completed by device clearing; a later transition must invalidate this continuation. */
  internal fun acquireLeaseAfterDeviceClear(
    generation: Long,
  ): UnifiedPushLifecycle.Lease<UnifiedPushAccountTransition.AccountContext>? =
    UnifiedPushAccountTransition.acquireLease(generation)

  @Throws(IOException::class)
  private fun cleanupMollySocketDevice(lease: UnifiedPushLifecycle.Lease<UnifiedPushAccountTransition.AccountContext>) {
    if (SignalStore.unifiedpush.airGapped) {
      Log.d(TAG, "MollySocket is air-gapped, cleanup skipped.")
      return
    }

    val device = lease.snapshot.pushDevice ?: return
    Log.d(TAG, "Cleaning up non-air-gapped $device...")

    when (MollySocketRepository.getDeviceStatus(device)) {
      LinkStatus.LINKED -> if (removeAndClearDevice(device, lease) == null) throw IOException("UnifiedPush account context changed during cleanup")
      LinkStatus.NOT_LINKED -> if (clearDevice(device, lease) == null) throw IOException("UnifiedPush account context changed during cleanup")
      LinkStatus.UNKNOWN -> {
        throw IOException("Cannot determine $device link status during cleanup.")
      }
    }
  }

  @Throws(IOException::class)
  private fun removeAndClearDevice(
    device: MollySocketDevice,
    lease: UnifiedPushLifecycle.Lease<UnifiedPushAccountTransition.AccountContext>,
    compensating: Boolean = false,
  ): Long? = clearDevice(device, lease, unlinkConfirmed = false, compensating = compensating)

  /**
   * Close admission and persist the intent before unlinking under the lease that pins the account.
   * Drop the lease before entering the serialized transition, which drains any remaining work.
   */
  internal fun clearDevice(
    device: MollySocketDevice,
    lease: UnifiedPushLifecycle.Lease<UnifiedPushAccountTransition.AccountContext>,
    unlinkConfirmed: Boolean = true,
    compensating: Boolean = false,
  ): Long? {
    val cleanup = try {
      val intent = synchronized(UnifiedPushLifecycle.monitor) {
        val values = SignalStore.unifiedpush
        val current = values.device
        val ownsReadyGeneration = UnifiedPushLifecycle.isReadyLocked() && UnifiedPushLifecycle.currentGeneration() == lease.generation
        if (lease.closed || (!ownsReadyGeneration && !compensating) || values.pendingRelayCleanup != null ||
          !UnifiedPushAccountTransition.accountAndLocalDeviceMatchLocked(lease.snapshot) ||
          (current != device && !(compensating && current == null))) return null
        val invalidationGeneration = if (ownsReadyGeneration) {
          UnifiedPushLifecycle.invalidateLocked(credentialOnly = true)
          UnifiedPushLifecycle.currentGeneration()
        } else {
          null
        }
        UnifiedPushValues.RelayCleanup(
          lease.snapshot.accountId,
          lease.snapshot.localDeviceId,
          device,
          invalidationGeneration,
          unlinkConfirmed = unlinkConfirmed,
          clearControlUpdate = current != null,
        ).also { values.setRelayCleanup(it, pending = true) }
      }

      if (!intent.unlinkConfirmed) {
        // Recovery may already be waiting on this lease. It reads the latest phase after the drain.
        MollySocketRepository.removeDevice(device)
        synchronized(UnifiedPushLifecycle.monitor) {
          SignalStore.unifiedpush.setRelayCleanup(intent.copy(unlinkConfirmed = true), pending = true)
        }
      }
      intent
    } finally {
      lease.close()
    }

    val transition = try {
      finishDeviceClear(cleanup, cleanup.invalidationGeneration)
    } catch (stale: UnifiedPushLifecycle.StaleLifecycleGenerationException) {
      if (cleanup.invalidationGeneration == null) throw stale
      finishDeviceClear(cleanup, expectedGeneration = null)
    }
    return transition.generation.takeIf { transition.value }
  }

  /** Only the cleanup record can authorize completing a clear after restart or a lost acknowledgement. */
  private fun finishDeviceClear(
    cleanup: UnifiedPushValues.RelayCleanup,
    expectedGeneration: Long?,
  ): UnifiedPushLifecycle.TransitionResult<Boolean> = runBlocking {
    UnifiedPushAccountTransition.withTransitionAndGeneration(
      expectedGeneration = expectedGeneration,
      relayCleanup = { cleanup },
    ) {
      val values = SignalStore.unifiedpush
      val intent = synchronized(UnifiedPushLifecycle.monitor) {
        val pending = values.pendingRelayCleanup
        if (pending == null || !pending.hasSameOwner(cleanup) || !UnifiedPushAccountTransition.ownsRelayCleanupLocked(pending)) {
          return@withTransitionAndGeneration false
        }
        // A prior write may have failed before acknowledgement. Require a durable intent before advancing.
        values.setRelayCleanup(pending, pending = true)
        pending
      }

      if (!intent.unlinkConfirmed) {
        when (MollySocketRepository.getDeviceStatus(intent.device)) {
          LinkStatus.NOT_LINKED -> Unit
          LinkStatus.LINKED -> MollySocketRepository.removeDevice(intent.device)
          LinkStatus.UNKNOWN -> throw IOException("Cannot confirm the old relay is unlinked during cleanup")
        }
      }

      synchronized(UnifiedPushLifecycle.monitor) {
        UnifiedPushAccountTransition.clearConfirmedRelayCleanupLocked(intent)
      }
    }
  }

  private class UnacknowledgedDeviceException(val failure: Exception) : Exception(failure)

  override fun onFailure() = Unit

  public override fun onShouldRetry(throwable: Exception): Boolean {
    return throwable !is NonSuccessfulResponseCodeException
  }

  override fun serialize(): ByteArray? {
    return JsonJobData.Builder()
      .putBoolean(KEY_PING, testPing)
      .putBoolean(KEY_FROM_NEW_ENDPOINT, fromNewEndpoint)
      .putBoolean(KEY_CONFIGURE_DELIVERY, configureDelivery)
      .apply {
        authorization?.let {
          putString(KEY_DISCLOSURE_PROCESS, it.processId)
          putString(KEY_DISCLOSURE_ACCOUNT, it.accountId)
          putInt(KEY_DISCLOSURE_DEVICE, it.localDeviceId)
          putBoolean(KEY_DISCLOSURE_MANUAL, it.manual)
          putString(KEY_DISCLOSURE_URL, it.url)
          putString(KEY_DISCLOSURE_VAPID, it.vapid)
          putString(KEY_DISCLOSURE_METHOD, it.deliveryMethod.name)
        }
      }
      .serialize()
  }

  override fun getFactoryKey(): String = KEY

  class Factory : Job.Factory<UnifiedPushRefreshJob?> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): UnifiedPushRefreshJob {
      val data = JsonJobData.deserialize(serializedData)
      val authorization = if (data.hasString(KEY_DISCLOSURE_PROCESS)) {
        RelayDisclosure.RefreshAuthorization(
          processId = data.getString(KEY_DISCLOSURE_PROCESS),
          accountId = data.getString(KEY_DISCLOSURE_ACCOUNT),
          localDeviceId = data.getInt(KEY_DISCLOSURE_DEVICE),
          manual = data.getBoolean(KEY_DISCLOSURE_MANUAL),
          url = data.getString(KEY_DISCLOSURE_URL),
          vapid = data.getString(KEY_DISCLOSURE_VAPID),
          deliveryMethod = NotificationDeliveryMethod.valueOf(data.getString(KEY_DISCLOSURE_METHOD)),
        )
      } else {
        null
      }
      return UnifiedPushRefreshJob(
        testPing = data.getBoolean(KEY_PING),
        fromNewEndpoint = data.getBoolean(KEY_FROM_NEW_ENDPOINT),
        authorization = authorization,
        configureDelivery = data.getBooleanOrDefault(KEY_CONFIGURE_DELIVERY, false),
        parameters = parameters,
      )
    }
  }

  companion object {
    private val TAG = Log.tag(UnifiedPushRefreshJob::class.java)

    @JvmStatic
    fun configurePreferredDelivery(): UnifiedPushRefreshJob = UnifiedPushRefreshJob(false, false, null, configureDelivery = true)

    const val KEY = "UnifiedPushRefreshJob"
    private const val KEY_PING = "ping"
    private const val KEY_FROM_NEW_ENDPOINT = "fromNewEndpoint"
    private const val KEY_CONFIGURE_DELIVERY = "configureDelivery"
    private const val KEY_DISCLOSURE_PROCESS = "disclosureProcessId"
    private const val KEY_DISCLOSURE_ACCOUNT = "disclosureAccountId"
    private const val KEY_DISCLOSURE_DEVICE = "disclosureLocalDeviceId"
    private const val KEY_DISCLOSURE_MANUAL = "disclosureManual"
    private const val KEY_DISCLOSURE_URL = "disclosureUrl"
    private const val KEY_DISCLOSURE_VAPID = "disclosureVapid"
    private const val KEY_DISCLOSURE_METHOD = "disclosureDeliveryMethod"
  }
}

/**
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.service.webrtc.links

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.SingleEmitter
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.signal.libsignal.zkgroup.GenericServerPublicParams
import org.signal.libsignal.zkgroup.calllinks.CallLinkAuthCredentialPresentation
import org.signal.libsignal.zkgroup.calllinks.CallLinkSecretParams
import org.signal.libsignal.zkgroup.calllinks.CreateCallLinkCredential
import org.signal.libsignal.zkgroup.calllinks.CreateCallLinkCredentialPresentation
import org.signal.libsignal.zkgroup.calllinks.CreateCallLinkCredentialRequestContext
import org.signal.libsignal.zkgroup.calllinks.CreateCallLinkCredentialResponse
import org.signal.network.NetworkResult
import org.signal.ringrtc.CallLinkRootKey
import org.signal.ringrtc.CallLinkState
import org.signal.ringrtc.CallLinkState.Restrictions
import org.signal.ringrtc.CallManager
import org.thoughtcrime.securesms.calls.permissions.CallLinkPolicy
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.calls.permissions.ManagedCallLinkRootKey
import org.thoughtcrime.securesms.calls.runtime.CallRuntime
import org.thoughtcrime.securesms.calls.runtime.CallRuntimePolicy
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.net.SignalNetwork
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.service.webrtc.SignalCallManager
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Call Link manager which encapsulates CallManager and provides a stable interface.
 *
 * We can remove the outer sealed class once we have the final, working builds from core.
 */
class SignalCallLinkManager(
  private val signalCallManager: SignalCallManager
) {

  private val genericServerPublicParams: GenericServerPublicParams by lazy {
    GenericServerPublicParams(
      AppDependencies.signalServiceNetworkAccess
        .getConfiguration()
        .genericServerPublicParams
    )
  }

  private fun requestCreateCallLinkCredentialPresentation(
    linkRootKey: ByteArray,
    roomId: ByteArray
  ): CreateCallLinkCredentialPresentation {
    val userAci = Recipient.self().requireAci()
    val requestContext = CreateCallLinkCredentialRequestContext.forRoom(roomId)
    val request = requestContext.request

    Log.d(TAG, "Requesting call link credential response.")

    when (val result: NetworkResult<CreateCallLinkCredentialResponse> = SignalNetwork.callingApi.createCallLinkCredential(request)) {
      is NetworkResult.Success -> {
        Log.d(TAG, "Requesting call link credential.")

        val createCallLinkCredential: CreateCallLinkCredential = requestContext.receiveResponse(
          result.result,
          userAci.libSignalAci,
          genericServerPublicParams
        )

        Log.d(TAG, "Requesting and returning call link presentation.")

        return createCallLinkCredential.present(
          roomId,
          userAci.libSignalAci,
          genericServerPublicParams,
          CallLinkSecretParams.deriveFromRootKey(linkRootKey)
        )
      }

      else -> throw IOException("Failed to create credential response", result.getCause())
    }
  }

  private fun requestCallLinkAuthCredentialPresentation(
    linkRootKey: ByteArray
  ): CallLinkAuthCredentialPresentation {
    return AppDependencies.groupsV2Authorization.getCallLinkAuthorizationForToday(
      genericServerPublicParams,
      CallLinkSecretParams.deriveFromRootKey(linkRootKey)
    )
  }

  fun createCallLink(
    callLinkCredentials: CallLinkCredentials
  ): Single<CreateCallLinkResult> {
    val denied = CreateCallLinkResult.Failure(CallLinkPolicy.DENIED_STATUS)
    val keyBytes = callLinkCredentials.linkKeyBytes.copyOf()
    val adminPassKey = callLinkCredentials.adminPassBytes?.copyOf()
    return authorizedLinkOperation<CreateCallLinkResult>(denied) { emitter, generation ->
      val roomId = requireNotNull(ManagedCallLinkRootKey.fromBytes(keyBytes)).roomId.encodeForProto().toByteArray()
      requireNotNull(adminPassKey)

      Log.d(TAG, "Generating credential.")
      val credentialPresentation = try {
        requestCreateCallLinkCredentialPresentation(keyBytes, roomId)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to create call link credential.", e)
        emitter.onSuccess(CreateCallLinkResult.Failure(-1))
        return@authorizedLinkOperation null
      }

      val publicParams = CallLinkSecretParams.deriveFromRootKey(keyBytes).publicParams
      val operation: (CallManager) -> Unit = { callManager ->
        val dispatched = CallPermissions.runIfCurrent(generation) {
          if (!emitter.isDisposed) {
            callManager.createCallLink(
              SignalStore.internal.groupCallingServer,
              credentialPresentation.serialize(),
              CallLinkRootKey(keyBytes),
              adminPassKey,
              publicParams.serialize(),
              Restrictions.ADMIN_APPROVAL
            ) { result ->
              val outcome = if (result.isSuccess) {
                Log.d(TAG, "Successfully created call link.")
                CreateCallLinkResult.Success(
                  credentials = CallLinkCredentials(result.value!!.rootKey.keyBytes, adminPassKey),
                  state = result.value!!.toAppState()
                )
              } else {
                Log.w(TAG, "Failed to create call link with failure status ${result.status}")
                CreateCallLinkResult.Failure(result.status)
              }
              emitAuthorized(emitter, generation, outcome, denied)
            }
          }
        }
        if (!dispatched) emitter.onSuccess(denied)
      }
      operation
    }
  }

  fun readCallLink(
    credentials: CallLinkCredentials
  ): Single<ReadCallLinkResult> {
    val denied = ReadCallLinkResult.Failure(CallLinkPolicy.DENIED_STATUS)
    val keyBytes = credentials.linkKeyBytes.copyOf()
    return authorizedLinkOperation<ReadCallLinkResult>(denied, allowColdStart = false) { emitter, generation ->
      val presentation = requestCallLinkAuthCredentialPresentation(keyBytes).serialize()
      val operation: (CallManager) -> Unit = { callManager ->
        val dispatched = CallPermissions.runIfCurrent(generation) {
          if (!emitter.isDisposed) {
            callManager.readCallLink(
              SignalStore.internal.groupCallingServer,
              presentation,
              CallLinkRootKey(keyBytes)
            ) { result ->
              val outcome = if (result.isSuccess) {
                ReadCallLinkResult.Success(result.value!!.toAppState())
              } else {
                Log.w(TAG, "Failed to read call link with failure status ${result.status}")
                ReadCallLinkResult.Failure(result.status)
              }
              emitAuthorized(emitter, generation, outcome, denied)
            }
          }
        }
        if (!dispatched) emitter.onSuccess(denied)
      }
      operation
    }
  }

  fun updateCallLinkName(
    credentials: CallLinkCredentials,
    name: String
  ): Single<UpdateCallLinkResult> {
    if (credentials.adminPassBytes == null) return Single.just(UpdateCallLinkResult.NotAuthorized)
    val keyBytes = credentials.linkKeyBytes.copyOf()
    val adminPassKey = credentials.adminPassBytes.copyOf()

    return authorizedLinkOperation<UpdateCallLinkResult>(UpdateCallLinkResult.NotAuthorized) { emitter, generation ->
      val presentation = requestCallLinkAuthCredentialPresentation(keyBytes)
      val operation: (CallManager) -> Unit = { callManager ->
        val dispatched = CallPermissions.runIfCurrent(generation) {
          if (!emitter.isDisposed) {
            callManager.updateCallLinkName(
              SignalStore.internal.groupCallingServer,
              presentation.serialize(),
              CallLinkRootKey(keyBytes),
              adminPassKey,
              name
            ) { result ->
              val outcome = if (result.isSuccess) UpdateCallLinkResult.Update(result.value!!.toAppState()) else UpdateCallLinkResult.Failure(result.status)
              emitAuthorized(emitter, generation, outcome, UpdateCallLinkResult.NotAuthorized)
            }
          }
        }
        if (!dispatched) emitter.onSuccess(UpdateCallLinkResult.NotAuthorized)
      }
      operation
    }
  }

  fun updateCallLinkRestrictions(
    credentials: CallLinkCredentials,
    restrictions: Restrictions
  ): Single<UpdateCallLinkResult> {
    if (credentials.adminPassBytes == null) return Single.just(UpdateCallLinkResult.NotAuthorized)
    val keyBytes = credentials.linkKeyBytes.copyOf()
    val adminPassKey = credentials.adminPassBytes.copyOf()

    return authorizedLinkOperation<UpdateCallLinkResult>(UpdateCallLinkResult.NotAuthorized) { emitter, generation ->
      val presentation = requestCallLinkAuthCredentialPresentation(keyBytes)
      val operation: (CallManager) -> Unit = { callManager ->
        val dispatched = CallPermissions.runIfCurrent(generation) {
          if (!emitter.isDisposed) {
            callManager.updateCallLinkRestrictions(
              SignalStore.internal.groupCallingServer,
              presentation.serialize(),
              CallLinkRootKey(keyBytes),
              adminPassKey,
              restrictions
            ) { result ->
              val outcome = if (result.isSuccess) UpdateCallLinkResult.Update(result.value!!.toAppState()) else UpdateCallLinkResult.Failure(result.status)
              emitAuthorized(emitter, generation, outcome, UpdateCallLinkResult.NotAuthorized)
            }
          }
        }
        if (!dispatched) emitter.onSuccess(UpdateCallLinkResult.NotAuthorized)
      }
      operation
    }
  }

  fun deleteCallLink(
    credentials: CallLinkCredentials
  ): Single<UpdateCallLinkResult> {
    if (credentials.adminPassBytes == null) return Single.just(UpdateCallLinkResult.NotAuthorized)
    val keyBytes = credentials.linkKeyBytes.copyOf()
    val adminPassKey = credentials.adminPassBytes.copyOf()

    return authorizedLinkOperation<UpdateCallLinkResult>(UpdateCallLinkResult.NotAuthorized) { emitter, generation ->
      val presentation = requestCallLinkAuthCredentialPresentation(keyBytes)
      val roomId = requireNotNull(ManagedCallLinkRootKey.fromBytes(keyBytes)).roomId
      val operation: (CallManager) -> Unit = { callManager ->
        val dispatched = CallPermissions.runIfCurrent(generation) {
          if (!emitter.isDisposed) {
            callManager.deleteCallLink(
              SignalStore.internal.groupCallingServer,
              presentation.serialize(),
              CallLinkRootKey(keyBytes),
              adminPassKey
            ) { result ->
              val outcome = if (result.isSuccess && result.value == true) {
                UpdateCallLinkResult.Delete(roomId)
              } else {
                when (result.status) {
                  409.toShort() -> UpdateCallLinkResult.CallLinkIsInUse
                  else -> UpdateCallLinkResult.Failure(result.status)
                }
              }
              emitAuthorized(emitter, generation, outcome, UpdateCallLinkResult.NotAuthorized)
            }
          }
        }
        if (!dispatched) emitter.onSuccess(UpdateCallLinkResult.NotAuthorized)
      }
      operation
    }
  }

  /**
   * Obtaining this facade or constructing a Single is passive. Credentials are requested on an
   * IO worker after subscription. Only the final operation enters the serialized runtime queue;
   * cancellation and the original Part 1 generation are checked before and after engine startup.
   */
  private fun <T : Any> authorizedLinkOperation(
    denied: T,
    allowColdStart: Boolean = true,
    prepare: (SingleEmitter<T>, Long) -> ((CallManager) -> Unit)?
  ): Single<T> {
    val generation = CallPermissions.generation()
    return Single.create { emitter ->
      val request = AtomicReference<CallRuntime.Request?>()
      emitter.setCancellable { request.get()?.cancel() }

      fun authorized(): Boolean = !emitter.isDisposed && CallPermissions.isCurrent(generation) && CallPermissions.canUseCallLinks()

      if (!authorized()) {
        emitter.onSuccess(denied)
        return@create
      }

      // Passive preview, details and sync refreshes keep their saved metadata while cold.
      // Check before credential retrieval, not merely before the eventual native read.
      if (!allowColdStart && CallRuntimePolicy.isOnDemand() && !signalCallManager.isEngineReady()) {
        emitter.onSuccess(denied)
        return@create
      }

      SignalExecutors.BOUNDED_IO.execute {
        if (!authorized()) {
          emitter.onSuccess(denied)
          return@execute
        }
        try {
          val operation = prepare(emitter, generation) ?: return@execute
          if (!authorized()) {
            emitter.onSuccess(denied)
            return@execute
          }
          val pending = signalCallManager.withCallEngine(
            allowColdStart,
            ::authorized,
            { manager ->
              try {
                operation(manager)
              } catch (e: Exception) {
                if (!emitter.isDisposed) emitter.tryOnError(e)
              }
            },
            { result -> if (result != CallRuntime.Result.DISPATCHED && !emitter.isDisposed) emitter.onSuccess(denied) }
          )
          request.set(pending)
          // Disposal can race with publication of the request handle.
          if (emitter.isDisposed) pending.cancel()
        } catch (e: Exception) {
          if (!emitter.isDisposed) emitter.tryOnError(e)
        }
      }
    }
  }

  private fun <T : Any> emitAuthorized(emitter: SingleEmitter<T>, generation: Long, result: T, denied: T) {
    // Recheck before forwarding results to state updates and sync scheduling.
    // A response from an operation revoked in flight is discarded.
    if (emitter.isDisposed) return
    if (!CallPermissions.runIfCurrent(generation) { if (!emitter.isDisposed) emitter.onSuccess(result) }) {
      emitter.onSuccess(denied)
    }
  }

  companion object {

    private val TAG = Log.tag(SignalCallLinkManager::class.java)

    private fun CallLinkState.toAppState(): SignalCallLinkState {
      return SignalCallLinkState(
        name = name,
        expiration = expiration,
        restrictions = restrictions,
        revoked = hasBeenRevoked()
      )
    }
  }
}

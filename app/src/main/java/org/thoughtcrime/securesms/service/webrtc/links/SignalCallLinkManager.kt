/**
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.service.webrtc.links

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.SingleEmitter
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
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.net.SignalNetwork
import org.thoughtcrime.securesms.recipients.Recipient
import java.io.IOException

/**
 * Call Link manager which encapsulates CallManager and provides a stable interface.
 *
 * We can remove the outer sealed class once we have the final, working builds from core.
 */
class SignalCallLinkManager(
  private val callManager: CallManager
) {

  private val genericServerPublicParams: GenericServerPublicParams = GenericServerPublicParams(
    AppDependencies.signalServiceNetworkAccess
      .getConfiguration()
      .genericServerPublicParams
  )

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
    return authorizedLinkOperation<CreateCallLinkResult>(denied) { emitter, generation ->
      Log.d(TAG, "Generating keys.")
      val rootKey = CallLinkRootKey(callLinkCredentials.linkKeyBytes)
      val adminPassKey: ByteArray = requireNotNull(callLinkCredentials.adminPassBytes)
      val roomId: ByteArray = rootKey.deriveRoomId()

      Log.d(TAG, "Generating credential.")
      val credentialPresentation = try {
        requestCreateCallLinkCredentialPresentation(rootKey.keyBytes, roomId)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to create call link credential.", e)
        emitter.onSuccess(CreateCallLinkResult.Failure(-1))
        return@authorizedLinkOperation
      }

      val publicParams = CallLinkSecretParams.deriveFromRootKey(rootKey.keyBytes).publicParams
      val dispatched = CallPermissions.runIfCurrent(generation) {
        callManager.createCallLink(
          SignalStore.internal.groupCallingServer,
          credentialPresentation.serialize(),
          rootKey,
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
      if (!dispatched) emitter.onSuccess(denied)
    }
  }

  fun readCallLink(
    credentials: CallLinkCredentials
  ): Single<ReadCallLinkResult> {
    val denied = ReadCallLinkResult.Failure(CallLinkPolicy.DENIED_STATUS)
    return authorizedLinkOperation<ReadCallLinkResult>(denied) { emitter, generation ->
      val presentation = requestCallLinkAuthCredentialPresentation(credentials.linkKeyBytes).serialize()
      val dispatched = CallPermissions.runIfCurrent(generation) {
        callManager.readCallLink(
          SignalStore.internal.groupCallingServer,
          presentation,
          CallLinkRootKey(credentials.linkKeyBytes)
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
      if (!dispatched) emitter.onSuccess(denied)
    }
  }

  fun updateCallLinkName(
    credentials: CallLinkCredentials,
    name: String
  ): Single<UpdateCallLinkResult> {
    if (credentials.adminPassBytes == null) return Single.just(UpdateCallLinkResult.NotAuthorized)

    return authorizedLinkOperation<UpdateCallLinkResult>(UpdateCallLinkResult.NotAuthorized) { emitter, generation ->
      val presentation = requestCallLinkAuthCredentialPresentation(credentials.linkKeyBytes)
      val dispatched = CallPermissions.runIfCurrent(generation) {
        callManager.updateCallLinkName(
          SignalStore.internal.groupCallingServer,
          presentation.serialize(),
          CallLinkRootKey(credentials.linkKeyBytes),
          credentials.adminPassBytes,
          name
        ) { result ->
          val outcome = if (result.isSuccess) UpdateCallLinkResult.Update(result.value!!.toAppState()) else UpdateCallLinkResult.Failure(result.status)
          emitAuthorized(emitter, generation, outcome, UpdateCallLinkResult.NotAuthorized)
        }
      }
      if (!dispatched) emitter.onSuccess(UpdateCallLinkResult.NotAuthorized)
    }
  }

  fun updateCallLinkRestrictions(
    credentials: CallLinkCredentials,
    restrictions: Restrictions
  ): Single<UpdateCallLinkResult> {
    if (credentials.adminPassBytes == null) return Single.just(UpdateCallLinkResult.NotAuthorized)

    return authorizedLinkOperation<UpdateCallLinkResult>(UpdateCallLinkResult.NotAuthorized) { emitter, generation ->
      val presentation = requestCallLinkAuthCredentialPresentation(credentials.linkKeyBytes)
      val dispatched = CallPermissions.runIfCurrent(generation) {
        callManager.updateCallLinkRestrictions(
          SignalStore.internal.groupCallingServer,
          presentation.serialize(),
          CallLinkRootKey(credentials.linkKeyBytes),
          credentials.adminPassBytes,
          restrictions
        ) { result ->
          val outcome = if (result.isSuccess) UpdateCallLinkResult.Update(result.value!!.toAppState()) else UpdateCallLinkResult.Failure(result.status)
          emitAuthorized(emitter, generation, outcome, UpdateCallLinkResult.NotAuthorized)
        }
      }
      if (!dispatched) emitter.onSuccess(UpdateCallLinkResult.NotAuthorized)
    }
  }

  fun deleteCallLink(
    credentials: CallLinkCredentials
  ): Single<UpdateCallLinkResult> {
    if (credentials.adminPassBytes == null) return Single.just(UpdateCallLinkResult.NotAuthorized)

    return authorizedLinkOperation<UpdateCallLinkResult>(UpdateCallLinkResult.NotAuthorized) { emitter, generation ->
      val presentation = requestCallLinkAuthCredentialPresentation(credentials.linkKeyBytes)
      val dispatched = CallPermissions.runIfCurrent(generation) {
        callManager.deleteCallLink(
          SignalStore.internal.groupCallingServer,
          presentation.serialize(),
          CallLinkRootKey(credentials.linkKeyBytes),
          credentials.adminPassBytes
        ) { result ->
          val outcome = if (result.isSuccess && result.value == true) {
            UpdateCallLinkResult.Delete(credentials.roomId)
          } else {
            when (result.status) {
              409.toShort() -> UpdateCallLinkResult.CallLinkIsInUse
              else -> UpdateCallLinkResult.Failure(result.status)
            }
          }
          emitAuthorized(emitter, generation, outcome, UpdateCallLinkResult.NotAuthorized)
        }
      }
      if (!dispatched) emitter.onSuccess(UpdateCallLinkResult.NotAuthorized)
    }
  }

  private fun <T : Any> authorizedLinkOperation(denied: T, operation: (SingleEmitter<T>, Long) -> Unit): Single<T> {
    val generation = CallPermissions.generation()
    return Single.create { emitter ->
      if (!CallPermissions.isCallingAllowed() || !CallPermissions.isCurrent(generation)) {
        emitter.onSuccess(denied)
      } else {
        operation(emitter, generation)
      }
    }
  }

  private fun <T : Any> emitAuthorized(emitter: SingleEmitter<T>, generation: Long, result: T, denied: T) {
    // Recheck before forwarding results to state updates and sync scheduling.
    // A response from an operation revoked in flight is discarded.
    if (!CallPermissions.runIfCurrent(generation) { emitter.onSuccess(result) }) {
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

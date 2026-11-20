/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.signalservice.api.donations

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import org.signal.core.util.Base64
import org.signal.core.util.urlEncode
import org.signal.libsignal.net.RequestResult
import org.signal.libsignal.zkgroup.receipts.ReceiptCredentialPresentation
import org.signal.libsignal.zkgroup.receipts.ReceiptCredentialRequest
import org.signal.libsignal.zkgroup.receipts.ReceiptCredentialResponse
import org.signal.network.NetworkResult
import org.signal.network.exceptions.MalformedResponseException
import org.signal.network.rest.RestStatusCodeError
import org.signal.network.websocket.WebSocketRequestMessage
import org.signal.network.websocket.delete
import org.signal.network.websocket.get
import org.signal.network.websocket.post
import org.signal.network.websocket.put
import org.whispersystems.signalservice.api.fromWebSocketRequest
import org.whispersystems.signalservice.api.subscriptions.ActiveSubscription
import org.whispersystems.signalservice.api.subscriptions.SubscriberId
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.internal.push.SubscriptionsConfiguration
import java.util.Locale

/**
 * One-stop shop for Signal service calls related to in-app payments.
 *
 * Be sure to check for cached versions of these methods in DonationsService before calling these methods elsewhere.
 */
class DonationsApi(private val authWebSocket: SignalWebSocket.AuthenticatedWebSocket, private val unauthWebSocket: SignalWebSocket.UnauthenticatedWebSocket) {

  /**
   * Get configuration data associated with donations, like gift, one-time, and monthly levels, etc.
   *
   * Note, this will skip cached values, causing us to hit the network more than necessary. Consider accessing this method via the DonationsService instead.
   *
   * GET /v1/subscription/configuration
   * - 200: Success
   */
  fun getDonationsConfiguration(locale: Locale): NetworkResult<SubscriptionsConfiguration> {
    val request = WebSocketRequestMessage.get("/v1/subscription/configuration", locale.toAcceptLanguageHeaders())
    return NetworkResult.fromWebSocketRequest(unauthWebSocket, request, SubscriptionsConfiguration::class)
  }

  /**
   * GET /v1/subscription/[subscriberId]
   * - 200: Success
   * - 403: Invalid or malformed [subscriberId]
   * - 404: [subscriberId] not found
   */
  fun getSubscription(subscriberId: SubscriberId): NetworkResult<ActiveSubscription> {
    val request = WebSocketRequestMessage.get("/v1/subscription/${subscriberId.serialize()}")
    return NetworkResult.fromWebSocketRequest(unauthWebSocket, request, ActiveSubscription::class)
  }

  /**
   * Refreshes an existing subscriber's access time (keep-alive). Does not attach a donation permit; use
   * [createSubscriber] when creating a new subscriber.
   *
   * PUT /v1/subscription/[subscriberId]
   * - 200: Success
   * - 403, 404: Invalid or malformed [subscriberId]
   */
  fun putSubscription(subscriberId: SubscriberId): NetworkResult<Unit> {
    val request = WebSocketRequestMessage.put("/v1/subscription/${subscriberId.serialize()}", body = "")
    return NetworkResult.fromWebSocketRequest(unauthWebSocket, request)
  }

  /**
   * Creates a subscriber record on the signal server, attaching a required single-use donation permit.
   *
   * PUT /v1/subscription/[subscriberId]
   * - 200: Success
   * - 403, 404: Invalid or malformed [subscriberId]
   */
  fun createSubscriber(subscriberId: SubscriberId, donationPermit: String): NetworkResult<Unit> {
    val request = WebSocketRequestMessage.put("/v1/subscription/${subscriberId.serialize()}", body = "", headers = donationPermitHeaders(donationPermit))
    return NetworkResult.fromWebSocketRequest(unauthWebSocket, request)
  }

  /**
   * DELETE /v1/subscription/[subscriberId]
   * - 204: Success
   * - 403: Invalid or malformed [subscriberId]
   * - 404: [subscriberId] not found
   */
  fun deleteSubscription(subscriberId: SubscriberId): NetworkResult<Unit> {
    val request = WebSocketRequestMessage.delete("/v1/subscription/${subscriberId.serialize()}")
    return NetworkResult.fromWebSocketRequest(unauthWebSocket, request)
  }

  /**
   * POST /v1/subscription/[subscriberId]/receipt_credentials
   */
  fun submitReceiptCredentials(subscriberId: SubscriberId, receiptCredentialRequest: ReceiptCredentialRequest): NetworkResult<ReceiptCredentialResponse> {
    val body = ReceiptCredentialRequestJson(receiptCredentialRequest)
    val request = WebSocketRequestMessage.post("/v1/subscription/${subscriberId.serialize()}/receipt_credentials", body)
    return NetworkResult.fromWebSocketRequest(unauthWebSocket, request, webSocketResponseConverter = NetworkResult.LongPollingWebSocketConverter(ReceiptCredentialResponseJson::class))
      .map { it.credentialResponse }
      .then {
        if (it != null) {
          NetworkResult.Success(it)
        } else {
          NetworkResult.NetworkError(MalformedResponseException("Unable to parse response"))
        }
      }
  }

  /**
   * POST /v1/subscription/[subscriberId]/playbilling/[purchaseToken]
   * - 200: Success
   */
  fun linkPlayBillingPurchaseToken(subscriberId: SubscriberId, purchaseToken: String): NetworkResult<Unit> {
    val request = WebSocketRequestMessage.post("/v1/subscription/${subscriberId.serialize()}/playbilling/${purchaseToken.urlEncode()}", "")
    return NetworkResult.fromWebSocketRequest(unauthWebSocket, request)
  }

  /**
   * Allows a user to redeem a given receipt they were given after submitting a donation successfully.
   *
   * POST /v1/donation/redeem-receipt
   * - 200: Success
   *
   * @param receiptCredentialPresentation Receipt
   * @param visible                       Whether the badge will be visible on the user's profile immediately after redemption
   * @param primary                       Whether the badge will be made primary immediately after redemption
   */
  fun redeemDonationReceipt(receiptCredentialPresentation: ReceiptCredentialPresentation, visible: Boolean, primary: Boolean): NetworkResult<Unit> {
    val body = RedeemDonationReceiptRequest(Base64.encodeWithPadding(receiptCredentialPresentation.serialize()), visible, primary)
    val request = WebSocketRequestMessage.post("/v1/donation/redeem-receipt", body)
    return NetworkResult.fromWebSocketRequest(authWebSocket, request)
  }

  /**
   * Allows a user to redeem a given receipt they were given after submitting a donation successfully.
   *
   * POST /v1/archives/redeem-receipt
   * - 200: Success
   *
   * @param receiptCredentialPresentation Receipt
   */
  fun redeemArchivesReceipt(receiptCredentialPresentation: ReceiptCredentialPresentation): NetworkResult<Unit> {
    val body = RedeemArchivesReceiptRequest(Base64.encodeWithPadding(receiptCredentialPresentation.serialize()))
    val request = WebSocketRequestMessage.post("/v1/archives/redeem-receipt", body)
    return NetworkResult.fromWebSocketRequest(authWebSocket, request)
  }

  /**
   * Exchanges a blinded donation-permit request for the issuing server's response, returning the serialized
   * libsignal `DonationPermitResponse` bytes.
   *
   * POST /v1/donation/permit
   * - 200: Success
   * - 429: Rate limited by the per-account request frequency limit.
   */
  fun createDonationPermits(permitRequest: ByteArray): RequestResult<ByteArray, RestStatusCodeError> {
    val request = WebSocketRequestMessage.post("/v1/donation/permit", DonationPermitRequestBody(permitRequest))
    return authWebSocket.fromWebSocketRequest(request, DonationPermitResponseJson::class)
      .map { it.permitResponse }
  }

  private fun Locale.toAcceptLanguageHeaders(): Map<String, String> {
    return mapOf("Accept-Language" to "${this.language}${if (this.country.isNotEmpty()) "-${this.country}" else ""}")
  }

  /** The base64 donation permit as the `Donation-Permit` header. */
  private fun donationPermitHeaders(donationPermit: String): Map<String, String> {
    return mapOf("Donation-Permit" to donationPermit)
  }
}

/** Request body for `POST /v1/donation/permit`: the base64-encoded, serialized libsignal `DonationPermitRequest`. */
private class DonationPermitRequestBody(permitRequest: ByteArray) {
  @JsonProperty("permitRequest")
  val permitRequest: String = Base64.encodeWithPadding(permitRequest)
}

/** Response body for `POST /v1/donation/permit`: the base64-encoded, serialized libsignal `DonationPermitResponse`. */
private class DonationPermitResponseJson @JsonCreator constructor(
  @JsonProperty("permitResponse") encoded: String
) {
  val permitResponse: ByteArray = Base64.decode(encoded)
}

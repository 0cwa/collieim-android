/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.billing

/**
 * The few fields of a Play one-time purchase offer that we care about, decoupled from the billing library's
 * final [com.android.billingclient.api.ProductDetails] types so selection can be unit tested.
 */
internal data class OfferCandidate(
  val purchaseOptionId: String?,
  val offerToken: String?,
  val formattedPrice: String?
)

internal object OfferSelection {

  /**
   * Picks the offer to buy.
   *
   * Modern Play (and Play-compatible stores) report every purchase option in `oneTimePurchaseOfferDetailsList`, each
   * with a purchase option id, and we only ever buy the option named by [wantedOptionId].
   *
   * Some Play-compatible implementations, such as microG, only report the single legacy `oneTimePurchaseOfferDetails`
   * block, which has no purchase option id (depending on the billing library version it may also surface as a
   * one-entry list whose id is null). When no entry carries an option id there is exactly one offer to choose from, so
   * that one is used. An empty list is authoritative and means no offers. If any entry carries an option id but none matches [wantedOptionId], the option really is
   * unavailable and this returns null rather than guessing.
   */
  fun select(list: List<OfferCandidate>?, legacy: OfferCandidate?, wantedOptionId: String): OfferCandidate? {
    // An empty list is Play explicitly saying there are no offers, so it is authoritative just like a list with ids.
    val listIsAuthoritative = list != null && (list.isEmpty() || list.any { it.purchaseOptionId != null })

    val candidate = if (listIsAuthoritative) {
      list?.firstOrNull { it.purchaseOptionId == wantedOptionId }
    } else {
      legacy ?: list?.singleOrNull()
    }

    return candidate?.takeIf { it.offerToken != null && it.formattedPrice != null }
  }
}

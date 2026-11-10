package org.signal.billing

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test

class OfferSelectionTest {

  private val nonumber = OfferCandidate("nonumber", "token-nonumber", "$2.99")
  private val other = OfferCandidate("other", "token-other", "$9.99")
  private val legacy = OfferCandidate(null, "token-legacy", "$2.99")

  @Test
  fun `list containing the wanted option picks it`() {
    assertThat(OfferSelection.select(listOf(other, nonumber), legacy, "nonumber")).isEqualTo(nonumber)
  }

  @Test
  fun `list without the wanted option is unavailable, even if a legacy block exists`() {
    assertThat(OfferSelection.select(listOf(other), legacy, "nonumber")).isNull()
  }

  @Test
  fun `empty list is unavailable`() {
    assertThat(OfferSelection.select(emptyList(), legacy, "nonumber")).isNull()
  }

  @Test
  fun `absent list falls back to the legacy block`() {
    assertThat(OfferSelection.select(null, legacy, "nonumber")).isEqualTo(legacy)
  }

  @Test
  fun `one-entry list with no option id is treated as the legacy offer`() {
    assertThat(OfferSelection.select(listOf(legacy), null, "nonumber")).isEqualTo(legacy)
  }

  @Test
  fun `multi-entry list with no option ids is ambiguous and unavailable`() {
    assertThat(OfferSelection.select(listOf(legacy, legacy.copy(offerToken = "t2")), null, "nonumber")).isNull()
  }

  @Test
  fun `absent list and absent legacy block is unavailable`() {
    assertThat(OfferSelection.select(null, null, "nonumber")).isNull()
  }

  @Test
  fun `candidate missing a token or price is unavailable`() {
    assertThat(OfferSelection.select(listOf(nonumber.copy(offerToken = null)), null, "nonumber")).isNull()
    assertThat(OfferSelection.select(null, legacy.copy(formattedPrice = null), "nonumber")).isNull()
  }
}

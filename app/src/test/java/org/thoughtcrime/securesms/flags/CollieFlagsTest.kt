/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.flags

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.Test
import org.thoughtcrime.securesms.BuildConfig

class CollieFlagsTest {
  @Test
  fun `settings and project log are available in both editions`() {
    assertThat(CollieFlags.appUpdatesInCollieSettings).isTrue()
    assertThat(CollieFlags.setupChecklist).isTrue()
    assertThat(CollieFlags.projectLog).isTrue()
    assertThat(CollieFlags.hardeningSettings).isTrue()
  }

  @Test
  fun `group admission and attachment removal remain Beta-only`() {
    assertThat(CollieFlags.groupAdmission).isEqualTo(BuildConfig.TRAILBLAZER_BETA)
    assertThat(CollieFlags.attachmentRemoval).isEqualTo(BuildConfig.TRAILBLAZER_BETA)
  }
}

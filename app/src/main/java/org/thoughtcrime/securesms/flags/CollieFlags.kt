/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.flags

import org.thoughtcrime.securesms.BuildConfig

/** Build-variant flags for Collie-only features. */
object CollieFlags {
  /** Enables the Material You propagation prepared for TrailBlazer Beta. */
  val userTheming: Boolean
    get() = BuildConfig.TRAILBLAZER_BETA

  val previewIndependentSharedLinks: Boolean
    get() = BuildConfig.TRAILBLAZER_BETA

  val appUpdatesInCollieSettings: Boolean
    get() = true

  val setupChecklist: Boolean
    get() = true

  val projectLog: Boolean
    get() = true

  /** Shows group admission controls in TrailBlazer Beta; saved restrictive policies remain enforced. */
  val groupAdmission: Boolean
    get() = BuildConfig.TRAILBLAZER_BETA

  val hardeningSettings: Boolean
    get() = true

  val attachmentRemoval: Boolean
    get() = BuildConfig.TRAILBLAZER_BETA
}

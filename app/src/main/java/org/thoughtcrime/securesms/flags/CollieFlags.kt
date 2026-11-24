/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.flags

import org.thoughtcrime.securesms.BuildConfig

/** Build-variant flags for Collie-only features. */
object CollieFlags {
  val appUpdatesInCollieSettings: Boolean
    get() = BuildConfig.TRAILBLAZER_BETA
}

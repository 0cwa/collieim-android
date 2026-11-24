/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.calls.permissions

import org.junit.runner.RunWith
import org.junit.runners.Suite

/** One runner selector keeps the classes intact through AGP's comma-delimited argument map. */
@RunWith(Suite::class)
@Suite.SuiteClasses(
  CallPermissionsScreenshotTest::class,
  CallLinkKeyNativeParityTest::class,
  CallEraIdNativeParityTest::class,
  ColdCallRuntimeTest::class
)
class CallPermissionsApi35Suite

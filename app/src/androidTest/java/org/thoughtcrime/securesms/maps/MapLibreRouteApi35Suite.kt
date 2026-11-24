/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.maps

import org.junit.runner.RunWith
import org.junit.runners.Suite

/** One selector keeps both classes intact through AGP's instrumentation argument map. */
@RunWith(Suite::class)
@Suite.SuiteClasses(MapLibreOfflinePickerProofTest::class, MapLibreProxyRouteProofTest::class)
class MapLibreRouteApi35Suite

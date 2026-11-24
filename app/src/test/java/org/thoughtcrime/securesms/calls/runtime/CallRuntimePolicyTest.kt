/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.calls.runtime

import android.app.Application
import io.mockk.every
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.keyvalue.CallPermissionValues
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor

/** Uses the real persisted startup values, production policy and managed runtime, with an inert engine. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class CallRuntimePolicyTest {
  @get:Rule
  val store = MockSignalStoreRule()

  @Before
  fun setUp() {
    every { store.account.aci } returns ServiceId.ACI.from(UUID.fromString("10000000-0000-0000-0000-000000000001"))
    every { store.account.isRegistered } returns true
    every { store.account.isUnauthorizedReceived } returns false
    CallPermissions.setRevocationListener(Runnable {})
    CallPermissions.endAllSessions()
  }

  @After
  fun tearDown() {
    CallPermissions.setRevocationListener(Runnable {})
    CallPermissions.endAllSessions()
  }

  @Test
  fun `default and explicitly off startup preserve eager initialization`() {
    assertFalse(CallRuntimePolicy.isOnDemand())
    assertTrue(CallRuntimePolicy.shouldInitializeAtStartup())
    assertEquals(CallRuntime.Mode.START_IF_AUTHORIZED, CallRuntimePolicy.mode(false))

    store.callPermissions.setOnDemandCallingEnabled(true)
    store.callPermissions.setOnDemandCallingEnabled(false)

    assertFalse(CallRuntimePolicy.isOnDemand())
    assertTrue(CallRuntimePolicy.shouldInitializeAtStartup())
    assertEquals(CallRuntime.Mode.START_IF_AUTHORIZED, CallRuntimePolicy.mode(false))
  }

  @Test
  fun `on demand opt in defers startup and cold background work`() {
    store.callPermissions.setOnDemandCallingEnabled(true)

    assertTrue(CallRuntimePolicy.isOnDemand())
    assertFalse(CallRuntimePolicy.shouldInitializeAtStartup())
    assertColdOperation(CallRuntimePolicy.mode(false), CallRuntime.Result.DEFERRED)
    assertEquals(CallRuntime.Mode.START_IF_AUTHORIZED, CallRuntimePolicy.mode(true))
  }

  @Test
  fun `absolute disable overrides either startup preference and explicit operation mode`() {
    CallPermissions.setAllCallingDisabled(true)

    for (onDemand in listOf(false, true)) {
      store.callPermissions.setOnDemandCallingEnabled(onDemand)

      assertFalse(CallRuntimePolicy.shouldInitializeAtStartup())
      assertColdOperation(CallRuntimePolicy.mode(false), CallRuntime.Result.DENIED)
      assertColdOperation(CallRuntimePolicy.mode(true), CallRuntime.Result.DENIED)
    }
  }

  @Test
  fun `wrong type startup preference defers rather than initializing in the background`() {
    store.callPermissions.store.beginWrite().putString(CallPermissionValues.ON_DEMAND, "invalid").commitAndWaitForPersistence()

    assertTrue(CallPermissions.settings().available)
    assertTrue(CallRuntimePolicy.isOnDemand())
    assertFalse(CallRuntimePolicy.shouldInitializeAtStartup())
    assertColdOperation(CallRuntimePolicy.mode(false), CallRuntime.Result.DEFERRED)
  }

  @Test
  fun `unavailable calling policy prevents startup with the ordinary preference`() {
    store.callPermissions.store.beginWrite().putString(CallPermissionValues.RECORD, "99\nunsupported").commitAndWaitForPersistence()

    assertFalse(CallRuntimePolicy.isOnDemand())
    assertFalse(CallPermissions.settings().available)
    assertFalse(CallRuntimePolicy.shouldInitializeAtStartup())
    assertColdOperation(CallRuntimePolicy.mode(false), CallRuntime.Result.DENIED)
  }

  @Test
  fun `fresh unregistered installation preserves ordinary eager startup without authorizing a call`() {
    every { store.account.aci } returns null
    every { store.account.isRegistered } returns false

    assertTrue(CallPermissions.settings().available)
    assertFalse(CallRuntimePolicy.isOnDemand())
    assertTrue(CallRuntimePolicy.shouldInitializeAtStartup())
    assertFalse(CallPermissions.isCallingAllowed())
    assertColdOperation(CallRuntimePolicy.mode(true), CallRuntime.Result.DENIED)
  }

  @Test
  fun `public runtime cannot initialize from an always true predicate while policy denies or is unavailable`() {
    var initializations = 0
    val results = mutableListOf<CallRuntime.Result>()
    val runtime = CallPermissionRuntime(SerializedCallRuntime(Executor { it.run() }, { initializations++ }, { fail(it.toString()) }))
    CallPermissions.setAllCallingDisabled(true)

    runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, { true }, { fail("Absolute prohibition must win") }, results::add)

    CallPermissions.setAllCallingDisabled(false)
    store.callPermissions.store.beginWrite().putString(CallPermissionValues.RECORD, "99\nunsupported").commitAndWaitForPersistence()
    runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, { true }, { fail("Unavailable policy must not become permission") }, results::add)

    assertEquals(listOf(CallRuntime.Result.DENIED, CallRuntime.Result.DENIED), results)
    assertEquals(0, initializations)
    assertEquals(CallRuntime.State.COLD, runtime.state)
  }

  @Test
  fun `public runtime keeps queued work in its original generation across revoke and reenable`() {
    var initializations = 0
    var actions = 0
    val pending = ArrayDeque<Runnable>()
    val results = mutableListOf<CallRuntime.Result>()
    val runtime = CallPermissionRuntime(SerializedCallRuntime(Executor { pending.add(it) }, { initializations++ }, { fail(it.toString()) }))

    runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, { true }, { actions++ }, results::add)
    CallPermissions.setAllCallingDisabled(true)
    CallPermissions.setAllCallingDisabled(false)
    pending.removeFirst().run()

    assertEquals(listOf(CallRuntime.Result.DENIED), results)
    assertEquals(0, initializations)
    assertEquals(0, actions)

    runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, { true }, { actions++ }, results::add)
    pending.removeFirst().run()

    assertEquals(listOf(CallRuntime.Result.DENIED, CallRuntime.Result.DISPATCHED), results)
    assertEquals(1, initializations)
    assertEquals(1, actions)
  }

  @Test
  fun `public runtime reports denial when permission changes during its final dispatch predicate`() {
    var initializations = 0
    var checks = 0
    var actions = 0
    val results = mutableListOf<CallRuntime.Result>()
    val runtime = CallPermissionRuntime(SerializedCallRuntime(Executor { it.run() }, { initializations++ }, { fail(it.toString()) }))

    runtime.execute(
      CallRuntime.Mode.START_IF_AUTHORIZED,
      {
        // The first two checks belong to the backend. Interleave revocation while the
        // wrapper's final caller predicate is running, as a blocking predicate could allow.
        if (++checks == 3) CallPermissions.setAllCallingDisabled(true)
        true
      },
      { actions++ },
      results::add
    )

    assertEquals(listOf(CallRuntime.Result.DENIED), results)
    assertEquals(1, initializations)
    assertEquals(0, actions)
    assertEquals(CallRuntime.State.READY, runtime.state)
  }

  private fun assertColdOperation(mode: CallRuntime.Mode, expected: CallRuntime.Result) {
    var initializations = 0
    val results = mutableListOf<CallRuntime.Result>()
    val runtime = SerializedCallRuntime(Executor { it.run() }, { initializations++ }, { fail(it.toString()) })

    runtime.execute(mode, CallPermissions::isCallingAllowed, { fail("A deferred or denied operation must not dispatch") }, results::add)

    assertEquals(listOf(expected), results)
    assertEquals(0, initializations)
    assertEquals(CallRuntime.State.COLD, runtime.state)
  }
}

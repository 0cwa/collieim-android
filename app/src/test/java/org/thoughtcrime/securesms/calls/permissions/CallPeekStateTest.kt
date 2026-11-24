package org.thoughtcrime.securesms.calls.permissions

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.reactivex.rxjava3.processors.BehaviorProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.signal.core.models.recipients.RecipientId
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.service.webrtc.CallLinkPeekInfo
import org.thoughtcrime.securesms.service.webrtc.SignalCallManager

class CallPeekStateTest {
  private val changes = MutableStateFlow(1L)
  private var enabled = false
  private val manager = mockk<SignalCallManager>()
  private val peeks = BehaviorProcessor.createDefault<Map<RecipientId, CallLinkPeekInfo>>(emptyMap())

  @Before
  fun setUp() {
    mockkObject(CallPermissions)
    mockkStatic(CallPermissions::class, AppDependencies::class)
    every { CallPermissions.changes } returns changes
    every { CallPermissions.generation() } answers { changes.value }
    every { CallPermissions.isCurrent(any()) } answers { enabled && firstArg<Long>() == changes.value }
    every { CallPermissions.runIfCurrent(any(), any()) } answers {
      if (enabled && firstArg<Long>() == changes.value) {
        secondArg<Runnable>().run()
        true
      } else {
        false
      }
    }
    every { AppDependencies.signalCallManager } returns manager
    every { manager.peekInfoCache } returns peeks
    every { manager.peekInfoSnapshot } returns peeks.value!!
  }

  @After
  fun tearDown() = unmockkAll()

  @Test
  fun `disabled cached state never obtains the native manager`() {
    assertTrue(CallPeekState.snapshot().isEmpty())
    val observer = CallPeekState.observe().test()
    observer.assertValue(emptyMap())
    verify(exactly = 0) { AppDependencies.signalCallManager }
    observer.dispose()
  }

  @Test
  fun `reenabling resumes cached state and revocation clears it without stale callback updates`() {
    val active = mapOf(RecipientId.from(1) to CallLinkPeekInfo(null, true, false))
    peeks.onNext(active)
    val observer = CallPeekState.observe().test()
    observer.assertValue(emptyMap())

    enabled = true
    changes.value = 2
    observer.assertValues(emptyMap(), active)

    enabled = false
    changes.value = 3
    peeks.onNext(mapOf(RecipientId.from(2) to CallLinkPeekInfo(null, true, false)))
    observer.assertValues(emptyMap(), active, emptyMap())
    verify(exactly = 1) { AppDependencies.signalCallManager }
    observer.dispose()
  }
}

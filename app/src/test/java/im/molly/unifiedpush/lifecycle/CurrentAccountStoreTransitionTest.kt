package im.molly.unifiedpush.lifecycle

import android.app.Application
import io.mockk.every
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.testutil.DirectExecutor
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class CurrentAccountStoreTransitionTest {
  @get:Rule val store = MockSignalStoreRule()
  @get:Rule val dependencies = MockAppDependenciesRule()
  private fun values() = UnifiedPushValues(KeyValueStore(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet()), DirectExecutor()))

  @Before fun setup() = UnifiedPushLifecycle.resetForTests()
  @After fun cleanup() = UnifiedPushLifecycle.resetForTests()

  @Test fun `replacement store marker cannot be acknowledged by previous store owner`() {
    val original = values()
    val replacement = values()
    replacement.setIncompleteAccountTransition(true)
    var current = original
    every { SignalStore.unifiedpush } answers { current }
    assertThrows(IllegalStateException::class.java) {
      runBlocking {
        UnifiedPushAccountTransition.withTransition(readyAfter = { true }) {
          assertTrue(original.hasIncompleteAccountTransition)
          current = replacement
        }
      }
    }
    assertTrue(original.hasIncompleteAccountTransition)
    assertTrue(replacement.hasIncompleteAccountTransition)
    assertFalse(UnifiedPushLifecycle.isReady())
  }

  @Test fun `link restore store replacement keeps both stores closed`() {
    val original = values()
    val replacement = values()
    var current = original
    every { SignalStore.unifiedpush } answers { current }
    assertThrows(IllegalStateException::class.java) {
      runBlocking {
        UnifiedPushAccountTransition.withTransition(linkRestore = true, readyAfter = { true }) {
          assertTrue(original.hasIncompleteLinkRestore)
          current = replacement
        }
      }
    }
    assertTrue(original.hasIncompleteAccountTransition)
    assertTrue(original.hasIncompleteLinkRestore)
    assertTrue(replacement.hasIncompleteAccountTransition)
    assertTrue(replacement.hasIncompleteLinkRestore)
    assertFalse(UnifiedPushLifecycle.isReady())
  }


  @Test fun `replacement after retained value closes the new store`() {
    val original = values()
    val replacement = values()
    var current = original
    every { SignalStore.unifiedpush } answers { current }
    assertThrows(IllegalStateException::class.java) {
      runBlocking {
        UnifiedPushAccountTransition.withTransition(linkRestore = true, retainMarkerWhenNotReady = true, readyAfter = { false }) {
          current = replacement
          Unit
        }
      }
    }
    assertTrue(original.hasIncompleteLinkRestore)
    assertTrue(replacement.hasIncompleteAccountTransition)
    assertTrue(replacement.hasIncompleteLinkRestore)
    assertFalse(UnifiedPushLifecycle.isReady())
  }

  @Test fun `replacement after operation exception preserves failure and closes new store`() {
    val original = values()
    val replacement = values()
    var current = original
    every { SignalStore.unifiedpush } answers { current }
    val failure = assertThrows(IllegalArgumentException::class.java) {
      runBlocking {
        UnifiedPushAccountTransition.withTransition(linkRestore = true, readyAfter = { true }) {
          current = replacement
          throw IllegalArgumentException("synthetic")
        }
      }
    }
    assertTrue(failure.suppressed.isNotEmpty())
    assertTrue(replacement.hasIncompleteAccountTransition)
    assertTrue(replacement.hasIncompleteLinkRestore)
    assertFalse(UnifiedPushLifecycle.isReady())
  }

  @Test fun `replacement after cancellation preserves cancellation and closes new store`() {
    val original = values()
    val replacement = values()
    var current = original
    every { SignalStore.unifiedpush } answers { current }
    assertThrows(java.util.concurrent.CancellationException::class.java) {
      runBlocking {
        UnifiedPushAccountTransition.withTransition(linkRestore = true, readyAfter = { true }) {
          current = replacement
          throw java.util.concurrent.CancellationException("synthetic")
        }
      }
    }
    assertTrue(replacement.hasIncompleteAccountTransition)
    assertTrue(replacement.hasIncompleteLinkRestore)
    assertFalse(UnifiedPushLifecycle.isReady())
  }

  @Test fun `replacement during initial marker write is closed before failed acquisition releases writer`() {
    val original = io.mockk.mockk<UnifiedPushValues>()
    val replacement = values()
    var current = original
    every { SignalStore.unifiedpush } answers { current }
    every { original.hasIncompleteAccountTransition } returns false
    every { original.hasIncompleteLinkRestore } returns false
    every { original.setLinkRestoreTransition(true) } answers {
      current = replacement
      throw IllegalStateException("synthetic persistence failure")
    }
    assertThrows(IllegalStateException::class.java) {
      runBlocking { UnifiedPushAccountTransition.withTransition(linkRestore = true, readyAfter = { true }) { Unit } }
    }
    assertTrue(replacement.hasIncompleteAccountTransition)
    assertTrue(replacement.hasIncompleteLinkRestore)
    assertFalse(UnifiedPushLifecycle.isReady())
  }


  @Test fun `replacement during canceled drain closes the new store`() {
    val original = values()
    val observed = io.mockk.spyk(original)
    val replacement = values()
    val current = java.util.concurrent.atomic.AtomicReference<UnifiedPushValues>(observed)
    val markerWritten = java.util.concurrent.CountDownLatch(1)
    val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    every { SignalStore.unifiedpush } answers { current.get() }
    every { observed.setLinkRestoreTransition(true) } answers {
      original.setLinkRestoreTransition(true)
      markerWritten.countDown()
    }
    val lease = checkNotNull(UnifiedPushLifecycle.acquireLease { "synthetic-account" })
    val worker = kotlin.concurrent.thread {
      try {
        UnifiedPushAccountTransition.withTransitionBlocking(linkRestore = true, readyAfter = { true }) { Unit }
      } catch (caught: Throwable) { failure.set(caught) }
    }
    try {
      assertTrue(markerWritten.await(5, java.util.concurrent.TimeUnit.SECONDS))
      current.set(replacement)
      worker.interrupt()
    } finally {
      lease.close()
      worker.join(5000)
    }
    assertFalse(worker.isAlive)
    assertTrue(failure.get() is InterruptedException || failure.get() is java.util.concurrent.CancellationException)
    assertTrue(replacement.hasIncompleteAccountTransition)
    assertTrue(replacement.hasIncompleteLinkRestore)
    assertFalse(UnifiedPushLifecycle.isReady())
  }


  @Test fun `replacement immediately before success guard never publishes readiness`() {
    val original = values()
    val replacement = values()
    var current = original
    var reads = 0
    every { SignalStore.unifiedpush } answers {
      reads++
      if (reads == 3) { current = replacement; original } else current
    }
    assertThrows(IllegalStateException::class.java) {
      runBlocking { UnifiedPushAccountTransition.withTransition(linkRestore = true, readyAfter = { true }) { Unit } }
    }
    assertTrue(replacement.hasIncompleteAccountTransition)
    assertTrue(replacement.hasIncompleteLinkRestore)
    assertFalse(UnifiedPushLifecycle.isReady())
    assertTrue(UnifiedPushLifecycle.acquireLease { "account" } == null)
  }

  @Test fun `replacement persistence completes before readiness can be published`() {
    val original = values()
    val replacementBacking = values()
    val replacement = io.mockk.spyk(replacementBacking)
    val persisting = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    var current: UnifiedPushValues = original
    var reads = 0
    every { SignalStore.unifiedpush } answers {
      reads++
      if (reads == 3) { current = replacement; original } else current
    }
    every { replacement.setLinkRestoreTransition(true) } answers {
      persisting.countDown()
      check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
      replacementBacking.setLinkRestoreTransition(true)
    }
    val worker = kotlin.concurrent.thread {
      try {
        UnifiedPushAccountTransition.withTransitionBlocking(linkRestore = true, readyAfter = { true }) { Unit }
      } catch (caught: Throwable) { failure.set(caught) }
    }
    try {
      assertTrue(persisting.await(5, java.util.concurrent.TimeUnit.SECONDS))
      assertFalse(UnifiedPushLifecycle.isReady())
      assertTrue(UnifiedPushLifecycle.acquireLease { "account" } == null)
    } finally { release.countDown(); worker.join(5000) }
    assertFalse(worker.isAlive)
    assertTrue(failure.get() is IllegalStateException)
    assertTrue(replacementBacking.hasIncompleteAccountTransition)
    assertTrue(replacementBacking.hasIncompleteLinkRestore)
    assertFalse(UnifiedPushLifecycle.isReady())
    runBlocking { UnifiedPushLifecycle.withTransition(persistIncompleteMarker = {}, readyAfter = { true }) { Unit } }
    assertTrue(UnifiedPushLifecycle.isReady())
  }

  @Test fun `marker persistence failure remains primary when notification also fails`() {
    val persistence = IllegalStateException("persistence")
    val notification = IllegalArgumentException("notification")
    val failure = assertThrows(IllegalStateException::class.java) {
      runBlocking {
        UnifiedPushLifecycle.withTransitionAndGeneration(
          persistIncompleteMarker = { throw persistence },
          readyAfter = { true },
          afterTransitionStarted = { throw notification }
        ) { Unit }
      }
    }
    assertTrue(failure.message == "persistence")
    assertTrue(failure.suppressed.any { it.message == "notification" } || failure.cause?.suppressed?.any { it.message == "notification" } == true)
    assertFalse(UnifiedPushLifecycle.isReady())
    // Failed admission released the writer; a later controlled transition can complete.
    runBlocking { UnifiedPushLifecycle.withTransition(persistIncompleteMarker = {}, readyAfter = { true }) { Unit } }
    assertTrue(UnifiedPushLifecycle.isReady())
  }


  @Test fun `failed replacement persistence preserves operation error and stays closed`() {
    val original = values()
    val replacement = io.mockk.mockk<UnifiedPushValues>()
    var current = original
    every { SignalStore.unifiedpush } answers { current }
    every { replacement.setLinkRestoreTransition(true) } throws IllegalStateException("replacement persistence")
    val failure = assertThrows(IllegalArgumentException::class.java) {
      runBlocking {
        UnifiedPushAccountTransition.withTransition(linkRestore = true, readyAfter = { true }) {
          current = replacement
          throw IllegalArgumentException("operation")
        }
      }
    }
    assertTrue(failure.message == "operation")
    assertTrue(failure.suppressed.any { it.message == "replacement persistence" } || failure.cause?.suppressed?.any { it.message == "replacement persistence" } == true)
    assertFalse(UnifiedPushLifecycle.isReady())
    assertTrue(UnifiedPushLifecycle.acquireLease { "account" } == null)
  }

}

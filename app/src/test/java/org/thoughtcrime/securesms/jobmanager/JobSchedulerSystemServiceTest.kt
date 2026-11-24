package org.thoughtcrime.securesms.jobmanager

import android.app.Application
import android.app.job.JobParameters
import android.os.PersistableBundle
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.util.function.Consumer

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class JobSchedulerSystemServiceTest {

  private val manager = mockk<JobManager>(relaxed = true)
  private val listeners = mutableListOf<JobManager.EmptyQueueListener>()
  private val completions = mutableListOf<Consumer<Boolean>>()
  private lateinit var service: JobSchedulerScheduler.SystemService

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    mockkObject(AppDependencies)
    mockkStatic(AppDependencies::class, KeyCachingService::class)
    every { AppDependencies.jobManager } returns manager
    every { KeyCachingService.isLocked() } returns false
    every { manager.addOnEmptyQueueListener(any()) } answers { listeners.add(firstArg()); Unit }
    every { manager.onSystemJobFinished(any(), any()) } answers { completions.add(secondArg()); Unit }
    // Exercise the service's listener lifecycle without starting Android jobs or a real job loop.
    service = spyk(JobSchedulerScheduler.SystemService())
    every { service.jobFinished(any(), any()) } returns Unit
  }

  @After
  fun tearDown() {
    unmockkStatic(AppDependencies::class, KeyCachingService::class)
    unmockkObject(AppDependencies)
  }

  @Test
  fun `active queue drain finishes once only after rearming reports its result`() {
    for ((index, retry) in listOf(false, true).withIndex()) {
      val parameters = parameters(id = index, generation = "generation-$index")
      assertTrue(service.onStartJob(parameters))
      val listener = listeners.last()

      listener.onQueueEmpty()
      listener.onQueueEmpty()

      verify(exactly = 1) { manager.removeOnEmptyQueueListener(listener) }
      verify(exactly = 1) { manager.onSystemJobFinished(parameters, any()) }
      verify(exactly = 0) { service.jobFinished(parameters, any()) }
      assertEquals(index + 1, completions.size)

      completions.last().accept(retry)

      verify(exactly = 1) { service.jobFinished(parameters, retry) }
    }
    verify(exactly = 2) { manager.wakeUp() }
  }

  @Test
  fun `stop during terminal dispatch returns the rearm result and keeps the generation fenced`() {
    val parameters = parameters(0, "finishing")
    assertTrue(service.onStartJob(parameters))
    listeners.single().onQueueEmpty()

    // The owner remains available while rearming is in flight; before the result, do not ask
    // Android to retry a generation that is already being consumed.
    assertFalse(service.onStopJob(parameters))
    completions.single().accept(true)

    assertTrue(service.onStopJob(parameters))
    verify(exactly = 1) { service.jobFinished(parameters, true) }
    verify(exactly = 1) { manager.onSystemJobFinished(parameters, any()) }
  }

  @Test
  fun `stopping a wakeup removes its listener requests retry and ignores a late drain`() {
    val parameters = parameters(0, "stopped")
    assertTrue(service.onStartJob(parameters))
    val stopped = listeners.single()

    assertTrue(service.onStopJob(parameters))
    stopped.onQueueEmpty()

    verify(exactly = 1) { manager.removeOnEmptyQueueListener(stopped) }
    verify(exactly = 0) { manager.onSystemJobFinished(any(), any()) }
    verify(exactly = 0) { service.jobFinished(any(), any()) }
  }

  @Test
  fun `old stop and drain callbacks cannot finish or remove a replacement wakeup`() {
    val oldParameters = parameters(0, "old")
    val newParameters = parameters(0, "new")
    assertTrue(service.onStartJob(oldParameters))
    val oldListener = listeners.last()
    assertTrue(service.onStartJob(newParameters))
    val newListener = listeners.last()
    verify(exactly = 1) { manager.removeOnEmptyQueueListener(oldListener) }

    assertTrue(service.onStopJob(oldParameters))
    oldListener.onQueueEmpty()

    verify(exactly = 0) { manager.removeOnEmptyQueueListener(newListener) }
    verify(exactly = 0) { manager.onSystemJobFinished(any(), any()) }
    verify(exactly = 0) { service.jobFinished(any(), any()) }

    newListener.onQueueEmpty()
    completions.single().accept(false)

    verify(exactly = 1) { manager.onSystemJobFinished(newParameters, any()) }
    verify(exactly = 1) { service.jobFinished(newParameters, false) }
    verify(exactly = 0) { service.jobFinished(oldParameters, any()) }
  }

  @Test
  fun `locked app does not acquire the job manager or register a listener`() {
    every { KeyCachingService.isLocked() } returns true

    assertFalse(service.onStartJob(parameters(0, "locked")))

    assertTrue(listeners.isEmpty())
    verify(exactly = 0) { AppDependencies.jobManager }
    verify(exactly = 0) { manager.wakeUp() }
    verify(exactly = 0) { service.jobFinished(any(), any()) }
  }

  private fun parameters(id: Int, generation: String): JobParameters {
    val extras = PersistableBundle().apply { putString(JobSchedulerScheduler.WAKEUP_GENERATION, generation) }
    return mockk {
      every { jobId } returns id
      every { this@mockk.extras } returns extras
    }
  }
}

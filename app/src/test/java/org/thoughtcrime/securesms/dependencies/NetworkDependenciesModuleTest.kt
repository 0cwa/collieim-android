package org.thoughtcrime.securesms.dependencies

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertSame
import org.junit.Test
import org.thoughtcrime.securesms.push.SignalServiceNetworkAccess
import java.util.function.Supplier

class NetworkDependenciesModuleTest {
  @Test
  fun `network access is available without constructing either websocket`() {
    val provider = mockk<AppDependencies.Provider>()
    val networkAccess = mockk<SignalServiceNetworkAccess>()
    every { provider.provideSignalServiceNetworkAccess() } returns networkAccess

    val module = NetworkDependenciesModule(
      mockk<Application>(),
      provider,
      { error("authenticated websocket was initialized too early") },
      { error("unauthenticated websocket was initialized too early") },
      Supplier { error("libsignal network was initialized too early") }
    )

    assertSame(networkAccess, module.signalServiceNetworkAccess)
  }
}

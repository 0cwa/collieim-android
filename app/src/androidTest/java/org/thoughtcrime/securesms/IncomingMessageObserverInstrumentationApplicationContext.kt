package org.thoughtcrime.securesms

import android.content.Context
import org.signal.core.util.logging.AndroidLogger
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.logging.SignalProtocolLoggerProvider
import org.thoughtcrime.securesms.crypto.MasterSecretUtil
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.dependencies.ApplicationDependencyProvider
import org.thoughtcrime.securesms.logging.CustomSignalProtocolLogger
import org.thoughtcrime.securesms.testing.incomingmessageobserver.IncomingMessageObserverDependencyProvider
import org.thoughtcrime.securesms.testing.incomingmessageobserver.IncomingMessageObserverTestRunner
import org.thoughtcrime.securesms.util.Environment

/**
 * Application used when running `IncomingMessageObserver` instrumentation tests. Installs
 * [IncomingMessageObserverDependencyProvider] so the websocket and job manager are replaced
 * with test-friendly implementations. Selected by [IncomingMessageObserverTestRunner] when
 * gradle is invoked with `-PimoTests`.
 */
class IncomingMessageObserverInstrumentationApplicationContext : ApplicationContext() {

  override fun attachBaseContext(base: Context?) {
    Environment.IS_INSTRUMENTATION = true
    super.attachBaseContext(base)
  }

  override fun onCreate() {
    val passphrase = MasterSecretUtil.getUnencryptedPassphrase()
    val masterSecret = if (MasterSecretUtil.isPassphraseInitialized(this)) {
      MasterSecretUtil.getMasterSecret(this, passphrase)
    } else {
      MasterSecretUtil.generateMasterSecret(this, passphrase)
    }
    KeyCachingService.setMasterSecret(masterSecret)
    super.onCreate()
  }

  override fun initializeAppDependencies() {
    val default = ApplicationDependencyProvider(this)
    AppDependencies.init(IncomingMessageObserverDependencyProvider(this, default))
    AppDependencies.deadlockDetector.start()
  }

  override fun initializeLogging(locked: Boolean) {
    Log.initialize({ true }, AndroidLogger)
    SignalProtocolLoggerProvider.setProvider(CustomSignalProtocolLogger())
  }

  override fun initializeCleanup() = Unit

  override fun beginJobLoop() = Unit

  fun beginJobLoopForTests() {
    super.beginJobLoop()
  }
}

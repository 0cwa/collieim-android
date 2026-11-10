package org.signal.storage.keyvalue

class ProxyValues(store: KeyValueStore) : SignalStoreValues(store) {

  companion object {
    private const val KEY_PROXY_ENABLED = "proxy.enabled"
    private const val KEY_HOST = "proxy.host"
    private const val KEY_PORT = "proxy.port"
  }

  public override fun onFirstEverAppLaunch() = Unit

  override val keysToIncludeInBackup: List<String>
    get() = listOf(KEY_PROXY_ENABLED, KEY_HOST, KEY_PORT)

}

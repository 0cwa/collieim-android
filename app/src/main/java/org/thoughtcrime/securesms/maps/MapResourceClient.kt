package org.thoughtcrime.securesms.maps

import android.content.Context
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.net.Networking
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Transport for the bundled OpenStreetMap raster style. */
object MapResourceClient {
  @JvmStatic
  fun create(context: Context): OkHttpClient = OkHttpClient.Builder()
    .socketFactory(Networking.socketFactory)
    .proxySelector(Networking.proxySelectorForSocks)
    .dns(Networking.dns)
    // A connection opened before a proxy change must not serve a later request.
    // HTTP/1.1 also prevents concurrent calls from sharing an HTTP/2 connection.
    .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
    .protocols(listOf(Protocol.HTTP_1_1))
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .callTimeout(30, TimeUnit.SECONDS)
    .addInterceptor { chain ->
      if (!Networking.isEnabled) {
        throw IOException("Map networking is disabled")
      }
      if (Networking.proxy == Networking.DUMMY_PROXY) {
        throw IOException("Map proxy is unavailable")
      }

      val request = chain.request()
      val url = request.url
      if (url.scheme != "https" || url.host != "tile.openstreetmap.org" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) {
        // Resource URLs contain viewed coordinates. Never include them in errors.
        throw IOException("Map resource destination is not allowed")
      }

      chain.proceed(
        request.newBuilder()
          .header("User-Agent", "Collie/${BuildConfig.VERSION_NAME} (+https://collie.im)")
          .build()
      )
    }
    .addInterceptor(MapResourceCache(context.applicationContext))
    .build()
}

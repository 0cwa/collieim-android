package org.thoughtcrime.securesms.maps

import android.content.Context
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.net.Networking
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Transport for the bundled OSM raster map and OSMF Shortbread transit overlay. */
object MapResourceClient {
  const val RASTER_TILE_TEMPLATE = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
  const val TRANSIT_TILE_TEMPLATE = "https://vector.openstreetmap.org/shortbread_v1/{z}/{x}/{y}.mvt"

  private val RASTER_PATH = Regex("/([0-9]{1,2})/([0-9]{1,6})/([0-9]{1,6})\\.png")
  private val TRANSIT_PATH = Regex("/shortbread_v1/([0-9]{1,2})/([0-9]{1,6})/([0-9]{1,6})\\.mvt")

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
      val proxy = currentProxy()
      val request = chain.request()
      if (tileKind(request) == null) {
        // Resource URLs contain viewed coordinates. Never include them in errors.
        throw IOException("Map resource destination is not allowed")
      }

      chain.proceed(
        request.newBuilder()
          .header("User-Agent", "Collie/${BuildConfig.VERSION_NAME} (+https://collie.im)")
          .tag(Route::class.java, Route(proxy))
          .build()
      )
    }
    .addInterceptor(MapResourceCache(context.applicationContext))
    .addNetworkInterceptor { chain ->
      // Check again after DNS/connect and before sending coordinates in HTTP headers.
      // This does not cancel a request whose headers were already sent before a change.
      if (chain.request().tag(Route::class.java)?.proxy != currentProxy()) {
        throw IOException("Map network route changed")
      }
      chain.proceed(chain.request())
    }
    .build()

  internal fun tileKind(request: Request): MapTileKind? {
    val url = request.url
    if (request.method != "GET" || url.scheme != "https" || url.port != 443 ||
      url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null
    ) {
      return null
    }

    val (kind, path) = when (url.host) {
      "tile.openstreetmap.org" -> MapTileKind.RASTER to RASTER_PATH
      "vector.openstreetmap.org" -> MapTileKind.TRANSIT to TRANSIT_PATH
      else -> return null
    }
    val match = path.matchEntire(url.encodedPath) ?: return null
    val coordinates = match.groupValues.drop(1)
    val numbers = coordinates.map { it.toInt() }
    if (coordinates.zip(numbers).any { (text, number) -> text != number.toString() }) return null
    val (zoom, x, y) = numbers
    if (zoom > kind.maximumZoom) return null
    val tilesPerSide = 1 shl zoom
    return kind.takeIf { x < tilesPerSide && y < tilesPerSide }
  }

  private fun currentProxy(): Proxy {
    if (!Networking.isEnabled) throw IOException("Map networking is disabled")
    val proxy = Networking.proxy
    if (proxy == Networking.DUMMY_PROXY) throw IOException("Map proxy is unavailable")
    return proxy
  }

  private data class Route(val proxy: Proxy)
}

internal enum class MapTileKind(val maximumZoom: Int, val maximumBytes: Int) {
  RASTER(19, 1024 * 1024),
  TRANSIT(14, 4 * 1024 * 1024)
}

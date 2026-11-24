package org.thoughtcrime.securesms.maps

import android.content.Context
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.net.Networking
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Transport for the bundled OSM raster map and OSMF Shortbread transit overlay. */
object MapResourceClient {
  const val RASTER_TILE_TEMPLATE = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
  const val TRANSIT_TILE_TEMPLATE = "https://vector.openstreetmap.org/shortbread_v1/{z}/{x}/{y}.mvt"

  private val RASTER_PATH = Regex("/([0-9]{1,2})/([0-9]{1,6})/([0-9]{1,6})\\.png")
  private val TRANSIT_PATH = Regex("/shortbread_v1/([0-9]{1,2})/([0-9]{1,6})/([0-9]{1,6})\\.mvt")

  @JvmStatic
  fun create(context: Context): Call.Factory {
    val application = context.applicationContext
    return object : Call.Factory {
      private var routing: Networking.Routing? = null
      private var client: OkHttpClient? = null

      @Synchronized
      override fun newCall(request: Request): Call {
        val current = Networking.routing
        if (routing !== current) {
          client = createClient(application, current)
          routing = current
        }
        return checkNotNull(client).newCall(request)
      }
    }
  }

  internal fun createClient(context: Context, route: Networking.Routing): OkHttpClient = OkHttpClient.Builder()
    .socketFactory(route.socketFactory)
    .proxySelector(route.proxySelector)
    .dns(route.dns)
    // Only share connections within one route and avoid retaining them between tile calls.
    .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
    .protocols(listOf(Protocol.HTTP_1_1))
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .callTimeout(30, TimeUnit.SECONDS)
    .addInterceptor { chain ->
      val request = chain.request()
      if (tileKind(request) == null) {
        // Resource URLs contain viewed coordinates. Never include them in errors.
        throw IOException("Map resource destination is not allowed")
      }

      val call = chain.call()
      route.track(call)
      try {
        chain.proceed(request.newBuilder()
          .header("User-Agent", "Collie/${BuildConfig.VERSION_NAME} (+https://collie.im)")
          .build())
      } finally {
        route.untrack(call)
      }
    }
    .addInterceptor(MapResourceCache(context.applicationContext))
    .addNetworkInterceptor { chain ->
      // Check after DNS and connection setup, immediately before sending tile headers.
      route.ensureCurrent()
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
}

internal enum class MapTileKind(val maximumZoom: Int, val maximumBytes: Int) {
  RASTER(19, 1024 * 1024),
  TRANSIT(14, 4 * 1024 * 1024)
}

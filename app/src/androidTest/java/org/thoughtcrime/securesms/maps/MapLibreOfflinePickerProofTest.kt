package org.thoughtcrime.securesms.maps

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.text.Spanned
import android.text.style.URLSpan
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.net.Networking
import org.thoughtcrime.securesms.testing.SignalActivityRule
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Requires a disposable API 35 device without location grants. The app and tile transport stay offline. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class MapLibreOfflinePickerProofTest {
  @get:Rule val harness = SignalActivityRule(othersCount = 0, networkEnabled = false)

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context get() = harness.context
  private val rasterResponses = AtomicInteger()
  private val transitRequests = AtomicInteger()
  private val transitReleased = CountDownLatch(1)
  private val unexpectedRequests = AtomicInteger()
  private val tileColor = Color.rgb(186, 220, 213)

  @Test
  fun pickerRendersVectorRailInTheSharePreview() = verifyPicker(stallTransit = false, renderRail = true)

  private fun verifyPicker(stallTransit: Boolean, renderRail: Boolean = false) {
    assertFalse(Networking.isEnabled)
    assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
    assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION))

    val previousSecurity = SignalStore.settings.isScreenSecurityEnabled
    SignalStore.settings.isScreenSecurityEnabled = false
    try {
      installSyntheticTileTransport(stallTransit, renderRail)
      val intent = Intent(context, PlacePickerActivity::class.java).putExtra(PlacePickerActivity.KEY_CHAT_COLOR, Color.rgb(31, 99, 115))
      ActivityScenario.launchActivityForResult<PlacePickerActivity>(intent).use { scenario ->
        val firstFrame = CountDownLatch(1)
        val liveMap = AtomicReference<GoogleMap>()
        scenario.onActivity { activity ->
          activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
          val fragment = activity.supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
          fragment.getMapAsync { map ->
            liveMap.set(map)
            map.setOnMapLoadedCallback { firstFrame.countDown() }
          }
        }
        assertTrue("No fully rendered native map frame", firstFrame.await(20, TimeUnit.SECONDS))
        assertTrue("The renderer must request a synthetic raster tile", rasterResponses.get() > 0)
        if (stallTransit || renderRail) {
          assertTrue("The renderer must request a vector transit tile", transitRequests.get() > 0)
        }
        assertPicker(scenario, checkNotNull(liveMap.get()))
        capturePicker("maplibre-offline-picker")

        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        awaitLandscape(scenario)
        assertPicker(scenario, checkNotNull(liveMap.get()))
        capturePicker("maplibre-offline-landscape")

        onView(withId(R.id.place_chosen_button)).perform(click())
        val result = scenario.result
        assertEquals(Activity.RESULT_OK, result.resultCode)
        val data = checkNotNull(result.resultData)
        data.setExtrasClassLoader(PlacePickerActivity::class.java.classLoader)
        val selected = checkNotNull(PlacePickerActivity.addressFromData(data))
        assertEquals(51.4779, selected.latitude, 0.0001)
        assertEquals(-0.0015, selected.longitude, 0.0001)
        assertEquals("An offline picker must return coordinates without a platform address", "", selected.address)
        val uri = checkNotNull(data.data)
        try {
          val preview = AppDependencies.blobs.getStream(context, uri).use { checkNotNull(BitmapFactory.decodeStream(it)) }
          try {
            assertTrue("The returned preview must contain rendered raster tiles", containsSyntheticTile(preview))
            saveScreenshot(preview, "maplibre-offline-share-preview")
            if (renderRail) assertTrue("The preview must contain the rendered vector rail line", containsRailLine(preview))
          } finally {
            preview.recycle()
          }
        } finally {
          AppDependencies.blobs.delete(context, uri)
        }
      }
      assertEquals("A non-fixture resource was requested", 0, unexpectedRequests.get())
    } finally {
      transitReleased.countDown()
      instrumentation.runOnMainSync {
        if (MapLibre.hasInstance()) {
          HttpRequestUtil.setOkHttpClient(MapResourceClient.create(context))
          MapLibre.setConnected(null)
        }
        SignalStore.settings.isScreenSecurityEnabled = previousSecurity
      }
    }
  }

  private fun assertPicker(scenario: ActivityScenario<PlacePickerActivity>, map: GoogleMap) {
    scenario.onActivity {
      val camera = map.cameraPosition
      assertEquals(51.4779, camera.target.latitude, 0.0001)
      assertEquals(-0.0015, camera.target.longitude, 0.0001)
    }
    onView(withId(R.id.marker_image_view)).check(matches(isDisplayed()))
    onView(withId(R.id.place_chosen_button)).check(matches(isDisplayed())).check(matches(isEnabled()))
    onView(withText(R.string.MapView__osm_attribution)).check(matches(isDisplayed())).check { view, noMatch ->
      if (noMatch != null) throw noMatch
      val text = (view as TextView).text as Spanned
      val attribution = text.getSpans(0, text.length, URLSpan::class.java).single()
      assertEquals("https://www.openstreetmap.org/copyright", attribution.url)
    }
  }

  private fun awaitLandscape(scenario: ActivityScenario<PlacePickerActivity>) {
    val deadline = SystemClock.uptimeMillis() + 15_000
    do {
      var landscape = false
      scenario.onActivity { activity ->
        val map = activity.supportFragmentManager.findFragmentById(R.id.map)?.view
        landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
          map != null && map.width > map.height && activity.findViewById<View>(R.id.place_chosen_button).isEnabled
      }
      if (landscape) return
      SystemClock.sleep(50)
    } while (SystemClock.uptimeMillis() < deadline)
    error("The location picker did not become ready in landscape")
  }

  private fun installSyntheticTileTransport(stallTransit: Boolean, renderRail: Boolean) {
    val rasterTile = rasterTile()
    val transitTile = if (renderRail) railTransitTile() else emptyTransitTile()
    val client = OkHttpClient.Builder()
      .dns { throw IOException("Offline map proof does not permit DNS") }
      .addInterceptor { chain ->
        val request = chain.request()
        val (bytes, mime) = when (MapResourceClient.tileKind(request)) {
          MapTileKind.RASTER -> {
            rasterResponses.incrementAndGet()
            rasterTile to "image/png"
          }
          MapTileKind.TRANSIT -> {
            transitRequests.incrementAndGet()
            if (stallTransit) {
              transitReleased.await()
              throw IOException("Synthetic transit tile unavailable")
            }
            transitTile to "application/vnd.mapbox-vector-tile"
          }
          null -> {
            unexpectedRequests.incrementAndGet()
            throw IOException("Offline map proof received an unexpected resource")
          }
        }
        // Return bytes directly. Never call proceed or open a socket.
        Response.Builder()
          .request(request)
          .protocol(Protocol.HTTP_1_1)
          .code(200)
          .message("OK")
          .header("Content-Type", mime)
          .body(bytes.toResponseBody(mime.toMediaType()))
          .build()
      }
      .build()
    val initialized = CountDownLatch(1)
    val failure = AtomicReference<Exception>()
    instrumentation.runOnMainSync {
      MapLibreConfiguration.prepare(context, object : MapLibreConfiguration.Callback {
        override fun onReady() {
          HttpRequestUtil.setOkHttpClient(client)
          // Let the native renderer request fixture tiles while Collie networking stays disabled.
          MapLibre.setConnected(true)
          initialized.countDown()
        }

        override fun onError(error: Exception) {
          failure.set(error)
          initialized.countDown()
        }
      })
    }
    assertTrue("Private map storage did not initialize", initialized.await(15, TimeUnit.SECONDS))
    failure.get()?.let { throw AssertionError("Private map storage could not initialize", it) }
  }

  private fun rasterTile(): ByteArray {
    val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
    try {
      val canvas = Canvas(bitmap)
      canvas.drawColor(tileColor)
      val paint = Paint().apply {
        color = Color.rgb(252, 248, 226)
        strokeWidth = 12f
      }
      canvas.drawLine(0f, 170f, 256f, 170f, paint)
      canvas.drawLine(70f, 0f, 70f, 256f, paint)
      return ByteArrayOutputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        it.toByteArray()
      }
    } finally {
      bitmap.recycle()
    }
  }

  // Mapbox Vector Tile 2.1, with Shortbread's streets/kind=rail attributes.
  // Repeat horizontal lines so some remain visible when z14 tiles are overzoomed.
  private fun railTransitTile(): ByteArray = ByteArrayOutputStream().apply {
    val streets = ByteArrayOutputStream().apply {
      field(1, "streets".toByteArray())
      for (y in 16 until 4096 step 32) {
        field(2, ByteArrayOutputStream().apply {
          field(2, byteArrayOf(0, 0)) // First key and first value.
          number(3 shl 3)
          number(2) // LINESTRING
          field(4, ByteArrayOutputStream().apply {
            for (value in listOf(9, 0, y * 2, 10, 4096 * 2, 0)) number(value)
          }.toByteArray())
        }.toByteArray())
      }
      field(3, "kind".toByteArray())
      field(4, ByteArrayOutputStream().apply { field(1, "rail".toByteArray()) }.toByteArray())
      number(5 shl 3)
      number(4096)
      number(15 shl 3)
      number(2)
    }.toByteArray()
    field(3, streets)
  }.toByteArray()

  private fun ByteArrayOutputStream.field(field: Int, payload: ByteArray) {
    number((field shl 3) or 2)
    number(payload.size)
    write(payload)
  }

  private fun ByteArrayOutputStream.number(value: Int) {
    var remaining = value
    while (remaining >= 128) {
      write((remaining and 127) or 128)
      remaining = remaining ushr 7
    }
    write(remaining)
  }

  private fun containsRailLine(bitmap: Bitmap): Boolean {
    val length = bitmap.width / 4
    for (y in bitmap.height / 4 until bitmap.height * 3 / 4) {
      var matching = 0
      for (x in length until length * 3) {
        val pixel = bitmap.getPixel(x, y)
        if (abs(Color.red(pixel) - 98) <= 12 &&
          abs(Color.green(pixel) - 123) <= 12 &&
          abs(Color.blue(pixel) - 142) <= 12
        ) {
          if (++matching >= length) return true
        } else {
          matching = 0
        }
      }
    }
    return false
  }

  private fun emptyTransitTile(): ByteArray = ByteArrayOutputStream().use { output ->
    for (name in listOf("streets", "public_transport")) {
      val bytes = name.toByteArray(Charsets.UTF_8)
      // Vector tile layer field, name field and version 2, with no geometry.
      output.write(byteArrayOf(0x1a, (bytes.size + 4).toByte(), 0x0a, bytes.size.toByte()))
      output.write(bytes)
      output.write(byteArrayOf(0x78, 0x02))
    }
    output.toByteArray()
  }

  private fun capturePicker(name: String) {
    instrumentation.waitForIdleSync()
    val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
      saveScreenshot(screenshot, name)
    } finally {
      screenshot.recycle()
    }
  }

  private fun containsSyntheticTile(bitmap: Bitmap): Boolean {
    var matching = 0
    for (y in 0 until bitmap.height step 12) {
      for (x in 0 until bitmap.width step 12) {
        val pixel = bitmap.getPixel(x, y)
        if (abs(Color.red(pixel) - Color.red(tileColor)) < 10 &&
          abs(Color.green(pixel) - Color.green(tileColor)) < 10 &&
          abs(Color.blue(pixel) - Color.blue(tileColor)) < 10
        ) {
          if (++matching >= 20) return true
        }
      }
    }
    return false
  }

  private fun saveScreenshot(bitmap: Bitmap, name: String) {
    val resolver = context.contentResolver
    val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
      put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
      put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/collie-settings-navigation/")
      put(MediaStore.MediaColumns.IS_PENDING, 1)
    }))
    checkNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
  }
}

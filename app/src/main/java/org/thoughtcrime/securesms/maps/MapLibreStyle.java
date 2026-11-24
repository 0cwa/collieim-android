package org.thoughtcrime.securesms.maps;

import android.content.Context;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.RasterLayer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.sources.RasterSource;
import org.maplibre.android.style.sources.TileSet;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.thoughtcrime.securesms.R;

import java.util.Objects;

import static org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap;
import static org.maplibre.android.style.layers.PropertyFactory.iconAnchor;
import static org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement;
import static org.maplibre.android.style.layers.PropertyFactory.iconImage;
import static org.maplibre.android.style.layers.PropertyFactory.rasterFadeDuration;

/** A bundled raster style: no remote style, TileJSON, fonts, sprites or API keys. */
public final class MapLibreStyle {
  public static final String SELECTED_LOCATION = "selected-location";
  private static final String MARKER = "collie-location-marker";

  private MapLibreStyle() {}

  public static @NonNull Style.Builder create(@NonNull Context context) {
    TileSet tiles = new TileSet("2.1.0", "https://tile.openstreetmap.org/{z}/{x}/{y}.png");
    tiles.setAttribution("© OpenStreetMap contributors");
    tiles.setMinZoom(0f);
    tiles.setMaxZoom(19f);

    RasterSource source = new RasterSource("openstreetmap", tiles, 256);
    // This also prevents responses being forwarded to MapLibre's plaintext SQLite cache.
    source.setVolatile(true);

    Drawable marker = Objects.requireNonNull(ResourcesCompat.getDrawable(context.getResources(), R.drawable.ic_map_marker, context.getTheme()));
    return new Style.Builder()
        .withSource(source)
        .withLayer(new RasterLayer("openstreetmap", "openstreetmap").withProperties(rasterFadeDuration(0f)))
        .withSource(new GeoJsonSource(SELECTED_LOCATION, FeatureCollection.fromFeatures(new Feature[0])))
        .withImage(MARKER, marker)
        .withLayer(new SymbolLayer(SELECTED_LOCATION, SELECTED_LOCATION).withProperties(
            iconImage(MARKER), iconAnchor(Property.ICON_ANCHOR_BOTTOM), iconAllowOverlap(true), iconIgnorePlacement(true)));
  }
}

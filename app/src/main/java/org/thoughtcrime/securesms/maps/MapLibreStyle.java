package org.thoughtcrime.securesms.maps;

import android.content.Context;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.RasterLayer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.sources.RasterSource;
import org.maplibre.android.style.sources.TileSet;
import org.maplibre.android.style.sources.VectorSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.thoughtcrime.securesms.R;

import java.util.Objects;

import static org.maplibre.android.style.expressions.Expression.all;
import static org.maplibre.android.style.expressions.Expression.coalesce;
import static org.maplibre.android.style.expressions.Expression.eq;
import static org.maplibre.android.style.expressions.Expression.get;
import static org.maplibre.android.style.expressions.Expression.in;
import static org.maplibre.android.style.expressions.Expression.interpolate;
import static org.maplibre.android.style.expressions.Expression.linear;
import static org.maplibre.android.style.expressions.Expression.literal;
import static org.maplibre.android.style.expressions.Expression.stop;
import static org.maplibre.android.style.expressions.Expression.zoom;
import static org.maplibre.android.style.layers.PropertyFactory.circleColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleRadius;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth;
import static org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap;
import static org.maplibre.android.style.layers.PropertyFactory.iconAnchor;
import static org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement;
import static org.maplibre.android.style.layers.PropertyFactory.iconImage;
import static org.maplibre.android.style.layers.PropertyFactory.lineCap;
import static org.maplibre.android.style.layers.PropertyFactory.lineColor;
import static org.maplibre.android.style.layers.PropertyFactory.lineDasharray;
import static org.maplibre.android.style.layers.PropertyFactory.lineJoin;
import static org.maplibre.android.style.layers.PropertyFactory.lineOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.lineWidth;
import static org.maplibre.android.style.layers.PropertyFactory.rasterFadeDuration;

/** A local OSM style with transit context and no remote style, fonts, sprites or API keys. */
public final class MapLibreStyle {
  public static final String SELECTED_LOCATION = "selected-location";
  private static final String MARKER = "collie-location-marker";
  private static final String TRANSIT_SOURCE = "openstreetmap-transit";
  private static final String RAIL_LAYER = "transit-rail";
  private static final String UNDERGROUND_LAYER = "transit-underground";
  private static final String STATIONS_LAYER = "transit-stations";
  private static final String STOPS_LAYER = "transit-stops";
  private static final String TRANSIT_COLOR = "#526A82";

  private MapLibreStyle() {}

  public static @NonNull Style.Builder create(@NonNull Context context) {
    TileSet tiles = new TileSet("2.1.0", MapResourceClient.RASTER_TILE_TEMPLATE);
    tiles.setAttribution("© OpenStreetMap contributors");
    tiles.setMinZoom(0f);
    tiles.setMaxZoom(19f);

    RasterSource source = new RasterSource("openstreetmap", tiles, 256);
    // This also prevents responses being forwarded to MapLibre's plaintext SQLite cache.
    source.setVolatile(true);

    Style.Builder style = new Style.Builder()
        .withSource(source)
        .withLayer(new RasterLayer("openstreetmap", "openstreetmap").withProperties(rasterFadeDuration(0f)));
    addTransitContext(style);

    Drawable marker = Objects.requireNonNull(ResourcesCompat.getDrawable(context.getResources(), R.drawable.ic_map_marker, context.getTheme()));
    // Context stays below the selected place; the base raster continues to provide labels.
    return style
        .withSource(new GeoJsonSource(SELECTED_LOCATION, FeatureCollection.fromFeatures(new Feature[0])))
        .withImage(MARKER, marker)
        .withLayer(new SymbolLayer(SELECTED_LOCATION, SELECTED_LOCATION).withProperties(
            iconImage(MARKER), iconAnchor(Property.ICON_ANCHOR_BOTTOM), iconAllowOverlap(true), iconIgnorePlacement(true)));
  }

  private static void addTransitContext(@NonNull Style.Builder style) {
    TileSet tiles = new TileSet("3.0.0", MapResourceClient.TRANSIT_TILE_TEMPLATE);
    tiles.setAttribution("© OpenStreetMap contributors");
    // Only request this additional context at neighbourhood zooms. Shortbread is
    // published through z14; MapLibre overzooms those tiles at closer camera zooms.
    tiles.setMinZoom(12f);
    tiles.setMaxZoom(14f);

    VectorSource source = new VectorSource(TRANSIT_SOURCE, tiles);
    source.setVolatile(true);
    style.withSource(source);

    // Shortbread supplies physical infrastructure, not named passenger routes or
    // service colours. Use one subdued colour and leave navigation to map apps.
    Expression rail = in(get("kind"), literal(new String[] {
        "rail", "narrow_gauge", "tram", "light_rail", "subway", "monorail", "funicular"
    }));
    LineLayer surface = new LineLayer(RAIL_LAYER, TRANSIT_SOURCE)
        .withSourceLayer("streets")
        .withFilter(all(rail, eq(coalesce(get("tunnel"), literal(false)), literal(false))))
        .withProperties(lineColor(TRANSIT_COLOR), lineOpacity(0.85f),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            lineWidth(interpolate(linear(), zoom(), stop(12, 1.2f), stop(16, 2.2f))));
    surface.setMinZoom(12f);

    LineLayer underground = new LineLayer(UNDERGROUND_LAYER, TRANSIT_SOURCE)
        .withSourceLayer("streets")
        .withFilter(all(rail, eq(get("tunnel"), literal(true))))
        .withProperties(lineColor(TRANSIT_COLOR), lineOpacity(0.75f),
            lineDasharray(new Float[] {2f, 1.5f}), lineJoin(Property.LINE_JOIN_ROUND),
            lineWidth(interpolate(linear(), zoom(), stop(12, 1.2f), stop(16, 2.2f))));
    underground.setMinZoom(12f);

    CircleLayer stations = new CircleLayer(STATIONS_LAYER, TRANSIT_SOURCE)
        .withSourceLayer("public_transport")
        .withFilter(in(get("kind"), literal(new String[] {"station", "halt", "bus_station", "ferry_terminal"})))
        .withProperties(circleColor("#FFFFFF"), circleStrokeColor(TRANSIT_COLOR), circleStrokeWidth(1.4f),
            circleRadius(interpolate(linear(), zoom(), stop(13, 2.5f), stop(16, 3.5f))));
    stations.setMinZoom(13f);

    CircleLayer stops = new CircleLayer(STOPS_LAYER, TRANSIT_SOURCE)
        .withSourceLayer("public_transport")
        .withFilter(in(get("kind"), literal(new String[] {"tram_stop", "bus_stop"})))
        .withProperties(circleColor(TRANSIT_COLOR), circleStrokeColor("#FFFFFF"), circleStrokeWidth(1f),
            circleRadius(interpolate(linear(), zoom(), stop(14, 2f), stop(17, 3f))));
    stops.setMinZoom(14f);

    style.withLayer(surface).withLayer(underground).withLayer(stations).withLayer(stops);
  }

  /** Drop optional context without replacing the base map or its selected-location marker. */
  public static void removeTransitContext(@NonNull Style style) {
    // MapLibre refuses to remove a source while a style layer still refers to it.
    style.removeLayer(STOPS_LAYER);
    style.removeLayer(STATIONS_LAYER);
    style.removeLayer(UNDERGROUND_LAYER);
    style.removeLayer(RAIL_LAYER);
    style.removeSource(TRANSIT_SOURCE);
  }
}

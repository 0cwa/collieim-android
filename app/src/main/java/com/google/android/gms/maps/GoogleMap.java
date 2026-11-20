package com.google.android.gms.maps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;

import androidx.core.content.ContextCompat;

import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import org.maplibre.android.location.LocationComponent;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Point;
import org.thoughtcrime.securesms.maps.MapLibreStyle;

/** The small subset of upstream's GoogleMap API used by Collie's location sharing. */
public final class GoogleMap {
  public static final int MAP_TYPE_NONE = 0;
  public static final int MAP_TYPE_NORMAL = 1;
  public static final int MAP_TYPE_SATELLITE = 2;
  public static final int MAP_TYPE_TERRAIN = 3;
  public static final int MAP_TYPE_HYBRID = 4;

  private final MapView mapView;
  private final MapLibreMap map;
  private final UiSettings uiSettings;
  private OnCameraIdleListener onCameraIdleListener;
  private OnCameraMoveStartedListener onCameraMoveStartedListener;

  GoogleMap(MapView mapView, MapLibreMap map) {
    this.mapView = mapView;
    this.map = map;
    this.uiSettings = new UiSettings(mapView, map.getUiSettings());
    map.addOnCameraMoveStartedListener(reason -> {
      mapView.invalidateRenderedFrame();
      if (onCameraMoveStartedListener != null) onCameraMoveStartedListener.onCameraMoveStarted(reason);
    });
    map.addOnCameraIdleListener(() -> {
      if (onCameraIdleListener != null) onCameraIdleListener.onCameraIdle();
    });
  }

  public void setOnCameraMoveStartedListener(OnCameraMoveStartedListener listener) {
    onCameraMoveStartedListener = listener;
  }

  public void setOnCameraIdleListener(OnCameraIdleListener listener) {
    onCameraIdleListener = listener;
  }

  public CameraPosition getCameraPosition() {
    org.maplibre.android.camera.CameraPosition position = map.getCameraPosition();
    org.maplibre.android.geometry.LatLng target = position.target;
    return new CameraPosition(new LatLng(target.getLatitude(), target.getLongitude()), (float) position.zoom);
  }

  public void moveCamera(CameraUpdate update) {
    mapView.invalidateRenderedFrame();
    // Preserve the old shim's +2 zoom adjustment, accounting for MapLibre's
    // 512-pixel camera basis versus osmdroid's 256-pixel basis.
    map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
        new org.maplibre.android.geometry.LatLng(update.latLng.latitude, update.latLng.longitude), update.zoom + 1));
  }

  public Marker addMarker(MarkerOptions options) {
    Style style = map.getStyle();
    if (style != null) {
      GeoJsonSource source = style.getSourceAs(MapLibreStyle.SELECTED_LOCATION);
      if (source != null) {
        mapView.invalidateRenderedFrame();
        source.setGeoJson(Point.fromLngLat(options.getPosition().longitude, options.getPosition().latitude));
      }
    }
    return new Marker();
  }

  @SuppressLint("MissingPermission")
  public void setMyLocationEnabled(boolean enabled) {
    boolean permitted = ContextCompat.checkSelfPermission(mapView.getContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(mapView.getContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    LocationComponent location = map.getLocationComponent();
    Style style = map.getStyle();
    if (enabled && permitted && style != null && !location.isLocationComponentActivated()) {
      location.activateLocationComponent(LocationComponentActivationOptions.builder(mapView.getContext(), style).build());
    }
    if (location.isLocationComponentActivated()) location.setLocationComponentEnabled(enabled && permitted);
  }

  public void setBuildingsEnabled(boolean enabled) {}

  // The raster provider has no satellite/terrain variants or Google JSON styling.
  public void setMapType(int type) {}

  public boolean setMapStyle(MapStyleOptions style) {
    return false;
  }

  public void setPadding(int left, int top, int right, int bottom) {
    map.setPadding(left, top, right, bottom);
    mapView.setMapPadding(left, top, right, bottom);
  }

  public UiSettings getUiSettings() {
    return uiSettings;
  }

  public void setOnMapLoadedCallback(OnMapLoadedCallback callback) {
    mapView.setOnMapLoadedCallback(callback);
  }

  public void snapshot(SnapshotReadyCallback callback) {
    mapView.snapshot(map, callback);
  }

  public interface OnCameraMoveStartedListener {
    void onCameraMoveStarted(int reason);
  }

  public interface OnCameraIdleListener {
    void onCameraIdle();
  }

  public interface OnMapLoadedCallback {
    void onMapLoaded();
  }

  public interface SnapshotReadyCallback {
    void onSnapshotReady(Bitmap snapshot);
  }
}

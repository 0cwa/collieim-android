package com.google.android.gms.maps;

public final class UiSettings {
  private final MapView mapView;
  private final org.maplibre.android.maps.UiSettings settings;

  UiSettings(MapView mapView, org.maplibre.android.maps.UiSettings settings) {
    this.mapView = mapView;
    this.settings = settings;
  }

  public void setAllGesturesEnabled(boolean isAllGesturesEnabled) {
    settings.setAllGesturesEnabled(isAllGesturesEnabled);
    mapView.setGesturesEnabled(isAllGesturesEnabled);
  }
}

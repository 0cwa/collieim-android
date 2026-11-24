package com.google.android.gms.maps;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.List;

public class SupportMapFragment extends Fragment {
  private final List<OnMapReadyCallback> pendingCallbacks = new ArrayList<>();

  private @Nullable MapView mapView;

  public void getMapAsync(@NonNull OnMapReadyCallback callback) {
    MapView currentMapView = mapView;
    if (currentMapView == null) {
      pendingCallbacks.add(callback);
    } else {
      currentMapView.getMapAsync(googleMap -> {
        if (mapView == currentMapView) {
          callback.onMapReady(googleMap);
        }
      });
    }
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState)
  {
    mapView = new MapView(inflater.getContext());
    mapView.onCreate(savedInstanceState);

    List<OnMapReadyCallback> callbacks = new ArrayList<>(pendingCallbacks);
    pendingCallbacks.clear();
    for (OnMapReadyCallback callback : callbacks) {
      getMapAsync(callback);
    }

    return mapView;
  }

  @Override
  public void onStart() {
    super.onStart();
    if (mapView != null) mapView.onStart();
  }

  @Override
  public void onResume() {
    super.onResume();
    if (mapView != null) mapView.onResume();
  }

  @Override
  public void onPause() {
    if (mapView != null) mapView.onPause();
    super.onPause();
  }

  @Override
  public void onStop() {
    if (mapView != null) mapView.onStop();
    super.onStop();
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    if (mapView != null) mapView.onSaveInstanceState(outState);
  }

  @Override
  public void onLowMemory() {
    super.onLowMemory();
    if (mapView != null) mapView.onLowMemory();
  }

  @Override
  public void onDestroyView() {
    MapView previousMapView = mapView;
    mapView = null;
    pendingCallbacks.clear();
    if (previousMapView != null) previousMapView.onDestroy();
    super.onDestroyView();
  }

  @Override
  public void onDestroy() {
    pendingCallbacks.clear();
    super.onDestroy();
  }
}

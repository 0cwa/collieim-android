package com.google.android.gms.maps;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.ZoomControls;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.Style;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.maps.MapLibreConfiguration;
import org.thoughtcrime.securesms.maps.MapLibreStyle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Compatibility view for upstream callers, backed by MapLibre Native. */
public final class MapView extends FrameLayout {
  private static final long OPTIONAL_CONTEXT_TIMEOUT_MILLIS = 5_000;

  private final List<OnMapReadyCallback> readyCallbacks = new ArrayList<>();

  private org.maplibre.android.maps.MapView renderer;
  private GoogleMap googleMap;
  private TextView attribution;
  private ZoomControls zoomControls;
  private GoogleMap.OnMapLoadedCallback loadedCallback;
  private Runnable optionalContextFallback;
  private OnMapErrorListener errorListener;
  private Exception failure;
  private Bundle savedState;
  private int generation;
  private int loadedCallbackGeneration;
  private boolean initializing;
  private boolean started;
  private boolean resumed;
  private boolean fullyRendered;

  public MapView(@NonNull Context context) {
    this(context, null);
  }

  public MapView(@NonNull Context context, @Nullable AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public MapView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
  }

  public void getMapAsync(@NonNull OnMapReadyCallback callback) {
    if (googleMap != null) {
      callback.onMapReady(googleMap);
    } else if (failure == null) {
      readyCallbacks.add(callback);
      initialize();
    }
  }

  private void initialize() {
    if (initializing || renderer != null || failure != null) return;
    initializing = true;
    int currentGeneration = generation;
    MapLibreConfiguration.prepare(getContext(), new MapLibreConfiguration.Callback() {
      @Override
      public void onReady() {
        if (generation != currentGeneration) return;
        createRenderer(currentGeneration);
      }

      @Override
      public void onError(@NonNull Exception error) {
        if (generation == currentGeneration) reportFailure(error);
      }
    });
  }

  private void createRenderer(int currentGeneration) {
    // Never construct a renderer before the shared cache initialization gate succeeds.
    renderer = new org.maplibre.android.maps.MapView(getContext(), new MapLibreMapOptions()
        .textureMode(true)
        .actionJournalEnabled(false)
        .logoEnabled(false)
        .attributionEnabled(false)
        .compassEnabled(false)
        .rotateGesturesEnabled(false)
        .tiltGesturesEnabled(false)
        .setPrefetchZoomDelta(0));
    addView(renderer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    addAttribution();
    renderer.addOnDidFailLoadingMapListener(message -> {
      if (generation == currentGeneration) reportFailure(new IOException("Map could not be loaded"));
    });
    renderer.addOnDidFinishRenderingFrameListener((fully, frameTime, encodingTime) -> {
      if (generation != currentGeneration) return;
      fullyRendered = fully;
      dispatchLoadedCallback();
    });
    renderer.onCreate(savedState);
    if (started) renderer.onStart();
    if (resumed) renderer.onResume();
    renderer.getMapAsync(map -> {
      if (generation != currentGeneration) return;
      map.setPrefetchZoomDelta(0);
      map.setStyle(MapLibreStyle.create(getContext()), style -> {
        if (generation != currentGeneration) return;
        initializing = false;
        googleMap = new GoogleMap(MapView.this, map);
        addZoomControls(map);
        List<OnMapReadyCallback> callbacks = new ArrayList<>(readyCallbacks);
        readyCallbacks.clear();
        for (OnMapReadyCallback callback : callbacks) {
          if (generation != currentGeneration) break;
          callback.onMapReady(googleMap);
        }
      });
    });
  }

  private void addAttribution() {
    attribution = new TextView(getContext());
    SpannableString text = new SpannableString(getContext().getString(R.string.MapView__osm_attribution));
    text.setSpan(new URLSpan("https://www.openstreetmap.org/copyright"), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    attribution.setText(text);
    attribution.setMovementMethod(LinkMovementMethod.getInstance());
    attribution.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
    attribution.setTextColor(Color.BLACK);
    attribution.setLinkTextColor(Color.BLACK);
    attribution.setBackgroundColor(Color.argb(230, 255, 255, 255));
    attribution.setPadding(dp(4), dp(2), dp(4), dp(2));
    // A child of the native MapView is also included in its snapshot bitmap.
    renderer.addView(attribution, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
  }

  private void addZoomControls(@NonNull MapLibreMap map) {
    zoomControls = new ZoomControls(getContext());
    zoomControls.setOnZoomInClickListener(view -> map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.zoomIn()));
    zoomControls.setOnZoomOutClickListener(view -> map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.zoomOut()));
    renderer.addView(zoomControls, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
  }

  void setMapPadding(int left, int top, int right, int bottom) {
    if (attribution != null) {
      LayoutParams params = (LayoutParams) attribution.getLayoutParams();
      params.setMargins(left, top, right, bottom);
      attribution.setLayoutParams(params);
    }
    if (zoomControls != null) {
      LayoutParams params = (LayoutParams) zoomControls.getLayoutParams();
      params.setMargins(left, top, right, bottom);
      zoomControls.setLayoutParams(params);
    }
  }

  void setGesturesEnabled(boolean enabled) {
    if (zoomControls != null) zoomControls.setVisibility(enabled ? VISIBLE : GONE);
  }

  void invalidateRenderedFrame() {
    fullyRendered = false;
  }

  void setOnMapLoadedCallback(@Nullable GoogleMap.OnMapLoadedCallback callback) {
    loadedCallbackGeneration++;
    loadedCallback = callback;
    scheduleOptionalContextFallback();
    dispatchLoadedCallback();
  }

  private void scheduleOptionalContextFallback() {
    cancelOptionalContextFallback();
    if (loadedCallback == null || fullyRendered || !resumed) return;

    int currentGeneration = generation;
    int callbackGeneration = loadedCallbackGeneration;
    GoogleMap.OnMapLoadedCallback callback = loadedCallback;
    optionalContextFallback = () -> {
      optionalContextFallback = null;
      if (generation != currentGeneration || loadedCallbackGeneration != callbackGeneration || loadedCallback != callback || fullyRendered || renderer == null) return;
      renderer.getMapAsync(map -> {
        if (generation != currentGeneration || loadedCallbackGeneration != callbackGeneration || loadedCallback != callback || fullyRendered) return;
        Style style = map.getStyle();
        if (style != null) {
          MapLibreStyle.removeTransitContext(style);
          map.triggerRepaint();
        }
        // Wait for a real complete base-map frame. Never declare a partial map ready.
      });
    };
    postDelayed(optionalContextFallback, OPTIONAL_CONTEXT_TIMEOUT_MILLIS);
  }

  private void cancelOptionalContextFallback() {
    if (optionalContextFallback != null) removeCallbacks(optionalContextFallback);
    optionalContextFallback = null;
  }

  private void dispatchLoadedCallback() {
    if (!fullyRendered || loadedCallback == null || !resumed) return;
    cancelOptionalContextFallback();
    GoogleMap.OnMapLoadedCallback callback = loadedCallback;
    loadedCallback = null;
    int currentGeneration = generation;
    int callbackGeneration = loadedCallbackGeneration;
    // Leave the render callback before requesting a snapshot; never recurse through idle events.
    post(() -> {
      if (generation != currentGeneration || loadedCallbackGeneration != callbackGeneration || failure != null) return;
      if (resumed && fullyRendered) {
        callback.onMapLoaded();
      } else {
        loadedCallback = callback;
        scheduleOptionalContextFallback();
      }
    });
  }

  void snapshot(@NonNull MapLibreMap map, @NonNull GoogleMap.SnapshotReadyCallback callback) {
    if (!started || renderer == null) {
      reportFailure(new IOException("Map preview is not active"));
      return;
    }
    int currentGeneration = generation;
    map.snapshot(bitmap -> {
      if (generation == currentGeneration) callback.onSnapshotReady(bitmap);
    });
  }

  public void setOnMapErrorListener(@Nullable OnMapErrorListener listener) {
    errorListener = listener;
    if (listener != null && failure != null) listener.onMapError(failure);
  }

  private void reportFailure(@NonNull Exception error) {
    cancelOptionalContextFallback();
    failure = error;
    readyCallbacks.clear();
    loadedCallback = null;
    if (errorListener != null) {
      errorListener.onMapError(error);
    } else {
      TextView message = new TextView(getContext());
      message.setText(R.string.MapView__could_not_load_map);
      message.setGravity(Gravity.CENTER);
      addView(message, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }
  }

  public void onCreate(@Nullable Bundle state) {
    savedState = state;
    initialize();
  }

  public void onStart() {
    if (started) return;
    started = true;
    if (renderer != null) renderer.onStart();
  }

  public void onResume() {
    if (resumed) return;
    resumed = true;
    if (renderer != null) renderer.onResume();
    scheduleOptionalContextFallback();
    dispatchLoadedCallback();
  }

  public void onPause() {
    if (!resumed) return;
    resumed = false;
    cancelOptionalContextFallback();
    if (renderer != null) renderer.onPause();
  }

  public void onStop() {
    if (!started) return;
    started = false;
    if (renderer != null) renderer.onStop();
  }

  public void onDestroy() {
    cancelOptionalContextFallback();
    OnMapErrorListener listener = errorListener;
    errorListener = null;
    generation++;
    readyCallbacks.clear();
    loadedCallback = null;
    onPause();
    onStop();
    if (renderer != null) renderer.onDestroy();
    renderer = null;
    googleMap = null;
    attribution = null;
    zoomControls = null;
    savedState = null;
    failure = null;
    fullyRendered = false;
    initializing = false;
    removeAllViews();
    if (listener != null) listener.onMapError(new IOException("Map preview was closed"));
  }

  public void onLowMemory() {
    if (renderer != null) renderer.onLowMemory();
  }

  public void onSaveInstanceState(@NonNull Bundle state) {
    if (renderer != null) renderer.onSaveInstanceState(state);
  }

  @Override
  protected void onDetachedFromWindow() {
    onDestroy();
    super.onDetachedFromWindow();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  public interface OnMapErrorListener {
    void onMapError(@NonNull Exception error);
  }
}

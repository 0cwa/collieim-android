package org.thoughtcrime.securesms.components.location;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;

import org.signal.core.util.concurrent.ListenableFuture;
import org.signal.core.util.concurrent.SettableFuture;
import org.thoughtcrime.securesms.R;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public class SignalMapView extends LinearLayout {

  private static final long SNAPSHOT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(20);

  private MapView   mapView;
  private ImageView imageView;
  private TextView  textView;
  private long      displayGeneration;

  public SignalMapView(Context context) {
    this(context, null);
  }

  public SignalMapView(Context context, AttributeSet attrs) {
    super(context, attrs);
    initialize(context);
  }

  public SignalMapView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initialize(context);
  }

  private void initialize(Context context) {
    setOrientation(LinearLayout.VERTICAL);
    LayoutInflater.from(context).inflate(R.layout.signal_map_view, this, true);

    this.mapView   = findViewById(R.id.map_view);
    this.imageView = findViewById(R.id.image_view);
    this.textView  = findViewById(R.id.address_view);
  }

  public ListenableFuture<Bitmap> display(final SignalPlace place) {
    final long generation = ++displayGeneration;

    this.imageView.setImageDrawable(null);
    this.imageView.setVisibility(View.GONE);
    this.textView.setText(place.getDescription());
    ListenableFuture<Bitmap> future = snapshot(place, mapView);
    future.addListener(new ListenableFuture.Listener<Bitmap>() {
      @Override
      public void onSuccess(Bitmap result) {
        if (displayGeneration == generation) {
          imageView.setImageBitmap(result);
          imageView.setVisibility(View.VISIBLE);
        }
      }

      @Override
      public void onFailure(ExecutionException e) {
        // The caller receives the failure through the same future.
      }
    });

    return future;
  }

  @Override
  protected void onDetachedFromWindow() {
    displayGeneration++;
    mapView.onDestroy();
    super.onDetachedFromWindow();
  }

  public static ListenableFuture<Bitmap> snapshot(final LatLng place, @NonNull final MapView mapView) {
    SnapshotOperation operation = new SnapshotOperation(place, mapView);
    operation.start();
    return operation.future;
  }

  public static ListenableFuture<Bitmap> snapshot(final SignalPlace place, @NonNull final MapView mapView) {
    return snapshot(place.getLatLong(), mapView);
  }

  private static final class SnapshotOperation implements View.OnAttachStateChangeListener {

    private final LatLng                 place;
    private final MapView                mapView;
    private final Handler                handler   = new Handler(Looper.getMainLooper());
    private final AtomicBoolean          completed = new AtomicBoolean();
    private final SettableFuture<Bitmap> future    = new SettableFuture<>();
    private final Runnable               timeout   = () -> fail(new TimeoutException("Map snapshot timed out"));

    private @Nullable GoogleMap googleMap;

    private SnapshotOperation(LatLng place, MapView mapView) {
      this.place   = place;
      this.mapView = mapView;
    }

    private void start() {
      runOnMain(() -> {
        handler.postDelayed(timeout, SNAPSHOT_TIMEOUT_MILLIS);

        try {
          // This also cancels any previous snapshot using this reusable view.
          mapView.onDestroy();
          mapView.setOnMapErrorListener(this::fail);
          mapView.addOnAttachStateChangeListener(this);
          mapView.onCreate(null);
          if (completed.get()) return;
          mapView.onStart();
          mapView.onResume();
          if (completed.get()) return;
          mapView.setVisibility(View.VISIBLE);
          mapView.getMapAsync(map -> runOnMain(() -> onMapReady(map)));
        } catch (RuntimeException error) {
          fail(error);
        }
      });
    }

    private void onMapReady(GoogleMap map) {
      if (completed.get()) return;

      googleMap = map;

      try {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(place, 13));
        map.addMarker(new MarkerOptions().position(place));
        map.setBuildingsEnabled(true);
        map.setMapType(GoogleMap.MAP_TYPE_NORMAL);
        map.getUiSettings().setAllGesturesEnabled(false);
        map.setOnMapLoadedCallback(() -> {
          if (completed.get()) return;
          map.setOnMapLoadedCallback(null);
          try {
            map.snapshot(bitmap -> {
              if (bitmap == null) {
                fail(new IllegalStateException("Map snapshot was empty"));
              } else {
                finish(bitmap, null);
              }
            });
          } catch (RuntimeException error) {
            fail(error);
          }
        });
      } catch (RuntimeException error) {
        fail(error);
      }
    }

    private void fail(Exception error) {
      finish(null, error);
    }

    private void finish(@Nullable Bitmap bitmap, @Nullable Exception error) {
      runOnMain(() -> {
        if (!completed.compareAndSet(false, true)) return;

        handler.removeCallbacks(timeout);
        mapView.removeOnAttachStateChangeListener(this);
        mapView.setOnMapErrorListener(null);

        Exception failure = error;
        try {
          try {
            if (googleMap != null) googleMap.setOnMapLoadedCallback(null);
          } finally {
            mapView.setVisibility(View.GONE);
            try {
              mapView.onPause();
              mapView.onStop();
            } finally {
              mapView.onDestroy();
            }
          }
        } catch (RuntimeException cleanupError) {
          if (failure == null) failure = cleanupError;
          else                 failure.addSuppressed(cleanupError);
        }

        if (failure != null) future.setException(failure);
        else                 future.set(bitmap);
      });
    }

    private void runOnMain(Runnable runnable) {
      if (Looper.myLooper() == Looper.getMainLooper()) {
        runnable.run();
      } else {
        handler.post(runnable);
      }
    }

    @Override
    public void onViewAttachedToWindow(View view) { }

    @Override
    public void onViewDetachedFromWindow(View view) {
      fail(new CancellationException("Map view detached before snapshot completed"));
    }
  }
}

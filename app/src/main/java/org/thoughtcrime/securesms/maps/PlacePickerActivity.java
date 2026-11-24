package org.thoughtcrime.securesms.maps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import androidx.activity.EdgeToEdge;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MapStyleOptions;

import org.signal.core.util.bitmaps.BitmapUtil;
import org.signal.core.util.concurrent.ListenableFuture;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.location.SignalMapView;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.net.Networking;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;
import org.thoughtcrime.securesms.util.DynamicTheme;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.util.views.SimpleProgressDialog;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;

/**
 * Allows selection of an address from the OpenStreetMap raster map.
 * <p>
 * Based on https://github.com/suchoX/PlacePicker
 */
public final class PlacePickerActivity extends AppCompatActivity {

  private static final String TAG = Log.tag(PlacePickerActivity.class);

  // If it cannot load location for any reason, it defaults to the prime meridian.
  private static final LatLng PRIME_MERIDIAN = new LatLng(51.4779, -0.0015);
  private static final String ADDRESS_INTENT = "ADDRESS";
  private static final float  ZOOM           = 17.0f;

  private static final int                   ANIMATION_DURATION     = 250;
  private static final OvershootInterpolator OVERSHOOT_INTERPOLATOR = new OvershootInterpolator();
  public  static final String                KEY_CHAT_COLOR         = "chat_color";

  private final DynamicTheme dynamicTheme = new DynamicNoActionBarTheme();

  private SingleAddressBottomSheet bottomSheet;
  private Address                  currentAddress;
  private LatLng                   initialLocation = PRIME_MERIDIAN;
  private LatLng                   currentLocation = new LatLng(0, 0);
  private long                     addressLookupId;
  private BooleanSupplier          addressRoute;
  private GoogleMap                googleMap;
  private Insets                   systemBarInsets;
  private @Nullable SimpleProgressDialog.DismissibleDialog snapshotProgress;

  public static void startActivityForResultAtCurrentLocation(@NonNull Fragment fragment, int requestCode, @ColorInt int chatColor) {
    fragment.startActivityForResult(new Intent(fragment.requireActivity(), PlacePickerActivity.class).putExtra(KEY_CHAT_COLOR, chatColor), requestCode);
  }

  public static AddressData addressFromData(@NonNull Intent data) {
    return data.getParcelableExtra(ADDRESS_INTENT);
  }

  @SuppressLint("MissingInflatedId")
  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    EdgeToEdge.enable(this);
    super.onCreate(savedInstanceState);
    dynamicTheme.onCreate(this);

    setContentView(R.layout.activity_place_picker);

    bottomSheet      = findViewById(R.id.bottom_sheet);
    View markerImage = findViewById(R.id.marker_image_view);
    View fab         = findViewById(R.id.place_chosen_button);
    // MapLibre initializes asynchronously. Do not send the placeholder coordinate
    // before a camera target has been installed on the map.
    fab.setEnabled(false);

    ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (view, insets) -> {
      systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars());
      bottomSheet.setPadding(systemBarInsets.left, 0, systemBarInsets.right, systemBarInsets.bottom);
      applyMapPadding();
      float markerOffsetX = (systemBarInsets.left - systemBarInsets.right) / 2f;
      float markerOffsetY = getMapMarkerOffsetY();
      markerImage.setTranslationX(markerOffsetX);
      markerImage.setTranslationY(markerOffsetY);
      View markerShadow = findViewById(R.id.marker_shadow_image_view);
      markerShadow.setTranslationX(markerOffsetX);
      markerShadow.setTranslationY(markerOffsetY);
      return insets;
    });

    ViewCompat.setBackgroundTintList(fab, ColorStateList.valueOf(getIntent().getIntExtra(KEY_CHAT_COLOR, Color.RED)));
    fab.setOnClickListener(v -> finishWithAddress());

    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    {
      new LocationRetriever(this, this, location -> {
        setInitialLocation(new LatLng(location.getLatitude(), location.getLongitude()));
      }, () -> {
        Log.w(TAG, "Failed to get location.");
        setInitialLocation(PRIME_MERIDIAN);
      });
    } else {
      Log.w(TAG, "No location permissions");
      setInitialLocation(PRIME_MERIDIAN);
    }

    SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
    if (mapFragment == null) throw new AssertionError("No map fragment");

    mapFragment.getMapAsync(googleMap -> {
      setMap(googleMap);
      if (DynamicTheme.isDarkTheme(this)) {
        try {
          boolean success = googleMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style));

          if (!success) {
            Log.e(TAG, "Style parsing failed.");
          }
        } catch (Resources.NotFoundException e) {
          Log.e(TAG, "Can't find style. Error: ", e);
        }
      }

      enableMyLocationButtonIfHaveThePermission(googleMap);

      googleMap.setOnCameraMoveStartedListener(i -> {
        fab.setEnabled(false);
        markerImage.animate()
                   .translationY(getMapMarkerOffsetY() - 75f)
                   .setInterpolator(OVERSHOOT_INTERPOLATOR)
                   .setDuration(ANIMATION_DURATION)
                   .start();

        bottomSheet.hide();
      });

      googleMap.setOnCameraIdleListener(() -> {
        markerImage.animate()
                   .translationY(getMapMarkerOffsetY())
                   .setInterpolator(OVERSHOOT_INTERPOLATOR)
                   .setDuration(ANIMATION_DURATION)
                   .start();

        setCurrentLocation(googleMap.getCameraPosition().target);
      });
    });
  }

  @Override
  protected void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
    if (googleMap != null) enableMyLocationButtonIfHaveThePermission(googleMap);
  }

  private void setInitialLocation(@NonNull LatLng latLng) {
    initialLocation = latLng;

    moveMapToInitialIfPossible();
  }

  private void setMap(GoogleMap googleMap) {
    this.googleMap = googleMap;

    applyMapPadding();
    moveMapToInitialIfPossible();
  }

  private void applyMapPadding() {
    if (googleMap != null && systemBarInsets != null) {
      googleMap.setPadding(systemBarInsets.left, systemBarInsets.top, systemBarInsets.right, systemBarInsets.bottom);
    }
  }

  private float getMapMarkerOffsetY() {
    return systemBarInsets == null ? 0f : (systemBarInsets.top - systemBarInsets.bottom) / 2f;
  }

  private void moveMapToInitialIfPossible() {
    if (initialLocation != null && googleMap != null) {
      Log.d(TAG, "Moving map to initial location");
      googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(initialLocation, ZOOM));
      setCurrentLocation(initialLocation);
    }
  }

  private void setCurrentLocation(LatLng location) {
    currentLocation = location;
    findViewById(R.id.place_chosen_button).setEnabled(true);
    bottomSheet.showLoading();
    lookupAddress(location);
  }

  private void finishWithAddress() {
    if (snapshotProgress != null) return;

    Intent      returnIntent = new Intent();
    String      address      = addressRoute != null && addressRoute.getAsBoolean() ? Objects.requireNonNullElse(addressToString(currentAddress), "") : "";
    AddressData addressData  = new AddressData(currentLocation.latitude, currentLocation.longitude, address);

    SimpleProgressDialog.DismissibleDialog progress = SimpleProgressDialog.showDelayed(this);
    snapshotProgress = progress;
    MapView mapView = findViewById(R.id.map_view);
    SignalMapView.snapshot(currentLocation, mapView).addListener(new ListenableFuture.Listener<>() {
      @Override
      public void onSuccess(Bitmap result) {
        if (snapshotProgress != progress) return;
        clearSnapshotProgress();
        if (isFinishing() || isDestroyed()) return;

        byte[] blob = BitmapUtil.toByteArray(result);
        Uri uri = AppDependencies.getBlobs()
                              .forData(blob)
                              .withMimeType(MediaUtil.IMAGE_JPEG)
                              .createForSingleSessionInMemory();
        returnIntent.putExtra(ADDRESS_INTENT, addressData);
        returnIntent.setData(uri);
        setResult(RESULT_OK, returnIntent);
        finish();
      }

      @Override
      public void onFailure(ExecutionException e) {
        if (snapshotProgress != progress) return;
        clearSnapshotProgress();
        Log.e(TAG, "Failed to generate snapshot", e);
      }
    });
  }

  private void clearSnapshotProgress() {
    if (snapshotProgress != null) {
      snapshotProgress.dismissNow();
      snapshotProgress = null;
    }
  }

  @Override
  protected void onDestroy() {
    clearSnapshotProgress();
    super.onDestroy();
  }

  private void enableMyLocationButtonIfHaveThePermission(GoogleMap googleMap) {
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
      googleMap.setMyLocationEnabled(true);
    }
  }

  private void lookupAddress(@Nullable LatLng target) {
    long lookupId = ++addressLookupId;
    currentAddress = null;
    addressRoute = null;
    // Older Android versions only expose a blocking lookup. Keep coordinate-only picking there.
    if (target == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !Geocoder.isPresent() || !startAddressLookup(target, lookupId)) {
      bottomSheet.hide();
    }
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private boolean startAddressLookup(@NonNull LatLng target, long lookupId) {
    Geocoder geocoder = new Geocoder(getApplicationContext(), Locale.getDefault());
    return Networking.dispatchPlatformGeocoding(routeIsCurrent -> {
      geocoder.getFromLocation(target.latitude, target.longitude, 1, new Geocoder.GeocodeListener() {
        @Override
        public void onGeocode(@NonNull List<Address> addresses) {
          runOnUiThread(() -> finishAddressLookup(lookupId, routeIsCurrent, addresses.isEmpty() ? null : addresses.get(0)));
        }

        @Override
        public void onError(@Nullable String errorMessage) {
          runOnUiThread(() -> finishAddressLookup(lookupId, routeIsCurrent, null));
        }
      });
    });
  }

  private void finishAddressLookup(long lookupId, BooleanSupplier routeIsCurrent, @Nullable Address address) {
    if (lookupId != addressLookupId || isFinishing() || isDestroyed()) return;
    currentAddress = routeIsCurrent.getAsBoolean() ? address : null;
    addressRoute = routeIsCurrent;
    if (currentAddress != null) {
      bottomSheet.showResult(currentAddress.getLatitude(), currentAddress.getLongitude(),
                             addressToShortString(currentAddress), addressToString(currentAddress));
    } else {
      bottomSheet.hide();
    }
  }

  @SuppressLint("MissingPermission")
  @Override
  protected void onPause() {
    super.onPause();
    ++addressLookupId;
    if (googleMap != null && (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)) {
      googleMap.setMyLocationEnabled(false);
    }
  }

  private static @NonNull String addressToString(@Nullable Address address) {
    return address != null ? address.getAddressLine(0) : "";
  }

  private static @NonNull String addressToShortString(@Nullable Address address) {
    if (address == null) return "";

    String   addressLine = Objects.requireNonNullElse(address.getAddressLine(0), "");
    String[] split       = addressLine.split(",");

    if (split.length >= 3) {
      return split[1].trim() + ", " + split[2].trim();
    } else if (split.length == 2) {
      return split[1].trim();
    } else return split[0].trim();
  }
}

package com.google.android.gms.maps;

import android.app.Activity;
import android.app.Application;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.shadows.ShadowLooper.idleMainLooper;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class MapViewCallbackTest {
  private ActivityController<Activity> controller;
  private MapView mapView;
  private final List<String> callbacks = new ArrayList<>();

  @Before
  public void setUp() {
    controller = Robolectric.buildActivity(Activity.class).setup();
    mapView = new MapView(controller.get());
    controller.get().setContentView(mapView);
    idleMainLooper();
    assertTrue(mapView.isAttachedToWindow());

    // Exercise the wrapper's dispatch queue without constructing a native renderer.
    ReflectionHelpers.setField(mapView, "resumed", true);
    ReflectionHelpers.setField(mapView, "fullyRendered", true);
  }

  @After
  public void tearDown() {
    mapView.onDestroy();
    controller.pause().stop().destroy();
  }

  @Test
  public void replacementBeforePostedDeliveryOnlyInvokesReplacement() {
    mapView.setOnMapLoadedCallback(() -> callbacks.add("original"));
    mapView.setOnMapLoadedCallback(() -> callbacks.add("replacement"));

    assertTrue(callbacks.isEmpty());
    idleMainLooper();

    assertEquals(Collections.singletonList("replacement"), callbacks);
  }

  @Test
  public void cancellationBeforePostedDeliveryDoesNotInvokeCallback() {
    mapView.setOnMapLoadedCallback(() -> callbacks.add("cancelled"));
    mapView.setOnMapLoadedCallback(null);

    idleMainLooper();

    assertTrue(callbacks.isEmpty());
  }

  @Test
  public void postedCallbackCannotOverwriteReplacementWhilePaused() {
    mapView.setOnMapLoadedCallback(() -> callbacks.add("original"));
    mapView.onPause();
    mapView.setOnMapLoadedCallback(() -> callbacks.add("replacement"));

    idleMainLooper();
    assertTrue(callbacks.isEmpty());

    mapView.onResume();
    idleMainLooper();

    assertEquals(Collections.singletonList("replacement"), callbacks);
  }

  @Test
  public void invalidatedFrameDefersPostedCallbackUntilAnotherCompleteFrame() {
    mapView.setOnMapLoadedCallback(() -> callbacks.add("loaded"));
    mapView.invalidateRenderedFrame();

    idleMainLooper();
    assertTrue(callbacks.isEmpty());

    // Supply the same state change as a later complete-frame notification.
    ReflectionHelpers.setField(mapView, "fullyRendered", true);
    ReflectionHelpers.callInstanceMethod(mapView, "dispatchLoadedCallback");
    idleMainLooper();

    assertEquals(Collections.singletonList("loaded"), callbacks);
  }
}

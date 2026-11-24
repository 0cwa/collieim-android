package org.thoughtcrime.securesms.maps;

import android.content.Context;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.maplibre.android.MapLibre;
import org.maplibre.android.log.Logger;
import org.maplibre.android.module.http.HttpRequestUtil;
import org.maplibre.android.offline.OfflineManager;
import org.maplibre.android.offline.OfflineRegion;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Installs Collie's transport and disables the native plaintext resource cache before any map loads. */
@MainThread
public final class MapLibreConfiguration {
  private enum State { NEW, PREPARING, READY, FAILED }

  private static final List<Callback> callbacks = new ArrayList<>();
  private static State state = State.NEW;

  private MapLibreConfiguration() {}

  public static void prepare(@NonNull Context context, @NonNull Callback callback) {
    if (state == State.READY) {
      callback.onReady();
      return;
    }
    if (state == State.FAILED) {
      callback.onError(new IOException("Private map storage could not be initialized"));
      return;
    }

    callbacks.add(callback);
    if (state == State.PREPARING) return;
    state = State.PREPARING;

    Context application = context.getApplicationContext();
    MapLibre.getInstance(application);
    Logger.setVerbosity(Logger.NONE);
    HttpRequestUtil.setOkHttpClient(MapResourceClient.create(application));
    HttpRequestUtil.setLogEnabled(false);
    HttpRequestUtil.setPrintRequestUrlOnFailure(false);

    OfflineManager manager = OfflineManager.getInstance(application);
    // A zero ambient limit disables database reads/writes only when no offline
    // regions exist. Collie never creates regions; refuse an unexpected database.
    manager.listOfflineRegions(new OfflineManager.ListOfflineRegionsCallback() {
      @Override
      public void onList(@Nullable OfflineRegion[] regions) {
        if (regions != null && regions.length != 0) {
          complete(false);
          return;
        }
        manager.setMaximumAmbientCacheSize(0, new OfflineManager.FileSourceCallback() {
          @Override
          public void onSuccess() {
            complete(true);
          }

          @Override
          public void onError(@NonNull String message) {
            complete(false);
          }
        });
      }

      @Override
      public void onError(@NonNull String message) {
        complete(false);
      }
    });
  }

  private static void complete(boolean ready) {
    state = ready ? State.READY : State.FAILED;
    List<Callback> pending = new ArrayList<>(callbacks);
    callbacks.clear();
    for (Callback callback : pending) {
      if (ready) callback.onReady();
      else callback.onError(new IOException("Private map storage could not be initialized"));
    }
  }

  public interface Callback {
    void onReady();
    void onError(@NonNull Exception error);
  }
}

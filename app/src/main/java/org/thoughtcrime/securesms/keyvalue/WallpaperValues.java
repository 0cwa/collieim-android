package org.thoughtcrime.securesms.keyvalue;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.logging.Log;
import org.signal.storage.keyvalue.KeyValueStore;
import org.signal.storage.keyvalue.SignalStoreValues;
import org.thoughtcrime.securesms.database.model.databaseprotos.Wallpaper;
import org.thoughtcrime.securesms.wallpaper.ChatWallpaper;
import org.thoughtcrime.securesms.wallpaper.ChatWallpaperFactory;
import org.thoughtcrime.securesms.wallpaper.WallpaperStorage;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WallpaperValues extends SignalStoreValues {

  private static final String TAG = Log.tag(WallpaperValues.class);

  private static final String KEY_WALLPAPER = "wallpaper.wallpaper";

  /**
   * Local-only colors derived from photo wallpapers, keyed by wallpaper attachment ID. Excluded from
   * {@link #getKeysToIncludeInBackup()} and never synced; see {@link org.thoughtcrime.securesms.wallpaper.PhotoWallpaperColors}.
   * Encoded as {@code attachmentId,color,fingerprint} entries separated by {@code ;}.
   */
  private static final String KEY_PHOTO_AUTO_COLORS = "wallpaper.photo_auto_colors";

  private final Object photoColorLock = new Object();

  private volatile ParsedPhotoColors parsedPhotoColors = new ParsedPhotoColors(null, Collections.emptyMap());

  WallpaperValues(@NonNull KeyValueStore store) {
    super(store);
    readPhotoColors();
  }

  @Override
  public void onFirstEverAppLaunch() {
  }

  @Override
  public @NonNull List<String> getKeysToIncludeInBackup() {
    return Collections.emptyList();
  }

  public void setWallpaper(@Nullable ChatWallpaper wallpaper) {
    Wallpaper currentWallpaper = getCurrentRawWallpaper();
    Uri       currentUri       = null;

    if (currentWallpaper != null && currentWallpaper.file_ != null) {
      currentUri = Uri.parse(currentWallpaper.file_.uri);
    }

    if (wallpaper != null) {
      putBlob(KEY_WALLPAPER, wallpaper.serialize().encode());
    } else {
      getStore().beginWrite().remove(KEY_WALLPAPER).apply();
    }

    if (currentUri != null) {
      WallpaperStorage.onWallpaperDeselected(currentUri);
    }
  }

  public @Nullable ChatWallpaper getWallpaper() {
    Wallpaper currentWallpaper = getCurrentRawWallpaper();

    if (currentWallpaper != null) {
      return ChatWallpaperFactory.create(currentWallpaper);
    } else {
      return null;
    }
  }

  public boolean hasWallpaperSet() {
    return getStore().getBlob(KEY_WALLPAPER, null) != null;
  }

  public void setDimInDarkTheme(boolean enabled) {
    Wallpaper currentWallpaper = getCurrentRawWallpaper();

    if (currentWallpaper != null) {
      putBlob(KEY_WALLPAPER,
              currentWallpaper.newBuilder()
                              .dimLevelInDarkTheme(enabled ? 0.2f : 0)
                              .build()
                              .encode());
    } else {
      throw new IllegalStateException("No wallpaper currently set!");
    }
  }


  /**
   * Retrieves the URI of the current wallpaper. Note that this will only return a value if the
   * wallpaper is both set *and* it's an image.
   */
  public @Nullable Uri getWallpaperUri() {
    Wallpaper currentWallpaper = getCurrentRawWallpaper();

    if (currentWallpaper != null && currentWallpaper.file_ != null) {
      return Uri.parse(currentWallpaper.file_.uri);
    } else {
      return null;
    }
  }

  /**
   * Allows for retrieval of the raw, serialized wallpaper proto. Clients should prefer {@link #getWallpaper()} instead.
   */
  public @Nullable Wallpaper getCurrentRawWallpaper() {
    byte[] serialized = getBlob(KEY_WALLPAPER, null);

    if (serialized != null) {
      try {
        return Wallpaper.ADAPTER.decode(serialized);
      } catch (IOException e) {
        Log.w(TAG, "Invalid proto stored for wallpaper!");
        return null;
      }
    } else {
      return null;
    }
  }

  /** A derived photo wallpaper color. {@code color} is 0 when sampling found nothing usable. */
  public static final class PhotoAutoColor {
    public final int color;
    public final int fingerprint;

    public PhotoAutoColor(int color, int fingerprint) {
      this.color       = color;
      this.fingerprint = fingerprint;
    }
  }

  /** Reads only the in-memory key-value cache; safe to call from synchronous color getters. */
  public @Nullable PhotoAutoColor getPhotoAutoColor(long attachmentId) {
    return parsedPhotoColors.colors.get(attachmentId);
  }

  public void setPhotoAutoColor(long attachmentId, int color, int fingerprint) {
    synchronized (photoColorLock) {
      Map<Long, PhotoAutoColor> updated = new LinkedHashMap<>(readPhotoColors());
      updated.put(attachmentId, new PhotoAutoColor(color, fingerprint));
      writePhotoColors(updated);
    }
  }

  public void removePhotoAutoColor(long attachmentId) {
    synchronized (photoColorLock) {
      Map<Long, PhotoAutoColor> current = readPhotoColors();
      if (current.containsKey(attachmentId)) {
        Map<Long, PhotoAutoColor> updated = new LinkedHashMap<>(current);
        updated.remove(attachmentId);
        writePhotoColors(updated);
      }
    }
  }

  public void clearPhotoAutoColors() {
    synchronized (photoColorLock) {
      writePhotoColors(Collections.emptyMap());
    }
  }

  private @NonNull Map<Long, PhotoAutoColor> readPhotoColors() {
    String            raw    = getString(KEY_PHOTO_AUTO_COLORS, null);
    ParsedPhotoColors cached = parsedPhotoColors;
    if (Objects.equals(raw, cached.raw)) {
      return cached.colors;
    }

    Map<Long, PhotoAutoColor> parsed = new LinkedHashMap<>();
    if (raw != null && !raw.isEmpty()) {
      for (String entry : raw.split(";")) {
        String[] parts = entry.split(",");
        if (parts.length != 3) continue;
        try {
          parsed.put(Long.parseLong(parts[0]), new PhotoAutoColor(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
        } catch (NumberFormatException e) {
          Log.w(TAG, "Skipping malformed photo wallpaper color entry.");
        }
      }
    }

    Map<Long, PhotoAutoColor> result = Collections.unmodifiableMap(parsed);
    parsedPhotoColors = new ParsedPhotoColors(raw, result);
    return result;
  }

  private static final class ParsedPhotoColors {
    final @Nullable String                    raw;
    final @NonNull  Map<Long, PhotoAutoColor> colors;

    ParsedPhotoColors(@Nullable String raw, @NonNull Map<Long, PhotoAutoColor> colors) {
      this.raw    = raw;
      this.colors = colors;
    }
  }

  private void writePhotoColors(@NonNull Map<Long, PhotoAutoColor> colors) {
    if (colors.isEmpty()) {
      getStore().beginWrite().remove(KEY_PHOTO_AUTO_COLORS).apply();
      parsedPhotoColors = new ParsedPhotoColors(null, Collections.emptyMap());
      return;
    }

    StringBuilder builder = new StringBuilder();
    for (Map.Entry<Long, PhotoAutoColor> entry : colors.entrySet()) {
      if (builder.length() > 0) builder.append(';');
      builder.append(entry.getKey()).append(',').append(entry.getValue().color).append(',').append(entry.getValue().fingerprint);
    }
    String raw = builder.toString();
    putString(KEY_PHOTO_AUTO_COLORS, raw);
    parsedPhotoColors = new ParsedPhotoColors(raw, Collections.unmodifiableMap(colors));
  }

  /**
   * For a migration, we need to update the current wallpaper _without_ triggering the onDeselectedEvents and such.
   * For normal usage, use {@link #setWallpaper(ChatWallpaper)}
   */
  public void setRawWallpaperForMigration(@NonNull Wallpaper wallpaper) {
    putBlob(KEY_WALLPAPER, wallpaper.encode());
  }
}

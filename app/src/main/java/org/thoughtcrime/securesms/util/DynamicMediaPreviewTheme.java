package org.thoughtcrime.securesms.util;

import androidx.annotation.NonNull;
import androidx.annotation.StyleRes;

import org.thoughtcrime.securesms.R;

public class DynamicMediaPreviewTheme extends DynamicTheme {

  protected @StyleRes int getRegularTheme() {
    return R.style.TextSecure_MediaPreview;
  }

  protected @StyleRes int getDynamicTheme() {
    return R.style.Theme_Molly_Dynamic_MediaPreview;
  }

  /** The media preview window is always dark, so a palette applies its dark roles even in light mode. */
  @Override
  protected @NonNull PalettePolicy getPalettePolicy() {
    return PalettePolicy.ALWAYS_DARK;
  }
}

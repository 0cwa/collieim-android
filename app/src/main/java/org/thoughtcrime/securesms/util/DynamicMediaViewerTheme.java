package org.thoughtcrime.securesms.util;

import androidx.annotation.StyleRes;

import org.thoughtcrime.securesms.R;

/** Keeps the media viewer translucent while applying the selected palette. */
public class DynamicMediaViewerTheme extends DynamicTheme {

  @Override
  protected @StyleRes int getRegularTheme() {
    return R.style.Signal_DayNight_NoActionBar_MediaViewer;
  }

  @Override
  protected @StyleRes int getDynamicTheme() {
    return R.style.Theme_Molly_Dynamic_NoActionBar_MediaViewer;
  }
}

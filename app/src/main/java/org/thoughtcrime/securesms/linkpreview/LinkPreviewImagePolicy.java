/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.calls.links.CallLinks;
import org.thoughtcrime.securesms.groups.v2.GroupInviteLinkUrl;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.stickers.StickerUrl;

/** The local image preference for newly composed web previews, including restored drafts. */
public final class LinkPreviewImagePolicy {

  private LinkPreviewImagePolicy() {}

  public static @Nullable LinkPreview sanitize(@Nullable LinkPreview preview) {
    if (preview == null) {
      return null;
    }

    return sanitize(preview, SignalStore.settings().isWebLinkPreviewImagesEnabled());
  }

  public static @Nullable LinkPreview sanitize(@Nullable LinkPreview preview, boolean includeImages) {
    if (preview == null || includeImages || !isWebLinkPreview(preview.getUrl())) {
      return preview;
    }

    if (preview.getTitle().trim().isEmpty() && preview.getDescription().trim().isEmpty()) {
      return null;
    }

    return preview.withoutThumbnail();
  }

  /** Keep the same special-card routing as LinkPreviewRepository. */
  public static boolean isWebLinkPreview(@NonNull String url) {
    return !StickerUrl.isValidShareLink(url) &&
           !GroupInviteLinkUrl.isGroupLink(url) &&
           !CallLinks.isCallLink(url);
  }
}

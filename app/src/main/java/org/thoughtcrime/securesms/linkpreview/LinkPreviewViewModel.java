package org.thoughtcrime.securesms.linkpreview;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.signal.core.util.ThreadUtil;
import org.signal.core.models.database.AttachmentId;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.net.RequestController;
import org.signal.core.util.Debouncer;

import java.util.Collections;
import java.util.List;
import java.util.Optional;


public class LinkPreviewViewModel extends ViewModel {

  private final LinkPreviewRepository             repository;
  private final MutableLiveData<LinkPreviewState> linkPreviewState;
  private final LiveData<LinkPreviewState>        linkPreviewSafeState;

  private String            activeUrl;
  private RequestController activeRequest;
  private boolean           userCanceled;
  private Debouncer         debouncer;
  private boolean           enabled;
  private boolean           webImagesEnabled;
  private long              requestGeneration;

  private final boolean enablePlaceholder;

  private LinkPreviewViewModel(@NonNull LinkPreviewRepository repository, boolean enablePlaceholder) {
    this.repository           = repository;
    this.enablePlaceholder    = enablePlaceholder;
    this.linkPreviewState     = new MutableLiveData<>();
    this.debouncer            = new Debouncer(250);
    this.enabled              = SignalStore.settings().isLinkPreviewsEnabled();
    this.webImagesEnabled     = SignalStore.settings().isWebLinkPreviewImagesEnabled();
    this.linkPreviewSafeState = Transformations.map(linkPreviewState, state -> cleanseState(state));
  }

  public LiveData<LinkPreviewState> getLinkPreviewState() {
    return linkPreviewSafeState;
  }

  /**
   * Gets the current state for use in the UI, then resets local state to prepare for the next message send.
   */
  public @NonNull List<LinkPreview> onSend() {
    final LinkPreviewState currentState = linkPreviewState.getValue() == null ? null : cleanseState(linkPreviewState.getValue());

    cancelActiveRequest();

    userCanceled = false;
    activeUrl    = null;

    debouncer.clear();
    linkPreviewState.setValue(LinkPreviewState.forNoLinks());

    if (currentState == null || !currentState.linkPreview.isPresent()) {
      return Collections.emptyList();
    } else {
      return Collections.singletonList(currentState.linkPreview.get());
    }
  }

  /**
   * Gets the current state for use in the UI, then resets local state to prepare for the next message send.
   */
  public @NonNull List<LinkPreview> onSendWithErrorUrl() {
    final LinkPreviewState currentState = linkPreviewState.getValue() == null ? null : cleanseState(linkPreviewState.getValue());

    cancelActiveRequest();

    userCanceled = false;
    activeUrl    = null;

    debouncer.clear();
    linkPreviewState.setValue(LinkPreviewState.forNoLinks());

    if (currentState == null) {
      return Collections.emptyList();
    } else if (currentState.linkPreview.isPresent()) {
      return Collections.singletonList(currentState.linkPreview.get());
    } else if (currentState.activeUrlForError != null) {
      String       topLevelDomain = LinkPreviewUtil.getTopLevelDomain(currentState.activeUrlForError);
      AttachmentId attachmentId   = null;

      return Collections.singletonList(new LinkPreview(currentState.activeUrlForError,
                                                       topLevelDomain != null ? topLevelDomain : currentState.activeUrlForError,
                                                       null,
                                                       -1L,
                                                       attachmentId));
    } else {
      return Collections.emptyList();
    }
  }

  public void onTextChanged(@NonNull String text, int cursorStart, int cursorEnd) {
    refreshImagePreference();
    if (!enabled && !enablePlaceholder) return;

    debouncer.publish(() -> {
      if (TextUtils.isEmpty(text)) {
        userCanceled = false;
      }

      if (userCanceled) {
        return;
      }

      Optional<Link> link = LinkPreviewUtil.findValidPreviewUrls(text)
                                           .findFirst();

      if (link.isPresent() && link.get().url.equals(activeUrl)) {
        return;
      }

      cancelActiveRequest();

      if (!link.isPresent() || !isCursorPositionValid(text, link.get(), cursorStart, cursorEnd)) {
        activeUrl = null;
        linkPreviewState.setValue(LinkPreviewState.forNoLinks());
        return;
      }

      linkPreviewState.setValue(LinkPreviewState.forLoading(link.get()));

      activeUrl     = link.get().url;
      activeRequest = enabled ? performRequest(activeUrl) : createPlaceholder(activeUrl);
    });
  }

  public void onUserCancel() {
    cancelActiveRequest();

    userCanceled = true;
    activeUrl    = null;

    debouncer.clear();
    linkPreviewState.setValue(LinkPreviewState.forNoLinks());
  }

  public void onTransportChanged(boolean isSms) {
    enabled = SignalStore.settings().isLinkPreviewsEnabled() && !isSms;

    if (!enabled) {
      onUserCancel();
    }
  }

  public void onEnabled() {
    userCanceled = false;
    enabled      = SignalStore.settings().isLinkPreviewsEnabled();
    refreshImagePreference();
  }

  /** Applies a setting changed while this composer was in the background. */
  public void refreshImagePreference() {
    boolean includeImages = SignalStore.settings().isWebLinkPreviewImagesEnabled();
    if (includeImages == webImagesEnabled) {
      return;
    }

    webImagesEnabled = includeImages;
    if (activeUrl != null && !LinkPreviewImagePolicy.isWebLinkPreview(activeUrl)) {
      return;
    }

    cancelActiveRequest();
    debouncer.clear();

    LinkPreviewState currentState = linkPreviewState.getValue();
    if (currentState != null) {
      linkPreviewState.setValue(cleanseState(currentState));
    }
    if (activeUrl != null && enabled && !userCanceled && (includeImages || (currentState != null && currentState.isLoading))) {
      activeRequest = performRequest(activeUrl);
    }
  }

  @Override
  protected void onCleared() {
    cancelActiveRequest();
    debouncer.clear();
  }

  private void cancelActiveRequest() {
    // Transport cancellation cannot remove callbacks that already reached the main-thread queue.
    requestGeneration++;
    RequestController request = activeRequest;
    activeRequest = null;
    if (request != null) {
      request.cancel();
    }
  }

  private boolean isCursorPositionValid(@NonNull String text, @NonNull Link link, int cursorStart, int cursorEnd) {
    if (cursorStart != cursorEnd) {
      return true;
    }

    if (text.endsWith(link.url) && cursorStart == link.position + link.url.length()) {
      return true;
    }

    return cursorStart < link.position || cursorStart > link.position + link.url.length();
  }

  private @Nullable RequestController createPlaceholder(String url) {
    final long generation = ++requestGeneration;
    ThreadUtil.runOnMain(() -> {
      if (generation != requestGeneration) {
        return;
      }

      activeRequest = null;
      if (!userCanceled) {
        if (activeUrl != null && activeUrl.equals(url)) {
          linkPreviewState.setValue(LinkPreviewState.forLinksWithNoPreview(url, LinkPreviewRepository.Error.PREVIEW_NOT_AVAILABLE));
        } else {
          linkPreviewState.setValue(LinkPreviewState.forNoLinks());
        }
      }
    });

    return null;
  }

  private @Nullable RequestController performRequest(String url) {
    final long generation = ++requestGeneration;
    return repository.getLinkPreview(AppDependencies.getApplication(), url, new LinkPreviewRepository.Callback() {
      @Override
      public void onSuccess(@NonNull LinkPreview linkPreview) {
        ThreadUtil.runOnMain(() -> {
          if (generation != requestGeneration) {
            return;
          }

          activeRequest = null;
          if (!userCanceled) {
            if (activeUrl != null && activeUrl.equals(linkPreview.getUrl())) {
              linkPreviewState.setValue(LinkPreviewState.forPreview(linkPreview));
            } else {
              linkPreviewState.setValue(LinkPreviewState.forNoLinks());
            }
          }
        });
      }

      @Override
      public void onError(@NonNull LinkPreviewRepository.Error error) {
        ThreadUtil.runOnMain(() -> {
          if (generation != requestGeneration) {
            return;
          }

          activeRequest = null;
          if (!userCanceled) {
            if (activeUrl != null) {
              linkPreviewState.setValue(LinkPreviewState.forLinksWithNoPreview(activeUrl, error));
            } else {
              linkPreviewState.setValue(LinkPreviewState.forNoLinks());
            }
          }
        });
      }
    });
  }

  private @NonNull LinkPreviewState cleanseState(@NonNull LinkPreviewState state) {
    if (enabled) {
      if (!state.linkPreview.isPresent()) {
        return state;
      }

      LinkPreview preview = LinkPreviewImagePolicy.sanitize(state.linkPreview.get());
      return preview != null ? LinkPreviewState.forPreview(preview)
                             : LinkPreviewState.forLinksWithNoPreview(state.linkPreview.get().getUrl(), LinkPreviewRepository.Error.PREVIEW_NOT_AVAILABLE);
    }

    if (enablePlaceholder) {
      return state.linkPreview
          .map(linkPreview -> LinkPreviewState.forLinksWithNoPreview(linkPreview.getUrl(), LinkPreviewRepository.Error.PREVIEW_NOT_AVAILABLE))
          .orElse(state);
    }

    return LinkPreviewState.forNoLinks();
  }

  public static class Factory extends ViewModelProvider.NewInstanceFactory {

    private final LinkPreviewRepository repository;
    private final boolean               enablePlaceholder;

    public Factory(@NonNull LinkPreviewRepository repository) {
      this(repository, false);
    }

    public Factory(@NonNull LinkPreviewRepository repository, boolean enablePlaceholder) {
      this.repository        = repository;
      this.enablePlaceholder = enablePlaceholder;
    }

    @Override
    public @NonNull <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
      return modelClass.cast(new LinkPreviewViewModel(repository, enablePlaceholder));
    }
  }
}

package org.whispersystems.signalservice.internal.push.http;

import java.io.IOException;

/**
 * Used to communicate to observers whether or not something is canceled.
 */
public interface CancelationSignal {
  boolean isCanceled();

  default void throwIfCanceled() throws SendCanceledException {
    if (isCanceled()) {
      throw new SendCanceledException();
    }
  }

  final class SendCanceledException extends IOException {
    public SendCanceledException() {
      super("Canceled");
    }
  }
}

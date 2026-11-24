/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.runtime;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One initialization attempt per runtime, with no automatic retry or permission fallback. */
public final class SerializedCallRuntime implements CallRuntime {
  @FunctionalInterface
  public interface Initializer {
    void initialize() throws Exception;
  }

  private final Executor executor;
  private final Initializer initializer;
  private final Consumer<Throwable> failureHandler;
  private volatile State state;

  public SerializedCallRuntime(Executor executor, Initializer initializer, Consumer<Throwable> failureHandler) {
    this(executor, initializer, failureHandler, false);
  }

  public SerializedCallRuntime(Executor executor, Initializer initializer, Consumer<Throwable> failureHandler, boolean initiallyReady) {
    this.executor = executor;
    this.initializer = initializer;
    this.failureHandler = failureHandler;
    this.state = initiallyReady ? State.READY : State.COLD;
  }

  @Override
  public State getState() {
    return state;
  }

  @Override
  public Request execute(Mode mode, BooleanSupplier isAuthorized, Runnable action, Consumer<Result> complete) {
    Pending request = new Pending();
    executor.execute(() -> dispatch(request, mode, isAuthorized, action, complete));
    return request;
  }

  // The production executor is already serial. This lock also protects initialization if another
  // backend supplies a pool; cancellation never takes it and can invalidate a blocked initializer.
  private synchronized void dispatch(Pending request, Mode mode, BooleanSupplier isAuthorized, Runnable action, Consumer<Result> complete) {
    if (request.cancelled.get()) {
      complete.accept(Result.CANCELLED);
      return;
    }
    if (!isAuthorized.getAsBoolean()) {
      complete.accept(Result.DENIED);
      return;
    }
    if (state == State.FAILED) {
      complete.accept(Result.FAILED);
      return;
    }
    if (state == State.COLD) {
      if (mode == Mode.IF_RUNNING) {
        complete.accept(Result.DEFERRED);
        return;
      }
      state = State.INITIALIZING;
      try {
        initializer.initialize();
        state = State.READY;
      } catch (Exception | LinkageError failure) {
        state = State.FAILED;
        failureHandler.accept(failure);
        complete.accept(Result.FAILED);
        return;
      }
    }
    if (request.cancelled.get()) {
      complete.accept(Result.CANCELLED);
    } else if (!isAuthorized.getAsBoolean()) {
      complete.accept(Result.DENIED);
    } else {
      action.run();
      complete.accept(Result.DISPATCHED);
    }
  }

  private static final class Pending implements Request {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    @Override
    public void cancel() {
      cancelled.set(true);
    }
  }
}

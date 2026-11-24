/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.runtime;

import org.thoughtcrime.securesms.calls.permissions.CallPermissions;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Binds every backend request to Part 1, including callers using AppDependencies.callRuntime. */
public final class CallPermissionRuntime implements CallRuntime {
  private final CallRuntime backend;

  public CallPermissionRuntime(CallRuntime backend) {
    this.backend = backend;
  }

  @Override
  public State getState() {
    return backend.getState();
  }

  @Override
  public Request execute(Mode mode, BooleanSupplier isAuthorized, Runnable action, Consumer<Result> complete) {
    long generation = CallPermissions.generation();
    AtomicBoolean dispatched = new AtomicBoolean();
    AtomicBoolean cancelled = new AtomicBoolean();
    BooleanSupplier authorized = () -> !cancelled.get() && CallPermissions.isCurrent(generation) &&
                                       isAuthorized.getAsBoolean() && CallPermissions.isCurrent(generation) && !cancelled.get();
    Request pending = backend.execute(mode, authorized, () -> {
      CallPermissions.runIfCurrent(generation, () -> {
        if (!authorized.getAsBoolean()) return;
        dispatched.set(true);
        action.run();
      });
    }, result -> complete.accept(result == Result.DISPATCHED && !dispatched.get() ? (cancelled.get() ? Result.CANCELLED : Result.DENIED) : result));
    return () -> {
      cancelled.set(true);
      pending.cancel();
    };
  }
}

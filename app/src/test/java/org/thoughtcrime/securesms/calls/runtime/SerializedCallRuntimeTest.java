/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.calls.runtime;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Tests operation admission and lifecycle with an inert backend; no RingRTC classes are loaded. */
public class SerializedCallRuntimeTest {

  @Test
  public void deniedOperationNeverInitializes() {
    QueueExecutor executor = new QueueExecutor();
    AtomicInteger initializations = new AtomicInteger();
    AtomicInteger actions = new AtomicInteger();
    List<CallRuntime.Result> results = new ArrayList<>();
    CallRuntime runtime = new SerializedCallRuntime(executor, initializations::incrementAndGet, error -> fail(error.toString()));

    runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> false, actions::incrementAndGet, results::add);
    executor.drain();

    assertEquals(0, initializations.get());
    assertEquals(0, actions.get());
    assertEquals(List.of(CallRuntime.Result.DENIED), results);
    assertEquals(CallRuntime.State.COLD, runtime.getState());
  }

  @Test
  public void passiveRequestDefersWithoutInitializing() {
    QueueExecutor executor = new QueueExecutor();
    AtomicInteger initializations = new AtomicInteger();
    List<CallRuntime.Result> results = new ArrayList<>();
    CallRuntime runtime = new SerializedCallRuntime(executor, initializations::incrementAndGet, error -> fail(error.toString()));

    runtime.execute(CallRuntime.Mode.IF_RUNNING, () -> true, () -> fail("A cold background operation must be deferred"), results::add);
    executor.drain();

    assertEquals(List.of(CallRuntime.Result.DEFERRED), results);
    assertEquals(0, initializations.get());
    assertEquals(CallRuntime.State.COLD, runtime.getState());
  }

  @Test
  public void successiveAuthorizedOperationsInitializeOnlyOnce() {
    QueueExecutor executor = new QueueExecutor();
    AtomicInteger initializations = new AtomicInteger();
    List<String> actions = new ArrayList<>();
    List<CallRuntime.Result> results = new ArrayList<>();
    CallRuntime runtime = new SerializedCallRuntime(executor, initializations::incrementAndGet, error -> fail(error.toString()));

    runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> true, () -> actions.add("first"), results::add);
    runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> true, () -> actions.add("second"), results::add);
    executor.drain();

    assertEquals(1, initializations.get());
    assertEquals(List.of("first", "second"), actions);
    assertEquals(List.of(CallRuntime.Result.DISPATCHED, CallRuntime.Result.DISPATCHED), results);
    assertEquals(CallRuntime.State.READY, runtime.getState());
  }

  @Test
  public void revocationDuringInitializationPreventsDispatch() {
    QueueExecutor executor = new QueueExecutor();
    AtomicBoolean authorized = new AtomicBoolean(true);
    List<CallRuntime.Result> results = new ArrayList<>();
    CallRuntime runtime = new SerializedCallRuntime(executor, () -> authorized.set(false), error -> fail(error.toString()));

    runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, authorized::get, () -> fail("The original grant was revoked"), results::add);
    executor.drain();

    assertEquals(List.of(CallRuntime.Result.DENIED), results);
    // Initialization cannot be undone. READY is not itself an authorization grant.
    assertEquals(CallRuntime.State.READY, runtime.getState());
  }

  @Test
  public void queuedCancellationPreventsInitialization() {
    QueueExecutor executor = new QueueExecutor();
    AtomicInteger initializations = new AtomicInteger();
    List<CallRuntime.Result> results = new ArrayList<>();
    CallRuntime runtime = new SerializedCallRuntime(executor, initializations::incrementAndGet, error -> fail(error.toString()));

    CallRuntime.Request request = runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> true, () -> fail("Cancelled"), results::add);
    request.cancel();
    request.cancel();
    executor.drain();

    assertEquals(0, initializations.get());
    assertEquals(List.of(CallRuntime.Result.CANCELLED), results);
  }

  @Test
  public void cancellationDuringInitializationPreventsDispatch() {
    QueueExecutor executor = new QueueExecutor();
    AtomicReference<CallRuntime.Request> request = new AtomicReference<>();
    List<CallRuntime.Result> results = new ArrayList<>();
    CallRuntime runtime = new SerializedCallRuntime(executor, () -> request.get().cancel(), error -> fail(error.toString()));

    request.set(runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> true, () -> fail("Cancelled while starting"), results::add));
    executor.drain();

    assertEquals(List.of(CallRuntime.Result.CANCELLED), results);
    assertEquals(CallRuntime.State.READY, runtime.getState());
  }

  @Test
  public void cancelDuringFirstAuthorizationDoesNotInitialize() {
    QueueExecutor executor = new QueueExecutor();
    AtomicReference<CallRuntime.Request> request = new AtomicReference<>();
    AtomicInteger initializations = new AtomicInteger();
    List<CallRuntime.Result> results = new ArrayList<>();
    CallRuntime runtime = new SerializedCallRuntime(executor, initializations::incrementAndGet, error -> fail(error.toString()));

    request.set(runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> { request.get().cancel(); return true; }, () -> fail("Cancelled during admission"), results::add));
    executor.drain();

    assertEquals(0, initializations.get());
    assertEquals(List.of(CallRuntime.Result.CANCELLED), results);
    assertEquals(CallRuntime.State.COLD, runtime.getState());
  }

  @Test
  public void cancelDuringFinalAuthorizationDoesNotDispatch() {
    QueueExecutor executor = new QueueExecutor();
    AtomicReference<CallRuntime.Request> request = new AtomicReference<>();
    AtomicInteger authorizations = new AtomicInteger();
    AtomicInteger initializations = new AtomicInteger();
    List<CallRuntime.Result> results = new ArrayList<>();
    CallRuntime runtime = new SerializedCallRuntime(executor, initializations::incrementAndGet, error -> fail(error.toString()));

    request.set(runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> {
      if (authorizations.incrementAndGet() == 2) request.get().cancel();
      return true;
    }, () -> fail("Cancelled before dispatch"), results::add));
    executor.drain();

    assertEquals(1, initializations.get());
    assertEquals(2, authorizations.get());
    assertEquals(List.of(CallRuntime.Result.CANCELLED), results);
    assertEquals(CallRuntime.State.READY, runtime.getState());
  }

  @Test
  public void cancelledFollowerDoesNotCancelAnotherAuthorizedOperation() {
    QueueExecutor executor = new QueueExecutor();
    AtomicInteger initializations = new AtomicInteger();
    AtomicInteger actions = new AtomicInteger();
    List<CallRuntime.Result> results = new ArrayList<>();
    CallRuntime runtime = new SerializedCallRuntime(executor, initializations::incrementAndGet, error -> fail(error.toString()));

    runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> true, actions::incrementAndGet, results::add);
    CallRuntime.Request follower = runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> true, () -> fail("Cancelled follower"), results::add);
    follower.cancel();
    executor.drain();

    assertEquals(1, initializations.get());
    assertEquals(1, actions.get());
    assertEquals(List.of(CallRuntime.Result.DISPATCHED, CallRuntime.Result.CANCELLED), results);
  }

  @Test
  public void initializationFailureIsStickyAndReportedOnce() {
    QueueExecutor executor = new QueueExecutor();
    AtomicInteger initializations = new AtomicInteger();
    Exception failure = new Exception("synthetic startup failure");
    List<Throwable> failures = new ArrayList<>();
    List<CallRuntime.Result> results = new ArrayList<>();
    CallRuntime runtime = new SerializedCallRuntime(executor, () -> { initializations.incrementAndGet(); throw failure; }, failures::add);

    runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> true, () -> fail("Failed startup"), results::add);
    runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> true, () -> fail("Must not retry"), results::add);
    executor.drain();

    assertEquals(1, initializations.get());
    assertEquals(List.of(CallRuntime.Result.FAILED, CallRuntime.Result.FAILED), results);
    assertEquals(1, failures.size());
    assertSame(failure, failures.get(0));
    assertEquals(CallRuntime.State.FAILED, runtime.getState());
  }

  @Test
  public void nativeLinkFailureIsStickyToo() {
    QueueExecutor executor = new QueueExecutor();
    AtomicInteger initializations = new AtomicInteger();
    UnsatisfiedLinkError failure = new UnsatisfiedLinkError("synthetic missing library");
    List<Throwable> failures = new ArrayList<>();
    List<CallRuntime.Result> results = new ArrayList<>();
    CallRuntime runtime = new SerializedCallRuntime(executor, () -> { initializations.incrementAndGet(); throw failure; }, failures::add);

    runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> true, () -> fail("Missing library"), results::add);
    runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> true, () -> fail("Must not retry"), results::add);
    executor.drain();

    assertEquals(1, initializations.get());
    assertEquals(List.of(CallRuntime.Result.FAILED, CallRuntime.Result.FAILED), results);
    assertEquals(List.of(failure), failures);
  }

  @Test
  public void assertionErrorInitializationFailureIsTerminal() {
    QueueExecutor executor = new QueueExecutor();
    AtomicInteger initializations = new AtomicInteger();
    AssertionError failure = new AssertionError("Pinned RingRTC wraps startup failures");
    List<Throwable> failures = new ArrayList<>();
    List<CallRuntime.Result> results = new ArrayList<>();
    CallRuntime runtime = new SerializedCallRuntime(executor, () -> { initializations.incrementAndGet(); throw failure; }, failures::add);

    runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> true, () -> fail("Failed native startup"), results::add);
    runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> true, () -> fail("Must not retry"), results::add);
    executor.drain();

    assertEquals(1, initializations.get());
    assertEquals(List.of(CallRuntime.Result.FAILED, CallRuntime.Result.FAILED), results);
    assertEquals(List.of(failure), failures);
    assertEquals(CallRuntime.State.FAILED, runtime.getState());
  }

  @Test
  public void warmRuntimeStillRechecksEveryAuthorization() {
    QueueExecutor executor = new QueueExecutor();
    AtomicInteger initializations = new AtomicInteger();
    AtomicInteger actions = new AtomicInteger();
    List<CallRuntime.Result> results = new ArrayList<>();
    CallRuntime runtime = new SerializedCallRuntime(executor, initializations::incrementAndGet, error -> fail(error.toString()));

    runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> true, actions::incrementAndGet, results::add);
    executor.drain();
    runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> false, () -> fail("READY must not authorize work"), results::add);
    executor.drain();

    assertEquals(1, initializations.get());
    assertEquals(1, actions.get());
    assertEquals(List.of(CallRuntime.Result.DISPATCHED, CallRuntime.Result.DENIED), results);
  }

  @Test
  public void passiveOperationCanUseAlreadyRunningEngine() {
    QueueExecutor executor = new QueueExecutor();
    AtomicInteger initializations = new AtomicInteger();
    AtomicInteger actions = new AtomicInteger();
    List<CallRuntime.Result> results = new ArrayList<>();
    CallRuntime runtime = new SerializedCallRuntime(executor, initializations::incrementAndGet, error -> fail(error.toString()));

    runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> true, () -> {}, results::add);
    executor.drain();
    runtime.execute(CallRuntime.Mode.IF_RUNNING, () -> true, actions::incrementAndGet, results::add);
    executor.drain();

    assertEquals(1, initializations.get());
    assertEquals(1, actions.get());
    assertEquals(List.of(CallRuntime.Result.DISPATCHED, CallRuntime.Result.DISPATCHED), results);
  }

  @Test
  public void injectedReadyBackendDoesNotInitializeAgain() {
    QueueExecutor executor = new QueueExecutor();
    List<CallRuntime.Result> results = new ArrayList<>();
    CallRuntime runtime = new SerializedCallRuntime(executor, () -> fail("Already initialized"), error -> fail(error.toString()), true);

    assertEquals(CallRuntime.State.READY, runtime.getState());
    runtime.execute(CallRuntime.Mode.IF_RUNNING, () -> true, () -> {}, results::add);
    executor.drain();

    assertEquals(List.of(CallRuntime.Result.DISPATCHED), results);
  }

  @Test
  public void actionFailureIsNotMisreportedAsInitializationFailure() {
    QueueExecutor executor = new QueueExecutor();
    AtomicInteger initializations = new AtomicInteger();
    List<Throwable> failures = new ArrayList<>();
    IllegalStateException failure = new IllegalStateException("synthetic dispatch failure");
    CallRuntime runtime = new SerializedCallRuntime(executor, initializations::incrementAndGet, failures::add);

    runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> true, () -> { throw failure; }, result -> {});
    try {
      executor.drain();
      fail("The existing action failure must propagate");
    } catch (IllegalStateException expected) {
      assertSame(failure, expected);
    }

    assertEquals(CallRuntime.State.READY, runtime.getState());
    assertTrue(failures.isEmpty());
    assertEquals(1, initializations.get());
  }

  @Test
  public void actionFailureCompletesExactlyOnce() {
    QueueExecutor executor = new QueueExecutor();
    List<CallRuntime.Result> results = new ArrayList<>();
    CallRuntime runtime = new SerializedCallRuntime(executor, () -> {}, error -> fail("Not an initialization failure"));
    IllegalStateException failure = new IllegalStateException("synthetic command failure");
    CallRuntime.Request request = runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> true, () -> { throw failure; }, results::add);

    try {
      executor.drain();
      fail("Dispatch failure must propagate");
    } catch (IllegalStateException expected) {
      assertSame(failure, expected);
    }
    request.cancel();
    executor.drain();

    assertEquals(List.of(CallRuntime.Result.FAILED), results);
    assertEquals(CallRuntime.State.READY, runtime.getState());
  }

  @Test
  public void initializationRemainsVisibleAndSerializedWhileAnotherRequestArrives() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch initializing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch completed = new CountDownLatch(2);
    AtomicInteger initializations = new AtomicInteger();
    AtomicInteger actions = new AtomicInteger();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CallRuntime runtime = new SerializedCallRuntime(executor, () -> {
      initializations.incrementAndGet();
      initializing.countDown();
      if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test initializer did not release");
    }, failure::set);

    try {
      runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> true, actions::incrementAndGet, result -> completed.countDown());
      assertTrue(initializing.await(5, TimeUnit.SECONDS));
      assertEquals(CallRuntime.State.INITIALIZING, runtime.getState());
      runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, () -> true, actions::incrementAndGet, result -> completed.countDown());
      assertEquals(0, actions.get());
      release.countDown();
      assertTrue(completed.await(5, TimeUnit.SECONDS));
      assertEquals(1, initializations.get());
      assertEquals(2, actions.get());
      assertEquals(null, failure.get());
      assertEquals(CallRuntime.State.READY, runtime.getState());
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void laterGrantDoesNotReviveCancelledOperation() {
    QueueExecutor executor = new QueueExecutor();
    AtomicInteger initializations = new AtomicInteger();
    AtomicBoolean authorized = new AtomicBoolean(true);
    List<CallRuntime.Result> results = new ArrayList<>();
    CallRuntime runtime = new SerializedCallRuntime(executor, initializations::incrementAndGet, error -> fail(error.toString()));

    CallRuntime.Request cancelled = runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, authorized::get, () -> fail("A new grant must not revive cancellation"), results::add);
    authorized.set(false);
    cancelled.cancel();
    authorized.set(true);
    runtime.execute(CallRuntime.Mode.START_IF_AUTHORIZED, authorized::get, () -> {}, results::add);
    executor.drain();

    assertEquals(1, initializations.get());
    assertEquals(List.of(CallRuntime.Result.CANCELLED, CallRuntime.Result.DISPATCHED), results);
    assertFalse(results.contains(CallRuntime.Result.FAILED));
  }

  private static final class QueueExecutor implements Executor {
    private final Queue<Runnable> pending = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
      pending.add(command);
    }

    void drain() {
      while (!pending.isEmpty()) pending.remove().run();
    }
  }
}

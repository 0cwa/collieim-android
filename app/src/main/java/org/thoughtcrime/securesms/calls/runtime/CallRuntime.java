/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.runtime;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Managed admission-to-runtime boundary. Merely obtaining this interface or reading its state
 * must not load a calling library. The authorization predicate delegates to CallPermissions;
 * it is not a second permission policy. Backend commands retain their own final dispatch checks.
 *
 * No RingRTC or WebRTC types cross this boundary. A future isolated backend must preserve its
 * cancellation, generation and failure semantics, without treating a request as a durable grant.
 */
public interface CallRuntime {
  enum State { COLD, INITIALIZING, READY, FAILED }
  enum Mode { START_IF_AUTHORIZED, IF_RUNNING }
  enum Result { DISPATCHED, CANCELLED, DENIED, DEFERRED, FAILED }

  interface Request {
    /** Prevents queued or initializing work from dispatching; does not unload native libraries. */
    void cancel();
  }

  State getState();

  /**
   * Checks authorization before initialization and again before dispatch. Completion is delivered
   * once on the runtime queue. Cancelling after dispatch does not undo an already executed command;
   * asynchronous backend work must also check its originating authorization and cancellation.
   */
  Request execute(Mode mode, BooleanSupplier isAuthorized, Runnable action, Consumer<Result> complete);
}

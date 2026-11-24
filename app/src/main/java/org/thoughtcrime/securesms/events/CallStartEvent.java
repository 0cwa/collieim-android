/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.events;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.models.recipients.RecipientId;
import org.thoughtcrime.securesms.calls.runtime.CallRuntime;

/** Managed progress for one local start; observing it never creates native call or video state. */
public final class CallStartEvent {
  private final long attemptId;
  private final RecipientId recipientId;
  private final boolean preJoin;
  @Nullable private final CallRuntime.Result result;

  public CallStartEvent(long attemptId, @NonNull RecipientId recipientId, boolean preJoin) {
    this(attemptId, recipientId, preJoin, null);
  }

  private CallStartEvent(long attemptId, RecipientId recipientId, boolean preJoin, @Nullable CallRuntime.Result result) {
    this.attemptId = attemptId;
    this.recipientId = recipientId;
    this.preJoin = preJoin;
    this.result = result;
  }

  public long getAttemptId() {
    return attemptId;
  }

  public @NonNull RecipientId getRecipientId() {
    return recipientId;
  }

  public boolean isPreJoin() {
    return preJoin;
  }

  public boolean isPending() {
    return result == null;
  }

  public @Nullable CallRuntime.Result getResult() {
    return result;
  }

  public @NonNull CallStartEvent completed(@NonNull CallRuntime.Result result) {
    return new CallStartEvent(attemptId, recipientId, preJoin, result);
  }
}

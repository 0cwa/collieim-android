package org.thoughtcrime.securesms.database;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import org.signal.core.models.recipients.RecipientId;
import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.signal.network.util.Preconditions;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Resolves old recipient and thread IDs using the current database state. */
class RemappedRecords {
  private static final String TAG = Log.tag(RemappedRecords.class);
  private static final RemappedRecords INSTANCE = new RemappedRecords();
  private final AtomicBoolean staleTrimScheduled = new AtomicBoolean(false);
  private final Executor trimExecutor;
  private final Runnable trim;

  private RemappedRecords() {
    this(SignalExecutors.BOUNDED, () -> SignalDatabase.remappedRecords().trimStaleMappings());
  }

  @VisibleForTesting
  RemappedRecords(Executor trimExecutor, Runnable trim) {
    this.trimExecutor = trimExecutor;
    this.trim = trim;
  }

  static RemappedRecords getInstance() {
    return INSTANCE;
  }

  @NonNull Optional<RecipientId> getRecipient(@NonNull RecipientId oldId) {
    RecipientId result = SignalDatabase.remappedRecords().getRecipientMapping(oldId);
    scheduleStaleTrimIfNeeded(result != null);
    return Optional.ofNullable(result);
  }

  @NonNull Optional<Long> getThread(long oldId) {
    Long result = SignalDatabase.remappedRecords().getThreadMapping(oldId);
    scheduleStaleTrimIfNeeded(result != null);
    return Optional.ofNullable(result);
  }

  void deleteThread(long oldId, long expectedNewId) {
    ensureInTransaction();
    SignalDatabase.remappedRecords().deleteThreadMapping(oldId, expectedNewId);
  }

  boolean areAnyRemapped(@NonNull Collection<RecipientId> recipientIds) {
    Map<RecipientId, RecipientId> snapshot = Collections.unmodifiableMap(new HashMap<>(SignalDatabase.remappedRecords().getAllRecipientMappings()));
    scheduleStaleTrimIfNeeded(!snapshot.isEmpty());
    return recipientIds.stream().anyMatch(snapshot::containsKey);
  }

  @NonNull String buildRemapDescription(@NonNull Collection<RecipientId> recipientIds) {
    Map<RecipientId, RecipientId> snapshot = Collections.unmodifiableMap(new HashMap<>(SignalDatabase.remappedRecords().getAllRecipientMappings()));
    scheduleStaleTrimIfNeeded(!snapshot.isEmpty());
    StringBuilder builder = new StringBuilder();
    for (RecipientId original : recipientIds) {
      if (snapshot.containsKey(original)) {
        builder.append(original).append(" -> ").append(snapshot.get(original)).append(" ");
      }
    }
    return builder.toString();
  }

  /** Can only be called inside of a transaction. */
  void addRecipient(@NonNull RecipientId oldId, @NonNull RecipientId newId) {
    Log.w(TAG, "[Recipient] Remapping " + oldId + " to " + newId);
    Preconditions.checkArgument(!oldId.equals(newId), "Cannot remap an ID to the same thing!");
    ensureInTransaction();
    SignalDatabase.remappedRecords().addRecipientMapping(oldId, newId);
  }

  /** Can only be called inside of a transaction. */
  void addThread(long oldId, long newId) {
    Log.w(TAG, "[Thread] Remapping " + oldId + " to " + newId);
    Preconditions.checkArgument(oldId != newId, "Cannot remap an ID to the same thing!");
    ensureInTransaction();
    SignalDatabase.remappedRecords().addThreadMapping(oldId, newId);
  }

  private void scheduleStaleTrimIfNeeded(boolean hasMappings) {
    if (!hasMappings || !staleTrimScheduled.compareAndSet(false, true)) return;
    try {
      trimExecutor.execute(() -> {
        boolean completed = false;
        try {
          trim.run();
          completed = true;
        } catch (RuntimeException failure) {
          Log.w(TAG, "Unable to trim stale remaps; a later lookup may retry.", failure);
        } finally {
          if (!completed) staleTrimScheduled.set(false);
        }
      });
    } catch (RuntimeException failure) {
      staleTrimScheduled.set(false);
      Log.w(TAG, "Unable to schedule remap cleanup; a later lookup may retry.", failure);
    }
  }

  private void ensureInTransaction() {
    if (!SignalDatabase.inTransaction()) {
      throw new IllegalStateException("Must be in a transaction!");
    }
  }
}

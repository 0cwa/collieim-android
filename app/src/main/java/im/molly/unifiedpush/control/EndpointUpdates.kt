package im.molly.unifiedpush.control

import im.molly.unifiedpush.enrollment.EnrollmentFile
import im.molly.unifiedpush.receiver.EncryptedPush

/** The rules of the endpoint-update state machine in `contracts/encrypted-push/v2` ("Confirmation event and phone state machine"). */
object EndpointUpdates {

  private const val MAX_EPOCH = 9007199254740991L

  class Reconciled(val update: ControlUpdate?, val built: Boolean)

  /**
   * Makes the server's subscription converge on [current]. If a keyed enrollment is active and the server is not known
   * to hold [current], and no unconfirmed update already targets it, this stores a new update at an epoch above
   * everything allocated so far and returns it with `built` true. An unconfirmed update for [current] is returned
   * unchanged, so calling this again never allocates another epoch or builds another blob. A different pending update
   * is superseded. Being a function of the stored state alone, it recovers from a lost update, a crash between
   * receiving an endpoint and storing its update, and an endpoint that changed before the first keyed import.
   * The caller sends the returned update.
   *
   * [force] builds an update even when the server already holds [current] (used to test the whole path).
   */
  fun reconcile(
    store: ControlStore,
    accountId: String,
    deviceId: Int,
    current: ControlFrame.Subscription?,
    now: Long,
    nonce: ByteArray? = null,
    force: Boolean = false,
  ): Reconciled {
    var built = false
    val state = store.mutate { old ->
      val key = old.key
      val pending = old.update
      when {
        key == null || old.activeEpoch <= 0 || current == null -> old
        pending != null && pending.sameTarget(current) -> old
        pending == null && !force && old.serverTarget?.matches(current) == true -> old
        else -> {
          val epoch = maxOf(old.highestEpoch, old.activeEpoch, old.pendingEpoch) + 1
          if (epoch > MAX_EPOCH) throw EnrollmentFile.Invalid("invalid_epoch")
          val frame = if (nonce == null) ControlFrame.build(key, accountId, deviceId, epoch, current)
          else ControlFrame.build(key, accountId, deviceId, epoch, current, nonce)
          built = true
          old.copy(highestEpoch = epoch, update = ControlUpdate(epoch, current.endpoint, current.p256dh, current.auth, frame, now))
        }
      }
    }
    return Reconciled(state.update?.takeIf { current != null && it.sameTarget(current) }, built)
  }

  /** Only a status event with code `subscription_updated` at the pending update's epoch confirms it. */
  fun confirms(update: ControlUpdate?, event: EncryptedPush.Event): Boolean =
    update != null && event.kind == EncryptedPush.Kind.STATUS &&
      event.code == EncryptedPush.CODE_SUBSCRIPTION_UPDATED && event.epoch == update.epoch

  enum class Effect { NONE, CONFIRMED, PROMOTED }

  /**
   * Applies an accepted event to the stored state, together with its replay state, in one write. The decision is made
   * against the state as it is inside the write, so an update that was replaced since the event was checked is left
   * alone: a confirmation clears the update only if the stored epoch still equals the event's.
   *
   * A confirmation at or above the epoch of an export awaiting import also retires that export, whose key must not
   * replace the working one later. An event at the pending export's epoch proves the server imported that file: its
   * key, epoch and subscription become the active ones and an update built under the old key is dropped (the caller
   * then reconciles, which rebuilds it under the new key if the endpoint moved on).
   */
  fun accepted(store: ControlStore, replay: String, event: EncryptedPush.Event): Effect {
    var effect = Effect.NONE
    store.mutate(replay) { old ->
      val update = old.update
      when {
        confirms(update, event) -> {
          effect = Effect.CONFIRMED
          val retire = old.pendingEpoch in 1..event.epoch
          old.copy(
            update = null, activeEpoch = event.epoch, serverTarget = update!!.target, highestEpoch = maxOf(old.highestEpoch, event.epoch),
            pendingEpoch = if (retire) 0 else old.pendingEpoch,
            pendingKey = if (retire) null else old.pendingKey,
            pendingTarget = if (retire) null else old.pendingTarget,
          )
        }
        old.pendingEpoch > 0 && event.epoch == old.pendingEpoch -> {
          effect = Effect.PROMOTED
          val newKey = old.pendingKey
          old.copy(
            key = newKey ?: old.key, activeEpoch = old.pendingEpoch, serverTarget = old.pendingTarget ?: old.serverTarget,
            highestEpoch = maxOf(old.highestEpoch, old.pendingEpoch),
            pendingEpoch = 0, pendingKey = null, pendingTarget = null,
            update = if (newKey != null) null else old.update,
          )
        }
        else -> old
      }
    }
    return effect
  }

  /** Attempt bookkeeping for the update at [epoch]; an update that has been replaced since is left alone. */
  fun recordAttempt(store: ControlStore, epoch: Long, now: Long, error: String?) {
    store.mutate { old -> if (old.update?.epoch == epoch) old.copy(update = old.update.afterAttempt(now, error)) else old }
  }

  /** Records an export before its file is published; [retireExport] undoes it if publishing fails. */
  fun recordExport(store: ControlStore, epoch: Long, key: String, target: Target) {
    store.mutate { old -> old.copy(pendingEpoch = epoch, pendingKey = key, pendingTarget = target) }
  }

  /** Undoes [recordExport] for [epoch] if nothing has replaced it, e.g. because the file could not be written. */
  fun retireExport(store: ControlStore, epoch: Long, before: ControlState) {
    store.mutate { old ->
      if (old.pendingEpoch == epoch) old.copy(pendingEpoch = before.pendingEpoch, pendingKey = before.pendingKey, pendingTarget = before.pendingTarget) else old
    }
  }

  /** Epochs a receiver accepts events at: the active one, an export awaiting import, an update awaiting confirmation. */
  fun acceptedEpochs(state: ControlState): Set<Long> =
    setOf(state.activeEpoch, state.pendingEpoch, state.update?.epoch ?: 0).filter { it > 0 }.toSet()

  /** A keyed enrollment sends version 2 events, an unkeyed (earlier) one version 1. */
  fun versionForEpoch(state: ControlState, epoch: Long): Int = when {
    epoch == state.update?.epoch -> 2
    epoch == state.pendingEpoch && state.pendingKey != null -> 2
    epoch == state.activeEpoch && state.key != null -> 2
    else -> 1
  }

  /**
   * The epoch an export gets: one above what the operator read from the server, and above every epoch this phone put
   * into an update. Exports themselves do not raise the mark, so exporting twice without an import reuses the epoch.
   */
  fun exportEpoch(entered: Long?, state: ControlState): Long = maxOf(EnrollmentFile.nextEpoch(entered), state.highestEpoch + 1)
}

package im.molly.unifiedpush.control

import im.molly.unifiedpush.enrollment.EnrollmentFile
import im.molly.unifiedpush.receiver.EncryptedPush
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle

/** Phone-side encrypted-push state transitions for legacy endpoint updates and v3 import intents. */
object EndpointUpdates {

  private const val MAX_EPOCH = 9007199254740991L

  /** Registration tuple read from the live local Signal/UnifiedPush state, never from a saved export. */
  data class RegistrationContext(val accountId: String, val deviceId: Int, val instance: String) {
    fun matches(accountId: String?, deviceId: Int?, instance: String?): Boolean =
      this.accountId == accountId && this.deviceId == deviceId && this.instance == instance
  }

  class Reconciled(val update: ControlUpdate?, val built: Boolean)
  enum class Effect { NONE, CONFIRMED, PROMOTED, REJECTED }

  /** Builds an endpoint control frame under the currently confirmed key. */
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
          val maxCandidateEpoch = old.intents.maxOfOrNull { it.epoch } ?: 0
          val epoch = maxOf(old.highestEpoch, old.activeEpoch, maxCandidateEpoch) + 1
          if (epoch > MAX_EPOCH) throw EnrollmentFile.Invalid("invalid_epoch")
          val frame = if (nonce == null) ControlFrame.build(key, accountId, deviceId, epoch, current)
          else ControlFrame.build(key, accountId, deviceId, epoch, current, nonce)
          built = true
          old.copy(highestEpoch = epoch, update = ControlUpdate(epoch, current.endpoint, current.p256dh, current.auth, frame, now, ping = force))
        }
      }
    }
    return Reconciled(state.update?.takeIf { current != null && it.sameTarget(current) }, built)
  }

  /**
   * Commits one new logical import before the caller publishes its enrollment file. Superseded records stay stored
   * and count toward the cap; only a higher confirmed sequence retires them. [create] must only construct the
   * candidate and must not publish anything.
   */
  fun recordIntent(
    store: ControlStore,
    fullRecovery: Boolean,
    replayIfAbsent: String? = null,
    create: (ControlState, Long) -> ImportIntent,
  ): ImportIntent {
    var created: ImportIntent? = null
    val mutate: ((ControlState) -> ControlState) -> ControlState = { block ->
      if (replayIfAbsent == null) store.mutate(block = block) else store.mutateWithReplayIfAbsent(replayIfAbsent, block)
    }
    mutate { old ->
      if (old.pendingIntentCount >= ControlState.MAX_PENDING_INTENTS) throw EnrollmentFile.Invalid("pending_limit")
      if (old.importSequence >= ControlState.MAX_IMPORT_SEQUENCE) throw EnrollmentFile.Invalid("sequence_exhausted")
      val sequence = old.importSequence + 1
      val intent = create(old, sequence)
      if (intent.sequence != sequence || intent.epoch !in 1..MAX_EPOCH || intent.requestId.isBlank()) throw EnrollmentFile.Invalid("invalid_enrollment")
      val retained = old.intents.map { previous ->
        if (previous.eligible && (fullRecovery || previous.epoch >= intent.epoch)) previous.copy(eligible = false) else previous
      }
      created = intent
      old.copy(
        intents = retained + intent,
        importSequence = sequence,
        highestEpoch = maxOf(old.highestEpoch, intent.epoch),
      )
    }
    return checkNotNull(created)
  }

  /** Explicitly forgets one stored candidate after the UI warns that an old file may still exist. */
  fun forgetIntent(store: ControlStore, requestId: String): Boolean {
    var removed = false
    store.mutate { old ->
      val next = old.intents.filterNot { if (it.requestId == requestId) { removed = true; true } else false }
      if (removed) old.copy(intents = next) else old
    }
    return removed
  }

  /** Explicitly forgets a migrated unconfirmed legacy export after a user warning. */
  fun forgetLegacyPending(store: ControlStore): Boolean {
    var removed = false
    store.mutate { old ->
      if (old.legacyPending == null) old else old.copy(legacyPending = null).also { removed = true }
    }
    return removed
  }

  /** v2 endpoint confirmations have no import identity; v3 confirmations must match the active intent first. */
  fun confirms(state: ControlState, event: EncryptedPush.Event, current: RegistrationContext? = null): Boolean =
    event.kind == EncryptedPush.Kind.STATUS && event.code == EncryptedPush.CODE_SUBSCRIPTION_UPDATED &&
      event.epoch == state.update?.epoch && activeIdentityMatches(state, event, current)

  fun identityMatches(state: ControlState, event: EncryptedPush.Event, current: RegistrationContext? = null): Boolean {
    if (event.version < 3) return activeIdentityMatches(state, event, current)
    // Candidate identity is checked before the active key identity. Same-key first-v3 adoption binds the fresh
    // import identity and sequence even though key_id is unchanged. Its saved account/device/instance must still
    // describe the registration that is live on this phone.
    if (matchingIntent(state, event, current) != null) return true
    return activeIdentityMatches(state, event, current)
  }

  private fun matchingIntent(state: ControlState, event: EncryptedPush.Event, current: RegistrationContext?): ImportIntent? =
    if (event.version != 3 || event.importId == null || event.keyId == null || current == null) null
    else state.intents.firstOrNull {
      it.eligible && it.requestId == event.importId && it.keyId == event.keyId && it.epoch == event.epoch &&
        it.instance == event.instance && current.matches(it.accountId, it.deviceId, it.instance) &&
        it.sequence > state.activeImportSeq &&
        (it.epoch >= state.activeEpoch || it.recovery)
    }

  private fun activeIdentityMatches(state: ControlState, event: EncryptedPush.Event, current: RegistrationContext?): Boolean = when (event.version) {
    1 -> state.key == null && state.activeImportId == null && event.epoch == state.activeEpoch
    2 -> state.key != null && state.activeImportId == null && (event.epoch == state.activeEpoch || confirmsUpdateAt(state, event))
    3 -> current != null && current.matches(state.activeAccountId, state.activeDeviceId, state.activeInstance) &&
      state.activeImportId != null && state.activeKeyId != null && event.instance == state.activeInstance &&
      (event.epoch == state.activeEpoch || confirmsUpdateAt(state, event)) &&
      event.importId == state.activeImportId && event.keyId == state.activeKeyId
    else -> false
  }

  private fun confirmsUpdateAt(state: ControlState, event: EncryptedPush.Event) =
    event.kind == EncryptedPush.Kind.STATUS && event.code == EncryptedPush.CODE_SUBSCRIPTION_UPDATED && event.epoch == state.update?.epoch

  /** Applies only an exact v3 logical intent, or a status confirmation from the already active key identity. */
  fun accepted(
    store: ControlStore,
    replay: String,
    event: EncryptedPush.Event,
    current: RegistrationContext? = null,
  ): Effect {
    var effect = Effect.NONE
    store.mutate(replay) { old ->
      val intent = matchingIntent(old, event, current)
      when {
        !identityMatches(old, event, current) -> {
          effect = Effect.REJECTED
          old
        }
        intent != null -> {
          effect = Effect.PROMOTED
          old.copy(
            key = intent.controlKey,
            activeKeyId = intent.keyId,
            activeImportId = intent.requestId,
            activeImportSeq = intent.sequence,
            activeAccountId = intent.accountId,
            activeDeviceId = intent.deviceId,
            activeInstance = intent.instance,
            activeEpoch = intent.epoch,
            highestEpoch = maxOf(old.highestEpoch, intent.epoch),
            intents = old.intents.filterNot { it.sequence <= intent.sequence },
            legacyPending = null,
            serverTarget = intent.target,
            update = if (old.key == intent.controlKey) old.update else null,
          )
        }
        confirms(old, event, current) -> {
          val update = old.update!!
          effect = Effect.CONFIRMED
          old.copy(update = null, activeEpoch = event.epoch, serverTarget = update.target, highestEpoch = maxOf(old.highestEpoch, event.epoch))
        }
        else -> old
      }
    }
    return effect
  }

  /** Attempt bookkeeping only for the exact selected frame in the same account execution generation. */
  fun recordAttempt(store: ControlStore, expected: ControlUpdate, generation: Long, now: Long, error: String?) {
    synchronized(UnifiedPushLifecycle.monitor) {
      if (!UnifiedPushLifecycle.isReadyLocked() || UnifiedPushLifecycle.currentGeneration() != generation) return
      store.mutate { old ->
        val current = old.update
        if (current != null && current.epoch == expected.epoch && current.frame.contentEquals(expected.frame) &&
          current.endpoint == expected.endpoint && current.p256dh == expected.p256dh && current.auth == expected.auth) {
          old.copy(update = current.afterAttempt(now, error))
        } else old
      }
    }
  }

  /** Active and pending-intent epochs the receiver may parse before exact identity checks. */
  fun acceptedEpochs(state: ControlState): Set<Long> = buildSet {
    if (state.activeEpoch > 0) add(state.activeEpoch)
    state.intents.forEach { add(it.epoch) }
    state.legacyPending?.epoch?.takeIf { it > 0 }?.let(::add)
    state.update?.epoch?.takeIf { it > 0 }?.let(::add)
  }

  /** Versions that can be present at an epoch while an old active intent and a v3 candidate overlap. */
  fun versionsForEpoch(state: ControlState, epoch: Long): Set<Int> = buildSet {
    val active = epoch == state.activeEpoch || epoch == state.update?.epoch || epoch == state.legacyPending?.epoch
    if (active) add(when {
      state.activeImportId != null -> 3
      state.key != null -> 2
      else -> 1
    })
    if (state.intents.any { it.epoch == epoch }) add(3)
  }

  fun exportEpoch(serverEpoch: Long?): Long = EnrollmentFile.nextEpoch(serverEpoch)

  /** Old endpoint-update epochs still collide with the server epoch explicitly entered by the operator. */
  fun exportCollides(epoch: Long, state: ControlState): Boolean = epoch <= state.highestEpoch
}

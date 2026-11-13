package im.molly.unifiedpush.control

import im.molly.unifiedpush.enrollment.ControlKeyId
import im.molly.unifiedpush.enrollment.EnrollmentFile
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import im.molly.unifiedpush.receiver.EncryptedPush
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class EndpointUpdatesTest {
  private val runtimeFixture by lazy {
    ObjectMapper().readTree(javaClass.getResourceAsStream("/colliesocket-contract-v3/fixtures/runtime-cases-v3.json"))
  }
  private val runtimeCases by lazy { runtimeFixture.get("cases").associateBy { it.get("id").asText() } }

  /** Every pinned runtime vector is deliberately classified; additions to the contract fail closed here. */
  private val runtimeCaseClassification = mapOf(
    "stale-sequence-unseen-request-id" to "phone: delayed lower sequence cannot roll back",
    "equal-sequence-nonidentical" to "phone: equal sequence cannot replace exact intent",
    "same-epoch-different-key" to "server-only: enrollment epoch conflict for a different control key",
    "conflicting-repeated-request-id" to "server-only: request receipt digest conflict",
    "reaffirm-same-epoch-fresh-id" to "server-only: server row generation and outbox transaction",
    "delayed-old-same-epoch-event" to "phone: delayed event for superseded import",
    "old-file-recorded-reimport-after-newer-same-epoch" to "server-only: historical receipt lookup",
    "old-file-unseen-reimport-after-newer-same-epoch" to "server-only: selected-account receipt ledger",
    "legacy-unknown-intent" to "phone: v2 cannot bind an unknown legacy intent",
    "explicit-lower-epoch-recovery" to "phone: explicit lower-epoch recovery promotes exact identity",
    "lost-status-later-wake" to "phone: matching wake promotes pending import",
    "ordinary-lower-epoch-before-later-candidate" to "phone: lower epoch candidate remains eligible",
    "cap-refusal-rerender-restart" to "phone: pending cap and sequence floor retained",
    "rerender-existing-no-slot" to "phone: existing persisted intent is not duplicated",
    "restart-retains-pending-intents" to "phone: restart retains pending records",
    "unknown-legacy-migration" to "phone: epoch-only v3 mismatch does not bind legacy intent",
  )

  private class Memory(initial: ControlState) : ControlStore {
    var state = initial
    var replay: String? = null
    override fun read() = state
    override fun mutate(replay: String?, block: (ControlState) -> ControlState): ControlState {
      if (replay != null) this.replay = replay
      state = block(state)
      return state
    }
    override fun mutateWithReplayIfAbsent(replayIfAbsent: String, block: (ControlState) -> ControlState): ControlState {
      if (replay == null) replay = replayIfAbsent
      return mutate(block = block)
    }
  }

  private val account = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
  private val instance = "fixture-instance"
  private val liveRegistration = EndpointUpdates.RegistrationContext(account, 2, instance)
  private val keyA = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
  private val keyB = key((1..32).map(Int::toByte).toByteArray())
  private val keyC = key((33..64).map(Int::toByte).toByteArray())
  private val target = Target(
    "https://push.example.invalid/synthetic-endpoint",
    "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
    "BTBZMqHH6r4Tts7J_aSIgg",
  )

  private fun key(bytes: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)
  private fun fixtureId(value: String): String = when (value) {
    "A" -> id(100)
    "B" -> id(101)
    else -> value
  }
  private fun fixtureKey(value: String): String = when (value) {
    "K0", "K1" -> keyA
    "K2" -> keyB
    else -> error("Unknown synthetic fixture key label: $value")
  }
  private fun intent(
    requestId: String,
    sequence: Long,
    epoch: Long,
    controlKey: String,
    recovery: Boolean = false,
    eligible: Boolean = true,
    target: Target = this.target,
    accountId: String = account,
    deviceId: Int = 2,
    instanceId: String = instance,
  ) = ImportIntent(
    requestId, sequence, epoch, controlKey,
    ControlKeyId.derive(controlKey, accountId, deviceId, instanceId), target,
    accountId, deviceId, "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL", instanceId, recovery, eligible,
  )

  private fun registration(accountId: String = account, deviceId: Int = 2, instanceId: String = instance) =
    EndpointUpdates.RegistrationContext(accountId, deviceId, instanceId)

  private fun matches(
    state: ControlState,
    event: EncryptedPush.Event,
    current: EndpointUpdates.RegistrationContext? = liveRegistration,
  ) = EndpointUpdates.identityMatches(state, event, current)

  private fun accept(
    store: ControlStore,
    replay: String,
    event: EncryptedPush.Event,
    current: EndpointUpdates.RegistrationContext? = liveRegistration,
  ) = EndpointUpdates.accepted(store, replay, event, current)

  private fun active(intent: ImportIntent) = ControlState(
    key = intent.controlKey,
    activeKeyId = intent.keyId,
    activeImportId = intent.requestId,
    activeImportSeq = intent.sequence,
    activeAccountId = intent.accountId,
    activeDeviceId = intent.deviceId,
    activeInstance = intent.instance,
    activeEpoch = intent.epoch,
    highestEpoch = intent.epoch,
    importSequence = intent.sequence,
    serverTarget = intent.target,
  )

  @Test
  fun `every runtime contract case maps to production behavior or explicit server exclusion`() {
    assertEquals(1, runtimeFixture.get("fixture_version").asInt())
    assertTrue(runtimeFixture.get("synthetic_only").asBoolean())
    assertEquals("duplicate fixture IDs are invalid", runtimeFixture.get("cases").size(), runtimeCases.size)
    assertEquals(runtimeCaseClassification.keys, runtimeCases.keys)
    for ((caseId, classification) in runtimeCaseClassification) {
      val fixture = runtimeCases.getValue(caseId)
      when {
        classification.startsWith("server-only:") -> {
          assertTrue("$caseId must remain explicitly server-only", classification.startsWith("server-only: "))
          when (caseId) {
            "same-epoch-different-key" -> {
              assertEquals("reject", fixture.get("expected").asText())
              assertEquals("epoch_conflict", fixture.get("expected_error").asText())
              assertFalse(fixture.get("row_mutation").asBoolean())
            }
            "conflicting-repeated-request-id" -> {
              assertEquals("reject", fixture.get("expected").asText())
              assertEquals("idempotency_conflict", fixture.get("expected_error").asText())
              assertFalse(fixture.get("row_mutation").asBoolean())
            }
            "reaffirm-same-epoch-fresh-id" -> {
              assertEquals("accept", fixture.get("expected").asText())
              assertTrue(fixture.get("confirmation_queued_atomically").asBoolean())
            }
            "old-file-recorded-reimport-after-newer-same-epoch" -> {
              assertEquals("saved_historical_receipt", fixture.get("expected").asText())
              assertFalse(fixture.get("historical_receipt_is_current_confirmation").asBoolean())
            }
            "old-file-unseen-reimport-after-newer-same-epoch" -> {
              assertEquals("reject", fixture.get("expected").asText())
              assertEquals("epoch_conflict", fixture.get("expected_error").asText())
              assertFalse(fixture.get("row_mutation").asBoolean())
            }
            else -> error("Unmapped server-only runtime case: $caseId")
          }
        }
        else -> when (caseId) {
          "stale-sequence-unseen-request-id" -> {
            val retained = fixture.get("retained")
            val incoming = fixture.get("incoming")
            val old = intent(
              fixtureId(retained.get("import_id").asText()),
              retained.get("import_seq").asLong(), retained.get("epoch").asLong(), fixtureKey(retained.get("key_id").asText()),
            )
            val candidate = intent(
              fixtureId(incoming.get("import_id").asText()),
              incoming.get("import_seq").asLong(), incoming.get("epoch").asLong(), fixtureKey(incoming.get("key_id").asText()),
            )
            val store = Memory(active(old).copy(intents = listOf(candidate), importSequence = retained.get("import_seq").asLong()))
            val before = store.state
            assertEquals(EndpointUpdates.Effect.REJECTED, accept(store, caseId, event(candidate)))
            assertEquals(before, store.state)
          }
          "equal-sequence-nonidentical" -> {
            val retained = fixture.get("retained")
            val incoming = fixture.get("incoming")
            val old = intent(
              fixtureId(retained.get("import_id").asText()), retained.get("import_seq").asLong(),
              retained.get("epoch").asLong(), fixtureKey(retained.get("key_id").asText()),
            )
            val candidate = intent(
              fixtureId(incoming.get("import_id").asText()), incoming.get("import_seq").asLong(),
              incoming.get("epoch").asLong(), fixtureKey(incoming.get("key_id").asText()),
            )
            val store = Memory(active(old).copy(intents = listOf(candidate), importSequence = retained.get("import_seq").asLong()))
            val before = store.state
            assertEquals(EndpointUpdates.Effect.REJECTED, accept(store, caseId, event(candidate)))
            assertEquals(before, store.state)
          }
          "delayed-old-same-epoch-event" -> {
            val retained = fixture.get("retained")
            val incoming = fixture.get("incoming")
            val newer = intent(
              fixtureId(retained.get("import_id").asText()), retained.get("import_seq").asLong(),
              retained.get("epoch").asLong(), fixtureKey(retained.get("key_id").asText()),
            )
            val old = intent(
              fixtureId(incoming.get("import_id").asText()), incoming.get("import_seq").asLong(),
              incoming.get("epoch").asLong(), fixtureKey(incoming.get("key_id").asText()), eligible = false,
            )
            val store = Memory(active(newer).copy(intents = listOf(old), importSequence = retained.get("import_seq").asLong()))
            val before = store.state
            assertEquals(EndpointUpdates.Effect.REJECTED, accept(store, caseId, event(old)))
            assertEquals(before, store.state)
          }
          "legacy-unknown-intent", "unknown-legacy-migration" -> {
            val retained = fixture.get("retained")
            val incoming = fixture.get("incoming")
            if (caseId == "legacy-unknown-intent") assertEquals("UNKNOWN", retained.get("intent").asText())
            if (caseId == "unknown-legacy-migration") {
              assertTrue(incoming.get("matches_epoch_only").asBoolean())
              assertFalse(incoming.get("matches_import_id").asBoolean())
            }
            val activeKey = fixtureKey(retained.get("active_key").asText())
            val eventEpoch = incoming.get("epoch")?.asLong() ?: 5L
            val store = Memory(ControlState(
              key = activeKey,
              activeEpoch = eventEpoch,
              highestEpoch = eventEpoch,
              legacyPending = LegacyPending(activeKey, eventEpoch, target),
            ))
            val eventIdentity = intent(id(106), 1, eventEpoch, activeKey)
            val legacyEvent = event(
              eventIdentity,
              version = incoming.get("event_version").asInt(),
              requestId = id(107),
              kind = if (incoming.get("event_version").asInt() == 2) EncryptedPush.Kind.WAKE else EncryptedPush.Kind.STATUS,
              code = if (incoming.get("event_version").asInt() == 2) null else EncryptedPush.CODE_ENROLLMENT_IMPORTED,
            )
            val expectedEffect = if (caseId == "legacy-unknown-intent") EndpointUpdates.Effect.NONE else EndpointUpdates.Effect.REJECTED
            val before = store.state
            assertEquals(expectedEffect, accept(store, caseId, legacyEvent))
            assertEquals(before, store.state)
            assertEquals(activeKey, store.state.key)
            assertNotNull(store.state.legacyPending)
            assertTrue(store.state.intents.isEmpty())
          }
          "explicit-lower-epoch-recovery" -> {
            val retained = fixture.get("retained")
            val incoming = fixture.get("incoming")
            val from = intent(id(108), retained.get("active_intent_seq").asLong(), retained.get("remembered_epoch").asLong(), keyA)
            val recovery = intent(
              id(109), incoming.get("import_seq").asLong(), incoming.get("resulting_export_epoch").asLong(),
              keyB, recovery = incoming.get("explicit_recovery_acknowledged").asBoolean(),
            )
            val store = Memory(active(from).copy(
              intents = listOf(recovery), importSequence = incoming.get("import_seq").asLong(),
              highestEpoch = retained.get("remembered_epoch").asLong(),
            ))
            assertEquals(EndpointUpdates.Effect.PROMOTED, accept(store, caseId, event(recovery)))
            assertEquals(incoming.get("resulting_export_epoch").asLong(), store.state.activeEpoch)
          }
          "lost-status-later-wake" -> {
            val retained = fixture.get("retained")
            val incoming = fixture.get("incoming")
            val pending = intent(
              fixtureId(retained.get("pending_import_id").asText()), 1, retained.get("epoch").asLong(),
              fixtureKey(retained.get("key_id").asText()),
            )
            val store = Memory(ControlState(intents = listOf(pending), importSequence = 1, highestEpoch = retained.get("epoch").asLong()))
            val kind = when (incoming.get("event_kind").asText()) {
              "wake" -> EncryptedPush.Kind.WAKE
              else -> error("Unmapped phone runtime event kind: ${incoming.get("event_kind").asText()}")
            }
            val wake = event(
              pending, requestId = fixtureId(incoming.get("import_id").asText()),
              epoch = incoming.get("epoch").asLong(), kind = kind, code = null,
            )
            assertEquals(EndpointUpdates.Effect.PROMOTED, accept(store, caseId, wake))
            assertEquals(pending.requestId, store.state.activeImportId)
          }
          "ordinary-lower-epoch-before-later-candidate" -> {
            val retained = fixture.get("retained")
            val incoming = fixture.get("incoming")
            assertTrue(incoming.get("is_next_server_epoch").asBoolean())
            val earlier = intent(
              id(111), retained.get("candidate_seq").asLong(), retained.get("candidate_target_epoch").asLong(),
              keyB, eligible = retained.get("eligible").asBoolean(),
            )
            val later = intent(
              id(112), incoming.get("import_seq").asLong() + 1, incoming.get("epoch").asLong() + 1, keyC,
            )
            val store = Memory(ControlState(
              intents = listOf(earlier, later), importSequence = incoming.get("import_seq").asLong() + 1,
              highestEpoch = incoming.get("epoch").asLong() + 1,
            ))
            assertEquals(EndpointUpdates.Effect.PROMOTED, accept(store, caseId, event(earlier)))
            assertEquals(earlier.requestId, store.state.activeImportId)
          }
          "cap-refusal-rerender-restart" -> {
            val retained = fixture.get("retained")
            val count = retained.get("pending_count").asInt()
            val floor = retained.get("sequence_floor").asLong()
            val records = (floor - count + 1..floor).map { sequence ->
              intent(id(120 + sequence.toInt()), sequence, sequence - (floor - count), keyB)
            }
            val store = Memory(ControlState(intents = records, importSequence = floor, highestEpoch = floor + 1))
            val before = store.state
            val failure = runCatching {
              EndpointUpdates.recordIntent(store, false) { _, seq -> intent(id(140), seq, floor + 2, keyC) }
            }.exceptionOrNull()
            assertEquals("pending_limit", (failure as EnrollmentFile.Invalid).code)
            assertEquals(before, store.state)
            assertEquals(floor, store.state.importSequence)
          }
          "rerender-existing-no-slot" -> {
            val retained = fixture.get("retained")
            val incoming = fixture.get("incoming")
            val floor = retained.get("import_seq").asLong()
            val pending = (floor - retained.get("pending_count").asInt() + 1..floor).map { sequence ->
              val requestId = if (sequence == floor) fixtureId(retained.get("import_id").asText()) else id(150 + sequence.toInt())
              intent(requestId, sequence, 6, keyA)
            }
            val store = Memory(ControlState(intents = pending, importSequence = floor, highestEpoch = 6))
            assertEquals(retained.get("pending_count").asInt(), store.state.pendingIntentCount)
            val before = store.state
            val restored = (ControlState.parse(before.serialize()) as ControlStateRead.Current).state
            assertEquals(before, restored)
            assertEquals(floor, restored.importSequence)
            assertFalse(incoming.get("new_id").asBoolean())
            assertFalse(incoming.get("new_sequence").asBoolean())
            val existing = restored.intents.single { it.requestId == fixtureId(retained.get("import_id").asText()) }
            val afterExistingPublication = Memory(restored)
            assertEquals(EndpointUpdates.Effect.PROMOTED, accept(afterExistingPublication, caseId, event(existing)))
            assertEquals(floor, afterExistingPublication.state.importSequence)
            assertEquals(existing.requestId, afterExistingPublication.state.activeImportId)
          }
          "restart-retains-pending-intents" -> {
            val retained = fixture.get("retained")
            assertTrue(fixture.get("incoming").get("restart").asBoolean())
            val count = retained.get("pending_count").asInt()
            val floor = retained.get("sequence_floor").asLong()
            val pending = (floor - count + 1..floor).map { sequence ->
              intent(id(150 + sequence.toInt()), sequence, sequence - (floor - count), keyB)
            }
            val store = Memory(ControlState(intents = pending, importSequence = floor, highestEpoch = floor - count + 1))
            val restored = (ControlState.parse(store.state.serialize()) as ControlStateRead.Current).state
            assertEquals(pending, restored.intents)
            assertEquals(count, restored.pendingIntentCount)
            assertEquals(floor, restored.importSequence)
          }
          else -> error("Unmapped phone runtime case: $caseId")
        }
      }
    }
  }

  @Test
  fun `seeded short import sequences preserve exact promotion and the retained sequence floor`() {
    val random = Random(0x43534f434b45544cL)
    repeat(48) { run ->
      val store = Memory(ControlState())
      val generated = (1..ControlState.MAX_PENDING_INTENTS).map { index ->
        val sequence = (index + 1).toLong()
        val candidateKey = listOf(keyA, keyB, keyC)[random.nextInt(3)]
        val candidateEpoch = 1L + random.nextInt(7)
        EndpointUpdates.recordIntent(store, fullRecovery = random.nextBoolean()) { _, allocated ->
          intent(id(1000 + run * 10 + index), allocated, candidateEpoch, candidateKey, recovery = true)
        }
      }

      val newest = generated.last()
      val wrongIdentity = event(newest, requestId = id(9000 + run))
      val beforeWrongIdentity = store.state
      assertEquals(EndpointUpdates.Effect.REJECTED, accept(store, "seed-$run-wrong-identity", wrongIdentity))
      assertEquals("a mismatched import identity cannot mutate pending state", beforeWrongIdentity, store.state)

      assertEquals(EndpointUpdates.Effect.PROMOTED, accept(store, "seed-$run-newest", event(newest)))
      assertEquals(newest.requestId, store.state.activeImportId)
      assertEquals(newest.sequence, store.state.activeImportSeq)
      assertEquals(newest.sequence, store.state.importSequence)

      val acceptedState = store.state
      assertEquals(EndpointUpdates.Effect.NONE, accept(store, "seed-$run-replay", event(newest)))
      assertEquals("replayed active confirmation is state-idempotent", acceptedState, store.state)

      val stale = generated.first()
      assertEquals(EndpointUpdates.Effect.REJECTED, accept(store, "seed-$run-stale", event(stale)))
      assertEquals("rejected stale sequence cannot change durable state", acceptedState, store.state)
      val restored = (ControlState.parse(store.state.serialize()) as ControlStateRead.Current).state
      assertEquals(newest.sequence, restored.importSequence)
      assertEquals(newest.requestId, restored.activeImportId)
      assertTrue(restored.intents.all { it.sequence > restored.activeImportSeq })
    }
  }

  private fun event(
    intent: ImportIntent,
    requestId: String = intent.requestId,
    epoch: Long = intent.epoch,
    kind: EncryptedPush.Kind = EncryptedPush.Kind.STATUS,
    code: String? = EncryptedPush.CODE_ENROLLMENT_IMPORTED,
    keyId: String = intent.keyId,
    version: Int = 3,
  ) = EncryptedPush.Event(
    version, id(800 + intent.sequence.toInt()), intent.instance, epoch, kind, code, 1800000000, 1800000300,
    if (version == 3) keyId else null, if (version == 3) requestId else null,
  )

  @Test
  fun `first v3 same key adoption binds new import identity before active key matching`() {
    val candidate = intent(id(1), sequence = 1, epoch = 5, controlKey = keyA)
    val legacyActive = ControlState(key = keyA, activeEpoch = 5, highestEpoch = 5, serverTarget = target)
    val store = Memory(legacyActive.copy(intents = listOf(candidate), importSequence = 1))
    val adopted = event(candidate)

    assertNull(legacyActive.activeKeyId)
    assertTrue(matches(store.state, adopted))
    assertEquals(EndpointUpdates.Effect.PROMOTED, accept(store, "replay", adopted))
    assertEquals(keyA, store.state.key)
    assertEquals(candidate.keyId, store.state.activeKeyId)
    assertEquals(candidate.requestId, store.state.activeImportId)
    assertEquals(1, store.state.activeImportSeq)
    assertEquals(5, store.state.activeEpoch)
    assertTrue(store.state.intents.isEmpty())

    val restored = (ControlState.parse(store.state.serialize()) as ControlStateRead.Current).state
    assertEquals(candidate.requestId, restored.activeImportId)
    assertEquals(candidate.keyId, restored.activeKeyId)
  }

  @Test
  fun `same key same epoch candidate binds newer intent before active v3 identity`() {
    val activeIntent = intent(id(2), sequence = 1, epoch = 5, controlKey = keyA)
    val candidate = intent(id(3), sequence = 2, epoch = 5, controlKey = keyA)
    val store = Memory(active(activeIntent).copy(intents = listOf(candidate), importSequence = 2))

    assertEquals(activeIntent.keyId, candidate.keyId)
    assertTrue(matches(store.state, event(candidate)))
    assertEquals(EndpointUpdates.Effect.PROMOTED, accept(store, "same-key-adoption", event(candidate)))
    assertEquals(candidate.requestId, store.state.activeImportId)
    assertEquals(candidate.sequence, store.state.activeImportSeq)
    assertEquals(candidate.keyId, store.state.activeKeyId)
    assertEquals(candidate.epoch, store.state.activeEpoch)
  }

  @Test
  fun `old active wake at an overlapping epoch never promotes a new intent`() {
    val activeIntent = intent(id(4), sequence = 1, epoch = 5, controlKey = keyA)
    val candidate = intent(id(5), sequence = 2, epoch = 5, controlKey = keyA)
    val store = Memory(active(activeIntent).copy(intents = listOf(candidate), importSequence = 2))

    // The old legitimate v3 wake still identifies the active key, but its import_id is not the new intent.
    assertTrue(matches(store.state, event(activeIntent, kind = EncryptedPush.Kind.WAKE, code = null)))
    assertEquals(
      EndpointUpdates.Effect.NONE,
      accept(store, "old-active-v3", event(activeIntent, kind = EncryptedPush.Kind.WAKE, code = null)),
    )
    assertEquals(activeIntent.requestId, store.state.activeImportId)
    assertEquals(1, store.state.activeImportSeq)
    assertEquals(candidate.requestId, store.state.intents.single().requestId)

    // A legacy wake at the same epoch cannot supply the missing import identity either.
    for (version in 1..2) {
      val legacy = event(candidate, version = version, kind = EncryptedPush.Kind.WAKE, code = null)
      assertFalse("v$version does not identify the pending v3 import", matches(store.state, legacy))
      assertEquals(EndpointUpdates.Effect.REJECTED, accept(store, "old-v$version", legacy))
      assertEquals(activeIntent.requestId, store.state.activeImportId)
      assertEquals(candidate.requestId, store.state.intents.single().requestId)
    }

    // Only the exact newer import_id may bind the same key at the same epoch.
    assertEquals(EndpointUpdates.Effect.PROMOTED, accept(store, "matching-new-v3", event(candidate)))
    assertEquals(candidate.requestId, store.state.activeImportId)
    assertEquals(2, store.state.activeImportSeq)

    // A migrated v2 active key can still process its ordinary wake at that epoch without adopting an unknown
    // candidate. This preserves legacy delivery while keeping the pending key unknown.
    val legacyActiveV2 = ControlState(key = keyA, activeEpoch = 5, highestEpoch = 5)
    val pendingV3 = intent(id(6), sequence = 1, epoch = 5, controlKey = keyB)
    val legacyStoreV2 = Memory(legacyActiveV2.copy(intents = listOf(pendingV3), importSequence = 1))
    val oldV2Wake = event(pendingV3, version = 2, kind = EncryptedPush.Kind.WAKE, code = null)
    assertTrue(matches(legacyStoreV2.state, oldV2Wake))
    assertEquals(EndpointUpdates.Effect.NONE, accept(legacyStoreV2, "legacy-old-v2", oldV2Wake))
    assertEquals(keyA, legacyStoreV2.state.key)
    assertNull(legacyStoreV2.state.activeImportId)
    assertEquals(pendingV3.requestId, legacyStoreV2.state.intents.single().requestId)

    // Unkeyed v1 wake handling has the same non-promoting behavior.
    val legacyActiveV1 = ControlState(activeEpoch = 5, highestEpoch = 5)
    val legacyStoreV1 = Memory(legacyActiveV1.copy(intents = listOf(pendingV3), importSequence = 1))
    val oldV1Wake = event(pendingV3, version = 1, kind = EncryptedPush.Kind.WAKE, code = null)
    assertTrue(matches(legacyStoreV1.state, oldV1Wake))
    assertEquals(EndpointUpdates.Effect.NONE, accept(legacyStoreV1, "legacy-old-v1", oldV1Wake))
    assertNull(legacyStoreV1.state.key)
    assertEquals(5, legacyStoreV1.state.activeEpoch)
    assertEquals(pendingV3.requestId, legacyStoreV1.state.intents.single().requestId)
  }

  @Test
  fun `delayed pre-restore K1 event cannot satisfy a fresh K1 recovery intent`() {
    val k0 = intent(id(10), 1, 8, keyA)
    val candidate = intent(id(12), 2, 3, keyB, recovery = true)
    val store = Memory(active(k0).copy(intents = listOf(candidate), importSequence = 2, highestEpoch = 8))
    val delayedOldK1 = event(candidate, requestId = id(11), epoch = 3)

    assertEquals(candidate.keyId, delayedOldK1.keyId)
    assertFalse(matches(store.state, delayedOldK1))
    assertEquals(EndpointUpdates.Effect.REJECTED, accept(store, "old", delayedOldK1))
    assertEquals(k0.requestId, store.state.activeImportId)
    assertEquals(candidate.requestId, store.state.intents.single().requestId)

    assertEquals(EndpointUpdates.Effect.PROMOTED, accept(store, "new", event(candidate)))
    assertEquals(candidate.requestId, store.state.activeImportId)
    assertEquals(3, store.state.activeEpoch)
  }

  @Test
  fun `ordinary lower epoch candidate cannot cross the active epoch guard`() {
    val activeIntent = intent(id(118), sequence = 8, epoch = 9, controlKey = keyA)
    val ordinary = intent(id(119), sequence = 9, epoch = 6, controlKey = keyB)
    val recovery = intent(id(120), sequence = 10, epoch = 6, controlKey = keyC, recovery = true)
    val store = Memory(active(activeIntent).copy(
      intents = listOf(ordinary, recovery), importSequence = 10, highestEpoch = 9,
    ))

    val beforeOrdinaryEvent = store.state
    assertFalse(matches(store.state, event(ordinary)))
    assertEquals(EndpointUpdates.Effect.REJECTED, accept(store, "ordinary-lower-epoch", event(ordinary)))
    assertEquals(beforeOrdinaryEvent, store.state)

    assertEquals(EndpointUpdates.Effect.PROMOTED, accept(store, "authorized-lower-epoch", event(recovery)))
    assertEquals(recovery.requestId, store.state.activeImportId)
    assertEquals(6, store.state.activeEpoch)
  }

  @Test
  fun `superseded same epoch file identity cannot promote but newer same key intent can`() {
    val activeIntent = intent(id(20), 1, 1, keyA)
    val older = intent(id(21), 2, 2, keyB, eligible = false)
    val newer = intent(id(22), 3, 2, keyB, recovery = true)
    val store = Memory(active(activeIntent).copy(intents = listOf(older, newer), importSequence = 3, highestEpoch = 2))

    assertFalse(matches(store.state, event(older)))
    assertEquals(EndpointUpdates.Effect.REJECTED, accept(store, "old-file", event(older)))
    assertEquals(EndpointUpdates.Effect.PROMOTED, accept(store, "new-file", event(newer)))
    assertEquals(newer.requestId, store.state.activeImportId)
    assertEquals(3, store.state.activeImportSeq)
    assertTrue(store.state.intents.isEmpty())
  }

  @Test
  fun `lost enrollment status is recovered by a later matching wake`() {
    val candidate = intent(id(30), 1, 2, keyB)
    val store = Memory(ControlState(intents = listOf(candidate), importSequence = 1, highestEpoch = 2))

    assertEquals(EndpointUpdates.Effect.PROMOTED, accept(
      store, "later-wake", event(candidate, kind = EncryptedPush.Kind.WAKE, code = null),
    ))
    assertEquals(candidate.requestId, store.state.activeImportId)
  }

  @Test
  fun `ordinary lower epoch intent remains eligible until a later sequence confirms`() {
    val lower = intent(id(40), 1, 2, keyB)
    val higher = intent(id(41), 2, 3, keyC)
    val store = Memory(ControlState(intents = listOf(lower, higher), importSequence = 2, highestEpoch = 3))

    assertTrue(matches(store.state, event(lower)))
    assertEquals(EndpointUpdates.Effect.PROMOTED, accept(store, "lower-first", event(lower)))
    assertEquals(1, store.state.activeImportSeq)
    assertEquals(listOf(higher.requestId), store.state.intents.map { it.requestId })

    val restarted = Memory((ControlState.parse(store.state.serialize()) as ControlStateRead.Current).state)
    assertEquals(EndpointUpdates.Effect.PROMOTED, accept(restarted, "higher-next", event(higher)))
    assertEquals(3, restarted.state.activeEpoch)
    assertEquals(2, restarted.state.activeImportSeq)
    assertTrue(restarted.state.intents.isEmpty())
  }

  @Test
  fun `delayed lower sequence cannot roll back a later confirmed import after restart`() {
    val lower = intent(id(42), 1, 2, keyB)
    val higher = intent(id(43), 2, 3, keyC)
    val store = Memory(ControlState(intents = listOf(lower, higher), importSequence = 2, highestEpoch = 3))

    assertEquals(EndpointUpdates.Effect.PROMOTED, accept(store, "higher-first", event(higher)))
    assertEquals(higher.requestId, store.state.activeImportId)
    assertEquals(2, store.state.activeImportSeq)
    assertTrue(store.state.intents.isEmpty())

    val restarted = Memory((ControlState.parse(store.state.serialize()) as ControlStateRead.Current).state)
    assertFalse(matches(restarted.state, event(lower)))
    assertEquals(EndpointUpdates.Effect.REJECTED, accept(restarted, "delayed-lower", event(lower)))
    assertEquals(higher.requestId, restarted.state.activeImportId)
    assertEquals(2, restarted.state.activeImportSeq)
    assertEquals(3, restarted.state.activeEpoch)
  }

  @Test
  fun `v2 epoch match never invents candidate import identity`() {
    val activeIntent = intent(id(50), 1, 1, keyA)
    val candidate = intent(id(51), 2, 2, keyB)
    val store = Memory(active(activeIntent).copy(intents = listOf(candidate), importSequence = 2, highestEpoch = 2))
    val oldEvent = event(candidate, version = 2, code = EncryptedPush.CODE_SUBSCRIPTION_UPDATED)

    assertFalse(EndpointUpdates.identityMatches(store.state, oldEvent, current = null))
    assertEquals(EndpointUpdates.Effect.REJECTED, EndpointUpdates.accepted(store, "legacy", oldEvent, current = null))
    assertEquals(activeIntent.requestId, store.state.activeImportId)
  }

  @Test
  fun `cap counts superseded and same key distinct intent records without evicting`() {
    val records = (1..4).map { n -> intent(id(60 + n), n.toLong(), n.toLong() + 1, keyB, eligible = n == 4) }
    val store = Memory(ControlState(intents = records, importSequence = 4, highestEpoch = 5))
    val failure = try {
      EndpointUpdates.recordIntent(store, fullRecovery = false) { _, sequence -> intent(id(70), sequence, 6, keyC) }
      null
    } catch (e: EnrollmentFile.Invalid) { e.code }

    assertEquals("pending_limit", failure)
    assertEquals(4, store.state.intents.size)
    assertEquals(4, store.state.importSequence)
    assertEquals(records.map { it.requestId }, store.state.intents.map { it.requestId })
  }

  @Test
  fun `sequence overflow refuses and never wraps`() {
    val store = Memory(ControlState(importSequence = Long.MAX_VALUE))
    val failure = try {
      EndpointUpdates.recordIntent(store, fullRecovery = false) { _, sequence -> intent(id(80), sequence, 1, keyA) }
      null
    } catch (e: EnrollmentFile.Invalid) { e.code }
    assertEquals("sequence_exhausted", failure)
    assertEquals(Long.MAX_VALUE, store.state.importSequence)
  }

  @Test
  fun `active v3 endpoint confirmation requires current import identity`() {
    val activeIntent = intent(id(90), 1, 5, keyA)
    val update = ControlUpdate(6, target.endpoint, target.p256dh, target.auth, ByteArray(ControlFrame.HEADER_SIZE), 1)
    val state = active(activeIntent).copy(update = update, highestEpoch = 6)
    val valid = event(activeIntent, epoch = 6, code = EncryptedPush.CODE_SUBSCRIPTION_UPDATED)
    val stale = event(activeIntent.copy(requestId = id(91)), epoch = 6, code = EncryptedPush.CODE_SUBSCRIPTION_UPDATED)

    assertTrue(EndpointUpdates.confirms(state, valid, liveRegistration))
    assertFalse(EndpointUpdates.confirms(state, stale, liveRegistration))
  }

  @Test
  fun `stale send attempt cannot update a replacement frame at the same epoch or cross a generation`() {
    UnifiedPushLifecycle.resetForTests()
    val generation = UnifiedPushLifecycle.currentGeneration()
    val sent = ControlUpdate(6, target.endpoint, target.p256dh, target.auth, byteArrayOf(1, 2), 10)
    val replacement = ControlUpdate(6, target.endpoint, target.p256dh, target.auth, byteArrayOf(3, 4), 11)
    val store = Memory(ControlState(key = keyA, activeEpoch = 5, update = replacement))

    EndpointUpdates.recordAttempt(store, sent, generation, now = 20, error = "IOException")

    assertSame(replacement, store.state.update)
    assertEquals(0, store.state.update!!.attempts)

    runBlocking {
      UnifiedPushLifecycle.withTransition(
        persistIncompleteMarker = {},
        readyAfter = { true },
      ) { }
    }
    store.state = store.state.copy(update = sent)

    EndpointUpdates.recordAttempt(store, sent, generation, now = 30, error = "IOException")

    assertSame(sent, store.state.update)
    assertEquals(0, store.state.update!!.attempts)
  }

  @Test
  fun `candidate account must match the live Signal account before promotion`() {
    val candidate = intent(id(92), 1, 1, keyA)
    val store = Memory(ControlState(intents = listOf(candidate), importSequence = 1, highestEpoch = 1))
    val event = event(candidate)
    val before = store.state
    val anotherAccount = registration(accountId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")

    assertEquals(candidate.keyId, ControlKeyId.derive(candidate.controlKey, candidate.accountId, candidate.deviceId, candidate.instance))
    assertFalse(matches(store.state, event, anotherAccount))
    assertEquals(EndpointUpdates.Effect.REJECTED, accept(store, "wrong-account", event, anotherAccount))
    assertEquals(before, store.state)
  }

  @Test
  fun `candidate device and instance must match the live UnifiedPush registration`() {
    val candidate = intent(id(93), 1, 1, keyA)
    val store = Memory(ControlState(intents = listOf(candidate), importSequence = 1, highestEpoch = 1))
    val event = event(candidate)

    assertFalse(matches(store.state, event, registration(deviceId = 3)))
    assertFalse(matches(store.state, event, registration(instanceId = "replacement-instance")))
    assertFalse(matches(store.state, event, current = null))
    val beforeMismatch = store.state
    assertEquals(EndpointUpdates.Effect.REJECTED, accept(store, "wrong-device", event, registration(deviceId = 3)))
    assertEquals(beforeMismatch, store.state)
    val beforeUnknownContext = store.state
    assertEquals(EndpointUpdates.Effect.REJECTED, accept(store, "missing-live-context", event, current = null))
    assertEquals(beforeUnknownContext, store.state)
    assertTrue(matches(store.state, event, liveRegistration))
    assertEquals(EndpointUpdates.Effect.PROMOTED, accept(store, "matching-live-context", event, liveRegistration))
    assertEquals(candidate.requestId, store.state.activeImportId)
  }

  @Test
  fun `stored active key identity alone cannot confirm after the live account changes`() {
    val activeIntent = intent(id(94), 1, 5, keyA)
    val state = active(activeIntent)
    val event = event(activeIntent, kind = EncryptedPush.Kind.WAKE, code = null)

    assertTrue(matches(state, event, liveRegistration))
    assertFalse(matches(state, event, registration(accountId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")))
    assertFalse(matches(state, event, current = null))
  }
}

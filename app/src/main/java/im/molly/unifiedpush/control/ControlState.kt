package im.molly.unifiedpush.control

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import im.molly.unifiedpush.enrollment.ControlKeyId
import im.molly.unifiedpush.enrollment.EnrollmentFile

sealed interface ControlStateRead {
  data object Absent : ControlStateRead
  data class Legacy(val state: ControlState) : ControlStateRead
  data class Current(val state: ControlState) : ControlStateRead
  data object Corrupt : ControlStateRead

  val stateOrNull: ControlState?
    get() = when (this) {
      Absent -> ControlState()
      is Legacy -> state
      is Current -> state
      Corrupt -> null
    }
}

class CorruptControlStateException : IllegalStateException("Unified Push control state is unavailable")

/** A subscription as the server holds it or as an exported file carries it. Never printed. */
class Target(val endpoint: String, val p256dh: String, val auth: String) {
  fun matches(subscription: ControlFrame.Subscription) =
    endpoint == subscription.endpoint && p256dh == subscription.p256dh && auth == subscription.auth

  override fun equals(other: Any?) = other is Target && endpoint == other.endpoint && p256dh == other.p256dh && auth == other.auth
  override fun hashCode() = listOf(endpoint, p256dh, auth).hashCode()
  override fun toString() = "Target"
}

/** A saved v3 logical import. Secrets stay in the app's private key-value store and never appear in diagnostics. */
data class ImportIntent(
  val requestId: String,
  val sequence: Long,
  val epoch: Long,
  val controlKey: String,
  val keyId: String,
  val target: Target,
  val accountId: String,
  val deviceId: Int,
  val password: String,
  val instance: String,
  val recovery: Boolean,
  val eligible: Boolean = true,
) {
  override fun toString() = "ImportIntent(epoch=$epoch, eligible=$eligible)"
}

/** A pre-v3 export that has no import identity and therefore cannot be promoted by an event. */
data class LegacyPending(val key: String?, val epoch: Long, val target: Target?) {
  override fun toString() = "LegacyPending(epoch=$epoch)"
}

/**
 * The phone's durable view of one registration. `importSequence` never rolls back after an export is published or
 * publication fails. Legacy records retain their active key and any old pending export as UNKNOWN identity.
 */
data class ControlState(
  val key: String? = null,
  val activeKeyId: String? = null,
  val activeImportId: String? = null,
  val activeImportSeq: Long = 0,
  val activeAccountId: String? = null,
  val activeDeviceId: Int? = null,
  val activeInstance: String? = null,
  val activeEpoch: Long = 0,
  val highestEpoch: Long = 0,
  val importSequence: Long = 0,
  val intents: List<ImportIntent> = emptyList(),
  val legacyPending: LegacyPending? = null,
  val update: ControlUpdate? = null,
  val serverTarget: Target? = null,
) {
  /** Compatibility view for older health and settings code; never used as confirmation identity. */
  val pendingEpoch: Long get() = intents.maxOfOrNull { it.epoch } ?: (legacyPending?.epoch ?: 0)
  val pendingKey: String? get() = intents.maxByOrNull { it.sequence }?.controlKey ?: legacyPending?.key
  val pendingTarget: Target? get() = intents.maxByOrNull { it.sequence }?.target ?: legacyPending?.target
  val pendingIntentCount: Int get() = intents.size + if (legacyPending != null) 1 else 0

  override fun toString() = "ControlState(active=$activeEpoch, pending=${pendingEpoch}, highest=$highestEpoch)"

  fun serialize(): String = mapper.writeValueAsString(
    linkedMapOf(
      "format_version" to FORMAT_VERSION,
      "key" to key,
      "active_key_id" to activeKeyId,
      "active_import_id" to activeImportId,
      "active_import_seq" to activeImportSeq,
      "active_account_id" to activeAccountId,
      "active_device_id" to activeDeviceId,
      "active_instance" to activeInstance,
      "active_epoch" to activeEpoch,
      "highest_epoch" to highestEpoch,
      "import_sequence" to importSequence,
      "intents" to intents.map(::intentMap),
      "legacy_pending" to legacyPending?.let(::legacyPendingMap),
      "update" to update?.serialize(),
      "server_target" to serverTarget?.let(::targetMap),
    )
  )

  companion object {
    const val MAX_PENDING_INTENTS = 4
    const val MAX_IMPORT_SEQUENCE = Long.MAX_VALUE
    private const val FORMAT_VERSION = 2
    private const val MAX_EPOCH = 9007199254740991L
    private val mapper = ObjectMapper(JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION))
    private val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val UUID_ANY = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val PASSWORD = Regex("[ -~]{16,256}")
    private val INSTANCE = Regex("[A-Za-z0-9._:-]{1,128}")
    private val LEGACY_FIELDS = setOf("key", "pendingKey", "pendingEpoch", "pendingTarget", "activeEpoch", "highestEpoch", "update", "serverTarget")
    private val PREVIOUS_LOCAL_FIELDS = LEGACY_FIELDS + "format_version"
    private val CURRENT_FIELDS = setOf(
      "format_version", "key", "active_key_id", "active_import_id", "active_import_seq", "active_epoch", "highest_epoch",
      "active_account_id", "active_device_id", "active_instance", "import_sequence", "intents", "legacy_pending", "update", "server_target",
    )

    private fun targetMap(t: Target) = mapOf("endpoint" to t.endpoint, "p256dh" to t.p256dh, "auth" to t.auth)
    private fun intentMap(i: ImportIntent) = mapOf(
      "request_id" to i.requestId, "sequence" to i.sequence, "epoch" to i.epoch,
      "control_key" to i.controlKey, "key_id" to i.keyId, "target" to targetMap(i.target),
      "account_id" to i.accountId, "device_id" to i.deviceId, "password" to i.password,
      "instance" to i.instance, "recovery" to i.recovery, "eligible" to i.eligible,
    )
    private fun legacyPendingMap(i: LegacyPending) = mapOf("key" to i.key, "epoch" to i.epoch, "target" to i.target?.let(::targetMap))

    private fun exactFields(node: JsonNode, expected: Set<String>) = node.isObject && node.fieldNames().asSequence().toSet() == expected

    private fun requiredNode(node: JsonNode, field: String): JsonNode = node[field] ?: throw IllegalArgumentException("Missing field")
    private fun requiredText(node: JsonNode, field: String): String = requiredNode(node, field).takeIf { it.isTextual }?.asText() ?: throw IllegalArgumentException("Invalid text")
    private fun nullableText(node: JsonNode, field: String): String? = requiredNode(node, field).let {
      if (it.isNull) null else it.takeIf(JsonNode::isTextual)?.asText() ?: throw IllegalArgumentException("Invalid nullable text")
    }
    private fun requiredLong(node: JsonNode, field: String, minimum: Long = 0): Long = requiredNode(node, field).let {
      require(it.isIntegralNumber && it.canConvertToLong())
      it.asLong().also { n -> require(n >= minimum) }
    }
    private fun requiredInt(node: JsonNode, field: String, minimum: Int = 0): Int = requiredNode(node, field).let {
      require(it.isIntegralNumber && it.canConvertToInt())
      it.asInt().also { n -> require(n >= minimum) }
    }
    private fun requiredBoolean(node: JsonNode, field: String) = requiredNode(node, field).takeIf { it.isBoolean }?.asBoolean() ?: throw IllegalArgumentException("Invalid boolean")

    private fun target(node: JsonNode?): Target? {
      require(node != null)
      if (node.isNull) return null
      require(exactFields(node, setOf("endpoint", "p256dh", "auth")))
      val value = Target(requiredText(node, "endpoint"), requiredText(node, "p256dh"), requiredText(node, "auth"))
      EnrollmentFile.requireEndpoint(value.endpoint)
      val p256dh = EnrollmentFile.canonicalKey(value.p256dh, 65)
      EnrollmentFile.requireOnCurve(java.util.Base64.getUrlDecoder().decode(p256dh))
      EnrollmentFile.canonicalKey(value.auth, 16)
      return value
    }

    private fun parseIntent(node: JsonNode): ImportIntent {
      require(exactFields(node, setOf("request_id", "sequence", "epoch", "control_key", "key_id", "target", "account_id", "device_id", "password", "instance", "recovery", "eligible")))
      val intentTarget = target(node["target"]) ?: throw IllegalArgumentException("Missing intent target")
      return ImportIntent(
        requestId = requiredText(node, "request_id"),
        sequence = requiredLong(node, "sequence", 1),
        epoch = requiredLong(node, "epoch", 1),
        controlKey = requiredText(node, "control_key"),
        keyId = requiredText(node, "key_id"),
        target = intentTarget,
        accountId = requiredText(node, "account_id"),
        deviceId = requiredInt(node, "device_id", 2),
        password = requiredText(node, "password"),
        instance = requiredText(node, "instance"),
        recovery = requiredBoolean(node, "recovery"),
        eligible = requiredBoolean(node, "eligible"),
      ).also {
        require(UUID_V4.matches(it.requestId) && UUID_ANY.matches(it.accountId) && PASSWORD.matches(it.password) && INSTANCE.matches(it.instance))
        require(it.controlKey == EnrollmentFile.canonicalKey(it.controlKey, 32) && ControlKeyId.isCanonical(it.keyId))
        require(ControlKeyId.derive(it.controlKey, it.accountId, it.deviceId, it.instance) == it.keyId)
      }
    }

    private fun parseLegacyPending(node: JsonNode?): LegacyPending? {
      require(node != null)
      if (node.isNull) return null
      require(exactFields(node, setOf("key", "epoch", "target")))
      val epoch = requiredLong(node, "epoch", 1)
      return LegacyPending(nullableText(node, "key"), epoch, target(node["target"]))
    }

    private fun parseLegacy(root: JsonNode): ControlState {
      val pendingEpoch = requiredLong(root, "pendingEpoch")
      val pendingKey = nullableText(root, "pendingKey")
      val pendingTarget = target(root["pendingTarget"])
      require(pendingEpoch <= MAX_EPOCH)
      require((pendingEpoch == 0L) == (pendingKey == null && pendingTarget == null) || pendingEpoch > 0)
      val updateText = nullableText(root, "update")
      return ControlState(
        key = nullableText(root, "key"),
        activeEpoch = requiredLong(root, "activeEpoch"),
        highestEpoch = requiredLong(root, "highestEpoch"),
        legacyPending = if (pendingEpoch > 0) LegacyPending(pendingKey, pendingEpoch, pendingTarget) else null,
        update = updateText?.let { ControlUpdate.parse(it) ?: throw IllegalArgumentException("Invalid update") },
        serverTarget = target(root["serverTarget"]),
      ).also { require(it.activeEpoch <= MAX_EPOCH && it.highestEpoch in it.activeEpoch..MAX_EPOCH) }
    }

    private fun parseCurrent(root: JsonNode): ControlState {
      require(requiredLong(root, "format_version", 1) == FORMAT_VERSION.toLong())
      val intentsNode = requiredNode(root, "intents")
      require(intentsNode.isArray)
      val intents = intentsNode.map(::parseIntent)
      require(intents.size <= MAX_PENDING_INTENTS)
      val state = ControlState(
        key = nullableText(root, "key"),
        activeKeyId = nullableText(root, "active_key_id"),
        activeImportId = nullableText(root, "active_import_id"),
        activeImportSeq = requiredLong(root, "active_import_seq"),
        activeAccountId = nullableText(root, "active_account_id"),
        activeDeviceId = requiredNode(root, "active_device_id").let { if (it.isNull) null else requiredInt(root, "active_device_id", 2) },
        activeInstance = nullableText(root, "active_instance"),
        activeEpoch = requiredLong(root, "active_epoch"),
        highestEpoch = requiredLong(root, "highest_epoch"),
        importSequence = requiredLong(root, "import_sequence"),
        intents = intents,
        legacyPending = parseLegacyPending(root["legacy_pending"]),
        update = nullableText(root, "update")?.let { ControlUpdate.parse(it) ?: throw IllegalArgumentException("Invalid update") },
        serverTarget = target(root["server_target"]),
      )
      require(state.activeEpoch <= MAX_EPOCH && state.highestEpoch in state.activeEpoch..MAX_EPOCH)
      require(state.importSequence >= state.activeImportSeq)
      require(state.importSequence <= MAX_IMPORT_SEQUENCE)
      require(state.intents.map { it.requestId }.toSet().size == state.intents.size)
      require(state.intents.map { it.sequence }.toSet().size == state.intents.size)
      require(state.intents.all { it.sequence > state.activeImportSeq && it.sequence <= state.importSequence && it.epoch <= MAX_EPOCH })
      require(state.intents.map { it.sequence } == state.intents.map { it.sequence }.sorted())
      require(state.activeImportId == null || state.intents.none { it.requestId == state.activeImportId })
      require((state.activeImportId == null) == (state.activeImportSeq == 0L))
      require(state.activeImportSeq == 0L || state.activeImportId != null && state.activeKeyId != null)
      require((state.activeImportId == null) == (state.activeKeyId == null))
      require((state.activeImportId == null) == (state.activeAccountId == null))
      require((state.activeImportId == null) == (state.activeDeviceId == null))
      require((state.activeImportId == null) == (state.activeInstance == null))
      require(state.activeImportId == null || UUID_V4.matches(state.activeImportId))
      require(state.activeImportId == null || state.activeEpoch >= 1)
      require(state.activeKeyId == null || ControlKeyId.isCanonical(state.activeKeyId))
      require(state.key == null || state.key == EnrollmentFile.canonicalKey(state.key, 32))
      if (state.activeImportId != null) {
        require(state.key != null && UUID_ANY.matches(state.activeAccountId!!) && INSTANCE.matches(state.activeInstance!!))
        require(ControlKeyId.derive(state.key, state.activeAccountId, state.activeDeviceId!!, state.activeInstance) == state.activeKeyId)
      }
      require(state.legacyPending?.key == null || state.legacyPending.key == EnrollmentFile.canonicalKey(state.legacyPending.key, 32))
      require(state.update == null || state.key != null)
      require(state.legacyPending == null || state.legacyPending.epoch in 1..MAX_EPOCH)
      require(state.pendingIntentCount <= MAX_PENDING_INTENTS)
      return state
    }

    /** Missing serialized state, recognized old singleton state, current state and corruption stay distinct. */
    fun parse(text: String?): ControlStateRead {
      if (text == null) return ControlStateRead.Absent
      return try {
        val root = mapper.factory.createParser(text).use { parser ->
          val parsed = mapper.readTree<JsonNode>(parser)
          require(parser.nextToken() == null)
          parsed
        }
        require(root != null && root.isObject)
        val fields = root.fieldNames().asSequence().toSet()
        when {
          fields == LEGACY_FIELDS -> ControlStateRead.Legacy(parseLegacy(root))
          fields == PREVIOUS_LOCAL_FIELDS && requiredLong(root, "format_version", 1) == 1L -> ControlStateRead.Legacy(parseLegacy(root))
          fields == CURRENT_FIELDS -> ControlStateRead.Current(parseCurrent(root))
          else -> ControlStateRead.Corrupt
        }
      } catch (e: Exception) {
        ControlStateRead.Corrupt
      }
    }
  }
}

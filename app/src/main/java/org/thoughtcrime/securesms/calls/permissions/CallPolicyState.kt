/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.calls.permissions

/** INHERIT is absence of an override, never a copy of today's device default. */
enum class CallPermission { INHERIT, ALLOW, BLOCK }

enum class CallContext { DIRECT, GROUP, CALL_LINK }

data class CallPermissionSettings(
  val available: Boolean,
  val allCallingDisabled: Boolean,
  val directDefault: CallPermission,
  val groupDefault: CallPermission,
  val generation: Long
)

/** Small installation-local policy record. There are deliberately no unimplemented capabilities. */
internal data class CallPolicyState(
  val account: String? = null,
  val installation: String? = null,
  val allCallingDisabled: Boolean = false,
  val directDefault: CallPermission = CallPermission.ALLOW,
  val groupDefault: CallPermission = CallPermission.ALLOW,
  val overrides: Map<String, CallPermission> = emptyMap()
) {
  fun override(context: CallContext, recipient: Long): CallPermission = overrides[key(context, recipient)] ?: CallPermission.INHERIT

  fun allowsIncoming(context: CallContext, recipient: Long): Boolean {
    if (allCallingDisabled || context == CallContext.CALL_LINK) return false
    val permission = override(context, recipient)
    return (if (permission == CallPermission.INHERIT) default(context) else permission) == CallPermission.ALLOW
  }

  fun default(context: CallContext): CallPermission = when (context) {
    CallContext.DIRECT -> directDefault
    CallContext.GROUP -> groupDefault
    CallContext.CALL_LINK -> CallPermission.BLOCK
  }

  fun withOverride(context: CallContext, recipient: Long, permission: CallPermission): CallPolicyState {
    require(context != CallContext.CALL_LINK && recipient > 0)
    val changed = overrides.toMutableMap()
    if (permission == CallPermission.INHERIT) changed.remove(key(context, recipient)) else changed[key(context, recipient)] = permission
    return copy(overrides = changed)
  }

  /** A rolled-back recipient merge must also remain safe: retain the restrictive result on both IDs. */
  fun merge(primary: Long, secondary: Long): CallPolicyState {
    var result = this
    for (context in listOf(CallContext.DIRECT, CallContext.GROUP)) {
      val a = override(context, primary)
      val b = override(context, secondary)
      val merged = when {
        a == CallPermission.BLOCK || b == CallPermission.BLOCK -> CallPermission.BLOCK
        a == CallPermission.INHERIT || b == CallPermission.INHERIT -> CallPermission.INHERIT
        else -> CallPermission.ALLOW
      }
      result = result.withOverride(context, primary, merged).withOverride(context, secondary, merged)
    }
    return result
  }

  /** Recipient IDs cannot cross account/restore boundaries. Lost restrictions become device defaults. */
  fun resetRecipients(newAccount: String?): CallPolicyState = copy(
    account = newAccount,
    directDefault = if (hasBlock(CallContext.DIRECT)) CallPermission.BLOCK else directDefault,
    groupDefault = if (hasBlock(CallContext.GROUP)) CallPermission.BLOCK else groupDefault,
    overrides = emptyMap()
  )

  private fun hasBlock(context: CallContext): Boolean = overrides.any { (key, value) -> key.startsWith("${context.name}:") && value == CallPermission.BLOCK }

  companion object {
    fun key(context: CallContext, recipient: Long): String = "${context.name}:$recipient"
  }
}

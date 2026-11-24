/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.permissions

import java.util.UUID

/** Versioned and bounded local record; unknown versions/values must never turn into ALLOW. */
internal object CallPolicyCodec {
  private const val MAX_SIZE = 512 * 1024
  private const val MAX_OVERRIDES = 10_000

  fun encode(state: CallPolicyState): String {
    require(state.overrides.size <= MAX_OVERRIDES)
    val text = (listOf("1", state.account ?: "-", state.installation ?: "-", if (state.allCallingDisabled) "1" else "0", state.directDefault.name, state.groupDefault.name) +
      state.overrides.toSortedMap().map { (key, permission) -> "$key=${permission.name}" }).joinToString("\n")
    require(text.length <= MAX_SIZE)
    return text
  }

  fun decode(text: String): CallPolicyState {
    require(text.length <= MAX_SIZE)
    val lines = text.split('\n')
    require(lines.size in 6..(6 + MAX_OVERRIDES) && lines[0] == "1")
    val account = lines[1].takeUnless { it == "-" }?.also { require(UUID.fromString(it).toString() == it) }
    val installation = lines[2].takeUnless { it == "-" }?.also { require(UUID.fromString(it).toString() == it) }
    val disabled = when (lines[3]) { "1" -> true; "0" -> false; else -> error("Invalid call prohibition") }
    fun explicit(value: String): CallPermission = CallPermission.valueOf(value).also { require(it != CallPermission.INHERIT) }
    val overrides = mutableMapOf<String, CallPermission>()
    for (line in lines.drop(6)) {
      val pair = line.split('=')
      require(pair.size == 2)
      val key = pair[0].split(':')
      require(key.size == 2 && key[0] in listOf("DIRECT", "GROUP") && key[1].toLong() > 0)
      require(key[1].toLong().toString() == key[1] && !overrides.containsKey(pair[0]))
      overrides[pair[0]] = explicit(pair[1])
    }
    return CallPolicyState(account, installation, disabled, explicit(lines[4]), explicit(lines[5]), overrides)
  }
}

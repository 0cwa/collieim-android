package im.molly.unifiedpush.control

import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues

class UnifiedPushControlStore(private val values: UnifiedPushValues) : ControlStore {
  override fun read() = values.controlState
  override fun mutate(replay: String?, block: (ControlState) -> ControlState) = values.mutateControl(replay, block)
  override fun mutateWithReplayIfAbsent(replayIfAbsent: String, block: (ControlState) -> ControlState) =
    values.mutateControlWithReplayIfAbsent(replayIfAbsent, block)
}

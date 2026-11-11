package im.molly.unifiedpush.control

/** Where the [ControlState] lives. */
interface ControlStore {
  fun read(): ControlState

  /**
   * The only way to change the state. Holds one lock, hands [block] the state as it is now (not as the caller last
   * saw it), and writes the result once and durably. [replay] is the encrypted-push replay state to store in the
   * same write. [block] must be quick and free of side effects, and may return its argument to change nothing.
   */
  fun mutate(replay: String? = null, block: (ControlState) -> ControlState): ControlState
}

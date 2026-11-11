package im.molly.unifiedpush.control

import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues

class UnifiedPushControlStore(private val values: UnifiedPushValues) : ControlStore {
  override val controlKey get() = values.controlKey
  override val enrollmentEpoch get() = values.enrollmentEpoch
  override val pendingEnrollmentEpoch get() = values.pendingEnrollmentEpoch
  override val highestEpoch get() = values.controlHighestEpoch

  /** An update at or below the active epoch was confirmed already; a late write cannot bring it back. */
  override val update get() = ControlUpdate.parse(values.controlUpdate)?.takeIf { it.epoch > values.enrollmentEpoch }

  override fun commit(update: ControlUpdate) = values.commitControlUpdate(update.serialize(), update.epoch)

  override fun replace(update: ControlUpdate) {
    synchronized(this::class.java) {
      if (this.update?.epoch == update.epoch) values.controlUpdate = update.serialize()
    }
  }
}

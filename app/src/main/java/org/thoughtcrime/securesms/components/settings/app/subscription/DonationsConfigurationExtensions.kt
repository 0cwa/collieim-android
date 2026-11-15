package org.thoughtcrime.securesms.components.settings.app.subscription

import org.signal.core.models.badges.Badge
import org.thoughtcrime.securesms.badges.Badges
import org.whispersystems.signalservice.internal.push.SubscriptionsConfiguration
import org.whispersystems.signalservice.internal.push.SubscriptionsConfiguration.Companion.BOOST_LEVEL
import org.whispersystems.signalservice.internal.push.SubscriptionsConfiguration.Companion.GIFT_LEVEL
import org.whispersystems.signalservice.internal.push.SubscriptionsConfiguration.Companion.SUBSCRIPTION_LEVELS

/** Resolves the artwork for an already-received gift; it does not initiate a purchase. */
fun SubscriptionsConfiguration.getBadge(level: Int): Badge {
  require(level == GIFT_LEVEL || level == BOOST_LEVEL || SUBSCRIPTION_LEVELS.contains(level))
  return Badges.fromServiceBadge(levels[level]!!.badge!!)
}

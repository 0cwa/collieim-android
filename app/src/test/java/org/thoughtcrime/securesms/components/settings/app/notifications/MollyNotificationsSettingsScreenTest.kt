package org.thoughtcrime.securesms.components.settings.app.notifications

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.common.ConnectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MollyNotificationsSettingsScreenTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val coreUiDependencies = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())
  private val context: Application = ApplicationProvider.getApplicationContext()

  @Test
  fun `linked devices cannot select or configure UnifiedPush`() {
    show(state(isLinkedDevice = true))
    scrollTo(R.string.NotificationsSettingsFragment__delivery_service)
    compose.onNodeWithText(text(R.string.NotificationsSettingsFragment__delivery_service)).performClick()
    compose.onNodeWithText(text(NotificationDeliveryMethod.UNIFIEDPUSH.stringId)).assertDoesNotExist()
    compose.onNodeWithText(text(R.string.NotificationsSettingsFragment__configure_unifiedpush)).assertDoesNotExist()
  }

  @Test
  fun `choosing FCM reports transition and UnifiedPush configuration stays disabled`() {
    var transition: Pair<NotificationDeliveryMethod, NotificationDeliveryMethod>? = null
    val callbacks = object : NotificationsSettingsCallbacks {
      override fun onNotificationMethodChanged(fromToMethods: Pair<NotificationDeliveryMethod, NotificationDeliveryMethod>) {
        transition = fromToMethods
      }
    }
    show(state(), callbacks)
    scrollTo(R.string.NotificationsSettingsFragment__configure_unifiedpush)
    compose.onNodeWithText(text(R.string.NotificationsSettingsFragment__configure_unifiedpush)).assertIsNotEnabled()
    scrollTo(R.string.NotificationsSettingsFragment__delivery_service)
    compose.onNodeWithText(text(R.string.NotificationsSettingsFragment__delivery_service)).performClick()
    compose.onNodeWithText(text(NotificationDeliveryMethod.FCM.stringId)).performClick()
    compose.runOnIdle { assertEquals(NotificationDeliveryMethod.WEBSOCKET to NotificationDeliveryMethod.FCM, transition) }
  }

  @Test
  fun `unsupported locked notifications reach the callback as unsupported`() {
    var supported: Boolean? = null
    val callbacks = object : NotificationsSettingsCallbacks {
      override fun setNotifyNewActivityWhileLocked(enabled: Boolean, isSupported: Boolean) {
        supported = isSupported
      }
    }
    show(state(canEnableNotifyWhileLocked = false), callbacks)
    scrollTo(R.string.NotificationsSettingsFragment__new_activity_while_locked)
    compose.onNodeWithText(text(R.string.NotificationsSettingsFragment__new_activity_while_locked)).performClick()
    compose.runOnIdle { assertFalse(requireNotNull(supported)) }
  }

  @Test
  fun `unavailable selected service exposes an accessible warning`() {
    val current = mutableStateOf(state().copy(preferredNotificationMethod = NotificationDeliveryMethod.FCM, canReceiveFcm = false))
    compose.setContent { SignalTheme { NotificationsSettingsScreen(current.value, NotificationsSettingsCallbacks.Empty, DeviceState(35, true)) } }
    scrollTo(R.string.NotificationsSettingsFragment__delivery_service)
    val description = text(R.string.notification_delivery_service_unavailable)
    compose.onNodeWithContentDescription(description).assertIsDisplayed()

    compose.runOnIdle { current.value = current.value.copy(preferredNotificationMethod = NotificationDeliveryMethod.UNIFIEDPUSH, canReceiveUnifiedPush = false) }
    compose.onNodeWithContentDescription(description).assertIsDisplayed()

    compose.runOnIdle { current.value = current.value.copy(preferredNotificationMethod = NotificationDeliveryMethod.WEBSOCKET) }
    compose.onNodeWithContentDescription(description).assertDoesNotExist()
  }

  @Test
  fun `clearing a play services error prevents a repeated callback`() {
    val current = mutableStateOf(state(playServicesErrorCode = ConnectionResult.SERVICE_UPDATING))
    val errors = mutableListOf<Int>()
    val callbacks = object : NotificationsSettingsCallbacks {
      override fun onPlayServicesError(errorCode: Int) {
        errors += errorCode
        current.value = current.value.copy(playServicesErrorCode = null)
      }
    }
    compose.setContent { SignalTheme { NotificationsSettingsScreen(current.value, callbacks, DeviceState(35, true)) } }
    compose.waitForIdle()
    compose.runOnIdle { assertEquals(listOf(ConnectionResult.SERVICE_UPDATING), errors) }
  }

  private fun show(state: NotificationsSettingsState, callbacks: NotificationsSettingsCallbacks = NotificationsSettingsCallbacks.Empty) {
    compose.setContent { SignalTheme { NotificationsSettingsScreen(state, callbacks, DeviceState(35, true)) } }
  }

  private fun scrollTo(id: Int) {
    compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(id)))
  }

  private fun text(id: Int): String = context.getString(id)

  private fun state(isLinkedDevice: Boolean = false, canEnableNotifyWhileLocked: Boolean = true, playServicesErrorCode: Int? = null) = NotificationsSettingsState(
    messageNotificationsState = MessageNotificationsState(
      notificationsEnabled = true, canEnableNotifications = true, sound = Uri.EMPTY, vibrateEnabled = true,
      ledColor = "blue", ledBlink = "", inChatSoundsEnabled = true, repeatAlerts = 1, messagePrivacy = "",
      priority = 1, troubleshootNotifications = true, reactionNotificationEnabled = true, unreadReminderEnabled = true,
      allowCallsWhileMuted = false, allowMentionsWhileMuted = false, allowRepliesWhileMuted = false
    ),
    callNotificationsState = CallNotificationsState(true, true, Uri.EMPTY, true),
    notifyWhileLocked = false,
    canEnableNotifyWhileLocked = canEnableNotifyWhileLocked,
    isLinkedDevice = isLinkedDevice,
    preferredNotificationMethod = NotificationDeliveryMethod.WEBSOCKET,
    playServicesErrorCode = playServicesErrorCode,
    canReceiveFcm = true,
    canReceiveUnifiedPush = true,
    notifyWhenContactJoinsSignal = true
  )
}

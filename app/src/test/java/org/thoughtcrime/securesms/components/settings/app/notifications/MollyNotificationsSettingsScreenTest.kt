package org.thoughtcrime.securesms.components.settings.app.notifications

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.common.ConnectionResult
import im.molly.unifiedpush.enrollment.RelayDisclosure
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod
import org.signal.appsettings.R as AppSettingsR

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MollyNotificationsSettingsScreenTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val coreUiDependencies = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())
  private val context: Application = ApplicationProvider.getApplicationContext()

  @Test
  fun `setup opens the push section with an accessible heading and working delivery picker`() {
    var selected: NotificationDeliveryMethod? = null
    val callbacks = object : NotificationsSettingsCallbacks {
      override fun onNotificationMethodChanged(fromToMethods: Pair<NotificationDeliveryMethod, NotificationDeliveryMethod>, disclosure: RelayDisclosure.Snapshot?) {
        selected = fromToMethods.second
      }
    }
    show(state(), callbacks, focusPushNotifications = true)

    val title = hasText(text(R.string.NotificationsSettingsFragment__push_notifications))
    compose.onNode(isHeading() and (title or hasAnyDescendant(title))).assertIsDisplayed()
    compose.onNodeWithText(text(R.string.CollieSetup__delivery_title)).assertIsDisplayed()
    compose.onNodeWithText(text(R.string.CollieSetup__delivery_settings_hint)).assertIsDisplayed()
    compose.onNodeWithText(text(R.string.NotificationsSettingsFragment__delivery_service))
      .assertIsDisplayed()
      .assertHasClickAction()
      .performClick()
    compose.onNodeWithText(text(NotificationDeliveryMethod.FCM.stringId)).performClick()
    compose.runOnIdle { assertEquals(NotificationDeliveryMethod.FCM, selected) }
    compose.onNodeWithText(text(R.string.CollieSetup__delivery_title)).assertIsDisplayed()
  }

  @Test
  fun `ordinary notifications entry starts at the top without setup guidance`() {
    show(state())

    compose.onNodeWithText(text(AppSettingsR.string.preferences__enable_notifications)).assertIsDisplayed()
    scrollTo(R.string.NotificationsSettingsFragment__push_notifications)
    compose.onNodeWithText(text(R.string.NotificationsSettingsFragment__push_notifications)).assertIsDisplayed()
    compose.onNodeWithText(text(R.string.CollieSetup__delivery_title)).assertDoesNotExist()
    compose.onNodeWithText(text(R.string.CollieSetup__delivery_settings_hint)).assertDoesNotExist()
  }

  @Test
  fun `setup finds push controls when permission and older Android rows change their position`() {
    show(
      state(canEnableNotifications = false),
      deviceState = DeviceState(21, false),
      focusPushNotifications = true
    )

    compose.onNodeWithText(text(R.string.NotificationsSettingsFragment__push_notifications)).assertIsDisplayed()
    compose.onNodeWithText(text(R.string.CollieSetup__delivery_title)).assertIsDisplayed()
    compose.onNodeWithText(text(R.string.NotificationsSettingsFragment__delivery_service)).assertIsDisplayed()
  }

  @Test
  fun `setup opens push controls on linked devices without offering UnifiedPush configuration`() {
    show(state(isLinkedDevice = true), focusPushNotifications = true)

    compose.onNodeWithText(text(R.string.NotificationsSettingsFragment__push_notifications)).assertIsDisplayed()
    compose.onNodeWithText(text(R.string.NotificationsSettingsFragment__delivery_service)).assertIsDisplayed().performClick()
    compose.onNodeWithText(text(NotificationDeliveryMethod.UNIFIEDPUSH.stringId)).assertDoesNotExist()
    compose.onNodeWithText(text(R.string.NotificationsSettingsFragment__configure_unifiedpush)).assertDoesNotExist()
  }

  @Test
  fun `refreshing notification state does not pull the user back to push controls`() {
    val current = mutableStateOf(state())
    compose.setContent {
      SignalTheme {
        NotificationsSettingsScreen(current.value, NotificationsSettingsCallbacks.Empty, DeviceState(35, true), focusPushNotifications = true)
      }
    }
    compose.onNodeWithText(text(R.string.CollieSetup__delivery_title)).assertIsDisplayed()
    compose.onNode(hasScrollAction()).performScrollToIndex(0)

    compose.runOnIdle { current.value = current.value.copy(notifyWhenContactJoinsSignal = false, canReceiveFcm = false) }

    compose.onNodeWithText(text(AppSettingsR.string.preferences__enable_notifications)).assertIsDisplayed()
    scrollTo(R.string.NotificationsSettingsFragment__push_notifications)
    compose.onNodeWithText(text(R.string.CollieSetup__delivery_title)).assertIsDisplayed()
  }

  @Test
  fun `restoring the screen keeps the user scroll position after the setup jump`() {
    val restoration = StateRestorationTester(compose)
    restoration.setContent {
      SignalTheme {
        NotificationsSettingsScreen(state(), NotificationsSettingsCallbacks.Empty, DeviceState(35, true), focusPushNotifications = true)
      }
    }
    compose.onNodeWithText(text(R.string.CollieSetup__delivery_title)).assertIsDisplayed()
    compose.onNode(hasScrollAction()).performScrollToIndex(0)
    compose.onNodeWithText(text(AppSettingsR.string.preferences__enable_notifications)).assertIsDisplayed()

    restoration.emulateSavedInstanceStateRestore()

    compose.onNodeWithText(text(AppSettingsR.string.preferences__enable_notifications)).assertIsDisplayed()
    scrollTo(R.string.NotificationsSettingsFragment__push_notifications)
    compose.onNodeWithText(text(R.string.CollieSetup__delivery_title)).assertIsDisplayed()
  }

  @Test
  fun `large text in a short viewport starts at the push heading and keeps controls reachable`() {
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        SignalTheme {
          Box(Modifier.width(320.dp).height(480.dp)) {
            NotificationsSettingsScreen(state(), NotificationsSettingsCallbacks.Empty, DeviceState(35, true), focusPushNotifications = true)
          }
        }
      }
    }

    compose.onNodeWithText(text(R.string.NotificationsSettingsFragment__push_notifications)).assertIsDisplayed()
    compose.onNodeWithText(text(R.string.NotificationsSettingsFragment__delivery_service))
      .performScrollTo()
      .assertIsDisplayed()
      .assertHasClickAction()
    compose.onNodeWithText(text(R.string.NotificationsSettingsFragment__configure_unifiedpush))
      .performScrollTo()
      .assertIsDisplayed()
  }

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
      override fun onNotificationMethodChanged(fromToMethods: Pair<NotificationDeliveryMethod, NotificationDeliveryMethod>, disclosure: RelayDisclosure.Snapshot?) {
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
  fun `choosing UnifiedPush carries the authorization attached to the rendered screen`() {
    val disclosure = mockk<RelayDisclosure.Snapshot>()
    var selectedDisclosure: RelayDisclosure.Snapshot? = null
    val callbacks = object : NotificationsSettingsCallbacks {
      override fun onNotificationMethodChanged(fromToMethods: Pair<NotificationDeliveryMethod, NotificationDeliveryMethod>, disclosure: RelayDisclosure.Snapshot?) {
        assertEquals(NotificationDeliveryMethod.WEBSOCKET to NotificationDeliveryMethod.UNIFIEDPUSH, fromToMethods)
        selectedDisclosure = disclosure
      }
    }
    show(state().copy(relayDisclosure = disclosure), callbacks)
    scrollTo(R.string.NotificationsSettingsFragment__delivery_service)
    compose.onNodeWithText(text(R.string.NotificationsSettingsFragment__delivery_service)).performClick()
    compose.onNodeWithText(text(NotificationDeliveryMethod.UNIFIEDPUSH.stringId)).performClick()
    compose.runOnIdle { assertSame(disclosure, selectedDisclosure) }
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

  private fun show(
    state: NotificationsSettingsState,
    callbacks: NotificationsSettingsCallbacks = NotificationsSettingsCallbacks.Empty,
    deviceState: DeviceState = DeviceState(35, true),
    focusPushNotifications: Boolean = false
  ) {
    compose.setContent { SignalTheme { NotificationsSettingsScreen(state, callbacks, deviceState, focusPushNotifications) } }
  }

  private fun scrollTo(id: Int) {
    compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(id)))
  }

  private fun text(id: Int): String = context.getString(id)

  private fun state(
    isLinkedDevice: Boolean = false,
    canEnableNotifyWhileLocked: Boolean = true,
    playServicesErrorCode: Int? = null,
    canEnableNotifications: Boolean = true
  ) = NotificationsSettingsState(
    messageNotificationsState = MessageNotificationsState(
      notificationsEnabled = true, canEnableNotifications = canEnableNotifications, sound = Uri.EMPTY, vibrateEnabled = true,
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

package org.thoughtcrime.securesms.components.settings.app.notifications

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.gcm.FcmUtil
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod
import org.thoughtcrime.securesms.testutil.DirectExecutor
import org.thoughtcrime.securesms.testutil.EmptyLogger
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule

class NotificationMethodsTest {
  @get:Rule val signalStore = MockSignalStoreRule()

  @Before fun setup() {
    Log.initialize(EmptyLogger())
    mockkStatic(FcmUtil::class)
    every { FcmUtil.isSupported() } returns false
    every { signalStore.account.fcmEnabled } returns true
  }

  @After fun cleanup() = unmockkStatic(FcmUtil::class)

  @Test fun `Firebase-free primary device offers websocket and UnifiedPush`() {
    assertEquals(listOf(NotificationDeliveryMethod.WEBSOCKET, NotificationDeliveryMethod.UNIFIEDPUSH), availableNotificationMethods(false, false))
  }

  @Test fun `default linked device offers FCM and websocket`() {
    assertEquals(listOf(NotificationDeliveryMethod.FCM, NotificationDeliveryMethod.WEBSOCKET), availableNotificationMethods(true, true))
  }

  @Test fun `implicit FCM choice survives Firebase-free disabling FCM and returning to Firebase`() {
    val store = existingSettingsStore()
    val context = mockk<Context>()
    val settings = SettingsValues(store, context)

    assertEquals(NotificationDeliveryMethod.WEBSOCKET, settings.preferredNotificationMethod)
    every { signalStore.account.fcmEnabled } returns false
    every { FcmUtil.isSupported() } returns true

    assertEquals(NotificationDeliveryMethod.FCM, SettingsValues(store, context).preferredNotificationMethod)
  }

  @Test fun `explicit websocket and UnifiedPush choices survive Firebase-free round trip`() {
    listOf(NotificationDeliveryMethod.WEBSOCKET, NotificationDeliveryMethod.UNIFIEDPUSH).forEach { method ->
      val store = existingSettingsStore()
      val settings = SettingsValues(store, mockk<Context>())
      settings.preferredNotificationMethod = method
      every { FcmUtil.isSupported() } returns false
      assertEquals(method, settings.preferredNotificationMethod)
      every { signalStore.account.fcmEnabled } returns false
      every { FcmUtil.isSupported() } returns true
      assertEquals(method, settings.preferredNotificationMethod)
    }
  }

  private fun existingSettingsStore(): KeyValueStore {
    // Model an installed app whose Molly preference migration already ran.
    val data = KeyValueDataSet().apply { putBoolean(SettingsValues.SCREEN_SECURITY_ENABLED, true) }
    return KeyValueStore(MockKeyValuePersistentStorage.withDataSet(data), DirectExecutor())
  }
}

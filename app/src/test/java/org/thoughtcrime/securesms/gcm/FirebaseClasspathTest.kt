package org.thoughtcrime.securesms.gcm

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FirebaseClasspathTest {
  @Test fun `Firebase class availability matches the selected build`() {
    val classNames = listOf(
      "com.google.firebase.messaging.FirebaseMessaging",
      "com.google.firebase.FirebaseException",
      "com.google.firebase.iid.internal.FirebaseInstanceIdInternal"
    )
    classNames.forEach { name ->
      val present = try {
        Class.forName(name, false, javaClass.classLoader)
        true
      } catch (_: ClassNotFoundException) {
        false
      }
      assertEquals(name, FcmUtil.isSupported(), present)
    }
  }

  @Test fun `Firebase-free build does not return an FCM token`() {
    if (!FcmUtil.isSupported()) {
      assertFalse(FcmUtil.getToken(mockk()).isPresent)
      assertFalse(FcmUtil.getTokenWithoutAvailCheck(mockk()).isPresent)
    }
  }
}

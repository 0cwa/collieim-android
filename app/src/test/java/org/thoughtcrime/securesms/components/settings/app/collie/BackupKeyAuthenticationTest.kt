package org.thoughtcrime.securesms.components.settings.app.collie

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class BackupKeyAuthenticationTest {
  @Test
  fun defaultsOffAndPersistsBothChanges() {
    val context = RuntimeEnvironment.getApplication()
    context.getSharedPreferences("collie_backup_key_authentication", 0).edit().clear().commit()
    assertFalse(BackupKeyAuthentication.isEnabled(context))
    BackupKeyAuthentication.setEnabled(context, true)
    assertTrue(BackupKeyAuthentication.isEnabled(context))
    BackupKeyAuthentication.setEnabled(context, false)
    assertFalse(BackupKeyAuthentication.isEnabled(context))
  }
}

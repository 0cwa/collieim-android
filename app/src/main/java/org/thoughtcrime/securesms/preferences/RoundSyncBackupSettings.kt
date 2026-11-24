package org.thoughtcrime.securesms.preferences

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.backup.RoundSyncBackupTrigger
import org.thoughtcrime.securesms.backup.RoundSyncBackupTrigger.TriggerResult
import org.thoughtcrime.securesms.keyvalue.SignalStore
import kotlin.math.roundToInt

internal object RoundSyncBackupSettings {
  @JvmStatic
  fun summary(context: Context): String {
    val installed = RoundSyncBackupTrigger.isInstalled(context)
    val settings = SignalStore.molly
    return when {
      settings.roundSyncEnabled && installed -> context.getString(R.string.RoundSyncBackup__task_summary, settings.roundSyncTaskId)
      settings.roundSyncEnabled -> context.getString(R.string.RoundSyncBackup__task_not_installed, settings.roundSyncTaskId)
      installed -> context.getString(R.string.RoundSyncBackup__off)
      else -> context.getString(R.string.RoundSyncBackup__not_installed)
    }
  }

  @JvmStatic
  fun show(context: Context, onChanged: Runnable) {
    val settings = SignalStore.molly
    val builder = MaterialAlertDialogBuilder(context)
    val input = EditText(builder.context).apply {
      setSingleLine()
      inputType = InputType.TYPE_CLASS_NUMBER
      setHint(R.string.RoundSyncBackup__task_id)
      setText(settings.roundSyncTaskId.takeIf { it > 0 }?.toString().orEmpty())
    }
    val content = FrameLayout(builder.context).apply {
      val padding = (24 * resources.displayMetrics.density).roundToInt()
      setPaddingRelative(padding, 0, padding, 0)
      addView(input, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }
    val dialog = builder
      .setTitle(R.string.RoundSyncBackup__title)
      .setMessage(R.string.RoundSyncBackup__instructions)
      .setView(content)
      .setPositiveButton(R.string.RoundSyncBackup__save, null)
      .setNeutralButton(R.string.RoundSyncBackup__test, null)
      .apply {
        if (settings.roundSyncEnabled) {
          setNegativeButton(R.string.RoundSyncBackup__disable) { _, _ ->
            settings.roundSyncEnabled = false
            onChanged.run()
          }
        } else {
          setNegativeButton(android.R.string.cancel, null)
        }
      }
      .show()

    fun readTaskId(): Int? {
      val taskId = input.text.toString().toIntOrNull()?.takeIf { it > 0 }
      input.error = if (taskId == null) context.getString(R.string.RoundSyncBackup__invalid_id) else null
      return taskId
    }

    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
      val taskId = readTaskId() ?: return@setOnClickListener
      settings.roundSyncTaskId = taskId
      settings.roundSyncEnabled = true
      onChanged.run()
      dialog.dismiss()
    }

    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
      val taskId = readTaskId() ?: return@setOnClickListener
      val message = when (RoundSyncBackupTrigger.triggerTask(context, taskId)) {
        TriggerResult.TRIGGERED -> R.string.RoundSyncBackup__requested
        TriggerResult.NOT_INSTALLED -> R.string.RoundSyncBackup__not_installed
        else -> R.string.RoundSyncBackup__failed
      }
      Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
  }
}

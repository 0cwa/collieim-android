package org.thoughtcrime.securesms.components.settings.app.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
<<<<<<< MOLLY
import org.signal.core.ui.compose.ComposeFragment
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.Texts
import org.signal.core.util.bytes
import org.signal.mediasend.SentMediaQuality
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.compose.rememberStatusBarColorNestedScrollModifier
import org.thoughtcrime.securesms.util.AttachmentUtil
import org.thoughtcrime.securesms.util.SecurePreferenceManager
=======
import org.signal.appsettings.dataandstorage.DataAndStorageSettingsAction
import org.signal.appsettings.dataandstorage.DataAndStorageSettingsEvent
import org.signal.appsettings.dataandstorage.DataAndStorageSettingsScreen
import org.signal.core.ui.compose.CollectActions
import org.signal.core.ui.compose.ComposeFragment
import org.thoughtcrime.securesms.R
>>>>>>> SIGNAL
import org.thoughtcrime.securesms.util.navigation.safeNavigate

/**
 * Lets the user control automatic media downloads, sent media quality, call data usage, and network connectivity.
 */
class DataAndStorageSettingsFragment : ComposeFragment() {

<<<<<<< MOLLY
  private val viewModel: DataAndStorageSettingsViewModel by viewModels(
    factoryProducer = {
      val preferences =  SecurePreferenceManager.getSecurePreferences(requireContext())
      val repository = DataAndStorageSettingsRepository()
      DataAndStorageSettingsViewModel.Factory(preferences, repository)
    }
  )
=======
  private val viewModel: DataAndStorageSettingsViewModel by viewModels()
>>>>>>> SIGNAL

  override fun onResume() {
    super.onResume()
    viewModel.onEvent(DataAndStorageSettingsEvent.ScreenResumed)
  }

  @Composable
  override fun FragmentContent() {
    val state by viewModel.state.collectAsStateWithLifecycle()

    CollectActions(viewModel.actions) { handleAction(it) }

    DataAndStorageSettingsScreen(
      state = state,
      onEvent = viewModel::onEvent
    )
  }

<<<<<<< MOLLY
  private inner class Callbacks : DataAndStorageSettingsCallbacks {
    override fun onNavigationClick() {
      requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    override fun onManageStorageClick() {
      findNavController().safeNavigate(R.id.action_dataAndStorageSettingsFragment_to_storagePreferenceFragment)
    }

    override fun onSentMediaQualitySelected(code: String) {
      viewModel.setSentMediaQuality(SentMediaQuality.fromCode(code.toInt()))
    }

    override fun onCallDataModeSelected(code: String) {
      viewModel.setCallDataMode(CallDataMode.fromCode(abs(code.toInt() - 2)))
    }

    override fun onMobileDataAutoDownloadSelectionChanged(selection: Array<String>) {
      viewModel.setMobileAutoDownloadValues(selection.toSet())
    }

    override fun onWifiDataAutoDownloadSelectionChanged(selection: Array<String>) {
      viewModel.setWifiAutoDownloadValues(selection.toSet())
    }

    override fun onRoamingDataAutoDownloadSelectionChanged(selection: Array<String>) {
      viewModel.setRoamingAutoDownloadValues(selection.toSet())
    }
  }
}

private interface DataAndStorageSettingsCallbacks {
  fun onNavigationClick() = Unit
  fun onManageStorageClick() = Unit
  fun onSentMediaQualitySelected(code: String) = Unit
  fun onCallDataModeSelected(code: String) = Unit
  fun onMobileDataAutoDownloadSelectionChanged(selection: Array<String>) = Unit
  fun onWifiDataAutoDownloadSelectionChanged(selection: Array<String>) = Unit
  fun onRoamingDataAutoDownloadSelectionChanged(selection: Array<String>) = Unit
  fun onForceWebsocketModeChanged(enabled: Boolean) = Unit
  fun onConfirmStayConnectedInBackground() = Unit
  fun onDismissStayConnectedInBackgroundDialog() = Unit

  object Empty : DataAndStorageSettingsCallbacks
}

@Composable
private fun DataAndStorageSettingsScreen(
  state: DataAndStorageSettingsState,
  callbacks: DataAndStorageSettingsCallbacks
) {
  Scaffolds.Settings(
    title = stringResource(R.string.preferences__data_and_storage),
    onNavigationClick = callbacks::onNavigationClick,
    navigationIcon = SignalIcons.ArrowStart.imageVector
  ) { paddingValues ->
    LazyColumn(
      modifier = Modifier
        .padding(paddingValues)
        .then(rememberStatusBarColorNestedScrollModifier())
    ) {
      item {
        Rows.TextRow(
          text = stringResource(R.string.preferences_data_and_storage__manage_storage),
          label = state.totalStorageUse.bytes.toUnitString(),
          onClick = callbacks::onManageStorageClick
        )
      }

      item {
        Dividers.Default()
      }

      item {
        Texts.SectionHeader(stringResource(R.string.preferences_chats__media_auto_download))
      }

      item {
        Rows.MultiSelectRow(
          text = stringResource(R.string.preferences_chats__when_using_mobile_data),
          labels = stringArrayResource(R.array.pref_media_download_entries),
          values = stringArrayResource(R.array.pref_media_download_values),
          selection = state.mobileAutoDownloadValues.toTypedArray(),
          noSelectionLabel = stringResource(R.string.preferences__none),
          onSelectionChanged = callbacks::onMobileDataAutoDownloadSelectionChanged
        )
      }

      item {
        Rows.MultiSelectRow(
          text = stringResource(R.string.preferences_chats__when_using_wifi),
          labels = stringArrayResource(R.array.pref_media_download_entries),
          values = stringArrayResource(R.array.pref_media_download_values),
          selection = state.wifiAutoDownloadValues.toTypedArray(),
          noSelectionLabel = stringResource(R.string.preferences__none),
          onSelectionChanged = callbacks::onWifiDataAutoDownloadSelectionChanged
        )
      }

      item {
        Rows.MultiSelectRow(
          text = stringResource(R.string.preferences_chats__when_roaming),
          labels = stringArrayResource(R.array.pref_media_download_entries),
          values = stringArrayResource(R.array.pref_media_download_values),
          selection = state.roamingAutoDownloadValues.toTypedArray(),
          noSelectionLabel = stringResource(R.string.preferences__none),
          onSelectionChanged = callbacks::onRoamingDataAutoDownloadSelectionChanged
        )
      }

      item {
        Rows.TextRow(
          text = {
            Text(
              text = stringResource(
                R.string.DataAndStorageSettingsFragment__voice_messages_and_stickers_under_size_are_always_auto_downloaded,
                AttachmentUtil.SMALL_ATTACHMENT_SIZE.toUnitString()
              ),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        )
      }

      item {
        Dividers.Default()
      }

      item {
        Texts.SectionHeader(stringResource(R.string.DataAndStorageSettingsFragment__media_quality))
      }

      item {
        val context = LocalContext.current
        val labels = remember { SentMediaQuality.getLabels(context) }

        Rows.RadioListRow(
          text = stringResource(R.string.DataAndStorageSettingsFragment__sent_media_quality),
          labels = labels,
          values = SentMediaQuality.entries.map { it.code.toString() }.toTypedArray(),
          selectedValue = state.sentMediaQuality.code.toString(),
          onSelected = callbacks::onSentMediaQualitySelected
        )
      }

      item {
        Rows.TextRow(
          text = {
            Text(
              text = stringResource(R.string.DataAndStorageSettingsFragment__sending_high_quality_media_will_use_more_data),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        )
      }

      item {
        Dividers.Default()
      }

      item {
        Texts.SectionHeader(stringResource(R.string.DataAndStorageSettingsFragment__calls))
      }

      item {
        Rows.RadioListRow(
          text = stringResource(R.string.preferences_data_and_storage__use_less_data_for_calls),
          labels = stringArrayResource(R.array.pref_data_and_storage_call_data_mode_values),
          values = CallDataMode.entries.map { it.code.toString() }.toTypedArray(),
          selectedValue = abs(state.callDataMode.code - 2).toString(),
          onSelected = callbacks::onCallDataModeSelected
        )
      }

      item {
        Rows.TextRow(
          text = {
            Text(
              text = stringResource(R.string.preference_data_and_storage__using_less_data_may_improve_calls_on_bad_networks),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        )
      }
    }
  }
}

@DayNightPreviews
@Composable
private fun DataAndStorageSettingsScreenPreview() {
  Previews.Preview {
    DataAndStorageSettingsScreen(
      state = DataAndStorageSettingsState(
        totalStorageUse = 100_000,
        mobileAutoDownloadValues = setOf(),
        wifiAutoDownloadValues = setOf(),
        roamingAutoDownloadValues = setOf(),
        callDataMode = CallDataMode.HIGH_ALWAYS,
        sentMediaQuality = SentMediaQuality.STANDARD,
      ),
      callbacks = DataAndStorageSettingsCallbacks.Empty
    )
  }
}
=======
  private fun handleAction(action: DataAndStorageSettingsAction) {
    when (action) {
      DataAndStorageSettingsAction.NavigateBack -> requireActivity().onBackPressedDispatcher.onBackPressed()
      DataAndStorageSettingsAction.NavigateToManageStorage -> findNavController().safeNavigate(R.id.action_dataAndStorageSettingsFragment_to_storagePreferenceFragment)
      DataAndStorageSettingsAction.NavigateToProxy -> findNavController().safeNavigate(R.id.action_dataAndStorageSettingsFragment_to_editProxyFragment)
    }
  }
}
>>>>>>> SIGNAL

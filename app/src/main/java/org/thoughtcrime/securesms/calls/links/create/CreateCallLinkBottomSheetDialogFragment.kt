/**
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.calls.links.create

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.reactivex.rxjava3.kotlin.subscribeBy
import org.signal.core.models.recipients.RecipientId
import org.signal.core.ui.compose.BottomSheets
import org.signal.core.ui.compose.Buttons
import org.signal.core.ui.compose.ComposeBottomSheetDialogFragment
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.util.Util
import org.signal.core.util.concurrent.LifecycleDisposable
import org.signal.core.util.logging.Log
import org.signal.ringrtc.CallLinkState
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.calls.YouAreAlreadyInACallSnackbar.YouAreAlreadyInACallSnackbar
import org.thoughtcrime.securesms.calls.links.CallLinks
import org.thoughtcrime.securesms.calls.links.EditCallLinkNameDialogFragment
import org.thoughtcrime.securesms.calls.links.SignalCallRow
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.database.CallLinkTable
import org.thoughtcrime.securesms.service.webrtc.links.CallLinkRoomId
import org.thoughtcrime.securesms.service.webrtc.links.CreateCallLinkResult
import org.thoughtcrime.securesms.service.webrtc.links.SignalCallLinkState
import org.thoughtcrime.securesms.service.webrtc.links.UpdateCallLinkResult
import org.thoughtcrime.securesms.sharing.v2.ShareActivity
import org.thoughtcrime.securesms.util.CommunicationActions
import java.time.Instant
import org.signal.core.ui.R as CoreUiR

/**
 * Bottom sheet for creating call links
 */
class CreateCallLinkBottomSheetDialogFragment : ComposeBottomSheetDialogFragment() {

  companion object {
    private val TAG = Log.tag(CreateCallLinkBottomSheetDialogFragment::class.java)
  }

  private val viewModel: CreateCallLinkViewModel by viewModels()
  private val lifecycleDisposable = LifecycleDisposable()

  override val peekHeightPercentage: Float = 1f

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    lifecycleDisposable.bindTo(viewLifecycleOwner)
    parentFragmentManager.setFragmentResultListener(EditCallLinkNameDialogFragment.RESULT_KEY, viewLifecycleOwner) { resultKey, bundle ->
      if (bundle.containsKey(resultKey)) {
        setCallName(bundle.getString(resultKey)!!)
      }
    }
  }

  @Composable
  override fun SheetContent() {
    val generation by CallPermissions.changes.collectAsStateWithLifecycle()
    if (!remember(generation) { CallPermissions.isCallingAllowed() }) {
      LaunchedEffect(generation) {
        Toast.makeText(requireContext(), R.string.CallPermissions__calling_restricted, Toast.LENGTH_LONG).show()
        dismissAllowingStateLoss()
      }
      return
    }

    val callLink: CallLinkTable.CallLink by viewModel.callLink
    val displayAlreadyInACallSnackbar: Boolean by viewModel.showAlreadyInACall.collectAsStateWithLifecycle(false)
    val isLoadingAdminApprovalChange: Boolean by viewModel.isLoadingAdminApprovalChange.collectAsStateWithLifecycle(false)

    CreateCallLinkBottomSheetContent(
      callLink = callLink,
      onJoinClicked = this@CreateCallLinkBottomSheetDialogFragment::onJoinClicked,
      onAddACallNameClicked = this@CreateCallLinkBottomSheetDialogFragment::onAddACallNameClicked,
      onApproveAllMembersChanged = this@CreateCallLinkBottomSheetDialogFragment::setApproveAllMembers,
      onShareViaSignalClicked = this@CreateCallLinkBottomSheetDialogFragment::onShareViaSignalClicked,
      onCopyLinkClicked = this@CreateCallLinkBottomSheetDialogFragment::onCopyLinkClicked,
      onShareLinkClicked = this@CreateCallLinkBottomSheetDialogFragment::onShareLinkClicked,
      onDoneClicked = this@CreateCallLinkBottomSheetDialogFragment::onDoneClicked,
      displayAlreadyInACallSnackbar = displayAlreadyInACallSnackbar,
      isLoadingAdminApprovalChange = isLoadingAdminApprovalChange
    )
  }

  private fun setCallName(callName: String) {
    if (!isCallingAvailable()) return
    lifecycleDisposable += viewModel.setCallName(callName).subscribeBy(onSuccess = {
      if (it !is UpdateCallLinkResult.Update) {
        Log.w(TAG, "Failed to update call link name")
        toastFailure()
      }
    }, onError = this::handleError)
  }

  private fun setApproveAllMembers(approveAllMembers: Boolean) {
    if (!isCallingAvailable()) return
    lifecycleDisposable += viewModel.setApproveAllMembers(approveAllMembers).subscribeBy(onSuccess = {
      if (it !is UpdateCallLinkResult.Update) {
        Log.w(TAG, "Failed to update call link restrictions")
        toastFailure()
      }
    }, onError = this::handleError)
  }

  private fun onAddACallNameClicked() {
    if (!isCallingAvailable()) return
    val snapshot = viewModel.callLink.value
    EditCallLinkNameDialogFragment().apply {
      arguments = bundleOf(EditCallLinkNameDialogFragment.ARG_NAME to snapshot.state.name)
    }.show(parentFragmentManager, null)
  }

  private fun onJoinClicked() {
    withCommittedCallLink {
      when (it) {
        is EnsureCallLinkCreatedResult.Success -> {
          CommunicationActions.startVideoCall(requireActivity(), it.recipient) {
            viewModel.setShowAlreadyInACall(true)
          }
          dismissAllowingStateLoss()
        }

        is EnsureCallLinkCreatedResult.Failure -> handleCreateCallLinkFailure(it.failure)
      }
    }
  }

  private fun onDoneClicked() {
    withCommittedCallLink {
      when (it) {
        is EnsureCallLinkCreatedResult.Success -> dismissAllowingStateLoss()
        is EnsureCallLinkCreatedResult.Failure -> handleCreateCallLinkFailure(it.failure)
      }
    }
  }

  private fun onShareViaSignalClicked() {
    withCommittedCallLink {
      when (it) {
        is EnsureCallLinkCreatedResult.Success -> {
          startActivity(
            ShareActivity.sendSimpleText(
              requireContext(),
              getString(R.string.CreateCallLink__use_this_link_to_join_a_signal_call, CallLinks.url(viewModel.linkKeyBytes))
            )
          )
        }

        is EnsureCallLinkCreatedResult.Failure -> handleCreateCallLinkFailure(it.failure)
      }
    }
  }

  private fun onCopyLinkClicked() {
    withCommittedCallLink {
      when (it) {
        is EnsureCallLinkCreatedResult.Success -> {
          Util.copyToClipboard(requireContext(), CallLinks.url(viewModel.linkKeyBytes))
          Toast.makeText(requireContext(), R.string.CreateCallLinkBottomSheetDialogFragment__copied_to_clipboard, Toast.LENGTH_LONG).show()
        }

        is EnsureCallLinkCreatedResult.Failure -> handleCreateCallLinkFailure(it.failure)
      }
    }
  }

  private fun onShareLinkClicked() {
    withCommittedCallLink {
      when (it) {
        is EnsureCallLinkCreatedResult.Success -> {
          val mimeType = Intent.normalizeMimeType("text/plain")
          val shareIntent = ShareCompat.IntentBuilder(requireContext())
            .setText(CallLinks.url(viewModel.linkKeyBytes))
            .setType(mimeType)
            .createChooserIntent()

          try {
            startActivity(shareIntent)
          } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.CreateCallLinkBottomSheetDialogFragment__failed_to_open_share_sheet, Toast.LENGTH_LONG).show()
          }
        }

        is EnsureCallLinkCreatedResult.Failure -> {
          Log.w(TAG, "Failed to create link: $it")
          toastFailure()
        }
      }
    }
  }

  private fun withCommittedCallLink(onSuccess: (EnsureCallLinkCreatedResult) -> Unit) {
    if (!isCallingAvailable()) return
    val generation = CallPermissions.generation()
    lifecycleDisposable += viewModel.commitCallLink().subscribeBy(
      onSuccess = { if (CallPermissions.isCurrent(generation)) onSuccess(it) },
      onError = { if (CallPermissions.isCurrent(generation)) handleError(it) }
    )
  }

  private fun isCallingAvailable(): Boolean {
    if (CallPermissions.isCallingAllowed()) return true
    Toast.makeText(requireContext(), R.string.CallPermissions__calling_restricted, Toast.LENGTH_LONG).show()
    return false
  }

  private fun handleCreateCallLinkFailure(failure: CreateCallLinkResult.Failure) {
    Log.w(TAG, "Failed to create call link: $failure")
    toastFailure()
  }

  private fun handleError(throwable: Throwable) {
    Log.w(TAG, "Failed to create call link.", throwable)
    toastFailure()
  }

  private fun toastFailure() {
    Toast.makeText(requireContext(), R.string.CallLinkDetailsFragment__couldnt_save_changes, Toast.LENGTH_LONG).show()
  }
}

@Composable
private fun CreateCallLinkBottomSheetContent(
  callLink: CallLinkTable.CallLink,
  displayAlreadyInACallSnackbar: Boolean,
  isLoadingAdminApprovalChange: Boolean,
  onJoinClicked: () -> Unit = {},
  onAddACallNameClicked: () -> Unit = {},
  onApproveAllMembersChanged: (Boolean) -> Unit = {},
  onShareViaSignalClicked: () -> Unit = {},
  onCopyLinkClicked: () -> Unit = {},
  onShareLinkClicked: () -> Unit = {},
  onDoneClicked: () -> Unit = {}
) {
  Box {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .wrapContentSize(Alignment.Center)
        .verticalScroll(rememberScrollState())
    ) {
      BottomSheets.Handle(modifier = Modifier.align(Alignment.CenterHorizontally))

      Spacer(modifier = Modifier.height(20.dp))

      Text(
        text = stringResource(id = R.string.CreateCallLinkBottomSheetDialogFragment__create_call_link),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
      )

      Spacer(modifier = Modifier.height(24.dp))

      SignalCallRow(
        callLink = callLink,
        callLinkPeekInfo = null,
        onJoinClicked = onJoinClicked
      )

      Spacer(modifier = Modifier.height(12.dp))

      Rows.TextRow(
        text = stringResource(
          id = if (callLink.state.name.isEmpty()) {
            R.string.CreateCallLinkBottomSheetDialogFragment__add_call_name
          } else {
            R.string.CreateCallLinkBottomSheetDialogFragment__edit_call_name
          }
        ),
        onClick = onAddACallNameClicked
      )

      Rows.ToggleRow(
        checked = callLink.state.restrictions == CallLinkState.Restrictions.ADMIN_APPROVAL,
        text = stringResource(id = R.string.CreateCallLinkBottomSheetDialogFragment__require_admin_approval),
        onCheckChanged = onApproveAllMembersChanged,
        isLoading = isLoadingAdminApprovalChange
      )

      Dividers.Default()

      Rows.TextRow(
        text = stringResource(id = R.string.CreateCallLinkBottomSheetDialogFragment__share_link_via_signal),
        icon = SignalIcons.Forward.imageVector,
        onClick = onShareViaSignalClicked
      )

      Rows.TextRow(
        text = stringResource(id = R.string.CreateCallLinkBottomSheetDialogFragment__copy_link),
        icon = SignalIcons.Copy.imageVector,
        onClick = onCopyLinkClicked
      )

      Rows.TextRow(
        text = stringResource(id = R.string.CreateCallLinkBottomSheetDialogFragment__share_link),
        icon = SignalIcons.Share.imageVector,
        onClick = onShareLinkClicked
      )

      Buttons.MediumTonal(
        onClick = onDoneClicked,
        modifier = Modifier
          .padding(end = dimensionResource(id = CoreUiR.dimen.gutter))
          .align(Alignment.End)
      ) {
        Text(text = stringResource(id = R.string.CreateCallLinkBottomSheetDialogFragment__done))
      }

      Spacer(modifier = Modifier.size(16.dp))
    }

    YouAreAlreadyInACallSnackbar(
      displaySnackbar = displayAlreadyInACallSnackbar,
      modifier = Modifier.align(Alignment.BottomCenter)
    )
  }
}

@DayNightPreviews
@Composable
private fun CreateCallLinkBottomSheetContentPreview() {
  Previews.BottomSheetContentPreview {
    CreateCallLinkBottomSheetContent(
      callLink = CallLinkTable.CallLink(
        recipientId = RecipientId.UNKNOWN,
        roomId = CallLinkRoomId.fromBytes(byteArrayOf(1, 2, 3, 4)),
        credentials = null,
        state = SignalCallLinkState(
          name = "Test Call",
          restrictions = CallLinkState.Restrictions.ADMIN_APPROVAL,
          revoked = false,
          expiration = Instant.MAX
        ),
        deletionTimestamp = 0L
      ),
      displayAlreadyInACallSnackbar = true,
      isLoadingAdminApprovalChange = false
    )
  }
}

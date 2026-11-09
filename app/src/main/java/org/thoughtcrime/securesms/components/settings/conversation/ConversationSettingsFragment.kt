/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.conversation

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.IntRect
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.google.android.material.snackbar.Snackbar
import org.signal.core.models.recipients.RecipientId
import org.signal.core.ui.compose.CollectActions
import org.signal.core.ui.compose.ComposeFragment
import org.signal.core.ui.permissions.Permissions
<<<<<<< MOLLY
import org.signal.core.ui.util.ThemeUtil
import org.signal.core.util.DimensionUnit
import org.signal.core.util.Result
import org.signal.core.util.concurrent.LifecycleDisposable
import org.signal.core.util.concurrent.addTo
=======
>>>>>>> SIGNAL
import org.signal.core.util.getParcelableArrayListExtraCompat
import org.signal.core.util.logging.Log
import org.signal.core.util.requireParcelableCompat
import org.signal.donations.InAppPaymentType
import org.signal.uicomponents.recentmediarail.RecentMediaRailEvents
import org.thoughtcrime.securesms.AvatarPreviewActivity
import org.thoughtcrime.securesms.BlockUnblockDialog
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.PushContactSelectionActivity
import org.thoughtcrime.securesms.R
<<<<<<< MOLLY
import org.thoughtcrime.securesms.badges.BadgeImageView
import org.thoughtcrime.securesms.badges.Badges
import org.thoughtcrime.securesms.badges.Badges.displayBadges
import org.thoughtcrime.securesms.badges.models.Badge
=======
import org.thoughtcrime.securesms.badges.view.ViewBadgeBottomSheetDialogFragment
>>>>>>> SIGNAL
import org.thoughtcrime.securesms.calls.YouAreAlreadyInACallSnackbar
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.components.settings.app.subscription.donate.CheckoutFlowActivity
import org.thoughtcrime.securesms.components.settings.conversation.group.GroupSettingsEvent
import org.thoughtcrime.securesms.components.settings.conversation.group.GroupSettingsScreen
import org.thoughtcrime.securesms.components.settings.conversation.group.GroupSettingsViewModel
import org.thoughtcrime.securesms.components.settings.conversation.individual.IndividualSettingsEvent
import org.thoughtcrime.securesms.components.settings.conversation.individual.IndividualSettingsScreen
import org.thoughtcrime.securesms.components.settings.conversation.individual.IndividualSettingsViewModel
import org.thoughtcrime.securesms.components.settings.conversation.individual.NoteToSelfSettingsScreen
import org.thoughtcrime.securesms.components.settings.conversation.individual.ReleaseNotesSettingsScreen
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.groups.memberlabel.MemberLabelEducationSheet
import org.thoughtcrime.securesms.groups.ui.EndGroupDialog
import org.thoughtcrime.securesms.groups.ui.GroupErrors
import org.thoughtcrime.securesms.groups.ui.GroupLimitDialog
import org.thoughtcrime.securesms.groups.ui.LeaveGroupDialog
import org.thoughtcrime.securesms.groups.ui.MemberSearchFragment
import org.thoughtcrime.securesms.groups.ui.addmembers.AddMembersActivity
import org.thoughtcrime.securesms.groups.ui.addtogroup.AddToGroupsActivity
import org.thoughtcrime.securesms.groups.ui.invitesandrequests.ManagePendingAndRequestingMembersActivity
import org.thoughtcrime.securesms.groups.ui.managegroup.dialogs.GroupDescriptionDialog
import org.thoughtcrime.securesms.groups.ui.managegroup.dialogs.GroupInviteSentDialog
import org.thoughtcrime.securesms.groups.ui.managegroup.dialogs.GroupsLearnMoreBottomSheetDialogFragment
import org.thoughtcrime.securesms.jobs.AttachmentDownloadJob
import org.thoughtcrime.securesms.main.MainNavigationEventSink
import org.thoughtcrime.securesms.main.MainNavigationEvents
import org.thoughtcrime.securesms.mediaoverview.MediaOverviewActivity
import org.thoughtcrime.securesms.mediapreview.MediaIntentFactory
import org.thoughtcrime.securesms.mediapreview.MediaPreviewCache
import org.thoughtcrime.securesms.nicknames.NicknameActivity
import org.thoughtcrime.securesms.profiles.edit.CreateProfileActivity
import org.thoughtcrime.securesms.recipients.RecipientExporter
<<<<<<< MOLLY
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.recipients.RecipientUtil
=======
>>>>>>> SIGNAL
import org.thoughtcrime.securesms.recipients.ui.about.AboutSheet
import org.thoughtcrime.securesms.recipients.ui.bottomsheet.RecipientBottomSheetDialogFragment
import org.thoughtcrime.securesms.starred.StarredMessagesActivity
import org.thoughtcrime.securesms.stories.StoryViewerArgs
import org.thoughtcrime.securesms.stories.dialogs.StoryDialogs
import org.thoughtcrime.securesms.stories.viewer.AddToGroupStoryDelegate
import org.thoughtcrime.securesms.stories.viewer.StoryViewerActivity
import org.thoughtcrime.securesms.util.CommunicationActions
import org.thoughtcrime.securesms.util.navigation.safeNavigate
import org.thoughtcrime.securesms.verify.VerifyIdentityActivity
import org.thoughtcrime.securesms.wallpaper.ChatWallpaperActivity

private val TAG = Log.tag(ConversationSettingsFragment::class)

private const val REQUEST_CODE_VIEW_CONTACT = 1
private const val REQUEST_CODE_ADD_CONTACT = 2
private const val REQUEST_CODE_ADD_MEMBERS_TO_GROUP = 3
private const val REQUEST_CODE_RETURN_FROM_MEDIA = 4

/**
 * Entry point for conversation settings.
 *
 * Hands off to the screen matching the conversation type, and carries out the [ConversationSettingsAction]s that need an
 * Activity, FragmentManager, or the legacy nav graph.
 *
 * Hosts that want shared element enter transitions should implement [TransitionCallback].
 */
class ConversationSettingsFragment : ComposeFragment() {

  private val args: ConversationSettingsFragmentArgs by navArgs()

  private val callMessageIds: LongArray get() = args.callMessageIds ?: longArrayOf()

  private val repository: ConversationSettingsRepository by lazy { ConversationSettingsRepository(requireContext()) }

  // These are lazy, only one gets built
  private val individualViewModel: IndividualSettingsViewModel by viewModels(
    factoryProducer = { IndividualSettingsViewModel.Factory(requireNotNull(args.recipientId), args.kind, callMessageIds, repository) }
  )

  private val groupViewModel: GroupSettingsViewModel by viewModels(
    factoryProducer = { GroupSettingsViewModel.Factory(requireNotNull(args.groupId), callMessageIds, repository) }
  )

  private var transitionCallback: TransitionCallback? = null
  private var mainNavigationEventSink: MainNavigationEventSink? = null

  /** The avatar view owns the shared element transition out of this screen. */
  private var avatarView: View? = null

  private lateinit var addToGroupStoryDelegate: AddToGroupStoryDelegate
  private lateinit var nicknameLauncher: ActivityResultLauncher<NicknameActivity.Args>

  private val navController get() = Navigation.findNavController(requireView())

  override fun onAttach(context: Context) {
    super.onAttach(context)
    transitionCallback = context as? TransitionCallback
    mainNavigationEventSink = context as? MainNavigationEventSink
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    nicknameLauncher = registerForActivityResult(NicknameActivity.Contract()) {
      // No result to handle -- the nickname is saved to the database, and the recipient observer picks it up
    }
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    addToGroupStoryDelegate = AddToGroupStoryDelegate(this)

    parentFragmentManager.setFragmentResultListener(MemberLabelEducationSheet.RESULT_EDIT_MEMBER_LABEL, viewLifecycleOwner) { _, bundle ->
      val groupId = bundle.requireParcelableCompat(MemberLabelEducationSheet.KEY_GROUP_ID, GroupId.V2::class.java)
      navController.safeNavigate(ConversationSettingsFragmentDirections.actionConversationSettingsFragmentToMemberLabelFragment(groupId))
    }

    parentFragmentManager.setFragmentResultListener(AboutSheet.RESULT_EDIT_MEMBER_LABEL, viewLifecycleOwner) { _, bundle ->
      val groupId = bundle.requireParcelableCompat(AboutSheet.RESULT_GROUP_ID, GroupId.V2::class.java)
      navController.safeNavigate(ConversationSettingsFragmentDirections.actionConversationSettingsFragmentToMemberLabelFragment(groupId))
    }

    parentFragmentManager.setFragmentResultListener(MemberSearchFragment.RESULT_ADD_MEMBERS, viewLifecycleOwner) { _, _ ->
      groupViewModel.onEvent(GroupSettingsEvent.AddMembersClicked)
    }
  }

  override fun onDestroyView() {
    super.onDestroyView()
    avatarView = null
  }

  /**
   * Runs whichever of these applies to the conversation we're showing. Used to forward the things that happen to the
   * fragment -- activity results, dialog confirmations -- into the live view model's own events.
   */
  private fun dispatch(
    individual: (IndividualSettingsViewModel) -> Unit = {},
    group: (GroupSettingsViewModel) -> Unit = {}
  ) {
    if (args.kind == ConversationSettingsKind.GROUP) {
      group(groupViewModel)
    } else {
      individual(individualViewModel)
    }
  }

  @Composable
  override fun FragmentContent() {
    when (args.kind) {
      ConversationSettingsKind.INDIVIDUAL -> IndividualContent()
      ConversationSettingsKind.NOTE_TO_SELF -> NoteToSelfContent()
      ConversationSettingsKind.RELEASE_NOTES -> ReleaseNotesContent()
      ConversationSettingsKind.GROUP -> GroupContent()
    }
  }

  @Composable
  private fun IndividualContent() {
    val state by individualViewModel.state.collectAsStateWithLifecycle()

    CollectActions(individualViewModel.actions) { action -> handleAction(action) }
    NotifyWhenLoaded(state.isLoaded)

    IndividualSettingsScreen(
      state = state,
      onEvent = individualViewModel::onEvent,
      onNavigationClick = { requireActivity().onBackPressedDispatcher.onBackPressed() },
      onAvatarViewCreated = { avatarView = it }
    )
  }

  @Composable
  private fun NoteToSelfContent() {
    val state by individualViewModel.state.collectAsStateWithLifecycle()

    CollectActions(individualViewModel.actions) { action -> handleAction(action) }
    NotifyWhenLoaded(state.isLoaded)

    NoteToSelfSettingsScreen(
      state = state,
      onEvent = individualViewModel::onEvent,
      onNavigationClick = { requireActivity().onBackPressedDispatcher.onBackPressed() },
      onAvatarViewCreated = { avatarView = it }
    )
  }

  @Composable
  private fun ReleaseNotesContent() {
    val state by individualViewModel.state.collectAsStateWithLifecycle()

    CollectActions(individualViewModel.actions) { action -> handleAction(action) }
    NotifyWhenLoaded(state.isLoaded)

    ReleaseNotesSettingsScreen(
      state = state,
      onEvent = individualViewModel::onEvent,
      onNavigationClick = { requireActivity().onBackPressedDispatcher.onBackPressed() },
      onAvatarViewCreated = { avatarView = it }
    )
  }

  @Composable
  private fun GroupContent() {
    val state by groupViewModel.state.collectAsStateWithLifecycle()

    CollectActions(groupViewModel.actions) { action -> handleAction(action) }
    NotifyWhenLoaded(state.isLoaded)

    GroupSettingsScreen(
      state = state,
      onEvent = groupViewModel::onEvent,
      onNavigationClick = { requireActivity().onBackPressedDispatcher.onBackPressed() },
      onAvatarViewCreated = { avatarView = it }
    )
  }

  /**
   * Animates the media viewer up out of the rail item the user tapped. [bounds] arrive in window coordinates, since the
   * rail is Compose and has no view of its own to hand over, so they have to be moved into our root view's space first.
   */
  private fun scaleUpFromRail(bounds: IntRect): ActivityOptions? {
    if (bounds.isEmpty) {
      return null
    }

    val root = view ?: return null
    val rootLocation = IntArray(2).also { root.getLocationInWindow(it) }

    return ActivityOptions.makeScaleUpAnimation(root, bounds.left - rootLocation[0], bounds.top - rootLocation[1], bounds.width, bounds.height)
  }

  @Composable
  private fun NotifyWhenLoaded(isLoaded: Boolean) {
    LaunchedEffect(isLoaded) {
      if (isLoaded) {
        (view?.parent as? ViewGroup)?.doOnPreDraw {
          transitionCallback?.onReadyForEnterTransition()
        }
      }
    }
  }

  @Suppress("DEPRECATION")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    when (requestCode) {
      REQUEST_CODE_ADD_MEMBERS_TO_GROUP -> {
        if (data != null) {
          val selected: List<RecipientId> = requireNotNull(data.getParcelableArrayListExtraCompat(PushContactSelectionActivity.KEY_SELECTED_RECIPIENTS, RecipientId::class.java))
          groupViewModel.onEvent(GroupSettingsEvent.AddMembersSelected(selected))
        }
      }
      REQUEST_CODE_RETURN_FROM_MEDIA -> {
        dispatch(
          individual = { it.onEvent(IndividualSettingsEvent.MediaRailEvent(RecentMediaRailEvents.RefreshRequested)) },
          group = { it.onEvent(GroupSettingsEvent.MediaRailEvent(RecentMediaRailEvents.RefreshRequested)) }
        )
      }
      REQUEST_CODE_ADD_CONTACT, REQUEST_CODE_VIEW_CONTACT -> {
        individualViewModel.onEvent(IndividualSettingsEvent.RecipientRefreshRequested)
      }
    }
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
  }

  @Suppress("DEPRECATION")
  private fun handleAction(action: ConversationSettingsAction) {
    Log.d(TAG, "[Action] $action")

    when (action) {
      is ConversationSettingsAction.ShowAvatarPreview -> {
        val intent = AvatarPreviewActivity.intentFromRecipientId(requireContext(), action.recipientId)
        val transitionBundle = avatarView?.let { AvatarPreviewActivity.createTransitionBundle(requireActivity(), it) }
        startActivity(intent, transitionBundle)
      }
      is ConversationSettingsAction.ShowStoryOrAvatarDialog -> {
        val viewAvatarIntent = AvatarPreviewActivity.intentFromRecipientId(requireContext(), action.recipientId)
        val transitionBundle = avatarView?.let { AvatarPreviewActivity.createTransitionBundle(requireActivity(), it) }
        val viewStoryIntent = StoryViewerActivity.createIntent(
          requireContext(),
          StoryViewerArgs(
            recipientId = action.recipientId,
            isInHiddenStoryMode = action.isInHiddenStoryMode,
            isFromQuote = true
          )
        )

        StoryDialogs.displayStoryOrProfileImage(
          context = requireContext(),
          onViewStory = { startActivity(viewStoryIntent) },
          onViewAvatar = { startActivity(viewAvatarIntent, transitionBundle) }
        )
      }
      is ConversationSettingsAction.ShowBadgeSheet -> {
        ViewBadgeBottomSheetDialogFragment.show(parentFragmentManager, action.recipientId, action.badge)
      }
      is ConversationSettingsAction.ShowAboutSheet -> {
        AboutSheet.create(action.recipient).show(parentFragmentManager, null)
      }
      is ConversationSettingsAction.EditGroupProfile -> {
        startActivity(CreateProfileActivity.getIntentForGroupProfile(requireActivity(), action.groupId))
      }
      is ConversationSettingsAction.EditGroupDescription -> {
        startActivity(CreateProfileActivity.getIntentForGroupProfileWithFocusedDescription(requireActivity(), action.groupId))
      }
      is ConversationSettingsAction.ShowGroupDescriptionDialog -> {
        GroupDescriptionDialog.show(childFragmentManager, action.groupId, null, action.shouldLinkify)
      }
      ConversationSettingsAction.ShowGroupsLearnMore -> {
        GroupsLearnMoreBottomSheetDialogFragment.show(parentFragmentManager)
      }
      ConversationSettingsAction.ShowInviteFriends -> {
        startActivity(AppSettingsActivity.invite(requireContext()))
      }
      is ConversationSettingsAction.NavigateToInternalDetails -> {
        navController.safeNavigate(ConversationSettingsFragmentDirections.actionConversationSettingsFragmentToInternalDetailsSettingsFragment(action.recipientId))
      }
      is ConversationSettingsAction.OpenConversation -> {
        val builder = ConversationIntents.createBuilderSync(requireContext(), action.recipientId, action.threadId)
        startActivity(builder.withSearchOpen(action.withSearchOpen).build())

        if (action.withSearchOpen && requireActivity() !is MainNavigationEventSink) {
          requireActivity().finish()
        }
      }
      is ConversationSettingsAction.AddToGroupStory -> {
        addToGroupStoryDelegate.addToStory(action.recipientId)
      }
      is ConversationSettingsAction.StartVideoCall -> {
        CommunicationActions.startVideoCall(requireActivity(), action.recipient) {
          YouAreAlreadyInACallSnackbar.show(requireView())
        }
      }
      is ConversationSettingsAction.StartAudioCall -> {
        CommunicationActions.startVoiceCall(requireActivity(), action.recipient) {
          YouAreAlreadyInACallSnackbar.show(requireView())
        }
      }
      ConversationSettingsAction.ShowMuteUntilTimePicker -> {
        childFragmentManager.setFragmentResultListener(MuteUntilTimePickerBottomSheet.REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
          val muteUntil = bundle.getLong(MuteUntilTimePickerBottomSheet.RESULT_TIMESTAMP)
          dispatch(
            individual = { it.onEvent(IndividualSettingsEvent.MuteDurationSelected(muteUntil)) },
            group = { it.onEvent(GroupSettingsEvent.MuteDurationSelected(muteUntil)) }
          )
        }
        MuteUntilTimePickerBottomSheet.show(childFragmentManager)
      }
      is ConversationSettingsAction.NavigateToDisappearingMessages -> {
        navController.safeNavigate(
          ConversationSettingsFragmentDirections.actionConversationSettingsFragmentToAppSettingsExpireTimer()
            .setInitialValue(action.initialValue)
            .setRecipientId(action.recipientId)
            .setForResultMode(false)
        )
      }
      is ConversationSettingsAction.EditNickname -> {
        nicknameLauncher.launch(NicknameActivity.Args(action.recipientId, false))
      }
      is ConversationSettingsAction.OpenChatWallpaper -> {
        startActivity(ChatWallpaperActivity.createIntent(requireContext(), action.recipientId))
      }
      is ConversationSettingsAction.NavigateToSoundsAndNotifications -> {
        val directions = ConversationSettingsFragmentDirections.actionConversationSettingsFragmentToSoundsAndNotificationsSettingsFragment(action.recipientId)
        navController.safeNavigate(directions)
      }
      is ConversationSettingsAction.OpenStarredMessages -> {
        startActivity(StarredMessagesActivity.createIntent(requireContext(), action.threadId))
      }
      is ConversationSettingsAction.ViewContact -> {
        startActivityForResult(Intent(Intent.ACTION_VIEW, action.contactUri), REQUEST_CODE_VIEW_CONTACT)
      }
      is ConversationSettingsAction.AddContact -> {
        try {
          startActivityForResult(RecipientExporter.export(action.recipient).asAddContactIntent(), REQUEST_CODE_ADD_CONTACT)
        } catch (e: ActivityNotFoundException) {
          Toast.makeText(requireContext(), R.string.ConversationSettingsFragment__contacts_app_not_found, Toast.LENGTH_SHORT).show()
        }
      }
      is ConversationSettingsAction.ShowSafetyNumber -> {
        VerifyIdentityActivity.startOrShowExchangeMessagesDialog(requireActivity(), action.identityRecord)
      }
      is ConversationSettingsAction.ShowMediaPreview -> {
        // The rail is Compose and has no drawable to hand over, so make sure the viewer doesn't try to transition out of
        // whatever some other screen left behind.
        MediaPreviewCache.drawable = null

        startActivityForResult(
          MediaIntentFactory.intentFromMediaRecord(requireContext(), action.mediaRecord, action.isLtr, allMediaInRail = true),
          REQUEST_CODE_RETURN_FROM_MEDIA,
          scaleUpFromRail(action.bounds)?.toBundle()
        )
      }
      is ConversationSettingsAction.DownloadMedia -> {
        action.mediaRecord.attachment?.let { AttachmentDownloadJob.downloadAttachmentIfNeeded(it) }
      }
      ConversationSettingsAction.ShowMediaNotSentYet -> {
        Toast.makeText(requireContext(), R.string.ConversationSettingsFragment__this_media_is_not_sent_yet, Toast.LENGTH_LONG).show()
      }
      is ConversationSettingsAction.ShowMediaOverview -> {
        startActivityForResult(MediaOverviewActivity.forThread(requireContext(), action.threadId), REQUEST_CODE_RETURN_FROM_MEDIA)
      }
      ConversationSettingsAction.OpenSupportCenter -> {
        CommunicationActions.openBrowserLink(requireContext(), getString(R.string.support_center_url))
      }
      ConversationSettingsAction.OpenContactUs -> {
        startActivity(AppSettingsActivity.help(requireContext()))
      }
      ConversationSettingsAction.OpenDonate -> {
        startActivity(CheckoutFlowActivity.createIntent(requireContext(), InAppPaymentType.ONE_TIME_DONATION))
      }
      is ConversationSettingsAction.AddToAGroup -> {
        startActivity(AddToGroupsActivity.createIntent(requireContext(), action.recipientId, action.groupMembership))
      }
      is ConversationSettingsAction.OpenGroupConversation -> {
        CommunicationActions.startConversation(requireActivity(), action.recipient, null)
        if (requireActivity() !is MainNavigationEventSink) {
          requireActivity().finish()
        }
      }
      is ConversationSettingsAction.NavigateToMemberSearch -> {
        navController.safeNavigate(
          ConversationSettingsFragmentDirections.actionConversationSettingsFragmentToMemberSearchFragment(action.groupId, action.canAdd, action.hasGroupLink)
        )
      }
      is ConversationSettingsAction.AddMembersToGroup -> {
        startActivityForResult(AddMembersActivity.createIntent(requireContext(), action), REQUEST_CODE_ADD_MEMBERS_TO_GROUP)
      }
      ConversationSettingsAction.ShowGroupHardLimitDialog -> {
        GroupLimitDialog.showHardLimitMessage(requireContext())
      }
      is ConversationSettingsAction.ShowRecipientBottomSheet -> {
        RecipientBottomSheetDialogFragment.show(parentFragmentManager, action.recipientId, action.groupId)
      }
      is ConversationSettingsAction.NavigateToMemberLabel -> {
        navController.safeNavigate(ConversationSettingsFragmentDirections.actionConversationSettingsFragmentToMemberLabelFragment(action.groupId))
      }
      ConversationSettingsAction.ShowMemberLabelPermissionError -> {
        Snackbar.make(requireView(), R.string.GroupMemberLabel__error_no_edit_permission, Snackbar.LENGTH_SHORT).show()
      }
      is ConversationSettingsAction.NavigateToShareableGroupLink -> {
        navController.safeNavigate(ConversationSettingsFragmentDirections.actionConversationSettingsFragmentToShareableGroupLinkFragment(action.groupId))
      }
      is ConversationSettingsAction.OpenRequestsAndInvites -> {
        startActivity(ManagePendingAndRequestingMembersActivity.newIntent(requireContext(), action.groupId))
      }
      is ConversationSettingsAction.NavigateToPermissions -> {
        navController.safeNavigate(ConversationSettingsFragmentDirections.actionConversationSettingsFragmentToGroupPermissionsFragment(action.groupId))
      }
      is ConversationSettingsAction.ShowLeaveGroupDialog -> {
        LeaveGroupDialog.handleLeavePushGroup(requireActivity(), action.groupId.requirePush(), null)
      }
      is ConversationSettingsAction.ShowEndGroupDialog -> {
        EndGroupDialog.show(requireActivity(), action.groupId, action.groupTitle)
      }
      is ConversationSettingsAction.ShowBlockDialog -> {
        BlockUnblockDialog.showBlockFor(requireContext(), action.recipient) {
          dispatch(
            individual = { it.onEvent(IndividualSettingsEvent.BlockConfirmed) },
            group = { it.onEvent(GroupSettingsEvent.BlockConfirmed) }
          )
        }
      }
      is ConversationSettingsAction.ShowUnblockDialog -> {
        BlockUnblockDialog.showUnblockFor(requireContext(), action.recipient) {
          dispatch(
            individual = { it.onEvent(IndividualSettingsEvent.UnblockConfirmed) },
            group = { it.onEvent(GroupSettingsEvent.UnblockConfirmed) }
          )
        }
      }
      is ConversationSettingsAction.ShowReportSpamDialog -> {
        BlockUnblockDialog.showReportSpamFor(
          requireContext(),
          action.recipient,
          {
            dispatch(
              individual = { it.onEvent(IndividualSettingsEvent.ReportSpamConfirmed) },
              group = { it.onEvent(GroupSettingsEvent.ReportSpamConfirmed) }
            )
          },
          if (action.canBlock) {
            Runnable {
              dispatch(
                individual = { it.onEvent(IndividualSettingsEvent.BlockAndReportSpamConfirmed) },
                group = { it.onEvent(GroupSettingsEvent.BlockAndReportSpamConfirmed) }
              )
            }
          } else {
            null
          }
        )
      }
      is ConversationSettingsAction.ShowBlockError -> {
        Toast.makeText(requireContext(), GroupErrors.getUserDisplayMessage(action.failureReason), Toast.LENGTH_LONG).show()
      }
      ConversationSettingsAction.ShowSpamReported -> {
        Toast.makeText(requireContext(), R.string.ConversationFragment_reported_as_spam, Toast.LENGTH_SHORT).show()
      }
      ConversationSettingsAction.ShowSpamReportedAndBlocked -> {
        Toast.makeText(requireContext(), R.string.ConversationFragment_reported_as_spam_and_blocked, Toast.LENGTH_SHORT).show()
      }
      is ConversationSettingsAction.ShowAddMembersError -> {
        Toast.makeText(requireContext(), GroupErrors.getUserDisplayMessage(action.failureReason), Toast.LENGTH_LONG).show()
      }
      is ConversationSettingsAction.ShowGroupInvitesSentDialog -> {
        if (action.invitesSentTo.isNotEmpty()) {
          GroupInviteSentDialog.show(childFragmentManager, action.invitesSentTo)
        }
      }
      is ConversationSettingsAction.ShowMembersAdded -> {
        val message = resources.getQuantityString(R.plurals.ManageGroupActivity_added, action.membersAddedCount, action.membersAddedCount)
        Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).show()
      }
      ConversationSettingsAction.GoToConversationList -> {
        goToConversationList()
      }
    }
  }

  private fun goToConversationList() {
    val eventSink = mainNavigationEventSink
    if (eventSink != null) {
      eventSink.onEvent(MainNavigationEvents.ExitDetail)
    } else {
<<<<<<< MOLLY
      startActivity(MainActivity.clearTopAndOpenDetail(requireContext(), MainNavigationDetailLocation.Empty))
    }
  }

  override fun getMaterial3OnScrollHelper(toolbar: Toolbar?): Material3OnScrollHelper {
    return object : Material3OnScrollHelper(
      activity = requireActivity(),
      views = listOf(toolbar!!),
      lifecycleOwner = viewLifecycleOwner,
      setStatusBarColor = { color ->
        if (!resources.isSplitPane() || activity is ConversationSettingsActivity) {
          WindowUtil.setStatusBarColor(requireActivity().window, color)
        }
      }
    ) {
      override val inactiveColorSet = ColorSet.from(requireContext(),
        toolbarColorRes = CoreUiR.color.signal_colorBackground_0,
        statusBarColorRes = com.google.android.material.R.attr.colorSurface
      )
    }
  }

  override fun bindAdapter(adapter: MappingAdapter) {
    nicknameLauncher = registerForActivityResult(NicknameActivity.Contract()) {
      // Intentionally left blank
    }

    val args = ConversationSettingsFragmentArgs.fromBundle(requireArguments())

    BioTextPreference.register(adapter)
    AvatarPreference.register(adapter)
    ButtonStripPreference.register(adapter)
    LargeIconClickPreference.register(adapter)
    SharedMediaPreference.register(adapter)
    RecipientPreference.register(adapter)
    InternalPreference.register(adapter)
    GroupDescriptionPreference.register(adapter)
    LegacyGroupPreference.register(adapter)
    CallPreference.register(adapter)

    val recipientId = args.recipientId
    if (recipientId != null) {
      Badge.register(adapter) { _, _, _ ->
      }
    }

    addToGroupStoryDelegate = AddToGroupStoryDelegate(this)
    viewModel.state.observe(viewLifecycleOwner) { state ->

      if (state.recipient != Recipient.UNKNOWN) {
        toolbarAvatar.buildOptions()
          .withQuickContactEnabled(false)
          .withUseSelfProfileAvatar(false)
          .withFixedSize(ViewUtil.dpToPx(80))
          .load(state.recipient)

        state.withRecipientSettingsState {
          toolbarTitle.text = if (state.recipient.isSelf) getString(R.string.note_to_self) else state.recipient.getDisplayName(requireContext())
        }

        state.withGroupSettingsState {
          toolbarTitle.text = it.groupTitle
          toolbar.menu.findItem(R.id.action_edit).isVisible = it.canEditGroupAttributes
        }
      }

      adapter.submitList(getConfiguration(state).toMappingModelList()) {
        if (state.isLoaded) {
          (view?.parent as? ViewGroup)?.doOnPreDraw {
            transitionCallback?.onReadyForEnterTransition()
          }
        }
      }
    }

    lifecycleDisposable.bindTo(viewLifecycleOwner)
    lifecycleDisposable += viewModel.events.subscribe { event ->
      when (event) {
        is ConversationSettingsEvent.AddToAGroup -> handleAddToAGroup(event)
        is ConversationSettingsEvent.AddMembersToGroup -> handleAddMembersToGroup(event)
        ConversationSettingsEvent.ShowGroupHardLimitDialog -> showGroupHardLimitDialog()
        is ConversationSettingsEvent.ShowAddMembersToGroupError -> showAddMembersToGroupError(event)
        is ConversationSettingsEvent.ShowBlockGroupError -> showBlockGroupError(event)
        is ConversationSettingsEvent.ShowGroupInvitesSentDialog -> showGroupInvitesSentDialog(event)
        is ConversationSettingsEvent.ShowMembersAdded -> showMembersAdded(event)
      }
    }
  }

  private fun getConfiguration(state: ConversationSettingsState): DSLConfiguration {
    return configure {
      if (state.recipient == Recipient.UNKNOWN) {
        return@configure
      }

      customPref(
        AvatarPreference.Model(
          recipient = state.recipient,
          storyViewState = state.storyViewState,
          onAvatarClick = { avatar ->
            val viewAvatarIntent = AvatarPreviewActivity.intentFromRecipientId(requireContext(), state.recipient.id)
            val viewAvatarTransitionBundle = AvatarPreviewActivity.createTransitionBundle(requireActivity(), avatar)

            if (Stories.isFeatureEnabled() && avatar.hasStory()) {
              val viewStoryIntent = StoryViewerActivity.createIntent(
                requireContext(),
                StoryViewerArgs(
                  recipientId = state.recipient.id,
                  isInHiddenStoryMode = state.recipient.shouldHideStory,
                  isFromQuote = true
                )
              )
              StoryDialogs.displayStoryOrProfileImage(
                context = requireContext(),
                onViewStory = { startActivity(viewStoryIntent) },
                onViewAvatar = { startActivity(viewAvatarIntent, viewAvatarTransitionBundle) }
              )
            } else if (!state.recipient.isSelf) {
              startActivity(viewAvatarIntent, viewAvatarTransitionBundle)
            }
          },
          onBadgeClick = { _ ->
          }
        )
      )

      state.withRecipientSettingsState {
        customPref(
          BioTextPreference.RecipientModel(
            recipient = state.recipient,
            linkedDevices = RecipientUtil.getSubDeviceCount(requireContext(), state.recipient).orElse(null),
            onHeadlineClickListener = if (state.recipient.isSelf || !state.recipient.isIndividual) {
              null
            } else{
              { AboutSheet.create(state.recipient).show(parentFragmentManager, null) }
            }
          )
        )
      }

      state.withGroupSettingsState { groupState ->

        val groupMembershipDescription = if (groupState.groupId.isV1) {
          String.format("%s · %s", groupState.membershipCountDescription, getString(R.string.ManageGroupActivity_legacy_group))
        } else if (!groupState.canEditGroupAttributes && groupState.groupDescription.isNullOrEmpty()) {
          groupState.membershipCountDescription
        } else {
          null
        }

        customPref(
          BioTextPreference.GroupModel(
            groupTitle = groupState.groupTitle,
            groupMembershipDescription = groupMembershipDescription,
            isTerminated = groupState.isTerminated
          )
        )

        if (groupState.groupId.isV2 && !groupState.isTerminated) {
          customPref(
            GroupDescriptionPreference.Model(
              groupId = groupState.groupId,
              groupDescription = groupState.groupDescription,
              descriptionShouldLinkify = groupState.groupDescriptionShouldLinkify,
              canEditGroupAttributes = groupState.canEditGroupAttributes,
              onEditGroupDescription = {
                startActivity(CreateProfileActivity.getIntentForGroupProfileWithFocusedDescription(requireActivity(), groupState.groupId))
              },
              onViewGroupDescription = {
                GroupDescriptionDialog.show(childFragmentManager, groupState.groupId, null, groupState.groupDescriptionShouldLinkify)
              }
            )
          )
        } else if (groupState.legacyGroupState != LegacyGroupPreference.State.NONE) {
          customPref(
            LegacyGroupPreference.Model(
              state = groupState.legacyGroupState,
              onLearnMoreClick = { GroupsLearnMoreBottomSheetDialogFragment.show(parentFragmentManager) },
              onMmsWarningClick = { startActivity(AppSettingsActivity.invite(requireContext())) }
            )
          )
        }
      }

      if (state.displayInternalRecipientDetails) {
        customPref(
          InternalPreference.Model(
            recipient = state.recipient,
            onInternalDetailsClicked = {
              val action = ConversationSettingsFragmentDirections.actionConversationSettingsFragmentToInternalDetailsSettingsFragment(state.recipient.id)
              navController.safeNavigate(action)
            }
          )
        )
      }

      customPref(
        ButtonStripPreference.Model(
          state = state.buttonStripState,
          enabled = !state.isDeprecatedOrUnregistered,
          onMessageClick = {
            val intent = ConversationIntents
              .createBuilderSync(requireContext(), state.recipient.id, state.threadId)
              .build()

            startActivity(intent)
          },
          onAddToStoryClick = {
            if (state.recipient.isPushV2Group && state.requireGroupSettingsState().isAnnouncementGroup && !state.requireGroupSettingsState().isSelfAdmin) {
              MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ConversationSettingsFragment__cant_add_to_group_story)
                .setMessage(R.string.ConversationSettingsFragment__only_admins_of_this_group_can_add_to_its_story)
                .setPositiveButton(android.R.string.ok) { d, _ -> d.dismiss() }
                .show()
            } else {
              addToGroupStoryDelegate.addToStory(state.recipient.id)
            }
          },
          onVideoClick = {
            if (state.recipient.isPushV2Group && state.requireGroupSettingsState().isAnnouncementGroup && !state.requireGroupSettingsState().isSelfAdmin) {
              MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ConversationActivity_cant_start_group_call)
                .setMessage(R.string.ConversationActivity_only_admins_of_this_group_can_start_a_call)
                .setPositiveButton(android.R.string.ok) { d, _ -> d.dismiss() }
                .show()
            } else {
              CommunicationActions.startVideoCall(requireActivity(), state.recipient) {
                YouAreAlreadyInACallSnackbar.show(requireView())
              }
            }
          },
          onAudioClick = {
            CommunicationActions.startVoiceCall(requireActivity(), state.recipient) {
              YouAreAlreadyInACallSnackbar.show(requireView())
            }
          },
          onMuteClick = { view ->
            if (!state.buttonStripState.isMuted) {
              MuteContextMenu.show(view, requireView() as ViewGroup, childFragmentManager, viewLifecycleOwner) { duration ->
                viewModel.setMuteUntil(duration)
              }
            } else {
              MaterialAlertDialogBuilder(requireContext())
                .setMessage(state.recipient.muteUntil.formatMutedUntil(requireContext()))
                .setPositiveButton(R.string.ConversationSettingsFragment__unmute) { dialog, _ ->
                  viewModel.unmute()
                  dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
                .show()
            }
          },
          onSearchClick = {
            lifecycleDisposable += ConversationIntents.createBuilder(requireContext(), state.recipient.id, state.threadId)
              .subscribeBy { builder ->
                val intent = builder
                  .withSearchOpen(true)
                  .build()

                startActivity(intent)
                if (requireActivity() !is MainNavigationChatDetailRouter) {
                  requireActivity().finish()
                }
              }
          }
        )
      )

      dividerPref()

      if (state.calls.isNotEmpty()) {
        val firstCall = state.calls.first()
        sectionHeaderPref(DSLSettingsText.from(DateUtils.formatDate(Locale.getDefault(), firstCall.record.timestamp)))

        for (call in state.calls) {
          customPref(call)
        }

        dividerPref()
      }

      if (state.recipient.isReleaseNotes) {
        textPref(
          icon = DSLSettingsIcon.from(R.drawable.symbol_official_20),
          title = DSLSettingsText.from(R.string.ReleaseNotes__this_is_official_chat)
        )
        textPref(
          icon = DSLSettingsIcon.from(R.drawable.symbol_bell_20),
          title = DSLSettingsText.from(R.string.ReleaseNotes__keep_up_to_date)
        )
        dividerPref()
      }

      val summary = DSLSettingsText.from(formatDisappearingMessagesLifespan(state.disappearingMessagesLifespan))
      val icon = if (state.disappearingMessagesLifespan <= 0 || state.recipient.isBlocked) {
        R.drawable.symbol_timer_slash_24
      } else {
        R.drawable.symbol_timer_24
      }

      var enabled = !state.recipient.isBlocked
      state.withGroupSettingsState {
        enabled = it.canEditGroupAttributes && !state.recipient.isBlocked
      }

      if (!state.recipient.isReleaseNotes && !state.recipient.isBlocked) {
        clickPref(
          title = DSLSettingsText.from(R.string.ConversationSettingsFragment__disappearing_messages),
          summary = summary,
          icon = DSLSettingsIcon.from(icon),
          isEnabled = enabled && !state.isDeprecatedOrUnregistered,
          onClick = {
            val action = ConversationSettingsFragmentDirections.actionConversationSettingsFragmentToAppSettingsExpireTimer()
              .setInitialValue(state.disappearingMessagesLifespan)
              .setRecipientId(state.recipient.id)
              .setForResultMode(false)

            navController.safeNavigate(action)
          }
        )
      }

      if (state.recipient.isIndividual && !state.recipient.isSelf) {
        clickPref(
          title = DSLSettingsText.from(R.string.NicknameActivity__nickname),
          icon = DSLSettingsIcon.from(CoreUiR.drawable.symbol_edit_24),
          onClick = {
            nicknameLauncher.launch(
              NicknameActivity.Args(
                state.recipient.id,
                false
              )
            )
          }
        )
      }

      if (!state.recipient.isReleaseNotes) {
        clickPref(
          title = DSLSettingsText.from(R.string.preferences__chat_color_and_wallpaper),
          icon = DSLSettingsIcon.from(R.drawable.symbol_color_24),
          onClick = {
            startActivity(ChatWallpaperActivity.createIntent(requireContext(), state.recipient.id))
          }
        )
      }

      if (!state.recipient.isSelf) {
        clickPref(
          title = if (RemoteConfig.internalUser) {
            DSLSettingsText.from("${getString(R.string.ConversationSettingsFragment__sounds_and_notifications)} (Internal Only)")
          } else {
            DSLSettingsText.from(R.string.ConversationSettingsFragment__sounds_and_notifications)
          },
          icon = DSLSettingsIcon.from(R.drawable.symbol_speaker_24),
          isEnabled = !state.isDeprecatedOrUnregistered,
          onClick = {
            val action = if (RemoteConfig.internalUser) {
              ConversationSettingsFragmentDirections.actionConversationSettingsFragmentToSoundsAndNotificationsSettingsFragment2(state.recipient.id)
            } else {
              ConversationSettingsFragmentDirections.actionConversationSettingsFragmentToSoundsAndNotificationsSettingsFragment(state.recipient.id)
            }

            navController.safeNavigate(action)
          }
        )
      }

      if (!state.recipient.isReleaseNotes && SignalStore.labs.starredMessages) {
        clickPref(
          title = DSLSettingsText.from(R.string.ConversationSettingsFragment__starred_messages),
          icon = DSLSettingsIcon.from(R.drawable.symbol_star_outline_24),
          onClick = {
            startActivity(StarredMessagesActivity.createIntent(requireContext(), state.threadId))
          }
        )
      }

      state.withRecipientSettingsState { recipientState ->
        when (recipientState.contactLinkState) {
          ContactLinkState.OPEN -> {
            @Suppress("DEPRECATION")
            clickPref(
              title = DSLSettingsText.from(R.string.ConversationSettingsFragment__contact_details),
              icon = DSLSettingsIcon.from(R.drawable.ic_profile_circle_24),
              onClick = {
                startActivityForResult(Intent(Intent.ACTION_VIEW, state.recipient.contactUri), REQUEST_CODE_VIEW_CONTACT)
              }
            )
          }

          ContactLinkState.ADD -> {
            @Suppress("DEPRECATION")
            clickPref(
              title = DSLSettingsText.from(R.string.ConversationSettingsFragment__add_to_your_phones_contacts),
              icon = DSLSettingsIcon.from(R.drawable.ic_plus_24),
              onClick = {
                try {
                  startActivityForResult(RecipientExporter.export(state.recipient).asAddContactIntent(), REQUEST_CODE_ADD_CONTACT)
                } catch (e: ActivityNotFoundException) {
                  Toast.makeText(context, R.string.ConversationSettingsFragment__contacts_app_not_found, Toast.LENGTH_SHORT).show()
                }
              }
            )
          }

          ContactLinkState.NONE -> {
          }
        }

        if (!state.recipient.isReleaseNotes && !state.recipient.isSelf) {
          clickPref(
            title = DSLSettingsText.from(R.string.ConversationSettingsFragment__view_safety_number),
            icon = DSLSettingsIcon.from(R.drawable.symbol_safety_number_24),
            isEnabled = !state.isDeprecatedOrUnregistered,
            onClick = {
              VerifyIdentityActivity.startOrShowExchangeMessagesDialog(requireActivity(), recipientState.identityRecord)
            }
          )
        }
      }

      if (state.sharedMedia.isNotEmpty()) {
        dividerPref()

        sectionHeaderPref(R.string.recipient_preference_activity__shared_media)

        @Suppress("DEPRECATION")
        customPref(
          SharedMediaPreference.Model(
            mediaRecords = state.sharedMedia,
            mediaIds = state.sharedMediaIds,
            onMediaRecordClick = { view, mediaRecord, isLtr ->
              val attachment = mediaRecord.attachment
              if (attachment == null) {
                Toast.makeText(context, R.string.ConversationSettingsFragment__this_media_is_not_sent_yet, Toast.LENGTH_LONG).show()
                return@Model
              }
              if (attachment.displayUri == null) {
                if (attachment.transferState == AttachmentTable.TRANSFER_RESTORE_OFFLOADED) {
                  AttachmentDownloadJob.downloadAttachmentIfNeeded(attachment)
                } else {
                  Toast.makeText(context, R.string.ConversationSettingsFragment__this_media_is_not_sent_yet, Toast.LENGTH_LONG).show()
                }
                return@Model
              }
              if (attachment.transferState != AttachmentTable.TRANSFER_PROGRESS_DONE &&
                attachment.transferState != AttachmentTable.TRANSFER_RESTORE_OFFLOADED
              ) {
                Toast.makeText(context, R.string.ConversationSettingsFragment__this_media_is_not_sent_yet, Toast.LENGTH_LONG).show()
                return@Model
              }
              view.transitionName = "thumb"
              val options = ActivityOptions.makeSceneTransitionAnimation(requireActivity(), view, "thumb")
              startActivityForResult(
                MediaIntentFactory.intentFromMediaRecord(requireContext(), mediaRecord, isLtr, allMediaInRail = true),
                REQUEST_CODE_RETURN_FROM_MEDIA,
                options.toBundle()
              )
            }
          )
        )

        @Suppress("DEPRECATION")
        clickPref(
          title = DSLSettingsText.from(R.string.ConversationSettingsFragment__see_all),
          onClick = {
            startActivityForResult(MediaOverviewActivity.forThread(requireContext(), state.threadId), REQUEST_CODE_RETURN_FROM_MEDIA)
          }
        )
      }

      if (state.recipient.isReleaseNotes) {
        dividerPref()
        sectionHeaderPref(R.string.preferences__help)

        externalLinkPref(
          icon = DSLSettingsIcon.from(R.drawable.symbol_help_24),
          title = DSLSettingsText.from(R.string.HelpSettingsFragment__support_center),
          linkId = R.string.support_center_url
        )
        clickPref(
          icon = DSLSettingsIcon.from(R.drawable.symbol_invite_24),
          title = DSLSettingsText.from(R.string.HelpSettingsFragment__contact_us),
          onClick = { startActivity(AppSettingsActivity.help(requireContext())) }
        )
        clickPref(
          icon = DSLSettingsIcon.from(R.drawable.symbol_heart_24),
          title = DSLSettingsText.from(R.string.preferences__donate_to_signal),
          onClick = { startActivity(CheckoutFlowActivity.createIntent(requireContext(), InAppPaymentType.ONE_TIME_DONATION)) }
        )
      }

      state.withRecipientSettingsState { recipientSettingsState ->
        if (state.recipient.badges.isNotEmpty() && !state.recipient.isSelf) {
          dividerPref()

          sectionHeaderPref(R.string.ManageProfileFragment_badges)

          displayBadges(requireContext(), state.recipient.badges)

          textPref(
            summary = DSLSettingsText.from(
              R.string.ConversationSettingsFragment__get_badges
            )
          )
        }

        if (recipientSettingsState.selfHasGroups && !state.recipient.isReleaseNotes) {
          dividerPref()

          val groupsInCommonCount = recipientSettingsState.allGroupsInCommon.size
          sectionHeaderPref(
            DSLSettingsText.from(
              if (groupsInCommonCount == 0) {
                getString(R.string.ManageRecipientActivity_no_groups_in_common)
              } else {
                resources.getQuantityString(
                  R.plurals.ManageRecipientActivity_d_groups_in_common,
                  groupsInCommonCount,
                  groupsInCommonCount
                )
              }
            )
          )

          if (!state.recipient.isBlocked) {
            customPref(
              LargeIconClickPreference.Model(
                title = DSLSettingsText.from(R.string.ConversationSettingsFragment__add_to_a_group),
                icon = DSLSettingsIcon.from(R.drawable.add_to_a_group, NO_TINT),
                isEnabled = !state.isDeprecatedOrUnregistered,
                onClick = {
                  viewModel.onAddToGroup()
                }
              )
            )
          }

          for (group in recipientSettingsState.groupsInCommon) {
            customPref(
              RecipientPreference.Model(
                recipient = group,
                onRowClick = {
                  CommunicationActions.startConversation(requireActivity(), group, null)
                  if (requireActivity() !is MainNavigationChatDetailRouter) {
                    requireActivity().finish()
                  }
                }
              )
            )
          }

          if (recipientSettingsState.canShowMoreGroupsInCommon) {
            customPref(
              LargeIconClickPreference.Model(
                title = DSLSettingsText.from(R.string.ConversationSettingsFragment__see_all),
                icon = DSLSettingsIcon.from(R.drawable.show_more, NO_TINT),
                onClick = {
                  viewModel.revealAllMembers()
                }
              )
            )
          }
        }
      }

      state.withGroupSettingsState { groupState ->
        val memberCount = groupState.allMembers.size
        val canAdd = groupState.canAddToGroup && !groupState.isTerminated && !state.isDeprecatedOrUnregistered

        if (groupState.canAddToGroup || memberCount > 0) {
          dividerPref()

          val memberHeaderText = if (groupState.isTerminated) {
            resources.getQuantityString(R.plurals.ConversationSettingsFragment__d_former_members, memberCount, memberCount)
          } else {
            resources.getQuantityString(R.plurals.ContactSelectionListFragment_d_members, memberCount, memberCount)
          }

          sectionHeaderPref(
            title = DSLSettingsText.from(memberHeaderText),
            iconEnd = DSLSettingsIcon.from(CoreUiR.drawable.symbol_search_24),
            onClick = {
              val action = ConversationSettingsFragmentDirections.actionConversationSettingsFragmentToMemberSearchFragment(groupState.groupId, canAdd, groupState.groupLinkEnabled)
              navController.safeNavigate(action)
            }
          )
        }

        if (canAdd) {
          customPref(
            LargeIconClickPreference.Model(
              title = DSLSettingsText.from(R.string.ConversationSettingsFragment__add_members),
              icon = DSLSettingsIcon.from(R.drawable.add_to_a_group, NO_TINT),
              onClick = {
                viewModel.onAddToGroup()
              }
            )
          )
        }

        colorizer.onGroupMembershipChanged(
          serviceIds = groupState.allMembers.mapNotNull { it.member.serviceId.orNull() }
        )

        for (member in groupState.members) {
          val canSetMemberLabel = member.member.isSelf && groupState.canSetOwnMemberLabel
          val memberLabel = member.getMemberLabel(groupState)

          customPref(
            RecipientPreference.Model(
              recipient = member.member,
              isAdmin = member.isAdmin,
              memberLabel = memberLabel,
              canSetMemberLabel = canSetMemberLabel,
              lifecycleOwner = viewLifecycleOwner,
              onRowClick = {
                if (canSetMemberLabel && memberLabel == null) {
                  val action = ConversationSettingsFragmentDirections
                    .actionConversationSettingsFragmentToMemberLabelFragment(groupState.groupId)
                  navController.safeNavigate(action)
                } else {
                  RecipientBottomSheetDialogFragment.show(parentFragmentManager, member.member.id, groupState.groupId)
                }
              },
              onAvatarClick = {
                RecipientBottomSheetDialogFragment.show(parentFragmentManager, member.member.id, groupState.groupId)
              }
            )
          )
        }

        if (groupState.canShowMoreGroupMembers) {
          customPref(
            LargeIconClickPreference.Model(
              title = DSLSettingsText.from(R.string.ConversationSettingsFragment__see_all),
              icon = DSLSettingsIcon.from(R.drawable.show_more, NO_TINT),
              onClick = {
                viewModel.revealAllMembers()
              }
            )
          )
        }

        if (state.recipient.isPushV2Group && !groupState.isTerminated) {
          dividerPref()

          clickPref(
            title = DSLSettingsText.from(R.string.ConversationSettingsFragment__group_link),
            summary = DSLSettingsText.from(if (groupState.groupLinkEnabled) R.string.preferences_on else R.string.preferences_off),
            icon = DSLSettingsIcon.from(R.drawable.ic_link_16),
            isEnabled = state.recipient.isActiveGroup && !state.isDeprecatedOrUnregistered,
            onClick = {
              navController.safeNavigate(ConversationSettingsFragmentDirections.actionConversationSettingsFragmentToShareableGroupLinkFragment(groupState.groupId))
            }
          )

          clickPref(
            title = DSLSettingsText.from(R.string.ConversationSettingsFragment__group_member_label),
            icon = DSLSettingsIcon.from(R.drawable.symbol_tag_24),
            isEnabled = groupState.canSetOwnMemberLabel && !state.isDeprecatedOrUnregistered,
            onClick = {
              val action = ConversationSettingsFragmentDirections.actionConversationSettingsFragmentToMemberLabelFragment(groupState.groupId)
              navController.safeNavigate(action)
            },
            onDisabledClicked = {
              Snackbar.make(requireView(), R.string.GroupMemberLabel__error_no_edit_permission, Snackbar.LENGTH_SHORT).show()
            }
          )

          clickPref(
            title = DSLSettingsText.from(R.string.ConversationSettingsFragment__requests_and_invites),
            icon = DSLSettingsIcon.from(R.drawable.ic_update_group_add_16),
            isEnabled = state.recipient.isActiveGroup && !state.isDeprecatedOrUnregistered,
            onClick = {
              startActivity(ManagePendingAndRequestingMembersActivity.newIntent(requireContext(), groupState.groupId.requireV2()))
            }
          )

          if (groupState.isSelfAdmin) {
            clickPref(
              title = DSLSettingsText.from(R.string.ConversationSettingsFragment__permissions),
              icon = DSLSettingsIcon.from(R.drawable.ic_lock_24),
              isEnabled = state.recipient.isActiveGroup && !state.isDeprecatedOrUnregistered,
              onClick = {
                val action = ConversationSettingsFragmentDirections.actionConversationSettingsFragmentToPermissionsSettingsFragment(groupState.groupId)
                navController.safeNavigate(action)
              }
            )
          }
        }

        if (groupState.canLeave) {
          dividerPref()

          clickPref(
            title = DSLSettingsText.from(R.string.conversation__menu_leave_group, if (state.isDeprecatedOrUnregistered) alertDisabledTint else alertTint),
            icon = DSLSettingsIcon.from(leaveIcon),
            isEnabled = !state.isDeprecatedOrUnregistered,
            onClick = {
              LeaveGroupDialog.handleLeavePushGroup(requireActivity(), groupState.groupId.requirePush(), null)
            }
          )
        }
      }

      state.withGroupSettingsState { groupState ->
        if (groupState.isTerminated) {
          dividerPref()

          if (state.isArchived) {
            clickPref(
              title = DSLSettingsText.from(R.string.ConversationListFragment_unarchive),
              icon = DSLSettingsIcon.from(R.drawable.symbol_archive_up_24),
              onClick = {
                viewModel.toggleArchive()
              }
            )
          } else {
            clickPref(
              title = DSLSettingsText.from(R.string.ConversationSettingsFragment__archive_chat),
              icon = DSLSettingsIcon.from(R.drawable.symbol_archive_24),
              onClick = {
                viewModel.toggleArchive()
                goToConversationList()
              }
            )
          }

          clickPref(
            title = DSLSettingsText.from(R.string.ConversationSettingsFragment__delete_chat, alertTint),
            icon = DSLSettingsIcon.from(CoreUiR.drawable.symbol_trash_24, R.color.signal_alert_primary),
            onClick = {
              val progressDialog = ProgressCardDialogFragment.create(getString(R.string.ConversationFragment_deleting_messages))
              progressDialog.show(parentFragmentManager, null)
              lifecycleScope.launch {
                viewModel.deleteChat()
                progressDialog.dismissAllowingStateLoss()
                goToConversationList()
              }
            }
          )
        }
      }

      if (state.canModifyBlockedState && !state.isTerminatedGroup) {
        state.withRecipientSettingsState {
          dividerPref()
        }

        state.withGroupSettingsState {
          if (!it.canLeave) {
            dividerPref()
          }
        }

        val isBlocked = state.recipient.isBlocked
        val isGroup = state.recipient.isPushGroup

        val title = when {
          isBlocked && isGroup -> R.string.ConversationSettingsFragment__unblock_group
          isBlocked -> R.string.ConversationSettingsFragment__unblock
          isGroup -> R.string.ConversationSettingsFragment__block_group
          else -> R.string.ConversationSettingsFragment__block
        }

        val titleTint = if (isBlocked) {
          null
        } else if (state.isDeprecatedOrUnregistered) {
          alertDisabledTint
        } else {
          alertTint
        }

        clickPref(
          title = if (titleTint != null) DSLSettingsText.from(title, titleTint) else DSLSettingsText.from(title),
          icon = if (isBlocked) DSLSettingsIcon.from(R.drawable.symbol_block_24) else DSLSettingsIcon.from(blockIcon),
          isEnabled = !state.isDeprecatedOrUnregistered,
          onClick = {
            if (state.recipient.isBlocked) {
              BlockUnblockDialog.showUnblockFor(requireContext(), state.recipient) {
                viewModel.unblock()
              }
            } else {
              BlockUnblockDialog.showBlockFor(requireContext(), state.recipient) {
                viewModel.block()
              }
            }
          }
        )

        if (!state.recipient.isReleaseNotes) {
          val reportSpamTint = if (state.isDeprecatedOrUnregistered) R.color.signal_alert_primary_50 else R.color.signal_alert_primary
          clickPref(
            title = DSLSettingsText.from(R.string.ConversationFragment_report_spam, ThemeUtil.getThemedColor(requireContext(), reportSpamTint)),
            icon = DSLSettingsIcon.from(R.drawable.symbol_spam_24, reportSpamTint),
            isEnabled = !state.isDeprecatedOrUnregistered,
            onClick = {
              BlockUnblockDialog.showReportSpamFor(
                requireContext(),
                state.recipient,
                {
                  viewModel
                    .onReportSpam()
                    .subscribeBy {
                      Toast.makeText(requireContext(), R.string.ConversationFragment_reported_as_spam, Toast.LENGTH_SHORT).show()
                      goToConversationList()
                    }
                    .addTo(lifecycleDisposable)
                },
                if (state.recipient.isBlocked) {
                  null
                } else {
                  Runnable {
                    viewModel
                      .onBlockAndReportSpam()
                      .subscribeBy { result ->
                        when (result) {
                          is Result.Success -> {
                            Toast.makeText(requireContext(), R.string.ConversationFragment_reported_as_spam_and_blocked, Toast.LENGTH_SHORT).show()
                            goToConversationList()
                          }

                          is Result.Failure -> {
                            Toast.makeText(requireContext(), GroupErrors.getUserDisplayMessage(result.failure), Toast.LENGTH_SHORT).show()
                          }
                        }
                      }
                      .addTo(lifecycleDisposable)
                  }
                }
              )
            }
          )
        }
      }

      state.withGroupSettingsState { groupState ->
        if (groupState.isTerminated) {
          dividerPref()

          val reportSpamTint = R.color.signal_alert_primary
          clickPref(
            title = DSLSettingsText.from(R.string.ConversationFragment_report_spam, ContextCompat.getColor(requireContext(), reportSpamTint)),
            icon = DSLSettingsIcon.from(R.drawable.symbol_spam_24, reportSpamTint),
            onClick = {
              BlockUnblockDialog.showReportSpamFor(
                requireContext(),
                state.recipient,
                {
                  viewModel
                    .onReportSpam()
                    .subscribeBy {
                      Toast.makeText(requireContext(), R.string.ConversationFragment_reported_as_spam, Toast.LENGTH_SHORT).show()
                      goToConversationList()
                    }
                    .addTo(lifecycleDisposable)
                },
                null
              )
            }
          )
        }
      }

      state.withGroupSettingsState { groupState ->
        if (groupState.canEndGroup) {
          dividerPref()

          clickPref(
            title = DSLSettingsText.from(R.string.ConversationSettingsFragment__end_group, if (state.isDeprecatedOrUnregistered) alertDisabledTint else alertTint),
            icon = DSLSettingsIcon.from(endGroupIcon),
            isEnabled = !state.isDeprecatedOrUnregistered,
            onClick = {
              EndGroupDialog.show(requireActivity(), groupState.groupId.requireV2(), groupState.groupTitle)
            }
          )
        }
      }
    }
  }

  private fun GroupMemberEntry.FullMember.getMemberLabel(
    groupState: SpecificSettingsState.GroupSettingsState
  ): StyledMemberLabel? {
    return groupState.memberLabelsByRecipientId[member.id]?.let { label ->
      val tintColor = colorizer.getIncomingGroupSenderColor(context = requireContext(), recipient = member)
      StyledMemberLabel(label, tintColor)
    }
  }

  private fun formatDisappearingMessagesLifespan(disappearingMessagesLifespan: Int): String {
    return if (disappearingMessagesLifespan <= 0) {
      getString(R.string.preferences_off)
    } else {
      ExpirationUtil.getExpirationDisplayValue(requireContext(), disappearingMessagesLifespan)
    }
  }

  private fun handleAddToAGroup(addToAGroup: ConversationSettingsEvent.AddToAGroup) {
    startActivity(AddToGroupsActivity.createIntent(requireContext(), addToAGroup.recipientId, addToAGroup.groupMembership))
  }

  @Suppress("DEPRECATION")
  private fun handleAddMembersToGroup(addMembersToGroup: ConversationSettingsEvent.AddMembersToGroup) {
    startActivityForResult(
      AddMembersActivity.createIntent(
        requireContext(),
        addMembersToGroup
      ),
      REQUEST_CODE_ADD_MEMBERS_TO_GROUP
    )
  }

  private fun showGroupHardLimitDialog() {
    GroupLimitDialog.showHardLimitMessage(requireContext())
  }

  private fun showAddMembersToGroupError(showAddMembersToGroupError: ConversationSettingsEvent.ShowAddMembersToGroupError) {
    Toast.makeText(requireContext(), GroupErrors.getUserDisplayMessage(showAddMembersToGroupError.failureReason), Toast.LENGTH_LONG).show()
  }

  private fun showBlockGroupError(showBlockGroupError: ConversationSettingsEvent.ShowBlockGroupError) {
    Toast.makeText(requireContext(), GroupErrors.getUserDisplayMessage(showBlockGroupError.failureReason), Toast.LENGTH_LONG).show()
  }

  private fun showGroupInvitesSentDialog(showGroupInvitesSentDialog: ConversationSettingsEvent.ShowGroupInvitesSentDialog) {
    if (showGroupInvitesSentDialog.invitesSentTo.isNotEmpty()) {
      GroupInviteSentDialog.show(childFragmentManager, showGroupInvitesSentDialog.invitesSentTo)
    }
  }

  private fun showMembersAdded(showMembersAdded: ConversationSettingsEvent.ShowMembersAdded) {
    val string = resources.getQuantityString(
      R.plurals.ManageGroupActivity_added,
      showMembersAdded.membersAddedCount,
      showMembersAdded.membersAddedCount
    )

    Snackbar.make(requireView(), string, Snackbar.LENGTH_SHORT).show()
  }

  private class ConversationSettingsOnUserScrolledAnimationHelper(
    private val toolbarAvatar: View,
    private val toolbarTitle: View,
    private val toolbarBackground: View
  ) : OnScrollAnimationHelper() {

    override val duration: Long = 200L

    private val actionBarSize = DimensionUnit.DP.toPixels(64f)
    private val rect = Rect()

    override fun getAnimationState(recyclerView: RecyclerView): AnimationState {
      val layoutManager = recyclerView.layoutManager!!
      val firstVisibleItemPosition = if (layoutManager is FlexboxLayoutManager) {
        layoutManager.findFirstVisibleItemPosition()
      } else {
        (layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
      }

      return if (firstVisibleItemPosition == 0) {
        val firstChild = requireNotNull(layoutManager.getChildAt(0))
        firstChild.getLocalVisibleRect(rect)

        if (rect.height() <= actionBarSize) {
          AnimationState.SHOW
        } else {
          AnimationState.HIDE
        }
      } else {
        AnimationState.SHOW
      }
    }

    override fun show(duration: Long) {
      toolbarAvatar
        .animate()
        .setDuration(duration)
        .translationY(0f)
        .alpha(1f)

      toolbarTitle
        .animate()
        .setDuration(duration)
        .translationY(0f)
        .alpha(1f)

      toolbarBackground
        .animate()
        .setDuration(duration)
        .alpha(1f)
    }

    override fun hide(duration: Long) {
      toolbarAvatar
        .animate()
        .setDuration(duration)
        .translationY(ViewUtil.dpToPx(56).toFloat())
        .alpha(0f)

      toolbarTitle
        .animate()
        .setDuration(duration)
        .translationY(ViewUtil.dpToPx(56).toFloat())
        .alpha(0f)

      toolbarBackground
        .animate()
        .setDuration(duration)
        .alpha(0f)
=======
      startActivity(MainActivity.clearTopAndExitDetail(requireContext()))
>>>>>>> SIGNAL
    }
  }

  /**
   * Implemented by hosts that postpone enter transitions (for example, shared element flows).
   *
   * Called when this fragment has loaded enough UI state to safely run the postponed enter
   * transition.
   */
  interface TransitionCallback {
    fun onReadyForEnterTransition()
  }
}

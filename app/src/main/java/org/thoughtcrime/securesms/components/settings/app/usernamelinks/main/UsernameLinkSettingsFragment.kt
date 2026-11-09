/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.usernamelinks.main

import androidx.compose.runtime.Composable
import androidx.navigation.fragment.findNavController
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.core.ui.compose.ComposeFragment
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.appSettingsRouter
import org.thoughtcrime.securesms.util.navigation.safeNavigate

/**
 * Fragment host for [UsernameLinkSettingsEntry] until the username link moves to Nav3. Only translates routes into the
 * nav graph.
 */
class UsernameLinkSettingsFragment : ComposeFragment() {

  private val appSettingsRouter by appSettingsRouter(onNavigateToScreen = ::navigateToScreen)

  private fun navigateToScreen(route: AppSettingsRoute) {
    when (route) {
      AppSettingsRoute.UsernameLinkRoute.QRColorPicker -> findNavController().safeNavigate(R.id.action_usernameLinkSettingsFragment_to_usernameLinkQrColorPickerFragment)
      else -> error("Unexpected route: ${route.javaClass.name}")
    }
  }

  @Composable
  override fun FragmentContent() {
<<<<<<< MOLLY
    val state by viewModel.state
    val navController: NavController by remember { mutableStateOf(findNavController()) }
    val linkCopiedEvent: UUID? by viewModel.linkCopiedEvent
    val helpText = stringResource(id = R.string.UsernameLinkSettings_scan_this_qr_code)

    val cameraViewModel: CameraScreenViewModel = viewModel { CameraScreenViewModel() }
    val cameraState by cameraViewModel.state

    val cameraPermissionState: PermissionState = rememberPermissionState(permission = Manifest.permission.CAMERA) {
      viewModel.onTabSelected(ActiveTab.Scan)
    }

    LaunchedEffect(cameraViewModel) {
      cameraViewModel.qrCodeDetected.collect { data ->
        viewModel.onQrCodeScanned(data)
      }
    }

    MainScreen(
      state = state,
      navController = navController,
      cameraState = cameraState,
      cameraEmitter = cameraViewModel::onEvent,
      cameraPermissionState = cameraPermissionState,
      onCodeTabSelected = { viewModel.onTabSelected(ActiveTab.Code) },
      onScanTabSelected = { viewModel.onTabSelected(ActiveTab.Scan) },
      onUsernameLinkResetResultHandled = { viewModel.onUsernameLinkResetResultHandled() },
      onShareBadge = { shareQrBadge(requireActivity(), viewModel.generateQrCodeImage(helpText)) },
      onQrResultHandled = { viewModel.onQrResultHandled() },
      onOpenCameraClicked = { askCameraPermissions() },
      onOpenGalleryClicked = { galleryLauncher.launch(Unit) },
      onLinkReset = { viewModel.onUsernameLinkReset() },
      onBackNavigationPressed = { requireActivity().onBackPressed() },
      linkCopiedEvent = linkCopiedEvent
    )
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
  }

  override fun onResume() {
    super.onResume()
    viewModel.onResume()
  }

  private fun askCameraPermissions() {
    Permissions.with(this)
      .request(Manifest.permission.CAMERA)
      .ifNecessary()
      .withPermanentDenialDialog(getString(MediaSendR.string.CameraXFragment_signal_needs_camera_access_scan_qr_code), null, MediaSendR.string.CameraXFragment_allow_access_camera, MediaSendR.string.CameraXFragment_to_scan_qr_codes, parentFragmentManager)
      .onAnyDenied { Toast.makeText(requireContext(), MediaSendR.string.CameraXFragment_signal_needs_camera_access_scan_qr_code, Toast.LENGTH_LONG).show() }
      .execute()
  }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun MainScreen(
  state: UsernameLinkSettingsState,
  navController: NavController? = null,
  cameraState: CameraScreenState = CameraScreenState(),
  cameraEmitter: (CameraScreenEvents) -> Unit = {},
  cameraPermissionState: PermissionState = previewPermissionState(),
  onCodeTabSelected: () -> Unit = {},
  onScanTabSelected: () -> Unit = {},
  onUsernameLinkResetResultHandled: () -> Unit = {},
  onShareBadge: () -> Unit = {},
  onQrResultHandled: () -> Unit = {},
  onOpenCameraClicked: () -> Unit = {},
  onOpenGalleryClicked: () -> Unit = {},
  onLinkReset: () -> Unit = {},
  onBackNavigationPressed: () -> Unit = {},
  linkCopiedEvent: UUID? = null
) {
  val context = LocalContext.current

  val snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
  val scope: CoroutineScope = rememberCoroutineScope()
  var showResetDialog: Boolean by remember { mutableStateOf(false) }
  val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

  val linkCopiedString = stringResource(R.string.UsernameLinkSettings_link_copied_toast)

  LaunchedEffect(linkCopiedEvent) {
    if (linkCopiedEvent != null) {
      snackbarHostState.showSnackbar(linkCopiedString)
    }
  }

  Scaffold(
    snackbarHost = { Snackbars.Host(snackbarHostState) },
    topBar = {
      TopAppBarContent(
        activeTab = state.activeTab,
        scrollBehavior = scrollBehavior,
        onCodeTabSelected = onCodeTabSelected,
        onScanTabSelected = onScanTabSelected,
        onBackNavigationPressed = onBackNavigationPressed
      )
    },
    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
  ) { contentPadding ->

    if (state.indeterminateProgress) {
      Dialogs.IndeterminateProgressDialog()
    }

    AnimatedVisibility(
      visible = state.activeTab == ActiveTab.Code,
      enter = slideInHorizontally(initialOffsetX = { fullWidth -> -fullWidth }),
      exit = slideOutHorizontally(targetOffsetX = { fullWidth -> -fullWidth })
    ) {
      UsernameLinkShareScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        scope = scope,
        modifier = Modifier.padding(contentPadding),
        navController = navController,
        onShareBadge = onShareBadge,
        onResetClicked = { showResetDialog = true },
        onLinkResultHandled = onUsernameLinkResetResultHandled
      )
    }

    AnimatedVisibility(
      visible = state.activeTab == ActiveTab.Scan,
      enter = slideInHorizontally(initialOffsetX = { fullWidth -> fullWidth }),
      exit = slideOutHorizontally(targetOffsetX = { fullWidth -> fullWidth })
    ) {
      UsernameQrScanScreen(
        qrScanResult = state.qrScanResult,
        cameraState = cameraState,
        cameraEmitter = cameraEmitter,
        onQrResultHandled = onQrResultHandled,
        onOpenCameraClicked = onOpenCameraClicked,
        onOpenGalleryClicked = onOpenGalleryClicked,
        hasCameraPermission = cameraPermissionState.status.isGranted,
        modifier = Modifier.padding(contentPadding),
        onRecipientFound = { recipient ->
          val taskStack = TaskStackBuilder
            .create(context)
            .addNextIntent(MainActivity.clearTop(context))

          CommunicationActions.startConversation(context, recipient, null, taskStack)
        }
      )
    }
  }

  if (showResetDialog) {
    ResetDialog(
      onConfirm = {
        onLinkReset()
        showResetDialog = false
      },
      onDismiss = { showResetDialog = false }
    )
  }
}

@Composable
private fun TopAppBarContent(
  activeTab: ActiveTab,
  scrollBehavior: TopAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
  onCodeTabSelected: () -> Unit = {},
  onScanTabSelected: () -> Unit = {},
  onBackNavigationPressed: () -> Unit = {}
) {
  CenterAlignedTopAppBar(
    modifier = Modifier
      .fillMaxWidth(),
    title = {
      Row(
        modifier = Modifier
          .fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
      ) {
        TabButton(
          label = stringResource(R.string.UsernameLinkSettings_code_tab_name),
          active = activeTab == ActiveTab.Code,
          onClick = onCodeTabSelected,
          modifier = Modifier.padding(end = 8.dp)
        )
        TabButton(
          label = stringResource(R.string.UsernameLinkSettings_scan_tab_name),
          active = activeTab == ActiveTab.Scan,
          onClick = { onScanTabSelected() },
          modifier = Modifier.padding(end = 8.dp)
        )
      }
    },
    navigationIcon = {
      IconButton(
        onClick = onBackNavigationPressed
      ) {
        Icon(
          painter = SignalIcons.X.painter,
          contentDescription = stringResource(android.R.string.cancel)
        )
      }
    },
    scrollBehavior = scrollBehavior
  )
}

@Composable
private fun TabButton(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val colors = if (active) {
    ButtonDefaults.buttonColors()
  } else {
    ButtonDefaults.buttonColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
      contentColor = MaterialTheme.colorScheme.onSurface
    )
  }
  Buttons.Small(
    onClick = onClick,
    modifier = modifier.defaultMinSize(minWidth = 100.dp),
    shape = RoundedCornerShape(12.dp),
    colors = colors
  ) {
    Text(label)
  }
}

@Composable
private fun ResetDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
  Dialogs.SimpleAlertDialog(
    title = stringResource(id = R.string.UsernameLinkSettings_reset_link_dialog_title),
    body = stringResource(id = R.string.UsernameLinkSettings_reset_link_dialog_body),
    confirm = stringResource(id = R.string.UsernameLinkSettings_reset_link_dialog_confirm_button),
    dismiss = stringResource(id = android.R.string.cancel),
    onConfirm = onConfirm,
    onDismiss = onDismiss
  )
}

@DayNightPreviews
@Composable
private fun AppBarPreview() {
  Previews.Preview {
    Surface {
      Column {
        TopAppBarContent(activeTab = ActiveTab.Code)
        TopAppBarContent(activeTab = ActiveTab.Scan)
      }
    }
  }
}

@DayNightPreviews
@Composable
private fun MainScreenPreview() {
  Previews.Preview {
    MainScreen(
      state = UsernameLinkSettingsState(
        activeTab = ActiveTab.Code,
        username = "PeterParker.42",
        usernameLinkState = UsernameLinkState.Present("https://signal.org"),
        qrCodeState = QrCodeState.Present(QrCodeData.forData("PeterParker.42")),
        qrCodeColorScheme = UsernameQrCodeColorScheme.Orange
      )
    )
  }
}

@DayNightPreviews
@Composable
private fun ResetDialogPreview() {
  Previews.Preview {
    Surface {
      ResetDialog(onConfirm = {}, onDismiss = {})
    }
  }
}

private fun previewPermissionState(): PermissionState {
  return object : PermissionState {
    override val permission: String = ""
    override val status: PermissionStatus = PermissionStatus.Granted
    override fun launchPermissionRequest() = Unit
  }
}

private fun shareQrBadge(activity: Activity, badge: Bitmap?) {
  if (badge == null) {
    return
  }

  try {
    ByteArrayOutputStream().use { byteArrayOutputStream ->
      badge.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
      byteArrayOutputStream.flush()
      val bytes = byteArrayOutputStream.toByteArray()
      val shareUri = AppDependencies.blobs
        .forData(bytes)
        .withMimeType("image/png")
        .withFileName("SignalUsernameQr.png")
        .createForSingleSessionInMemory()

      val intent = ShareCompat.IntentBuilder(activity)
        .setType("image/png")
        .setStream(shareUri)
        .createChooserIntent()
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      activity.startActivity(intent)
    }
  } finally {
    badge.recycle()
=======
    UsernameLinkSettingsEntry(parentEventEmitter = appSettingsRouter::onEvent)
>>>>>>> SIGNAL
  }
}

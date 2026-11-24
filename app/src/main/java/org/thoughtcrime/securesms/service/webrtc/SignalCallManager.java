package org.thoughtcrime.securesms.service.webrtc;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.LongSparseArray;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.models.ServiceId.ACI;
import org.signal.core.models.recipients.RecipientId;
import org.signal.core.util.AppForegroundObserver;
import org.signal.core.util.Util;
import org.signal.core.util.concurrent.KeyedSerialMonoLifoExecutor;
import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.eventbus.EventBus;
import org.signal.core.util.logging.Log;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.zkgroup.GenericServerPublicParams;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.VerificationFailedException;
import org.signal.libsignal.zkgroup.calllinks.CallLinkAuthCredentialPresentation;
import org.signal.libsignal.zkgroup.calllinks.CallLinkSecretParams;
import org.signal.libsignal.zkgroup.groups.GroupIdentifier;
import org.signal.network.NetworkResult;
import org.signal.network.crypto.SealedSenderAccess;
import org.signal.ringrtc.CallException;
import org.signal.ringrtc.CallId;
import org.signal.ringrtc.CallLinkRootKey;
import org.signal.ringrtc.CallManager;
import org.signal.ringrtc.CallSummary;
import org.signal.ringrtc.GroupCall.Reaction;
import org.signal.ringrtc.GroupCall;
import org.signal.ringrtc.HttpHeader;
import org.signal.ringrtc.NetworkRoute;
import org.signal.ringrtc.PeekInfo;
import org.signal.ringrtc.Remote;
import org.signal.storageservice.storage.protos.groups.ExternalGroupCredential;
import org.thoughtcrime.securesms.calls.permissions.CallAuthorization;
import org.thoughtcrime.securesms.calls.permissions.CallEraId;
import org.thoughtcrime.securesms.calls.permissions.CallPermissions;
import org.thoughtcrime.securesms.calls.permissions.CallSessionKey;
import org.thoughtcrime.securesms.calls.permissions.GroupCallAdmission;
import org.thoughtcrime.securesms.components.webrtc.v2.CallIntent;
import org.thoughtcrime.securesms.components.webrtc.CallLinkProfileKeySender;
import org.thoughtcrime.securesms.crypto.SealedSenderAccessUtil;
import org.thoughtcrime.securesms.crypto.storage.SignalServiceAccountDataStoreImpl;
import org.thoughtcrime.securesms.database.CallLinkTable;
import org.thoughtcrime.securesms.database.CallTable;
import org.thoughtcrime.securesms.database.GroupTable;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.model.GroupRecord;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.events.CallParticipant;
import org.thoughtcrime.securesms.events.GroupCallPeekEvent;
import org.thoughtcrime.securesms.events.WebRtcViewModel;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.GroupManager;
import org.thoughtcrime.securesms.groups.v2.admission.GroupAdmission;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobs.CallSyncEventJob;
import org.thoughtcrime.securesms.jobs.GroupCallUpdateSendJob;
import org.thoughtcrime.securesms.jobs.RetrieveProfileJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.messages.GroupSendUtil;
import org.thoughtcrime.securesms.net.SignalNetwork;
import org.thoughtcrime.securesms.notifications.v2.ConversationId;
import org.thoughtcrime.securesms.ratelimit.ProofRequiredExceptionHandler;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientIds;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.ringrtc.CameraEventListener;
import org.thoughtcrime.securesms.ringrtc.CameraState;
import org.thoughtcrime.securesms.ringrtc.RemotePeer;
import org.thoughtcrime.securesms.service.webrtc.links.CallLinkRoomId;
import org.thoughtcrime.securesms.service.webrtc.links.SignalCallLinkManager;
import org.thoughtcrime.securesms.service.webrtc.state.WebRtcEphemeralState;
import org.thoughtcrime.securesms.service.webrtc.state.WebRtcServiceState;
import org.thoughtcrime.securesms.util.RecipientAccessList;
import org.thoughtcrime.securesms.util.rx.RxStore;
import org.thoughtcrime.securesms.webrtc.CallNotificationBuilder;
import org.thoughtcrime.securesms.webrtc.audio.SignalAudioManager;
import org.thoughtcrime.securesms.webrtc.locks.LockManager;
import org.webrtc.PeerConnection;
import org.whispersystems.signalservice.api.NetworkResultUtil;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.api.messages.calls.CallingResponse;
import org.whispersystems.signalservice.api.messages.calls.OfferMessage;
import org.whispersystems.signalservice.api.messages.calls.OpaqueMessage;
import org.whispersystems.signalservice.api.messages.calls.SignalServiceCallMessage;
import org.whispersystems.signalservice.api.messages.calls.TurnServerInfo;
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage;
import org.whispersystems.signalservice.api.push.exceptions.ProofRequiredException;
import org.whispersystems.signalservice.api.push.exceptions.UnregisteredUserException;
import org.whispersystems.signalservice.internal.push.SyncMessage;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import kotlin.Pair;
import kotlin.jvm.functions.Function1;
import kotlin.text.Charsets;

import static org.thoughtcrime.securesms.events.WebRtcViewModel.GroupCallState.IDLE;
import static org.thoughtcrime.securesms.events.WebRtcViewModel.State.CALL_INCOMING;
import static org.thoughtcrime.securesms.events.WebRtcViewModel.State.NETWORK_FAILURE;
import static org.thoughtcrime.securesms.events.WebRtcViewModel.State.NO_SUCH_USER;
import static org.thoughtcrime.securesms.events.WebRtcViewModel.State.UNTRUSTED_IDENTITY;
import static org.thoughtcrime.securesms.service.webrtc.WebRtcUtil.getUrgencyFromCallUrgency;

/**
 * Entry point for all things calling. Lives for the life of the app instance and will spin up a foreground service when needed to
 * handle "active" calls.
 */
public final class SignalCallManager implements CallManager.Observer, GroupCall.Observer, CameraEventListener, AppForegroundObserver.Listener {

  private static final String TAG = Log.tag(SignalCallManager.class);

  public static final int BUSY_TONE_LENGTH = 2000;

  @Nullable private final CallManager callManager;

  private final Context                     context;
  private final ExecutorService             serviceExecutor;
  private final KeyedSerialMonoLifoExecutor keyedExecutor;
  private final LockManager                 lockManager;

  private volatile WebRtcServiceState   serviceState;
  private final Map<RemotePeer, Long>   peerGenerations = new IdentityHashMap<>();
  private long                         groupCallGeneration;
  @Nullable private volatile GroupCallOrigin groupCallOrigin;
  private RxStore<WebRtcEphemeralState> ephemeralStateStore;
  private boolean                       needsToSetSelfUuid = true;
  private RelaunchListener              pipRelaunchListener;

  private RxStore<Map<RecipientId, CallLinkPeekInfo>> linkPeekInfoStore;

  public SignalCallManager(@NonNull Application application) {
    this(application, null, null);
  }

  /** Supplies an inert native manager and a controlled queue to admission-boundary tests. */
  SignalCallManager(@NonNull Application application, @Nullable CallManager testCallManager, @Nullable ExecutorService testExecutor) {
    this.context             = application.getApplicationContext();
    this.lockManager         = new LockManager(this.context);
    this.serviceExecutor     = testExecutor != null ? testExecutor : Executors.newSingleThreadExecutor();
    this.keyedExecutor       = new KeyedSerialMonoLifoExecutor(SignalExecutors.BOUNDED_IO);
    this.ephemeralStateStore = new RxStore<>(new WebRtcEphemeralState(), Schedulers.from(serviceExecutor));
    this.linkPeekInfoStore   = new RxStore<>(new HashMap<>(), Schedulers.from(serviceExecutor));

    CallManager callManager = testCallManager;
    try {
      if (callManager == null) {
        callManager = CallManager.createCallManager(this);
      }
    } catch (CallException e) {
      Log.w(TAG, "Unable to create CallManager", e);
    }
    this.callManager = callManager;

    this.serviceState = new WebRtcServiceState(new IdleActionProcessor(new WebRtcInteractor(this.context,
                                                                                            this,
                                                                                            lockManager,
                                                                                            this,
                                                                                            this,
                                                                                            this)));
    CallPermissions.setRevocationListener(this::onCallPermissionsChanged);
  }

  public @NonNull Flowable<WebRtcEphemeralState> ephemeralStates() {
    return ephemeralStateStore.getStateFlowable().distinctUntilChanged();
  }

  @NonNull CallManager getRingRtcCallManager() {
    //noinspection ConstantConditions
    return callManager;
  }

  @NonNull LockManager getLockManager() {
    return lockManager;
  }

  public @NonNull Flowable<Map<RecipientId, CallLinkPeekInfo>> getPeekInfoCache() {
    return linkPeekInfoStore.getStateFlowable();
  }

  public @NonNull Map<RecipientId, CallLinkPeekInfo> getPeekInfoSnapshot() {
    return linkPeekInfoStore.getState();
  }

  private void process(@NonNull ProcessAction action) {
    process(null, CallPermissions.generation(), action);
  }

  private void process(@Nullable CallAuthorization authorization, @NonNull ProcessAction action) {
    process(authorization, CallPermissions.generation(), action);
  }

  private void process(long generation, @NonNull ProcessAction action) {
    process(null, generation, action);
  }

  private void process(@Nullable CallAuthorization authorization, long generation, @NonNull ProcessAction action) {
    Throwable t      = new Throwable();
    String    caller = t.getStackTrace().length > 1 ? t.getStackTrace()[1].getMethodName() : "unknown";

    if (callManager == null) {
      Log.w(TAG, "Unable to process action, call manager is not initialized");
      return;
    }

    serviceExecutor.execute(() -> {
      Runnable dispatch = () -> {
        if (needsToSetSelfUuid) {
          try {
            ACI selfAci = SignalStore.account().getAci();
            if (selfAci == null || !CallPermissions.isCurrent(generation)) return;
            callManager.setSelfUuid(selfAci.getRawUuid());
            needsToSetSelfUuid = false;
          } catch (CallException e) {
            Log.w(TAG, "Unable to set self UUID on CallManager", e);
          }
        }

        Log.v(TAG, "Processing action: " + caller + ", handler: " + serviceState.getActionProcessor().getTag());
        WebRtcServiceState previous = serviceState;
        serviceState = action.process(previous, previous.getActionProcessor());

        if (previous != serviceState) {
          // Retain the original dispatch epoch for native objects. A callback
          // arriving after revoke/re-enable must never acquire the new epoch.
          Map<RemotePeer, Long> currentPeerGenerations = new IdentityHashMap<>();
          for (RemotePeer peer : serviceState.getCallInfoState().getPeerMap().values()) {
            currentPeerGenerations.put(peer, peerGenerations.getOrDefault(peer, generation));
          }
          peerGenerations.clear();
          peerGenerations.putAll(currentPeerGenerations);
          if (previous.getCallInfoState().getGroupCall() != serviceState.getCallInfoState().getGroupCall()) {
            groupCallGeneration = generation;
            GroupCall group = serviceState.getCallInfoState().getGroupCall();
            CallSessionKey key = group != null ? CallPermissions.currentCallLinkSession(serviceState.getCallInfoState().getCallRecipient().getId()) : null;
            groupCallOrigin = group != null ? new GroupCallOrigin(group, key) : null;
          }
          if (serviceState.getCallInfoState().getCallState() != WebRtcViewModel.State.IDLE) {
            postStateUpdate(serviceState);
          } else if (previous.getCallInfoState().getCallState() != WebRtcViewModel.State.IDLE) {
            CallLinkProfileKeySender.clear();
            EventBus.getDefault().removeStickyEvent(WebRtcViewModel.class);
          }
        }
      };

      // Recheck the generation on serialized dispatch. A queued payload
      // cannot reach even setSelfUuid(), let alone native message decoding,
      // after its authorization has been revoked.
      if (authorization != null) {
        CallPermissions.runAuthorized(authorization, dispatch);
      } else {
        CallPermissions.runIfCurrent(generation, dispatch);
      }
    });
  }

  private void onCallPermissionsChanged() {
    // This listener belongs to an existing manager; policy changes never obtain
    // AppDependencies.signalCallManager merely to stop calling.
    serviceExecutor.execute(() -> {
      peerGenerations.clear();
      groupCallGeneration = 0;
      groupCallOrigin = null;
      needsToSetSelfUuid = true;
      CallLinkProfileKeySender.clear();
      WebRtcActionProcessor processor = serviceState.getActionProcessor();
      if (serviceState.getCallInfoState().getGroupCall() != null) {
        serviceState = processor.groupCallFailure(serviceState, "Calling authorization changed", null);
      } else if (serviceState.getCallInfoState().getActivePeer() != null) {
        serviceState = processor.callFailure(serviceState, "Calling authorization changed", null);
      } else {
        serviceState = processor.handleCancelPreJoinCall(serviceState);
        if (callManager != null) {
          try {
            callManager.reset();
          } catch (CallException e) {
            Log.w(TAG, "Unable to reset calling after permission change", e);
          }
        }
      }
      EventBus.getDefault().removeStickyEvent(WebRtcViewModel.class);
    });
  }

  /**
   * Processes the given update to {@link WebRtcEphemeralState}.
   *
   * @param transformer The transformation to apply to the state. Runs on the {@link #serviceExecutor}.
   */
  @AnyThread
  private void processStateless(@NonNull Function1<WebRtcEphemeralState, WebRtcEphemeralState> transformer) {
    long generation = CallPermissions.generation();
    ephemeralStateStore.update(state -> CallPermissions.isCurrent(generation) ? transformer.invoke(state) : state);
  }

  private void processGroupCallback(@NonNull GroupCall groupCall, @NonNull ProcessAction action) {
    processGroupCallback(CallPermissions.generation(), groupCall, action);
  }

  private void processGroupCallback(long generation, @Nullable GroupCall groupCall, @NonNull ProcessAction action) {
    // Native callbacks may arrive after normal hangup and a new call, without a
    // policy generation change. They belong only to their exact GroupCall object.
    if (groupCall == null) return;
    process(generation, (s, p) -> s.getCallInfoState().getGroupCall() == groupCall && groupCallGeneration == generation ? action.process(s, p) : s);
  }

  private void processPeerCallback(@NonNull RemotePeer peer, @NonNull ProcessAction action) {
    process((s, p) -> isCurrentPeer(s, peer) ? action.process(s, p) : s);
  }

  private boolean isCurrentPeer(@NonNull WebRtcServiceState state, @NonNull RemotePeer peer) {
    Long generation = peerGenerations.get(peer);
    return state.getCallInfoState().getPeer(peer.hashCode()) == peer && generation != null && CallPermissions.isCurrent(generation);
  }

  private void processGroupRosterCallback(@NonNull GroupCall groupCall, @NonNull ProcessAction action) {
    GroupCallOrigin capturedOrigin = groupCallOrigin;
    processGroupCallback(groupCall, (s, p) -> {
      // The source object's creation-bound session is authoritative, including
      // when a native callback is queued before createGroupCall() returns.
      GroupCallOrigin origin = capturedOrigin != null && capturedOrigin.groupCall == groupCall ? capturedOrigin : groupCallOrigin;
      PeekInfo peekInfo = groupCall.getPeekInfo();
      if (origin != null && origin.groupCall == groupCall && origin.session != null && peekInfo != null) {
        Map<Long, UUID> devices = new HashMap<>();
        LongSparseArray<GroupCall.RemoteDeviceState> remoteDevices = groupCall.getRemoteDeviceStates();
        for (int i = 0; i < remoteDevices.size(); i++) {
          GroupCall.RemoteDeviceState device = remoteDevices.valueAt(i);
          devices.put(device.getDemuxId(), device.getUserId());
        }
        for (GroupCallAdmission.DeferredOpaque pending : GroupCallAdmission.updateCallLinkParticipants(origin.session, devices, peekInfo.getJoinedMembers())) {
          receivedOpaqueMessage(pending.getAuthorization(), pending.getMetadata());
        }
      }
      // Publish joined membership before the processor can queue profile jobs.
      return action.process(s, p);
    });
  }

  private static final class GroupCallOrigin {
    @NonNull final GroupCall groupCall;
    @Nullable final CallSessionKey session;

    GroupCallOrigin(@NonNull GroupCall groupCall, @Nullable CallSessionKey session) {
      this.groupCall = groupCall;
      this.session = session;
    }
  }

  public void startPreJoinCall(@NonNull Recipient recipient) {
    process((s, p) -> {
      Recipient target = Recipient.resolved(recipient.getId());
      return !GroupAdmission.isHeld(target) && CallPermissions.beginOutgoing(target.getId()) ? p.handlePreJoinCall(s, new RemotePeer(target.getId())) : s;
    });
  }

  public void startOutgoingAudioCall(@NonNull Recipient recipient) {
    process((s, p) -> {
      Recipient target = Recipient.resolved(recipient.getId());
      return beginLocalCall(target) ? p.handleOutgoingCall(s, new RemotePeer(target.getId()), OfferMessage.Type.AUDIO_CALL) : s;
    });
  }

  public void startOutgoingVideoCall(@NonNull Recipient recipient) {
    process((s, p) -> {
      Recipient target = Recipient.resolved(recipient.getId());
      return beginLocalCall(target) ? p.handleOutgoingCall(s, new RemotePeer(target.getId()), OfferMessage.Type.VIDEO_CALL) : s;
    });
  }

  private boolean beginLocalCall(@NonNull Recipient recipient) {
    if (GroupAdmission.isHeld(recipient) || !CallPermissions.beginOutgoing(recipient.getId())) return false;
    // Pressing Join is fresh local consent even after a long stay in the lobby.
    return !(recipient.isGroup() || recipient.isCallLink()) || CallPermissions.activateGroup(recipient.getId());
  }

  public void cancelPreJoin() {
    process((s, p) -> {
      CallPermissions.endAllSessions();
      return p.handleCancelPreJoinCall(s);
    });
  }

  public void updateRenderedResolutions() {
    process((s, p) -> p.handleUpdateRenderedResolutions(s));
  }

  public void orientationChanged(boolean isLandscapeEnabled, int degrees) {
    process((s, p) -> p.handleOrientationChanged(s, isLandscapeEnabled, degrees));
  }

  public void setMuteAudio(boolean enabled) {
    process((s, p) -> p.handleSetMuteAudio(s, enabled));
  }

  public void setEnableVideo(boolean enabled) {
    process((s, p) -> p.handleSetEnableVideo(s, enabled));
  }

  public void startScreenShare(@NonNull android.content.Intent mediaProjectionData) {
    process((s, p) -> p.handleSetLocalScreenShare(s, true, mediaProjectionData));
  }

  public void stopScreenShare() {
    process((s, p) -> p.handleSetLocalScreenShare(s, false, null));
  }

  public void onScreenSharingServiceReady() {
    process((s, p) -> p.handleScreenSharingServiceReady(s));
  }

  public void setIncomingRingingVanity(boolean enabled) {
    process((s, p) -> p.handleSetIncomingRingingVanity(s, enabled));
  }

  public void flipCamera() {
    process((s, p) -> p.handleSetCameraFlip(s));
  }

  public void acceptCall(boolean answerWithVideo) {
    process((s, p) -> {
      Recipient recipient = s.getCallInfoState().getCallRecipient();
      if (recipient == null) return s;
      if (recipient.isGroup() || recipient.isCallLink()) {
        if (!CallPermissions.activateGroup(recipient.getId())) return s;
      } else {
        if (!CallPermissions.activateCall(recipient.getId())) return s;
      }
      return p.handleAcceptCall(s, answerWithVideo);
    });
  }

  public void denyCall() {
    process((s, p) -> p.handleDenyCall(s));
  }

  public void localHangup() {
    process((s, p) -> p.handleLocalHangup(s));
  }

  public void requestUpdateGroupMembers() {
    process((s, p) -> p.handleGroupRequestUpdateMembers(s));
  }

  public void groupApproveSafetyChange(@NonNull List<RecipientId> changedRecipients) {
    process((s, p) -> p.handleGroupApproveSafetyNumberChange(s, changedRecipients));
  }

  public void isCallActive(@Nullable ResultReceiver resultReceiver) {
    process((s, p) -> p.handleIsInCallQuery(s, resultReceiver));
  }

  public void networkChange(boolean available) {
    process((s, p) -> p.handleNetworkChanged(s, available));
  }

  public void dataModeUpdate() {
    process((s, p) -> p.handleDataModeUpdate(s));
  }

  public void screenOff() {
    process((s, p) -> p.handleScreenOffChange(s));
  }

  public void raiseHand(boolean raised) {
    process((s, p) -> p.handleSelfRaiseHand(s, raised));
  }

  public void react(@NonNull String reaction) {
    processStateless(s -> serviceState.getActionProcessor().handleSendGroupReact(serviceState, s, reaction));
  }

  public void postStateUpdate(@NonNull WebRtcServiceState state) {
    EventBus.getDefault().postSticky(new WebRtcViewModel(state));
  }

  public void receivedOffer(@NonNull CallAuthorization authorization,
                            @NonNull WebRtcData.CallMetadata callMetadata,
                            @NonNull WebRtcData.OfferMetadata offerMetadata,
                            @NonNull WebRtcData.ReceivedOfferMetadata receivedOfferMetadata)
  {
    process(authorization, (s, p) -> p.handleReceivedOffer(s, callMetadata, offerMetadata, receivedOfferMetadata));
  }

  public void receivedAnswer(@NonNull CallAuthorization authorization,
                             @NonNull WebRtcData.CallMetadata callMetadata,
                             @NonNull WebRtcData.AnswerMetadata answerMetadata,
                             @NonNull WebRtcData.ReceivedAnswerMetadata receivedAnswerMetadata)
  {
    process(authorization, (s, p) -> p.handleReceivedAnswer(s, callMetadata, answerMetadata, receivedAnswerMetadata));
  }

  public void receivedIceCandidates(@NonNull CallAuthorization authorization, @NonNull WebRtcData.CallMetadata callMetadata, @NonNull List<byte[]> iceCandidates) {
    process(authorization, (s, p) -> p.handleReceivedIceCandidates(s, callMetadata, iceCandidates));
  }

  public void receivedCallHangup(@NonNull CallAuthorization authorization, @NonNull WebRtcData.CallMetadata callMetadata, @NonNull WebRtcData.HangupMetadata hangupMetadata) {
    process(authorization, (s, p) -> p.handleReceivedHangup(s, callMetadata, hangupMetadata));
  }

  public void receivedCallBusy(@NonNull CallAuthorization authorization, @NonNull WebRtcData.CallMetadata callMetadata) {
    process(authorization, (s, p) -> p.handleReceivedBusy(s, callMetadata));
  }

  public void receivedOpaqueMessage(@NonNull CallAuthorization authorization, @NonNull WebRtcData.OpaqueMessageMetadata opaqueMessageMetadata) {
    process(authorization, (s, p) -> p.handleReceivedOpaqueMessage(s, opaqueMessageMetadata));
  }

  public void setRingGroup(boolean ringGroup) {
    process((s, p) -> p.handleSetRingGroup(s, ringGroup));
  }

  private void receivedGroupCallPeekForRingingCheck(long generation, @NonNull GroupCallRingCheckInfo info, @NonNull PeekInfo peekInfo, @NonNull String permit, @NonNull RecipientId senderId) {
    process(generation, (s, p) -> {
      if (!CallPermissions.isGroupPeekPermitValid(permit, info.getRecipientId(), senderId) ||
          !CallPermissions.isIncomingGroupRingCurrent(info.getRecipientId(), info.getRingId(), senderId)) return s;
      return p.handleReceivedGroupCallPeekForRingingCheck(s, info, peekInfo);
    });
  }

  public void onAudioDeviceChanged(@NonNull SignalAudioManager.AudioDevice activeDevice, @NonNull Set<SignalAudioManager.AudioDevice> availableDevices) {
    process((s, p) -> p.handleAudioDeviceChanged(s, activeDevice, availableDevices));
  }

  public void onAudioDeviceChangeFailed() {
    process((s, p) -> p.handleAudioDeviceChangeFailed(s));
  }

  public void onAudioReadyForAccept() {
    process((s, p) -> p.handleAudioReadyForAccept(s));
  }

  public void onBluetoothPermissionDenied() {
    process((s, p) -> p.handleBluetoothPermissionDenied(s));
  }

  public void onMicrophoneSilencedChanged(boolean silenced) {
    process((s, p) -> p.handleMicrophoneSilencedChanged(s, silenced));
  }

  public void selectAudioDevice(@NonNull SignalAudioManager.ChosenAudioDeviceIdentifier desiredDevice) {
    process((s, p) -> p.handleSetUserAudioDevice(s, desiredDevice));
  }

  public void setTelecomApproved(long callId, @NonNull RecipientId recipientId) {
    process((s, p) -> p.handleSetTelecomApproved(s, callId, recipientId));
  }

  public void dropCall(long callId) {
    process((s, p) -> p.handleDropCall(s, callId));
  }

  public void setCallLinkJoinRequestAccepted(@NonNull RecipientId participant) {
    process((s, p) -> p.handleSetCallLinkJoinRequestAccepted(s, participant));
  }

  public void setCallLinkJoinRequestRejected(@NonNull RecipientId participant) {
    process((s, p) -> p.handleSetCallLinkJoinRequestRejected(s, participant));
  }

  public void sendRemoteMuteRequest(@NonNull CallParticipant participant) {
    process((s, p) -> p.handleSendRemoteMuteRequest(s, participant));
  }

  public void removeFromCallLink(@NonNull CallParticipant participant) {
    process((s, p) -> p.handleRemoveFromCallLink(s, participant));
  }

  public void blockFromCallLink(@NonNull CallParticipant participant) {
    process((s, p) -> p.handleBlockFromCallLink(s, participant));
  }

  public void peekCallLinkCall(@NonNull RecipientId id) {
    if (!CallPermissions.canUseCallLinks()) return;
    long generation = CallPermissions.generation();
    if (callManager == null) {
      Log.i(TAG, "Unable to peekCallLinkCall, call manager is null");
      return;
    }

    keyedExecutor.execute(id.toString(), () -> {
      if (!CallPermissions.isCurrent(generation) || !CallPermissions.canUseCallLinks()) return;
      try {
        Recipient              callLinkRecipient = Recipient.resolved(id);
        CallLinkRoomId         callLinkRoomId    = callLinkRecipient.requireCallLinkRoomId();
        CallLinkTable.CallLink callLink          = SignalDatabase.callLinks().getCallLinkByRoomId(callLinkRoomId);

        if (callLink == null || callLink.getCredentials() == null) {
          Log.w(TAG, "Cannot peek call link without credentials.");
          return;
        }

        byte[]                    callLinkRootKeyBytes      = callLink.getCredentials().getLinkKeyBytes();
        GenericServerPublicParams genericServerPublicParams = new GenericServerPublicParams(AppDependencies.getSignalServiceNetworkAccess()
                                                                                                           .getConfiguration()
                                                                                                           .getGenericServerPublicParams());


        CallLinkAuthCredentialPresentation callLinkAuthCredentialPresentation = AppDependencies.getGroupsV2Authorization()
                                                                                               .getCallLinkAuthorizationForToday(
                                                                                                   genericServerPublicParams,
                                                                                                   CallLinkSecretParams.deriveFromRootKey(callLinkRootKeyBytes)
                                                                                               );

        if (!CallPermissions.isCurrent(generation) || !CallPermissions.canUseCallLinks()) return;
        CallLinkRootKey callLinkRootKey = new CallLinkRootKey(callLinkRootKeyBytes);
        nativeIfCurrent(generation, () -> callManager.peekCallLinkCall(SignalStore.internal().getGroupCallingServer(), callLinkAuthCredentialPresentation.serialize(), callLinkRootKey, peekInfo -> {
          if (!CallPermissions.isCurrent(generation) || !CallPermissions.canUseCallLinks()) return;
          PeekInfo info = peekInfo.getValue();
          if (info == null) {
            Log.w(TAG, "Failed to get peek info: " + peekInfo.getStatus());
            return;
          }

          String eraId = info.getEraId();
          if (eraId != null && !info.getJoinedMembers().isEmpty()) {
            if (SignalDatabase.calls().insertAdHocCallFromLocalObserveEvent(callLinkRecipient, System.currentTimeMillis(), eraId)) {
              if (!CallPermissions.isCurrent(generation)) return;
              AppDependencies.getJobManager()
                             .add(CallSyncEventJob.createForObserved(callLinkRecipient.getId(), CallEraId.fromEra(eraId)));
            }
          }

          emitCallLinkPeekInfoUpdate(id, info);
        }));
      } catch (CallException | VerificationFailedException | InvalidInputException | IOException e) {
        Log.i(TAG, "error peeking call link", e);
      }
    });
  }

  public void emitCallLinkPeekInfoUpdate(@NonNull RecipientId recipientId, @NonNull PeekInfo peekInfo) {
    long generation = CallPermissions.generation();
    linkPeekInfoStore.update(store -> {
      if (!CallPermissions.isCurrent(generation) || !CallPermissions.canUseCallLinks()) return store;
      Map<RecipientId, CallLinkPeekInfo> newHashMap = new HashMap<>(store);
      newHashMap.put(recipientId, CallLinkPeekInfo.fromPeekInfo(peekInfo));
      return newHashMap;
    });
  }

  public void peekGroupCall(@NonNull RecipientId id) {
    peekGroupCall(id, null);
  }

  public void peekGroupCall(@NonNull RecipientId id, @Nullable Consumer<PeekInfo> onWillUpdateCallFromPeek) {
    peekGroupCall(id, onWillUpdateCallFromPeek, () -> true);
  }

  public void peekGroupCall(@NonNull RecipientId id, @Nullable Consumer<PeekInfo> onWillUpdateCallFromPeek, @NonNull BooleanSupplier isRequestAuthorized) {
    if (!CallPermissions.canUseGroup(id) || !isRequestAuthorized.getAsBoolean()) return;
    long generation = CallPermissions.generation();
    if (callManager == null) {
      Log.i(TAG, "Unable to peekGroupCall, call manager is null");
      return;
    }

    keyedExecutor.execute(id.toString(), () -> {
      if (!CallPermissions.isCurrent(generation) || !CallPermissions.canUseGroup(id) || !isRequestAuthorized.getAsBoolean()) return;
      try {
        Recipient group = Recipient.resolved(id);
        if (GroupAdmission.isHeld(group)) {
          return;
        }
        if (!group.getGroupId().isPresent()) {
          Log.w(TAG, "Recipient " + id + " is no longer a group. Skipping peek.");
          return;
        }

        GroupId.V2              groupId    = group.requireGroupId().requireV2();
        ExternalGroupCredential credential = GroupManager.getExternalGroupCredential(context, groupId);

        List<GroupCall.GroupMemberInfo> members = GroupManager.getUuidCipherTexts(context, groupId).entrySet().stream()
                                                              .map(entry -> new GroupCall.GroupMemberInfo(entry.getKey(), entry.getValue().serialize())).collect(Collectors.toList());
        nativeIfGroupCurrent(generation, id, () -> {
          if (GroupAdmission.isHeld(groupId) || !isRequestAuthorized.getAsBoolean()) return;
          callManager.peekGroupCall(SignalStore.internal().getGroupCallingServer(), credential.token.getBytes(Charsets.UTF_8), members, peekInfo -> {
            if (GroupAdmission.isHeld(groupId) || !CallPermissions.isCurrent(generation) || !CallPermissions.canUseGroup(id) || !isRequestAuthorized.getAsBoolean()) return;
            Long threadId = SignalDatabase.threads().getThreadIdFor(group.getId());

            if (threadId != null) {
              if (onWillUpdateCallFromPeek != null) {
                onWillUpdateCallFromPeek.accept(peekInfo);
              }

              SignalDatabase.calls()
                            .updateGroupCallFromPeek(threadId,
                                                     peekInfo.getEraId(),
                                                     peekInfo.getJoinedMembers(),
                                                     WebRtcUtil.isCallFull(peekInfo));

              AppDependencies.getMessageNotifier().updateNotification(context, ConversationId.forConversation(threadId));

              EventBus.getDefault().postSticky(new GroupCallPeekEvent(id, peekInfo.getEraId(), peekInfo.getDeviceCount(), peekInfo.getMaxDevices()));
            }
          });
        });
      } catch (IOException | VerificationFailedException | CallException e) {
        Log.i(TAG, "error peeking from active conversation", e);
      }
    });
  }

  public void peekGroupCallForRingingCheck(@NonNull GroupCallRingCheckInfo info) {
    if (!CallPermissions.canUseGroup(info.getRecipientId())) return;
    long generation = CallPermissions.generation();
    RecipientId senderId = Recipient.externalPush(info.getRingerAci()).getId();
    String permit = CallPermissions.createGroupPeekPermit(info.getRecipientId(), senderId);
    if (permit == null) return;
    BooleanSupplier isRequestAuthorized = () -> CallPermissions.isGroupPeekPermitValid(permit, info.getRecipientId(), senderId) &&
                                               CallPermissions.isIncomingGroupRingCurrent(info.getRecipientId(), info.getRingId(), senderId);
    if (!isRequestAuthorized.getAsBoolean()) return;
    if (callManager == null) {
      Log.i(TAG, "Unable to peekGroupCall, call manager is null");
      return;
    }

    keyedExecutor.execute("Call::" + info.getRecipientId(), () -> {
      if (!CallPermissions.isCurrent(generation) || !isRequestAuthorized.getAsBoolean()) return;
      try {
        Recipient               group      = Recipient.resolved(info.getRecipientId());
        GroupId.V2              groupId    = group.requireGroupId().requireV2();
        if (GroupAdmission.isHeld(groupId)) {
          return;
        }
        ExternalGroupCredential credential = GroupManager.getExternalGroupCredential(context, groupId);

        List<GroupCall.GroupMemberInfo> members = GroupManager.getUuidCipherTexts(context, groupId)
                                                              .entrySet()
                                                              .stream()
                                                              .map(entry -> new GroupCall.GroupMemberInfo(entry.getKey(), entry.getValue().serialize()))
                                                              .collect(Collectors.toList());

        nativeIfGroupCurrent(generation, info.getRecipientId(), () -> {
          if (GroupAdmission.isHeld(groupId) || !isRequestAuthorized.getAsBoolean()) return;
          callManager.peekGroupCall(SignalStore.internal().getGroupCallingServer(),
                                    credential.token.getBytes(Charsets.UTF_8),
                                    members,
                                    peekInfo -> {
                                      if (!GroupAdmission.isHeld(groupId) && CallPermissions.isCurrent(generation) && isRequestAuthorized.getAsBoolean()) {
                                        receivedGroupCallPeekForRingingCheck(generation, info, peekInfo, permit, senderId);
                                      }
                                    });
        });
      } catch (IOException | VerificationFailedException | CallException e) {
        Log.i(TAG, "error peeking for ringing check", e);
      }
    });
  }

  private void nativeIfCurrent(long generation, @NonNull NativeAction action) throws CallException {
    CallException[] failure = new CallException[1];
    CallPermissions.runIfCurrent(generation, () -> {
      try {
        action.run();
      } catch (CallException e) {
        failure[0] = e;
      }
    });
    if (failure[0] != null) throw failure[0];
  }

  private void nativeIfGroupCurrent(long generation, @NonNull RecipientId recipientId, @NonNull NativeAction action) throws CallException {
    nativeIfCurrent(generation, () -> {
      // Credentials may block on network work while this group is blocked or left.
      // Those identity changes do not necessarily change the calling-policy generation.
      if (CallPermissions.canUseGroup(recipientId) && CallPermissions.isCurrent(generation)) action.run();
    });
  }

  private interface NativeAction {
    void run() throws CallException;
  }

  void requestGroupMembershipToken(@NonNull GroupId.V2 groupId, @NonNull GroupCall groupCall) {
    long generation = CallPermissions.generation();
    SignalExecutors.BOUNDED_IO.execute(() -> {
      RecipientId recipientId = Recipient.externalPossiblyMigratedGroup(groupId).getId();
      if (GroupAdmission.isHeld(groupId) || serviceState.getCallInfoState().getGroupCall() != groupCall ||
          !CallPermissions.canContinueCall(recipientId) || !CallPermissions.isCurrent(generation)) return;
      try {
        ExternalGroupCredential credential = GroupManager.getExternalGroupCredential(context, groupId);
        if (GroupAdmission.isHeld(groupId) || !CallPermissions.canContinueCall(recipientId) || !CallPermissions.isCurrent(generation)) return;
        processGroupCallback(generation, groupCall, (s, p) ->
            !GroupAdmission.isHeld(groupId) && CallPermissions.canContinueCall(recipientId) && CallPermissions.isCurrent(generation)
                ? p.handleGroupMembershipProofResponse(s, groupCall.hashCode(), credential.token.getBytes(Charsets.UTF_8)) : s);
      } catch (IOException e) {
        Log.w(TAG, "Unable to get group membership proof from service", e);
        processGroupCallback(generation, groupCall, (s, p) -> p.handleGroupCallEnded(s, groupCall.hashCode(), CallManager.CallEndReason.SFU_CLIENT_FAILED_TO_JOIN));
      } catch (VerificationFailedException e) {
        Log.w(TAG, "Unable to verify group membership proof", e);
        processGroupCallback(generation, groupCall, (s, p) -> p.handleGroupCallEnded(s, groupCall.hashCode(), CallManager.CallEndReason.DEVICE_EXPLICITLY_DISCONNECTED));
      }
    });
  }

  /**
   * Attempts to launch the call screen. On newer OS versions we can only do this when we're already in the
   * foreground, otherwise the background activity start is blocked and we have to rely on the full screen
   * intent attached to the CallStyle notification.
   */
  public boolean startCallCardActivityIfPossible() {
    if (Build.VERSION.SDK_INT >= CallNotificationBuilder.API_LEVEL_CALL_STYLE && !AppForegroundObserver.isForegrounded()) {
      return false;
    }

    context.startActivity(new Intent(context, CallIntent.getActivityClass()).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    return true;
  }

  @Override
  public void onStartCall(@Nullable Remote remote,
                          @NonNull CallId callId,
                          @NonNull Boolean isOutgoing,
                          @Nullable CallManager.CallMediaType callMediaType)
  {
    Log.i(TAG, "onStartCall(): callId: " + callId + ", outgoing: " + isOutgoing + ", type: " + callMediaType);

    if (callManager == null) {
      Log.w(TAG, "Unable to start call, call manager is not initialized");
      return;
    }

    if (remote == null) {
      return;
    }

    process((s, p) -> {
      RemotePeer remotePeer = (RemotePeer) remote;
      if (!isCurrentPeer(s, remotePeer)) {
        Log.w(TAG, "remotePeer not found in map with key: " + remotePeer.hashCode() + "! Dropping.");
        try {
          callManager.drop(callId);
        } catch (CallException e) {
          Log.w(TAG, "Unable to drop stale call callback", e);
        }
        return s;
      }

      if (isOutgoing && !remotePeer.getCallId().equals(RemotePeer.NO_CALL_ID)) {
        Log.w(TAG, "Ignoring a repeated start for an already bound outgoing peer");
        return s;
      }
      if (isOutgoing && !CallPermissions.bindOutgoing(remotePeer.getId(), callId.longValue())) {
        return p.callFailure(s, "Outgoing call authorization expired", null);
      }

      remotePeer.setCallId(callId);

      if (isOutgoing) {
        return p.handleStartOutgoingCall(s, remotePeer, WebRtcUtil.getOfferTypeFromCallMediaType(callMediaType));
      } else {
        return p.handleStartIncomingCall(s, remotePeer, WebRtcUtil.getOfferTypeFromCallMediaType(callMediaType));
      }
    });
  }

  @Override
  public void onCallEnded(@Nullable Remote remote, @NonNull CallManager.CallEndReason reason, @NonNull CallSummary summary) {
    if (callManager == null) {
      Log.w(TAG, "Unable to process call end, call manager is not initialized");
      return;
    }

    if (!(remote instanceof RemotePeer)) {
      return;
    }

    process((s, p) -> {
      RemotePeer remotePeer = (RemotePeer) remote;
      if (!isCurrentPeer(s, remotePeer)) {
        Log.w(TAG, "remotePeer not found in map with key: " + remotePeer.hashCode() + "! Dropping.");
        try {
          callManager.drop(remotePeer.getCallId());
        } catch (CallException e) {
          Log.w(TAG, "Unable to drop stale call callback", e);
        }
        return s;
      }

      Log.i(TAG, "onCallEnded(): call_id: " + remotePeer.getCallId() + ", state: " + remotePeer.getState() + ", reason: " + reason);

      if (s.getCallInfoState().getGroupCall() != null) {
        Log.i(TAG, "onCallEnded(): call_id: bypassing call summary handling for group call, this is handled in onEnded(groupCall, ...)");
      } else {
        boolean     hasRemoteVideoContent = s.getCallInfoState()
                                             .getRemoteCallParticipants()
                                             .stream()
                                             .anyMatch(participant -> participant.isVideoEnabled() || participant.isScreenSharing());

        CameraState cameraState          = s.getLocalDeviceState().getCameraState();
        boolean     isLocalVideoEnabled  = cameraState.isEnabled() && cameraState.getCameraCount() > 0;
      }

      switch (reason) {
        case LOCAL_HANGUP:
          Log.i(TAG, "Ignoring end reason: " + reason);
          break;
        case REMOTE_HANGUP:
        case REMOTE_HANGUP_NEED_PERMISSION:
        case REMOTE_HANGUP_ACCEPTED:
        case REMOTE_HANGUP_DECLINED:
        case REMOTE_HANGUP_BUSY:
        case REMOTE_BUSY:
        case REMOTE_GLARE:
        case REMOTE_RECALL:
          return p.handleEndedRemote(s, reason, remotePeer);
        case TIMEOUT:
        case INTERNAL_FAILURE:
        case SIGNALING_FAILURE:
        case CONNECTION_FAILURE:
          return p.handleEnded(s, reason, remotePeer);
        case APP_DROPPED_CALL:
          Log.i(TAG, "Ignoring end reason: " + reason);
          break;
        case DEVICE_EXPLICITLY_DISCONNECTED:
        case SERVER_EXPLICITLY_DISCONNECTED:
        case DENIED_REQUEST_TO_JOIN_CALL:
        case REMOVED_FROM_CALL:
        case CALL_MANAGER_IS_BUSY:
        case SFU_CLIENT_FAILED_TO_JOIN:
        case FAILED_TO_CREATE_PEER_CONNECTION_FACTORY:
        case FAILED_TO_NEGOTIATE_SRTP_KEYS:
        case FAILED_TO_CREATE_PEER_CONNECTION:
        case FAILED_TO_START_PEER_CONNECTION:
        case FAILED_TO_UPDATE_PEER_CONNECTION:
        case FAILED_TO_SET_MAX_SEND_BITRATE:
        case ICE_FAILED_WHILE_CONNECTING:
        case ICE_FAILED_AFTER_CONNECTED:
        case SERVER_CHANGED_DEMUXID:
        case HAS_MAX_DEVICES:
        default:
          throw new AssertionError("Unexpected end reason: " + reason);
      }

      return s;
    });
  }

  @Override
  public void onCallEvent(@Nullable Remote remote, @NonNull CallManager.CallEvent event) {
    if (callManager == null) {
      Log.w(TAG, "Unable to process call event, call manager is not initialized");
      return;
    }

    if (!(remote instanceof RemotePeer)) {
      return;
    }

    process((s, p) -> {
      RemotePeer remotePeer = (RemotePeer) remote;
      if (!isCurrentPeer(s, remotePeer)) {
        Log.w(TAG, "remotePeer not found in map with key: " + remotePeer.hashCode() + "! Dropping.");
        try {
          callManager.drop(remotePeer.getCallId());
        } catch (CallException e) {
          Log.w(TAG, "Unable to drop stale call callback", e);
        }
        return s;
      }

      Log.i(TAG, "onCallEvent(): call_id: " + remotePeer.getCallId() + ", state: " + remotePeer.getState() + ", event: " + event);

      // A concluded peer remains mapped until RingRTC's final cleanup callback.
      // Its late media/connection events must not update a subsequent call.
      if (s.getCallInfoState().getActivePeer() != remotePeer &&
          event != CallManager.CallEvent.RECEIVED_OFFER_EXPIRED &&
          event != CallManager.CallEvent.RECEIVED_OFFER_WHILE_ACTIVE &&
          event != CallManager.CallEvent.RECEIVED_OFFER_WITH_GLARE) return s;

      switch (event) {
        case LOCAL_RINGING:
          return p.handleLocalRinging(s, remotePeer);
        case REMOTE_RINGING:
          return p.handleRemoteRinging(s, remotePeer);
        case RECONNECTING:
        case RECONNECTED:
          return p.handleCallReconnect(s, event);
        case LOCAL_CONNECTED:
        case REMOTE_CONNECTED:
          if (!CallPermissions.activateCall(remotePeer.getId())) {
            return p.callFailure(s, "Call authorization expired before connection", null);
          }
          return p.handleCallConnected(s, remotePeer);
        case REMOTE_AUDIO_ENABLE:
          return p.handleRemoteAudioEnable(s, true);
        case REMOTE_AUDIO_DISABLE:
          return p.handleRemoteAudioEnable(s, false);
        case REMOTE_VIDEO_ENABLE:
          return p.handleRemoteVideoEnable(s, true);
        case REMOTE_VIDEO_DISABLE:
          return p.handleRemoteVideoEnable(s, false);
        case REMOTE_SHARING_SCREEN_ENABLE:
          return p.handleScreenSharingEnable(s, true);
        case REMOTE_SHARING_SCREEN_DISABLE:
          return p.handleScreenSharingEnable(s, false);
        case GLARE_HANDLING_FAILURE:
          // Something broke when handling glare, end as an internal failure.
          return p.handleEnded(s, CallManager.CallEndReason.INTERNAL_FAILURE, remotePeer);
        case RECEIVED_OFFER_EXPIRED:
          return p.handleReceivedOfferExpired(s, remotePeer);
        case RECEIVED_OFFER_WHILE_ACTIVE:
        case RECEIVED_OFFER_WITH_GLARE:
          return p.handleReceivedOfferWhileActive(s, remotePeer);
        default:
          throw new AssertionError("Unexpected event: " + event);
      }
    });
  }

  @Override public void onNetworkRouteChanged(Remote remote, NetworkRoute networkRoute) {
    process((s, p) -> remote instanceof RemotePeer && s.getCallInfoState().getActivePeer() == remote && isCurrentPeer(s, (RemotePeer) remote) ? p.handleNetworkRouteChanged(s, networkRoute) : s);
  }

  @Override
  public void onAudioLevels(Remote remote, int capturedLevel, int receivedLevel) {
    processStateless(s -> remote instanceof RemotePeer && serviceState.getCallInfoState().getActivePeer() == remote && isCurrentPeer(serviceState, (RemotePeer) remote) ? serviceState.getActionProcessor().handleAudioLevelsChanged(serviceState, s, capturedLevel, receivedLevel) : s);
  }

  @Override
  public void onLowBandwidthForVideo(@Nullable Remote remote, boolean recovered) {
    // TODO: Implement handling of the "low outgoing bandwidth for video" notification.
  }

  @Override
  public void onCallConcluded(@Nullable Remote remote) {
    if (!(remote instanceof RemotePeer)) {
      return;
    }

    RemotePeer remotePeer = (RemotePeer) remote;
    Log.i(TAG, "onCallConcluded: call_id: " + remotePeer.getCallId());
    processPeerCallback(remotePeer, (s, p) -> {
      CallPermissions.endCall(remotePeer.getId(), remotePeer.getCallId().longValue());
      return p.handleCallConcluded(s, remotePeer);
    });
  }

  @Override
  public void onSendOffer(@NonNull CallId callId,
                          @Nullable Remote remote,
                          @NonNull Integer remoteDevice,
                          @NonNull Boolean broadcast,
                          @NonNull byte[] opaque,
                          @NonNull CallManager.CallMediaType callMediaType)
  {
    if (!(remote instanceof RemotePeer)) {
      return;
    }
    RemotePeer remotePeer = (RemotePeer) remote;

    Log.i(TAG, "onSendOffer: id: " + remotePeer.getCallId().format(remoteDevice) + " type: " + callMediaType.name());

    OfferMessage.Type        offerType     = WebRtcUtil.getOfferTypeFromCallMediaType(callMediaType);
    WebRtcData.CallMetadata  callMetadata  = new WebRtcData.CallMetadata(remotePeer, remoteDevice);
    WebRtcData.OfferMetadata offerMetadata = new WebRtcData.OfferMetadata(opaque, offerType);

    processPeerCallback(remotePeer, (s, p) -> s.getCallInfoState().getActivePeer() == remotePeer ? p.handleSendOffer(s, callMetadata, offerMetadata, broadcast) : s);
  }

  @Override
  public void onSendAnswer(@NonNull CallId callId,
                           @Nullable Remote remote,
                           @NonNull Integer remoteDevice,
                           @NonNull Boolean broadcast,
                           @NonNull byte[] opaque)
  {
    if (!(remote instanceof RemotePeer)) {
      return;
    }

    RemotePeer remotePeer = (RemotePeer) remote;

    Log.i(TAG, "onSendAnswer: id: " + remotePeer.getCallId().format(remoteDevice));

    WebRtcData.CallMetadata   callMetadata   = new WebRtcData.CallMetadata(remotePeer, remoteDevice);
    WebRtcData.AnswerMetadata answerMetadata = new WebRtcData.AnswerMetadata(opaque);

    processPeerCallback(remotePeer, (s, p) -> s.getCallInfoState().getActivePeer() == remotePeer ? p.handleSendAnswer(s, callMetadata, answerMetadata, broadcast) : s);
  }

  @Override
  public void onSendIceCandidates(@NonNull CallId callId,
                                  @Nullable Remote remote,
                                  @NonNull Integer remoteDevice,
                                  @NonNull Boolean broadcast,
                                  @NonNull List<byte[]> iceCandidates)
  {
    if (!(remote instanceof RemotePeer)) {
      return;
    }

    RemotePeer remotePeer = (RemotePeer) remote;

    Log.i(TAG, "onSendIceCandidates: id: " + remotePeer.getCallId().format(remoteDevice));

    WebRtcData.CallMetadata callMetadata = new WebRtcData.CallMetadata(remotePeer, remoteDevice);

    processPeerCallback(remotePeer, (s, p) -> s.getCallInfoState().getActivePeer() == remotePeer ? p.handleSendIceCandidates(s, callMetadata, broadcast, iceCandidates) : s);
  }

  @Override
  public void onSendHangup(@NonNull CallId callId, @Nullable Remote remote, @NonNull Integer remoteDevice, @NonNull Boolean broadcast, @NonNull CallManager.HangupType hangupType, @NonNull Integer deviceId) {
    if (!(remote instanceof RemotePeer)) {
      return;
    }

    RemotePeer remotePeer = (RemotePeer) remote;

    Log.i(TAG, "onSendHangup: id: " + remotePeer.getCallId().format(remoteDevice) + " type: " + hangupType.name());

    WebRtcData.CallMetadata   callMetadata   = new WebRtcData.CallMetadata(remotePeer, remoteDevice);
    WebRtcData.HangupMetadata hangupMetadata = new WebRtcData.HangupMetadata(WebRtcUtil.getHangupTypeFromCallHangupType(hangupType), deviceId);

    processPeerCallback(remotePeer, (s, p) -> p.handleSendHangup(s, callMetadata, hangupMetadata, broadcast));
  }

  @Override
  public void onSendBusy(@NonNull CallId callId, @Nullable Remote remote, @NonNull Integer remoteDevice, @NonNull Boolean broadcast) {
    if (!(remote instanceof RemotePeer)) {
      return;
    }

    RemotePeer remotePeer = (RemotePeer) remote;

    Log.i(TAG, "onSendBusy: id: " + remotePeer.getCallId().format(remoteDevice));

    WebRtcData.CallMetadata callMetadata = new WebRtcData.CallMetadata(remotePeer, remoteDevice);

    processPeerCallback(remotePeer, (s, p) -> p.handleSendBusy(s, callMetadata, broadcast));
  }

  @Override
  public void onSendCallMessage(@NonNull UUID aciUuid, @NonNull byte[] message, @NonNull CallManager.CallMessageUrgency urgency) {
    Log.i(TAG, "onSendCallMessage():");
    if (!CallPermissions.isCallingAllowed()) return;
    long generation = CallPermissions.generation();
    GroupCall outgoingGroup = serviceState.getCallInfoState().getGroupCall();

    OpaqueMessage            opaqueMessage = new OpaqueMessage(message, getUrgencyFromCallUrgency(urgency));
    SignalServiceCallMessage callMessage   = SignalServiceCallMessage.forOpaque(opaqueMessage, null);

    SignalExecutors.BOUNDED_IO.execute(() -> {
      if (!CallPermissions.isCurrent(generation)) return;
      Recipient recipient = Recipient.resolved(RecipientIds.from(ACI.from(aciUuid)));
      if (recipient.isBlocked()) {
        return;
      }
      try {
        if (!CallPermissions.isCurrent(generation)) return;
        AppDependencies.getSignalServiceMessageSender()
                       .sendCallMessage(RecipientUtil.toSignalServiceAddress(context, recipient),
                                        recipient.isSelf() ? SealedSenderAccess.NONE : SealedSenderAccessUtil.getSealedSenderAccessFor(recipient),
                                        callMessage);
      } catch (UntrustedIdentityException e) {
        if (!CallPermissions.isCurrent(generation)) return;
        Log.i(TAG, "onSendCallMessage onFailure: ", e);
        RetrieveProfileJob.enqueue(recipient.getId(), true);
        processGroupCallback(generation, outgoingGroup, (s, p) -> p.handleGroupMessageSentError(s, Collections.singletonList(recipient.getId()), UNTRUSTED_IDENTITY));
      } catch (ProofRequiredException e) {
        if (!CallPermissions.isCurrent(generation)) return;
        Log.i(TAG, "onSendCallMessage onFailure: ", e);
        ProofRequiredExceptionHandler.handle(context, e, recipient, -1L, -1L);
        processGroupCallback(generation, outgoingGroup, (s, p) -> p.handleResendMediaKeys(s));
        processGroupCallback(generation, outgoingGroup, (s, p) -> p.handleGroupMessageSentError(s, Collections.singletonList(recipient.getId()), NETWORK_FAILURE));
      } catch (IOException e) {
        Log.i(TAG, "onSendCallMessage onFailure: ", e);
        processGroupCallback(generation, outgoingGroup, (s, p) -> p.handleGroupMessageSentError(s, Collections.singletonList(recipient.getId()), NETWORK_FAILURE));
      } catch (NoSessionException e) {
        if (!CallPermissions.isCurrent(generation)) return;
        Log.w(TAG, "onSendCallMessage onFailure: missing or corrupt session. Archiving sessions so the next send rebuilds.", e);
        archiveSessions(recipient.getId());
        processGroupCallback(generation, outgoingGroup, (s, p) -> p.handleGroupMessageSentError(s, Collections.singletonList(recipient.getId()), NETWORK_FAILURE));
      }
    });
  }

  @Override
  public void onSendCallMessageToGroup(@NonNull byte[] groupIdBytes, @NonNull byte[] message, @NonNull CallManager.CallMessageUrgency urgency, @NonNull List<UUID> overrideRecipients) {
    Log.i(TAG, "onSendCallMessageToGroup():");
    if (!CallPermissions.isCallingAllowed()) return;
    long generation = CallPermissions.generation();
    GroupCall outgoingGroup = serviceState.getCallInfoState().getGroupCall();

    SignalExecutors.BOUNDED_IO.execute(() -> {
      if (!CallPermissions.isCurrent(generation)) return;
      try {
        GroupId         groupId    = GroupId.v2(new GroupIdentifier(groupIdBytes));
        if (!CallPermissions.canUseGroup(Recipient.externalPossiblyMigratedGroup(groupId).getId())) return;
        List<Recipient> recipients = SignalDatabase.groups().getGroupMembers(groupId, GroupTable.MemberSet.FULL_MEMBERS_EXCLUDING_SELF);
        Set<UUID>       toInclude  = new HashSet<>(overrideRecipients.size());

        toInclude.addAll(overrideRecipients);

        recipients = RecipientUtil.getEligibleForSending((recipients.stream()
                                                                    .map(Recipient::resolve)
                                                                    .filter(r -> {
                                                                      if (toInclude.isEmpty()) {
                                                                        return true;
                                                                      }
                                                                      return r.getHasServiceId() && toInclude.contains(r.requireServiceId().getRawUuid());
                                                                    })
                                                                    .collect(Collectors.toList())));

        OpaqueMessage            opaqueMessage = new OpaqueMessage(message, getUrgencyFromCallUrgency(urgency));
        SignalServiceCallMessage callMessage   = SignalServiceCallMessage.forOutgoingGroupOpaque(groupId.getDecodedId(), System.currentTimeMillis(), opaqueMessage, null);
        RecipientAccessList      accessList    = new RecipientAccessList(recipients);

        if (!CallPermissions.isCurrent(generation)) return;
        List<SendMessageResult> results = GroupSendUtil.sendCallMessage(context,
                                                                        groupId.requireV2(),
                                                                        recipients,
                                                                        callMessage);

        Set<RecipientId> identifyFailureRecipientIds = results.stream()
                                                              .filter(result -> result.getIdentityFailure() != null)
                                                              .map(result -> accessList.requireIdByAddress(result.getAddress()))
                                                              .collect(Collectors.toSet());

        if (CallPermissions.isCurrent(generation) && Util.hasItems(identifyFailureRecipientIds)) {
          processGroupCallback(generation, outgoingGroup, (s, p) -> p.handleGroupMessageSentError(s, identifyFailureRecipientIds, UNTRUSTED_IDENTITY));

          RetrieveProfileJob.enqueue(identifyFailureRecipientIds, true);
        }
      } catch (UntrustedIdentityException | IOException | InvalidInputException | NoSessionException e) {
        Log.w(TAG, "onSendCallMessageToGroup failed", e);
      }
    });
  }

  @Override
  public void onSendCallMessageToAdhocGroup(@NonNull byte[] message, @NonNull CallManager.CallMessageUrgency urgency, Instant expiration, @NonNull Map<UUID, byte[]> recipientsToEndorsements) {
    Log.w(TAG, "onSendCallMessageToAdhocGroup(): not handled yet!");
  }

  @Override
  public void onSendHttpRequest(long requestId, @NonNull String url, @NonNull CallManager.HttpMethod httpMethod, @Nullable List<HttpHeader> headers, @Nullable byte[] body) {
    if (!CallPermissions.isCallingAllowed()) return;
    long generation = CallPermissions.generation();
    if (callManager == null) {
      Log.w(TAG, "Unable to send http request, call manager is not initialized");
      return;
    }

    Log.i(TAG, "onSendHttpRequest(): request_id: " + requestId);
    SignalExecutors.BOUNDED_IO.execute(() -> {
      if (!CallPermissions.isCurrent(generation)) return;
      List<Pair<String, String>> headerPairs;
      if (headers != null) {
        headerPairs = headers.stream()
                             .map(header -> new Pair<>(header.getName(), header.getValue())).collect(Collectors.toList());
      } else {
        headerPairs = Collections.emptyList();
      }

      NetworkResult<CallingResponse> result = SignalNetwork.callingApi()
                                                           .makeCallingRequest(requestId, url, httpMethod.name(), headerPairs, body);

      if (!CallPermissions.isCurrent(generation)) return;
      if (!(result instanceof NetworkResult.Success<?>)) {
        try {
          nativeIfCurrent(generation, () -> callManager.httpRequestFailed(requestId));
        } catch (CallException e) {
          Log.i(TAG, "Failed to process HTTP failure", e);
        }
        return;
      }
      CallingResponse response = ((NetworkResult.Success<CallingResponse>) result).getResult();
      try {
        if (response instanceof CallingResponse.Success) {
          CallingResponse.Success success = (CallingResponse.Success) response;
          nativeIfCurrent(generation, () -> callManager.receivedHttpResponse(requestId, success.getResponseStatus(), success.getResponseBody()));
        } else {
          nativeIfCurrent(generation, () -> callManager.httpRequestFailed(requestId));
        }
      } catch (CallException e) {
        Log.i(TAG, "Failed to process HTTP response/failure", e);
      }
    });
  }

  @Override
  public void onGroupCallRingUpdate(@NonNull byte[] groupIdBytes, long ringId, @NonNull UUID sender, @NonNull CallManager.RingUpdate ringUpdate) {
    try {
      ACI         senderAci       = ACI.from(sender);
      GroupId.V2  groupId         = GroupId.v2(new GroupIdentifier(groupIdBytes));
      GroupRecord group           = SignalDatabase.groups().getGroup(groupId).orElse(null);
      Recipient   senderRecipient = Recipient.externalPush(senderAci);

      if (group != null && ringUpdate == CallManager.RingUpdate.REQUESTED &&
          !CallPermissions.isIncomingGroupRingCurrent(group.getRecipientId(), ringId, senderRecipient.getId())) return;

      if (group != null &&
          group.isActive() &&
          CallPermissions.canUseGroup(group.getRecipientId()) &&
          !Recipient.resolved(group.getRecipientId()).isBlocked() &&
          (ringUpdate != CallManager.RingUpdate.REQUESTED || !GroupAdmission.isHeld(groupId)) &&
          group.memberLevel(senderRecipient).isInGroup() &&
          (!group.isAnnouncementGroup() || group.isAdmin(senderRecipient)))
      {
        process((s, p) -> {
          GroupRecord currentGroup = SignalDatabase.groups().getGroup(groupId).orElse(null);
          Recipient currentSender = Recipient.resolved(senderRecipient.getId());
          if (currentGroup == null || !currentGroup.isActive() || currentSender.isBlocked() ||
              !CallPermissions.canUseGroup(currentGroup.getRecipientId()) ||
              !currentGroup.memberLevel(currentSender).isInGroup() ||
              (currentGroup.isAnnouncementGroup() && !currentGroup.isAdmin(currentSender))) return s;
          if (ringUpdate == CallManager.RingUpdate.REQUESTED &&
              (GroupAdmission.isHeld(groupId) || !CallPermissions.isIncomingGroupRingCurrent(currentGroup.getRecipientId(), ringId, currentSender.getId()))) return s;
          return p.handleGroupCallRingUpdate(s, new RemotePeer(currentGroup.getRecipientId()), groupId, ringId, senderAci, ringUpdate);
        });
      } else {
        Log.w(TAG, "Unable to ring unknown/inactive/blocked group, or sender is not a current member of the group.");
      }
    } catch (InvalidInputException e) {
      Log.w(TAG, "Unable to ring group due to invalid group id", e);
    }
  }

  @Override
  public void requestMembershipProof(@NonNull final GroupCall groupCall) {
    Log.i(TAG, "requestMembershipProof():");
    processGroupCallback(groupCall, (s, p) -> p.handleGroupRequestMembershipProof(s, groupCall.hashCode()));
  }

  @Override
  public void requestGroupMembers(@NonNull GroupCall groupCall) {
    processGroupCallback(groupCall, (s, p) -> p.handleGroupRequestUpdateMembers(s));
  }

  @Override
  public void onLocalDeviceStateChanged(@NonNull GroupCall groupCall) {
    Log.i(TAG, "onLocalDeviceStateChanged: localAdapterType: " + groupCall.getLocalDeviceState().getNetworkRoute().getLocalAdapterType());
    processGroupCallback(groupCall, (s, p) -> p.handleGroupLocalDeviceStateChanged(s));
  }

  @Override
  public void onAudioLevels(@NonNull GroupCall groupCall) {
    processStateless(s -> serviceState.getCallInfoState().getGroupCall() == groupCall && CallPermissions.isCurrent(groupCallGeneration) ? serviceState.getActionProcessor().handleGroupAudioLevelsChanged(serviceState, s) : s);
  }

  @Override
  public void onLowBandwidthForVideo(@NonNull GroupCall groupCall, boolean recovered) {
    // TODO: Implement handling of the "low outgoing bandwidth for video" notification.
  }

  @Override
  public void onReactions(@NonNull GroupCall groupCall, List<Reaction> reactions) {
    processStateless(s -> serviceState.getCallInfoState().getGroupCall() == groupCall && CallPermissions.isCurrent(groupCallGeneration) ? serviceState.getActionProcessor().handleGroupCallReaction(serviceState, s, reactions) : s);
  }

  @Override
  public void onRaisedHands(@NonNull GroupCall groupCall, List<Long> raisedHands) {
    processGroupCallback(groupCall, (s, p) -> p.handleGroupCallRaisedHand(s, raisedHands));
  }

  @Override
  public void onRemoteDeviceStatesChanged(@NonNull GroupCall groupCall) {
    processGroupRosterCallback(groupCall, (s, p) -> p.handleGroupRemoteDeviceStateChanged(s));
  }

  @Override
  public void onPeekChanged(@NonNull GroupCall groupCall) {
    processGroupRosterCallback(groupCall, (s, p) -> p.handleGroupJoinedMembershipChanged(s));
  }

  @Override
  public void onEnded(@NonNull GroupCall groupCall, @NonNull CallManager.CallEndReason reason, @NonNull CallSummary summary) {
    processGroupCallback(groupCall, (s, p) -> p.handleGroupCallEnded(s, groupCall.hashCode(), reason));
  }

  @Override
  public void onRemoteMuteRequest(@NonNull GroupCall groupCall, long sourceDemuxId) {
    processGroupCallback(groupCall, (s, p) -> p.handleRemoteMuteRequest(s, sourceDemuxId));
  }

  @Override
  public void onObservedRemoteMute(@NonNull GroupCall groupCall, long sourceDemuxId, long targetDemuxId) {
    processGroupCallback(groupCall, (s, p) -> p.handleObservedRemoteMute(s, sourceDemuxId, targetDemuxId));
  }

  @Override
  public void onSpeakingNotification(@NonNull GroupCall groupCall, @NonNull GroupCall.SpeechEvent speechEvent) {
    processGroupCallback(groupCall, (s, p) -> p.handleGroupCallSpeechEvent(s, speechEvent));
  }

  @Override
  public void onFullyInitialized() {
    process((s, p) -> p.handleOrientationChanged(s, s.getLocalDeviceState().isLandscapeEnabled(), s.getLocalDeviceState().getDeviceOrientation().getDegrees()));
  }

  @Override
  public void onCameraSwitchCompleted(@NonNull final CameraState newCameraState) {
    process((s, p) -> p.handleCameraSwitchCompleted(s, newCameraState));
  }

  @Override
  public void onCameraStopped() {
    Log.i(TAG, "Camera error. Muting video.");
    setEnableVideo(false);
  }

  @Override
  public void onScreenShareStopped() {
    stopScreenShare();
  }

  @Override
  public void onForeground() {
    process((s, p) -> {
      WebRtcViewModel.State          callState      = s.getCallInfoState().getCallState();
      WebRtcViewModel.GroupCallState groupCallState = s.getCallInfoState().getGroupCallState();

      if (callState == CALL_INCOMING && (groupCallState == IDLE || groupCallState.isRinging())) {
        Log.i(TAG, "Starting call activity from foreground listener");
        startCallCardActivityIfPossible();
      }
      AppForegroundObserver.removeListener(this);
      return s;
    });
  }

  public void insertMissedCall(@NonNull RemotePeer remotePeer, long timestamp, boolean isVideoOffer, @NonNull CallTable.Event missedEvent) {
    CallTable.OneToOneCallUpdate update = SignalDatabase.calls()
                                                        .updateOneToOneCall(remotePeer.getCallId().longValue(), missedEvent);

    if (update instanceof CallTable.OneToOneCallUpdate.NotFound) {
      CallTable.Type type = isVideoOffer ? CallTable.Type.VIDEO_CALL : CallTable.Type.AUDIO_CALL;

      SignalDatabase.calls()
                    .insertOneToOneCall(remotePeer.getCallId().longValue(), timestamp, remotePeer.getId(), type, CallTable.Direction.INCOMING, missedEvent, false);
    }
  }

  public void insertReceivedCall(@NonNull RemotePeer remotePeer, boolean isVideoOffer) {
    CallTable.OneToOneCallUpdate update = SignalDatabase.calls()
                                                        .updateOneToOneCall(remotePeer.getCallId().longValue(), CallTable.Event.ACCEPTED);

    if (update instanceof CallTable.OneToOneCallUpdate.NotFound) {
      CallTable.Type type = isVideoOffer ? CallTable.Type.VIDEO_CALL : CallTable.Type.AUDIO_CALL;

      SignalDatabase.calls()
                    .insertOneToOneCall(remotePeer.getCallId().longValue(), System.currentTimeMillis(), remotePeer.getId(), type, CallTable.Direction.INCOMING, CallTable.Event.ACCEPTED, false);
    }
  }

  public void retrieveTurnServers(@NonNull RemotePeer remotePeer) {
    long generation = CallPermissions.generation();
    SignalExecutors.BOUNDED_IO.execute(() -> {
      if (!CallPermissions.isCurrent(generation) || !CallPermissions.canContinueCall(remotePeer.getId())) return;
      try {
        List<PeerConnection.IceServer> cachedServers = TurnServerCache.getCachedServers();
        if (cachedServers != null) {
          processTurnServers(generation, remotePeer, cachedServers);
          return;
        }

        List<TurnServerInfo> turnServerInfos = NetworkResultUtil.toBasicLegacy(SignalNetwork.callingApi().getTurnServerInfo());
        if (!CallPermissions.isCurrent(generation) || !CallPermissions.canContinueCall(remotePeer.getId())) return;

        // Find *any* provided ttl values as long as they are valid.
        long minTtl = turnServerInfos.stream()
                                     .map(TurnServerInfo::getTtl)
                                     .filter(ttl -> ttl != null && ttl > 0)
                                     .min(Long::compare)
                                     .orElse(0L);

        List<PeerConnection.IceServer> iceServers = mapToIceServers(turnServerInfos);

        TurnServerCache.updateCache(iceServers, minTtl);

        processTurnServers(generation, remotePeer, iceServers);
      } catch (IOException e) {
        Log.w(TAG, "Unable to retrieve turn servers: ", e);
        process(generation, (s, p) -> s.getCallInfoState().getActivePeer() == remotePeer ? p.handleSetupFailure(s, remotePeer.getCallId()) : s);
      }
    });
  }

  private void processTurnServers(long generation, RemotePeer remotePeer, List<PeerConnection.IceServer> servers) {
    process(generation, (s, p) -> {
      RemotePeer activePeer = s.getCallInfoState().getActivePeer();
      if (activePeer == remotePeer) {
        return p.handleTurnServerUpdate(s, servers, SignalStore.settings().isTurnOnly());
      }

      Log.w(TAG, "Ignoring received turn servers for incorrect call id. requesting_call_id: " + remotePeer.getCallId() + " current_call_id: " + (activePeer != null ? activePeer.getCallId() : "null"));
      return s;
    });
  }

  private static List<PeerConnection.IceServer> mapToIceServers(@NonNull List<TurnServerInfo> turnServerInfos) {
    List<PeerConnection.IceServer> iceServers = new ArrayList<>();

    for (TurnServerInfo turnServerInfo: turnServerInfos) {
      if (turnServerInfo.getUrlsWithIps() != null) {
        iceServers.addAll(
            turnServerInfo.getUrlsWithIps()
                          .stream()
                          .map(url ->
                                   PeerConnection.IceServer.builder(url)
                                                           .setUsername(turnServerInfo.getUsername())
                                                           .setPassword(turnServerInfo.getPassword())
                                                           .setHostname(turnServerInfo.getHostname())
                                                           .createIceServer()
                          ).collect(Collectors.toList()));
      }

      if (turnServerInfo.getUrls() != null) {
        iceServers.addAll(
            turnServerInfo.getUrls()
                          .stream()
                          .map(url ->
                                   PeerConnection.IceServer.builder(url)
                                                           .setUsername(turnServerInfo.getUsername())
                                                           .setPassword(turnServerInfo.getPassword())
                                                           .createIceServer()
                          ).collect(Collectors.toList()));
      }
    }

    return iceServers;
  }

  public void sendGroupCallUpdateMessage(@NonNull Recipient recipient, @Nullable String groupCallEraId, final @Nullable CallId callId, boolean isIncoming, boolean isJoinEvent) {
    Log.i(TAG, "sendGroupCallUpdateMessage id: " + recipient.getId() + " era: " + groupCallEraId + " isIncoming: " + isIncoming + " isJoinEvent: " + isJoinEvent);
    if (recipient.isCallLink() ? !CallPermissions.canContinueCall(recipient.getId()) : !CallPermissions.canUseGroup(recipient.getId())) return;
    long generation = CallPermissions.generation();
    String cleanupPermit = !recipient.isCallLink() ? CallPermissions.createGroupCleanupPermit(recipient.getId()) : null;

    if (recipient.isCallLink()) {
      if (isJoinEvent) {
        SignalExecutors.BOUNDED.execute(() -> {
          if (!CallPermissions.isCurrent(generation) || !CallPermissions.canContinueCall(recipient.getId())) return;
          CallId callIdLocal = callId;

          if (callIdLocal == null && groupCallEraId != null) {
            if (!CallPermissions.isCurrent(generation)) return;
            callIdLocal = new CallId(CallEraId.fromEra(groupCallEraId));
          }

          if (callIdLocal != null) {
            AppDependencies.getJobManager().add(
                CallSyncEventJob.createForJoin(
                    recipient.getId(),
                    callIdLocal.longValue(),
                    isIncoming
                )
            );
          }
        });
      } else {
        Log.i(TAG, "sendGroupCallUpdateMessage -- ignoring non-join event for call link");
      }
      return;
    }

    SignalExecutors.BOUNDED.execute(() -> {
      // Closing updates may finish after normal session teardown, but never
      // after a policy/account change or loss of access to this group.
      if (GroupAdmission.isHeld(recipient) || !CallPermissions.isCurrent(generation) || !CallPermissions.canStartCall(recipient)) return;
      GroupCallUpdateSendJob updateSendJob = GroupCallUpdateSendJob.create(recipient.getId(), groupCallEraId, cleanupPermit);
      JobManager.Chain       chain         = AppDependencies.getJobManager().startChain(updateSendJob);
      CallId                 callIdLocal   = callId;

      if (callIdLocal == null && groupCallEraId != null) {
        if (!CallPermissions.isCurrent(generation)) return;
        callIdLocal = new CallId(CallEraId.fromEra(groupCallEraId));
      }

      if (callIdLocal != null) {
        if (isJoinEvent) {
          chain.then(CallSyncEventJob.createForJoin(
              recipient.getId(),
              callIdLocal.longValue(),
              isIncoming
          ));
        } else if (isIncoming) {
          chain.then(CallSyncEventJob.createForNotAccepted(
              recipient.getId(),
              callIdLocal.longValue(),
              isIncoming
          ));
        }
      } else {
        Log.w(TAG, "Can't send sync message without a call id. isIncoming: " + isIncoming + " isJoinEvent: " + isJoinEvent);
      }

      chain.enqueue();
    });
  }

  public void updateGroupCallUpdateMessage(@NonNull RecipientId groupId, @Nullable String groupCallEraId, @NonNull Collection<UUID> joinedMembers, boolean isCallFull) {
    long generation = CallPermissions.generation();
    SignalExecutors.BOUNDED.execute(() -> {
      if (!CallPermissions.isCurrent(generation) || !CallPermissions.canUseGroup(groupId)) return;
      SignalDatabase.calls().insertOrUpdateGroupCallFromLocalEvent(groupId,
                                                                                                       Recipient.self().getId(),
                                                                                                       System.currentTimeMillis(),
                                                                                                       groupCallEraId,
                                                                                                       joinedMembers,
                                                                                                       isCallFull);
    });
  }

  public void resendMediaKeys() {
    process((s, p) -> p.handleResendMediaKeys(s));
  }

  public void sendCallMessage(@NonNull final RemotePeer remotePeer,
                              @NonNull final SignalServiceCallMessage callMessage)
  {
    if (!CallPermissions.isCallingAllowed()) return;
    long generation = CallPermissions.generation();
    boolean closingMessage = callMessage.getHangupMessage().isPresent() || callMessage.getBusyMessage().isPresent();
    boolean permissionRequest = !remotePeer.getRecipient().getHasAci() &&
                                callMessage.getOfferMessage().map(offer -> offer.getId() == remotePeer.getCallId().longValue() && offer.getOpaque().length == 0).orElse(false);
    long enqueuedAt = SystemClock.elapsedRealtime();
    SignalExecutors.BOUNDED_IO.execute(() -> {
      if (!CallPermissions.isCurrent(generation) || SystemClock.elapsedRealtime() - enqueuedAt > 120_000L) return;
      // Final hangup/busy messages may finish after normal teardown. Old offers,
      // answers and ICE must not start or continue a previous call after a new one.
      // The existing empty PNI permission request also deliberately ends before
      // its one transport message runs; it never grants native continuation.
      if (!closingMessage && !permissionRequest && serviceState.getCallInfoState().getActivePeer() != remotePeer) return;
      Recipient recipient = Recipient.resolved(remotePeer.getId());
      if (recipient.isBlocked()) {
        return;
      }

      try {
        if (!CallPermissions.isCurrent(generation)) return;
        AppDependencies.getSignalServiceMessageSender()
                       .sendCallMessage(RecipientUtil.toSignalServiceAddress(context, recipient),
                                        SealedSenderAccessUtil.getSealedSenderAccessFor(recipient),
                                        callMessage);
        process(generation, (s, p) -> s.getCallInfoState().getPeer(remotePeer.hashCode()) == remotePeer ? p.handleMessageSentSuccess(s, remotePeer.getCallId()) : s);
      } catch (UntrustedIdentityException e) {
        if (!CallPermissions.isCurrent(generation)) return;
        RetrieveProfileJob.enqueue(remotePeer.getId(), true);
        processSendMessageFailureWithChangeDetection(generation, remotePeer,
                                                     (s, p) -> p.handleMessageSentError(s,
                                                                                        remotePeer.getCallId(),
                                                                                        UNTRUSTED_IDENTITY,
                                                                                        Optional.ofNullable(e.getIdentityKey())));
      } catch (IOException e) {
        if (!CallPermissions.isCurrent(generation)) return;
        if (e instanceof ProofRequiredException) {
          ProofRequiredExceptionHandler.handle(context, (ProofRequiredException) e, null, -1L, -1L);
          process(generation, (s, p) -> s.getCallInfoState().getActivePeer() == remotePeer ? p.handleResendMediaKeys(s) : s);
        }

        processSendMessageFailureWithChangeDetection(generation, remotePeer,
                                                     (s, p) -> p.handleMessageSentError(s,
                                                                                        remotePeer.getCallId(),
                                                                                        e instanceof UnregisteredUserException ? NO_SUCH_USER : NETWORK_FAILURE,
                                                                                        Optional.empty()));
      } catch (NoSessionException e) {
        if (!CallPermissions.isCurrent(generation)) return;
        Log.w(TAG, "sendCallMessage: missing or corrupt session. Archiving sessions so the next send rebuilds.", e);
        archiveSessions(recipient.getId());
        processSendMessageFailureWithChangeDetection(generation, remotePeer,
                                                     (s, p) -> p.handleMessageSentError(s,
                                                                                        remotePeer.getCallId(),
                                                                                        NETWORK_FAILURE,
                                                                                        Optional.empty()));
      }
    });
  }

  private void archiveSessions(@NonNull RecipientId recipientId) {
    AppDependencies.getProtocolStore().aci().sessions().archiveSessions(recipientId);

    SignalServiceAccountDataStoreImpl pniStore = AppDependencies.getProtocolStore().pniOrNull();
    if (pniStore != null) {
      pniStore.sessions().archiveSessions(recipientId);
    }
  }

  public void sendAcceptedCallEventSyncMessage(@NonNull RemotePeer remotePeer, boolean isOutgoing, boolean isVideoCall) {
    long                         callId = remotePeer.getCallId().longValue();
    CallTable.OneToOneCallUpdate update = SignalDatabase.calls().updateOneToOneCall(callId, CallTable.Event.ACCEPTED);

    if (update instanceof CallTable.OneToOneCallUpdate.Rejected rejected) {
      Log.i(TAG, "Call " + callId + " is " + rejected.getCall().getEvent() + ", not sending accepted sync message");
      return;
    }

    if (SignalStore.account().isMultiDevice()) {
      SignalExecutors.BOUNDED_IO.execute(() -> {
        try {
          SyncMessage.CallEvent callEvent = CallEventSyncMessageUtil.createAcceptedSyncMessage(remotePeer, System.currentTimeMillis(), isOutgoing, isVideoCall);
          AppDependencies.getSignalServiceMessageSender().sendSyncMessage(SignalServiceSyncMessage.forCallEvent(callEvent));
        } catch (IOException | UntrustedIdentityException | NoSessionException e) {
          Log.w(TAG, "Unable to send call event sync message for " + remotePeer.getCallId().longValue(), e);
        }
      });
    }
  }

  public void sendNotAcceptedCallEventSyncMessage(@NonNull RemotePeer remotePeer, boolean isOutgoing, boolean isVideoCall) {
    long                         callId = remotePeer.getCallId().longValue();
    CallTable.OneToOneCallUpdate update = SignalDatabase.calls().updateOneToOneCall(callId, CallTable.Event.NOT_ACCEPTED);

    if (update instanceof CallTable.OneToOneCallUpdate.Rejected rejected) {
      Log.i(TAG, "Call " + callId + " is " + rejected.getCall().getEvent() + ", not sending not accepted sync message");
      return;
    }

    if (SignalStore.account().isMultiDevice()) {
      SignalExecutors.BOUNDED_IO.execute(() -> {
        try {
          SyncMessage.CallEvent callEvent = CallEventSyncMessageUtil.createNotAcceptedSyncMessage(remotePeer, System.currentTimeMillis(), isOutgoing, isVideoCall);
          AppDependencies.getSignalServiceMessageSender().sendSyncMessage(SignalServiceSyncMessage.forCallEvent(callEvent));
        } catch (IOException | UntrustedIdentityException | NoSessionException e) {
          Log.w(TAG, "Unable to send call event sync message for " + remotePeer.getCallId().longValue(), e);
        }
      });
    }
  }

  public void sendGroupCallNotAcceptedCallEventSyncMessage(@NonNull RemotePeer remotePeer, boolean isOutgoing) {
    if (SignalStore.account().isMultiDevice()) {
      SignalExecutors.BOUNDED_IO.execute(() -> {
        try {
          SyncMessage.CallEvent callEvent = CallEventSyncMessageUtil.createNotAcceptedSyncMessage(remotePeer, System.currentTimeMillis(), isOutgoing, true);
          AppDependencies.getSignalServiceMessageSender().sendSyncMessage(SignalServiceSyncMessage.forCallEvent(callEvent));
        } catch (IOException | UntrustedIdentityException | NoSessionException e) {
          Log.w(TAG, "Unable to send call event sync message for " + remotePeer.getCallId().longValue(), e);
        }
      });
    }
  }

  public @NonNull SignalCallLinkManager getCallLinkManager() {
    return new SignalCallLinkManager(Objects.requireNonNull(callManager));
  }

  public void addAsset(String assetGroup, byte[] content) throws CallException {
    if (callManager == null) {
      throw new CallException("Unable to add asset, call manager is not initialized");
    }
    callManager.addAsset(assetGroup, content);
  }

  /**
   * Schedules the call activity to be relaunched in PiP the next time the app is foregrounded. Only relevant if the call activity did not survive being
   * backgrounded, e.g. the user dismissed the PiP window or another app's PiP evicted it. If it did survive, {@link #cancelPipRelaunch()} should be called
   * to cancel this.
   */
  @MainThread
  public void relaunchPipOnForeground() {
    cancelPipRelaunch();

    pipRelaunchListener = new RelaunchListener(AppForegroundObserver.isForegrounded());
    AppForegroundObserver.addListener(pipRelaunchListener);
  }

  /**
   * Cancels any pending PiP relaunch scheduled via {@link #relaunchPipOnForeground()}. Relaunching while the call activity is still alive delivers a
   * launch-in-PiP intent to it, which pulls it out of PiP and into fullscreen, or forces a deliberately expanded call back into PiP. The former is both
   * unprompted and, if it happens while the keyguard is going away, can leave the device wedged in a partially-locked state.
   */
  @MainThread
  public void cancelPipRelaunch() {
    if (pipRelaunchListener != null) {
      AppForegroundObserver.removeListener(pipRelaunchListener);
      pipRelaunchListener = null;
    }
  }

  private void processSendMessageFailureWithChangeDetection(long generation, @NonNull RemotePeer remotePeer,
                                                            @NonNull ProcessAction failureProcessAction)
  {
    process(generation, (s, p) -> {
      if (s.getCallInfoState().getPeer(remotePeer.hashCode()) != remotePeer) return s;
      RemotePeer activePeer = s.getCallInfoState().getActivePeer();

      boolean stateChanged = activePeer == null ||
                             remotePeer.getState() != activePeer.getState() ||
                             !remotePeer.getCallId().equals(activePeer.getCallId());

      if (stateChanged) {
        return p.handleMessageSentSuccess(s, remotePeer.getCallId());
      } else {
        return failureProcessAction.process(s, p);
      }
    });
  }

  private class RelaunchListener implements AppForegroundObserver.Listener {
    private boolean canRelaunch;

    public RelaunchListener(boolean isForegrounded) {
      canRelaunch = !isForegrounded;
    }

    @Override
    public void onForeground() {
      if (canRelaunch) {
        if (isSystemPipEnabledAndAvailable()) {
          process((s, p) -> {
            WebRtcViewModel.State callState = s.getCallInfoState().getCallState();

            if (callState.getInOngoingCall()) {
              context.startActivity(
                  new CallIntent.Builder(context)
                      .withIntentFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                      .withLaunchInPip(true)
                      .build()
              );
            }

            return s;
          });
        }
        AppForegroundObserver.removeListener(this);
        if (pipRelaunchListener == this) {
          pipRelaunchListener = null;
        }
      }
    }

    @Override
    public void onBackground() {
      canRelaunch = true;
    }

    private boolean isSystemPipEnabledAndAvailable() {
      return Build.VERSION.SDK_INT >= 26 && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }
  }

  interface ProcessAction {
    @NonNull WebRtcServiceState process(@NonNull WebRtcServiceState currentState, @NonNull WebRtcActionProcessor processor);
  }
}

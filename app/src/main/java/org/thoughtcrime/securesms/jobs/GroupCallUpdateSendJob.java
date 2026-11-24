package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.signal.core.models.recipients.RecipientId;
import org.thoughtcrime.securesms.calls.permissions.CallPermissions;
import org.signal.core.util.logging.Log;
import org.signal.libsignal.protocol.NoSessionException;
import org.thoughtcrime.securesms.database.GroupTable;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.model.RecipientRecord;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.v2.admission.GroupAdmission;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JsonJobData;
import org.thoughtcrime.securesms.jobmanager.impl.SealedSenderConstraint;
import org.thoughtcrime.securesms.messages.GroupSendUtil;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.transport.RetryLaterException;
import org.thoughtcrime.securesms.transport.UndeliverableMessageException;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.whispersystems.signalservice.api.crypto.ContentHint;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.push.exceptions.ServerRejectedException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Send a group call update message to every one in a V2 group. Used to indicate you
 * have joined or left a call.
 */
public class GroupCallUpdateSendJob extends BaseJob {

  public static final String KEY = "GroupCallUpdateSendJob";

  private static final String TAG = Log.tag(GroupCallUpdateSendJob.class);

  private static final String KEY_RECIPIENT_ID            = "recipient_id";
  private static final String KEY_ERA_ID                  = "era_id";
  private static final String KEY_RECIPIENTS              = "recipients";
  private static final String KEY_INITIAL_RECIPIENT_COUNT = "initial_recipient_count";
  private static final String KEY_CLEANUP_PERMIT          = "call_cleanup_permit";
  static final String KEY_SYNC_TIMESTAMP          = "sync_timestamp";

  private final RecipientId       recipientId;
  private final String            eraId;
  private final List<RecipientId> recipients;
  private final int               initialRecipientCount;
  private final @Nullable String cleanupPermit;

  private long syncTimestamp;

  @WorkerThread
  public static @NonNull GroupCallUpdateSendJob create(@NonNull RecipientId recipientId, @Nullable String eraId) {
    return create(recipientId, eraId, CallPermissions.createGroupCleanupPermit(recipientId));
  }

  @WorkerThread
  public static @NonNull GroupCallUpdateSendJob create(@NonNull RecipientId recipientId, @Nullable String eraId, @Nullable String cleanupPermit) {
    RecipientRecord conversationRecipient = SignalDatabase.recipients().getRecord(recipientId);

    if (conversationRecipient.getGroupId() == null || !conversationRecipient.getGroupId().isV2()) {
      throw new AssertionError("We have a recipient, but it's not a V2 Group");
    }

    List<RecipientId> recipientIds = RecipientUtil.getEligibleForSending(Recipient.resolvedList(SignalDatabase.groups().getGroupMemberIds(conversationRecipient.getGroupId(), GroupTable.MemberSet.FULL_MEMBERS_EXCLUDING_SELF))).stream()
                                                  .map(Recipient::getId).collect(Collectors.toList());

    return new GroupCallUpdateSendJob(recipientId,
                                      eraId,
                                      recipientIds,
                                      recipientIds.size(),
                                      0L,
                                      cleanupPermit,
                                      new Parameters.Builder()
                                                    .setQueue(conversationRecipient.getId().toQueueKey())
                                                    .addConstraint(SealedSenderConstraint.KEY)
                                                    .setLifespan(TimeUnit.MINUTES.toMillis(5))
                                                    .setMaxAttempts(3)
                                                    .build());
  }

  private GroupCallUpdateSendJob(@NonNull RecipientId recipientId,
                                 @Nullable String eraId,
                                 @NonNull List<RecipientId> recipients,
                                 int initialRecipientCount,
                                 long syncTimestamp,
                                 @Nullable String cleanupPermit,
                                 @NonNull Parameters parameters)
  {
    super(parameters);

    this.recipientId           = recipientId;
    this.eraId                 = eraId;
    this.recipients            = recipients;
    this.initialRecipientCount = initialRecipientCount;
    this.syncTimestamp         = syncTimestamp;
    this.cleanupPermit         = cleanupPermit;
  }

  @Override
  public @Nullable byte[] serialize() {
    return new JsonJobData.Builder().putString(KEY_RECIPIENT_ID, recipientId.serialize())
                                    .putString(KEY_ERA_ID, eraId)
                                    .putString(KEY_RECIPIENTS, RecipientId.toSerializedList(recipients))
                                    .putInt(KEY_INITIAL_RECIPIENT_COUNT, initialRecipientCount)
                                    .putLong(KEY_SYNC_TIMESTAMP, syncTimestamp)
                                    .putString(KEY_CLEANUP_PERMIT, cleanupPermit)
                                    .serialize();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  protected void onRun() throws Exception {
    long generation = CallPermissions.generation();
    if (!isAllowed()) {
      return;
    }
    if (!Recipient.self().isRegistered()) {
      throw new NotPushRegisteredException();
    }

    if (!SignalDatabase.recipients().containsId(recipientId)) {
      Log.w(TAG, "Missing recipient record for id.");
      return;
    }

    RecipientRecord conversationRecipient = SignalDatabase.recipients().getRecord(recipientId);

    if (conversationRecipient.getGroupId() == null || !conversationRecipient.getGroupId().isV2()) {
      throw new AssertionError("We have a recipient, but it's not a V2 Group");
    }

    if (!SignalDatabase.groups().isActive(conversationRecipient.getGroupId())) {
      Log.w(TAG, "Not sending group call update to terminated or inactive group.");
      return;
    }

    List<Recipient> destinations = recipients.stream().map(Recipient::resolved).collect(Collectors.toList());
    if (!CallPermissions.isCurrent(generation) || !isAllowed()) {
      return;
    }
    List<Recipient> completions  = deliver(conversationRecipient.getGroupId(), destinations);

    for (Recipient completion : completions) {
      recipients.remove(completion.getId());
    }

    Log.i(TAG, "Completed now: " + completions.size() + ", Remaining: " + recipients.size());

    if (!recipients.isEmpty()) {
      Log.w(TAG, "Still need to send to " + recipients.size() + " recipients. Retrying.");
      throw new RetryLaterException();
    }

    setOutputData(new JsonJobData.Builder()
                      .putLong(KEY_SYNC_TIMESTAMP, syncTimestamp)
                      .serialize());
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception e) {
    if (e instanceof ServerRejectedException) return false;
    return e instanceof IOException ||
           e instanceof RetryLaterException;
  }

  @Override
  public long getNextRunAttemptBackoff(int pastAttemptCount, @NonNull Exception exception) {
    return SendJobUtil.getBackoffMillisFromException(this, TAG, pastAttemptCount, exception, () -> super.getNextRunAttemptBackoff(pastAttemptCount, exception));
  }

  @Override
  public void onFailure() {
    if (recipients.size() < initialRecipientCount) {
      Log.w(TAG, "Only sent a group update to " + recipients.size() + "/" + initialRecipientCount + " recipients. Still, it sent to someone, so it stays.");
      return;
    }

    Log.w(TAG, "Failed to send the group update to all recipients!");
  }

  private @NonNull List<Recipient> deliver(@NonNull GroupId groupId, @NonNull List<Recipient> destinations)
      throws IOException, UntrustedIdentityException, NoSessionException, UndeliverableMessageException
  {
    SignalServiceDataMessage.Builder dataMessageBuilder = SignalServiceDataMessage.newBuilder()
                                                                                  .withTimestamp(System.currentTimeMillis())
                                                                                  .withGroupCallUpdate(new SignalServiceDataMessage.GroupCallUpdate(eraId));

    GroupUtil.setDataMessageGroupContext(context, dataMessageBuilder, groupId.requirePush());

    if (!isAllowed()) {
      return destinations;
    }

    SignalServiceDataMessage dataMessage         = dataMessageBuilder.build();
    List<Recipient>          nonSelfDestinations = destinations.stream().filter(r -> !r.isSelf()).collect(Collectors.toList());
    boolean                  includesSelf        = nonSelfDestinations.size() != destinations.size();
    List<SendMessageResult>  results             = GroupSendUtil.sendUnresendableDataMessage(context,
                                                                                             groupId.requireV2(),
                                                                                             nonSelfDestinations,
                                                                                             false,
                                                                                             ContentHint.DEFAULT,
                                                                                             dataMessage,
                                                                                             false,
                                                                                             null);

    if (includesSelf && isAllowed()) {
      results.add(AppDependencies.getSignalServiceMessageSender().sendSyncMessage(dataMessage));
      syncTimestamp = dataMessage.getTimestamp();
    }

    return GroupSendJobHelper.getCompletedSends(destinations, results).completed;
  }

  private boolean isAllowed() {
    if (GroupAdmission.isHeld(recipientId)) {
      return false;
    }

    // A normal local hangup must still notify this exact explicitly joined group.
    // The cleanup permit never admits incoming data and is invalid after revocation/restart.
    return cleanupPermit != null ? CallPermissions.isGroupCleanupPermitValid(cleanupPermit, recipientId) : CallPermissions.canReceiveGroup(recipientId);
  }

  public static class Factory implements Job.Factory<GroupCallUpdateSendJob> {

    @Override
    public @NonNull
    GroupCallUpdateSendJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      JsonJobData data = JsonJobData.deserialize(serializedData);

      RecipientId       recipientId           = RecipientId.from(data.getString(KEY_RECIPIENT_ID));
      String            eraId                 = data.getString(KEY_ERA_ID);
      List<RecipientId> recipients            = RecipientId.fromSerializedList(data.getString(KEY_RECIPIENTS));
      int               initialRecipientCount = data.getInt(KEY_INITIAL_RECIPIENT_COUNT);
      long              syncTimestamp         = data.getLongOrDefault(KEY_SYNC_TIMESTAMP, 0L);
      String            cleanupPermit         = data.getStringOrDefault(KEY_CLEANUP_PERMIT, null);

      return new GroupCallUpdateSendJob(recipientId, eraId, recipients, initialRecipientCount, syncTimestamp, cleanupPermit, parameters);
    }
  }
}

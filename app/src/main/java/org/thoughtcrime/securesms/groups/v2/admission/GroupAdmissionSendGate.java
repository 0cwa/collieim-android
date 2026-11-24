package org.thoughtcrime.securesms.groups.v2.admission;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.model.MessageId;
import org.thoughtcrime.securesms.database.model.StoryType;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.mms.MessageGroupContext;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingMessage;
import org.thoughtcrime.securesms.sms.GroupV2UpdateMessageUtil;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceGroupV2;

import java.io.IOException;
import java.util.Arrays;

/** Admission is checked again at the side effect, independently of imported trust flags. */
public final class GroupAdmissionSendGate {

  private GroupAdmissionSendGate() {}

  public static boolean canSend(@NonNull OutgoingMessage message) {
    return !GroupAdmission.isHeld(message.getThreadRecipient()) || isOnlySelfLeave(message);
  }

  private static boolean isOnlySelfLeave(@NonNull OutgoingMessage message) {
    MessageGroupContext context = message.getMessageGroupContext();
    return message.isGroupUpdate() && message.isV2Group() && context != null &&
           message.getBody().isEmpty() && message.getMentions().isEmpty() && message.getBodyRanges() == null &&
           message.getAttachments().isEmpty() && message.getLinkPreviews().isEmpty() &&
           message.getSharedContacts().isEmpty() && message.getOutgoingQuote() == null &&
           message.getMessageToEdit() <= 0 && message.getStoryType() == StoryType.NONE &&
           message.getParentStoryId() == null && !message.isStoryReaction() && message.getGiftBadge() == null &&
           !message.isViewOnce() && !message.isExpirationUpdate() && !message.isPaymentsNotification() &&
           !message.isRequestToActivatePayments() && !message.isPaymentsActivated() && message.getPoll() == null &&
           GroupV2UpdateMessageUtil.isJustAGroupLeave(context) &&
           GroupV2UpdateMessageUtil.isSelfLeave(context, SignalStore.account().requireAci());
  }

  public static void requireAllowed(@Nullable GroupId.V2 groupId,
                                    @Nullable MessageId relatedMessageId,
                                    @Nullable SignalServiceDataMessage wireMessage)
      throws HeldGroupException
  {
    if (groupId == null || !GroupAdmission.isHeld(groupId)) {
      return;
    }

    // A related message ID alone is not authority: a reaction or edit may name a leave.
    // Only the matching, content-free, locally recorded self-leave may pass this gate.
    if (relatedMessageId != null && wireMessage != null && wireMessage.isGroupV2Update() &&
        !wireMessage.isExpirationUpdate() && !wireMessage.isProfileKeyUpdate() &&
        !wireMessage.getProfileKey().isPresent() && !wireMessage.getGroupCallUpdate().isPresent() &&
        !wireMessage.getPayment().isPresent() && !wireMessage.getStoryContext().isPresent() &&
        !wireMessage.getGiftBadge().isPresent() && !wireMessage.getAdminDelete().isPresent() &&
        !wireMessage.getBodyRanges().isPresent() && !wireMessage.isViewOnce())
    {
      try {
        OutgoingMessage stored = SignalDatabase.messages().getOutgoingMessage(relatedMessageId.getId());
        if (isOnlySelfLeave(stored) && groupId.equals(stored.getThreadRecipient().requireGroupId())) {
          SignalServiceGroupV2 sent = wireMessage.getGroupContext().get();
          MessageGroupContext.GroupV2Properties expected = stored.requireGroupV2Properties();
          if (GroupId.v2(sent.getMasterKey()).equals(groupId) &&
              expected.getGroupContext().revision != null &&
              sent.getRevision() == expected.getGroupContext().revision &&
              wireMessage.getTimestamp() == stored.getSentTimeMillis() &&
              expected.getGroupContext().groupChange != null &&
              Arrays.equals(sent.getSignedGroupChange(), expected.getGroupContext().groupChange.toByteArray()))
          {
            return;
          }
        }
      } catch (NoSuchMessageException | MmsException e) {
        // A deleted control message cannot authorize a later send.
      }
    }

    throw new HeldGroupException();
  }

  /** A local policy rejection is terminal, rather than a retryable network error. */
  public static final class HeldGroupException extends IOException {
    public HeldGroupException() {
      super("Group requires local approval before sending.");
    }
  }
}

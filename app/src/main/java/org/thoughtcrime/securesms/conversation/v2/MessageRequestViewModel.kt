package org.thoughtcrime.securesms.conversation.v2

import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.Result
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.groups.ui.GroupChangeFailureReason
import org.thoughtcrime.securesms.groups.v2.admission.GroupAdmission
import org.thoughtcrime.securesms.jobs.AvatarGroupsV2DownloadJob
import org.thoughtcrime.securesms.jobs.RetrieveProfileAvatarJob
import org.thoughtcrime.securesms.messagerequests.MessageRequestRepository
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * View model for interacting with a message request displayed in ConversationFragment V2
 */
class MessageRequestViewModel(
  private val threadId: Long,
  private val recipientRepository: ConversationRecipientRepository,
  private val messageRequestRepository: MessageRequestRepository
) : ViewModel() {

  private val recipientId: Single<RecipientId>
    get() {
      return recipientRepository
        .conversationRecipient
        .map { it.id }
        .firstOrError()
    }

  fun onAccept(expectedGroupEpisode: String? = null): Single<Result<Unit, GroupChangeFailureReason>> {
    return recipientId
      .flatMap { recipientId ->
        messageRequestRepository.acceptMessageRequest(recipientId, threadId, expectedGroupEpisode)
          .doOnSuccess { result ->
            if (result is Result.Success) {
              enqueueAcceptedAvatars(recipientId)
            }
          }
      }
      .subscribeOn(Schedulers.io())
      .observeOn(AndroidSchedulers.mainThread())
  }

  private fun enqueueAcceptedAvatars(recipientId: RecipientId) {
    val recipient = Recipient.resolved(recipientId)
    if (GroupAdmission.isHeld(recipient)) {
      return
    }
    if (recipient.isPushV2Group) {
      if (recipient.shouldBlurAvatar && recipient.hasAvatar) {
        AvatarGroupsV2DownloadJob.enqueueUnblurredAvatar(recipient.requireGroupId().requireV2())
      }
      val jobs = recipient.participantIds
        .map { Recipient.resolved(it) }
        .filter { it.shouldBlurAvatar && it.hasAvatar }
        .map { RetrieveProfileAvatarJob(it, it.profileAvatar, true, true) }
      AppDependencies.jobManager.addAll(jobs)
    } else if (recipient.shouldBlurAvatar && recipient.hasAvatar) {
      RetrieveProfileAvatarJob.enqueueUnblurredAvatar(recipient)
    }
  }

  fun onDelete(): Single<Result<Unit, GroupChangeFailureReason>> {
    return recipientId
      .flatMap { recipientId ->
        messageRequestRepository.deleteMessageRequest(recipientId, threadId)
      }
      .observeOn(AndroidSchedulers.mainThread())
  }

  fun onBlock(): Single<Result<Unit, GroupChangeFailureReason>> {
    return recipientId
      .flatMap { recipientId ->
        messageRequestRepository.blockMessageRequest(recipientId)
      }
      .observeOn(AndroidSchedulers.mainThread())
  }

  fun onUnblock(): Single<Result<Unit, GroupChangeFailureReason>> {
    return recipientId
      .flatMap { recipientId ->
        messageRequestRepository.unblockAndAccept(recipientId)
      }
      .observeOn(AndroidSchedulers.mainThread())
  }

  fun onReportSpam(): Completable {
    return recipientId
      .flatMapCompletable { recipientId ->
        messageRequestRepository.reportSpamMessageRequest(recipientId, threadId)
      }
      .observeOn(AndroidSchedulers.mainThread())
  }

  fun onBlockAndReportSpam(): Single<Result<Unit, GroupChangeFailureReason>> {
    return recipientId
      .flatMap { recipientId ->
        messageRequestRepository.blockAndReportSpamMessageRequest(recipientId, threadId)
      }
      .observeOn(AndroidSchedulers.mainThread())
  }
}

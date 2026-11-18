package org.thoughtcrime.securesms.backup.v2.ui.subscription

import android.content.Context
import android.content.DialogInterface
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.concurrent.LifecycleDisposable
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.subscription.InAppPaymentsRepository
import org.thoughtcrime.securesms.components.settings.app.subscription.errors.DonationError
import org.thoughtcrime.securesms.components.settings.app.subscription.errors.DonationErrorDialogs
import org.thoughtcrime.securesms.components.settings.app.subscription.errors.DonationErrorParams
import org.thoughtcrime.securesms.database.InAppPaymentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.databaseprotos.InAppPaymentData

/** Error handling shared by backup subscription screens. */
object BackupPaymentErrorHandler {
  private val TAG = Log.tag(BackupPaymentErrorHandler::class.java)

  class ErrorHandler : DefaultLifecycleObserver {

    private var fragment: Fragment? = null
    private var errorDialog: DialogInterface? = null
    private var errorHandlerCallback: ErrorHandlerCallback? = null

    fun attach(
      fragment: Fragment,
      errorHandlerCallback: ErrorHandlerCallback?,
      inAppPaymentId: InAppPaymentTable.InAppPaymentId
    ) {
      attach(fragment, errorHandlerCallback, Flowable.just(inAppPaymentId))
    }

    fun attach(
      fragment: Fragment,
      errorHandlerCallback: ErrorHandlerCallback?,
      inAppPaymentIdSource: Flowable<InAppPaymentTable.InAppPaymentId>
    ) {
      this.fragment = fragment
      this.errorHandlerCallback = errorHandlerCallback

      val disposables = LifecycleDisposable()
      fragment.viewLifecycleOwner.lifecycle.addObserver(this)

      disposables.bindTo(fragment.viewLifecycleOwner)
      disposables += inAppPaymentIdSource
        .switchMap { filterUnnotifiedErrors(it) }
        .doOnNext {
          SignalDatabase.inAppPayments.update(it.copy(notified = true))
        }
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribeBy {
          showErrorDialog(it)
        }

      disposables += inAppPaymentIdSource
        .switchMap { InAppPaymentsRepository.observeTemporaryErrors(it) }
        .onBackpressureLatest()
        .concatMapSingle { (id, err) -> Single.fromCallable { SignalDatabase.inAppPayments.getById(id)!! to err } }
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribeBy { (inAppPayment, error) ->
          handleTemporaryError(inAppPayment, error)
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
      errorDialog?.dismiss()
      fragment = null
      errorHandlerCallback = null
    }

    private fun filterUnnotifiedErrors(inAppPaymentId: InAppPaymentTable.InAppPaymentId): Flowable<InAppPaymentTable.InAppPayment> {
      return InAppPaymentsRepository.observeUpdates(inAppPaymentId)
        .subscribeOn(Schedulers.io())
        .filter {
          !it.notified && it.data.error != null
        }
    }

    private fun handleTemporaryError(inAppPayment: InAppPaymentTable.InAppPayment, throwable: Throwable) {
      when (throwable) {
        is DonationError.UserCancelledPaymentError -> {
          Log.d(TAG, "User cancelled out of payment flow.", true)
        }

        is DonationError.BadgeRedemptionError.DonationPending -> {
          Log.d(TAG, "Long-running donation is still pending.", true)
          errorHandlerCallback?.navigateToDonationPending(inAppPayment)
        }

        is DonationError.UserLaunchedExternalApplication -> {
          Log.d(TAG, "User launched an external application.", true)
          errorHandlerCallback?.onUserLaunchedAnExternalApplication()
        }

        else -> {
          Log.d(TAG, "Displaying donation error dialog.", true)
          errorDialog = DonationErrorDialogs.show(
            fragment!!.requireContext(),
            throwable,
            DialogHandler(inAppPayment)
          )
        }
      }
    }

    private fun showErrorDialog(inAppPayment: InAppPaymentTable.InAppPayment) {
      if (errorDialog != null) {
        Log.d(TAG, "Already displaying an error dialog. Skipping. ${inAppPayment.data.error}", true)
        return
      }

      val error = inAppPayment.data.error
      if (error == null) {
        Log.d(TAG, "InAppPayment does not contain an error. Skipping.", true)
        return
      }

      when (error.type) {
        InAppPaymentData.Error.Type.SETUP_CANCELLED -> {
          Log.d(TAG, "User canceled payment setup.", true)
        }
        else -> {
          Log.d(TAG, "Displaying donation error dialog.", true)
          errorDialog = DonationErrorDialogs.show(
            fragment!!.requireContext(),
            inAppPayment,
            DialogHandler(inAppPayment)
          )
        }
      }
    }

    private inner class DialogHandler(private val inAppPayment: InAppPaymentTable.InAppPayment) : DonationErrorDialogs.DialogCallback() {
      var tryAgain = false

      override fun onTryCreditCardAgain(context: Context): DonationErrorParams.ErrorAction<Unit> {
        return DonationErrorParams.ErrorAction(
          label = R.string.DeclineCode__try,
          action = {
            tryAgain = true
          }
        )
      }

      override fun onTryBankTransferAgain(context: Context): DonationErrorParams.ErrorAction<Unit> {
        return DonationErrorParams.ErrorAction(
          label = R.string.DeclineCode__try,
          action = {
            tryAgain = true
          }
        )
      }

      override fun onDialogDismissed() {
        errorDialog = null
        if (tryAgain) {
          Log.d(TAG, "Resetting InAppPayment[${inAppPayment.id}] to CREATED for retry.", true)
          InAppPaymentsRepository.updateInAppPayment(
            inAppPayment.copy(
              state = InAppPaymentTable.State.CREATED,
              data = inAppPayment.data.newBuilder()
                .error(null)
                .redemption(InAppPaymentData.RedemptionState(stage = InAppPaymentData.RedemptionState.Stage.INIT))
                .build()
            )
          ).subscribe()
        } else {
          errorHandlerCallback?.exitCheckoutFlow()
        }
      }
    }
  }

  interface ErrorHandlerCallback {
    fun onUserLaunchedAnExternalApplication()
    fun navigateToDonationPending(inAppPayment: InAppPaymentTable.InAppPayment)
    fun exitCheckoutFlow()
  }

}

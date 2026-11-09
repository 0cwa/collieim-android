package org.thoughtcrime.securesms.components.settings.app.subscription.thanks

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import org.signal.core.models.badges.Badge
import org.signal.core.ui.FixedRoundedCornerBottomSheetDialogFragment
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.util.fragments.findListener

class ThanksForYourSupportBottomSheetDialogFragment : FixedRoundedCornerBottomSheetDialogFragment() {

  companion object {
    const val SHEET_TAG = "ThanksForYourSupportBottomSheet"

    fun create(badge: Badge): ThanksForYourSupportBottomSheetDialogFragment {
      val args = ThanksForYourSupportBottomSheetDialogFragmentArgs.Builder(badge).build().toBundle()
      val sheet = ThanksForYourSupportBottomSheetDialogFragment()

      sheet.arguments = args
      return sheet
    }
  }

  override val peekHeightPercentage: Float = 1f

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
    return inflater.inflate(R.layout.thanks_for_your_support_bottom_sheet_dialog_fragment, container, false)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    val done: MaterialButton = view.findViewById(R.id.thanks_bottom_sheet_done)
    done.setOnClickListener { dismissAllowingStateLoss() }
  }

  override fun onDismiss(dialog: DialogInterface) {
    super.onDismiss(dialog)
    val args = ThanksForYourSupportBottomSheetDialogFragmentArgs.fromBundle(requireArguments())

    if (args.badge.isSubscription()) {
      requireActivity().finish()
      requireActivity().startActivity(AppSettingsActivity.manageSubscriptions(requireContext()))
    } else {
      findListener<Callback>()?.onBoostThanksSheetDismissed()
    }
  }

  interface Callback {
    fun onBoostThanksSheetDismissed()
  }
}

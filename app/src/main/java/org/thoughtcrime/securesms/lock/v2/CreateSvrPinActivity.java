package org.thoughtcrime.securesms.lock.v2;


import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStore;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;

import org.thoughtcrime.securesms.PassphrasePromptActivity;
import org.thoughtcrime.securesms.PassphraseRequiredActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.pin.RecoveryPinGuard;
import org.thoughtcrime.securesms.service.KeyCachingService;
import org.thoughtcrime.securesms.util.DynamicRegistrationTheme;
import org.thoughtcrime.securesms.util.DynamicTheme;

public class CreateSvrPinActivity extends PassphraseRequiredActivity {

  public static final int REQUEST_NEW_PIN = 27698;

  private final DynamicTheme dynamicTheme = new DynamicRegistrationTheme();
  private boolean requiresVerification;
  private @Nullable ConfirmationState confirmationState;

  public static @NonNull Intent getIntentForPinCreate(@NonNull Context context) {
    CreateSvrPinFragmentArgs args = new CreateSvrPinFragmentArgs.Builder()
                                                                .setIsForgotPin(false)
                                                                .setIsPinChange(false)
                                                                .build();

    return getIntentWithArgs(context, args);
  }

  public static @NonNull Intent getIntentForPinChangeFromForgotPin(@NonNull Context context) {
    CreateSvrPinFragmentArgs args = new CreateSvrPinFragmentArgs.Builder()
                                                                .setIsForgotPin(true)
                                                                .setIsPinChange(true)
                                                                .build();

    return getIntentWithArgs(context, args);
  }

  public static @NonNull Intent getIntentForPinChangeFromSettings(@NonNull Context context) {
    CreateSvrPinFragmentArgs args = new CreateSvrPinFragmentArgs.Builder()
                                                                .setIsForgotPin(false)
                                                                .setIsPinChange(true)
                                                                .build();

    return getIntentWithArgs(context, args);
  }

  private static @NonNull Intent getIntentWithArgs(@NonNull Context context, @NonNull CreateSvrPinFragmentArgs args) {
    return new Intent(context, CreateSvrPinActivity.class).putExtras(args.toBundle());
  }

  @Override
  protected @Nullable Bundle frameworkSavedInstanceState(@Nullable Bundle savedInstanceState) {
    requiresVerification = (createArguments().getIsPinChange() || SignalStore.svr().hasPin()) &&
                           SignalStore.pin().getRecoveryPinGuardEnabled();
    return requiresVerification ? null : savedInstanceState;
  }

  @Override
  public void onCreate(Bundle bundle, boolean ready) {
    super.onCreate(bundle, ready);
    dynamicTheme.onCreate(this);

    CreateSvrPinFragmentArgs arguments = createArguments();
    if (requiresVerification) {
      confirmationState = new ViewModelProvider(this).get(ConfirmationState.class);
      RecoveryPinGuard.verify(this, () -> {
        if (!isFinishing() && !isDestroyed() && !KeyCachingService.isLocked()) {
          showPinFlow(arguments);
        } else {
          finish();
        }
      }, this::finish);
    } else {
      showPinFlow(arguments);
    }
  }

  private @NonNull CreateSvrPinFragmentArgs createArguments() {
    Bundle extras = getIntent().getExtras();
    return CreateSvrPinFragmentArgs.fromBundle(extras == null ? new Bundle() : extras);
  }

  @Override
  protected void onSaveInstanceState(@NonNull Bundle state) {
    // Run the normal lifecycle bookkeeping without persisting the protected arguments or views.
    super.onSaveInstanceState(requiresVerification ? new Bundle() : state);
  }

  @Override
  protected void onRestoreInstanceState(@NonNull Bundle state) {
    super.onRestoreInstanceState(requiresVerification ? new Bundle() : state);
  }

  private void showPinFlow(@NonNull CreateSvrPinFragmentArgs arguments) {
    setContentView(R.layout.create_kbs_pin_activity);
    NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment);
    if (navController.getCurrentDestination() == null) {
      navController.setGraph(R.navigation.create_kbs_pin, arguments.toBundle());
    }
    ConfirmationState state = confirmationState;
    if (state != null) {
      if (state.arguments != null) {
        navController.navigate(R.id.action_confirmPin, state.arguments.toBundle());
      }
      navController.addOnDestinationChangedListener((controller, destination, args) -> {
        if (destination.getId() == R.id.confirmKbsPinFragment && args != null) {
          state.arguments = ConfirmSvrPinFragmentArgs.fromBundle(args);
        } else {
          state.clear();
        }
      });
    }
  }

  @Nullable ViewModelStoreOwner confirmationViewModelOwner() {
    return confirmationState;
  }

  /** In-memory confirmation state and any active save survive rotation but never process death. */
  public static final class ConfirmationState extends ViewModel implements ViewModelStoreOwner {
    private final ViewModelStore store = new ViewModelStore();
    private @Nullable ConfirmSvrPinFragmentArgs arguments;

    @Override public @NonNull ViewModelStore getViewModelStore() {
      return store;
    }

    @Override protected void onCleared() {
      clear();
    }

    private void clear() {
      arguments = null;
      store.clear();
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
  }

  private Intent getPromptPassphraseIntent() {
    return getRoutedIntent(PassphrasePromptActivity.class, getIntent());
  }

  private Intent getRoutedIntent(Class<?> destination, @Nullable Intent nextIntent) {
    final Intent intent = new Intent(this, destination);
    if (nextIntent != null)   intent.putExtra("next_intent", nextIntent);
    return intent;
  }
}

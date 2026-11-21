package org.thoughtcrime.securesms.preferences;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.signal.core.ui.util.SpanUtil;
import org.signal.core.ui.util.ThemeUtil;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.settings.app.privacy.NetworkSettingsController;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.keyvalue.MollyValues;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.net.NetworkManager;
import org.thoughtcrime.securesms.net.ProxyType;
import org.thoughtcrime.securesms.net.SocksProxy;
import org.thoughtcrime.securesms.util.SystemWindowInsetsSetter;

import java.util.Arrays;
import java.util.Objects;

import info.guardianproject.netcipher.proxy.OrbotHelper;

public class NetworkPreferenceFragment extends PreferenceFragmentCompat {

  private final NetworkManager networkManager;
  private final NetworkSettingsController networkSettingsController = new NetworkSettingsController();

  private Preference networkState;

  public NetworkPreferenceFragment() {
    networkManager = AppDependencies.getNetworkManager();
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    getPreferenceManager().setPreferenceDataStore(SignalStore.getPreferenceDataStore());
    addPreferencesFromResource(R.xml.preferences_network);
  }

  @Override
  public @NonNull View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.network_preference_fragment, container, false);

    FrameLayout content = view.findViewById(R.id.preferences_content);
    View preferencesView = super.onCreateView(inflater, content, savedInstanceState);
    content.addView(preferencesView);

    return view;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    Toolbar toolbar = view.findViewById(R.id.toolbar);
    toolbar.setNavigationOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());

    // Edge-to-edge: the toolbar has to grow clear of the status bar, and the list has to clear the navigation bar and
    // the keyboard (the Socks host and port rows are text fields). Same treatment as the proxy screen it replaces.
    toolbar.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
    SystemWindowInsetsSetter.attach(toolbar, getViewLifecycleOwner(), WindowInsetsCompat.Type.statusBars());
    SystemWindowInsetsSetter.attach(view, getViewLifecycleOwner(),
                                    WindowInsetsCompat.Type.navigationBars() | WindowInsetsCompat.Type.ime(),
                                    SystemWindowInsetsSetter.ApplyMode.MARGIN);
  }

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);

    networkState = requirePreference("pref_network_connection_state");
    clearTestResult(networkState);

    ListPreference     proxyType  = requirePreference(MollyValues.PROXY_TYPE);
    Preference         socksGroup = requirePreference("proxy_socks");
    EditTextPreference socksHost  = requirePreference(MollyValues.PROXY_SOCKS_HOST);
    EditTextPreference socksPort  = requirePreference(MollyValues.PROXY_SOCKS_PORT);

    proxyType.setOnPreferenceChangeListener(new ListSummaryListener() {
      @Override
      public boolean onPreferenceChange(Preference preference, Object value) {
        ProxyType type = ProxyType.fromCode((String) value);
        if (type == ProxyType.ORBOT && !networkManager.isOrbotAvailable()) {
          promptToInstallOrbot(preference.getContext());
          return false;
        }
        networkManager.setProxyChoice(type);
        socksGroup.setVisible(type == ProxyType.SOCKS5);
        clearTestResult(networkState);
        return super.onPreferenceChange(preference, value);
      }
    });
    initializeListSummary(proxyType);

    socksGroup.setVisible(SignalStore.molly().getProxyType() == ProxyType.SOCKS5);

    socksHost.setOnBindEditTextListener(editText -> {
      editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
      editText.selectAll();
    });
    socksHost.setOnPreferenceChangeListener(((preference, newValue) -> {
      String host = (String) newValue;
      if (!host.isEmpty()) {
        if (!SocksProxy.isValidHost(host)) {
          Toast.makeText(preference.getContext(), R.string.NetworkPreferenceFragment_the_host_you_typed_is_not_valid, Toast.LENGTH_SHORT).show();
          return false;
        }
      } else {
        host = networkManager.getDefaultProxySocksHost();
      }
      networkManager.setProxySocksHost(host);
      socksHost.setText(host);
      clearTestResult(networkState);
      return false;
    }));

    socksPort.setOnBindEditTextListener(editText -> {
      editText.setInputType(InputType.TYPE_CLASS_NUMBER);
      editText.setFilters(new InputFilter[] {new InputFilter.LengthFilter(5)});
      editText.selectAll();
    });
    socksPort.setOnPreferenceChangeListener(((preference, newValue) -> {
      String newValueStr = (String) newValue;
      int port;
      if (!newValueStr.isEmpty()) {
        port = Integer.parseInt(newValueStr);
        if (!SocksProxy.isValidPort(port)) {
          Toast.makeText(preference.getContext(), R.string.NetworkPreferenceFragment_a_valid_port_number_is_between_0_and_65535, Toast.LENGTH_SHORT).show();
          return false;
        }
      } else {
        port = networkManager.getDefaultProxySocksPort();
      }
      networkManager.setProxySocksPort(port);
      socksPort.setText(String.valueOf(port));
      clearTestResult(networkState);
      return false;
    }));
  }

  @Override
  @SuppressLint("RestrictedApi")
  protected RecyclerView.Adapter onCreateAdapter(PreferenceScreen preferenceScreen) {
    return new PreferenceGroupAdapter(preferenceScreen) {
      @Override
      public void onBindViewHolder(@NonNull PreferenceViewHolder holder, int position) {
        super.onBindViewHolder(holder, position);
        Preference preference = getItem(position);
        View iconFrame = holder.itemView.findViewById(androidx.preference.R.id.icon_frame);
        if (iconFrame != null) {
          iconFrame.setVisibility(preference.getIcon() == null ? View.GONE : View.VISIBLE);
        }
      }
    };
  }

  private <T extends Preference> T requirePreference(@NonNull CharSequence key) {
    return Objects.requireNonNull(findPreference(key));
  }

  private void initializeListSummary(ListPreference preference) {
    preference.setSummary(preference.getEntry());
  }

  private class ListSummaryListener implements Preference.OnPreferenceChangeListener {
    @Override
    public boolean onPreferenceChange(Preference preference, Object value) {
      ListPreference listPreference = (ListPreference) preference;
      int entryIndex = Arrays.asList(listPreference.getEntryValues()).indexOf(value);

      listPreference.setSummary(entryIndex >= 0 && entryIndex < listPreference.getEntries().length
                                ? listPreference.getEntries()[entryIndex]
                                : getString(R.string.preferences__led_color_unknown));
      return true;
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    SignalStore.molly().setHasSeenNetworkConfig(true);
    applyProxyChanges(false);
  }

  private void applyProxyChanges(boolean alwaysRestart) {
    networkSettingsController.applyProxyChanges(alwaysRestart);
  }

  public boolean onTestConnectionClicked(Preference preference) {
    preference.setOnPreferenceClickListener(null);

    networkSettingsController.startConnectionCheck(
        getViewLifecycleOwner().getLifecycle(),
        isProxyConfigured -> preference.setSummary(isProxyConfigured ? R.string.preferences_connecting_to_proxy : R.string.preferences_network__connecting),
        succeeded -> {
          preference.setSummary(getTestResultString(succeeded));
          preference.setOnPreferenceClickListener(this::onTestConnectionClicked);
        }
    );

    return true;
  }

  private void clearTestResult(Preference preference) {
    networkSettingsController.invalidateConnectionCheck();
    preference.setSummary(null);
    preference.setOnPreferenceClickListener(this::onTestConnectionClicked);
  }

  private CharSequence getTestResultString(boolean succeeded) {
    if (succeeded) {
      return getString(R.string.preferences_network__online);
    }

    return SpanUtil.color(
        ThemeUtil.getThemedColor(requireContext(), com.google.android.material.R.attr.colorError),
        getString(R.string.preferences_connection_failed)
    );
  }

  public static CharSequence getSummary(Context context) {
    int proxyTypeResId = SignalStore.molly().getProxyType().getStringResource();
    return context.getString(R.string.ApplicationPreferencesActivity_network_summary, context.getString(proxyTypeResId));
  }

  private void promptToInstallOrbot(@NonNull Context context) {
    final Intent installIntent = OrbotHelper.getOrbotInstallIntent(context);
    new MaterialAlertDialogBuilder(context)
        .setTitle(R.string.NetworkPreferenceFragment_missing_orbot_app)
        .setMessage(R.string.NetworkPreferenceFragment_molly_wont_connect_without_orbot_which_is_not_installed_on_this_device)
        .setPositiveButton(R.string.NetworkPreferenceFragment_get_orbot, (dialog, which) -> {
          startActivity(installIntent);
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }
}

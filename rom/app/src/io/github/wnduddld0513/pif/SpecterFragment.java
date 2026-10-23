/*
 * Play Integrity Fix (inject-s) - ROM integration
 *
 * Copyright (C) 2026 wnduddld0513
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * The Specter page: the management layer over Play Integrity Fix and the keybox attestation
 * service. The online revocation check, the keybox catalog, the lookup of the latest
 * security patch and the restart of Google Play services run in this app. The settings of
 * the page are stored in the "specter" preferences, see SpecterSync.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 as published by the Free
 * Software Foundation. It is distributed WITHOUT ANY WARRANTY; see the GNU
 * General Public License (LICENSE) for details.
 */
package io.github.wnduddld0513.pif;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.security.keybox.IKeyboxManager;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SpecterFragment extends SettingsBasePreferenceFragment {
    private static final String TAG = "SpecterFragment";
    private static final String[] CATEGORIES = {
        "specter_sync", "specter_steps", "specter_pif", "specter_keybox", "specter_targets",
        "specter_patch", "specter_apply", "specter_adb", "specter_rom",
    };

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    // Model names of the devices by product
    private final Map<String, String> mModels = new HashMap<>();

    private SwitchPreferenceCompat mAutoUpdate;
    private Preference mUpdateNow;
    private Preference mCheck;
    private Preference mKeyboxInfo;
    private Preference mFetchPatch;
    private SwitchPreferenceCompat mAutoTarget;
    private MultiSelectListPreference mPreferred;
    private MultiSelectListPreference mBlocked;
    private MultiSelectListPreference mExcluded;
    private ListPreference mProvider;
    private ListPreference mPatchSource;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setSharedPreferencesName(SpecterSync.PREFS);
        setPreferencesFromResource(R.xml.specter_settings, rootKey);

        final MainSwitchPreference mainSwitch = findPreference("specter_enabled");
        mainSwitch.setChecked(SpecterSync.isEnabled(requireContext()));
        mainSwitch.addOnSwitchChangeListener((switchView, checked) -> {
            SpecterSync.setEnabled(requireContext(), checked);
            updateVisibility(checked);
        });
        updateVisibility(mainSwitch.isChecked());

        mAutoUpdate = findPreference("specter_auto");
        mAutoUpdate.setOnPreferenceChangeListener((p, value) -> {
            SpecterSync.setAutoUpdate(requireContext(), (Boolean) value);
            return true;
        });
        findPreference(SpecterSync.KEY_INTERVAL).setOnPreferenceChangeListener((p, value) -> {
            // Rescheduled once the value is stored
            mHandler.post(() -> PifStore.scheduleUpdates(requireContext()));
            return true;
        });
        mUpdateNow = findPreference("specter_update_now");
        mUpdateNow.setOnPreferenceClickListener(p -> {
            updateNow();
            return true;
        });

        mPreferred = findPreference(SpecterSync.KEY_PREFERRED);
        mBlocked = findPreference(SpecterSync.KEY_BLOCKED);
        for (MultiSelectListPreference list : new MultiSelectListPreference[] {mPreferred, mBlocked}) {
            setDevices(list, null);
            list.setOnPreferenceChangeListener((p, value) -> {
                @SuppressWarnings("unchecked")
                final Set<String> selected = (Set<String>) value;
                list.setSummary(deviceSummary(selected));
                mHandler.post(() -> propagate(false));
                return true;
            });
        }
        loadDevices();

        mProvider = findPreference(SpecterSync.KEY_PROVIDER);
        setProviders(null);
        loadProviders();
        findPreference("keybox_url").setOnPreferenceClickListener(p -> {
            askKeyboxUrl();
            return true;
        });
        mKeyboxInfo = findPreference("keybox_info");
        mCheck = findPreference("keybox_check");
        mCheck.setOnPreferenceClickListener(p -> {
            checkRevocation();
            return true;
        });

        mAutoTarget = findPreference("target_auto");
        mAutoTarget.setOnPreferenceChangeListener((p, value) -> {
            try {
                KeyboxStore.service().setAutoTarget((Boolean) value);
                return true;
            } catch (Exception e) {
                return false;
            }
        });
        mExcluded = findPreference(SpecterSync.KEY_EXCLUDED);
        setApps();
        mExcluded.setOnPreferenceChangeListener((p, value) -> {
            @SuppressWarnings("unchecked")
            final Set<String> selected = (Set<String>) value;
            mExcluded.setSummary(appSummary(selected));
            return true;
        });

        mPatchSource = findPreference(SpecterSync.KEY_PATCH_SOURCE);
        mPatchSource.setOnPreferenceChangeListener((p, value) -> {
            if (SpecterSync.SOURCE_MANUAL.equals(value)) {
                // The value that is set manually stays; the dialog edits it
                mHandler.post(() -> PatchDialog.show(requireContext(), null, this::refresh));
            } else {
                mHandler.post(() -> propagate(false));
            }
            return true;
        });
        for (String key : new String[] {SpecterSync.KEY_PATCH_DEVICE, SpecterSync.KEY_PATCH_BULLETIN,
                SpecterSync.KEY_PATCH_SYNTHETIC}) {
            findPreference(key).setOnPreferenceChangeListener((p, value) -> {
                mHandler.post(() -> propagate(false));
                return true;
            });
        }
        mFetchPatch = findPreference("patch_fetch");
        mFetchPatch.setOnPreferenceClickListener(p -> {
            fetchPatch();
            return true;
        });

        findPreference("apply_now").setOnPreferenceClickListener(p -> {
            final Context context = requireContext().getApplicationContext();
            mExecutor.execute(() -> KeyboxStore.applyNow(context));
            Toast.makeText(context, R.string.apply_done, Toast.LENGTH_SHORT).show();
            return true;
        });

        for (String key : new String[] {SpecterSync.KEY_ADB_DEV, SpecterSync.KEY_ADB_USB}) {
            findPreference(key).setOnPreferenceChangeListener((p, value) -> {
                // Applied once the toggle is stored
                final Context context = requireContext().getApplicationContext();
                mHandler.post(() -> mExecutor.execute(() -> SpecterSync.applyAdb(context)));
                return true;
            });
        }

        findPreference("rom_fingerprint").setSummary(Build.FINGERPRINT);
        findPreference("rom_model").setSummary(Build.MODEL);
        findPreference("rom_build_type").setSummary(Build.TYPE + " / " + Build.TAGS);
        findPreference("rom_security_patch").setSummary(Build.VERSION.SECURITY_PATCH);
    }

    @Override
    public void onResume() {
        super.onResume();
        getActivity().setTitle(R.string.specter_title);
        refresh();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mExecutor.shutdown();
    }

    /** The categories are shown while Specter is on. */
    private void updateVisibility(boolean enabled) {
        for (String key : CATEGORIES) {
            findPreference(key).setVisible(enabled);
        }
    }

    /** The selected devices by model name, or "Automatic" when none is selected. */
    private CharSequence deviceSummary(Set<String> selected) {
        if (selected.isEmpty()) return getString(R.string.pif_devices_auto);
        final List<String> names = new ArrayList<>();
        for (String product : selected) {
            names.add(mModels.getOrDefault(product, product));
        }
        java.util.Collections.sort(names);
        return String.join(", ", names);
    }

    /** Offers the devices when they could be loaded; until then only the selected ones. */
    private void setDevices(MultiSelectListPreference list, List<String[]> devices) {
        final List<CharSequence> names = new ArrayList<>();
        final List<CharSequence> values = new ArrayList<>();
        if (devices != null) {
            for (String[] d : devices) {
                names.add(d[0]);
                values.add(d[1]);
            }
        }
        for (String product : list.getValues()) {
            if (!values.contains(product)) {
                names.add(product);
                values.add(product);
            }
        }
        list.setEntries(names.toArray(new CharSequence[0]));
        list.setEntryValues(values.toArray(new CharSequence[0]));
        list.setSummary(deviceSummary(list.getValues()));
    }

    private void loadDevices() {
        mExecutor.execute(() -> {
            List<String[]> devices = null;
            try {
                devices = PifStore.deviceList();
            } catch (Exception e) {
                Log.w("PifStore", "Device list not available", e);
            }
            final List<String[]> result = devices;
            mHandler.post(() -> {
                if (!isAdded() || result == null) return;
                for (String[] d : result) {
                    mModels.put(d[1], d[0]);
                }
                setDevices(mPreferred, result);
                setDevices(mBlocked, result);
            });
        });
    }

    /** Lists Automatic first, then the sources of the catalog when they could be loaded. */
    private void setProviders(List<String> sources) {
        final List<CharSequence> names = new ArrayList<>();
        final List<CharSequence> values = new ArrayList<>();
        names.add(getString(R.string.keybox_provider_auto));
        values.add(SpecterSync.PROVIDER_AUTO);
        final List<String> all = new ArrayList<>();
        if (sources != null) all.addAll(sources);
        if (!all.contains(mProvider.getValue())
                && !SpecterSync.PROVIDER_AUTO.equals(mProvider.getValue())) {
            all.add(mProvider.getValue());
        }
        for (String source : all) {
            names.add(source);
            values.add(source);
        }
        mProvider.setEntries(names.toArray(new CharSequence[0]));
        mProvider.setEntryValues(values.toArray(new CharSequence[0]));
        mProvider.setValue(mProvider.getValue());
    }

    private void loadProviders() {
        mExecutor.execute(() -> {
            List<String> sources = null;
            try {
                sources = KeyboxStore.providers();
            } catch (Exception e) {
                Log.w("KeyboxStore", "Keybox catalog not available", e);
            }
            final List<String> result = sources;
            mHandler.post(() -> {
                if (isAdded() && result != null) setProviders(result);
            });
        });
    }

    /** The launchable apps for the exclusions, with the excluded ones that are not launchable. */
    private void setApps() {
        final PackageManager pm = requireContext().getPackageManager();
        final TreeMap<String, String> byLabel = new TreeMap<>();
        for (ResolveInfo info : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            final String pkg = info.activityInfo.packageName;
            byLabel.put(info.loadLabel(pm) + "\u0000" + pkg, pkg);
        }
        for (String pkg : mExcluded.getValues()) {
            if (!byLabel.containsValue(pkg)) {
                byLabel.put(KeyboxStore.label(requireContext(), pkg) + "\u0000" + pkg, pkg);
            }
        }
        final List<CharSequence> names = new ArrayList<>();
        final List<CharSequence> values = new ArrayList<>();
        for (String pkg : byLabel.values()) {
            names.add(KeyboxStore.label(requireContext(), pkg));
            values.add(pkg);
        }
        mExcluded.setEntries(names.toArray(new CharSequence[0]));
        mExcluded.setEntryValues(values.toArray(new CharSequence[0]));
        mExcluded.setSummary(appSummary(mExcluded.getValues()));
    }

    private CharSequence appSummary(Set<String> packages) {
        if (packages.isEmpty()) return getString(R.string.target_exclude_none);
        final List<String> names = new ArrayList<>();
        for (String pkg : packages) {
            names.add(KeyboxStore.label(requireContext(), pkg));
        }
        java.util.Collections.sort(names);
        return String.join(", ", names);
    }

    /** A change on this page reaches Play Integrity Fix and TEESimulator, see SpecterSync.propagate. */
    private void propagate(boolean replaceKeybox) {
        final Context context = requireContext().getApplicationContext();
        mExecutor.execute(() -> {
            int message;
            try {
                if (replaceKeybox) {
                    SpecterSync.propagateReplacingKeybox(context);
                } else {
                    SpecterSync.propagate(context);
                }
                message = R.string.specter_update_done;
            } catch (Exception e) {
                Log.w("PifStore", "Update failed", e);
                message = R.string.specter_update_failed;
            }
            final int result = message;
            mHandler.post(() -> {
                if (!isAdded()) return;
                Toast.makeText(context, result, Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    /** One pass over Play Integrity Fix and TEESimulator, see SpecterSync. */
    private void updateNow() {
        final Context context = requireContext().getApplicationContext();
        mUpdateNow.setEnabled(false);
        mExecutor.execute(() -> {
            int message;
            try {
                SpecterSync.updateNow(context);
                message = R.string.specter_update_done;
            } catch (Exception e) {
                Log.w("PifStore", "Update failed", e);
                message = R.string.specter_update_failed;
            }
            final int result = message;
            mHandler.post(() -> {
                if (!isAdded()) return;
                mUpdateNow.setEnabled(true);
                Toast.makeText(context, result, Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    private void refresh() {
        final Context context = requireContext();
        mAutoUpdate.setChecked(SpecterSync.isAutoUpdate(context));
        final IKeyboxManager svc = KeyboxStore.service();
        mCheck.setEnabled(svc != null);
        mFetchPatch.setEnabled(svc != null);
        mAutoTarget.setEnabled(svc != null);
        findPreference("keybox_url").setEnabled(svc != null);
        if (svc == null) {
            mCheck.setSummary(R.string.keybox_status_unavailable);
            mKeyboxInfo.setSummary(R.string.keybox_status_unavailable);
            return;
        }
        try {
            mAutoTarget.setChecked(svc.isAutoTarget());
            // A patch set in the dialog is the manual one; clearing it follows the profile
            final String patch = svc.getSecurityPatch();
            if (SpecterSync.SOURCE_MANUAL.equals(mPatchSource.getValue()) && patch.isEmpty()) {
                mPatchSource.setValue(SpecterSync.SOURCE_PROFILE);
            }
            mCheck.setEnabled(false);
            mCheck.setSummary(R.string.keybox_check_summary);
        } catch (Exception e) {
            mCheck.setSummary(R.string.keybox_status_unavailable);
        }
        refreshKeyboxInfo();
    }

    /** Reads the keybox state, and the catalog entry of the keybox, off the UI thread. */
    private void refreshKeyboxInfo() {
        final Context context = requireContext().getApplicationContext();
        mExecutor.execute(() -> {
            KeyboxStore.Info info = null;
            try {
                info = KeyboxStore.info(context, KeyboxStore.service());
            } catch (Exception e) {
                Log.w("KeyboxStore", "Keybox info not available", e);
            }
            final KeyboxStore.Info result = info;
            mHandler.post(() -> {
                if (!isAdded()) return;
                mCheck.setEnabled(result != null && result.present);
                if (result == null) {
                    mKeyboxInfo.setSummary(R.string.keybox_status_unavailable);
                } else if (!result.present) {
                    mKeyboxInfo.setSummary(R.string.keybox_status_none);
                } else {
                    final List<String> states = new ArrayList<>();
                    if (result.googleRevoked) states.add(getString(R.string.keybox_info_revoked_google));
                    if (result.catalogRevoked) states.add(getString(R.string.keybox_info_revoked_catalog));
                    if (result.softbanned) states.add(getString(R.string.keybox_info_softbanned));
                    if (states.isEmpty()) states.add(getString(R.string.keybox_info_present));
                    String text = String.join(", ", states);
                    if (!result.source.isEmpty()) {
                        text += "\n" + getString(R.string.keybox_info_source, result.source,
                                result.version);
                    }
                    mKeyboxInfo.setSummary(text);
                }
            });
        });
    }

    private void checkRevocation() {
        final Context context = requireContext().getApplicationContext();
        mCheck.setEnabled(false);
        mExecutor.execute(() -> {
            int message;
            try {
                final boolean revoked = KeyboxStore.checkRevocation(context, KeyboxStore.service());
                if (revoked) {
                    SpecterSync.propagate(context);
                }
                message = revoked ? R.string.keybox_check_revoked : R.string.keybox_check_ok;
            } catch (Exception e) {
                message = R.string.keybox_check_unknown;
            }
            final int result = message;
            mHandler.post(() -> {
                if (!isAdded()) return;
                Toast.makeText(context, result, Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    /** Asks for the URL of a keybox, downloads it and installs it. */
    private void askKeyboxUrl() {
        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://");
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.keybox_url_title)
                .setView(input)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    final String url = input.getText().toString().trim();
                    if (!url.isEmpty()) installFromUrl(url);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void installFromUrl(String url) {
        final Context context = requireContext().getApplicationContext();
        mExecutor.execute(() -> {
            String error = null;
            try {
                KeyboxStore.installFromUrl(context, KeyboxStore.service(), url);
            } catch (Exception e) {
                Log.w("KeyboxStore", "Installing the keybox from the URL failed: " + e);
                error = String.valueOf(e.getMessage());
            }
            final String reason = error;
            mHandler.post(() -> {
                if (!isAdded()) return;
                Toast.makeText(context, reason == null ? context.getString(R.string.keybox_imported)
                        : context.getString(R.string.keybox_import_failed, reason),
                        Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    /** Looks up the latest security patch and offers it in the manual field. */
    private void fetchPatch() {
        final Context context = requireContext().getApplicationContext();
        mExecutor.execute(() -> {
            String latest;
            try {
                latest = KeyboxStore.latestSecurityPatch();
            } catch (Exception e) {
                latest = null;
            }
            final String result = latest;
            mHandler.post(() -> {
                if (!isAdded()) return;
                if (result == null) {
                    Toast.makeText(context, R.string.patch_fetch_failed, Toast.LENGTH_SHORT).show();
                } else {
                    PatchDialog.show(requireContext(), result, () -> {
                        // A date saved in the dialog is the manual patch
                        if (!PatchDialog.current().isEmpty()) {
                            mPatchSource.setValue(SpecterSync.SOURCE_MANUAL);
                        }
                        refresh();
                    });
                }
            });
        });
    }
}

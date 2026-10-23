/*
 * Play Integrity Fix (inject-s) - ROM integration
 *
 * Copyright (C) 2026 wnduddld0513
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Specter keeps the Play Integrity Fix profile and TEESimulator consistent: one pass
 * (the steps of Specter's action.sh that need no root) restarts Google Play services and
 * the Play Store, keeps them among the target apps of the keybox service, sets its
 * security patch, checks and refreshes its keybox and fetches the profile of the chosen
 * device. The automatic pass runs after the update job of PifStore.scheduleUpdates, which
 * is the only scheduler.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 as published by the Free
 * Software Foundation. It is distributed WITHOUT ANY WARRANTY; see the GNU
 * General Public License (LICENSE) for details.
 */
package io.github.wnduddld0513.pif;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.security.keybox.IKeyboxManager;
import android.util.Log;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

final class SpecterSync {
    /** Unset or 1: Specter is on; only 0 turns it off. */
    static final String SWITCH_SETTING = "lenovo_specter";

    static final String PREFS = "specter";
    private static final String KEY_AUTO = "auto_update";
    private static final String KEY_FIRST_DONE = "first_update_done";

    // Keys of the preferences of the Specter page, which the page stores in PREFS itself
    static final String KEY_STEP_GMS = "step_gms";
    static final String KEY_STEP_TARGETS = "step_targets";
    static final String KEY_STEP_PATCH = "step_patch";
    static final String KEY_STEP_KEYBOX = "step_keybox";
    static final String KEY_STEP_PIF = "step_pif";
    static final String KEY_GMS_STOP = "gms_stop";
    static final String KEY_GMS_CLEAR = "gms_clear";
    static final String KEY_PREFERRED = "pif_preferred";
    static final String KEY_BLOCKED = "pif_blocked";
    static final String KEY_PROVIDER = "keybox_provider";
    static final String KEY_PATCH_SOURCE = "patch_source";
    static final String KEY_PATCH_DEVICE = "patch_src_device";
    static final String KEY_PATCH_BULLETIN = "patch_src_bulletin";
    static final String KEY_PATCH_SYNTHETIC = "patch_src_synthetic";
    static final String KEY_ADB_DEV = "adb_dev";
    static final String KEY_ADB_USB = "adb_usb";
    static final String KEY_EXCLUDED = "target_exclude";
    static final String KEY_INTERVAL = "interval_hours";

    static final String SOURCE_PROFILE = "profile";
    static final String SOURCE_DEVICE = "device";
    static final String SOURCE_BULLETIN = "bulletin";
    static final String SOURCE_SYNTHETIC = "synthetic";
    static final String SOURCE_MANUAL = "manual";
    static final String PROVIDER_AUTO = "auto";

    private static final String TAG = "SpecterSync";
    private static final long HOUR_MS = 60 * 60 * 1000L;

    private SpecterSync() {}

    static boolean isEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), SWITCH_SETTING, 1) != 0;
    }

    static void setEnabled(Context context, boolean enabled) {
        Settings.Global.putInt(context.getContentResolver(), SWITCH_SETTING, enabled ? 1 : 0);
    }

    static boolean isAutoUpdate(Context context) {
        return prefs(context).getBoolean(KEY_AUTO, true) && PifStore.isAutoUpdate(context);
    }

    /** Turning it on also turns on the automatic update of the profile, whose job drives the pass. */
    static void setAutoUpdate(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO, enabled).apply();
        if (enabled) {
            PifStore.setAutoUpdate(context, true);
        }
    }

    /** The period of the automatic update: 6, 12 (default) or 24 hours. */
    static long intervalMs(Context context) {
        long hours;
        try {
            hours = Long.parseLong(prefs(context).getString(KEY_INTERVAL, "12"));
        } catch (NumberFormatException e) {
            hours = 12;
        }
        return hours * HOUR_MS;
    }

    /** After the update job of the profile: aligns TEESimulator and restarts the targets if that changed anything. */
    static void afterScheduledUpdate(Context context) {
        if (!isEnabled(context) || !prefs(context).getBoolean(KEY_AUTO, true)) return;
        final SharedPreferences prefs = prefs(context);
        final boolean changed = align(context, false);
        // Like Specter's autopif: with preferred or blocked devices every pass picks again
        if (prefs.getBoolean(KEY_STEP_PIF, true)
                && (!prefs.getStringSet(KEY_PREFERRED, new HashSet<>()).isEmpty()
                        || !prefs.getStringSet(KEY_BLOCKED, new HashSet<>()).isEmpty())) {
            try {
                pickDevice(context);
                PifStore.fetch(context);
            } catch (IOException e) {
                Log.w(TAG, "Picking a device failed", e);
            }
        }
        if (changed) {
            PifStore.stopTargets(context);
        }
    }

    /** Whether the update that every fresh or reset setup runs once has succeeded. */
    static boolean isFirstUpdateDone(Context context) {
        return prefs(context).getBoolean(KEY_FIRST_DONE, false);
    }

    /**
     * The update that runs once after the first boot or a reset: applies the latest profile,
     * aligns TEESimulator and takes a keybox, without clearing any data. Recorded as done
     * only when it succeeds, so the job retries it until the device is online. Blocking;
     * call it from a worker thread. Does nothing while Specter is off.
     */
    static void firstUpdate(Context context) throws IOException {
        if (!isEnabled(context)) return;
        run(context, false, false);
        prefs(context).edit().putBoolean(KEY_FIRST_DONE, true).apply();
    }

    /**
     * What every change on the Specter page runs: the steps that are on, in the order of
     * Specter's action.sh, so that Play Integrity Fix and TEESimulator follow the page.
     * Blocking; call it from a worker thread. Throws if the profile cannot be fetched.
     * Does nothing while Specter is off.
     */
    static void propagate(Context context) throws IOException {
        if (!isEnabled(context)) return;
        run(context, true, false);
    }

    /** propagate, installing a new keybox from the chosen provider even if the installed one is fine. */
    static void propagateReplacingKeybox(Context context) throws IOException {
        if (!isEnabled(context)) return;
        run(context, true, true);
    }

    /** The manual pass, which follows the steps like propagate; see there. */
    static void updateNow(Context context) throws IOException {
        propagate(context);
    }

    /** The defaults after a reset: every Specter setting back to its default and the first update runs again. */
    static void resetDefaults(Context context) {
        prefs(context).edit().clear().apply();
        Settings.Global.putInt(context.getContentResolver(), SWITCH_SETTING, 1);
    }

    /** Turns off developer options and USB debugging as the toggles of the ADB category say; both are off by default. */
    static void applyAdb(Context context) {
        final SharedPreferences prefs = prefs(context);
        try {
            if (prefs.getBoolean(KEY_ADB_DEV, false)) {
                Settings.Global.putInt(context.getContentResolver(),
                        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0);
            }
            if (prefs.getBoolean(KEY_ADB_USB, false)) {
                Settings.Global.putInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Turning off the debugging settings failed", e);
        }
    }

    private static void run(Context context, boolean mayClear, boolean replaceKeybox) throws IOException {
        final SharedPreferences prefs = prefs(context);
        try {
            if (prefs.getBoolean(KEY_STEP_GMS, true)) {
                restartGms(context, mayClear && prefs.getBoolean(KEY_GMS_CLEAR, false));
            }
            align(context, replaceKeybox);
            if (prefs.getBoolean(KEY_STEP_PIF, true)) {
                pickDevice(context);
                PifStore.fetch(context);
            }
        } finally {
            applyAdb(context);
        }
    }

    /** Force-stops Google Play services and the Play Store as the options say, and clears the Play Store's data if asked. */
    private static void restartGms(Context context, boolean clear) {
        if (prefs(context).getBoolean(KEY_GMS_STOP, true)) {
            PifStore.stopTargets(context);
        }
        if (clear) {
            context.getSystemService(ActivityManager.class)
                    .clearApplicationUserData(KeyboxStore.VENDING, null);
        }
    }

    /**
     * Preferred and blocked devices, as pif_apply_preferred of Specter: one of the preferred
     * ones that are not blocked, else with only blocked ones any other device of the list,
     * else the current device stays. The pick is what PifStore downloads.
     */
    private static void pickDevice(Context context) throws IOException {
        final Set<String> preferred = prefs(context).getStringSet(KEY_PREFERRED, new HashSet<>());
        final Set<String> blocked = prefs(context).getStringSet(KEY_BLOCKED, new HashSet<>());
        if (preferred.isEmpty() && blocked.isEmpty()) return;
        final List<String> pool = new ArrayList<>(preferred);
        pool.removeAll(blocked);
        if (pool.isEmpty()) {
            for (String[] device : PifStore.deviceList()) {
                if (!blocked.contains(device[1])) pool.add(device[1]);
            }
        }
        if (!pool.isEmpty()) {
            PifStore.setDevice(context, pool.get(new Random().nextInt(pool.size())));
        }
    }

    /**
     * Keeps Google Play services and the Play Store among the target apps of the keybox
     * service, sets its security patch from the chosen source, then checks the revocation
     * (unknown while offline) and takes a keybox if there is none or it is revoked.
     * Skipped while TEESimulator is off. Returns whether the service changed.
     */
    private static boolean align(Context context, boolean replaceKeybox) {
        if (!KeyboxStore.isTeeEnabled(context)) return false;
        final IKeyboxManager svc = KeyboxStore.service();
        if (svc == null) return false;
        final SharedPreferences prefs = prefs(context);
        boolean changed = false;
        if (prefs.getBoolean(KEY_STEP_TARGETS, true)) {
            try {
                final List<String> targets = new ArrayList<>(svc.getTargetApps());
                boolean added = false;
                for (String pkg : new String[] {KeyboxStore.GMS, KeyboxStore.VENDING}) {
                    if (!targets.contains(pkg)) {
                        targets.add(pkg);
                        added = true;
                    }
                }
                if (added) {
                    svc.setTargetApps(targets);
                    changed = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "Keeping the target apps as they are", e);
            }
        }
        if (prefs.getBoolean(KEY_STEP_PATCH, true)) {
            try {
                final String patch = resolvePatch(context);
                if (patch != null && !svc.getSecurityPatch().equals(patch)) {
                    svc.setSecurityPatch(patch);
                    changed = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "Keeping the security patch as it is", e);
            }
        }
        if (prefs.getBoolean(KEY_STEP_KEYBOX, true)) {
            try {
                try {
                    KeyboxStore.checkRevocation(context, svc);
                } catch (Exception e) {
                    // Offline or no keybox: the revocation stays as it was
                }
                final boolean fixedProvider = !PROVIDER_AUTO.equals(
                        prefs.getString(KEY_PROVIDER, PROVIDER_AUTO));
                if (replaceKeybox || fixedProvider || KeyboxStore.needsKeybox(svc)) {
                    KeyboxStore.fetchOnline(context, svc);
                    changed = true;
                }
            } catch (Exception e) {
                Log.w("KeyboxStore", "Keeping the keybox as it is", e);
            }
        }
        return changed;
    }

    /**
     * The security patch the keybox service gets: "" follows the profile; Device, Bulletin
     * and Synthetic are tried from the chosen source on in that order, skipping the ones
     * that are off; null leaves the service as it is (Manual, or no source gave a date).
     */
    private static String resolvePatch(Context context) {
        final SharedPreferences prefs = prefs(context);
        final String source = prefs.getString(KEY_PATCH_SOURCE, SOURCE_PROFILE);
        if (SOURCE_PROFILE.equals(source)) return "";
        final String[] chain = {SOURCE_DEVICE, SOURCE_BULLETIN, SOURCE_SYNTHETIC};
        final String[] toggles = {KEY_PATCH_DEVICE, KEY_PATCH_BULLETIN, KEY_PATCH_SYNTHETIC};
        int start = -1;
        for (int i = 0; i < chain.length; i++) {
            if (chain[i].equals(source)) start = i;
        }
        if (start < 0) return null;
        for (int i = start; i < chain.length; i++) {
            if (!prefs.getBoolean(toggles[i], true)) continue;
            try {
                final String patch = chain[i].equals(SOURCE_DEVICE) ? Build.VERSION.SECURITY_PATCH
                        : chain[i].equals(SOURCE_BULLETIN) ? KeyboxStore.latestSecurityPatch()
                        : LocalDate.now().toString().substring(0, 7) + "-05";
                if (patch != null && patch.matches("\\d{4}-\\d{2}-\\d{2}")) return patch;
            } catch (Exception e) {
                Log.w(TAG, "Security patch source " + chain[i] + " not available", e);
            }
        }
        return null;
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

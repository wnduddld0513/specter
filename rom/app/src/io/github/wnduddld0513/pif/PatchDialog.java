/*
 * Play Integrity Fix (inject-s) - ROM integration
 *
 * Copyright (C) 2026 wnduddld0513
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 as published by the Free
 * Software Foundation. It is distributed WITHOUT ANY WARRANTY; see the GNU
 * General Public License (LICENSE) for details.
 */
package io.github.wnduddld0513.pif;

import android.app.AlertDialog;
import android.content.Context;
import android.security.keybox.IKeyboxManager;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import java.util.regex.Pattern;

/** Edits the security patch of the keybox service: Auto (empty) or a manual YYYY-MM-DD. */
final class PatchDialog {
    private static final Pattern FORMAT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private PatchDialog() {}

    /** The text of the service's security patch ("" is Auto), or "" while it cannot be read. */
    static String current() {
        try {
            return KeyboxStore.service().getSecurityPatch();
        } catch (Exception e) {
            return "";
        }
    }

    /** @param prefill the text of the field, or null for the stored security patch */
    static void show(Context context, String prefill, Runnable onDone) {
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
        input.setHint("YYYY-MM-DD");
        input.setText(prefill != null ? prefill : current());
        new AlertDialog.Builder(context)
                .setTitle(R.string.patch_title)
                .setView(input)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    final String patch = input.getText().toString().trim();
                    if (patch.isEmpty() || FORMAT.matcher(patch).matches()) {
                        set(context, patch, onDone);
                    } else {
                        Toast.makeText(context, R.string.patch_invalid, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton(R.string.patch_auto_button, (dialog, which) -> set(context, "", onDone))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void set(Context context, String patch, Runnable onDone) {
        try {
            final IKeyboxManager svc = KeyboxStore.service();
            svc.setSecurityPatch(patch);
        } catch (Exception e) {
            Toast.makeText(context, R.string.patch_invalid, Toast.LENGTH_SHORT).show();
        }
        onDone.run();
    }
}

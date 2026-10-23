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

import androidx.fragment.app.Fragment;

/** The Specter page: revocation check, automatic targets, latest patch, apply and fingerprint. */
public class SpecterActivity extends PifPageActivity {
    @Override
    Fragment createFragment() {
        return new SpecterFragment();
    }
}

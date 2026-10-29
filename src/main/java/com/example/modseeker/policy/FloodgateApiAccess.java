// SPDX-FileCopyrightText: 2026 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.policy;

import org.geysermc.floodgate.api.FloodgateApi;

final class FloodgateApiAccess {
    private FloodgateApiAccess() {
    }

    static FloodgateDetector.Lookup createLookup() {
        FloodgateApi api = FloodgateApi.getInstance();
        return api::isFloodgatePlayer;
    }
}

// SPDX-FileCopyrightText: 2026 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.protocol;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record VerifiedModPayload(
        String checkId,
        List<String> mods,
        List<String> nestedModIds,
        List<String> hashes) {

    public VerifiedModPayload {
        mods = List.copyOf(mods);
        nestedModIds = List.copyOf(nestedModIds);
        hashes = List.copyOf(hashes);
    }

    public VerifiedModPayload(String checkId, List<String> mods, List<String> hashes) {
        this(checkId, mods, List.of(), hashes);
    }

    public List<String> modIds() {
        return mods.stream().map(VerifiedModPayload::stripVersion).toList();
    }

    public List<String> allModIds() {
        Set<String> all = new LinkedHashSet<>(modIds());
        all.addAll(nestedModIds);
        return List.copyOf(all);
    }

    private static String stripVersion(String entry) {
        int separator = entry.indexOf(':');
        return separator < 0 ? entry : entry.substring(0, separator);
    }
}

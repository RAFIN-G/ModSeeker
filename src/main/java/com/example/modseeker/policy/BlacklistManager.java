// SPDX-FileCopyrightText: 2025 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.policy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;




public class BlacklistManager {
    private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_-]{0,127}");

    private final File blacklistFile;
    private final Gson gson;
    private volatile Set<String> blacklistedMods = Set.of();
    private volatile boolean loadFailed;
    private boolean hasGoodSnapshot;

    public BlacklistManager(File dataFolder) {
        this.blacklistFile = new File(dataFolder, "modblacklist.json");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        loadBlacklist();
    }

    private static class BlacklistData {
        List<String> blacklist = new ArrayList<>();
    }

    public synchronized void loadBlacklist() {
        try {
            if (!blacklistFile.exists()) {
                Set<String> empty = Set.of();
                if (!write(empty)) {
                    throw new IOException("Could not create modblacklist.json");
                }
                publish(empty);
                return;
            }

            BlacklistData data;
            try (Reader reader = Files.newBufferedReader(blacklistFile.toPath(), StandardCharsets.UTF_8)) {
                data = gson.fromJson(reader, BlacklistData.class);
            }
            if (data == null || data.blacklist == null) {
                throw new IOException("Blacklist data is missing");
            }
            Set<String> parsed = new LinkedHashSet<>();
            for (String value : data.blacklist) {
                String modId = normalize(value);
                if (modId == null) {
                    throw new IOException("Blacklist contains an invalid mod id");
                }
                parsed.add(modId);
            }
            write(parsed);
            publish(parsed);
        } catch (Exception e) {
            loadFailed = true;
            if (!hasGoodSnapshot) {
                blacklistedMods = Set.of();
            }
        }
    }

    public synchronized void saveBlacklist() {
        write(blacklistedMods);
    }







    public List<String> checkForBlacklistedMods(List<String> modList) {
        List<String> blacklistedFound = new ArrayList<>();
        for (String mod : modList) {
            if (blacklistedMods.contains(mod.toLowerCase(Locale.ROOT))) {
                blacklistedFound.add(mod);
            }
        }
        return blacklistedFound;
    }







    public synchronized boolean addBlacklistedMod(String modId) {
        return addAll(List.of(modId)).changed() == 1;
    }







    public synchronized boolean removeBlacklistedMod(String modId) {
        return removeAll(List.of(modId)).changed() == 1;
    }

    public synchronized BatchResult addAll(Collection<String> modIds) {
        Set<String> candidate = new LinkedHashSet<>(blacklistedMods);
        int changed = 0;
        int skipped = 0;
        for (String value : modIds) {
            String modId = normalize(value);
            if (modId == null || !candidate.add(modId)) {
                skipped++;
            } else {
                changed++;
            }
        }
        return commit(candidate, changed, skipped);
    }

    public synchronized BatchResult removeAll(Collection<String> modIds) {
        Set<String> candidate = new LinkedHashSet<>(blacklistedMods);
        int changed = 0;
        int skipped = 0;
        for (String value : modIds) {
            String modId = normalize(value);
            if (modId != null && candidate.remove(modId)) {
                changed++;
            } else {
                skipped++;
            }
        }
        return commit(candidate, changed, skipped);
    }

    private BatchResult commit(Set<String> candidate, int changed, int skipped) {
        if (changed == 0) {
            return new BatchResult(true, 0, skipped, "");
        }
        if (!write(candidate)) {
            return new BatchResult(false, 0, skipped, "Could not write modblacklist.json");
        }
        publish(candidate);
        return new BatchResult(true, changed, skipped, "");
    }

    private boolean write(Set<String> values) {
        try {
            BlacklistData data = new BlacklistData();
            data.blacklist = values.stream().sorted().toList();
            File temp = new File(blacklistFile.getParentFile(), blacklistFile.getName() + ".tmp");
            Files.writeString(temp.toPath(), gson.toJson(data), StandardCharsets.UTF_8);
            move(temp, blacklistFile);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void move(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void publish(Set<String> values) {
        blacklistedMods = Collections.unmodifiableSet(new LinkedHashSet<>(values));
        hasGoodSnapshot = true;
        loadFailed = false;
    }

    public static boolean isValidModId(String value) {
        return normalize(value) != null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return MOD_ID.matcher(normalized).matches() ? normalized : null;
    }






    public Set<String> getBlacklistedMods() {
        return blacklistedMods;
    }







    public boolean isModBlacklisted(String modId) {
        String normalized = normalize(modId);
        return normalized != null && blacklistedMods.contains(normalized);
    }

    public boolean isLoadFailed() {
        return loadFailed && !hasGoodSnapshot;
    }

    public record BatchResult(boolean success, int changed, int skipped, String error) {
    }
}

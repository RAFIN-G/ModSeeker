// SPDX-FileCopyrightText: 2025 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.policy;

import com.example.modseeker.ModSeekerPlugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.ChatColor;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;






public class ModAllowlistManager {

    private final File allowlistFile;
    private final Gson gson;
    private final Consumer<String> logger;

    private static final Pattern SHA512 = Pattern.compile("[0-9a-f]{128}");

    private volatile List<AllowEntry> entries = List.of();

    private volatile Set<String> allowedHashes = Collections.emptySet();
    private volatile Map<String, String> hashToLabel = Collections.emptyMap();
    private volatile boolean loadFailed = false;
    private boolean hasGoodSnapshot;

    public static class AllowEntry {
        public String label;
        public String modid;
        public String name;
        public String version;
        public String mcVersion;
        public String loader;
        public String filename;
        public String algo;
        public String hash;
        public boolean essential;
    }

    private static class AllowlistData {
        List<AllowEntry> allowlist = new ArrayList<>();
    }

    public ModAllowlistManager(ModSeekerPlugin plugin) {
        this(plugin.getStorageFolder(), plugin::logInfo);
    }

    ModAllowlistManager(File dataFolder, Consumer<String> logger) {
        this.allowlistFile = new File(dataFolder, "modallowlist.json");
        this.gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        this.logger = logger;
        load();
    }

    public synchronized void load() {
        if (!allowlistFile.exists()) {
            List<AllowEntry> empty = List.of();
            if (write(empty)) {
                publish(empty);
            } else {
                loadFailed = true;
            }
            return;
        }

        try {
            AllowlistData data;
            try (Reader reader = Files.newBufferedReader(allowlistFile.toPath(), StandardCharsets.UTF_8)) {
                data = gson.fromJson(reader, AllowlistData.class);
            }
            List<AllowEntry> parsed = new ArrayList<>();
            if (data != null && data.allowlist != null) {
                for (AllowEntry entry : data.allowlist) {
                    AllowEntry normalized = normalize(entry);
                    if (normalized == null) {
                        throw new IllegalArgumentException("Allowlist contains an invalid entry");
                    }
                    parsed.add(normalized);
                }
            }
            if (!write(parsed)) {
                logger.accept(ChatColor.YELLOW + "Could not normalize data/modallowlist.json");
            }
            publish(parsed);
        } catch (Exception e) {
            loadFailed = true;
            logger.accept(ChatColor.YELLOW + "modallowlist.json failed to load, keeping last good list: "
                    + e.getMessage());
        }
    }

    private void publish(List<AllowEntry> candidate) {
        List<AllowEntry> snapshot = candidate.stream().map(this::copy).toList();
        Set<String> newHashes = new HashSet<>();
        Map<String, String> newLabels = new HashMap<>();
        for (AllowEntry e : snapshot) {
            if (e.hash == null || e.hash.isEmpty()) {
                continue;
            }
            String h = e.hash.toLowerCase(Locale.ROOT);
            newHashes.add(h);
            newLabels.put(h, e.label != null ? e.label : (e.modid != null ? e.modid : h));
        }
        this.entries = snapshot;
        this.allowedHashes = Collections.unmodifiableSet(newHashes);
        this.hashToLabel = Collections.unmodifiableMap(newLabels);
        this.loadFailed = false;
        this.hasGoodSnapshot = true;
    }

    private boolean write(List<AllowEntry> candidate) {
        try {
            AllowlistData data = new AllowlistData();
            data.allowlist = candidate.stream().map(this::copy).toList();
            String json = gson.toJson(data);
            File tmp = new File(allowlistFile.getParentFile(), allowlistFile.getName() + ".tmp");
            Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp.toPath(), allowlistFile.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), allowlistFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            logger.accept(ChatColor.YELLOW + "Failed to write modallowlist.json: " + e.getMessage());
            return false;
        }
    }

    public boolean isHashAllowed(String hash) {
        if (hash == null) {
            return false;
        }
        return allowedHashes.contains(hash.toLowerCase(Locale.ROOT));
    }

    public String getLabelForHash(String hash) {
        if (hash == null) {
            return null;
        }
        return hashToLabel.get(hash.toLowerCase(Locale.ROOT));
    }

    public boolean containsHash(String hash) {
        return isHashAllowed(hash);
    }

    public synchronized boolean isEssentialHash(String hash) {
        if (hash == null) {
            return false;
        }
        for (AllowEntry e : entries) {
            if (e.hash != null && e.hash.equalsIgnoreCase(hash)) {
                return e.essential;
            }
        }
        return false;
    }

    public synchronized int addEntries(List<AllowEntry> newEntries) {
        return addEntriesResult(newEntries).changed();
    }

    public synchronized BatchResult addEntriesResult(List<AllowEntry> newEntries) {
        List<AllowEntry> candidate = new ArrayList<>(entries);
        Set<String> existing = new HashSet<>(allowedHashes);
        int added = 0;
        int skipped = 0;
        for (AllowEntry source : newEntries) {
            AllowEntry entry = normalize(source);
            if (entry == null || !existing.add(entry.hash)) {
                skipped++;
            } else {
                candidate.add(entry);
                added++;
            }
        }
        if (added == 0) {
            return new BatchResult(true, 0, skipped, 0, "");
        }
        if (!write(candidate)) {
            return new BatchResult(false, 0, skipped, 0, "Could not write modallowlist.json");
        }
        publish(candidate);
        return new BatchResult(true, added, skipped, 0, "");
    }

    public synchronized BatchResult replaceEssentialEntries(java.util.Collection<String> managedModIds,
            List<AllowEntry> replacementEntries) {
        if (managedModIds == null || replacementEntries == null) {
            return new BatchResult(false, 0, 0, 0, "Essential replacement input is missing");
        }
        Set<String> managed = new HashSet<>();
        for (String value : managedModIds) {
            if (value == null || value.isBlank()) {
                return new BatchResult(false, 0, 0, 0, "Essential mod ID is invalid");
            }
            managed.add(value.toLowerCase(Locale.ROOT));
        }
        if (managed.isEmpty()) {
            return new BatchResult(false, 0, 0, 0, "Essential mod ID set is empty");
        }
        List<AllowEntry> replacements = new ArrayList<>();
        Set<String> replacementHashes = new HashSet<>();
        int skipped = 0;
        for (AllowEntry source : replacementEntries) {
            AllowEntry entry = normalize(source);
            if (entry == null || !entry.essential
                    || !managed.contains(entry.modid.toLowerCase(Locale.ROOT))) {
                return new BatchResult(false, 0, skipped, 0, "Essential replacement entry is invalid");
            }
            if (replacementHashes.add(entry.hash)) {
                replacements.add(entry);
            } else {
                skipped++;
            }
        }
        if (replacements.isEmpty()) {
            return new BatchResult(false, 0, skipped, 0, "Essential replacement set is empty");
        }
        replacements.sort(Comparator.comparing((AllowEntry entry) -> entry.modid)
                .thenComparing(entry -> entry.version)
                .thenComparing(entry -> entry.hash));
        List<AllowEntry> candidate = new ArrayList<>();
        int removed = 0;
        for (AllowEntry entry : entries) {
            boolean managedEssential = entry.essential
                    && managed.contains(entry.modid.toLowerCase(Locale.ROOT));
            if (managedEssential || replacementHashes.contains(entry.hash)) {
                removed++;
            } else {
                candidate.add(copy(entry));
            }
        }
        candidate.addAll(replacements.stream().map(this::copy).toList());
        if (sameEntries(entries, candidate)) {
            return new BatchResult(true, 0, skipped, 0, "");
        }
        if (!write(candidate)) {
            return new BatchResult(false, 0, skipped, 0, "Could not write modallowlist.json");
        }
        publish(candidate);
        return new BatchResult(true, removed + replacements.size(), skipped, 0, "");
    }

    public synchronized int removeByHash(String hash) {
        if (hash == null) {
            return 0;
        }
        return removeHashes(List.of(hash)).changed();
    }

    public synchronized BatchResult removeHashes(java.util.Collection<String> hashes) {
        Set<String> requested = new HashSet<>();
        for (String value : hashes) {
            if (value != null) {
                requested.add(value.toLowerCase(Locale.ROOT));
            }
        }
        List<AllowEntry> candidate = new ArrayList<>();
        int removed = 0;
        int protectedCount = 0;
        for (AllowEntry entry : entries) {
            if (!requested.contains(entry.hash)) {
                candidate.add(entry);
            } else if (entry.essential) {
                candidate.add(entry);
                protectedCount++;
            } else {
                removed++;
            }
        }
        int skipped = Math.max(0, requested.size() - removed - protectedCount);
        if (removed == 0) {
            return new BatchResult(true, 0, skipped, protectedCount, "");
        }
        if (!write(candidate)) {
            return new BatchResult(false, 0, skipped, protectedCount, "Could not write modallowlist.json");
        }
        publish(candidate);
        return new BatchResult(true, removed, skipped, protectedCount, "");
    }

    public synchronized BatchResult removeHashesForModId(String modid, java.util.Collection<String> hashes) {
        if (modid == null || hashes == null) {
            return new BatchResult(true, 0, 1, 0, "");
        }
        Set<String> requested = new HashSet<>();
        for (String value : hashes) {
            if (value != null) {
                requested.add(value.toLowerCase(Locale.ROOT));
            }
        }
        List<AllowEntry> candidate = new ArrayList<>();
        Set<String> matched = new HashSet<>();
        int removed = 0;
        int protectedCount = 0;
        for (AllowEntry entry : entries) {
            boolean owned = entry.modid != null && entry.modid.equalsIgnoreCase(modid);
            if (!owned || !requested.contains(entry.hash)) {
                candidate.add(entry);
                continue;
            }
            matched.add(entry.hash);
            if (entry.essential) {
                candidate.add(entry);
                protectedCount++;
            } else {
                removed++;
            }
        }
        int skipped = Math.max(0, requested.size() - matched.size());
        if (removed == 0) {
            return new BatchResult(true, 0, skipped, protectedCount, "");
        }
        if (!write(candidate)) {
            return new BatchResult(false, 0, skipped, protectedCount, "Could not write modallowlist.json");
        }
        publish(candidate);
        return new BatchResult(true, removed, skipped, protectedCount, "");
    }

    public synchronized int removeByModId(String modid) {
        return removeByModIdResult(modid).changed();
    }

    public synchronized BatchResult removeByModIdResult(String modid) {
        if (modid == null) {
            return new BatchResult(true, 0, 1, 0, "");
        }
        List<AllowEntry> candidate = new ArrayList<>();
        int removed = 0;
        int protectedCount = 0;
        for (AllowEntry entry : entries) {
            boolean matches = (entry.modid != null && entry.modid.equalsIgnoreCase(modid))
                    || (entry.label != null && entry.label.toLowerCase(Locale.ROOT)
                            .startsWith(modid.toLowerCase(Locale.ROOT) + " "));
            if (matches && entry.essential) {
                candidate.add(entry);
                protectedCount++;
            } else if (matches) {
                removed++;
            } else {
                candidate.add(entry);
            }
        }
        if (removed == 0) {
            return new BatchResult(true, 0, 1, protectedCount, "");
        }
        if (!write(candidate)) {
            return new BatchResult(false, 0, 0, protectedCount, "Could not write modallowlist.json");
        }
        publish(candidate);
        return new BatchResult(true, removed, 0, protectedCount, "");
    }

    public synchronized List<AllowEntry> getEntries() {
        return entries.stream().map(this::copy).toList();
    }

    public Map<String, List<AllowEntry>> getEntriesByLabelName() {
        Map<String, List<AllowEntry>> grouped = new LinkedHashMap<>();
        for (AllowEntry e : entries) {
            String key = e.name != null ? e.name : (e.modid != null ? e.modid : "unknown");
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(copy(e));
        }
        grouped.replaceAll((key, values) -> List.copyOf(values));
        return Map.copyOf(grouped);
    }

    public int size() {
        return allowedHashes.size();
    }

    public int essentialCount() {
        int c = 0;
        for (AllowEntry e : entries) {
            if (e.essential) {
                c++;
            }
        }
        return c;
    }

    public boolean isEmpty() {
        return allowedHashes.isEmpty();
    }

    public boolean isLoadFailed() {
        return loadFailed && !hasGoodSnapshot;
    }

    public boolean exists() {
        return allowlistFile.exists();
    }

    public File getFile() {
        return allowlistFile;
    }

    private AllowEntry normalize(AllowEntry source) {
        if (source == null || source.hash == null) {
            return null;
        }
        AllowEntry entry = copy(source);
        entry.hash = entry.hash.toLowerCase(Locale.ROOT);
        if (!SHA512.matcher(entry.hash).matches()) {
            return null;
        }
        entry.algo = "sha512";
        entry.modid = value(entry.modid, "unknown");
        entry.name = value(entry.name, entry.modid);
        entry.version = value(entry.version, "unknown");
        entry.mcVersion = value(entry.mcVersion, "unknown");
        entry.loader = value(entry.loader, "fabric");
        entry.filename = value(entry.filename, "unknown");
        entry.label = value(entry.label, entry.modid);
        return entry;
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private AllowEntry copy(AllowEntry source) {
        AllowEntry copy = new AllowEntry();
        copy.label = source.label;
        copy.modid = source.modid;
        copy.name = source.name;
        copy.version = source.version;
        copy.mcVersion = source.mcVersion;
        copy.loader = source.loader;
        copy.filename = source.filename;
        copy.algo = source.algo;
        copy.hash = source.hash;
        copy.essential = source.essential;
        return copy;
    }

    private boolean sameEntries(List<AllowEntry> first, List<AllowEntry> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            AllowEntry left = first.get(index);
            AllowEntry right = second.get(index);
            if (!java.util.Objects.equals(left.label, right.label)
                    || !java.util.Objects.equals(left.modid, right.modid)
                    || !java.util.Objects.equals(left.name, right.name)
                    || !java.util.Objects.equals(left.version, right.version)
                    || !java.util.Objects.equals(left.mcVersion, right.mcVersion)
                    || !java.util.Objects.equals(left.loader, right.loader)
                    || !java.util.Objects.equals(left.filename, right.filename)
                    || !java.util.Objects.equals(left.algo, right.algo)
                    || !java.util.Objects.equals(left.hash, right.hash)
                    || left.essential != right.essential) {
                return false;
            }
        }
        return true;
    }

    public record BatchResult(boolean success, int changed, int skipped, int protectedCount, String error) {
    }
}

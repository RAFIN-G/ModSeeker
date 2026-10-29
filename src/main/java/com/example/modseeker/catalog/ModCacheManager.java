// SPDX-FileCopyrightText: 2025 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.catalog;

import com.example.modseeker.ModSeekerPlugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.ChatColor;

import java.io.File;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class ModCacheManager {
    static final int MAX_CACHE_ENTRIES = 512;
    private static final int MAX_MODS = 512;
    private static final long MAX_CACHE_BYTES = 32L * 1024 * 1024;
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{128}");

    private final File cacheFile;
    private final Gson gson;
    private final Consumer<String> logger;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public static class CacheEntry {
        public String name;
        public List<String> mods = new ArrayList<>();
        public List<String> hashes = new ArrayList<>();
        public long updated;
    }

    private static class CacheData {
        Map<String, CacheEntry> players = new HashMap<>();
    }

    public ModCacheManager(ModSeekerPlugin plugin) {
        this(plugin.getStorageFolder(), plugin::logInfo);
    }

    ModCacheManager(File dataFolder, Consumer<String> logger) {
        this.cacheFile = new File(dataFolder, "seek_cache.json");
        this.gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        this.logger = logger;
        load();
    }

    private synchronized void load() {
        if (!cacheFile.exists()) {
            return;
        }
        try {
            if (!cacheFile.isFile() || Files.size(cacheFile.toPath()) > MAX_CACHE_BYTES) {
                throw new IllegalStateException("cache file is outside its size bound");
            }
            CacheData data;
            try (Reader reader = Files.newBufferedReader(cacheFile.toPath(), StandardCharsets.UTF_8)) {
                data = gson.fromJson(reader, CacheData.class);
            }
            if (data == null || data.players == null) {
                throw new IllegalStateException("cache data is missing");
            }
            List<Map.Entry<String, CacheEntry>> candidates = new ArrayList<>(data.players.entrySet());
            candidates.sort(Comparator.comparingLong((Map.Entry<String, CacheEntry> entry) ->
                    entry.getValue() == null ? Long.MIN_VALUE : entry.getValue().updated).reversed());
            Map<String, CacheEntry> parsed = new HashMap<>();
            for (Map.Entry<String, CacheEntry> candidate : candidates) {
                if (parsed.size() >= MAX_CACHE_ENTRIES || !validUuid(candidate.getKey())) {
                    continue;
                }
                CacheEntry entry = normalize(candidate.getValue());
                if (entry != null) {
                    parsed.put(candidate.getKey(), entry);
                }
            }
            cache.clear();
            cache.putAll(parsed);
            save();
        } catch (Exception e) {
            cache.clear();
            logger.accept(ChatColor.YELLOW + "seek_cache.json failed to load: " + e.getMessage());
        }
    }

    public synchronized void put(UUID playerId, String name, List<String> mods, List<String> hashes) {
        CacheEntry candidate = new CacheEntry();
        candidate.name = name;
        candidate.mods = mods == null ? null : new ArrayList<>(mods);
        candidate.hashes = hashes == null ? new ArrayList<>() : new ArrayList<>(hashes);
        candidate.updated = System.currentTimeMillis();
        CacheEntry normalized = normalize(candidate);
        if (playerId == null || normalized == null) {
            logger.accept(ChatColor.YELLOW + "Verified mod cache entry was not retained because it was invalid");
            return;
        }
        cache.put(playerId.toString(), normalized);
        trimToLimit();
        save();
    }

    public CacheEntry get(UUID playerId) {
        return playerId == null ? null : copy(cache.get(playerId.toString()));
    }

    public CacheEntry getByName(String name) {
        if (name == null) {
            return null;
        }
        CacheEntry best = null;
        for (CacheEntry entry : cache.values()) {
            if (entry.name.equalsIgnoreCase(name)) {
                if (best == null || entry.updated > best.updated) {
                    best = entry;
                }
            }
        }
        return copy(best);
    }

    int size() {
        return cache.size();
    }

    private void trimToLimit() {
        while (cache.size() > MAX_CACHE_ENTRIES) {
            String oldest = cache.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().updated))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            cache.remove(oldest);
        }
    }

    private CacheEntry normalize(CacheEntry source) {
        if (source == null || source.name == null || !PLAYER_NAME.matcher(source.name).matches()
                || source.mods == null || source.mods.isEmpty() || source.mods.size() > MAX_MODS
                || source.hashes == null || !source.hashes.isEmpty() && source.hashes.size() != source.mods.size()) {
            return null;
        }
        List<String> mods = new ArrayList<>(source.mods.size());
        for (String value : source.mods) {
            if (value == null || value.isBlank() || value.length() > 256
                    || value.chars().anyMatch(Character::isISOControl)) {
                return null;
            }
            mods.add(value);
        }
        List<String> hashes = new ArrayList<>(source.hashes.size());
        for (String value : source.hashes) {
            if (value == null || !HASH.matcher(value.toLowerCase(Locale.ROOT)).matches()) {
                return null;
            }
            hashes.add(value.toLowerCase(Locale.ROOT));
        }
        CacheEntry result = new CacheEntry();
        result.name = source.name;
        result.mods = List.copyOf(mods);
        result.hashes = List.copyOf(hashes);
        result.updated = Math.max(0, source.updated);
        return result;
    }

    private CacheEntry copy(CacheEntry source) {
        if (source == null) {
            return null;
        }
        CacheEntry result = new CacheEntry();
        result.name = source.name;
        result.mods = List.copyOf(source.mods);
        result.hashes = List.copyOf(source.hashes);
        result.updated = source.updated;
        return result;
    }

    private boolean validUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void save() {
        Path parent = cacheFile.toPath().toAbsolutePath().getParent();
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            CacheData data = new CacheData();
            data.players = new HashMap<>();
            cache.forEach((key, value) -> data.players.put(key, copy(value)));
            temporary = Files.createTempFile(parent, "seek-cache-", ".tmp");
            Files.writeString(temporary, gson.toJson(data), StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            logger.accept(ChatColor.YELLOW + "Failed to write seek_cache.json: " + e.getMessage());
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception ignored) {
                }
            }
        }
    }
}

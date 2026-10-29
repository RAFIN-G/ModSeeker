// SPDX-FileCopyrightText: 2026 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.catalog;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class GuiModIdCache {
    private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_-]{0,127}");

    private final ModSeekerPlugin plugin;
    private final File file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private volatile Map<String, Entry> entries = Map.of();
    private boolean hasGoodSnapshot;

    public GuiModIdCache(ModSeekerPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getStorageFolder(), "gui_modid_cache.json");
        load();
    }

    public synchronized void load() {
        if (!file.exists()) {
            publish(Map.of());
            return;
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            CacheData data = gson.fromJson(reader, CacheData.class);
            if (data == null || data.entries == null) {
                throw new IllegalStateException("cache data is missing");
            }
            Map<String, Entry> parsed = new LinkedHashMap<>();
            for (Entry source : data.entries) {
                Entry entry = normalize(source);
                if (entry == null) {
                    throw new IllegalStateException("cache contains an invalid entry");
                }
                parsed.put(key(entry.slug, entry.loader, entry.mcVersion), entry);
            }
            publish(parsed);
        } catch (Exception e) {
            if (!hasGoodSnapshot) {
                entries = Map.of();
            }
            plugin.logInfo(ChatColor.YELLOW + "Failed to load gui_modid_cache.json; keeping the last good cache");
        }
    }

    public String get(String slug, String loader, String mcVersion) {
        Entry entry = entries.get(key(slug, loader, mcVersion));
        return entry == null ? null : entry.modId;
    }

    public String slugForModId(String modId) {
        if (modId == null) {
            return null;
        }
        String normalized = modId.toLowerCase(Locale.ROOT);
        Entry newest = null;
        for (Entry entry : entries.values()) {
            if (entry.modId.equals(normalized) && (newest == null || entry.updated > newest.updated)) {
                newest = entry;
            }
        }
        return newest == null ? null : newest.slug;
    }

    public synchronized boolean put(String slug, String loader, String mcVersion, String modId) {
        Entry entry = normalize(new Entry(slug, loader, mcVersion, modId, System.currentTimeMillis()));
        if (entry == null) {
            return false;
        }
        Map<String, Entry> candidate = new LinkedHashMap<>(entries);
        candidate.put(key(entry.slug, entry.loader, entry.mcVersion), entry);
        if (!write(candidate)) {
            return false;
        }
        publish(candidate);
        return true;
    }

    private void publish(Map<String, Entry> candidate) {
        entries = Map.copyOf(candidate);
        hasGoodSnapshot = true;
    }

    private boolean write(Map<String, Entry> candidate) {
        try {
            CacheData data = new CacheData();
            data.entries = new ArrayList<>(candidate.values());
            File temp = new File(file.getParentFile(), file.getName() + ".tmp");
            Files.writeString(temp.toPath(), gson.toJson(data), StandardCharsets.UTF_8);
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            plugin.logInfo(ChatColor.YELLOW + "Failed to write gui_modid_cache.json");
            return false;
        }
    }

    private Entry normalize(Entry source) {
        if (source == null) {
            return null;
        }
        String slug = value(source.slug, 128, true);
        String loader = value(source.loader, 32, true);
        String mcVersion = value(source.mcVersion, 64, false);
        String modId = value(source.modId, 128, true);
        if (slug == null || loader == null || mcVersion == null || modId == null
                || !MOD_ID.matcher(modId).matches()) {
            return null;
        }
        return new Entry(slug, loader, mcVersion, modId, Math.max(0, source.updated));
    }

    private String value(String source, int maxLength, boolean lowerCase) {
        if (source == null) {
            return null;
        }
        String normalized = source.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength
                || normalized.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        return lowerCase ? normalized.toLowerCase(Locale.ROOT) : normalized;
    }

    private String key(String slug, String loader, String mcVersion) {
        if (slug == null || loader == null || mcVersion == null) {
            return "";
        }
        return slug.trim().toLowerCase(Locale.ROOT) + "|" + loader.trim().toLowerCase(Locale.ROOT)
                + "|" + mcVersion.trim().toLowerCase(Locale.ROOT);
    }

    private static final class CacheData {
        List<Entry> entries = new ArrayList<>();
    }

    private record Entry(String slug, String loader, String mcVersion, String modId, long updated) {
    }
}

/*
 * Copyright (C) 2025 ModSeeker
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.example.modseeker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player whitelisting using UUIDs and Gson
 */
public class WhitelistManager {

    private final File whitelistFile;
    private final Gson gson;
    private final Map<UUID, WhitelistEntry> whitelistedPlayers = new ConcurrentHashMap<>();

    public WhitelistManager(File dataFolder) {
        this.whitelistFile = new File(dataFolder, "whitelist.json");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        loadWhitelist();
    }

    /**
     * Data structure for a whitelist entry
     */
    public static class WhitelistEntry {
        public UUID uuid;
        public String name;
        public long addedAt;

        public WhitelistEntry(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
            this.addedAt = System.currentTimeMillis();
        }
    }

    public void loadWhitelist() {
        whitelistedPlayers.clear();
        if (!whitelistFile.exists()) {
            saveWhitelist();
            return;
        }

        try (Reader reader = new FileReader(whitelistFile)) {
            WhitelistEntry[] entries = gson.fromJson(reader, WhitelistEntry[].class);
            if (entries != null) {
                for (WhitelistEntry entry : entries) {
                    if (entry.uuid != null) {
                        whitelistedPlayers.put(entry.uuid, entry);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void saveWhitelist() {
        try (Writer writer = new FileWriter(whitelistFile)) {
            gson.toJson(whitelistedPlayers.values(), writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean isWhitelisted(UUID uuid) {
        return whitelistedPlayers.containsKey(uuid);
    }

    public UUID addPlayer(String name) {
        UUID uuid = resolveUUID(name);
        if (uuid != null) {
            whitelistedPlayers.put(uuid, new WhitelistEntry(uuid, name));
            saveWhitelist();
        }
        return uuid;
    }

    public boolean removePlayer(String name) {
        UUID uuid = resolveUUID(name);
        if (uuid != null && whitelistedPlayers.containsKey(uuid)) {
            whitelistedPlayers.remove(uuid);
            saveWhitelist();
            return true;
        }
        // Fallback: iterate and remove by name if UUID resolution fails (rare)
        return whitelistedPlayers.values().removeIf(entry -> entry.name.equalsIgnoreCase(name));
    }

    private UUID resolveUUID(String name) {
        Player online = Bukkit.getPlayer(name);
        if (online != null) {
            return online.getUniqueId();
        }

        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.hasPlayedBefore()) {
            return offline.getUniqueId();
        }

        if (!Bukkit.getOnlineMode()) {
            return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        }

        return offline.getUniqueId();
    }

    public Set<String> getWhitelistedNames() {
        Set<String> names = new HashSet<>();
        for (WhitelistEntry entry : whitelistedPlayers.values()) {
            names.add(entry.name);
        }
        return names;
    }
}

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

import java.io.*;

import java.util.*;

/**
 * Manages blacklist functionality for ModSeeker plugin using Gson
 */
public class BlacklistManager {

    private final File blacklistFile;
    private Set<String> blacklistedMods = new HashSet<>();
    private final Gson gson;

    public BlacklistManager(File dataFolder) {
        this.blacklistFile = new File(dataFolder, "modblacklist.json");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        loadBlacklist();
    }

    /**
     * Data structure for JSON serialization
     */
    private static class BlacklistData {
        String _comment1 = "ModSeeker Blacklist Configuration File";
        String _comment2 = "Add mod IDs to the blacklist array below to prevent players from joining with those mods";
        List<String> blacklist = new ArrayList<>();
    }

    /**
     * Method to load blacklist from file
     */
    public void loadBlacklist() {
        try {
            if (blacklistFile.exists()) {
                try (Reader reader = new FileReader(blacklistFile)) {
                    BlacklistData data = gson.fromJson(reader, BlacklistData.class);

                    blacklistedMods.clear();
                    if (data != null && data.blacklist != null) {
                        for (String mod : data.blacklist) {
                            blacklistedMods.add(mod.toLowerCase());
                        }
                    }
                }
            } else {
                createDefaultBlacklistFile();
            }
        } catch (Exception e) {
            e.printStackTrace();
            blacklistedMods.clear();
        }
    }

    /**
     * Method to create a default blacklist file
     */
    private void createDefaultBlacklistFile() {
        try {
            BlacklistData data = new BlacklistData();
            // Add some examples or leave empty

            try (Writer writer = new FileWriter(blacklistFile)) {
                gson.toJson(data, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Save blacklist to file
     */
    public void saveBlacklist() {
        try {
            BlacklistData data = new BlacklistData();
            data.blacklist = new ArrayList<>(blacklistedMods);

            try (Writer writer = new FileWriter(blacklistFile)) {
                gson.toJson(data, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Check for blacklisted mods in a mod list
     * 
     * @param modList The list of mods to check
     * @return List of blacklisted mods found
     */
    public List<String> checkForBlacklistedMods(List<String> modList) {
        List<String> blacklistedFound = new ArrayList<>();
        for (String mod : modList) {
            if (blacklistedMods.contains(mod.toLowerCase())) {
                blacklistedFound.add(mod);
            }
        }
        return blacklistedFound;
    }

    /**
     * Add a mod to the blacklist
     * 
     * @param modId The mod ID to add
     * @return true if the mod was added, false if it was already blacklisted
     */
    public boolean addBlacklistedMod(String modId) {
        boolean added = blacklistedMods.add(modId.toLowerCase());
        if (added) {
            saveBlacklist();
        }
        return added;
    }

    /**
     * Remove a mod from the blacklist
     * 
     * @param modId The mod ID to remove
     * @return true if the mod was removed, false if it wasn't in the blacklist
     */
    public boolean removeBlacklistedMod(String modId) {
        boolean removed = blacklistedMods.remove(modId.toLowerCase());
        if (removed) {
            saveBlacklist();
        }
        return removed;
    }

    /**
     * Get the current blacklist
     * 
     * @return Set of blacklisted mod IDs
     */
    public Set<String> getBlacklistedMods() {
        return new HashSet<>(blacklistedMods);
    }

    /**
     * Check if a mod is blacklisted
     * 
     * @param modId The mod ID to check
     * @return true if the mod is blacklisted, false otherwise
     */
    public boolean isModBlacklisted(String modId) {
        return blacklistedMods.contains(modId.toLowerCase());
    }
}
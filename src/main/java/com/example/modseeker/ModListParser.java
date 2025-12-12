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

import java.util.ArrayList;
import java.util.List;

/**
 * Handles parsing of mod lists from JSON messages
 */
public class ModListParser {

    /**
     * Parse mod list from response message using string parsing
     * Returns only mod IDs (strips version info)
     * 
     * @param messageJson The JSON message containing the mod list
     * @return List of mod IDs
     */
    public List<String> parseModList(String messageJson) {
        return parseModListInternal(messageJson, false);
    }

    /**
     * Parse raw mod list from response message (keeping version info)
     * Used for signature verification
     * 
     * @param messageJson The JSON message containing the mod list
     * @return List of raw mod entries (e.g. "modid:version")
     */
    public List<String> parseRawModList(String messageJson) {
        return parseModListInternal(messageJson, true);
    }

    private List<String> parseModListInternal(String messageJson, boolean keepRaw) {
        List<String> modList = new ArrayList<>();
        try {
            // Look for the mods array in the JSON
            int modsStart = messageJson.indexOf("\"mods\":[");
            if (modsStart == -1) {
                return modList; // No mods array found
            }

            int modsEnd = messageJson.indexOf("]", modsStart);
            if (modsEnd == -1) {
                return modList; // Malformed JSON
            }

            // Extract the content between the brackets
            String modsContent = messageJson.substring(modsStart + 8, modsEnd); // +8 to skip "\"mods\":["

            // Handle empty array
            if (modsContent.trim().isEmpty()) {
                return modList;
            }

            // Remove surrounding quotes if present and split by ","
            modsContent = modsContent.trim();

            // Handle the case where we have multiple mods
            if (modsContent.startsWith("\"") && modsContent.endsWith("\"") && modsContent.contains("\",\"")) {
                // Multiple mods case - split by ","
                String[] modEntries = modsContent.split("\",\"");

                // Process each mod entry
                for (int i = 0; i < modEntries.length; i++) {
                    String modEntry = modEntries[i];
                    // Remove quotes
                    if (i == 0 && modEntry.startsWith("\"")) {
                        modEntry = modEntry.substring(1);
                    }
                    if (i == modEntries.length - 1 && modEntry.endsWith("\"")) {
                        modEntry = modEntry.substring(0, modEntry.length() - 1);
                    }

                    addModEntry(modList, modEntry, keepRaw);
                }
            } else if (modsContent.startsWith("\"") && modsContent.endsWith("\"")) {
                // Single mod case
                String modEntry = modsContent.substring(1, modsContent.length() - 1);
                addModEntry(modList, modEntry, keepRaw);
            } else if (modsContent.contains("\"")) {
                // Multiple quoted mods - more careful splitting
                // Find all quoted strings
                List<String> quotedStrings = new ArrayList<>();
                int quoteStart = -1;
                for (int i = 0; i < modsContent.length(); i++) {
                    if (modsContent.charAt(i) == '"' && (i == 0 || modsContent.charAt(i - 1) != '\\')) {
                        if (quoteStart == -1) {
                            quoteStart = i;
                        } else {
                            quotedStrings.add(modsContent.substring(quoteStart + 1, i));
                            quoteStart = -1;
                        }
                    }
                }

                // Add entries
                for (String modEntry : quotedStrings) {
                    addModEntry(modList, modEntry, keepRaw);
                }
            }
        } catch (Exception e) {
            // Silent fail - return empty list
        }
        return modList;
    }

    private void addModEntry(List<String> modList, String modEntry, boolean keepRaw) {
        if (modEntry == null || modEntry.isEmpty())
            return;

        if (keepRaw) {
            modList.add(modEntry);
        } else {
            String modId = extractModIdFromEntry(modEntry);
            if (modId != null && !modId.isEmpty()) {
                modList.add(modId);
            }
        }
    }

    /**
     * Helper method to extract modId from a mod entry string (modId:version format)
     * 
     * @param modEntry The mod entry string
     * @return The mod ID (before the colon) or the whole string if no colon
     */
    public String extractModIdFromEntry(String modEntry) {
        try {
            // Split by ":" and take the first part as mod ID
            int colonIndex = modEntry.indexOf(":");
            if (colonIndex != -1) {
                return modEntry.substring(0, colonIndex);
            } else {
                // If no colon, return the whole string
                return modEntry;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract mod version from presence message
     * 
     * @param messageJson The JSON message
     * @return The version string or null if not found
     */
    public String extractModVersion(String messageJson) {
        try {
            // Simple extraction - look for version field
            int versionIndex = messageJson.indexOf("\"version\":\"");
            if (versionIndex != -1) {
                int start = versionIndex + 11; // Length of "\"version\":\""
                int end = messageJson.indexOf("\"", start);
                if (end != -1) {
                    return messageJson.substring(start, end);
                }
            }
        } catch (Exception e) {
            // Silent fail
        }
        return null;
    }
}
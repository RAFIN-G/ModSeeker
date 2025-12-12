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

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Utility helper methods for ModSeeker plugin
 */
public class UtilityHelper {

    private static final String logPrefix = "[ModSeeker] ";

    /**
     * Log mod list with proper formatting
     * 
     * @param playerName                 The name of the player
     * @param mods                       The list of mods to log
     * @param SHOW_MOD_LIST              Whether to show the mod list
     * @param ONE_MOD_PER_LINE           Whether to show one mod per line
     * @param HIGHLIGHT_MODS             Whether to highlight mods
     * @param HIGHLIGHT_BLACKLISTED_MODS Whether to highlight blacklisted mods
     * @param blacklistedMods            The set of blacklisted mods
     */
    public static void logModList(String playerName, List<String> mods, boolean SHOW_MOD_LIST, boolean ONE_MOD_PER_LINE,
            boolean HIGHLIGHT_MODS, boolean HIGHLIGHT_BLACKLISTED_MODS, java.util.Set<String> blacklistedMods) {
        if (!SHOW_MOD_LIST || mods.isEmpty()) {
            return;
        }

        if (ONE_MOD_PER_LINE) {
            // Show one mod per line
            for (String mod : mods) {
                StringBuilder line = new StringBuilder("   ↳ ");
                // Check if this mod is blacklisted and highlighting is enabled
                if (HIGHLIGHT_BLACKLISTED_MODS && blacklistedMods.contains(mod.toLowerCase())) {
                    line.append(ChatColor.DARK_RED).append(mod).append(ChatColor.RESET);
                } else if (HIGHLIGHT_MODS) {
                    line.append(ChatColor.YELLOW).append(mod).append(ChatColor.RESET);
                } else {
                    line.append(mod);
                }
                logInfo(line.toString());
            }
        } else {
            // Format mod list with 5 mods per line
            for (int i = 0; i < mods.size(); i += 5) {
                StringBuilder line = new StringBuilder("   ↳ ");
                int end = Math.min(i + 5, mods.size());
                for (int j = i; j < end; j++) {
                    if (j > i)
                        line.append(", ");
                    // Check if this mod is blacklisted and highlighting is enabled
                    String mod = mods.get(j);
                    if (HIGHLIGHT_BLACKLISTED_MODS && blacklistedMods.contains(mod.toLowerCase())) {
                        line.append(ChatColor.DARK_RED).append(mod).append(ChatColor.RESET);
                    } else if (HIGHLIGHT_MODS) {
                        line.append(ChatColor.YELLOW).append(mod).append(ChatColor.RESET);
                    } else {
                        line.append(mod);
                    }
                }
                logInfo(line.toString());
            }
        }
    }

    /**
     * Simple logging method
     * 
     * @param message The message to log
     */
    public static void logInfo(String message) {
        // Convert ChatColor codes to ANSI color codes for console display
        String consoleMessage = message.replace(ChatColor.BLACK.toString(), "\u001B[30m")
                .replace(ChatColor.DARK_BLUE.toString(), "\u001B[34m")
                .replace(ChatColor.DARK_GREEN.toString(), "\u001B[32m")
                .replace(ChatColor.DARK_AQUA.toString(), "\u001B[36m")
                .replace(ChatColor.DARK_RED.toString(), "\u001B[31m")
                .replace(ChatColor.DARK_PURPLE.toString(), "\u001B[35m")
                .replace(ChatColor.GOLD.toString(), "\u001B[33m")
                .replace(ChatColor.GRAY.toString(), "\u001B[37m")
                .replace(ChatColor.DARK_GRAY.toString(), "\u001B[90m")
                .replace(ChatColor.BLUE.toString(), "\u001B[94m")
                .replace(ChatColor.GREEN.toString(), "\u001B[92m")
                .replace(ChatColor.AQUA.toString(), "\u001B[96m")
                .replace(ChatColor.RED.toString(), "\u001B[91m")
                .replace(ChatColor.LIGHT_PURPLE.toString(), "\u001B[95m")
                .replace(ChatColor.YELLOW.toString(), "\u001B[93m")
                .replace(ChatColor.WHITE.toString(), "\u001B[97m")
                .replace(ChatColor.MAGIC.toString(), "\u001B[5m")
                .replace(ChatColor.BOLD.toString(), "\u001B[1m")
                .replace(ChatColor.STRIKETHROUGH.toString(), "\u001B[9m")
                .replace(ChatColor.UNDERLINE.toString(), "\u001B[4m")
                .replace(ChatColor.ITALIC.toString(), "\u001B[3m")
                .replace(ChatColor.RESET.toString(), "\u001B[0m");

        org.bukkit.Bukkit.getLogger().info(logPrefix + consoleMessage + "\u001B[0m"); // Reset at the end
    }

    /**
     * Get default kick message based on key
     * 
     * @param key The key for the kick message
     * @return The default kick message
     */
    public static String getDefaultKickMessage(String key) {
        switch (key) {
            case "missingGCOptimizer":
                return "Please Install GCOptimizer Mod To Enter The Server";
            case "blacklistedMods":
                return "Please Remove {mods} Illegal Mod{plural} To Join The Server";
            case "modlistTimeout":
                return "Player Verification Failed";
            case "modlistRequestFailed":
                return "Player verification failed - unable to send mod list request.";
            case "modCountExceeded":
                return "You have too many mods installed. Maximum allowed: {maxMods}";
            default:
                return "Access denied";
        }
    }
}
// SPDX-FileCopyrightText: 2025 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.logging;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;




public class UtilityHelper {

    private static final String logPrefix = "[ModSeeker] ";












    public static void logModList(String playerName, List<String> mods, boolean SHOW_MOD_LIST, boolean ONE_MOD_PER_LINE,
            boolean HIGHLIGHT_MODS, boolean HIGHLIGHT_BLACKLISTED_MODS, java.util.Set<String> blacklistedMods) {
        if (!SHOW_MOD_LIST || mods.isEmpty()) {
            return;
        }

        if (ONE_MOD_PER_LINE) {

            for (String mod : mods) {
                StringBuilder line = new StringBuilder("   ↳ ");

                if (HIGHLIGHT_BLACKLISTED_MODS && blacklistedMods.contains(mod.toLowerCase(Locale.ROOT))) {
                    line.append(ChatColor.DARK_RED).append(mod).append(ChatColor.RESET);
                } else if (HIGHLIGHT_MODS) {
                    line.append(ChatColor.YELLOW).append(mod).append(ChatColor.RESET);
                } else {
                    line.append(mod);
                }
                logInfo(line.toString());
            }
        } else {

            for (int i = 0; i < mods.size(); i += 5) {
                StringBuilder line = new StringBuilder("   ↳ ");
                int end = Math.min(i + 5, mods.size());
                for (int j = i; j < end; j++) {
                    if (j > i)
                        line.append(", ");

                    String mod = mods.get(j);
                    if (HIGHLIGHT_BLACKLISTED_MODS && blacklistedMods.contains(mod.toLowerCase(Locale.ROOT))) {
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






    public static void logInfo(String message) {

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

        org.bukkit.Bukkit.getLogger().info(logPrefix + consoleMessage + "\u001B[0m");
    }







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

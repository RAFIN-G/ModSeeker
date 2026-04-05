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

import org.bukkit.entity.Player;

public class LauncherVerifier {

    private final ModSeekerPlugin plugin;
    private final LauncherListManager launcherListManager;
    private final ConfigManager configManager;

    public LauncherVerifier(ModSeekerPlugin plugin, LauncherListManager launcherListManager,
            ConfigManager configManager) {
        this.plugin = plugin;
        this.launcherListManager = launcherListManager;
        this.configManager = configManager;
    }

    public boolean isLauncherAllowed(String launcherId) {
        if (launcherId == null || launcherId.isEmpty()) {
            return configManager.isAllowUnknownClients();
        }

        if (launcherId.equalsIgnoreCase("unknown")) {
            return configManager.isAllowUnknownClients();
        }

        return launcherListManager.isLauncherEnabled(launcherId);
    }

    public String getLauncherName(String launcherId) {
        if (launcherId == null || launcherId.isEmpty()) {
            return "Unknown";
        }

        String name = launcherListManager.getLauncherName(launcherId);
        return name != null ? name : launcherId;
    }

    public void kickForInvalidLauncher(Player player, String launcherId) {
        String launcherName = getLauncherName(launcherId);
        String kickMessage = configManager.getLauncherKickMessage();

        plugin.logInfo("🚫 Layer 1: Blocked " + player.getName() + " - Launcher not allowed: " + launcherName);

        String fullMessage = "§c" + kickMessage + "\n§7Detected: " + launcherName;
        player.kickPlayer(fullMessage);
    }

    public void logLauncherDetected(String playerName, String launcherId) {
        String launcherName = getLauncherName(launcherId);
        plugin.logInfo("🎮 Client/Launcher: " + playerName + " using " + launcherName);
    }
}

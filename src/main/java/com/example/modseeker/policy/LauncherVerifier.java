// SPDX-FileCopyrightText: 2025 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.policy;

import com.example.modseeker.ModSeekerPlugin;
import com.example.modseeker.config.ConfigManager;

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

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

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manages the mod verification process, including checking against blacklists
 * and thresholds.
 */
public class VerificationService {

    private final ModSeekerPlugin plugin;
    private final PlayerDataManager playerDataManager;
    private final MessageHandler messageHandler;
    private final ModListParser modListParser;
    private final ConfigManager configManager;
    private final BlacklistManager blacklistManager;
    private final SecurityManager securityManager;

    public VerificationService(ModSeekerPlugin plugin, PlayerDataManager playerDataManager,
            MessageHandler messageHandler, ModListParser modListParser,
            ConfigManager configManager, BlacklistManager blacklistManager,
            SecurityManager securityManager) {
        this.plugin = plugin;
        this.playerDataManager = playerDataManager;
        this.messageHandler = messageHandler;
        this.modListParser = modListParser;
        this.configManager = configManager;
        this.blacklistManager = blacklistManager;
        this.securityManager = securityManager;
    }

    /**
     * Starts the mod check process after a successful handshake.
     */
    public void startModCheckAfterHandshake(Player player, PlayerDataManager.HandshakeData handshakeData) {
        PlayerDataManager.PlayerModCheckData checkData = new PlayerDataManager.PlayerModCheckData(player.getUniqueId(),
                handshakeData.playerName);
        checkData.handshakeCompleted = true;
        checkData.handshakeData = handshakeData;
        playerDataManager.getPlayerModChecks().put(player.getUniqueId(), checkData);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    messageHandler.sendModListRequest(player, checkData);
                }
            }
        }.runTaskLater(plugin, 20L);
    }

    /**
     * Handles the MODLIST_RESPONSE message from the client.
     */
    public void handleModListResponse(Player player, String messageJson) {
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();

        PlayerDataManager.PlayerModCheckData checkData = playerDataManager.getPlayerModChecks().get(playerId);
        if (checkData == null)
            return;

        if (checkData.timeoutTaskId != -1) {
            Bukkit.getScheduler().cancelTask(checkData.timeoutTaskId);
            checkData.timeoutTaskId = -1;
        }

        checkData.modListReceived = true;
        checkData.modListResponse = messageJson;

        // Verify Signature
        if (securityManager.isValidationEnabled()) {
            try {
                String signature = extractJsonField(messageJson, "signature");
                String timestampStr = extractJsonField(messageJson, "timestamp");

                if (signature == null || timestampStr == null) {
                    plugin.logInfo("🚫 SECURITY: Missing signature/timestamp from " + playerName);
                    player.kickPlayer(ChatColor.RED + "Security verification failed: Missing signature.");
                    return;
                }

                long timestamp = Long.parseLong(timestampStr);
                String checkId = extractJsonField(messageJson, "checkId");
                // Use raw mod list (with versions) for signature verification
                List<String> modList = modListParser.parseRawModList(messageJson);

                StringBuilder dataToVerify = new StringBuilder();
                dataToVerify.append(checkId != null ? checkId : "unknown").append("|");
                for (int i = 0; i < modList.size(); i++) {
                    if (i > 0)
                        dataToVerify.append(",");
                    dataToVerify.append(modList.get(i));
                }

                if (!securityManager.verifySignature(dataToVerify.toString(), signature)) {
                    plugin.logInfo("� SECURITY: Invalid signature from " + playerName);
                    player.kickPlayer(ChatColor.RED + "Security verification failed.");
                    return;
                }

                long currentTime = System.currentTimeMillis();
                long diff = Math.abs(currentTime - timestamp);

                // Increased tolerance to 1 hour (3600000ms) to handle timezone/clock drift
                // issues
                if (diff > 3600000) {
                    plugin.logInfo("🚫 SECURITY: Stale packet from " + playerName + " (Diff: " + diff + "ms)");
                    player.kickPlayer(ChatColor.RED + "Security verification failed: Packet timestamp out of sync.");
                    return;
                }
                plugin.logInfo("✅ Signature verified for " + playerName);
            } catch (Exception e) {
                plugin.logInfo("⚠️ Signature verification error: " + e.getMessage());
            }
        }

        List<String> modList = modListParser.parseModList(messageJson);
        checkData.detectedMods = modList;

        List<String> filteredModList = new ArrayList<>();
        for (String mod : modList) {
            if (!configManager.getModlistFilter().contains(mod)) {
                filteredModList.add(mod);
            }
        }

        plugin.logInfo("📋 Mod list received: " + filteredModList.size() + " mods verified");

        if (configManager.isSHOW_MOD_LIST() && !filteredModList.isEmpty()) {
            UtilityHelper.logModList(playerName, filteredModList, configManager.isSHOW_MOD_LIST(),
                    configManager.isONE_MOD_PER_LINE(), configManager.isHIGHLIGHT_MODS(),
                    configManager.isHIGHLIGHT_BLACKLISTED_MODS(), blacklistManager.getBlacklistedMods());
        }

        List<String> blacklistedDetected = blacklistManager.checkForBlacklistedMods(filteredModList);

        if (!blacklistedDetected.isEmpty()) {
            plugin.logInfo("🚫 Blacklisted mods detected: " + blacklistedDetected.size() + " mods");
            for (String mod : blacklistedDetected) {
                plugin.logInfo("   ↳ " + mod);
            }
            String kickMsg = configManager.getKickMessages().getOrDefault("blacklistedMods",
                    "Blacklisted mods detected");
            String modsList = String.join(", ", blacklistedDetected);
            String plural = blacklistedDetected.size() > 1 ? "s" : "";
            kickMsg = kickMsg.replace("{mods}", modsList).replace("{plural}", plural);

            player.kickPlayer(ChatColor.RED + kickMsg);
            return;
        }

        if (configManager.isEnableModCountThreshold() && filteredModList.size() > configManager.getMaxModCount()) {
            String kickMessage = configManager.getKickMessages().getOrDefault("modCountExceeded", "Too many mods");
            player.kickPlayer(ChatColor.RED + kickMessage);
            return;
        }

        plugin.logInfo("🎉 Player verification complete — access granted");
        playerDataManager.addApprovedPlayer(playerId);

        if (configManager.isEnablePlayerNotifications()) {
            player.sendMessage(ChatColor.GREEN + configManager.getWelcomeMessage());
        }
    }

    public void handleSeekModListResponse(Player player, String messageJson,
            PlayerDataManager.PlayerModCheckData seekCheckData) {
        if (seekCheckData.timeoutTaskId != -1) {
            Bukkit.getScheduler().cancelTask(seekCheckData.timeoutTaskId);
            seekCheckData.timeoutTaskId = -1;
        }

        List<String> modList = modListParser.parseModList(messageJson);
        List<String> filteredModList = new ArrayList<>();
        for (String mod : modList) {
            if (!configManager.getModlistFilter().contains(mod)) {
                filteredModList.add(mod);
            }
        }

        plugin.logInfo("📋 Mod list received: " + filteredModList.size() + " mods verified");
        if (!filteredModList.isEmpty()) {
            plugin.logInfo("   ↳ " + String.join(",", filteredModList));
        }

        String modString = String.join(",", filteredModList);
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.isOp()) {
                onlinePlayer
                        .sendMessage(ChatColor.GREEN + "amount of mod : " + filteredModList.size() + " | " + modString);
            }
        }
    }

    public void cleanupVerificationData(UUID playerId) {
        PlayerDataManager.PlayerModCheckData data = playerDataManager.getPlayerModChecks().remove(playerId);
        if (data != null && data.timeoutTaskId != -1) {
            Bukkit.getScheduler().cancelTask(data.timeoutTaskId);
        }
        playerDataManager.removeApprovedPlayer(playerId);
    }

    private String extractJsonField(String json, String fieldName) {
        try {
            String searchKey = "\"" + fieldName + "\":";
            int startIndex = json.indexOf(searchKey);
            if (startIndex == -1)
                return null;

            startIndex += searchKey.length();
            while (startIndex < json.length() && Character.isWhitespace(json.charAt(startIndex))) {
                startIndex++;
            }

            if (json.charAt(startIndex) == '"') {
                startIndex++;
                int endIndex = json.indexOf('"', startIndex);
                return json.substring(startIndex, endIndex);
            } else {
                int endIndex = startIndex;
                while (endIndex < json.length() &&
                        (Character.isDigit(json.charAt(endIndex)) || json.charAt(endIndex) == '.'
                                || json.charAt(endIndex) == '-')) {
                    endIndex++;
                }
                return json.substring(startIndex, endIndex);
            }
        } catch (Exception e) {
            return null;
        }
    }
}

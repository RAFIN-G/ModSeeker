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
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.ChatColor;

import java.util.UUID;

/**
 * Handles network communication and messaging for ModSeeker plugin
 */
public class MessageHandler {

    private static final String PLUGIN_CHANNEL = "modseeker:modlist";
    private static final int MAX_RETRIES = 3;

    private final ModSeekerPlugin plugin;

    public MessageHandler(ModSeekerPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Handle messages from the client using modern networking approach
     */
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!PLUGIN_CHANNEL.equals(channel)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        String playerName = player.getName();

        try {
            String messageJson = new String(message, "UTF-8");

            if (messageJson.contains("\"messageType\":\"ANNOUNCE_PRESENCE\"")) {
                plugin.handleAnnouncePresence(player, messageJson);
            } else if (messageJson.contains("\"messageType\":\"RESPONSE_MODLIST\"")) {
                plugin.handleModListResponse(player, messageJson);
            } else if (messageJson.contains("\"messageType\":\"RESPONSE_MODLIST_ENCRYPTED\"")) {
                plugin.getSecurityManager().handleEncryptedResponse(player, messageJson);
            }

        } catch (Exception e) {
            plugin.logInfo("❌ MESSAGE PARSING ERROR for player " + playerName + ": " + e.getMessage());
            player.kickPlayer(ChatColor.RED + "Invalid message format.");
        }
    }

    public void sendHandshakeRequest(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerDataManager.HandshakeData handshakeData = plugin.getPlayerDataManager().getPlayerHandshakes()
                .get(playerId);
        if (handshakeData == null) {
            return;
        }

        handshakeData.retryCount++;
        if (handshakeData.retryCount > MAX_RETRIES) {
            plugin.logInfo("❌ Handshake failed for " + player.getName() + " (UUID: " + playerId.toString() + ")");
            player.kickPlayer(plugin.getKickMessages().get("modlistRequestFailed"));
            return;
        }

        player.sendPluginMessage(plugin, PLUGIN_CHANNEL, new byte[0]);

        handshakeData.timeoutTaskId = new BukkitRunnable() {
            @Override
            public void run() {
                if (plugin.getPlayerDataManager().getPlayerHandshakes().containsKey(playerId)) {
                    plugin.logInfo(
                            "❌ Handshake timeout for " + player.getName() + " (UUID: " + playerId.toString() + ")");
                    player.kickPlayer(plugin.getKickMessages().get("modlistRequestFailed"));
                }
            }
        }.runTaskLater(plugin, plugin.getHANDSHAKE_TIMEOUT_SECONDS() * 20L).getTaskId();
    }

    public void sendModListRequest(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerDataManager.PlayerModCheckData checkData = plugin.getPlayerDataManager().getPlayerModChecks()
                .get(playerId);
        if (checkData == null) {
            return;
        }

        checkData.retryCount++;
        if (checkData.retryCount > MAX_RETRIES) {
            plugin.logInfo(
                    "❌ Mod list request failed for " + player.getName() + " (UUID: " + playerId.toString() + ")");
            player.kickPlayer(plugin.getKickMessages().get("modlistRequestFailed"));
            return;
        }

        player.sendPluginMessage(plugin, PLUGIN_CHANNEL, new byte[0]);

        checkData.timeoutTaskId = new BukkitRunnable() {
            @Override
            public void run() {
                if (plugin.getPlayerDataManager().getPlayerModChecks().containsKey(playerId)) {
                    plugin.logInfo(
                            "❌ Mod list timeout for " + player.getName() + " (UUID: " + playerId.toString() + ")");
                    player.kickPlayer(plugin.getKickMessages().get("modlistTimeout"));
                }
            }
        }.runTaskLater(plugin, plugin.getTIMEOUT_SECONDS() * 20L).getTaskId();
    }

    public void sendModListRequest(Player player, PlayerDataManager.PlayerModCheckData checkData) {
        try {
            checkData.attemptCount++;
            checkData.lastRequestTime = System.currentTimeMillis();

            checkData.lastRequestTime = System.currentTimeMillis();
            String requestJson = "{\"messageType\":\"REQUEST_MODLIST\",\"checkId\":\"" + checkData.checkId + "\"}";
            sendPluginMessage(player, requestJson);
            startModCheckTimeoutTimer(player, checkData);

        } catch (Exception e) {
            plugin.logInfo("❌ FAILED TO SEND MODLIST REQUEST to " + player.getName() + ": " + e.getMessage());
            // Retry or handle failure
            handleRequestFailure(player, checkData);
        }
    }

    public void sendPluginMessage(Player player, String messageJson) {
        try {
            // Convert the JSON message to bytes directly
            byte[] messageData = messageJson.getBytes("UTF-8");

            // Send the message using the proper channel
            player.sendPluginMessage(plugin, PLUGIN_CHANNEL, messageData);

        } catch (Exception e) {
            plugin.logInfo("❌ FAILED TO SEND PLUGIN MESSAGE to " + player.getName() + ": " + e.getMessage());
            throw new RuntimeException("Failed to send plugin message", e);
        }
    }

    private void startModCheckTimeoutTimer(Player player, PlayerDataManager.PlayerModCheckData checkData) {
        BukkitRunnable timeoutTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!checkData.modListReceived && player.isOnline()) {
                    handleModCheckTimeout(player, checkData);
                }
            }
        };

        checkData.timeoutTaskId = timeoutTask.runTaskLater(plugin, plugin.getTIMEOUT_SECONDS() * 20L).getTaskId();
    }

    private void handleModCheckTimeout(Player player, PlayerDataManager.PlayerModCheckData checkData) {
        // Check if this is a seek request by looking in the seekRequests map
        UUID playerId = player.getUniqueId();
        PlayerDataManager.PlayerModCheckData seekCheckData = plugin.getSeekRequests().get(playerId);

        if (seekCheckData != null && seekCheckData.checkId.equals(checkData.checkId)) {
            // This is a seek request, handle it differently
            if (checkData.attemptCount < MAX_RETRIES) {
                // Retry
                plugin.logInfo("⏰ TIMEOUT for player " + checkData.playerName + " - retrying seek modlist request (#"
                        + (checkData.attemptCount + 1) + ")");
                sendModListRequest(player, checkData);
            } else {
                // Final timeout for seek request - just log and remove, don't kick
                plugin.logInfo("⚠️ Seek request timeout for player " + checkData.playerName);
                plugin.getSeekRequests().remove(playerId);
            }
            return;
        }

        // Otherwise, this is a regular verification request
        if (checkData.attemptCount < MAX_RETRIES) {
            // Retry
            plugin.logInfo("⏰ TIMEOUT for player " + checkData.playerName + " - retrying modlist request (#"
                    + (checkData.attemptCount + 1) + ")");
            sendModListRequest(player, checkData);
        } else {
            // Final timeout - kick player
            plugin.logInfo("⚠️ Hidder failed to send modlist");
            plugin.logInfo("❌Player verification incomplete — access denied");
            plugin.logInfo(
                    "🦵 " + checkData.playerName + " has been kicked from the server for failing to send modlist");
            String kickMessage = plugin.getKickMessages().getOrDefault("modlistTimeout", "Player Verification Failed");
            player.kickPlayer(ChatColor.RED + kickMessage);
        }
    }

    private void handleRequestFailure(Player player, PlayerDataManager.PlayerModCheckData checkData) {
        // Check if this is a seek request by looking in the seekRequests map
        UUID playerId = player.getUniqueId();
        PlayerDataManager.PlayerModCheckData seekCheckData = plugin.getSeekRequests().get(playerId);

        if (seekCheckData != null && seekCheckData.checkId.equals(checkData.checkId)) {
            // This is a seek request, handle it differently
            if (checkData.attemptCount < MAX_RETRIES) {
                // Retry
                plugin.logInfo("🔄 RETRYING SEEK MODLIST REQUEST for player " + checkData.playerName + " (#"
                        + (checkData.attemptCount + 1) + ")");
                sendModListRequest(player, checkData);
            } else {
                // Final failure for seek request - just log and remove, don't kick
                plugin.logInfo("❌ SEEK MODLIST REQUEST FAILED for player " + checkData.playerName + " after "
                        + MAX_RETRIES + " attempts");
                plugin.getSeekRequests().remove(playerId);
            }
            return;
        }

        // Otherwise, this is a regular verification request
        if (checkData.attemptCount < MAX_RETRIES) {
            // Retry
            plugin.logInfo("🔄 RETRYING MODLIST REQUEST for player " + checkData.playerName + " (#"
                    + (checkData.attemptCount + 1) + ")");
            sendModListRequest(player, checkData);
        } else {
            // Final failure - kick player
            plugin.logInfo("❌ MODLIST REQUEST FAILED for player " + checkData.playerName + " after " + MAX_RETRIES
                    + " attempts");
            String kickMessage = plugin.getKickMessages().getOrDefault("modlistRequestFailed",
                    "Player verification failed - unable to send mod list request.");
            player.kickPlayer(ChatColor.RED + kickMessage);
        }
    }
}
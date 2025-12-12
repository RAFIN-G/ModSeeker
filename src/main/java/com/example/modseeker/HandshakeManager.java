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

import java.util.UUID;

/**
 * Manages the handshake process between the server and the client mod.
 */
public class HandshakeManager {

    private final ModSeekerPlugin plugin;
    private final PlayerDataManager playerDataManager;
    private final MessageHandler messageHandler;
    private final ModListParser modListParser;
    private final ConfigManager configManager;
    private final VerificationService verificationService;

    public HandshakeManager(ModSeekerPlugin plugin, PlayerDataManager playerDataManager,
            MessageHandler messageHandler, ModListParser modListParser,
            ConfigManager configManager, VerificationService verificationService) {
        this.plugin = plugin;
        this.playerDataManager = playerDataManager;
        this.messageHandler = messageHandler;
        this.modListParser = modListParser;
        this.configManager = configManager;
        this.verificationService = verificationService;
    }

    /**
     * Starts the handshake process for a player.
     * 
     * @param player The player to handshake with.
     */
    public void startHandshake(Player player) {
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();

        PlayerDataManager.HandshakeData handshakeData = new PlayerDataManager.HandshakeData(playerId, playerName);
        playerDataManager.getPlayerHandshakes().put(playerId, handshakeData);

        String playerIP = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "unknown";
        plugin.logInfo("🌍 Player Join: " + playerName + " (UUID: " + playerId.toString() + ", IP: " + playerIP + ")");
        plugin.logInfo("🤝 Handshake started [ID: " + handshakeData.handshakeId + "]");

        messageHandler.sendHandshakeRequest(player);

        startHandshakeTimeoutTimer(player, handshakeData);
    }

    /**
     * Handles the ANNOUNCE_PRESENCE message from the client.
     * 
     * @param player      The player who sent the message.
     * @param messageJson The JSON message content.
     */
    public void handleAnnouncePresence(Player player, String messageJson) {
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        PlayerDataManager.HandshakeData handshakeData = playerDataManager.getPlayerHandshakes().get(playerId);

        if (handshakeData == null) {
            return;
        }

        handshakeData.presenceAnnounced = true;
        handshakeData.presenceMessage = messageJson;

        if (messageJson.contains("\"modId\":\"" + ProtocolConstants.HIDDER_MOD_ID + "\"")) {
            plugin.logInfo("✅ Hidder presence confirmed");

            String version = modListParser.extractModVersion(messageJson);
            if (version != null) {
                handshakeData.modVersion = version;
            }

            sendAcknowledgePresence(player, handshakeData);

        } else {
            // Log invalid mod detection but don't log the full kick sequence
            // That will be handled by the timeout logic
            plugin.logInfo("❌ Invalid mod detected for player " + playerName);
            // Set a flag to indicate that we've detected an invalid mod
            handshakeData.presenceAnnounced = false; // This will trigger the timeout
            // Don't kick here, let the timeout handler do it
        }
    }

    private void sendAcknowledgePresence(Player player, PlayerDataManager.HandshakeData handshakeData) {
        try {
            if (handshakeData.timeoutTaskId != -1) {
                Bukkit.getScheduler().cancelTask(handshakeData.timeoutTaskId);
                handshakeData.timeoutTaskId = -1;
            }

            if (handshakeData.presenceTimeoutTaskId != -1) {
                Bukkit.getScheduler().cancelTask(handshakeData.presenceTimeoutTaskId);
                handshakeData.presenceTimeoutTaskId = -1;
            }

            String ackJson = "{\"messageType\":\"" + ProtocolConstants.MSG_ACKNOWLEDGE_PRESENCE
                    + "\",\"status\":\"ready\",\"serverId\":\"" + ProtocolConstants.PLUGIN_VERSION + "\"}";

            messageHandler.sendPluginMessage(player, ackJson);

            handshakeData.acknowledgmentSent = true;

            verificationService.startModCheckAfterHandshake(player, handshakeData);

        } catch (Exception e) {
            plugin.logInfo("❌ FAILED TO SEND ACKNOWLEDGMENT to " + handshakeData.playerName + ": " + e.getMessage());
            player.kickPlayer(ChatColor.RED + "Handshake failed.");
        }
    }

    private void startHandshakeTimeoutTimer(Player player, PlayerDataManager.HandshakeData handshakeData) {
        BukkitRunnable timeoutTask = new BukkitRunnable() {
            @Override
            public void run() {
                // Check if player is still in handshake process and hasn't announced presence
                if (playerDataManager.getPlayerHandshakes().containsKey(handshakeData.playerId)
                        && !handshakeData.presenceAnnounced) {
                    // Try to get the player object again in case the original reference is invalid
                    Player currentPlayer = Bukkit.getPlayer(handshakeData.playerId);
                    if (currentPlayer != null && currentPlayer.isOnline()) {
                        handleHandshakeTimeout(currentPlayer, handshakeData);
                    } else {
                        handleHandshakeTimeoutOffline(handshakeData);
                    }
                }
            }
        };

        handshakeData.presenceTimeoutTaskId = timeoutTask
                .runTaskLater(plugin, configManager.getHANDSHAKE_TIMEOUT_SECONDS() * 20L).getTaskId();
    }

    private void handleHandshakeTimeoutOffline(PlayerDataManager.HandshakeData handshakeData) {
        // Remove the handshake data to prevent further processing
        playerDataManager.getPlayerHandshakes().remove(handshakeData.playerId);

        // Log handshake timeout scenario
        plugin.logInfo("⚠️ Hidder presence unable to confirmed");
        plugin.logInfo("❌Player verification incomplete — access denied");
        plugin.logInfo("🦵 " + handshakeData.playerName + " has been kicked from the server for not having Hidder");
    }

    private void handleHandshakeTimeout(Player player, PlayerDataManager.HandshakeData handshakeData) {
        // Check if the player is still in the handshake process
        if (!playerDataManager.getPlayerHandshakes().containsKey(handshakeData.playerId)) {
            return; // Player already completed handshake or left the game
        }

        // Remove the handshake data to prevent further processing
        playerDataManager.getPlayerHandshakes().remove(handshakeData.playerId);

        // Log handshake timeout scenario
        plugin.logInfo("⚠️ Hidder presence unable to confirmed");
        plugin.logInfo("❌Player verification incomplete — access denied");
        plugin.logInfo("🦵 " + handshakeData.playerName + " has been kicked from the server for not having Hidder");

        String kickMessage = configManager.getKickMessages().getOrDefault("missingGCOptimizer",
                "Please Install Hidder Mod To Enter The Server");
        player.kickPlayer(ChatColor.RED + kickMessage);
    }

    /**
     * Cleans up handshake data for a player.
     * 
     * @param playerId The UUID of the player.
     * @return The handshake data that was removed, or null if none existed.
     */
    public PlayerDataManager.HandshakeData cleanupHandshakeData(UUID playerId) {
        PlayerDataManager.HandshakeData data = playerDataManager.getPlayerHandshakes().remove(playerId);
        if (data != null) {
            if (data.timeoutTaskId != -1) {
                Bukkit.getScheduler().cancelTask(data.timeoutTaskId);
            }
            if (data.presenceTimeoutTaskId != -1) {
                Bukkit.getScheduler().cancelTask(data.presenceTimeoutTaskId);
            }
        }
        return data;
    }
}

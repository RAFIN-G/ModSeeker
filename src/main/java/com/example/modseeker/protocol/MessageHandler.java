// SPDX-FileCopyrightText: 2025 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.protocol;

import com.example.modseeker.ModSeekerPlugin;
import com.example.modseeker.verification.PlayerDataManager;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.ChatColor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.UUID;




public class MessageHandler {

    private static final String PLUGIN_CHANNEL = "modseeker:modlist";
    public static final int MAX_RETRIES = 3;
    private static final int MAX_MESSAGE_BYTES = 32767;

    private final ModSeekerPlugin plugin;

    public MessageHandler(ModSeekerPlugin plugin) {
        this.plugin = plugin;
    }




    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!PLUGIN_CHANNEL.equals(channel)) {
            return;
        }
        if (plugin.getPlayerDataManager().isVerificationBypassed(player.getUniqueId())) {
            return;
        }

        String playerName = player.getName();

        try {
            if (message == null || message.length == 0 || message.length > MAX_MESSAGE_BYTES) {
                throw new IllegalArgumentException("Message size is invalid");
            }
            String raw = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(message)).toString();
            JsonObject payload = JsonParser.parseString(raw).getAsJsonObject();
            if (!payload.has(ProtocolConstants.KEY_MESSAGE_TYPE)
                    || !payload.get(ProtocolConstants.KEY_MESSAGE_TYPE).isJsonPrimitive()
                    || !payload.get(ProtocolConstants.KEY_MESSAGE_TYPE).getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Message type is missing");
            }
            String type = payload.get(ProtocolConstants.KEY_MESSAGE_TYPE).getAsString();
            switch (type) {
                case "ANNOUNCE_PRESENCE" -> plugin.handleAnnouncePresence(player, raw);
                case "CHALLENGE_RESPONSE" -> plugin.getHandshakeManager()
                        .handleChallengeResponse(player, raw);
                case "RESPONSE_MODLIST_ENCRYPTED" -> plugin.getSecurityManager()
                        .handleEncryptedResponse(player, payload);
                case "RESPONSE_MODLIST" -> throw new IllegalArgumentException("Plaintext mod lists are not accepted");
                default -> throw new IllegalArgumentException("Unknown message type");
            }
        } catch (Exception e) {
            plugin.logInfo("Message rejected for " + playerName + ": " + e.getMessage());
            player.kickPlayer(ChatColor.RED + "Invalid message format.");
        }
    }

    public void sendHandshakeRequest(Player player) {
        UUID playerId = player.getUniqueId();
        if (plugin.getPlayerDataManager().isVerificationBypassed(playerId)) {
            return;
        }
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
    }

    public void sendModListRequest(Player player, PlayerDataManager.PlayerModCheckData checkData) {
        if (plugin.getPlayerDataManager().isVerificationBypassed(player.getUniqueId())) {
            return;
        }
        try {
            checkData.attemptCount++;
            if (checkData.layer3Nonce == null) {
                byte[] nonceBytes = new byte[16];
                new java.security.SecureRandom().nextBytes(nonceBytes);
                StringBuilder sb = new StringBuilder();
                for (byte b : nonceBytes) {
                    sb.append(String.format("%02x", b));
                }
                checkData.layer3Nonce = sb.toString();
            }

            checkData.layer3IssuedNanos = System.nanoTime();
            checkData.layer3Advanced = plugin.getConfigManager().isAdvancedModVerificationEnabled();
            String serverPubKey = plugin.getServerKeyStore().getPublicKeyBase64();
            String requestJson = "{\"messageType\":\"REQUEST_MODLIST\",\"checkId\":\"" + checkData.checkId
                    + "\",\"nonce\":\"" + checkData.layer3Nonce + "\",\"serverPubKey\":\"" + serverPubKey
                    + "\",\"" + ProtocolConstants.KEY_ADVANCED + "\":" + checkData.layer3Advanced + "}";
            sendPluginMessage(player, requestJson);
            startModCheckTimeoutTimer(player, checkData);

        } catch (Exception e) {
            plugin.logInfo("❌ FAILED TO SEND MODLIST REQUEST to " + player.getName() + ": " + e.getMessage());

            handleRequestFailure(player, checkData);
        }
    }

    public void sendPluginMessage(Player player, String messageJson) {
        try {
            byte[] messageData = messageJson.getBytes(StandardCharsets.UTF_8);
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
                if (!plugin.getPlayerDataManager().isVerificationBypassed(player.getUniqueId())
                        && plugin.getPlayerDataManager().isCurrentModCheck(checkData)
                        && !checkData.modListReceived && player.isOnline()) {
                    handleModCheckTimeout(player, checkData);
                }
            }
        };

        checkData.timeoutTaskId = timeoutTask.runTaskLater(plugin, plugin.getTIMEOUT_SECONDS() * 20L).getTaskId();
    }

    private void handleModCheckTimeout(Player player, PlayerDataManager.PlayerModCheckData checkData) {
        if (checkData.attemptCount < MAX_RETRIES) {

            plugin.logInfo("⏰ TIMEOUT for player " + checkData.playerName + " - retrying modlist request (#"
                    + (checkData.attemptCount + 1) + ")");
            sendModListRequest(player, checkData);
        } else {

            plugin.logInfo("⚠️ Hidder failed to send modlist");
            plugin.logInfo("❌Player verification incomplete — access denied");
            plugin.logInfo(
                    "🦵 " + checkData.playerName + " has been kicked from the server for failing to send modlist");
            String kickMessage = plugin.getKickMessages().getOrDefault("modlistTimeout", "Player Verification Failed");
            player.kickPlayer(ChatColor.RED + kickMessage);
        }
    }

    private void handleRequestFailure(Player player, PlayerDataManager.PlayerModCheckData checkData) {
        if (checkData.attemptCount < MAX_RETRIES) {

            plugin.logInfo("🔄 RETRYING MODLIST REQUEST for player " + checkData.playerName + " (#"
                    + (checkData.attemptCount + 1) + ")");
            sendModListRequest(player, checkData);
        } else {

            plugin.logInfo("❌ MODLIST REQUEST FAILED for player " + checkData.playerName + " after " + MAX_RETRIES
                    + " attempts");
            String kickMessage = plugin.getKickMessages().getOrDefault("modlistRequestFailed",
                    "Player verification failed - unable to send mod list request.");
            player.kickPlayer(ChatColor.RED + kickMessage);
        }
    }
}

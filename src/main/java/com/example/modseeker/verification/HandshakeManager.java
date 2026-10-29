// SPDX-FileCopyrightText: 2025 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.verification;

import com.example.modseeker.ModSeekerPlugin;
import com.example.modseeker.config.ConfigManager;
import com.example.modseeker.policy.LauncherVerifier;
import com.example.modseeker.protocol.MessageHandler;
import com.example.modseeker.protocol.ProtocolConstants;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;




public class HandshakeManager {

    private static final Pattern LAUNCHER_ID = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    private final ModSeekerPlugin plugin;
    private final PlayerDataManager playerDataManager;
    private final MessageHandler messageHandler;
    private final ConfigManager configManager;
    private final VerificationService verificationService;
    private final LauncherVerifier launcherVerifier;
    private final SecurityManager securityManager;

    public HandshakeManager(ModSeekerPlugin plugin, PlayerDataManager playerDataManager,
            MessageHandler messageHandler,
            ConfigManager configManager, VerificationService verificationService,
            LauncherVerifier launcherVerifier, SecurityManager securityManager) {
        this.plugin = plugin;
        this.playerDataManager = playerDataManager;
        this.messageHandler = messageHandler;
        this.configManager = configManager;
        this.verificationService = verificationService;
        this.launcherVerifier = launcherVerifier;
        this.securityManager = securityManager;
    }






    public void startHandshake(Player player) {
        UUID playerId = player.getUniqueId();
        if (playerDataManager.isVerificationBypassed(playerId)) {
            return;
        }
        if (!configManager.isVerificationConfigured()) {
            plugin.logInfo("Verification denied for " + player.getName()
                    + ": ModVerification and launcherVerification are both disabled");
            player.kickPlayer("§cModSeeker verification is disabled. Contact the server administrator.");
            return;
        }
        cleanupHandshakeData(playerId);
        verificationService.cleanupVerificationData(playerId);
        String playerName = player.getName();

        PlayerDataManager.HandshakeData handshakeData = new PlayerDataManager.HandshakeData(playerId, playerName);
        playerDataManager.getPlayerHandshakes().put(playerId, handshakeData);

        String playerIP = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "unknown";
        plugin.logInfo("🌍 Player Join: " + playerName + " (UUID: " + playerId.toString() + ", IP: " + playerIP + ")");
        plugin.logInfo("🤝 Handshake started [ID: " + handshakeData.handshakeId + "]");


        plugin.getDiscordWebhook().sendPlayerJoin(playerName, playerId.toString(), playerIP);

        messageHandler.sendHandshakeRequest(player);

        startHandshakeTimeoutTimer(player, handshakeData);
    }







    public void handleAnnouncePresence(Player player, String messageJson) {
        UUID playerId = player.getUniqueId();
        if (playerDataManager.isVerificationBypassed(playerId)) {
            return;
        }
        String playerName = player.getName();
        PlayerDataManager.HandshakeData handshakeData = playerDataManager.getPlayerHandshakes().get(playerId);

        if (handshakeData == null) {
            return;
        }

        PresenceInfo presence;
        try {
            presence = parsePresence(messageJson);
        } catch (Exception e) {
            plugin.logInfo("❌ Invalid presence from " + playerName + ": " + e.getMessage());
            handshakeData.presenceAnnounced = false;
            return;
        }

        if (!ProtocolConstants.HIDDER_MOD_ID.equals(presence.modId())) {
            plugin.logInfo("❌ Invalid mod detected for player " + playerName);
            handshakeData.presenceAnnounced = false;
            return;
        }

        handshakeData.presenceAnnounced = true;
        plugin.logInfo("✅ Hidder presence confirmed");

        if (!presence.protocol().equals(ProtocolConstants.HIDDER_PROTOCOL_VERSION)) {
            plugin.logInfo("❌ Protocol mismatch for " + playerName + " (got=" + presence.protocol()
                    + ", expected=" + ProtocolConstants.HIDDER_PROTOCOL_VERSION + ")");
            player.kickPlayer("§cClient update required.\n§7Please install the latest Hidder mod.");
            handshakeData.presenceAnnounced = false;
            return;
        }

        if (configManager.isLauncherVerificationEnabled()) {
            sendChallenge(player, handshakeData);
        } else {
            sendAcknowledgePresence(player, handshakeData);
        }
    }

    private PresenceInfo parsePresence(String messageJson) throws IOException {
        String messageType = null;
        String modId = null;
        String protocol = null;
        String version = null;
        Set<String> seen = new HashSet<>();
        try (JsonReader reader = new JsonReader(new StringReader(messageJson))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!seen.add(name)) {
                    throw new IllegalArgumentException("Duplicate presence field");
                }
                switch (name) {
                    case ProtocolConstants.KEY_MESSAGE_TYPE -> messageType = nextPresenceString(reader);
                    case ProtocolConstants.KEY_MOD_ID -> modId = nextPresenceString(reader);
                    case ProtocolConstants.KEY_PROTOCOL -> protocol = nextPresenceString(reader);
                    case ProtocolConstants.KEY_VERSION -> version = nextPresenceString(reader);
                    case ProtocolConstants.KEY_CAPABILITIES -> readCapabilities(reader);
                    default -> throw new IllegalArgumentException("Unknown presence field");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("Trailing presence data");
            }
        }
        if (!ProtocolConstants.MSG_ANNOUNCE_PRESENCE.equals(messageType) || modId == null || protocol == null) {
            throw new IllegalArgumentException("Invalid presence");
        }
        return new PresenceInfo(modId, protocol, version);
    }

    private static String nextPresenceString(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) {
            throw new IllegalArgumentException("Presence field must be a string");
        }
        return reader.nextString();
    }

    private static void readCapabilities(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            throw new IllegalArgumentException("capabilities must be an array");
        }
        reader.beginArray();
        while (reader.hasNext()) {
            nextPresenceString(reader);
        }
        reader.endArray();
    }

    private record PresenceInfo(String modId, String protocol, String version) {
    }

    private void sendAcknowledgePresence(Player player, PlayerDataManager.HandshakeData handshakeData) {
        if (!playerDataManager.isCurrentHandshake(handshakeData) || handshakeData.acknowledgmentSent) {
            return;
        }
        if (!configManager.isVerificationConfigured()) {
            plugin.logInfo("Verification denied for " + handshakeData.playerName
                    + ": ModVerification and launcherVerification are both disabled");
            player.kickPlayer("§cModSeeker verification is disabled. Contact the server administrator.");
            return;
        }
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


            if (configManager.isModVerificationEnabled()) {
                verificationService.startModCheckAfterHandshake(player, handshakeData);
            } else {

                plugin.logInfo("⚠️ Layer 3 disabled (ModVerification: false) — skipping modlist check");
                playerDataManager.addApprovedPlayer(player.getUniqueId());
                securityManager.unfreezePlayer(player.getUniqueId());
                if (configManager.isEnablePlayerNotifications()) {
                    player.sendMessage(org.bukkit.ChatColor.GREEN + configManager.getWelcomeMessage());
                }
            }

        } catch (Exception e) {
            plugin.logInfo("❌ FAILED TO SEND ACKNOWLEDGMENT to " + handshakeData.playerName + ": " + e.getMessage());
            player.kickPlayer(ChatColor.RED + "Handshake failed.");
        }
    }

    private void startHandshakeTimeoutTimer(Player player, PlayerDataManager.HandshakeData handshakeData) {
        BukkitRunnable timeoutTask = new BukkitRunnable() {
            @Override
            public void run() {

                if (!playerDataManager.isVerificationBypassed(handshakeData.playerId)
                        && playerDataManager.isCurrentHandshake(handshakeData)
                        && !handshakeData.presenceAnnounced) {

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

        playerDataManager.getPlayerHandshakes().remove(handshakeData.playerId, handshakeData);


        plugin.logInfo("⚠️ Hidder presence unable to confirmed");
        plugin.logInfo("❌Player verification incomplete — access denied");
        plugin.logInfo("🦵 " + handshakeData.playerName + " has been kicked from the server for not having Hidder");
    }

    private void handleHandshakeTimeout(Player player, PlayerDataManager.HandshakeData handshakeData) {

        if (!playerDataManager.isCurrentHandshake(handshakeData)) {
            return;
        }


        playerDataManager.getPlayerHandshakes().remove(handshakeData.playerId, handshakeData);


        plugin.logInfo("⚠️ Hidder presence unable to confirmed");
        plugin.logInfo("❌Player verification incomplete — access denied");
        plugin.logInfo("🦵 " + handshakeData.playerName + " has been kicked from the server for not having Hidder");

        String kickMessage = configManager.getKickMessages().getOrDefault("missingHidder",
                "Please install Hidder to join this server.\nhttps://modrinth.com/mod/hidder");
        player.kickPlayer(ChatColor.RED + kickMessage);
    }







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

    public void sendChallenge(Player player, PlayerDataManager.HandshakeData handshakeData) {
        if (playerDataManager.isVerificationBypassed(player.getUniqueId())
                || !playerDataManager.isCurrentHandshake(handshakeData) || handshakeData.challengeIssuedNanos != 0) {
            return;
        }

        if (handshakeData.presenceTimeoutTaskId != -1) {
            Bukkit.getScheduler().cancelTask(handshakeData.presenceTimeoutTaskId);
            handshakeData.presenceTimeoutTaskId = -1;
        }
        if (handshakeData.timeoutTaskId != -1) {
            Bukkit.getScheduler().cancelTask(handshakeData.timeoutTaskId);
            handshakeData.timeoutTaskId = -1;
        }

        handshakeData.challengeIssuedNanos = System.nanoTime();
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !playerDataManager.isCurrentHandshake(handshakeData)
                        || handshakeData.challengeNonce != null
                        || challengeExpired(handshakeData.challengeIssuedNanos, System.nanoTime(),
                                configManager.getHANDSHAKE_TIMEOUT_SECONDS())) {
                    cancel();
                    return;
                }
                if (!player.getListeningPluginChannels().contains(ProtocolConstants.PLUGIN_CHANNEL)) return;
                cancel();

                String nonce = generateNonce();
                long timestamp = System.currentTimeMillis();

                handshakeData.challengeNonce = nonce;
                handshakeData.challengeTimestamp = timestamp;

                String challengeJson = "{\"messageType\":\"" + ProtocolConstants.MSG_CHALLENGE +
                        "\",\"nonce\":\"" + nonce + "\",\"timestamp\":" + timestamp + "}";

                messageHandler.sendPluginMessage(player, challengeJson);
            }
        }.runTaskTimer(plugin, 1L, 1L);


        BukkitRunnable challengeTimeout = new BukkitRunnable() {
            @Override
            public void run() {
                if (!playerDataManager.isVerificationBypassed(handshakeData.playerId)
                        && playerDataManager.isCurrentHandshake(handshakeData)
                        && !handshakeData.challengePassed) {
                    Player currentPlayer = Bukkit.getPlayer(handshakeData.playerId);
                    if (currentPlayer != null && currentPlayer.isOnline()) {
                        currentPlayer.kickPlayer("§cLauncher verification timed out. Please try again.");
                    }
                    playerDataManager.getPlayerHandshakes().remove(handshakeData.playerId, handshakeData);
                }
            }
        };
        handshakeData.timeoutTaskId = challengeTimeout
                .runTaskLater(plugin, configManager.getHANDSHAKE_TIMEOUT_SECONDS() * 20L).getTaskId();
    }

    public void handleChallengeResponse(Player player, String messageJson) {
        UUID playerId = player.getUniqueId();
        if (playerDataManager.isVerificationBypassed(playerId)) {
            return;
        }
        PlayerDataManager.HandshakeData handshakeData = playerDataManager.getPlayerHandshakes().get(playerId);

        if (handshakeData == null) {
            return;
        }
        if (!handshakeData.challengeResponseClaimed.compareAndSet(false, true)) {
            return;
        }
        if (handshakeData.challengeNonce == null || handshakeData.challengeTimestamp <= 0
                || handshakeData.challengeIssuedNanos == 0) {
            player.kickPlayer("§cUnexpected challenge response.");
            return;
        }

        try {
            LayerOneResponse response = parseLayerOneResponse(messageJson);
            String signature = response.signature();
            String launcherId = response.launcherId();

            if (challengeExpired(handshakeData.challengeIssuedNanos, System.nanoTime(),
                    configManager.getHANDSHAKE_TIMEOUT_SECONDS())) {
                player.kickPlayer("§cChallenge response too slow. Please try again.");
                return;
            }

            String expectedData = ProtocolConstants.LAYER_1_DOMAIN + handshakeData.challengeNonce + "|"
                    + handshakeData.challengeTimestamp + "|" + launcherId;


            if (signature != null) {
                if (signature.equals("DLL_NOT_LOADED")) {
                    plugin.logInfo("❌ Layer 1: Client " + handshakeData.playerName + " native DLL not loaded");
                    player.kickPlayer("§cSecurity Error: Mod installation incomplete.\n§7Reinstall the mod pack.");
                    return;
                }
                if (signature.equals("CLIENT_ERROR")) {
                    plugin.logInfo("❌ Layer 1: Client error for " + handshakeData.playerName);
                    player.kickPlayer("§cSecurity Error: Client error.\n§7Check client logs.");
                    return;
                }
                if (signature.startsWith("ERROR_")) {
                    plugin.logInfo("Layer 1 native verification failed for " + handshakeData.playerName);
                    player.kickPlayer("§cSecurity Error: Client verification failed.");
                    return;
                }
            }

            if (!securityManager.verifySignature(expectedData, signature)) {
                plugin.logInfo("❌ Layer 1: Invalid signature from " + handshakeData.playerName);
                player.kickPlayer("§cInvalid client verification.");
                return;
            }

            launcherVerifier.logLauncherDetected(handshakeData.playerName, launcherId);

            if (!launcherVerifier.isLauncherAllowed(launcherId)) {
                launcherVerifier.kickForInvalidLauncher(player, launcherId);
                return;
            }

            handshakeData.challengePassed = true;

            sendAcknowledgePresence(player, handshakeData);

        } catch (Exception e) {
            plugin.logInfo("❌ Layer 1: Error processing challenge response: " + e.getMessage());
            player.kickPlayer("§cVerification failed.");
        }
    }

    static boolean challengeExpired(long issuedNanos, long nowNanos, int timeoutSeconds) {
        return nowNanos - issuedNanos > (long) timeoutSeconds * 1_000_000_000L;
    }

    private LayerOneResponse parseLayerOneResponse(String messageJson) throws IOException {
        String messageType = null;
        String signature = null;
        String launcherId = null;
        Set<String> seen = new HashSet<>();
        try (JsonReader reader = new JsonReader(new StringReader(messageJson))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!seen.add(name) || reader.peek() != JsonToken.STRING) {
                    throw new IllegalArgumentException("Invalid or duplicate challenge response field");
                }
                switch (name) {
                    case ProtocolConstants.KEY_MESSAGE_TYPE -> messageType = reader.nextString();
                    case ProtocolConstants.KEY_SIGNATURE -> signature = reader.nextString();
                    case ProtocolConstants.KEY_LAUNCHER_ID -> launcherId = reader.nextString();
                    default -> throw new IllegalArgumentException("Unknown challenge response field");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("Trailing challenge response data");
            }
        }
        if (!ProtocolConstants.MSG_CHALLENGE_RESPONSE.equals(messageType)
                || signature == null || signature.isEmpty() || signature.length() > 512
                || launcherId == null || !LAUNCHER_ID.matcher(launcherId).matches()) {
            throw new IllegalArgumentException("Invalid challenge response");
        }
        return new LayerOneResponse(signature, launcherId);
    }

    private record LayerOneResponse(String signature, String launcherId) {
    }

    private String generateNonce() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

}

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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.ChatColor;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Arrays;
import java.util.stream.Collectors;

public class ModSeekerPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final int MAX_RETRIES = 3;

    private int TIMEOUT_SECONDS = 15;
    private int HANDSHAKE_TIMEOUT_SECONDS = 10;

    private final Map<UUID, PlayerDataManager.PlayerModCheckData> seekRequests = new ConcurrentHashMap<>();

    private PlayerDataManager playerDataManager;
    private MessageHandler messageHandler;
    private ModListParser modListParser;
    private ConfigManager configManager;
    private BlacklistManager blacklistManager;
    private WhitelistManager whitelistManager;
    private CommandHandler commandHandler;
    private HandshakeManager handshakeManager;
    private VerificationService verificationService;
    private SecurityManager securityManager;

    // Simple logging prefix
    private final String logPrefix = "[ModSeeker] ";

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        playerDataManager = new PlayerDataManager();
        configManager = new ConfigManager(getDataFolder());
        blacklistManager = new BlacklistManager(getDataFolder());
        whitelistManager = new WhitelistManager(getDataFolder());

        loadConfig();

        logInfo("🚀 ModSeeker " + ProtocolConstants.PLUGIN_VERSION + " enabled");

        logInfo("⏱️ Timeouts → Handshake: " + HANDSHAKE_TIMEOUT_SECONDS + "s | Modlist: " + TIMEOUT_SECONDS + "s");

        // Initialize message handler
        messageHandler = new MessageHandler(this);

        // Initialize mod list parser
        modListParser = new ModListParser();

        // Initialize services
        securityManager = new SecurityManager(this);
        verificationService = new VerificationService(this, playerDataManager, messageHandler, modListParser,
                configManager, blacklistManager, securityManager);
        handshakeManager = new HandshakeManager(this, playerDataManager, messageHandler, modListParser, configManager,
                verificationService);

        // Initialize command handler
        commandHandler = new CommandHandler(this, blacklistManager, configManager, whitelistManager);

        // Register events
        getServer().getPluginManager().registerEvents(this, this);
        logInfo("📂 Blacklist loaded | ✅ Event listeners active");

        // Register plugin messaging channel
        getServer().getMessenger().registerIncomingPluginChannel(this, ProtocolConstants.PLUGIN_CHANNEL,
                (channel, player, message) -> messageHandler.onPluginMessageReceived(channel, player, message));
        getServer().getMessenger().registerOutgoingPluginChannel(this, ProtocolConstants.PLUGIN_CHANNEL);
        logInfo("🔌 Messaging → Outgoing: 1 | Incoming: 1");
        logInfo("   ↳ " + ProtocolConstants.PLUGIN_CHANNEL);

        // Register commands
        this.getCommand("modseeker").setExecutor(commandHandler);
        this.getCommand("modseeker").setTabCompleter(commandHandler);

        logInfo("✅ Initialization complete — ModSeeker is ready!");
        logInfo("===== MODSEEKER " + ProtocolConstants.PLUGIN_VERSION + " READY =====");
    }

    @Override
    public void onDisable() {
        logInfo("🛑 ModSeeker " + ProtocolConstants.PLUGIN_VERSION + " disabled");

        // Clean up any ongoing tasks
        for (PlayerDataManager.PlayerModCheckData checkData : playerDataManager.getPlayerModChecks().values()) {
            if (checkData.timeoutTaskId != -1) {
                Bukkit.getScheduler().cancelTask(checkData.timeoutTaskId);
            }
        }

        // Clean up handshake timeout tasks
        for (PlayerDataManager.HandshakeData handshakeData : playerDataManager.getPlayerHandshakes().values()) {
            if (handshakeData.timeoutTaskId != -1) {
                Bukkit.getScheduler().cancelTask(handshakeData.timeoutTaskId);
            }
            if (handshakeData.presenceTimeoutTaskId != -1) {
                Bukkit.getScheduler().cancelTask(handshakeData.presenceTimeoutTaskId);
            }
        }

        // Clean up seek request timeout tasks
        for (PlayerDataManager.PlayerModCheckData seekData : seekRequests.values()) {
            if (seekData.timeoutTaskId != -1) {
                Bukkit.getScheduler().cancelTask(seekData.timeoutTaskId);
            }
        }
        logInfo("🧹 Cleanup complete — all sessions cleared");
        logInfo("✅ Plugin messaging channels closed");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();

        // Check if player is whitelisted
        if (whitelistManager.isWhitelisted(playerId)) {
            // Log whitelist scenario
            String playerIP = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress()
                    : "unknown";
            logInfo("🌍 Player Join: " + playerName + " (UUID: " + playerId.toString() + ", IP: " + playerIP + ")");
            logInfo("🛡️ " + playerName + " is whitelisted");
            logInfo("✅ " + playerName + " bypassed all verifications — access granted");

            // Add to approved players
            playerDataManager.addApprovedPlayer(playerId);

            // Send welcome message if enabled
            if (configManager.isEnablePlayerNotifications()) {
                player.sendMessage(ChatColor.GREEN + configManager.getWelcomeMessage());
            }
            return; // Skip all verification for whitelisted players
        }

        // Check for Floodgate / Bedrock players if enabled
        if (configManager.isAllowFloodgate()) {
            // Floodgate UUIDs typically have 0 as the most significant bits
            // This is a zero-dependency check for Floodgate players
            if (player.getUniqueId().getMostSignificantBits() == 0) {
                String playerIP = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress()
                        : "unknown";
                logInfo("🌊 Floodgate Player Detected: " + playerName + " (UUID: " + playerId.toString() + ", IP: "
                        + playerIP + ")");
                logInfo("✅ " + playerName + " bypassed checks (Bedrock/Floodgate)");

                // Add to approved players
                playerDataManager.addApprovedPlayer(playerId);

                // Send welcome message if enabled
                if (configManager.isEnablePlayerNotifications()) {
                    player.sendMessage(ChatColor.GREEN + configManager.getWelcomeMessage());
                }
                return;
            }
        }

        // Start handshake process
        handshakeManager.startHandshake(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();

        // Get session info before removing data
        PlayerDataManager.HandshakeData handshakeData = playerDataManager.getPlayerHandshakes().get(playerId);
        String sessionId = "unknown";
        String sessionTime = "0s";

        if (handshakeData != null) {
            sessionId = handshakeData.handshakeId;
            long sessionDuration = System.currentTimeMillis() - handshakeData.startTime;
            // Convert to seconds
            sessionTime = (sessionDuration / 1000) + "s";
        }

        // Log player quit with session info
        logInfo("🚪 Player Quit: " + playerName + " (Session: " + sessionTime + ")");
        logInfo("🧹 Session cleaned up [ID: " + sessionId + "]");

        // Clean up data via managers
        handshakeManager.cleanupHandshakeData(playerId);
        verificationService.cleanupVerificationData(playerId);

        // Clean up seek request data
        PlayerDataManager.PlayerModCheckData seekData = seekRequests.remove(playerId);
        if (seekData != null && seekData.timeoutTaskId != -1) {
            Bukkit.getScheduler().cancelTask(seekData.timeoutTaskId);
        }
    }

    // Method to handle announce presence message - Delegated to HandshakeManager
    public void handleAnnouncePresence(Player player, String messageJson) {
        handshakeManager.handleAnnouncePresence(player, messageJson);
    }

    // Method to handle mod list response - Delegated to VerificationService
    public void handleModListResponse(Player player, String messageJson) {
        UUID playerId = player.getUniqueId();

        // First check if this is a seek request
        PlayerDataManager.PlayerModCheckData seekCheckData = seekRequests.get(playerId);
        if (seekCheckData != null) {
            // This is a seek request, handle it separately
            verificationService.handleSeekModListResponse(player, messageJson, seekCheckData);
            // Remove the seek request data after handling
            seekRequests.remove(playerId);
            return;
        }

        // Otherwise, this is a regular verification request
        verificationService.handleModListResponse(player, messageJson);
    }

    // Method to load configuration
    private void loadConfig() {
        configManager.loadConfig();

        // Update local values from config manager
        TIMEOUT_SECONDS = configManager.getTIMEOUT_SECONDS();
        HANDSHAKE_TIMEOUT_SECONDS = configManager.getHANDSHAKE_TIMEOUT_SECONDS();

        // Update whitelist in player data manager
        playerDataManager.setWhitelist(configManager.getWhitelist());
    }

    // Simple logging method
    public void logInfo(String message) {
        UtilityHelper.logInfo(message);
    }

    // Getters for modular access
    public PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    public MessageHandler getMessageHandler() {
        return messageHandler;
    }

    public ModListParser getModListParser() {
        return modListParser;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public BlacklistManager getBlacklistManager() {
        return blacklistManager;
    }

    public WhitelistManager getWhitelistManager() {
        return whitelistManager;
    }

    public HandshakeManager getHandshakeManager() {
        return handshakeManager;
    }

    public VerificationService getVerificationService() {
        return verificationService;
    }

    public SecurityManager getSecurityManager() {
        return securityManager;
    }

    public Map<UUID, PlayerDataManager.PlayerModCheckData> getSeekRequests() {
        return seekRequests;
    }

    public int getTIMEOUT_SECONDS() {
        return TIMEOUT_SECONDS;
    }

    public int getHANDSHAKE_TIMEOUT_SECONDS() {
        return HANDSHAKE_TIMEOUT_SECONDS;
    }

    public Map<String, String> getKickMessages() {
        return configManager.getKickMessages();
    }
}
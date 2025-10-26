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
    
    // Plugin constants
    private static final String PLUGIN_CHANNEL = "modseeker:modlist";
    private static final String HIDDER_MOD_ID = "hidder";
    private static final String PLUGIN_VERSION = "3.0.0";
    private static final int MAX_RETRIES = 3;
    
    // Configurable timeout values (will be loaded from config)
    private int TIMEOUT_SECONDS = 15;
    private int HANDSHAKE_TIMEOUT_SECONDS = 10;
    
    // Player tracking with handshake support
    private final Map<UUID, PlayerDataManager.PlayerModCheckData> playerModChecks = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerDataManager.HandshakeData> playerHandshakes = new ConcurrentHashMap<>(); 
    private final Set<UUID> approvedPlayers = ConcurrentHashMap.newKeySet();
    
    // Separate map for seek requests to avoid interference with verification processes
    private final Map<UUID, PlayerDataManager.PlayerModCheckData> seekRequests = new ConcurrentHashMap<>();
    
    // Managers and handlers
    private PlayerDataManager playerDataManager;
    private MessageHandler messageHandler;
    private ModListParser modListParser;
    private ConfigManager configManager;
    private BlacklistManager blacklistManager;
    private CommandHandler commandHandler;
    
    // Simple logging prefix
    private final String logPrefix = "[ModSeeker] ";
    
    @Override
    public void onEnable() {
        // Create data folder if it doesn't exist
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        
        // Initialize managers
        playerDataManager = new PlayerDataManager();
        configManager = new ConfigManager(getDataFolder());
        blacklistManager = new BlacklistManager(getDataFolder());
        
        // Load configuration
        loadConfig();
        
        logInfo("🚀 ModSeeker " + PLUGIN_VERSION + " enabled");
        logInfo("🎯 Target Fabric: 1.21.4 | Bukkit: " + getServer().getBukkitVersion());
        logInfo("📡 Plugin Channel: " + PLUGIN_CHANNEL);
        logInfo("🔑 Required Mod: " + HIDDER_MOD_ID);
        logInfo("⏱️ Timeouts → Handshake: " + HANDSHAKE_TIMEOUT_SECONDS + "s | Modlist: " + TIMEOUT_SECONDS + "s");
        
        // Initialize message handler
        messageHandler = new MessageHandler(this);
        
        // Initialize mod list parser
        modListParser = new ModListParser();
        
        // Initialize command handler
        commandHandler = new CommandHandler(this, blacklistManager, configManager);
        
        // Register events
        getServer().getPluginManager().registerEvents(this, this);
        logInfo("📂 Blacklist loaded | ✅ Event listeners active");
        
        // Register plugin messaging channel using modern approach
        getServer().getMessenger().registerIncomingPluginChannel(this, PLUGIN_CHANNEL, (channel, player, message) -> messageHandler.onPluginMessageReceived(channel, player, message));
        getServer().getMessenger().registerOutgoingPluginChannel(this, PLUGIN_CHANNEL);
        logInfo("🔌 Messaging → Outgoing: 1 | Incoming: 1");
        logInfo("   ↳ " + PLUGIN_CHANNEL);
        
        // Register commands
        this.getCommand("modseeker").setExecutor(commandHandler);
        this.getCommand("modseeker").setTabCompleter(commandHandler);
        
        logInfo("✅ Initialization complete — ModSeeker is ready!");
        logInfo("===== MODSEEKER " + PLUGIN_VERSION + " READY =====");
    }
    
    @Override
    public void onDisable() {
        logInfo("🛑 ModSeeker " + PLUGIN_VERSION + " disabled");
        // Clean up any ongoing tasks
        for (PlayerDataManager.PlayerModCheckData checkData : playerModChecks.values()) {
            if (checkData.timeoutTaskId != -1) {
                Bukkit.getScheduler().cancelTask(checkData.timeoutTaskId);
            }
        }
        
        // Clean up handshake timeout tasks
        for (PlayerDataManager.HandshakeData handshakeData : playerHandshakes.values()) {
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
        if (configManager.getWhitelist().contains(playerName)) {
            // Log whitelist scenario (Scenario 6)
            String playerIP = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "unknown";
            logInfo("🌍 Player Join: " + playerName + " (UUID: " + playerId.toString() + ", IP: " + playerIP + ")");
            logInfo("🛡️ " + playerName + " is whitelisted");
            logInfo("✅ " + playerName + " bypassed all verifications — access granted");
            
            // Add to approved players
            approvedPlayers.add(playerId);
            
            // Send welcome message if enabled
            if (configManager.isEnablePlayerNotifications()) {
                player.sendMessage(ChatColor.GREEN + configManager.getWelcomeMessage());
            }
            return; // Skip all verification for whitelisted players
        }
        
        // Initialize handshake data (Scenario 1)
        PlayerDataManager.HandshakeData handshakeData = new PlayerDataManager.HandshakeData(playerId, playerName);
        playerHandshakes.put(playerId, handshakeData);
        
        // Log player join with full details (Scenario 1)
        String playerIP = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "unknown";
        logInfo("🌍 Player Join: " + playerName + " (UUID: " + playerId.toString() + ", IP: " + playerIP + ")");
        logInfo("🤝 Handshake started [ID: " + handshakeData.handshakeId + "]");
        
        // Send handshake request
        messageHandler.sendHandshakeRequest(player);
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        
        // Get session info before removing data
        PlayerDataManager.HandshakeData handshakeData = playerHandshakes.get(playerId);
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
        
        // Clean up handshake data
        playerHandshakes.remove(playerId);
        
        // Cancel any pending handshake timeout tasks
        if (handshakeData != null) {
            if (handshakeData.timeoutTaskId != -1) {
                Bukkit.getScheduler().cancelTask(handshakeData.timeoutTaskId);
            }
            if (handshakeData.presenceTimeoutTaskId != -1) {
                Bukkit.getScheduler().cancelTask(handshakeData.presenceTimeoutTaskId);
            }
        }
        
        // Clean up mod check data
        PlayerDataManager.PlayerModCheckData checkData = playerModChecks.remove(playerId);
        if (checkData != null && checkData.timeoutTaskId != -1) {
            Bukkit.getScheduler().cancelTask(checkData.timeoutTaskId);
        }
        
        // Clean up seek request data
        PlayerDataManager.PlayerModCheckData seekData = seekRequests.remove(playerId);
        if (seekData != null && seekData.timeoutTaskId != -1) {
            Bukkit.getScheduler().cancelTask(seekData.timeoutTaskId);
        }
        
        // Remove from approved players
        approvedPlayers.remove(playerId);
    }
    
    // Method to handle announce presence message
    public void handleAnnouncePresence(Player player, String messageJson) {
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        PlayerDataManager.HandshakeData handshakeData = playerHandshakes.get(playerId);
        
        if (handshakeData == null) {
            return;
        }
        
        handshakeData.presenceAnnounced = true;
        handshakeData.presenceMessage = messageJson;
        
        // Validate presence message
        if (messageJson.contains("\"modId\":\"" + HIDDER_MOD_ID + "\"")) {
            logInfo("✅ Hidder presence confirmed");
            
            // Extract version if possible
            String version = modListParser.extractModVersion(messageJson);
            if (version != null) {
                handshakeData.modVersion = version;
            }
            
            // Send acknowledgment
            sendAcknowledgePresence(player, handshakeData);
            
        } else {
            // Log invalid mod detection but don't log the full kick sequence
            // That will be handled by the timeout logic
            logInfo("❌ Invalid mod detected for player " + playerName);
            // Set a flag to indicate that we've detected an invalid mod
            handshakeData.presenceAnnounced = false; // This will trigger the timeout
            // Don't kick here, let the timeout handler do it
        }
    }
    
    // Method to send acknowledge presence message
    private void sendAcknowledgePresence(Player player, PlayerDataManager.HandshakeData handshakeData) {
        try {
            // Cancel the handshake timeout task since handshake is successful
            if (handshakeData.timeoutTaskId != -1) {
                Bukkit.getScheduler().cancelTask(handshakeData.timeoutTaskId);
                handshakeData.timeoutTaskId = -1;
            }
            
            // Cancel the presence timeout task since handshake is successful
            if (handshakeData.presenceTimeoutTaskId != -1) {
                Bukkit.getScheduler().cancelTask(handshakeData.presenceTimeoutTaskId);
                handshakeData.presenceTimeoutTaskId = -1;
            }
            
            // Create acknowledgment message
            String ackJson = "{\"messageType\":\"ACKNOWLEDGE_PRESENCE\",\"status\":\"ready\",\"serverId\":\"" + PLUGIN_VERSION + "\"}";
            
            // Send acknowledgment using the proper format
            messageHandler.sendPluginMessage(player, ackJson);
            
            handshakeData.acknowledgmentSent = true;
            
            // Now we can start the mod check process
            startModCheckAfterHandshake(player, handshakeData);
            
        } catch (Exception e) {
            logInfo("❌ FAILED TO SEND ACKNOWLEDGMENT to " + handshakeData.playerName + ": " + e.getMessage());
            player.kickPlayer(ChatColor.RED + "Handshake failed.");
        }
    }
    
    // Method to start mod check after handshake
    private void startModCheckAfterHandshake(Player player, PlayerDataManager.HandshakeData handshakeData) {
        // Create mod check data
        PlayerDataManager.PlayerModCheckData checkData = new PlayerDataManager.PlayerModCheckData(player.getUniqueId(), handshakeData.playerName);
        checkData.handshakeCompleted = true;
        checkData.handshakeData = handshakeData;
        playerModChecks.put(player.getUniqueId(), checkData);
        
        // Add a small delay before sending the modlist request to ensure client is ready
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    messageHandler.sendModListRequest(player, checkData);
                }
            }
        }.runTaskLater(this, 20L); // 1 second delay
    }
    
    // Method to handle mod list response
    public void handleModListResponse(Player player, String messageJson) {
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        
        // First check if this is a seek request
        PlayerDataManager.PlayerModCheckData seekCheckData = seekRequests.get(playerId);
        if (seekCheckData != null) {
            // This is a seek request, handle it separately
            handleSeekModListResponse(player, messageJson, seekCheckData);
            return;
        }
        
        // Otherwise, this is a regular verification request
        PlayerDataManager.PlayerModCheckData checkData = playerModChecks.get(playerId);
        if (checkData == null) {
            return;
        }
        
        // Cancel timeout task
        if (checkData.timeoutTaskId != -1) {
            Bukkit.getScheduler().cancelTask(checkData.timeoutTaskId);
            checkData.timeoutTaskId = -1;
        }
        
        checkData.modListReceived = true;
        checkData.modListResponse = messageJson;
        
        // Parse mod list
        List<String> modList = modListParser.parseModList(messageJson);
        checkData.detectedMods = modList;
        
        // Apply mod list filter - create filtered list for display
        List<String> filteredModList = new ArrayList<>();
        for (String mod : modList) {
            if (!configManager.getModlistFilter().contains(mod)) {
                filteredModList.add(mod);
            }
        }
        
        // Check if this is a seek command request by checking if handshake was completed
        boolean isSeekRequest = !checkData.handshakeCompleted;
        
        if (isSeekRequest) {
            // This is a seek command request, send results to the command sender
            logInfo("📋 Mod list received (seek request): " + filteredModList.size() + " mods verified");
        } else {
            // This is a regular player join verification
            logInfo("📋 Mod list received: " + filteredModList.size() + " mods verified");
        }
        
        // Enhanced logging for mod list (based on configuration)
        if (configManager.isSHOW_MOD_LIST() && !filteredModList.isEmpty()) {
            UtilityHelper.logModList(playerName, filteredModList, configManager.isSHOW_MOD_LIST(), 
                                   configManager.isONE_MOD_PER_LINE(), configManager.isHIGHLIGHT_MODS(), 
                                   configManager.isHIGHLIGHT_BLACKLISTED_MODS(), blacklistManager.getBlacklistedMods());
        }
        
        // If this is a seek request, send results to command sender
        if (isSeekRequest) {
            // Find online ops and send them the results
            StringBuilder resultMessage = new StringBuilder();
            resultMessage.append(ChatColor.GREEN).append("Mod count - ").append(filteredModList.size()).append(" | Mods - ");
            
            for (int i = 0; i < filteredModList.size(); i++) {
                if (i > 0) resultMessage.append(", ");
                // Check if this mod is blacklisted and highlighting is enabled
                String mod = filteredModList.get(i);
                if (configManager.isHIGHLIGHT_BLACKLISTED_MODS() && blacklistManager.isModBlacklisted(mod)) {
                    resultMessage.append(ChatColor.DARK_RED).append(mod).append(ChatColor.RESET);
                } else if (configManager.isHIGHLIGHT_MODS()) {
                    resultMessage.append(ChatColor.YELLOW).append(mod).append(ChatColor.RESET);
                } else {
                    resultMessage.append(mod);
                }
            }
            
            // Send to all online ops (admins)
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                if (onlinePlayer.isOp()) {
                    onlinePlayer.sendMessage(resultMessage.toString());
                }
            }
            
            // Remove temporary check data for seek requests
            playerModChecks.remove(playerId);
            return;
        }
        
        // Check for blacklisted mods (Scenario 2)
        List<String> blacklistedDetected = blacklistManager.checkForBlacklistedMods(filteredModList);
        
        if (!blacklistedDetected.isEmpty()) {
            // Log blacklisted mods scenario (Scenario 2)
            logInfo("🚫 Blacklisted mods detected: " + blacklistedDetected.size() + " mods");
            for (String mod : blacklistedDetected) {
                logInfo("   ↳ " + mod);
            }
            
            logInfo("❌Player verification incomplete — access denied");
            logInfo("🦵 " + playerName + " has been kicked from the server for having illegal mods installted");
            
            // Kick player with custom message
            String kickMessage = configManager.getKickMessages().getOrDefault("blacklistedMods", "Please Remove {mods} Illegal Mod{plural} To Join The Server");
            String plural = blacklistedDetected.size() > 1 ? "s" : "";
            String mods = String.join(", ", blacklistedDetected);
            kickMessage = kickMessage.replace("{mods}", mods).replace("{plural}", plural);
            player.kickPlayer(ChatColor.RED + kickMessage);
            return;
        }
        
        // Check mod count threshold (Scenario 5)
        if (configManager.isEnableModCountThreshold() && filteredModList.size() > configManager.getMaxModCount()) {
            logInfo("❌Player verification incomplete — access denied");
            logInfo("🦵 " + playerName + " has been kicked from the server for exceeding total mod limit");
            
            // Kick player with custom message
            String kickMessage = configManager.getKickMessages().getOrDefault("modCountExceeded", "You have been kicked from the server for have more then {maxMods} amount of mods");
            kickMessage = kickMessage.replace("{maxMods}", String.valueOf(configManager.getMaxModCount()));
            player.kickPlayer(ChatColor.RED + kickMessage);
            return;
        }
        
        // Log successful verification scenario (Scenario 1)
        logInfo("🎉 Player verification complete — access granted");
        
        // Add to approved players
        approvedPlayers.add(playerId);
        
        // Send welcome message if enabled
        if (configManager.isEnablePlayerNotifications()) {
            player.sendMessage(ChatColor.GREEN + configManager.getWelcomeMessage());
        }
    }
    
    // Method to handle mod list response for seek requests
    private void handleSeekModListResponse(Player player, String messageJson, PlayerDataManager.PlayerModCheckData seekCheckData) {
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        
        // Cancel timeout task for seek request
        if (seekCheckData.timeoutTaskId != -1) {
            Bukkit.getScheduler().cancelTask(seekCheckData.timeoutTaskId);
            seekCheckData.timeoutTaskId = -1;
        }
        
        seekCheckData.modListReceived = true;
        seekCheckData.modListResponse = messageJson;
        
        // Parse mod list
        List<String> modList = modListParser.parseModList(messageJson);
        seekCheckData.detectedMods = modList;
        
        // Apply mod list filter - create filtered list for display
        List<String> filteredModList = new ArrayList<>();
        for (String mod : modList) {
            if (!configManager.getModlistFilter().contains(mod)) {
                filteredModList.add(mod);
            }
        }
        
        // Log that we received the mod list for seek request
        logInfo("📋 Mod list received (seek request): " + filteredModList.size() + " mods verified");
        
        // Enhanced logging for mod list (based on configuration)
        if (configManager.isSHOW_MOD_LIST() && !filteredModList.isEmpty()) {
            UtilityHelper.logModList(playerName, filteredModList, configManager.isSHOW_MOD_LIST(), 
                                   configManager.isONE_MOD_PER_LINE(), configManager.isHIGHLIGHT_MODS(), 
                                   configManager.isHIGHLIGHT_BLACKLISTED_MODS(), blacklistManager.getBlacklistedMods());
        }
        
        // Find online ops and send them the results
        StringBuilder resultMessage = new StringBuilder();
        resultMessage.append(ChatColor.GREEN).append("Mod count - ").append(filteredModList.size()).append(" | Mods - ");
        
        for (int i = 0; i < filteredModList.size(); i++) {
            if (i > 0) resultMessage.append(", ");
            // Check if this mod is blacklisted and highlighting is enabled
            String mod = filteredModList.get(i);
            if (configManager.isHIGHLIGHT_BLACKLISTED_MODS() && blacklistManager.isModBlacklisted(mod)) {
                resultMessage.append(ChatColor.DARK_RED).append(mod).append(ChatColor.RESET);
            } else if (configManager.isHIGHLIGHT_MODS()) {
                resultMessage.append(ChatColor.YELLOW).append(mod).append(ChatColor.RESET);
            } else {
                resultMessage.append(mod);
            }
        }
        
        // Send to all online ops (admins)
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.isOp()) {
                onlinePlayer.sendMessage(resultMessage.toString());
            }
        }
        
        // Remove the seek request data
        seekRequests.remove(playerId);
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
    
    public Map<UUID, PlayerDataManager.PlayerModCheckData> getPlayerModChecks() {
        return playerModChecks;
    }
    
    public Map<UUID, PlayerDataManager.PlayerModCheckData> getSeekRequests() {
        return seekRequests;
    }
    
    public Map<UUID, PlayerDataManager.HandshakeData> getPlayerHandshakes() {
        return playerHandshakes;
    }
    
    public Set<UUID> getApprovedPlayers() {
        return approvedPlayers;
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
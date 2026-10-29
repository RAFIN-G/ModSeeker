// SPDX-FileCopyrightText: 2025 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker;

import com.example.modseeker.catalog.BucketService;
import com.example.modseeker.catalog.GuiModIdCache;
import com.example.modseeker.catalog.HashFetcher;
import com.example.modseeker.catalog.ModCacheManager;
import com.example.modseeker.catalog.ModrinthJarResolver;
import com.example.modseeker.command.CommandHandler;
import com.example.modseeker.config.ConfigManager;
import com.example.modseeker.gui.GuiServer;
import com.example.modseeker.logging.UtilityHelper;
import com.example.modseeker.policy.BlacklistManager;
import com.example.modseeker.policy.FloodgateDetector;
import com.example.modseeker.policy.LauncherListManager;
import com.example.modseeker.policy.LauncherVerifier;
import com.example.modseeker.policy.ModAllowlistManager;
import com.example.modseeker.policy.WhitelistManager;
import com.example.modseeker.protocol.MessageHandler;
import com.example.modseeker.protocol.ProtocolConstants;
import com.example.modseeker.protocol.VerifiedModPayload;
import com.example.modseeker.verification.HandshakeManager;
import com.example.modseeker.verification.PlayerDataManager;
import com.example.modseeker.verification.SecurityManager;
import com.example.modseeker.verification.ServerKeyStore;
import com.example.modseeker.verification.VerificationService;
import com.example.modseeker.webhook.DiscordWebhook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.ChatColor;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ModSeekerPlugin extends JavaPlugin implements Listener {

    private static final String LIFECYCLE_SEPARATOR = "=".repeat(108);
    private int TIMEOUT_SECONDS = 15;
    private int HANDSHAKE_TIMEOUT_SECONDS = 10;

    private PlayerDataManager playerDataManager;
    private MessageHandler messageHandler;
    private ConfigManager configManager;
    private BlacklistManager blacklistManager;
    private ModAllowlistManager allowlistManager;
    private ModCacheManager modCache;
    private WhitelistManager whitelistManager;
    private CommandHandler commandHandler;
    private HandshakeManager handshakeManager;
    private VerificationService verificationService;
    private SecurityManager securityManager;
    private ServerKeyStore serverKeyStore;
    private DiscordWebhook discordWebhook;
    private LauncherListManager launcherListManager;
    private HashFetcher hashFetcher;
    private GuiModIdCache guiModIdCache;
    private ModrinthJarResolver modrinthJarResolver;
    private GuiServer guiServer;
    private BucketService bucketService;
    private FloodgateDetector floodgateDetector;
    private File storageFolder;

    @Override
    public void onEnable() {
        long startupStartedAt = System.nanoTime();
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        if (!ensureStorageDirectory()) {
            failStartup("ModSeeker cannot start because its data directory is unavailable");
            return;
        }

        playerDataManager = new PlayerDataManager();
        configManager = new ConfigManager(getDataFolder(), this::logInfo);
        blacklistManager = new BlacklistManager(storageFolder);
        allowlistManager = new ModAllowlistManager(this);
        modCache = new ModCacheManager(this);
        whitelistManager = new WhitelistManager(this);
        if (whitelistManager.isLoadFailed()) {
            logInfo(ChatColor.YELLOW + "whitelist.json could not be loaded; whitelist bypass is fail-closed");
        }
        bucketService = new BucketService(this);
        if (!bucketService.ensureDirectory()) {
            logInfo(ChatColor.YELLOW + "Could not create the bucket directory");
        }

        applyConfig();


        messageHandler = new MessageHandler(this);


        serverKeyStore = new ServerKeyStore(this);
        if (!serverKeyStore.isReady()) {
            failStartup("ModSeeker cannot start because the server key pair is invalid");
            return;
        }
        try {
            securityManager = new SecurityManager(this, serverKeyStore);
        } catch (IllegalStateException e) {
            failStartup("ModSeeker cannot start: " + e.getMessage());
            return;
        }
        discordWebhook = new DiscordWebhook(configManager);
        hashFetcher = new HashFetcher();
        guiModIdCache = new GuiModIdCache(this);
        modrinthJarResolver = new ModrinthJarResolver(this, guiModIdCache);
        guiServer = new GuiServer(this);
        launcherListManager = new LauncherListManager(getDataFolder());
        floodgateDetector = FloodgateDetector.create(this);
        if (configManager.isAllowFloodgate()) {
            floodgateDetector.warnIfUnavailable();
        }
        LauncherVerifier launcherVerifier = new LauncherVerifier(this, launcherListManager, configManager);
        verificationService = new VerificationService(this, playerDataManager, messageHandler,
                configManager, blacklistManager, securityManager);
        handshakeManager = new HandshakeManager(this, playerDataManager, messageHandler, configManager,
                verificationService, launcherVerifier, securityManager);

        commandHandler = new CommandHandler(this, blacklistManager, configManager, whitelistManager, launcherListManager);

        getServer().getPluginManager().registerEvents(this, this);
        try {
            getServer().getPluginManager().registerEvents(securityManager, this);
        } catch (NoClassDefFoundError e) {
            logInfo("⚠️ Paper-specific features disabled (running on Spigot?)");
            logInfo("   ↳ Core freeze events will still work via manual registration");
        }

        getServer().getMessenger().registerIncomingPluginChannel(this, ProtocolConstants.PLUGIN_CHANNEL,
                (channel, player, message) -> messageHandler.onPluginMessageReceived(channel, player, message));
        getServer().getMessenger().registerOutgoingPluginChannel(this, ProtocolConstants.PLUGIN_CHANNEL);


        this.getCommand("modseeker").setExecutor(commandHandler);
        this.getCommand("modseeker").setTabCompleter(commandHandler);

        logStartupSummary(startupStartedAt);

        populateEssentialsAsync();
    }

    private void failStartup(String reason) {
        getLogger().severe(reason + "; stopping Paper to prevent unverified joins");
        getServer().getPluginManager().disablePlugin(this);
        getServer().shutdown();
    }

    private void logStartupSummary(long startedAt) {
        String verificationMode = configManager.isModVerificationEnabled()
                ? value(configManager.isAdvancedModVerificationEnabled() ? "hash" : "modid")
                : state(false);
        String launcher = state(configManager.isLauncherVerificationEnabled());
        String floodgate = state(configManager.isAllowFloodgate() && floodgateDetector.isAvailable());
        int allowlistSize = allowlistManager.size();
        int blacklistSize = blacklistManager.getBlacklistedMods().size();
        int whitelistSize = whitelistManager.getEntries().size();
        String whitelistLabel = whitelistSize == 1 ? "player" : "players";
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        String tag = bold(ChatColor.AQUA, "[startup]");
        logInfo(styled(ChatColor.DARK_GRAY, LIFECYCLE_SEPARATOR));
        logInfo(tag + label(" Starting ModSeeker ") + value(ProtocolConstants.PLUGIN_VERSION));
        logInfo(tag + label(" Verification: ") + verificationMode + separator(" | ")
                + label("launcher: ") + launcher + separator(" | ") + label("Floodgate bypass: ") + floodgate);
        logInfo(tag + label(" Lists: allowlist: ") + value(allowlistSize + " hashes") + separator(" | ")
                + label("blacklist: ") + value(blacklistSize + " ids") + separator(" | ")
                + label("whitelist: ") + value(whitelistSize + " " + whitelistLabel));
        logInfo(tag + label(" Channel: ") + value(ProtocolConstants.PLUGIN_CHANNEL) + separator(" | ")
                + label("handshake: ") + value(HANDSHAKE_TIMEOUT_SECONDS + "s") + separator(" | ")
                + label("modlist: ") + value(TIMEOUT_SECONDS + "s") + separator(" | ")
                + label("retries: ") + value(MessageHandler.MAX_RETRIES));
        if (configManager.isDiscordEnabled() && !discordWebhook.isActive()) {
            logInfo(bold(ChatColor.YELLOW, "[startup] Discord is enabled but the webhook URL is not a valid HTTPS"
                    + " Discord webhook; notifications are disabled"));
        }
        logInfo(bold(ChatColor.GREEN, "[startup] Ready in " + elapsedMillis + " ms"));
        logInfo(styled(ChatColor.DARK_GRAY, LIFECYCLE_SEPARATOR));
    }

    private static String state(boolean enabled) {
        return bold(enabled ? ChatColor.GREEN : ChatColor.GRAY, enabled ? "enabled" : "disabled");
    }

    private static String label(String text) {
        return styled(ChatColor.GRAY, text);
    }

    private static String separator(String text) {
        return styled(ChatColor.DARK_GRAY, text);
    }

    private static String value(Object value) {
        return bold(ChatColor.WHITE, String.valueOf(value));
    }

    private static String bold(ChatColor color, String text) {
        return color.toString() + ChatColor.BOLD + text + ChatColor.RESET;
    }

    private static String styled(ChatColor color, String text) {
        return color.toString() + text + ChatColor.RESET;
    }

    @Override
    public void onDisable() {
        long shutdownStartedAt = System.nanoTime();
        String tag = bold(ChatColor.GOLD, "[shutdown]");
        logInfo(styled(ChatColor.DARK_GRAY, LIFECYCLE_SEPARATOR));
        logInfo(tag + label(" Stopping ModSeeker ") + value(ProtocolConstants.PLUGIN_VERSION));

        int cancelledChecks = cancelPlayerChecks();
        boolean guiWasRunning = guiServer != null && guiServer.isRunning();
        if (guiServer != null) {
            guiServer.stop();
        }
        boolean webhookWasActive = discordWebhook != null && discordWebhook.isActive();
        if (discordWebhook != null) {
            discordWebhook.shutdown();
        }

        String guiStatus = shutdownState(guiWasRunning);
        String webhookStatus = shutdownState(webhookWasActive);
        long elapsedMillis = (System.nanoTime() - shutdownStartedAt) / 1_000_000L;
        logInfo(tag + label(" Player checks: ") + value(cancelledChecks + " cancelled") + separator(" | ")
                + label("GUI: ") + guiStatus + separator(" | ") + label("webhook: ") + webhookStatus);
        logInfo(bold(ChatColor.GREEN, "[shutdown] Complete in " + elapsedMillis + " ms"));
        logInfo(styled(ChatColor.DARK_GRAY, LIFECYCLE_SEPARATOR));
    }

    private static String shutdownState(boolean wasActive) {
        return bold(wasActive ? ChatColor.GREEN : ChatColor.GRAY, wasActive ? "stopped" : "inactive");
    }

    private int cancelPlayerChecks() {
        if (playerDataManager == null) {
            return 0;
        }
        Set<UUID> cancelled = new HashSet<>();
        for (PlayerDataManager.PlayerModCheckData checkData : playerDataManager.getPlayerModChecks().values()) {
            if (checkData.timeoutTaskId != -1) {
                Bukkit.getScheduler().cancelTask(checkData.timeoutTaskId);
                checkData.timeoutTaskId = -1;
                cancelled.add(checkData.playerId);
            }
        }
        for (PlayerDataManager.HandshakeData handshakeData : playerDataManager.getPlayerHandshakes().values()) {
            if (handshakeData.timeoutTaskId != -1) {
                Bukkit.getScheduler().cancelTask(handshakeData.timeoutTaskId);
                handshakeData.timeoutTaskId = -1;
                cancelled.add(handshakeData.playerId);
            }
            if (handshakeData.presenceTimeoutTaskId != -1) {
                Bukkit.getScheduler().cancelTask(handshakeData.presenceTimeoutTaskId);
                handshakeData.presenceTimeoutTaskId = -1;
                cancelled.add(handshakeData.playerId);
            }
        }
        return cancelled.size();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        playerDataManager.beginSession(playerId);






        if (whitelistManager.isWhitelisted(playerId)) {
            grantBypass(player, PlayerDataManager.BypassReason.WHITELIST);
            return;
        }

        if (configManager.isAllowFloodgate() && floodgateDetector.isFloodgatePlayer(playerId)) {
            grantBypass(player, PlayerDataManager.BypassReason.FLOODGATE);
            return;
        }


        securityManager.freezePlayer(playerId);


        handshakeManager.startHandshake(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();


        PlayerDataManager.HandshakeData handshakeData = playerDataManager.getPlayerHandshakes().get(playerId);
        String sessionId = "unknown";
        String sessionTime = "0s";

        if (handshakeData != null) {
            sessionId = handshakeData.handshakeId;
            long sessionDuration = System.currentTimeMillis() - handshakeData.startTime;

            sessionTime = (sessionDuration / 1000) + "s";
        }


        logInfo("🚪 Player Quit: " + playerName + " (Session: " + sessionTime + ")");
        logInfo("🧹 Session cleaned up [ID: " + sessionId + "]");


        handshakeManager.cleanupHandshakeData(playerId);
        verificationService.cleanupVerificationData(playerId);


        securityManager.unfreezePlayer(playerId);
        playerDataManager.clearBypass(playerId);
    }

    private void grantBypass(Player player, PlayerDataManager.BypassReason reason) {
        grantBypass(player, reason, true);
    }

    private void grantBypass(Player player, PlayerDataManager.BypassReason reason, boolean logJoin) {
        UUID playerId = player.getUniqueId();
        String playerIP = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "unknown";
        playerDataManager.setBypassReason(playerId, reason);
        playerDataManager.addApprovedPlayer(playerId);
        securityManager.unfreezePlayer(playerId);
        if (logJoin) {
            logInfo("Player Join: " + player.getName() + " (UUID: " + playerId + ", IP: " + playerIP + ")");
        }
        logInfo(player.getName() + " bypassed verification via " + reason.name().toLowerCase(Locale.ROOT));
        if (configManager.isEnablePlayerNotifications()) {
            player.sendMessage(ChatColor.GREEN + configManager.getWelcomeMessage());
        }
    }


    public void handleAnnouncePresence(Player player, String messageJson) {
        handshakeManager.handleAnnouncePresence(player, messageJson);
    }

    public void handleVerifiedModPayload(Player player, VerifiedModPayload payload) {
        verificationService.handleVerifiedPayload(player, payload);
    }


    public void reloadConfiguration() {
        configManager.loadConfig();
        applyConfig();
    }

    private void applyConfig() {
        if (!configManager.isVerificationConfigured()) {
            logInfo(ChatColor.RED + "ModVerification and launcherVerification are both disabled;"
                    + " non-bypassed players will be denied until a verification check is enabled");
        }

        TIMEOUT_SECONDS = configManager.getTIMEOUT_SECONDS();
        HANDSHAKE_TIMEOUT_SECONDS = configManager.getHANDSHAKE_TIMEOUT_SECONDS();
    }

    public void activateWhitelistBypass(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline() || !whitelistManager.isWhitelisted(playerId)) {
            return;
        }
        if (playerDataManager.isVerificationBypassed(playerId)) {
            return;
        }
        boolean alreadyApproved = playerDataManager.isPlayerApproved(playerId);
        handshakeManager.cleanupHandshakeData(playerId);
        verificationService.cleanupVerificationData(playerId);
        if (alreadyApproved) {
            playerDataManager.setBypassReason(playerId, PlayerDataManager.BypassReason.WHITELIST);
            playerDataManager.addApprovedPlayer(playerId);
            securityManager.unfreezePlayer(playerId);
            return;
        }
        grantBypass(player, PlayerDataManager.BypassReason.WHITELIST, false);
    }

    public void synchronizeWhitelistBypasses() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (whitelistManager.isWhitelisted(player.getUniqueId())) {
                activateWhitelistBypass(player.getUniqueId());
            }
        }
    }

    public void populateEssentialsAsync() {
        if (!configManager.isAdvancedModVerificationEnabled()) {
            return;
        }
        String minecraftVersion = getServer().getMinecraftVersion();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            final HashFetcher.EssentialFetchResult fetched = hashFetcher.fetchEssentials(minecraftVersion);
            getServer().getScheduler().runTask(this, () -> {
                if (!fetched.ok()) {
                    logInfo(ChatColor.YELLOW + "Could not refresh essential mod hashes for Minecraft "
                            + minecraftVersion + ": " + fetched.error());
                    return;
                }
                ModAllowlistManager.BatchResult result = allowlistManager.replaceEssentialEntries(
                        HashFetcher.essentialModIds(), fetched.entries());
                if (!result.success()) {
                    logInfo(ChatColor.YELLOW + "Could not update essential mod hashes for Minecraft "
                            + minecraftVersion + ": " + result.error());
                } else if (result.changed() > 0) {
                    logInfo(ChatColor.YELLOW + "Synchronized " + fetched.entries().size()
                            + " essential mod hashes for Minecraft " + minecraftVersion);
                }
            });
        });
    }

    public boolean ensureStorageDirectory() {
        storageFolder = new File(getDataFolder(), "data");
        try {
            Files.createDirectories(storageFolder.toPath());
            for (String name : List.of("modallowlist.json", "modblacklist.json", "seek_cache.json",
                    "whitelist.json", "gui_modid_cache.json")) {
                migrateDataFile(name);
            }
            return true;
        } catch (IOException e) {
            logInfo(ChatColor.YELLOW + "Could not prepare the ModSeeker data directory: " + e.getMessage());
            return false;
        }
    }

    private void migrateDataFile(String name) throws IOException {
        Path source = getDataFolder().toPath().resolve(name);
        Path target = storageFolder.toPath().resolve(name);
        if (!Files.exists(source)) {
            return;
        }
        if (Files.exists(target)) {
            logInfo(ChatColor.YELLOW + "Legacy " + name + " remains in the plugin root because data/" + name
                    + " already exists");
            return;
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
        logInfo(ChatColor.YELLOW + "Moved " + name + " to data/" + name);
    }


    public void logInfo(String message) {
        UtilityHelper.logInfo(message);
    }


    public PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    public MessageHandler getMessageHandler() {
        return messageHandler;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public BlacklistManager getBlacklistManager() {
        return blacklistManager;
    }

    public ModAllowlistManager getAllowlistManager() {
        return allowlistManager;
    }

    public ModCacheManager getModCache() {
        return modCache;
    }

    public HashFetcher getHashFetcher() {
        return hashFetcher;
    }

    public GuiModIdCache getGuiModIdCache() {
        return guiModIdCache;
    }

    public ModrinthJarResolver getModrinthJarResolver() {
        return modrinthJarResolver;
    }

    public GuiServer getGuiServer() {
        return guiServer;
    }

    public BucketService getBucketService() {
        return bucketService;
    }

    public File getStorageFolder() {
        return storageFolder != null ? storageFolder : new File(getDataFolder(), "data");
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

    public ServerKeyStore getServerKeyStore() {
        return serverKeyStore;
    }

    public FloodgateDetector getFloodgateDetector() {
        return floodgateDetector;
    }

    public DiscordWebhook getDiscordWebhook() {
        return discordWebhook;
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

// SPDX-FileCopyrightText: 2025 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;




public class ConfigManager {
    private static final int MIN_TIMEOUT_SECONDS = 1;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final int MAX_REPORTED_MODS = 512;

    private final File configFile;
    private final Consumer<String> logger;
    private FileConfiguration config;


    private int TIMEOUT_SECONDS = 15;
    private int HANDSHAKE_TIMEOUT_SECONDS = 10;
    private Map<String, String> kickMessages = new HashMap<>();
    private boolean enablePlayerNotifications = true;
    private String welcomeMessage = "Welcome To The Server";
    private boolean enableModCountThreshold = false;
    private int maxModCount = 50;
    private boolean SHOW_MOD_LIST = true;
    private boolean ONE_MOD_PER_LINE = false;
    private boolean allowFloodgate = true;


    private boolean modVerificationEnabled = true;
    private boolean advancedModVerificationEnabled = false;


    private boolean discordEnabled = false;
    private String discordWebhookUrl = "";
    private boolean discordPlayerJoin = true;
    private boolean discordModListReceived = true;
    private boolean discordBlacklistViolation = true;
    private boolean discordVerificationFailed = true;


    private boolean launcherVerificationEnabled = false;
    private String launcherKickMessage = "Please use an approved launcher to join this server";
    private boolean allowUnknownClients = false;
    private int guiPort;
    private String guiExternalUrl = "";

    public ConfigManager(File dataFolder) {
        this(dataFolder, ignored -> { });
    }

    public ConfigManager(File dataFolder, Consumer<String> logger) {
        this.configFile = new File(dataFolder, "config.yml");
        this.logger = logger;
        loadConfig();
    }




    public void loadConfig() {
        resetDefaults();
        try {
            if (!configFile.exists()) {
                createDefaultConfigFile();
            }

            config = YamlConfiguration.loadConfiguration(configFile);


            HANDSHAKE_TIMEOUT_SECONDS = bounded(config.getInt("handshakeTimeoutSeconds", 10),
                    MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS, 10);
            TIMEOUT_SECONDS = bounded(config.getInt("modlistTimeoutSeconds", 15),
                    MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS, 15);

            kickMessages.clear();
            if (config.isConfigurationSection("kickMessages")) {
                for (String key : config.getConfigurationSection("kickMessages").getKeys(false)) {
                    String value = config.getString("kickMessages." + key);
                    if (value != null) {
                        kickMessages.put(key, value);
                    }
                }
            }

            ensureKickMessage("missingHidder",
                    "Please install Hidder to join this server.\nhttps://modrinth.com/mod/hidder");
            ensureKickMessage("blacklistedMods", "Please Remove {mods} Illegal Mod{plural} To Join The Server");
            ensureKickMessage("modlistTimeout", "Player Verification Failed");
            ensureKickMessage("modlistRequestFailed", "Player verification failed - unable to send mod list request.");
            ensureKickMessage("modCountExceeded", "You have too many mods installed. Maximum allowed: {maxMods}");

            enablePlayerNotifications = config.getBoolean("enablePlayerNotifications", true);
            welcomeMessage = value(config.getString("welcomeMessage"), "Welcome To The Server");

            enableModCountThreshold = config.getBoolean("enableModCountThreshold", false);
            maxModCount = bounded(config.getInt("maxModCount", 50), 1, MAX_REPORTED_MODS, 50);

            SHOW_MOD_LIST = config.getBoolean("showModList", true);
            ONE_MOD_PER_LINE = config.getBoolean("oneModPerLine", false);


            allowFloodgate = config.getBoolean("allowFloodgate", true);


            modVerificationEnabled = config.getBoolean("ModVerification", true);
            advancedModVerificationEnabled = config.getBoolean("advancedModVerification", false);


            discordEnabled = config.getBoolean("discord.enabled", false);
            discordWebhookUrl = value(config.getString("discord.webhookUrl"), "");
            discordPlayerJoin = config.getBoolean("discord.events.playerJoin", true);
            discordModListReceived = config.getBoolean("discord.events.modListReceived", true);
            discordBlacklistViolation = config.getBoolean("discord.events.blacklistViolation", true);
            discordVerificationFailed = config.getBoolean("discord.events.verificationFailed", true);


            launcherVerificationEnabled = config.getBoolean("launcherVerification.enabled", false);
            launcherKickMessage = value(config.getString("launcherVerification.kickMessage"),
                    "Please use an approved launcher to join this server");
            allowUnknownClients = config.getBoolean("launcherVerification.allowUnknownClients", false);

            int configuredPort = config.getInt("gui.port", 0);
            guiPort = configuredPort >= 0 && configuredPort <= 65535 ? configuredPort : 0;
            guiExternalUrl = value(config.getString("gui.externalUrl"), "").trim();

        } catch (Exception e) {
            logger.accept("Config file failed to load; secure defaults are active: "
                    + e.getClass().getSimpleName());
        }
    }

    private void resetDefaults() {
        TIMEOUT_SECONDS = 15;
        HANDSHAKE_TIMEOUT_SECONDS = 10;
        kickMessages.clear();
        enablePlayerNotifications = true;
        welcomeMessage = "Welcome To The Server";
        enableModCountThreshold = false;
        maxModCount = 50;
        SHOW_MOD_LIST = true;
        ONE_MOD_PER_LINE = false;
        allowFloodgate = true;
        modVerificationEnabled = true;
        advancedModVerificationEnabled = false;
        discordEnabled = false;
        discordWebhookUrl = "";
        discordPlayerJoin = true;
        discordModListReceived = true;
        discordBlacklistViolation = true;
        discordVerificationFailed = true;
        launcherVerificationEnabled = false;
        launcherKickMessage = "Please use an approved launcher to join this server";
        allowUnknownClients = false;
        guiPort = 0;
        guiExternalUrl = "";
    }

    private void ensureKickMessage(String key, String defaultMsg) {
        if (!kickMessages.containsKey(key)) {
            kickMessages.put(key, defaultMsg);
        }
    }

    private static int bounded(int candidate, int minimum, int maximum, int fallback) {
        return candidate >= minimum && candidate <= maximum ? candidate : fallback;
    }

    private static String value(String candidate, String fallback) {
        return candidate == null ? fallback : candidate;
    }





    private void createDefaultConfigFile() throws IOException {
        String defaultContent = "# ModSeeker Configuration File\n" +
                    "# This file contains all configurable options for the ModSeeker plugin\n" +
                    "# ==================================================================\n" +
                    "# Toggle mod list verification\n" +
                    "# false = disables mod checking\n" +
                    "# If launcher verification is also false, non-bypassed players are denied\n" +
                    "ModVerification: true\n" +
                    "\n" +
                    "# ---------------------------------------------------------------\n" +
                    "# Hash-based mod verification (Advanced Mode — highly recommended)\n" +
                    "# true  = verify mods by secure file hash (modallowlist.json)\n" +
                    "# false = use the older mod-ID check (modblacklist.json)\n" +
                    "# ---------------------------------------------------------------\n" +
                    "advancedModVerification: false\n" +
                    "\n" +
                    "# ---------------------------------------------------------------\n" +
                    "# Timeout Configuration\n" +
                    "# Configure handshake and modlist timeout values in seconds\n" +
                    "# - Handshake timeout: Time allowed for Hidder presence confirmation\n" +
                    "# - Modlist timeout: Time allowed for receiving the mod list from client\n" +
                    "# ---------------------------------------------------------------\n" +
                    "handshakeTimeoutSeconds: 10\n" +
                    "modlistTimeoutSeconds: 15\n" +
                    "\n" +
                    "# ---------------------------------------------------------------\n" +
                    "# Kick Messages\n" +
                    "# Customize kick messages for different violation types\n" +
                    "# Available placeholders: {player}, {mods}, {plural}\n" +
                    "# ---------------------------------------------------------------\n" +
                    "kickMessages:\n" +
                    "  missingHidder: \"Please install Hidder to join this server.\\nhttps://modrinth.com/mod/hidder\"\n" +
                    "  blacklistedMods: \"Please Remove {mods} Illegal Mod{plural} To Join The Server\"\n" +
                    "  modlistTimeout: \"Player Verification Failed\"\n" +
                    "  modlistRequestFailed: \"Player verification failed - unable to send mod list request.\"\n" +
                    "  modCountExceeded: \"You have too many mods installed. Maximum allowed: {maxMods}\"\n" +
                    "# ---------------------------------------------------------------\n" +
                    "# Player Notification\n" +
                    "# Enable/disable welcome messages and customize the message\n" +
                    "# ---------------------------------------------------------------\n" +
                    "enablePlayerNotifications: true\n" +
                    "welcomeMessage: \"Welcome To The Server\"\n" +
                    "\n" +
                    "# ---------------------------------------------------------------\n" +
                    "# Mod Count Threshold\n" +
                    "# Set the maximum number of reported mods allowed\n" +
                    "# ---------------------------------------------------------------\n" +
                    "enableModCountThreshold: false\n" +
                    "maxModCount: 50\n" +
                    "# ---------------------------------------------------------------\n" +
                    "# Mod List Display Settings\n" +
                    "# Configure how mod lists are displayed in the console\n" +
                    "# - showModList: Enable/disable mod list logging\n" +
                    "# - oneModPerLine: Show one mod per line (requires showModList = true)\n" +
                    "# ---------------------------------------------------------------\n" +
                    "showModList: true\n" +
                    "oneModPerLine: false\n" +
                    "\n" +
                    "# ---------------------------------------------------------------\n" +
                    "# Floodgate / Bedrock Player Handling\n" +
                    "# If set to true, Bedrock players (Floodgate) bypass all mod checks\n" +
                    "# Recommended: true for Geyser/Floodgate servers\n" +
                    "# ---------------------------------------------------------------\n" +
                    "allowFloodgate: true\n" +
                    "\n" +
                    "# ==================================================================\n" +
                    "# Discord Webhook Integration\n" +
                    "# ==================================================================\n" +
                    "discord:\n" +
                    "  enabled: false\n" +
                    "  webhookUrl: \"https://discord.com/api/webhooks/YOUR_WEBHOOK_URL_HERE\"\n" +
                    "  events:\n" +
                    "    playerJoin: true\n" +
                    "    modListReceived: true\n" +
                    "    blacklistViolation: true\n" +
                    "    verificationFailed: true\n" +
                    "\n" +
                    "# ==================================================================\n" +
                    "# Launcher/Client Verification\n" +
                    "# ==================================================================\n" +
                    "launcherVerification:\n" +
                    "  enabled: false                # Require approved client/launcher to join\n" +
                    "  kickMessage: \"Please use an approved launcher to join this server\"  # Message shown on kick\n" +
                    "  allowUnknownClients: false     # Allow unrecognized clients/launchers (not recommended)\n" +
                    "\n" +
                    "# ==================================================================\n" +
                    "# ModSeeker GUI\n" +
                    "# externalUrl: leave \"\" for localhost, or use \"http://YOUR_REAL_PUBLIC_IP:PORT\"\n" +
                    "# HTTPS proxy example: \"https://panel.example.com\"\n" +
                    "# ==================================================================\n" +
                    "gui:\n" +
                    "  port: 0\n" +
                    "  externalUrl: \"\"\n";

        Files.write(configFile.toPath(), defaultContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }


    public int getTIMEOUT_SECONDS() {
        return TIMEOUT_SECONDS;
    }

    public int getHANDSHAKE_TIMEOUT_SECONDS() {
        return HANDSHAKE_TIMEOUT_SECONDS;
    }

    public Map<String, String> getKickMessages() {
        return Map.copyOf(kickMessages);
    }

    public boolean isEnablePlayerNotifications() {
        return enablePlayerNotifications;
    }

    public String getWelcomeMessage() {
        return welcomeMessage;
    }

    public boolean isEnableModCountThreshold() {
        return enableModCountThreshold;
    }

    public int getMaxModCount() {
        return maxModCount;
    }

    public boolean isSHOW_MOD_LIST() {
        return SHOW_MOD_LIST;
    }

    public boolean isONE_MOD_PER_LINE() {
        return ONE_MOD_PER_LINE;
    }

    public boolean isAllowFloodgate() {
        return allowFloodgate;
    }


    public boolean isModVerificationEnabled() {
        return modVerificationEnabled;
    }

    public boolean isVerificationConfigured() {
        return modVerificationEnabled || launcherVerificationEnabled;
    }

    public boolean isAdvancedModVerificationEnabled() {
        return advancedModVerificationEnabled;
    }


    public boolean isDiscordEnabled() {
        return discordEnabled;
    }

    public String getDiscordWebhookUrl() {
        return discordWebhookUrl;
    }

    public boolean isDiscordPlayerJoin() {
        return discordPlayerJoin;
    }

    public boolean isDiscordModListReceived() {
        return discordModListReceived;
    }

    public boolean isDiscordBlacklistViolation() {
        return discordBlacklistViolation;
    }

    public boolean isDiscordVerificationFailed() {
        return discordVerificationFailed;
    }


    public boolean isLauncherVerificationEnabled() {
        return launcherVerificationEnabled;
    }

    public String getLauncherKickMessage() {
        return launcherKickMessage;
    }

    public boolean isAllowUnknownClients() {
        return allowUnknownClients;
    }

    public int getGuiPort() {
        return guiPort;
    }

    public String getGuiExternalUrl() {
        return guiExternalUrl;
    }

}

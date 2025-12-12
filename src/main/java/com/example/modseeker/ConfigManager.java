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

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * Manages configuration loading and saving for ModSeeker plugin using YAML
 */
public class ConfigManager {

    private File configFile;
    private FileConfiguration config;

    // Configuration values (Default)
    private int TIMEOUT_SECONDS = 15;
    private int HANDSHAKE_TIMEOUT_SECONDS = 10;
    private Set<String> modlistFilter = new HashSet<>();
    private Map<String, String> kickMessages = new HashMap<>();
    private Set<String> whitelist = new HashSet<>();
    private boolean enablePlayerNotifications = true;
    private String welcomeMessage = "Welcome To The Server";
    private boolean enableModCountThreshold = false;
    private int maxModCount = 50;
    private boolean SHOW_MOD_LIST = true;
    private boolean ONE_MOD_PER_LINE = false;
    private boolean HIGHLIGHT_MODS = true;
    private boolean HIGHLIGHT_BLACKLISTED_MODS = true;
    private boolean allowFloodgate = true;

    public ConfigManager(File dataFolder) {
        this.configFile = new File(dataFolder, "config.yml");
        loadConfig();
    }

    /**
     * Load configuration from config.yml
     */
    public void loadConfig() {
        try {
            if (!configFile.exists()) {
                createDefaultConfigFile();
            }

            config = YamlConfiguration.loadConfiguration(configFile);

            // Load timeout values
            HANDSHAKE_TIMEOUT_SECONDS = config.getInt("handshakeTimeoutSeconds", 10);
            TIMEOUT_SECONDS = config.getInt("modlistTimeoutSeconds", 15);

            // Load mod list filter
            modlistFilter.clear();
            List<String> filterList = config.getStringList("modlistFilter");
            if (filterList != null && !filterList.isEmpty()) {
                modlistFilter.addAll(filterList);
            } else {
                modlistFilter.addAll(Arrays.asList("java", "minecraft", "fabricloader"));
            }

            kickMessages.clear();
            if (config.isConfigurationSection("kickMessages")) {
                for (String key : config.getConfigurationSection("kickMessages").getKeys(false)) {
                    kickMessages.put(key, config.getString("kickMessages." + key));
                }
            }
            // Ensure defaults exist
            ensureKickMessage("missingHidder", "Please Install Hidder Mod To Enter The Server");
            ensureKickMessage("blacklistedMods", "Please Remove {mods} Illegal Mod{plural} To Join The Server");
            ensureKickMessage("modlistTimeout", "Player Verification Failed");
            ensureKickMessage("modlistRequestFailed", "Player verification failed - unable to send mod list request.");
            ensureKickMessage("modCountExceeded", "You have too many mods installed. Maximum allowed: {maxMods}");

            whitelist.clear();
            List<String> whitelistList = config.getStringList("whitelist");
            if (whitelistList != null) {
                whitelist.addAll(whitelistList);
            }

            enablePlayerNotifications = config.getBoolean("enablePlayerNotifications", true);
            welcomeMessage = config.getString("welcomeMessage", "Welcome To The Server");

            enableModCountThreshold = config.getBoolean("enableModCountThreshold", false);
            maxModCount = config.getInt("maxModCount", 50);

            SHOW_MOD_LIST = config.getBoolean("showModList", true);
            ONE_MOD_PER_LINE = config.getBoolean("oneModPerLine", false);
            HIGHLIGHT_MODS = config.getBoolean("highlightMods", true);
            HIGHLIGHT_BLACKLISTED_MODS = config.getBoolean("highlightBlacklistedMods", true);

            // Load floodgate settings
            allowFloodgate = config.getBoolean("allowFloodgate", true);

        } catch (Exception e) {
            e.printStackTrace();
            // Use default values on error
            HANDSHAKE_TIMEOUT_SECONDS = 10;
            TIMEOUT_SECONDS = 15;
            SHOW_MOD_LIST = true;
            ONE_MOD_PER_LINE = false;
            HIGHLIGHT_MODS = true;
            HIGHLIGHT_BLACKLISTED_MODS = true;
            allowFloodgate = true;
            modlistFilter.addAll(Arrays.asList("java", "minecraft", "fabricloader"));
        }
    }

    private void ensureKickMessage(String key, String defaultMsg) {
        if (!kickMessages.containsKey(key)) {
            kickMessages.put(key, defaultMsg);
        }
    }

    /**
     * Method to create a default config file with comments
     * Uses the exact template provided by the user
     */
    private void createDefaultConfigFile() {
        try {
            String defaultContent = "# ModSeeker Configuration File\n" +
                    "# This file contains all configurable options for the ModSeeker plugin\n" +
                    "# ==================================================================\n" +
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
                    "# Mod List Filter\n" +
                    "# Specify mod IDs to filter out from console display\n" +
                    "# These mods will still be checked but won't appear in logs\n" +
                    "# ---------------------------------------------------------------\n" +
                    "modlistFilter:\n" +
                    "  - \"java\"\n" +
                    "  - \"minecraft\"\n" +
                    "  - \"fabricloader\"\n" +
                    "# ---------------------------------------------------------------\n" +
                    "# Kick Messages\n" +
                    "# Customize kick messages for different violation types\n" +
                    "# Available placeholders: {player}, {mods}, {plural}\n" +
                    "# ---------------------------------------------------------------\n" +
                    "kickMessages:\n" +
                    "  missingHidder: \"Please Install Hidder Mod To Enter The Server\"\n" +
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
                    "# Set maximum number of mods allowed (set to -1 to disable)\n" +
                    "# Note: This count does NOT include mods filtered by modlistFilter\n" +
                    "# ---------------------------------------------------------------\n" +
                    "enableModCountThreshold: false\n" +
                    "maxModCount: 50\n" +
                    "# ---------------------------------------------------------------\n" +
                    "# Mod List Display Settings\n" +
                    "# Configure how mod lists are displayed in the console\n" +
                    "# - showModList: Enable/disable mod list logging\n" +
                    "# - oneModPerLine: Show one mod per line (requires showModList = true)\n" +
                    "# - highlightMods: Enable/disable color highlighting (requires showModList = true)\n" +
                    "# - highlightBlacklistedMods: Highlight blacklisted mods in red (requires showModList = true)\n" +
                    "# ---------------------------------------------------------------\n" +
                    "showModList: true\n" +
                    "oneModPerLine: false\n" +
                    "highlightMods: true\n" +
                    "highlightBlacklistedMods: true\n" +
                    "\n" +
                    "# ---------------------------------------------------------------\n" +
                    "# Floodgate / Bedrock Player Handling\n" +
                    "# If set to true, Bedrock players (Floodgate) bypass all mod checks\n" +
                    "# Recommended: true for Geyser/Floodgate servers\n" +
                    "# ---------------------------------------------------------------\n" +
                    "allowFloodgate: true\n";

            Files.write(configFile.toPath(), defaultContent.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Save whitelist to config file
    // Note: This will strip comments if we use YamlConfiguration.save()
    // But since we want to preserve the user's template, we should try to only
    // modify the whitelist section if possible.
    // However, for simplicity and reliability, we will use YamlConfiguration.save()
    // for updates,
    // accepting that comments might be lost on dynamic updates, OR we can implement
    // a smarter updater later.
    // For now, let's just use standard save to ensure functionality.
    public void saveWhitelist(Set<String> whitelist) {
        try {
            config.set("whitelist", new ArrayList<>(whitelist));
            config.save(configFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Getters for configuration values
    public int getTIMEOUT_SECONDS() {
        return TIMEOUT_SECONDS;
    }

    public int getHANDSHAKE_TIMEOUT_SECONDS() {
        return HANDSHAKE_TIMEOUT_SECONDS;
    }

    public Set<String> getModlistFilter() {
        return modlistFilter;
    }

    public Map<String, String> getKickMessages() {
        return kickMessages;
    }

    public Set<String> getWhitelist() {
        return whitelist;
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

    public boolean isHIGHLIGHT_MODS() {
        return HIGHLIGHT_MODS;
    }

    public boolean isHIGHLIGHT_BLACKLISTED_MODS() {
        return HIGHLIGHT_BLACKLISTED_MODS;
    }

    public boolean isAllowFloodgate() {
        return allowFloodgate;
    }
}
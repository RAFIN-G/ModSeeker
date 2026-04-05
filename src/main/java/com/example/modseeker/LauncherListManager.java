/*
 * Copyright (C) 2025 ModSeeker
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.example.modseeker;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Manages the launcher/client whitelist from client-list.yml
 * Part of ModSeeker v2.0 security upgrade
 */
public class LauncherListManager {

    private final File clientListFile;
    private FileConfiguration config;

    // Map of launcher ID to launcher info
    private final Map<String, LauncherInfo> launchers = new HashMap<>();

    public LauncherListManager(File dataFolder) {
        this.clientListFile = new File(dataFolder, "client-list.yml");
        loadClientList();
    }

    /**
     * Load the client list from client-list.yml
     */
    public void loadClientList() {
        try {
            if (!clientListFile.exists()) {
                createDefaultClientList();
            }

            config = YamlConfiguration.loadConfiguration(clientListFile);
            launchers.clear();

            // Load all launcher entries
            for (String key : config.getKeys(false)) {
                if (config.isConfigurationSection(key)) {
                    boolean enabled = config.getBoolean(key + ".enabled", false);
                    String name = config.getString(key + ".name", key);
                    launchers.put(key.toLowerCase(), new LauncherInfo(key, name, enabled));
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            // Load defaults on error
            loadDefaults();
        }
    }

    /**
     * Check if a launcher is allowed
     * 
     * @param launcherId The launcher ID (lowercase)
     * @return true if launcher is enabled in the whitelist
     */
    public boolean isLauncherAllowed(String launcherId) {
        if (launcherId == null || launcherId.isEmpty()) {
            return false;
        }
        LauncherInfo info = launchers.get(launcherId.toLowerCase());
        return info != null && info.isEnabled();
    }

    public boolean isLauncherEnabled(String launcherId) {
        return isLauncherAllowed(launcherId);
    }

    /**
     * Get launcher display name
     * 
     * @param launcherId The launcher ID
     * @return Display name or ID if not found
     */
    public String getLauncherName(String launcherId) {
        if (launcherId == null)
            return "Unknown";
        LauncherInfo info = launchers.get(launcherId.toLowerCase());
        return info != null ? info.getName() : launcherId;
    }

    /**
     * Get all launcher entries
     */
    public Map<String, LauncherInfo> getLaunchers() {
        return launchers;
    }

    /**
     * Get set of enabled launcher IDs
     */
    public Set<String> getEnabledLaunchers() {
        Set<String> enabled = new java.util.HashSet<>();
        for (Map.Entry<String, LauncherInfo> entry : launchers.entrySet()) {
            if (entry.getValue().isEnabled()) {
                enabled.add(entry.getKey());
            }
        }
        return enabled;
    }

    /**
     * Reload the client list
     */
    public void reload() {
        loadClientList();
    }

    private void loadDefaults() {
        launchers.clear();
        launchers.put("prism", new LauncherInfo("prism", "Prism Launcher", false));
        launchers.put("multimc", new LauncherInfo("multimc", "MultiMC", false));
        launchers.put("atlauncher", new LauncherInfo("atlauncher", "ATLauncher", false));
        launchers.put("curseforge", new LauncherInfo("curseforge", "CurseForge", false));
        launchers.put("modrinth", new LauncherInfo("modrinth", "Modrinth App", false));
        launchers.put("gdlauncher", new LauncherInfo("gdlauncher", "GDLauncher", false));
        launchers.put("technic", new LauncherInfo("technic", "Technic Launcher", false));
        launchers.put("ftb", new LauncherInfo("ftb", "FTB App", false));
        launchers.put("lunar", new LauncherInfo("lunar", "Lunar Client", false));
        launchers.put("badlion", new LauncherInfo("badlion", "Badlion Client", false));
        launchers.put("feather", new LauncherInfo("feather", "Feather Client", false));
        launchers.put("labymod", new LauncherInfo("labymod", "LabyMod", false));
        launchers.put("tlauncher", new LauncherInfo("tlauncher", "TLauncher", false));
        launchers.put("sklauncher", new LauncherInfo("sklauncher", "SKLauncher", false));
    }

    private void createDefaultClientList() {
        try {
            String defaultContent = "# Launcher/Client Whitelist\n" +
                    "# enabled: true = allowed, enabled: false = blocked\n" +
                    "\n" +
                    "prism:\n" +
                    "  enabled: false\n" +
                    "  name: \"Prism Launcher\"\n" +
                    "multimc:\n" +
                    "  enabled: false\n" +
                    "  name: \"MultiMC\"\n" +
                    "atlauncher:\n" +
                    "  enabled: false\n" +
                    "  name: \"ATLauncher\"\n" +
                    "curseforge:\n" +
                    "  enabled: false\n" +
                    "  name: \"CurseForge\"\n" +
                    "modrinth:\n" +
                    "  enabled: false\n" +
                    "  name: \"Modrinth App\"\n" +
                    "gdlauncher:\n" +
                    "  enabled: false\n" +
                    "  name: \"GDLauncher\"\n" +
                    "technic:\n" +
                    "  enabled: false\n" +
                    "  name: \"Technic Launcher\"\n" +
                    "ftb:\n" +
                    "  enabled: false\n" +
                    "  name: \"FTB App\"\n" +
                    "lunar:\n" +
                    "  enabled: false\n" +
                    "  name: \"Lunar Client\"\n" +
                    "badlion:\n" +
                    "  enabled: false\n" +
                    "  name: \"Badlion Client\"\n" +
                    "feather:\n" +
                    "  enabled: false\n" +
                    "  name: \"Feather Client\"\n" +
                    "labymod:\n" +
                    "  enabled: false\n" +
                    "  name: \"LabyMod\"\n" +
                    "tlauncher:\n" +
                    "  enabled: false\n" +
                    "  name: \"TLauncher\"\n" +
                    "sklauncher:\n" +
                    "  enabled: false\n" +
                    "  name: \"SKLauncher\"\n" +
                    "pojav:\n" +
                    "  enabled: false\n" +
                    "  name: \"Pojav Launcher\"\n";

            Files.write(clientListFile.toPath(), defaultContent.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Inner class to hold launcher information
     */
    public static class LauncherInfo {
        private final String id;
        private final String name;
        private final boolean enabled;

        public LauncherInfo(String id, String name, boolean enabled) {
            this.id = id;
            this.name = name;
            this.enabled = enabled;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public boolean isEnabled() {
            return enabled;
        }
    }
}

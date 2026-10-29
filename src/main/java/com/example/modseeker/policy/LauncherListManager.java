// SPDX-FileCopyrightText: 2025 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.policy;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;





public class LauncherListManager {
    private static final Set<String> REMOVED_CLIENT_IDS = Set.of("labymod", "pojav");

    private final File clientListFile;
    private FileConfiguration config;


    private final Map<String, LauncherInfo> launchers = new HashMap<>();

    public LauncherListManager(File dataFolder) {
        this.clientListFile = new File(dataFolder, "client-list.yml");
        loadClientList();
    }




    public void loadClientList() {
        try {
            if (!clientListFile.exists()) {
                createDefaultClientList();
            }

            config = YamlConfiguration.loadConfiguration(clientListFile);
            launchers.clear();

            boolean migrated = false;
            for (String clientId : REMOVED_CLIENT_IDS) {
                if (config.contains(clientId)) {
                    config.set(clientId, null);
                    migrated = true;
                }
            }
            if (migrated) {
                config.save(clientListFile);
            }


            for (String key : config.getKeys(false)) {
                if (config.isConfigurationSection(key)) {
                    boolean enabled = config.getBoolean(key + ".enabled", false);
                    String name = config.getString(key + ".name", key);
                    launchers.put(key.toLowerCase(Locale.ROOT), new LauncherInfo(key, name, enabled));
                }
            }

        } catch (Exception e) {
            loadDefaults();
        }
    }







    public boolean isLauncherAllowed(String launcherId) {
        if (launcherId == null || launcherId.isEmpty()) {
            return false;
        }
        LauncherInfo info = launchers.get(launcherId.toLowerCase(Locale.ROOT));
        return info != null && info.isEnabled();
    }

    public boolean isLauncherEnabled(String launcherId) {
        return isLauncherAllowed(launcherId);
    }







    public String getLauncherName(String launcherId) {
        if (launcherId == null)
            return "Unknown";
        LauncherInfo info = launchers.get(launcherId.toLowerCase(Locale.ROOT));
        return info != null ? info.getName() : launcherId;
    }




    public Map<String, LauncherInfo> getLaunchers() {
        return Map.copyOf(launchers);
    }




    public Set<String> getEnabledLaunchers() {
        Set<String> enabled = new java.util.HashSet<>();
        for (Map.Entry<String, LauncherInfo> entry : launchers.entrySet()) {
            if (entry.getValue().isEnabled()) {
                enabled.add(entry.getKey());
            }
        }
        return enabled;
    }




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
                    "tlauncher:\n" +
                    "  enabled: false\n" +
                    "  name: \"TLauncher\"\n" +
                    "sklauncher:\n" +
                    "  enabled: false\n" +
                    "  name: \"SKLauncher\"\n";

            Files.writeString(clientListFile.toPath(), defaultContent, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }




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

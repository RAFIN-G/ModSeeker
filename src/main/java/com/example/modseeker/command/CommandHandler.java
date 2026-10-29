// SPDX-FileCopyrightText: 2025 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.command;

import com.example.modseeker.ModSeekerPlugin;
import com.example.modseeker.catalog.BucketService;
import com.example.modseeker.catalog.HashFetcher;
import com.example.modseeker.catalog.ModCacheManager;
import com.example.modseeker.config.ConfigManager;
import com.example.modseeker.gui.GuiServer;
import com.example.modseeker.policy.BlacklistManager;
import com.example.modseeker.policy.LauncherListManager;
import com.example.modseeker.policy.ModAllowlistManager;
import com.example.modseeker.policy.WhitelistManager;
import com.example.modseeker.protocol.ProtocolConstants;
import com.example.modseeker.verification.PlayerDataManager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class CommandHandler implements TabExecutor {
    private static final List<String> SUBCOMMANDS = List.of(
            "seek", "modallowlist", "modblacklist", "whitelist", "gui", "reload", "status");

    private final ModSeekerPlugin plugin;
    private final BlacklistManager blacklistManager;
    private final ConfigManager configManager;
    private final WhitelistManager whitelistManager;
    private final LauncherListManager launcherListManager;

    public CommandHandler(ModSeekerPlugin plugin, BlacklistManager blacklistManager, ConfigManager configManager,
            WhitelistManager whitelistManager, LauncherListManager launcherListManager) {
        this.plugin = plugin;
        this.blacklistManager = blacklistManager;
        this.configManager = configManager;
        this.whitelistManager = whitelistManager;
        this.launcherListManager = launcherListManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("modseeker")) {
            return false;
        }

        if (args.length < 1) {
            List<String> available = SUBCOMMANDS.stream()
                    .filter(subCommand -> hasCommandPermission(sender, subCommand))
                    .toList();
            if (available.isEmpty()) {
                deny(sender);
            } else {
                sender.sendMessage(ChatColor.RED + "Usage: /modseeker <" + String.join("|", available) + ">");
            }
            return true;
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);

        switch (subCommand) {
            case "seek":
                if (!requireCommandPermission(sender, subCommand)) return true;
                return handleSeekCommand(sender, args);
            case "modblacklist":
                if (!requireCommandPermission(sender, subCommand)) return true;
                return handleModBlacklistCommand(sender, args);
            case "modallowlist":
                if (!requireCommandPermission(sender, subCommand)) return true;
                return handleModAllowlistCommand(sender, args);
            case "whitelist":
                if (!requireCommandPermission(sender, subCommand)) return true;
                return handleWhitelistCommand(sender, args);
            case "gui":
                if (!requireCommandPermission(sender, subCommand)) return true;
                return handleGuiCommand(sender);
            case "reload":
                if (!requireCommandPermission(sender, subCommand)) return true;
                plugin.getGuiServer().stop();
                if (!plugin.ensureStorageDirectory()) {
                    sender.sendMessage(ChatColor.RED + "ModSeeker data directory could not be prepared.");
                    return true;
                }
                plugin.reloadConfiguration();
                if (!configManager.isVerificationConfigured()) {
                    sender.sendMessage(ChatColor.RED + "ModVerification and launcherVerification are both disabled;"
                            + " non-bypassed players will be denied until a verification check is enabled.");
                    plugin.logInfo(ChatColor.RED + "Both verification checks are disabled after reload;"
                            + " non-bypassed players will be denied");
                }
                blacklistManager.loadBlacklist();
                plugin.getAllowlistManager().load();
                if (!whitelistManager.loadWhitelist()) {
                    plugin.logInfo(ChatColor.YELLOW
                            + "whitelist.json reload failed; the last-good whitelist remains active");
                }
                plugin.synchronizeWhitelistBypasses();
                launcherListManager.reload();
                plugin.getBucketService().ensureDirectory();
                plugin.populateEssentialsAsync();
                sender.sendMessage(ChatColor.GREEN + "ModSeeker configuration and lists reloaded successfully.");
                return true;
            case "status":
                if (!requireCommandPermission(sender, subCommand)) return true;
                return handleStatusCommand(sender);
            default:
                if (SUBCOMMANDS.stream().noneMatch(candidate -> hasCommandPermission(sender, candidate))) {
                    deny(sender);
                } else {
                    sender.sendMessage(ChatColor.RED + "Unknown ModSeeker subcommand.");
                }
                return true;
        }
    }

    private boolean requireCommandPermission(CommandSender sender, String subCommand) {
        if (!hasCommandPermission(sender, subCommand)) {
            deny(sender);
            return false;
        }
        return true;
    }

    private boolean hasCommandPermission(CommandSender sender, String subCommand) {
        return sender instanceof ConsoleCommandSender
                || sender.hasPermission("modseeker.command." + subCommand.toLowerCase(Locale.ROOT));
    }

    private void deny(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
    }

    private boolean handleGuiCommand(CommandSender sender) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage(ChatColor.RED + "This command is console-only.");
            return true;
        }
        if (configManager.isAdvancedModVerificationEnabled() && !plugin.getAllowlistManager().exists()) {
            plugin.getAllowlistManager().load();
        }
        GuiServer gui = plugin.getGuiServer();
        if (gui.start()) {
            String panel = configManager.isAdvancedModVerificationEnabled() ? "allowlist" : "blacklist";
            plugin.logInfo(ChatColor.GREEN + "ModSeeker " + panel + " GUI started successfully");
            plugin.logInfo(ChatColor.AQUA + gui.getAccessUrl());
            if (gui.isInsecureRemote()) {
                plugin.logInfo(ChatColor.YELLOW + "Warning: this GUI is using HTTP without end-to-end TLS;"
                        + " it is running, but administrative traffic is not encrypted");
            }
        } else {
            plugin.logInfo(ChatColor.YELLOW
                    + "The GUI could not be started. Check earlier console messages; if a previous update is finishing, retry shortly.");
        }
        return true;
    }

    private boolean handleModAllowlistCommand(CommandSender sender, String[] args) {
        if (!configManager.isAdvancedModVerificationEnabled()) {
            sender.sendMessage(ChatColor.YELLOW
                    + "The hash allowlist is dormant while advancedModVerification is false.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED
                    + "Usage: /modseeker modallowlist <add|remove|show>  (add/remove take <link> <mc>, <modid>, or a <player>)");
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        ModAllowlistManager allow = plugin.getAllowlistManager();

        if (action.equals("add")) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED
                        + "Usage: /modseeker modallowlist add <modrinth-link> <mcVersion>   OR   add <player>");
                return true;
            }
            final String a2 = args[2];
            if (a2.equalsIgnoreCase("bucket")) {
                plugin.getBucketService().execute(sender, BucketService.Target.ALLOWLIST,
                        BucketService.Operation.ADD);
                return true;
            }
            if (a2.startsWith("http://") || a2.startsWith("https://")) {
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Usage: /modseeker modallowlist add <modrinth-link> <mcVersion>");
                    return true;
                }
                final String link = a2;
                final String mcVersion = args[3];
                sender.sendMessage(ChatColor.YELLOW + "Fetching from Modrinth...");
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    HashFetcher.FetchResult res = plugin.getHashFetcher().fetchForCommand(link, mcVersion);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!res.ok) {
                            sender.sendMessage(ChatColor.RED + res.errorMessage);
                            return;
                        }
                        int added = allow.addEntries(res.entries);
                        if (added > 0) {
                            java.util.List<String> versions = new ArrayList<>();
                            for (ModAllowlistManager.AllowEntry e : res.entries) {
                                versions.add(e.version);
                            }
                            sender.sendMessage(ChatColor.GREEN + "Added " + res.projectName + " — " + added
                                    + " version(s) for " + mcVersion + " (" + String.join(", ", versions) + ")");
                        } else {
                            sender.sendMessage(ChatColor.YELLOW + res.projectName + " — already in allowlist.");
                        }
                    });
                });
            } else {
                addFromPlayer(sender, allow, a2, args.length >= 4 ? args[3] : null);
            }
            return true;
        } else if (action.equals("remove")) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED
                        + "Usage: /modseeker modallowlist remove <link> <mc>  |  remove <modid>  |  remove <player>");
                return true;
            }
            final String arg = args[2];
            if (arg.equalsIgnoreCase("bucket")) {
                plugin.getBucketService().execute(sender, BucketService.Target.ALLOWLIST,
                        BucketService.Operation.REMOVE);
                return true;
            }
            if ((arg.startsWith("http://") || arg.startsWith("https://")) && args.length >= 4) {
                final String mcVersion = args[3];
                sender.sendMessage(ChatColor.YELLOW + "Resolving from Modrinth...");
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    HashFetcher.FetchResult res = plugin.getHashFetcher().fetchForCommand(arg, mcVersion);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!res.ok) {
                            sender.sendMessage(ChatColor.RED + "This mod does not exist");
                            return;
                        }
                        ModAllowlistManager.BatchResult mutation = allow.removeHashes(
                                res.entries.stream().map(entry -> entry.hash).toList());
                        int removed = mutation.changed();
                        sender.sendMessage(removed > 0
                                ? ChatColor.GREEN + "Removed " + removed + " hash(es) for " + res.projectName + "."
                                : ChatColor.RED + "This mod does not exist");
                    });
                });
            } else if (plugin.getModCache().getByName(arg) != null) {
                removeFromPlayer(sender, allow, arg);
            } else {
                int removed = allow.removeByModId(arg);
                if (removed > 0) {
                    sender.sendMessage(ChatColor.GREEN + "Removed " + removed + " entr(y/ies) for " + arg + ".");
                } else {
                    sender.sendMessage(ChatColor.RED + "This mod does not exist");
                }
            }
            return true;
        } else if (action.equals("show")) {
            java.util.List<ModAllowlistManager.AllowEntry> entries = allow.getEntries();
            if (entries.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "The allowlist is empty.");
            } else {
                sender.sendMessage(ChatColor.GREEN + "Allowlist (" + allow.size() + " hashes):");
                int shown = 0;
                for (ModAllowlistManager.AllowEntry e : entries) {
                    if (shown >= 60) {
                        sender.sendMessage(ChatColor.GRAY + "  ... and " + (entries.size() - 60) + " more");
                        break;
                    }
                    sender.sendMessage(ChatColor.GRAY + "  " + e.label
                            + (e.essential ? ChatColor.DARK_GRAY + " [essential]" : ""));
                    shown++;
                }
            }
            return true;
        } else {
            sender.sendMessage(ChatColor.RED + "Usage: /modseeker modallowlist <add|remove|show>");
            return true;
        }
    }

    private void addFromPlayer(CommandSender sender, ModAllowlistManager allow, String playerName,
            String reviewedSnapshot) {
        if (!allow.exists()) {
            sender.sendMessage(ChatColor.RED
                    + "modallowlist.json is missing — run /modseeker reload to regenerate it, then try again.");
            return;
        }
        ModCacheManager.CacheEntry entry = plugin.getModCache().getByName(playerName);
        if (entry == null || entry.hashes == null || entry.hashes.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No cached mod data for " + playerName
                    + ". They must join once so their mods are recorded.");
            return;
        }
        if (entry.mods == null || entry.mods.size() != entry.hashes.size()
                || entry.hashes.stream().anyMatch(hash -> hash == null || !hash.matches("[0-9a-fA-F]{128}"))
                || entry.mods.stream().anyMatch(mod -> mod == null || mod.isBlank())) {
            sender.sendMessage(ChatColor.RED + "Cached mod data is incomplete or malformed. Ask the player to reconnect.");
            return;
        }
        java.util.List<ModAllowlistManager.AllowEntry> toAdd = new ArrayList<>();
        for (int i = 0; i < entry.hashes.size(); i++) {
            String hash = entry.hashes.get(i);
            if (hash == null || hash.isEmpty() || allow.isHashAllowed(hash)) {
                continue;
            }
            String modid = "unknown";
            String version = "unknown";
            if (entry.mods != null && i < entry.mods.size() && entry.mods.get(i) != null) {
                String raw = entry.mods.get(i);
                int c = raw.indexOf(':');
                if (c >= 0) {
                    modid = raw.substring(0, c);
                    version = raw.substring(c + 1);
                } else {
                    modid = raw;
                }
                if (modid.isEmpty()) {
                    modid = "unknown";
                }
            }
            ModAllowlistManager.AllowEntry e = new ModAllowlistManager.AllowEntry();
            e.hash = hash;
            e.algo = "sha512";
            e.loader = "fabric";
            e.modid = modid;
            e.version = version;
            e.name = "unknown".equals(modid) ? "unknown" : modid;
            e.mcVersion = "unknown";
            e.essential = false;
            e.label = modid + " v" + version + " (from " + playerName + ")";
            toAdd.add(e);
        }
        if (toAdd.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + playerName + "'s mods are already all in the allowlist.");
            return;
        }
        String reviewKey = reviewKey(entry);
        if (!reviewKey.equals(reviewedSnapshot)) {
            sender.sendMessage(ChatColor.YELLOW + "This signed cache may belong to a player rejected by policy."
                    + " Review these " + toAdd.size() + " hashes before adding them:");
            for (ModAllowlistManager.AllowEntry item : toAdd) {
                sender.sendMessage(ChatColor.GRAY + "  " + item.modid + " v" + item.version + " " + item.hash);
            }
            sender.sendMessage(ChatColor.YELLOW + "After review, run /modseeker modallowlist add "
                    + playerName + " " + reviewKey + ". A new join changes this review key.");
            return;
        }
        int added = allow.addEntries(toAdd);
        sender.sendMessage(ChatColor.GREEN + "Added " + added + " mod hash(es) from " + playerName + "'s client.");
    }

    static String reviewKey(ModCacheManager.CacheEntry entry) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Long.toString(entry.updated).getBytes(StandardCharsets.UTF_8));
            for (String value : entry.mods) {
                digest.update((byte) 0);
                digest.update(value.getBytes(StandardCharsets.UTF_8));
            }
            for (String value : entry.hashes) {
                digest.update((byte) 0);
                digest.update(value.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void removeFromPlayer(CommandSender sender, ModAllowlistManager allow, String playerName) {
        ModCacheManager.CacheEntry entry = plugin.getModCache().getByName(playerName);
        if (entry == null || entry.hashes == null || entry.hashes.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No cached mod data for " + playerName + ".");
            return;
        }
        int removed = 0;
        int skipped = 0;
        for (String hash : entry.hashes) {
            if (hash == null || hash.isEmpty()) {
                continue;
            }
            if (allow.isEssentialHash(hash)) {
                skipped++;
                continue;
            }
            removed += allow.removeByHash(hash);
        }
        sender.sendMessage(ChatColor.GREEN + "Removed " + removed + " of " + playerName + "'s mod hash(es)"
                + (skipped > 0 ? ChatColor.GRAY + " (kept " + skipped + " essential)" : "") + ".");
    }

    private boolean handleSeekCommand(CommandSender sender, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("mod")) {
            sender.sendMessage(ChatColor.RED + "Usage: /modseeker seek mod <player>");
            return true;
        }

        String playerName = args[2];
        Player targetPlayer = Bukkit.getPlayer(playerName);

        if (targetPlayer == null || !targetPlayer.isOnline()) {
            sender.sendMessage(ChatColor.RED + "Player " + playerName + " is not online.");
            return true;
        }

        ModCacheManager.CacheEntry entry = plugin.getModCache().get(targetPlayer.getUniqueId());
        if (entry == null || entry.mods == null || entry.mods.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No mod list cached for " + playerName
                    + " yet. It is captured when they join — ask them to reconnect.");
            return true;
        }

        String modString = String.join(", ", entry.mods);
        sender.sendMessage(ChatColor.GREEN + "Mod count: " + entry.mods.size() + " | Mods - " + modString);

        if (sender instanceof Player) {
            plugin.logInfo(ChatColor.YELLOW + "Seek by " + sender.getName() + " -> " + playerName
                    + " (" + entry.mods.size() + " mods): " + modString);
        }
        return true;
    }

    private boolean handleModBlacklistCommand(CommandSender sender, String[] args) {
        if (configManager.isAdvancedModVerificationEnabled()) {
            sender.sendMessage(ChatColor.YELLOW
                    + "The mod-ID blacklist is dormant while advancedModVerification is true.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /modseeker modblacklist <add|remove|show>");
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);

        if (action.equals("add")) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /modseeker modblacklist add <modId>");
                return true;
            }

            String modId = args[2].toLowerCase(Locale.ROOT);
            if (modId.equals("bucket")) {
                plugin.getBucketService().execute(sender, BucketService.Target.BLACKLIST,
                        BucketService.Operation.ADD);
                return true;
            }
            if (!BlacklistManager.isValidModId(modId)) {
                sender.sendMessage(ChatColor.RED + "Invalid mod ID.");
                return true;
            }
            BlacklistManager.BatchResult mutation = blacklistManager.addAll(List.of(modId));
            if (!mutation.success()) {
                sender.sendMessage(ChatColor.RED + mutation.error());
            } else if (mutation.changed() > 0) {
                sender.sendMessage(ChatColor.GREEN + "Mod " + modId + " added to blacklist.");
            } else {
                sender.sendMessage(ChatColor.RED + "Mod " + modId + " is already blacklisted.");
            }
            return true;
        } else if (action.equals("remove")) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /modseeker modblacklist remove <modId>");
                return true;
            }

            String modId = args[2].toLowerCase(Locale.ROOT);
            if (modId.equals("bucket")) {
                plugin.getBucketService().execute(sender, BucketService.Target.BLACKLIST,
                        BucketService.Operation.REMOVE);
                return true;
            }
            if (!BlacklistManager.isValidModId(modId)) {
                sender.sendMessage(ChatColor.RED + "Invalid mod ID.");
                return true;
            }
            BlacklistManager.BatchResult mutation = blacklistManager.removeAll(List.of(modId));
            if (!mutation.success()) {
                sender.sendMessage(ChatColor.RED + mutation.error());
            } else if (mutation.changed() > 0) {
                sender.sendMessage(ChatColor.GREEN + "Mod " + modId + " removed from blacklist.");
            } else {
                sender.sendMessage(ChatColor.RED + "Mod " + modId + " is not blacklisted.");
            }
            return true;
        } else if (action.equals("show")) {
            Set<String> blacklistedMods = blacklistManager.getBlacklistedMods();
            if (blacklistedMods.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "No mods are currently blacklisted.");
            } else {
                sender.sendMessage(ChatColor.GREEN + "Blacklisted mods (" + blacklistedMods.size() + "):");
                StringBuilder modsList = new StringBuilder();
                for (String mod : blacklistedMods) {
                    if (modsList.length() > 0) {
                        modsList.append(", ");
                    }
                    modsList.append(mod);
                }
                sender.sendMessage("   " + modsList.toString());
            }
            return true;
        } else {
            sender.sendMessage(ChatColor.RED + "Usage: /modseeker modblacklist <add|remove|show>");
            return true;
        }
    }

    private boolean handleWhitelistCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /modseeker whitelist <add|remove|show>");
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);

        if (action.equals("add")) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /modseeker whitelist add <player> [uuid]");
                return true;
            }

            String playerName = args[2];
            if (args.length >= 4) {
                try {
                    reportWhitelistResult(sender, whitelistManager.addPlayer(playerName, UUID.fromString(args[3])));
                } catch (IllegalArgumentException e) {
                    reportWhitelistResult(sender, new WhitelistManager.MutationResult(
                            WhitelistManager.Status.INVALID_INPUT, playerName, null, "UUID is invalid"));
                }
            } else {
                whitelistManager.addPlayer(playerName).thenAccept(result ->
                        Bukkit.getScheduler().runTask(plugin, () -> reportWhitelistResult(sender, result)));
                sender.sendMessage(ChatColor.YELLOW + "Resolving player identity...");
            }
            return true;
        } else if (action.equals("remove")) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /modseeker whitelist remove <player>");
                return true;
            }

            reportWhitelistResult(sender, whitelistManager.removePlayer(args[2]));
            return true;
        } else if (action.equals("show")) {
            List<WhitelistManager.WhitelistEntry> entries = whitelistManager.getEntries();
            if (entries.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "No players are currently whitelisted.");
            } else {
                sender.sendMessage(ChatColor.GREEN + "Whitelisted identities (" + entries.size() + "):");
                for (WhitelistManager.WhitelistEntry entry : entries) {
                    sender.sendMessage("   " + entry.name + " (" + entry.uuid + ")");
                }
            }
            return true;
        } else {
            sender.sendMessage(ChatColor.RED + "Usage: /modseeker whitelist <add|remove|show>");
            return true;
        }
    }

    private void reportWhitelistResult(CommandSender sender, WhitelistManager.MutationResult result) {
        switch (result.status()) {
            case ADDED -> {
                sender.sendMessage(ChatColor.GREEN + "Player " + result.name() + " (" + result.uuid()
                        + ") added to the whitelist.");
                plugin.logInfo(ChatColor.YELLOW + "Whitelist added " + result.name() + " (" + result.uuid() + ")");
                plugin.activateWhitelistBypass(result.uuid());
            }
            case ALREADY_PRESENT -> sender.sendMessage(ChatColor.YELLOW + "Player " + result.name()
                    + " is already whitelisted.");
            case REMOVED -> {
                sender.sendMessage(ChatColor.GREEN + "Player " + result.name() + " (" + result.uuid()
                        + ") removed from the whitelist.");
                plugin.logInfo(ChatColor.YELLOW + "Whitelist removed " + result.name() + " (" + result.uuid() + ")");
            }
            case NOT_FOUND -> sender.sendMessage(ChatColor.YELLOW + "That player is not in the whitelist.");
            case LOOKUP_FAILED -> sender.sendMessage(ChatColor.RED + result.detail()
                    + ". Use /modseeker whitelist add <name> <uuid> if needed.");
            case SAVE_FAILED -> {
                sender.sendMessage(ChatColor.RED + "Whitelist update failed; no changes were applied.");
                plugin.logInfo(ChatColor.YELLOW + result.detail());
            }
            case INVALID_INPUT -> sender.sendMessage(ChatColor.RED + result.detail());
        }
    }

    private boolean handleStatusCommand(CommandSender sender) {
        PlayerDataManager pdm = plugin.getPlayerDataManager();

        boolean modVerif = configManager.isModVerificationEnabled();
        boolean launcherVerif = configManager.isLauncherVerificationEnabled();
        boolean allowUnknown = configManager.isAllowUnknownClients();
        boolean allowFloodgate = configManager.isAllowFloodgate();

        int whitelistCount = whitelistManager.getEntries().size();
        int blacklistCount = blacklistManager.getBlacklistedMods().size();
        int enabledLaunchers = launcherListManager.getEnabledLaunchers().size();
        int totalLaunchers = launcherListManager.getLaunchers().size();

        int onlinePlayers = Bukkit.getOnlinePlayers().size();
        int verifiedCount = 0;
        int inHandshake = pdm.getPlayerHandshakes().size();

        for (java.util.UUID uid : pdm.getApprovedPlayers()) {
            if (Bukkit.getPlayer(uid) != null) {
                verifiedCount++;
            }
        }

        String modVerifStr = modVerif ? (ChatColor.GREEN + "ON") : (ChatColor.RED + "OFF");
        String launcherVerifStr = launcherVerif ? (ChatColor.GREEN + "ON") : (ChatColor.RED + "OFF");
        String allowUnknownStr = allowUnknown ? (ChatColor.GREEN + "true") : (ChatColor.RED + "false");
        String allowFloodStr = allowFloodgate ? (ChatColor.GREEN + "true") : (ChatColor.RED + "false");

        ModAllowlistManager allow = plugin.getAllowlistManager();
        boolean advanced = configManager.isAdvancedModVerificationEnabled();
        String advancedStr = advanced ? (ChatColor.GREEN + "ON") : (ChatColor.RED + "OFF");

        sender.sendMessage(ChatColor.GOLD + "═══════ ModSeeker " + ProtocolConstants.PLUGIN_VERSION + " Status ═══════");
        sender.sendMessage(ChatColor.GRAY + "⚙ ModVerification: " + modVerifStr);
        sender.sendMessage(ChatColor.GRAY + "⚙ Advanced (hash) mode: " + advancedStr);
        sender.sendMessage(ChatColor.GRAY + "⚙ LauncherVerification: " + launcherVerifStr);
        sender.sendMessage(ChatColor.GRAY + "⚙ AllowUnknownClients: " + allowUnknownStr);
        sender.sendMessage(ChatColor.GRAY + "⚙ AllowFloodgate: " + allowFloodStr);
        sender.sendMessage(ChatColor.DARK_GRAY + "──────────────────────────────────");
        sender.sendMessage(ChatColor.GRAY + "📋 Whitelisted Players: " + ChatColor.WHITE + whitelistCount);
        sender.sendMessage(ChatColor.GRAY + "📋 Blacklisted Mods: " + ChatColor.WHITE + blacklistCount);
        sender.sendMessage(ChatColor.GRAY + "📋 Allowlist Hashes: " + ChatColor.WHITE + allow.size()
                + ChatColor.GRAY + " (" + allow.essentialCount() + " essential)"
                + (allow.isLoadFailed() ? ChatColor.RED + " [LOAD FAILED]" : ""));
        sender.sendMessage(ChatColor.GRAY + "📋 Enabled Launchers: " + ChatColor.WHITE + enabledLaunchers + "/" + totalLaunchers);
        sender.sendMessage(ChatColor.DARK_GRAY + "──────────────────────────────────");
        sender.sendMessage(ChatColor.GRAY + "👥 Online Players: " + ChatColor.WHITE + onlinePlayers);
        sender.sendMessage(ChatColor.GREEN + "✅ Verified: " + ChatColor.WHITE + verifiedCount);
        sender.sendMessage(ChatColor.AQUA + "🔄 In Handshake: " + ChatColor.WHITE + inHandshake);
        sender.sendMessage(ChatColor.GOLD + "═══════════════════════════════════");

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (command.getName().equalsIgnoreCase("modseeker")) {
            if (args.length == 1) {
                for (String subCommand : SUBCOMMANDS) {
                    if (hasCommandPermission(sender, subCommand)) {
                        completions.add(subCommand);
                    }
                }
            } else if (args.length == 2) {
                if (!hasCommandPermission(sender, args[0])) {
                    return List.of();
                }
                if (args[0].equalsIgnoreCase("modallowlist")) {
                    completions.add("add");
                    completions.add("remove");
                    completions.add("show");
                } else if (args[0].equalsIgnoreCase("modblacklist")) {
                    completions.add("add");
                    completions.add("remove");
                    completions.add("show");
                } else if (args[0].equalsIgnoreCase("whitelist")) {
                    completions.add("add");
                    completions.add("remove");
                    completions.add("show");
                }
            } else if (args.length == 3) {
                if (hasCommandPermission(sender, "seek")
                        && args[0].equalsIgnoreCase("seek") && args[1].equalsIgnoreCase("mod")) {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        completions.add(player.getName());
                    }
                }
            }
        }

        String input = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return completions.stream()
                .filter(completion -> completion.toLowerCase(Locale.ROOT).startsWith(input))
                .toList();
    }
}

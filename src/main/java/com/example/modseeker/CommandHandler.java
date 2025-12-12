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
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Handles commands for ModSeeker plugin
 */
public class CommandHandler implements TabExecutor {

    private final ModSeekerPlugin plugin;
    private final BlacklistManager blacklistManager;
    private final ConfigManager configManager;
    private final WhitelistManager whitelistManager;

    public CommandHandler(ModSeekerPlugin plugin, BlacklistManager blacklistManager, ConfigManager configManager,
            WhitelistManager whitelistManager) {
        this.plugin = plugin;
        this.blacklistManager = blacklistManager;
        this.configManager = configManager;
        this.whitelistManager = whitelistManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("modseeker")) {
            if (args.length < 1) {
                sender.sendMessage(ChatColor.RED + "Usage: /modseeker <seek|modblacklist|whitelist|reload|status>");
                return true;
            }

            String subCommand = args[0].toLowerCase();

            switch (subCommand) {
                case "seek":
                    return handleSeekCommand(sender, args);
                case "modblacklist":
                    return handleModBlacklistCommand(sender, args);
                case "whitelist":
                    return handleWhitelistCommand(sender, args);
                case "reload":
                    configManager.loadConfig();
                    blacklistManager.loadBlacklist();
                    whitelistManager.loadWhitelist();
                    sender.sendMessage(ChatColor.GREEN + "ModSeeker configuration and lists reloaded successfully.");
                    return true;
                case "status":
                    sender.sendMessage(ChatColor.RED + "Status command not implemented yet.");
                    return true;
                default:
                    sender.sendMessage(ChatColor.RED
                            + "Unknown subcommand. Usage: /modseeker <seek|modblacklist|whitelist|reload|status>");
                    return true;
            }
        }
        return false;
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

        UUID playerId = targetPlayer.getUniqueId();
        PlayerDataManager.PlayerModCheckData seekCheckData = new PlayerDataManager.PlayerModCheckData(playerId,
                playerName);

        seekCheckData.handshakeCompleted = false;
        plugin.getSeekRequests().put(playerId, seekCheckData);

        plugin.getMessageHandler().sendModListRequest(targetPlayer, seekCheckData);
        sender.sendMessage(ChatColor.GREEN + "Sent mod list request to " + playerName + ". Check chat for results.");
        return true;
    }

    private boolean handleModBlacklistCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /modseeker modblacklist <add|remove|show>");
            return true;
        }

        String action = args[1].toLowerCase();

        if (action.equals("add")) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /modseeker modblacklist add <modId>");
                return true;
            }

            String modId = args[2].toLowerCase();
            if (blacklistManager.addBlacklistedMod(modId)) {
                blacklistManager.saveBlacklist();
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

            String modId = args[2].toLowerCase();
            if (blacklistManager.removeBlacklistedMod(modId)) {
                blacklistManager.saveBlacklist();
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

        String action = args[1].toLowerCase();

        if (action.equals("add")) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /modseeker whitelist add <player>");
                return true;
            }

            String playerName = args[2];
            addToWhitelist(playerName);
            sender.sendMessage(ChatColor.GREEN + "Player " + playerName + " added to whitelist.");
            return true;
        } else if (action.equals("remove")) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /modseeker whitelist remove <player>");
                return true;
            }

            String playerName = args[2];
            removeFromWhitelist(playerName);
            sender.sendMessage(ChatColor.GREEN + "Player " + playerName + " removed from whitelist.");
            return true;
        } else if (action.equals("show")) {
            Set<String> whitelistedNames = whitelistManager.getWhitelistedNames();
            if (whitelistedNames.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "No players are currently whitelisted.");
            } else {
                sender.sendMessage(ChatColor.GREEN + "Whitelisted players (" + whitelistedNames.size() + "):");
                sender.sendMessage("   " + String.join(", ", whitelistedNames));
            }
            return true;
        } else {
            sender.sendMessage(ChatColor.RED + "Usage: /modseeker whitelist <add|remove|show>");
            return true;
        }
    }

    private void addToWhitelist(String playerName) {
        UUID uuid = whitelistManager.addPlayer(playerName);
        if (uuid != null) {
            plugin.logInfo("✅ Added " + playerName + " to whitelist (UUID: " + uuid + ")");
        } else {
            plugin.logInfo("⚠️ Could not find UUID for " + playerName + " - added anyway (offline mode calculation)");
        }
    }

    private void removeFromWhitelist(String playerName) {
        if (whitelistManager.removePlayer(playerName)) {
            plugin.logInfo("✅ Removed " + playerName + " from whitelist");
        } else {
            plugin.logInfo("⚠️ " + playerName + " was not in the whitelist");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (command.getName().equalsIgnoreCase("modseeker")) {
            if (args.length == 1) {
                completions.add("seek");
                completions.add("modblacklist");
                completions.add("whitelist");
                completions.add("reload");
                completions.add("status");
            } else if (args.length == 2) {
                if (args[0].equalsIgnoreCase("modblacklist")) {
                    completions.add("add");
                    completions.add("remove");
                    completions.add("show");
                } else if (args[0].equalsIgnoreCase("whitelist")) {
                    completions.add("add");
                    completions.add("remove");
                    completions.add("show");
                }
            } else if (args.length == 3) {
                if (args[0].equalsIgnoreCase("seek") && args[1].equalsIgnoreCase("mod")) {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        completions.add(player.getName());
                    }
                }
            }
        }

        return completions;
    }
}
// SPDX-FileCopyrightText: 2025 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.verification;

import com.example.modseeker.ModSeekerPlugin;
import com.example.modseeker.config.ConfigManager;
import com.example.modseeker.logging.UtilityHelper;
import com.example.modseeker.policy.BlacklistManager;
import com.example.modseeker.policy.ModAllowlistManager;
import com.example.modseeker.protocol.MessageHandler;
import com.example.modseeker.protocol.VerifiedModPayload;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;





public class VerificationService {

    private final ModSeekerPlugin plugin;
    private final PlayerDataManager playerDataManager;
    private final MessageHandler messageHandler;
    private final ConfigManager configManager;
    private final BlacklistManager blacklistManager;
    private final SecurityManager securityManager;

    public VerificationService(ModSeekerPlugin plugin, PlayerDataManager playerDataManager,
            MessageHandler messageHandler, ConfigManager configManager, BlacklistManager blacklistManager,
            SecurityManager securityManager) {
        this.plugin = plugin;
        this.playerDataManager = playerDataManager;
        this.messageHandler = messageHandler;
        this.configManager = configManager;
        this.blacklistManager = blacklistManager;
        this.securityManager = securityManager;
    }




    public void startModCheckAfterHandshake(Player player, PlayerDataManager.HandshakeData handshakeData) {
        if (playerDataManager.isVerificationBypassed(player.getUniqueId())) {
            return;
        }
        PlayerDataManager.PlayerModCheckData checkData = new PlayerDataManager.PlayerModCheckData(player.getUniqueId(),
                handshakeData.playerName);
        playerDataManager.getPlayerModChecks().put(player.getUniqueId(), checkData);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && playerDataManager.isCurrentHandshake(handshakeData)
                        && playerDataManager.isCurrentModCheck(checkData)) {
                    messageHandler.sendModListRequest(player, checkData);
                }
            }
        }.runTaskLater(plugin, 20L);
    }

    public void handleVerifiedPayload(Player player, VerifiedModPayload payload) {
        UUID playerId = player.getUniqueId();
        if (playerDataManager.isVerificationBypassed(playerId)) {
            return;
        }
        PlayerDataManager.PlayerModCheckData checkData = playerDataManager.getPlayerModChecks().get(playerId);
        if (checkData == null || !checkData.checkId.equals(payload.checkId())) {
            player.kickPlayer(ChatColor.RED + "Security verification failed.");
            return;
        }
        synchronized (checkData) {
            if (checkData.verificationComplete) {
                return;
            }
            checkData.verificationComplete = true;
        }

        if (checkData.timeoutTaskId != -1) {
            Bukkit.getScheduler().cancelTask(checkData.timeoutTaskId);
            checkData.timeoutTaskId = -1;
        }
        checkData.modListReceived = true;

        List<String> modList = payload.modIds();
        List<String> scannedMods = payload.allModIds();
        plugin.getModCache().put(playerId, player.getName(), payload.mods(), payload.hashes());

        boolean advanced = checkData.layer3Advanced;
        if (configManager.isSHOW_MOD_LIST() && !modList.isEmpty()) {
            UtilityHelper.logModList(player.getName(), modList, true,
                    configManager.isONE_MOD_PER_LINE(), true, !advanced,
                    advanced ? Set.of() : blacklistManager.getBlacklistedMods());
        }

        if (advanced) {
            if (!withinModCount(player, modList)) {
                return;
            }
            verifyHashes(player, payload.hashes(), modList);
            return;
        }

        if (blacklistManager.isLoadFailed()) {
            plugin.logInfo(ChatColor.YELLOW + "Normal verification is unavailable for " + player.getName());
            player.kickPlayer(ChatColor.RED + "Mod Verification Failed");
            return;
        }

        if (scannedMods.contains("unknown")) {
            plugin.logInfo(ChatColor.YELLOW + "Normal verification cannot identify every mod for " + player.getName());
            player.kickPlayer(ChatColor.RED + "Mod Verification Failed");
            return;
        }

        List<String> blacklisted = blacklistManager.checkForBlacklistedMods(scannedMods);
        if (!blacklisted.isEmpty()) {
            plugin.getDiscordWebhook().sendBlacklistViolation(player.getName(), blacklisted);
            String message = configManager.getKickMessages().getOrDefault("blacklistedMods",
                    "Blacklisted mods detected");
            message = message.replace("{mods}", String.join(", ", blacklisted))
                    .replace("{plural}", blacklisted.size() > 1 ? "s" : "");
            player.kickPlayer(ChatColor.RED + message);
            return;
        }

        if (!withinModCount(player, modList)) {
            return;
        }

        grantAccess(player, playerId, player.getName(), modList);
    }

    private boolean withinModCount(Player player, List<String> modList) {
        if (!configManager.isEnableModCountThreshold() || modList.size() <= configManager.getMaxModCount()) {
            return true;
        }
        plugin.getDiscordWebhook().sendVerificationFailed(player.getName(),
                "Too many mods (" + modList.size() + "/" + configManager.getMaxModCount() + ")");
        String message = configManager.getKickMessages().getOrDefault("modCountExceeded", "Too many mods")
                .replace("{maxMods}", String.valueOf(configManager.getMaxModCount()));
        player.kickPlayer(ChatColor.RED + message);
        return false;
    }

    private void verifyHashes(Player player, List<String> hashes, List<String> modList) {
        ModAllowlistManager allowlist = plugin.getAllowlistManager();
        if (hashes.isEmpty() || allowlist.isLoadFailed() || allowlist.isEmpty()) {
            plugin.logInfo(ChatColor.YELLOW + "Advanced verification is unavailable for " + player.getName());
            player.kickPlayer(ChatColor.RED + "Mod Verification Failed");
            return;
        }

        List<String> unknown = hashes.stream().filter(hash -> !allowlist.isHashAllowed(hash)).toList();
        if (!unknown.isEmpty()) {
            reverseLogUnknownAsync(unknown);
            plugin.getDiscordWebhook().sendVerificationFailed(player.getName(),
                    "Unauthorized mods (" + unknown.size() + ")");
            player.kickPlayer(ChatColor.RED + "Unauthorized mods detected (" + unknown.size()
                    + "). Use only server-approved mods.");
            return;
        }

        grantAccess(player, player.getUniqueId(), player.getName(), modList);
    }

    private void reverseLogUnknownAsync(List<String> unknown) {
        final List<String> copy = new ArrayList<>(unknown);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (String h : copy) {
                String name = plugin.getHashFetcher().reverseName(h);
                String shown = name != null ? name
                        : ("unknown (" + h.substring(0, Math.min(12, h.length())) + "…)");
                plugin.logInfo(ChatColor.YELLOW + "   unauthorized: " + shown);
            }
        });
    }

    private void grantAccess(Player player, UUID playerId, String playerName, List<String> modList) {
        plugin.logInfo("Verification passed for " + playerName + " (" + modList.size() + " reported IDs)");
        playerDataManager.addApprovedPlayer(playerId);
        securityManager.unfreezePlayer(playerId);

        plugin.getDiscordWebhook().sendModListReceived(playerName, modList, true);

        if (configManager.isEnablePlayerNotifications()) {
            player.sendMessage(ChatColor.GREEN + configManager.getWelcomeMessage());
        }
    }

    public void cleanupVerificationData(UUID playerId) {
        PlayerDataManager.PlayerModCheckData data = playerDataManager.getPlayerModChecks().remove(playerId);
        if (data != null && data.timeoutTaskId != -1) {
            Bukkit.getScheduler().cancelTask(data.timeoutTaskId);
        }
        playerDataManager.removeApprovedPlayer(playerId);
    }

}

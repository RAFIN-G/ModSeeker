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

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.ChatColor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/**
 * Manages player data and session tracking for ModSeeker plugin
 */
public class PlayerDataManager {

    // Player tracking with handshake support
    private final Map<UUID, PlayerModCheckData> playerModChecks = new ConcurrentHashMap<>();
    private final Map<UUID, HandshakeData> playerHandshakes = new ConcurrentHashMap<>();
    private final Set<UUID> approvedPlayers = ConcurrentHashMap.newKeySet();

    // Player whitelist
    private Set<String> whitelist = new HashSet<>();

    /**
     * Handshake data for tracking client-server handshake process
     */
    public static class HandshakeData {
        public final UUID playerId;
        public final String playerName;
        public final String handshakeId;
        public long startTime;

        public boolean presenceAnnounced = false;
        public boolean acknowledgmentSent = false;
        public String presenceMessage;
        public String modVersion;

        // Fields for retry and timeout handling
        public int retryCount = 0;
        public int timeoutTaskId = -1; // For sendHandshakeRequest timeout
        public int presenceTimeoutTaskId = -1; // For startHandshakeTimeoutTimer timeout

        public HandshakeData(UUID playerId, String playerName) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.handshakeId = "hs-" + UUID.randomUUID().toString().substring(0, 8);
            this.startTime = System.currentTimeMillis();
        }
    }

    /**
     * Player mod check data for tracking mod verification process
     */
    public static class PlayerModCheckData {
        public final UUID playerId;
        public final String playerName;
        public final String checkId;
        public final long startTime;

        public int attemptCount = 0;
        public int retryCount = 0;
        public long lastRequestTime = 0;
        public boolean modListReceived = false;
        public String modListResponse;

        public boolean handshakeCompleted = false;
        public HandshakeData handshakeData;

        // Verification results
        public List<String> detectedMods = new ArrayList<>();
        public boolean verificationComplete = false;
        public String verificationResult;
        public int timeoutTaskId = -1;

        public PlayerModCheckData(UUID playerId, String playerName) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.checkId = "chk-" + UUID.randomUUID().toString().substring(0, 8);
            this.startTime = System.currentTimeMillis();
        }
    }

    // Getters for the maps
    public Map<UUID, PlayerModCheckData> getPlayerModChecks() {
        return playerModChecks;
    }

    public Map<UUID, HandshakeData> getPlayerHandshakes() {
        return playerHandshakes;
    }

    public Set<UUID> getApprovedPlayers() {
        return approvedPlayers;
    }

    // Whitelist management
    public Set<String> getWhitelist() {
        return whitelist;
    }

    public void setWhitelist(Set<String> whitelist) {
        this.whitelist = whitelist;
    }

    // Player approval methods
    public void addApprovedPlayer(UUID playerId) {
        approvedPlayers.add(playerId);
    }

    public void removeApprovedPlayer(UUID playerId) {
        approvedPlayers.remove(playerId);
    }

    public boolean isPlayerApproved(UUID playerId) {
        return approvedPlayers.contains(playerId);
    }
}
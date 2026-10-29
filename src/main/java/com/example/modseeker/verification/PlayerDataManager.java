// SPDX-FileCopyrightText: 2025 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.verification;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;




public class PlayerDataManager {


    private final Map<UUID, PlayerModCheckData> playerModChecks = new ConcurrentHashMap<>();
    private final Map<UUID, HandshakeData> playerHandshakes = new ConcurrentHashMap<>();
    private final Set<UUID> approvedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BypassReason> bypassReasons = new ConcurrentHashMap<>();

    public enum BypassReason {
        WHITELIST,
        FLOODGATE
    }




    public static class HandshakeData {
        public final UUID playerId;
        public final String playerName;
        public final String handshakeId;
        public long startTime;

        public boolean presenceAnnounced = false;
        public boolean acknowledgmentSent = false;
        public int retryCount = 0;
        public int timeoutTaskId = -1;
        public int presenceTimeoutTaskId = -1;

        public String challengeNonce;
        public long challengeTimestamp;
        public long challengeIssuedNanos;
        public boolean challengePassed = false;
        public final AtomicBoolean challengeResponseClaimed = new AtomicBoolean();

        public HandshakeData(UUID playerId, String playerName) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.handshakeId = "hs-" + UUID.randomUUID().toString().substring(0, 8);
            this.startTime = System.currentTimeMillis();
        }
    }




    public static class PlayerModCheckData {
        public final UUID playerId;
        public final String playerName;
        public final String checkId;
        public int attemptCount = 0;
        public boolean modListReceived = false;
        public final AtomicBoolean layer3ResponseClaimed = new AtomicBoolean();
        public boolean verificationComplete = false;
        public int timeoutTaskId = -1;


        public String layer3Nonce;
        public boolean layer3Advanced;
        public long layer3IssuedNanos = System.nanoTime();

        public PlayerModCheckData(UUID playerId, String playerName) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.checkId = "chk-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }


    public Map<UUID, PlayerModCheckData> getPlayerModChecks() {
        return playerModChecks;
    }

    public Map<UUID, HandshakeData> getPlayerHandshakes() {
        return playerHandshakes;
    }

    public boolean isCurrentHandshake(HandshakeData data) {
        return data != null && playerHandshakes.get(data.playerId) == data;
    }

    public boolean isCurrentModCheck(PlayerModCheckData data) {
        return data != null && playerModChecks.get(data.playerId) == data;
    }

    public Set<UUID> getApprovedPlayers() {
        return approvedPlayers;
    }


    public void addApprovedPlayer(UUID playerId) {
        approvedPlayers.add(playerId);
    }

    public void removeApprovedPlayer(UUID playerId) {
        approvedPlayers.remove(playerId);
    }

    public boolean isPlayerApproved(UUID playerId) {
        return approvedPlayers.contains(playerId);
    }

    public void beginSession(UUID playerId) {
        approvedPlayers.remove(playerId);
        bypassReasons.remove(playerId);
    }

    public void setBypassReason(UUID playerId, BypassReason reason) {
        bypassReasons.put(playerId, reason);
    }

    public BypassReason getBypassReason(UUID playerId) {
        return bypassReasons.get(playerId);
    }

    public boolean isVerificationBypassed(UUID playerId) {
        return bypassReasons.containsKey(playerId);
    }

    public void clearBypass(UUID playerId) {
        bypassReasons.remove(playerId);
    }
}

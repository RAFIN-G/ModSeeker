// SPDX-FileCopyrightText: 2025 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.verification;

import com.example.modseeker.ModSeekerPlugin;
import com.example.modseeker.protocol.ProtocolConstants;
import com.example.modseeker.protocol.VerifiedModPayload;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

public class SecurityManager implements Listener {

    private static final Pattern CHECK_ID = Pattern.compile("chk-[0-9a-f]{8}");
    private static final Pattern NONCE = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern RUN = Pattern.compile("[2-9]|[1-9][0-9]{1,2}");
    private static final Pattern CLIENT_ERROR = Pattern.compile("[A-Z][A-Z_]{2,39}");
    private static final int MAX_MODS = 512;

    private final ModSeekerPlugin plugin;
    private final PublicKey publicKey;

    private final Set<UUID> frozenPlayers = ConcurrentHashMap.newKeySet();

    private final java.security.PrivateKey privateKey;

    public SecurityManager(ModSeekerPlugin plugin, ServerKeyStore serverKeyStore) {
        this.plugin = plugin;
        this.privateKey = serverKeyStore.getPrivateKey();
        this.publicKey = loadPublicKey();
    }

    public String decrypt(String ciphertext) {
        try {
            if (!(privateKey instanceof RSAKey rsaKey) || ciphertext == null || ciphertext.length() > 65536) {
                return null;
            }
            String[] parts = ciphertext.split("\\|", 4);
            if (parts.length != 3) {
                return null;
            }
            byte[] decodedKeyBytes = Base64.getDecoder().decode(parts[0]);
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] encryptedData = Base64.getDecoder().decode(parts[2]);
            if (decodedKeyBytes.length != (rsaKey.getModulus().bitLength() + 7) / 8
                    || iv.length != 12 || encryptedData.length < 16
                    || encryptedData.length > 49152) {
                return null;
            }
            javax.crypto.Cipher rsaCipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            rsaCipher.init(javax.crypto.Cipher.DECRYPT_MODE, privateKey,
                    new javax.crypto.spec.OAEPParameterSpec("SHA-256", "MGF1",
                            java.security.spec.MGF1ParameterSpec.SHA256,
                            javax.crypto.spec.PSource.PSpecified.DEFAULT));
            byte[] aesKey = rsaCipher.doFinal(decodedKeyBytes);
            if (aesKey.length != 32) {
                return null;
            }
            javax.crypto.Cipher aesCipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(aesKey, "AES");
            aesCipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] decodedData = aesCipher.doFinal(encryptedData);
            if (decodedData.length > 32768) {
                return null;
            }
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(decodedData)).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private PublicKey loadPublicKey() {
        try {
            String publicKeyStr = ProtocolConstants.CLIENT_PUBLIC_KEY;

            if (publicKeyStr == null || publicKeyStr.contains("PLACEHOLDER") || publicKeyStr.isEmpty()) {
                throw new IllegalStateException("Client public key is missing");
            }

            byte[] keyBytes = Base64.getDecoder().decode(publicKeyStr);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PublicKey key = kf.generatePublic(spec);
            if (!(key instanceof RSAKey rsa) || rsa.getModulus().bitLength() < 2048) {
                throw new IllegalStateException("Client public key is not RSA-2048 or stronger");
            }
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load client public key", e);
        }
    }

    public boolean verifySignature(String data, String signatureBase64) {
        try {
            if (data == null || signatureBase64 == null || signatureBase64.length() > 512) {
                return false;
            }
            Signature sign = Signature.getInstance("SHA256withRSA");
            sign.initVerify(publicKey);
            sign.update(data.getBytes(StandardCharsets.UTF_8));

            byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);
            return sign.verify(signatureBytes);

        } catch (Exception e) {
            plugin.logInfo("❌ Security: Signature verification error: " + e.getMessage());
            return false;
        }
    }

    public void handleEncryptedResponse(Player player, JsonObject envelope) {
        if (plugin.getPlayerDataManager().isVerificationBypassed(player.getUniqueId())) {
            return;
        }
        try {
            PlayerDataManager.PlayerModCheckData checkData = plugin.getPlayerDataManager()
                    .getPlayerModChecks().get(player.getUniqueId());
            if (checkData == null || checkData.layer3Nonce == null) {
                deny(player, "No active mod verification exists");
                return;
            }
            if (checkData.verificationComplete) {
                return;
            }
            if (!checkData.layer3ResponseClaimed.compareAndSet(false, true)) {
                return;
            }

            long deadlineNanos = (long) plugin.getTIMEOUT_SECONDS() * 1_000_000_000L;
            if (System.nanoTime() - checkData.layer3IssuedNanos > deadlineNanos) {
                deny(player, "Sealed response exceeded the freshness deadline");
                return;
            }

            if (!envelope.has("ciphertext") || !envelope.get("ciphertext").isJsonPrimitive()
                    || !envelope.get("ciphertext").getAsJsonPrimitive().isString()) {
                deny(player, "Encrypted response is missing ciphertext");
                return;
            }

            String ciphertext = envelope.get("ciphertext").getAsString();
            String decrypted = decrypt(ciphertext);
            if (decrypted == null) {
                deny(player, CLIENT_ERROR.matcher(ciphertext).matches() ? "Client reported " + ciphertext
                        : "Encrypted response could not be decrypted");
                return;
            }

            ParsedSealedPayload parsed = parseSealedPayload(decrypted, checkData.layer3Advanced);
            if (!parsed.checkId().equals(checkData.checkId) || !parsed.nonce().equals(checkData.layer3Nonce)) {
                deny(player, "Sealed payload does not match the active verification");
                return;
            }

            if (!verifySignature(ProtocolConstants.LAYER_3_DOMAIN + parsed.signedFields(), parsed.signature())) {
                deny(player, "Sealed payload signature is invalid");
                return;
            }
            if (!plugin.getPlayerDataManager().isCurrentModCheck(checkData)) {
                deny(player, "Mod verification session changed");
                return;
            }

            plugin.handleVerifiedModPayload(player, parsed.payload());
        } catch (IllegalArgumentException e) {
            deny(player, e.getMessage());
        } catch (Exception e) {
            deny(player, "Encrypted response handling failed");
        }
    }

    static ParsedSealedPayload parseSealedPayload(String decrypted, boolean advanced) {
        if (decrypted == null || decrypted.length() > 32768) {
            throw new IllegalArgumentException("Sealed payload size is invalid");
        }
        String[] fields = decrypted.split("\\|", -1);
        if (fields.length != 5) {
            throw new IllegalArgumentException("Sealed payload has an invalid field count");
        }

        String checkId = field(fields[0], "checkId=");
        String nonce = field(fields[1], "nonce=");
        String modsValue = field(fields[2], "mods=");
        String hashesValue = field(fields[3], "hashes=");
        String signature = field(fields[4], "sig=");
        if (checkId == null || nonce == null || modsValue == null || hashesValue == null || signature == null) {
            throw new IllegalArgumentException("Sealed payload field order is invalid");
        }
        if (!CHECK_ID.matcher(checkId).matches() || !NONCE.matcher(nonce).matches()) {
            throw new IllegalArgumentException("Sealed payload identifiers are invalid");
        }
        if (signature.isEmpty() || signature.length() > 512) {
            throw new IllegalArgumentException("Sealed payload signature is invalid");
        }

        List<String> entries = splitList(modsValue);
        List<String> hashes = expandHashes(hashesValue);
        if (entries.isEmpty() || entries.size() > MAX_MODS || hashes == null
                || entries.stream().anyMatch(value -> value.isBlank() || value.length() > 256)) {
            throw new IllegalArgumentException("Sealed mod data is malformed or unaligned");
        }
        List<String> topLevelMods = new ArrayList<>();
        List<String> nestedIds = new ArrayList<>();
        Set<String> topLevelIds = new HashSet<>();
        for (int index = 0; index < entries.size(); index++) {
            String entry = entries.get(index);
            boolean nested = entry.startsWith("+");
            String value = nested ? entry.substring(1) : entry;
            String modId = stripVersion(value);
            if (modId.isEmpty()) {
                throw new IllegalArgumentException("Sealed mod identity is invalid at index " + index);
            }
            if (nested) {
                nestedIds.add(modId);
            } else {
                if (!topLevelIds.add(modId)) {
                    throw new IllegalArgumentException("Sealed top-level mod identity is duplicated at index " + index);
                }
                topLevelMods.add(entry);
            }
        }
        if (topLevelMods.isEmpty() || hashes.size() != (advanced ? topLevelMods.size() : 0)) {
            throw new IllegalArgumentException("Sealed mod data is malformed or unaligned");
        }

        int signatureIndex = decrypted.length() - fields[4].length() - 1;
        String signedFields = decrypted.substring(0, signatureIndex);
        VerifiedModPayload payload = new VerifiedModPayload(checkId, topLevelMods, nestedIds, hashes);
        return new ParsedSealedPayload(checkId, nonce, signedFields, signature, payload);
    }

    private static String field(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()) : null;
    }

    private static String stripVersion(String entry) {
        int separator = entry.indexOf(':');
        return separator < 0 ? entry : entry.substring(0, separator);
    }

    private static List<String> expandHashes(String value) {
        List<String> hashes = new ArrayList<>();
        for (String entry : splitList(value)) {
            int star = entry.indexOf('*');
            String hex = hexOfDigest(star < 0 ? entry : entry.substring(0, star));
            if (hex == null || star >= 0 && !RUN.matcher(entry.substring(star + 1)).matches()) {
                return null;
            }
            int run = star < 0 ? 1 : Integer.parseInt(entry.substring(star + 1));
            if (hashes.size() + run > MAX_MODS) {
                return null;
            }
            hashes.addAll(Collections.nCopies(run, hex));
        }
        return hashes;
    }

    private static String hexOfDigest(String encoded) {
        try {
            byte[] digest = Base64.getDecoder().decode(encoded);
            return digest.length == 64 && Base64.getEncoder().encodeToString(digest).equals(encoded)
                    ? HexFormat.of().formatHex(digest) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> splitList(String value) {
        if (value.isEmpty()) {
            return List.of();
        }
        String[] values = value.split(",", -1);
        List<String> result = new ArrayList<>(values.length);
        for (String item : values) {
            result.add(item);
        }
        return List.copyOf(result);
    }

    private void deny(Player player, String reason) {
        plugin.logInfo("Security verification failed for " + player.getName() + ": " + reason);
        player.kickPlayer("§cSecurity verification failed.");
    }

    record ParsedSealedPayload(String checkId, String nonce, String signedFields, String signature,
            VerifiedModPayload payload) {
    }

    public void freezePlayer(UUID playerId) {
        if (!plugin.getPlayerDataManager().isVerificationBypassed(playerId)) {
            frozenPlayers.add(playerId);
        }
    }

    public void unfreezePlayer(UUID playerId) {
        frozenPlayers.remove(playerId);
    }

    public boolean isFrozen(UUID playerId) {
        return frozenPlayers.contains(playerId);
    }

    public void unfreezeAll() {
        frozenPlayers.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            if (event.getFrom().getX() != event.getTo().getX() ||
                    event.getFrom().getY() != event.getTo().getY() ||
                    event.getFrom().getZ() != event.getTo().getZ()) {
                event.setTo(event.getFrom());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (isFrozen(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            Player player = (Player) event.getDamager();
            if (isFrozen(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

}

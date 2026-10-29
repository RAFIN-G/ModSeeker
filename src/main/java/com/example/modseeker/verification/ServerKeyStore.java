// SPDX-FileCopyrightText: 2025 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.verification;

import com.example.modseeker.ModSeekerPlugin;

import org.bukkit.ChatColor;

import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.function.Consumer;

public class ServerKeyStore {
    private static final long MAX_KEY_FILE_BYTES = 16 * 1024;

    private final File keysDir;
    private final File privFile;
    private final File pubFile;
    private final Consumer<String> logger;

    private PrivateKey privateKey;
    private PublicKey publicKey;
    private String publicKeyBase64;
    private boolean ready;

    public ServerKeyStore(ModSeekerPlugin plugin) {
        this(plugin.getDataFolder(), plugin::logInfo);
    }

    ServerKeyStore(File dataFolder, Consumer<String> logger) {
        this.keysDir = new File(dataFolder, "keys");
        this.privFile = new File(keysDir, "server_private.key");
        this.pubFile = new File(keysDir, "server_public.key");
        this.logger = logger;
        load();
    }

    private void load() {
        try {
            Files.createDirectories(keysDir.toPath());
            boolean hasPrivate = privFile.isFile();
            boolean hasPublic = pubFile.isFile();
            if (hasPrivate != hasPublic) {
                throw new IllegalStateException("Server key pair is incomplete");
            }
            if (hasPrivate) {
                loadExisting();
            } else {
                generateAndSave();
            }
            verifyPair(privateKey, publicKey);
            ready = true;
        } catch (Exception e) {
            logger.accept(ChatColor.YELLOW + "ServerKeyStore init failed: " + e.getMessage());
            privateKey = null;
            publicKey = null;
            publicKeyBase64 = null;
            ready = false;
        }
    }

    private void loadExisting() throws Exception {
        String privB64 = readKey(privFile);
        String pubB64 = readKey(pubFile);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privB64)));
        publicKey = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pubB64)));
        publicKeyBase64 = pubB64;
    }

    private void generateAndSave() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair pair = kpg.generateKeyPair();
        this.privateKey = pair.getPrivate();
        this.publicKey = pair.getPublic();
        this.publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());

        String privB64 = Base64.getEncoder().encodeToString(privateKey.getEncoded());
        atomicWrite(privFile, privB64);
        atomicWrite(pubFile, publicKeyBase64);
        logger.accept(ChatColor.YELLOW + "Generated a new per-server key pair (keys/server_private.key)");
    }

    private void verifyPair(PrivateKey privateKey, PublicKey publicKey) throws Exception {
        if (!(privateKey instanceof RSAKey privateRsa) || !(publicKey instanceof RSAKey publicRsa)
                || privateRsa.getModulus().bitLength() < 2048
                || privateRsa.getModulus().bitLength() > 4096
                || !privateRsa.getModulus().equals(publicRsa.getModulus())) {
            throw new IllegalStateException("Server keys must be a matching RSA-2048 to RSA-4096 pair");
        }
        byte[] probe = "modseeker-server-key-check".getBytes(StandardCharsets.UTF_8);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(probe);
        byte[] signed = signature.sign();
        signature.initVerify(publicKey);
        signature.update(probe);
        if (!signature.verify(signed)) {
            throw new IllegalStateException("Server key pair verification failed");
        }
    }

    private void atomicWrite(File target, String content) throws Exception {
        Path parent = target.toPath().toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(parent, target.getName() + "-", ".tmp");
        try {
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private String readKey(File file) throws Exception {
        if (Files.size(file.toPath()) > MAX_KEY_FILE_BYTES) {
            throw new IllegalStateException("Server key file is too large");
        }
        return Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public String getPublicKeyBase64() {
        return publicKeyBase64;
    }

    public boolean isReady() {
        return ready;
    }
}

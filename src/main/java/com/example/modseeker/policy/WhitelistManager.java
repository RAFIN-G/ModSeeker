// SPDX-FileCopyrightText: 2025 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.policy;

import com.example.modseeker.ModSeekerPlugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;

import java.io.IOException;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public final class WhitelistManager {
    public enum Status {
        ADDED,
        ALREADY_PRESENT,
        REMOVED,
        NOT_FOUND,
        LOOKUP_FAILED,
        SAVE_FAILED,
        INVALID_INPUT
    }

    public record MutationResult(Status status, String name, UUID uuid, String detail) {
    }

    record ResolvedIdentity(UUID uuid, String name) {
    }

    @FunctionalInterface
    interface IdentityResolver {
        CompletableFuture<ResolvedIdentity> resolve(String name);
    }

    public static final class WhitelistEntry {
        public UUID uuid;
        public String name;
        public long addedAt;

        public WhitelistEntry(UUID uuid, String name) {
            this(uuid, name, System.currentTimeMillis());
        }

        WhitelistEntry(UUID uuid, String name, long addedAt) {
            this.uuid = uuid;
            this.name = name;
            this.addedAt = addedAt;
        }
    }

    private record Snapshot(Map<UUID, WhitelistEntry> byUuid, Map<String, Set<UUID>> byName) {
        private static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of());
    }

    private final Path whitelistFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final IdentityResolver identityResolver;
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile boolean loadFailed;

    public WhitelistManager(ModSeekerPlugin plugin) {
        this(plugin.getStorageFolder().toPath().resolve("whitelist.json"), new PaperIdentityResolver());
    }

    WhitelistManager(Path whitelistFile, IdentityResolver identityResolver) {
        this.whitelistFile = whitelistFile;
        this.identityResolver = identityResolver;
        loadWhitelist();
    }

    public synchronized boolean loadWhitelist() {
        if (!Files.exists(whitelistFile)) {
            if (!write(Map.of())) {
                loadFailed = true;
                return false;
            }
            snapshot = Snapshot.EMPTY;
            loadFailed = false;
            return true;
        }

        try (Reader reader = Files.newBufferedReader(whitelistFile, StandardCharsets.UTF_8)) {
            WhitelistEntry[] entries = gson.fromJson(reader, WhitelistEntry[].class);
            snapshot = buildSnapshot(entries == null ? List.of() : Arrays.asList(entries));
            loadFailed = false;
            return true;
        } catch (IOException | JsonParseException | IllegalArgumentException e) {
            loadFailed = true;
            return false;
        }
    }

    public boolean isWhitelisted(UUID uuid) {
        return snapshot.byUuid().containsKey(uuid);
    }

    public boolean isLoadFailed() {
        return loadFailed;
    }

    public CompletableFuture<MutationResult> addPlayer(String name) {
        String normalized = normalizeName(name);
        if (normalized == null) {
            return CompletableFuture.completedFuture(
                    new MutationResult(Status.INVALID_INPUT, name, null, "Player name is invalid"));
        }
        try {
            return identityResolver.resolve(normalized)
                    .orTimeout(5, TimeUnit.SECONDS)
                    .handle((identity, error) -> error == null
                            ? addResolved(identity, false)
                            : new MutationResult(Status.LOOKUP_FAILED, normalized, null, lookupFailure(error)));
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(
                    new MutationResult(Status.LOOKUP_FAILED, normalized, null, lookupFailure(e)));
        }
    }

    public MutationResult addPlayer(String name, UUID uuid) {
        String normalized = normalizeName(name);
        if (normalized == null || uuid == null) {
            return new MutationResult(Status.INVALID_INPUT, name, uuid, "Player name or UUID is invalid");
        }
        return addResolved(new ResolvedIdentity(uuid, normalized), true);
    }

    public synchronized MutationResult removePlayer(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.isBlank()) {
            return new MutationResult(Status.INVALID_INPUT, nameOrUuid, null, "Player name or UUID is invalid");
        }

        String normalizedInput = nameOrUuid.trim();
        UUID uuid = parseUuid(normalizedInput);
        if (uuid == null) {
            Set<UUID> matches = snapshot.byName().get(nameKey(normalizedInput));
            if (matches == null || matches.isEmpty()) {
                return new MutationResult(Status.NOT_FOUND, normalizedInput, null, null);
            }
            if (matches.size() > 1) {
                return new MutationResult(Status.INVALID_INPUT, normalizedInput, null,
                        "Multiple UUIDs use that name; remove the intended entry by UUID");
            }
            uuid = matches.iterator().next();
        }

        WhitelistEntry existing = snapshot.byUuid().get(uuid);
        if (existing == null) {
            return new MutationResult(Status.NOT_FOUND, nameOrUuid.trim(), uuid, null);
        }

        Map<UUID, WhitelistEntry> candidate = new LinkedHashMap<>(snapshot.byUuid());
        candidate.remove(uuid);
        if (!write(candidate)) {
            return new MutationResult(Status.SAVE_FAILED, existing.name, existing.uuid,
                    "Could not persist whitelist.json");
        }
        snapshot = buildSnapshot(candidate.values());
        loadFailed = false;
        return new MutationResult(Status.REMOVED, existing.name, existing.uuid, null);
    }

    public Set<String> getWhitelistedNames() {
        List<String> names = snapshot.byUuid().values().stream()
                .map(entry -> entry.name)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return Collections.unmodifiableSet(new LinkedHashSet<>(names));
    }

    public List<WhitelistEntry> getEntries() {
        return snapshot.byUuid().values().stream()
                .sorted(Comparator.comparing(entry -> entry.name, String.CASE_INSENSITIVE_ORDER))
                .map(entry -> new WhitelistEntry(entry.uuid, entry.name, entry.addedAt))
                .toList();
    }

    private synchronized MutationResult addResolved(ResolvedIdentity identity, boolean explicitUuid) {
        if (identity == null || identity.uuid() == null || normalizeName(identity.name()) == null) {
            return new MutationResult(Status.LOOKUP_FAILED, identity == null ? null : identity.name(),
                    identity == null ? null : identity.uuid(), "Profile lookup returned an incomplete identity");
        }

        String name = identity.name().trim();
        WhitelistEntry existing = snapshot.byUuid().get(identity.uuid());
        Set<UUID> existingNameUuids = snapshot.byName().getOrDefault(nameKey(name), Set.of());
        if (existing != null && nameKey(existing.name).equals(nameKey(name))) {
            return new MutationResult(Status.ALREADY_PRESENT, existing.name, existing.uuid, null);
        }
        if (!explicitUuid && !existingNameUuids.isEmpty() && !existingNameUuids.contains(identity.uuid())) {
            return new MutationResult(Status.INVALID_INPUT, name, identity.uuid(),
                    "That player name is already stored with another UUID");
        }

        Map<UUID, WhitelistEntry> candidate = new LinkedHashMap<>(snapshot.byUuid());
        long addedAt = existing == null ? System.currentTimeMillis() : existing.addedAt;
        candidate.put(identity.uuid(), new WhitelistEntry(identity.uuid(), name, addedAt));
        if (!write(candidate)) {
            return new MutationResult(Status.SAVE_FAILED, name, identity.uuid(), "Could not persist whitelist.json");
        }
        snapshot = buildSnapshot(candidate.values());
        loadFailed = false;
        return new MutationResult(Status.ADDED, name, identity.uuid(), null);
    }

    private Snapshot buildSnapshot(Collection<WhitelistEntry> entries) {
        Map<UUID, WhitelistEntry> byUuid = new LinkedHashMap<>();
        Map<String, Set<UUID>> byName = new LinkedHashMap<>();
        for (WhitelistEntry entry : entries) {
            if (entry == null || entry.uuid == null || normalizeName(entry.name) == null) {
                throw new IllegalArgumentException("Invalid whitelist entry");
            }
            String key = nameKey(entry.name);
            if (byUuid.containsKey(entry.uuid)) {
                throw new IllegalArgumentException("Duplicate whitelist entry");
            }
            WhitelistEntry normalized = new WhitelistEntry(entry.uuid, entry.name.trim(), entry.addedAt);
            byUuid.put(normalized.uuid, normalized);
            byName.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(normalized.uuid);
        }
        Map<String, Set<UUID>> immutableNames = new LinkedHashMap<>();
        byName.forEach((name, uuids) -> immutableNames.put(name, Set.copyOf(uuids)));
        return new Snapshot(Map.copyOf(byUuid), Map.copyOf(immutableNames));
    }

    private boolean write(Map<UUID, WhitelistEntry> entries) {
        Path parent = whitelistFile.toAbsolutePath().getParent();
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, "whitelist-", ".tmp");
            List<WhitelistEntry> ordered = new ArrayList<>(entries.values());
            ordered.sort(Comparator.comparing((WhitelistEntry entry) -> entry.name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(entry -> entry.uuid));
            Files.writeString(temporary, gson.toJson(ordered), StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, whitelistFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, whitelistFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String normalizeName(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.trim();
        if (normalized.isEmpty() || normalized.length() > 64
                || normalized.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        return normalized;
    }

    private static String nameKey(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String lookupFailure(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return "Profile lookup failed: " + cause.getClass().getSimpleName();
    }

    private static final class PaperIdentityResolver implements IdentityResolver {
        private static final int MAX_CACHE_SIZE = 1024;
        private final Map<String, ResolvedIdentity> cache = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ResolvedIdentity> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        };

        @Override
        public CompletableFuture<ResolvedIdentity> resolve(String name) {
            Player online = Bukkit.getPlayerExact(name);
            if (online != null) {
                return CompletableFuture.completedFuture(new ResolvedIdentity(online.getUniqueId(), online.getName()));
            }
            if (!Bukkit.getOnlineMode()) {
                UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
                return CompletableFuture.completedFuture(new ResolvedIdentity(uuid, name));
            }

            OfflinePlayer known = Bukkit.getOfflinePlayerIfCached(name);
            if (known != null && known.hasPlayedBefore()) {
                String knownName = known.getName() == null ? name : known.getName();
                return CompletableFuture.completedFuture(new ResolvedIdentity(known.getUniqueId(), knownName));
            }

            ResolvedIdentity cached;
            synchronized (cache) {
                cached = cache.get(nameKey(name));
            }
            if (cached != null) {
                return CompletableFuture.completedFuture(cached);
            }

            PlayerProfile unresolved = Bukkit.createPlayerProfile(name);
            return unresolved.update().thenApply(profile -> {
                if (profile.getUniqueId() == null) {
                    throw new IllegalStateException("Profile UUID is unavailable");
                }
                String resolvedName = profile.getName() == null ? name : profile.getName();
                ResolvedIdentity identity = new ResolvedIdentity(profile.getUniqueId(), resolvedName);
                synchronized (cache) {
                    cache.put(nameKey(name), identity);
                }
                return identity;
            });
        }
    }
}

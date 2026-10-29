// SPDX-FileCopyrightText: 2026 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.catalog;

import com.example.modseeker.ModSeekerPlugin;
import com.example.modseeker.policy.BlacklistManager;
import com.example.modseeker.policy.ModAllowlistManager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BucketService {
    private static final int MAX_JARS = 256;
    private static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;

    private final ModSeekerPlugin plugin;
    private final File directory;
    private final AtomicBoolean running = new AtomicBoolean();

    public BucketService(ModSeekerPlugin plugin) {
        this.plugin = plugin;
        this.directory = new File(plugin.getDataFolder(), "bucket");
    }

    public boolean ensureDirectory() {
        return directory.isDirectory() || directory.mkdirs();
    }

    public File getDirectory() {
        return directory;
    }

    public void execute(CommandSender sender, Target target, Operation operation) {
        if (!ensureDirectory()) {
            send(sender, ChatColor.YELLOW + "Could not create bucket: " + directory.getAbsolutePath());
            return;
        }
        if (!running.compareAndSet(false, true)) {
            send(sender, ChatColor.YELLOW + "A bucket operation is already running.");
            return;
        }
        send(sender, ChatColor.YELLOW + "Scanning bucket...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> inspect(sender, target, operation));
    }

    private void inspect(CommandSender sender, Target target, Operation operation) {
        List<JarInspector.Result> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            List<Path> jars = scanBucket(directory.toPath());
            if (jars.isEmpty()) {
                complete(sender, "Bucket is empty: " + directory.getAbsolutePath());
                return;
            }
            JarInspector inspector = new JarInspector(directory);
            for (Path jar : jars) {
                JarInspector.Result result = inspector.inspect(jar.toFile());
                if (target == Target.BLACKLIST && !result.ok()) {
                    errors.add(result.filename() + ": " + result.error());
                } else if (target == Target.ALLOWLIST && !result.hasHash()) {
                    errors.add(result.filename() + ": " + result.error());
                } else {
                    results.add(result);
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> apply(sender, target, operation, jars.size(), results, errors));
        } catch (Exception e) {
            complete(sender, "Bucket scan failed: " + e.getMessage());
        }
    }

    static List<Path> scanBucket(Path directory) throws Exception {
        Path root = directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        List<Path> jars;
        try (var paths = Files.list(root)) {
            jars = paths
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .limit(MAX_JARS + 1L)
                    .toList();
        }
        if (jars.size() > MAX_JARS) {
            throw new IllegalStateException("Bucket contains more than " + MAX_JARS + " jars");
        }
        long total = 0;
        for (Path jar : jars) {
            Path real = jar.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.startsWith(root)) {
                throw new IllegalStateException("Bucket contains an invalid path");
            }
            total = Math.addExact(total, Files.size(real));
            if (total > MAX_TOTAL_BYTES) {
                throw new IllegalStateException("Bucket exceeds the 1 GiB scan limit");
            }
        }
        return jars;
    }

    private void apply(CommandSender sender, Target target, Operation operation, int scanned,
            List<JarInspector.Result> results, List<String> errors) {
        try {
            errors.forEach(error -> warn(sender, error));
            if (target == Target.BLACKLIST) {
                List<String> ids = results.stream().map(JarInspector.Result::modId).toList();
                BlacklistManager.BatchResult result = operation == Operation.ADD
                        ? plugin.getBlacklistManager().addAll(ids)
                        : plugin.getBlacklistManager().removeAll(ids);
                report(sender, scanned, errors.size(), result.success(), result.changed(), result.skipped(), 0,
                        result.error(), operation);
            } else {
                if (operation == Operation.ADD) {
                    List<ModAllowlistManager.AllowEntry> entries = results.stream().map(this::entry).toList();
                    ModAllowlistManager.BatchResult result = plugin.getAllowlistManager().addEntriesResult(entries);
                    report(sender, scanned, errors.size(), result.success(), result.changed(), result.skipped(),
                            result.protectedCount(), result.error(), operation);
                } else {
                    ModAllowlistManager.BatchResult result = plugin.getAllowlistManager()
                            .removeHashes(results.stream().map(JarInspector.Result::sha512).toList());
                    report(sender, scanned, errors.size(), result.success(), result.changed(), result.skipped(),
                            result.protectedCount(), result.error(), operation);
                }
            }
        } finally {
            running.set(false);
        }
    }

    private ModAllowlistManager.AllowEntry entry(JarInspector.Result result) {
        String modId = result.ok() ? result.modId() : "unknown";
        String version = result.ok() ? result.version() : "unknown";
        ModAllowlistManager.AllowEntry entry = new ModAllowlistManager.AllowEntry();
        entry.hash = result.sha512();
        entry.algo = "sha512";
        entry.modid = modId;
        entry.name = modId;
        entry.version = version;
        entry.mcVersion = "unknown";
        entry.loader = "fabric";
        entry.filename = result.filename();
        entry.label = modId + " v" + version + " (bucket)";
        entry.essential = false;
        return entry;
    }

    private void report(CommandSender sender, int scanned, int errors, boolean success, int changed, int skipped,
            int protectedCount, String failure, Operation operation) {
        if (!success) {
            warn(sender, failure);
            return;
        }
        String action = operation == Operation.ADD ? "added" : "removed";
        String summary = "Bucket: scanned " + scanned + " -> " + action + " " + changed + ", skipped " + skipped
                + ", " + errors + " errors" + (protectedCount == 0 ? "" : ", kept " + protectedCount + " essential");
        send(sender, ChatColor.GREEN + summary);
    }

    private void warn(CommandSender sender, String message) {
        plugin.logInfo(ChatColor.YELLOW + "Bucket: " + message);
        if (!(sender instanceof ConsoleCommandSender)) {
            send(sender, ChatColor.YELLOW + message);
        }
    }

    private void complete(CommandSender sender, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            running.set(false);
            send(sender, ChatColor.YELLOW + message);
        });
    }

    private void send(CommandSender sender, String message) {
        if (!(sender instanceof Player player) || player.isOnline()) {
            sender.sendMessage(message);
        }
    }

    public enum Target {
        BLACKLIST,
        ALLOWLIST
    }

    public enum Operation {
        ADD,
        REMOVE
    }
}

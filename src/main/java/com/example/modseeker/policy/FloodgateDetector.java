// SPDX-FileCopyrightText: 2026 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.policy;

import com.example.modseeker.ModSeekerPlugin;

import org.bukkit.ChatColor;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class FloodgateDetector {
    @FunctionalInterface
    interface Lookup {
        boolean isFloodgatePlayer(UUID playerId);
    }

    private final Lookup lookup;
    private final Consumer<String> warningSink;
    private final String unavailableReason;
    private final AtomicBoolean warned = new AtomicBoolean();

    public static FloodgateDetector create(ModSeekerPlugin plugin) {
        Consumer<String> warningSink = message -> plugin.logInfo(ChatColor.YELLOW + message);
        Plugin floodgate = plugin.getServer().getPluginManager().getPlugin("floodgate");
        if (floodgate == null || !floodgate.isEnabled()) {
            return new FloodgateDetector(null, warningSink, "Floodgate is not installed or enabled");
        }
        try {
            return new FloodgateDetector(FloodgateApiAccess.createLookup(), warningSink, null);
        } catch (RuntimeException | LinkageError e) {
            return new FloodgateDetector(null, warningSink,
                    "Floodgate API initialization failed: " + e.getClass().getSimpleName());
        }
    }

    FloodgateDetector(Lookup lookup, Consumer<String> warningSink, String unavailableReason) {
        this.lookup = lookup;
        this.warningSink = Objects.requireNonNull(warningSink);
        this.unavailableReason = unavailableReason;
    }

    public boolean isAvailable() {
        return lookup != null;
    }

    public void warnIfUnavailable() {
        if (lookup == null) {
            warnOnce(unavailableReason);
        }
    }

    public boolean isFloodgatePlayer(UUID playerId) {
        if (lookup == null) {
            warnOnce(unavailableReason);
            return false;
        }
        try {
            return lookup.isFloodgatePlayer(playerId);
        } catch (RuntimeException | LinkageError e) {
            warnOnce("Floodgate API lookup failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private void warnOnce(String reason) {
        if (warned.compareAndSet(false, true)) {
            warningSink.accept(reason + "; Bedrock bypass is unavailable and players will be verified as Java clients");
        }
    }
}

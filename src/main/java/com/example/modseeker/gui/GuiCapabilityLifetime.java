// SPDX-FileCopyrightText: 2026 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.gui;

import java.util.HashMap;
import java.util.Map;

final class GuiCapabilityLifetime {
    static final long CLOSE_GRACE_MS = 3_000;
    static final long VIEWER_TIMEOUT_MS = 90_000;

    private final long deadlineMillis;
    private final Map<String, Long> viewers = new HashMap<>();
    private int activeOperations;
    private String selectedHost;
    private long emptySince = -1;
    private boolean opened;
    private boolean expired;

    GuiCapabilityLifetime(long issuedAtMillis, long lifetimeMillis) {
        if (lifetimeMillis <= 0 || issuedAtMillis > Long.MAX_VALUE - lifetimeMillis) {
            throw new IllegalArgumentException("Invalid capability lifetime");
        }
        deadlineMillis = issuedAtMillis + lifetimeMillis;
    }

    synchronized boolean accept(long nowMillis) {
        if (isExpired(nowMillis)) {
            return false;
        }
        activeOperations++;
        return true;
    }

    synchronized boolean complete() {
        if (activeOperations == 0) {
            throw new IllegalStateException("No active GUI operation");
        }
        activeOperations--;
        return expired && activeOperations == 0;
    }

    synchronized boolean expire(long nowMillis) {
        return isExpired(nowMillis) && activeOperations == 0;
    }

    synchronized boolean isExpired(long nowMillis) {
        if (nowMillis >= deadlineMillis) {
            expired = true;
        }
        if (!expired && opened) {
            viewers.entrySet().removeIf(entry -> nowMillis - entry.getValue() >= VIEWER_TIMEOUT_MS);
            if (viewers.isEmpty()) {
                if (emptySince < 0) {
                    emptySince = nowMillis;
                } else if (nowMillis - emptySince >= CLOSE_GRACE_MS) {
                    expired = true;
                }
            }
        }
        return expired;
    }

    synchronized boolean permitsHost(String host, long nowMillis) {
        return !isExpired(nowMillis) && (selectedHost == null || selectedHost.equals(host));
    }

    synchronized boolean selectHost(String host, long nowMillis) {
        if (!permitsHost(host, nowMillis)) {
            return false;
        }
        if (selectedHost == null) {
            selectedHost = host;
        }
        return true;
    }

    synchronized boolean open(String host, String viewerId, long nowMillis) {
        if (!selectHost(host, nowMillis)) {
            return false;
        }
        viewers.put(viewerId, nowMillis);
        opened = true;
        emptySince = -1;
        return true;
    }

    synchronized boolean heartbeat(String host, String viewerId, long nowMillis) {
        if (!permitsHost(host, nowMillis) || !viewers.containsKey(viewerId)) {
            return false;
        }
        viewers.put(viewerId, nowMillis);
        return true;
    }

    synchronized void leave(String host, String viewerId, long nowMillis) {
        if (!host.equals(selectedHost) || viewers.remove(viewerId) == null) {
            return;
        }
        if (viewers.isEmpty()) {
            emptySince = nowMillis;
        }
    }

    synchronized boolean hasActiveOperations() {
        return activeOperations > 0;
    }

    long deadlineMillis() {
        return deadlineMillis;
    }
}

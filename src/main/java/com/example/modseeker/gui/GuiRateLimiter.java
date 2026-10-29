// SPDX-FileCopyrightText: 2026 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.gui;

import java.util.concurrent.ConcurrentHashMap;

final class GuiRateLimiter {
    private final int requestLimit;
    private final int clientLimit;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    GuiRateLimiter(int requestLimit, int clientLimit) {
        if (requestLimit < 1 || clientLimit < 1) {
            throw new IllegalArgumentException("Rate limits must be positive");
        }
        this.requestLimit = requestLimit;
        this.clientLimit = clientLimit;
    }

    boolean allow(String address, long minute) {
        Window window = windows.get(address);
        if (window == null) {
            synchronized (windows) {
                window = windows.get(address);
                if (window == null) {
                    if (windows.size() >= clientLimit) {
                        windows.entrySet().removeIf(entry -> entry.getValue().minute < minute - 1);
                    }
                    if (windows.size() >= clientLimit) {
                        return false;
                    }
                    window = new Window(minute);
                    windows.put(address, window);
                }
            }
        }
        synchronized (window) {
            if (window.minute != minute) {
                window.minute = minute;
                window.count = 0;
            }
            return ++window.count <= requestLimit;
        }
    }

    void clear() {
        windows.clear();
    }

    int size() {
        return windows.size();
    }

    private static final class Window {
        private volatile long minute;
        private int count;

        private Window(long minute) {
            this.minute = minute;
        }
    }
}

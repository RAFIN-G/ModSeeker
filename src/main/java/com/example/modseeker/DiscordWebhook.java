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

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles Discord webhook integration for ModSeeker plugin.
 * Sends async HTTP POST requests to Discord webhooks.
 */
public class DiscordWebhook {

    private final ConfigManager configManager;
    private final ExecutorService executor;

    public DiscordWebhook(ConfigManager configManager) {
        this.configManager = configManager;
        this.executor = Executors.newSingleThreadExecutor();
    }

    /**
     * Shutdown the executor service
     */
    public void shutdown() {
        executor.shutdown();
    }

    /**
     * Send player join notification
     */
    public void sendPlayerJoin(String playerName, String uuid, String ip) {
        if (!configManager.isDiscordEnabled() || !configManager.isDiscordPlayerJoin()) {
            return;
        }

        String json = buildEmbed(
                "🌍 Player Join",
                "**" + playerName + "** joined the server",
                0x00FF00, // Green
                new String[][] {
                        { "UUID", uuid, "true" },
                        { "IP", ip, "true" }
                });
        sendWebhook(json);
    }

    /**
     * Send mod list received notification
     */
    public void sendModListReceived(String playerName, List<String> mods, boolean verified) {
        if (!configManager.isDiscordEnabled() || !configManager.isDiscordModListReceived()) {
            return;
        }

        String modString = String.join(", ", mods);
        if (modString.length() > 1000) {
            modString = modString.substring(0, 997) + "...";
        }

        String status = verified ? "✅ Verified" : "❓ Pending";
        int color = verified ? 0x00FF00 : 0xFFFF00; // Green or Yellow

        String json = buildEmbed(
                "📋 Mod List Received",
                "Player: **" + playerName + "**",
                color,
                new String[][] {
                        { "Mod Count", String.valueOf(mods.size()), "true" },
                        { "Status", status, "true" },
                        { "Mods", modString, "false" }
                });
        sendWebhook(json);
    }

    /**
     * Send blacklist violation alert
     */
    public void sendBlacklistViolation(String playerName, List<String> blacklistedMods) {
        if (!configManager.isDiscordEnabled() || !configManager.isDiscordBlacklistViolation()) {
            return;
        }

        String modString = String.join(", ", blacklistedMods);

        String json = buildEmbed(
                "🚫 BLACKLIST VIOLATION",
                "Player: **" + playerName + "**",
                0xFF0000, // Red
                new String[][] {
                        { "Blacklisted Mods", modString, "false" },
                        { "Action", "🦵 Kicked", "true" }
                });
        sendWebhook(json);
    }

    /**
     * Send verification failed alert
     */
    public void sendVerificationFailed(String playerName, String reason) {
        if (!configManager.isDiscordEnabled() || !configManager.isDiscordVerificationFailed()) {
            return;
        }

        String json = buildEmbed(
                "❌ Verification Failed",
                "Player: **" + playerName + "**",
                0xFF6600, // Orange
                new String[][] {
                        { "Reason", reason, "false" },
                        { "Action", "🦵 Kicked", "true" }
                });
        sendWebhook(json);
    }

    /**
     * Build a Discord embed JSON
     */
    private String buildEmbed(String title, String description, int color, String[][] fields) {
        StringBuilder fieldsJson = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0)
                fieldsJson.append(",");
            fieldsJson.append("{")
                    .append("\"name\":\"").append(escapeJson(fields[i][0])).append("\",")
                    .append("\"value\":\"").append(escapeJson(fields[i][1])).append("\",")
                    .append("\"inline\":").append(fields[i][2])
                    .append("}");
        }

        return "{\"embeds\":[{"
                + "\"title\":\"" + escapeJson(title) + "\","
                + "\"description\":\"" + escapeJson(description) + "\","
                + "\"color\":" + color + ","
                + "\"fields\":[" + fieldsJson.toString() + "],"
                + "\"footer\":{\"text\":\"ModSeeker\"}"
                + "}]}";
    }

    /**
     * Escape special JSON characters
     */
    private String escapeJson(String text) {
        if (text == null)
            return "";
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Send webhook asynchronously
     */
    private void sendWebhook(String jsonPayload) {
        String webhookUrl = configManager.getDiscordWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty() || webhookUrl.contains("YOUR_WEBHOOK")) {
            return;
        }

        executor.submit(() -> {
            try {
                URL url = new URL(webhookUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = conn.getResponseCode();
                if (responseCode != 200 && responseCode != 204) {
                    // Log error silently - don't spam console
                }

                conn.disconnect();
            } catch (Exception e) {
                // Silently fail - don't disrupt gameplay
            }
        });
    }
}

// SPDX-FileCopyrightText: 2025 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.webhook;

import com.example.modseeker.config.ConfigManager;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;





public class DiscordWebhook {

    private static final Set<String> WEBHOOK_HOSTS = Set.of(
            "discord.com", "canary.discord.com", "ptb.discord.com", "discordapp.com");
    private static final Pattern WEBHOOK_PATH = Pattern.compile("/api(/v\\d{1,2})?/webhooks/[\\w-]+/[\\w-]+");

    private final ConfigManager configManager;
    private final ThreadPoolExecutor executor;

    public DiscordWebhook(ConfigManager configManager) {
        this.configManager = configManager;
        this.executor = newExecutor();
    }

    static ThreadPoolExecutor newExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), task -> {
            Thread thread = new Thread(task, "ModSeeker-Discord");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.DiscardPolicy());
    }




    public void shutdown() {
        executor.shutdownNow();
    }

    public boolean isActive() {
        return configManager.isDiscordEnabled()
                && isValidWebhookUrl(configManager.getDiscordWebhookUrl())
                && !executor.isShutdown();
    }

    static boolean isValidWebhookUrl(String value) {
        if (value == null || value.isBlank() || value.contains("YOUR_WEBHOOK")) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getUserInfo() == null
                    && host != null && WEBHOOK_HOSTS.contains(host.toLowerCase(Locale.ROOT))
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && uri.getPath() != null && WEBHOOK_PATH.matcher(uri.getPath()).matches();
        } catch (URISyntaxException e) {
            return false;
        }
    }




    public void sendPlayerJoin(String playerName, String uuid, String ip) {
        if (!configManager.isDiscordEnabled() || !configManager.isDiscordPlayerJoin()) {
            return;
        }

        String json = buildEmbed(
                "🌍 Player Join",
                "**" + playerName + "** joined the server",
                0x00FF00,
                new String[][] {
                        { "UUID", uuid, "true" },
                        { "IP", ip, "true" }
                });
        sendWebhook(json);
    }




    public void sendModListReceived(String playerName, List<String> mods, boolean verified) {
        if (!configManager.isDiscordEnabled() || !configManager.isDiscordModListReceived()) {
            return;
        }

        String modString = String.join(", ", mods);
        if (modString.length() > 1000) {
            modString = modString.substring(0, 997) + "...";
        }

        String status = verified ? "✅ Verified" : "❓ Pending";
        int color = verified ? 0x00FF00 : 0xFFFF00;

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




    public void sendBlacklistViolation(String playerName, List<String> blacklistedMods) {
        if (!configManager.isDiscordEnabled() || !configManager.isDiscordBlacklistViolation()) {
            return;
        }

        String modString = String.join(", ", blacklistedMods);
        if (modString.length() > 1000) {
            modString = modString.substring(0, 997) + "...";
        }

        String json = buildEmbed(
                "🚫 BLACKLIST VIOLATION",
                "Player: **" + playerName + "**",
                0xFF0000,
                new String[][] {
                        { "Blacklisted Mods", modString, "false" },
                        { "Action", "🦵 Kicked", "true" }
                });
        sendWebhook(json);
    }




    public void sendVerificationFailed(String playerName, String reason) {
        if (!configManager.isDiscordEnabled() || !configManager.isDiscordVerificationFailed()) {
            return;
        }

        String json = buildEmbed(
                "❌ Verification Failed",
                "Player: **" + playerName + "**",
                0xFF6600,
                new String[][] {
                        { "Reason", reason, "false" },
                        { "Action", "🦵 Kicked", "true" }
                });
        sendWebhook(json);
    }




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




    private String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            switch (value) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (value < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) value));
                    } else {
                        escaped.append(value);
                    }
                }
            }
        }
        return escaped.toString();
    }




    private void sendWebhook(String jsonPayload) {
        String webhookUrl = configManager.getDiscordWebhookUrl();
        if (!isValidWebhookUrl(webhookUrl)) {
            return;
        }

        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(webhookUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                conn.getResponseCode();
            } catch (IOException ignored) {
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }
}

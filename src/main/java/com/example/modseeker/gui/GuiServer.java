// SPDX-FileCopyrightText: 2025 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.gui;

import com.example.modseeker.ModSeekerPlugin;
import com.example.modseeker.catalog.HashFetcher;
import com.example.modseeker.catalog.ModrinthJarResolver;
import com.example.modseeker.policy.BlacklistManager;
import com.example.modseeker.policy.ModAllowlistManager;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class GuiServer {
    static final long CAPABILITY_LIFETIME_MS = 15 * 60 * 1000L;
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final int MAX_QUERY_BYTES = 2048;
    private static final int RATE_LIMIT = 120;
    private static final int RATE_CLIENT_LIMIT = 1024;
    private static final Pattern SLUG = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_-]{0,127}");
    private static final Pattern VERSION_ID = Pattern.compile("[A-Za-z0-9]{1,64}");
    private static final Pattern VIEWER_ID = Pattern.compile("[A-Fa-f0-9]{32}");
    private static final Pattern SHA512 = Pattern.compile("[A-Fa-f0-9]{128}");
    private static final Pattern MC_VERSION = Pattern.compile("[A-Za-z0-9._+\\-]{1,32}");
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data: blob:; connect-src 'self'; font-src 'self'; base-uri 'none'; "
            + "frame-ancestors 'none'; form-action 'none'";

    private final ModSeekerPlugin plugin;
    private final Gson gson = new Gson();
    private final GuiRateLimiter rateLimiter = new GuiRateLimiter(RATE_LIMIT, RATE_CLIENT_LIMIT);
    private final AtomicBoolean mutationRunning = new AtomicBoolean();
    private final byte[] logoBytes;
    private final byte[] fontBytes;
    private final byte[] styleBytes;
    private final byte[] scriptBytes;
    private final byte[] allowlistPage;
    private final byte[] blacklistPage;

    private HttpServer server;
    private ThreadPoolExecutor executor;
    private volatile Mode mode;
    private volatile String token;
    private volatile GuiCapabilityLifetime lifetime;
    private volatile GuiEndpoint access;
    private int port;
    private int idleTaskId = -1;

    public GuiServer(ModSeekerPlugin plugin) {
        this.plugin = plugin;
        logoBytes = resource("/modseeker.png");
        fontBytes = resource("/gui/VT323-Regular.ttf");
        styleBytes = resource("/gui/shared.css");
        scriptBytes = resource("/gui/app.js");
        allowlistPage = resource("/gui/allowlist.html");
        blacklistPage = resource("/gui/blacklist.html");
    }

    public synchronized boolean start() {
        Mode requested = plugin.getConfigManager().isAdvancedModVerificationEnabled()
                ? Mode.ALLOWLIST : Mode.BLACKLIST;
        long now = System.currentTimeMillis();
        if (server != null && mode == requested && lifetime != null) {
            if (!lifetime.isExpired(now)) {
                return true;
            }
            if (lifetime.hasActiveOperations()) {
                return false;
            }
        }
        if (server != null) {
            stop();
        }
        try {
            Files.deleteIfExists(plugin.getDataFolder().toPath().resolve("gui-link.txt"));
            mode = requested;
            token = randomToken();
            lifetime = new GuiCapabilityLifetime(now, CAPABILITY_LIFETIME_MS);
            int configuredPort = plugin.getConfigManager().getGuiPort();
            GuiEndpoint.Configuration endpoint = GuiEndpoint.configure(plugin.getConfigManager().getGuiExternalUrl());
            if (endpoint.requiresFixedPort() && configuredPort == 0) {
                throw new IllegalArgumentException("Remote GUI access requires a fixed gui.port");
            }
            server = HttpServer.create(new InetSocketAddress(endpoint.bindAddress(), configuredPort), 0);
            port = server.getAddress().getPort();
            access = endpoint.activate(port);
            executor = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.AbortPolicy());
            server.setExecutor(executor);
            register("/", this::handleRoot);
            register("/assets/logo", ex -> handleAsset(ex, "/assets/logo", "image/png", logoBytes));
            register("/assets/font", ex -> handleAsset(ex, "/assets/font", "font/ttf", fontBytes));
            register("/assets/style.css", ex -> handleAsset(ex, "/assets/style.css", "text/css; charset=utf-8", styleBytes));
            register("/assets/app.js", ex -> handleAsset(ex, "/assets/app.js", "text/javascript; charset=utf-8", scriptBytes));
            register("/api/search", this::handleSearch);
            register("/api/versions", this::handleVersions);
            register("/api/icon", this::handleIcon);
            register("/api/session/open", this::handleSessionOpen);
            register("/api/session/heartbeat", this::handleSessionHeartbeat);
            register("/api/session/close", this::handleSessionClose);
            if (mode == Mode.ALLOWLIST) {
                register("/api/allowlist/current", this::handleAllowlistCurrent);
                register("/api/allowlist/add", this::handleAllowlistAdd);
                register("/api/allowlist/remove", this::handleAllowlistRemove);
            } else {
                register("/api/blacklist/current", this::handleBlacklistCurrent);
                register("/api/blacklist/add", this::handleBlacklistAdd);
                register("/api/blacklist/remove", this::handleBlacklistRemove);
            }
            server.start();
            startExpiryWatch(lifetime);
            return true;
        } catch (Exception e) {
            plugin.logInfo(ChatColor.YELLOW + "GUI server failed to start: " + e.getMessage());
            stop();
            return false;
        }
    }

    public synchronized void stop() {
        if (idleTaskId != -1) {
            Bukkit.getScheduler().cancelTask(idleTaskId);
            idleTaskId = -1;
        }
        HttpServer active = server;
        ThreadPoolExecutor activeExecutor = executor;
        server = null;
        executor = null;
        mode = null;
        token = null;
        lifetime = null;
        access = null;
        rateLimiter.clear();
        if (active != null) {
            active.stop(0);
        }
        if (activeExecutor != null) {
            activeExecutor.shutdown();
        }
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    public synchronized String getAccessUrl() {
        GuiCapabilityLifetime activeLifetime = lifetime;
        GuiEndpoint activeAccess = access;
        if (server == null || token == null || activeAccess == null || activeLifetime == null
                || activeLifetime.isExpired(System.currentTimeMillis())) {
            return "";
        }
        return activeAccess.link(token);
    }

    public synchronized boolean isInsecureRemote() {
        return access != null && access.insecureRemote();
    }

    private void register(String path, HttpHandler handler) {
        server.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } catch (JsonParseException | IllegalArgumentException e) {
                sendApi(exchange, 400, false, "Invalid request.", null);
            } catch (Exception e) {
                sendApi(exchange, 500, false, "Request failed.", null);
            }
        });
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!prepare(exchange, "GET", "/", false)) {
            return;
        }
        byte[] page = mode == Mode.ALLOWLIST ? allowlistPage : blacklistPage;
        send(exchange, 200, "text/html; charset=utf-8", page);
    }

    private void handleAsset(HttpExchange exchange, String path, String contentType, byte[] data) throws IOException {
        if (!prepare(exchange, "GET", path, false)) {
            return;
        }
        if (data.length == 0) {
            send(exchange, 404, "text/plain; charset=utf-8", bytes("Not found"));
            return;
        }
        send(exchange, 200, contentType, data);
    }

    private void handleSearch(HttpExchange exchange) throws IOException {
        if (!prepare(exchange, "GET", "/api/search", true)) {
            return;
        }
        Map<String, String> query = query(exchange);
        HashFetcher.CatalogResult<List<HashFetcher.SearchHit>> result = plugin.getHashFetcher()
                .search(query.get("q"), query.get("mc"));
        sendApi(exchange, result.ok() ? 200 : 502, result.ok(), result.error(), result.data());
    }

    private void handleVersions(HttpExchange exchange) throws IOException {
        if (!prepare(exchange, "GET", "/api/versions", true)) {
            return;
        }
        Map<String, String> query = query(exchange);
        HashFetcher.CatalogResult<List<HashFetcher.VersionView>> result = plugin.getHashFetcher()
                .versions(query.get("slug"), query.get("mc"));
        sendApi(exchange, result.ok() ? 200 : 502, result.ok(), result.error(), result.data());
    }

    private void handleIcon(HttpExchange exchange) throws IOException {
        if (!prepare(exchange, "GET", "/api/icon", true)) {
            return;
        }
        HashFetcher.IconResult icon = plugin.getHashFetcher().fetchIcon(query(exchange).get("slug"));
        if (!icon.ok()) {
            send(exchange, 404, "text/plain; charset=utf-8", bytes("Not found"));
            return;
        }
        send(exchange, 200, icon.contentType(), icon.bytes());
    }

    private void handleSessionOpen(HttpExchange exchange) throws IOException {
        if (!prepare(exchange, "POST", "/api/session/open", true)) {
            return;
        }
        ViewerRequest request = body(exchange, ViewerRequest.class);
        if (request == null || request.id == null || !VIEWER_ID.matcher(request.id).matches()) {
            sendApi(exchange, 400, false, "Invalid viewer.", null);
            return;
        }
        GuiCapabilityLifetime activeLifetime = lifetime;
        if (activeLifetime == null || !activeLifetime.open(requestHost(exchange), request.id,
                System.currentTimeMillis())) {
            sendApi(exchange, 410, false, "This GUI link has expired.", null);
            stopIfExpired(activeLifetime);
            return;
        }
        sendApi(exchange, 200, true, "", null);
    }

    private void handleSessionHeartbeat(HttpExchange exchange) throws IOException {
        if (!prepare(exchange, "POST", "/api/session/heartbeat", true)) {
            return;
        }
        ViewerRequest request = body(exchange, ViewerRequest.class);
        GuiCapabilityLifetime activeLifetime = lifetime;
        if (request == null || request.id == null || activeLifetime == null
                || !activeLifetime.heartbeat(requestHost(exchange), request.id, System.currentTimeMillis())) {
            sendApi(exchange, 410, false, "This GUI link has expired.", null);
            stopIfExpired(activeLifetime);
            return;
        }
        sendApi(exchange, 200, true, "", null);
    }

    private void handleSessionClose(HttpExchange exchange) throws IOException {
        if (!prepare(exchange, "POST", "/api/session/close", true)) {
            return;
        }
        ViewerRequest request = body(exchange, ViewerRequest.class);
        if (request == null || request.id == null || !VIEWER_ID.matcher(request.id).matches()) {
            sendApi(exchange, 400, false, "Invalid viewer.", null);
            return;
        }
        GuiCapabilityLifetime activeLifetime = lifetime;
        if (activeLifetime != null) {
            activeLifetime.leave(requestHost(exchange), request.id, System.currentTimeMillis());
        }
        sendApi(exchange, 200, true, "", null);
    }

    private void handleAllowlistCurrent(HttpExchange exchange) throws IOException {
        if (!prepare(exchange, "GET", "/api/allowlist/current", true)) {
            return;
        }
        sendApi(exchange, 200, true, "", plugin.getAllowlistManager().getEntries());
    }

    private void handleAllowlistAdd(HttpExchange exchange) throws IOException {
        if (!prepare(exchange, "POST", "/api/allowlist/add", true)) {
            return;
        }
        OperationLease operation = beginMutation(exchange);
        if (operation == null) {
            return;
        }
        try {
            ProjectRequest request = body(exchange, ProjectRequest.class);
            if (!validProject(request)) {
                sendApi(exchange, 400, false, "Invalid project or Minecraft version.", null);
                return;
            }
            HashFetcher.FetchResult fetched = plugin.getHashFetcher()
                    .fetchForCommand("https://modrinth.com/mod/" + request.slug, request.mcVersion,
                            request.versionIds);
            if (!fetched.ok) {
                audit(exchange, "allowlist add", request.slug, false);
                sendApi(exchange, 502, false, fetched.errorMessage, null);
                return;
            }
            ModAllowlistManager.BatchResult mutation = plugin.getAllowlistManager().addEntriesResult(fetched.entries);
            audit(exchange, "allowlist add", request.slug + " changed=" + mutation.changed()
                    + " skipped=" + mutation.skipped(), mutation.success());
            sendApi(exchange, mutation.success() ? 200 : 500, mutation.success(),
                    mutation.success() ? "Allowlist updated." : mutation.error(), mutation);
        } finally {
            operation.close();
        }
    }

    private void handleAllowlistRemove(HttpExchange exchange) throws IOException {
        if (!prepare(exchange, "POST", "/api/allowlist/remove", true)) {
            return;
        }
        OperationLease operation = beginMutation(exchange);
        if (operation == null) {
            return;
        }
        try {
            ModIdRequest request = body(exchange, ModIdRequest.class);
            if (!validAllowlistRemoval(request)) {
                sendApi(exchange, 400, false, "Invalid mod or version selection.", null);
                return;
            }
            ModAllowlistManager.BatchResult mutation = request.hashes == null
                    ? plugin.getAllowlistManager().removeByModIdResult(request.modId)
                    : plugin.getAllowlistManager().removeHashesForModId(request.modId, request.hashes);
            audit(exchange, "allowlist remove", request.modId + " changed=" + mutation.changed()
                    + " protected=" + mutation.protectedCount() + " selected="
                    + (request.hashes == null ? "all" : request.hashes.size()), mutation.success());
            sendApi(exchange, mutation.success() ? 200 : 500, mutation.success(),
                    mutation.success() ? "Allowlist updated." : mutation.error(), mutation);
        } finally {
            operation.close();
        }
    }

    private void handleBlacklistCurrent(HttpExchange exchange) throws IOException {
        if (!prepare(exchange, "GET", "/api/blacklist/current", true)) {
            return;
        }
        List<BlacklistItem> entries = plugin.getBlacklistManager().getBlacklistedMods().stream()
                .sorted()
                .map(modId -> new BlacklistItem(modId, plugin.getGuiModIdCache().slugForModId(modId)))
                .toList();
        sendApi(exchange, 200, true, "", entries);
    }

    private void handleBlacklistAdd(HttpExchange exchange) throws IOException {
        if (!prepare(exchange, "POST", "/api/blacklist/add", true)) {
            return;
        }
        OperationLease operation = beginMutation(exchange);
        if (operation == null) {
            return;
        }
        try {
            ProjectRequest request = body(exchange, ProjectRequest.class);
            if (!validProject(request)) {
                sendApi(exchange, 400, false, "Invalid project or Minecraft version.", null);
                return;
            }
            ModrinthJarResolver.ResolveResult resolved = plugin.getModrinthJarResolver()
                    .resolve(request.slug, request.mcVersion);
            if (!resolved.ok()) {
                audit(exchange, "blacklist add", request.slug, false);
                sendApi(exchange, 502, false, resolved.error(), null);
                return;
            }
            BlacklistManager.BatchResult mutation = plugin.getBlacklistManager().addAll(List.of(resolved.modId()));
            audit(exchange, "blacklist add", resolved.modId() + " changed=" + mutation.changed()
                    + " skipped=" + mutation.skipped() + " cache=" + resolved.cacheHit(), mutation.success());
            sendApi(exchange, mutation.success() ? 200 : 500, mutation.success(),
                    mutation.success() ? "Blacklist updated." : mutation.error(),
                    new BlacklistMutation(resolved.modId(), mutation.changed(), mutation.skipped(), resolved.cacheHit()));
        } finally {
            operation.close();
        }
    }

    private void handleBlacklistRemove(HttpExchange exchange) throws IOException {
        if (!prepare(exchange, "POST", "/api/blacklist/remove", true)) {
            return;
        }
        OperationLease operation = beginMutation(exchange);
        if (operation == null) {
            return;
        }
        try {
            ModIdRequest request = body(exchange, ModIdRequest.class);
            if (!validModId(request == null ? null : request.modId)) {
                sendApi(exchange, 400, false, "Invalid mod id.", null);
                return;
            }
            BlacklistManager.BatchResult mutation = plugin.getBlacklistManager().removeAll(List.of(request.modId));
            audit(exchange, "blacklist remove", request.modId + " changed=" + mutation.changed()
                    + " skipped=" + mutation.skipped(), mutation.success());
            sendApi(exchange, mutation.success() ? 200 : 500, mutation.success(),
                    mutation.success() ? "Blacklist updated." : mutation.error(), mutation);
        } finally {
            operation.close();
        }
    }

    private OperationLease beginMutation(HttpExchange exchange) throws IOException {
        if (!mutationRunning.compareAndSet(false, true)) {
            sendApi(exchange, 409, false, "Another GUI update is already running.", null);
            return null;
        }
        GuiCapabilityLifetime activeLifetime = lifetime;
        if (activeLifetime == null || !activeLifetime.accept(System.currentTimeMillis())) {
            mutationRunning.set(false);
            sendApi(exchange, 410, false, "This GUI link has expired.", null);
            stopIfExpired(activeLifetime);
            return null;
        }
        return new OperationLease(activeLifetime);
    }

    private boolean prepare(HttpExchange exchange, String method, String path, boolean authenticated)
            throws IOException {
        if (!exchange.getRequestURI().getPath().equals(path)) {
            send(exchange, 404, "text/plain; charset=utf-8", bytes("Not found"));
            return false;
        }
        if (!exchange.getRequestMethod().equals(method)) {
            exchange.getResponseHeaders().set("Allow", method);
            send(exchange, 405, "text/plain; charset=utf-8", bytes("Method not allowed"));
            return false;
        }
        GuiCapabilityLifetime activeLifetime = lifetime;
        long now = System.currentTimeMillis();
        if (activeLifetime == null || activeLifetime.isExpired(now)) {
            if (path.startsWith("/api/")) {
                sendApi(exchange, 410, false, "This GUI link has expired.", null);
            } else {
                send(exchange, 410, "text/plain; charset=utf-8", bytes("This GUI link has expired."));
            }
            stopIfExpired(activeLifetime);
            return false;
        }
        String host = requestHost(exchange);
        GuiEndpoint activeAccess = access;
        if (activeAccess == null || !activeAccess.acceptsHost(host)) {
            sendApi(exchange, 403, false, "Forbidden.", null);
            return false;
        }
        if (!activeLifetime.permitsHost(host, now)) {
            sendApi(exchange, 410, false, "This GUI link has expired.", null);
            return false;
        }
        if (authenticated && (!sameOrigin(exchange) || !authorized(exchange))) {
            sendApi(exchange, 403, false, "Forbidden.", null);
            return false;
        }
        if (authenticated && !withinRate(exchange)) {
            sendApi(exchange, 429, false, "Too many requests. Try again shortly.", null);
            return false;
        }
        if (authenticated && !activeLifetime.selectHost(host, now)) {
            sendApi(exchange, 410, false, "This GUI link has expired.", null);
            return false;
        }
        return true;
    }

    private String requestHost(HttpExchange exchange) {
        List<String> hosts = exchange.getRequestHeaders().get("Host");
        return hosts == null || hosts.size() != 1 ? "" : hosts.getFirst().toLowerCase(Locale.ROOT);
    }

    private boolean authorized(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ") || token == null) {
            return false;
        }
        byte[] supplied = authorization.substring(7).getBytes(StandardCharsets.US_ASCII);
        byte[] expected = token.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(supplied, expected);
    }

    private boolean sameOrigin(HttpExchange exchange) {
        List<String> origins = exchange.getRequestHeaders().get("Origin");
        if (origins != null && origins.size() != 1) {
            return false;
        }
        String origin = origins == null ? null : origins.getFirst();
        GuiEndpoint activeAccess = access;
        return activeAccess != null && activeAccess.acceptsOrigin(origin);
    }

    private boolean withinRate(HttpExchange exchange) {
        String address = exchange.getRemoteAddress().getAddress().getHostAddress();
        long minute = System.currentTimeMillis() / 60000L;
        return rateLimiter.allow(address, minute);
    }

    private Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_BYTES) {
            throw new IllegalArgumentException("query too large");
        }
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("invalid query");
            }
            String key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("duplicate query field");
            }
        }
        return Map.copyOf(values);
    }

    private <T> T body(HttpExchange exchange, Class<T> type) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new IllegalArgumentException("invalid content type");
        }
        byte[] data = readBounded(exchange.getRequestBody(), MAX_BODY_BYTES);
        if (data.length == 0) {
            throw new IllegalArgumentException("empty body");
        }
        return gson.fromJson(new String(data, StandardCharsets.UTF_8), type);
    }

    private byte[] readBounded(InputStream input, int limit) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new IllegalArgumentException("body too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private void sendApi(HttpExchange exchange, int status, boolean ok, String message, Object data)
            throws IOException {
        send(exchange, status, "application/json; charset=utf-8",
                gson.toJson(new ApiEnvelope(ok, message == null ? "" : message, data)).getBytes(StandardCharsets.UTF_8));
    }

    private void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Content-Security-Policy", CSP);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        } finally {
            exchange.close();
        }
    }

    private void audit(HttpExchange exchange, String action, String target, boolean success) {
        String remote = exchange.getRemoteAddress().getAddress().getHostAddress();
        plugin.logInfo(ChatColor.YELLOW + "GUI " + action + " from " + remote + " for " + target
                + ": " + (success ? "success" : "failed"));
    }

    private void startExpiryWatch(GuiCapabilityLifetime activeLifetime) {
        idleTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (activeLifetime.expire(System.currentTimeMillis())) {
                stopIfExpired(activeLifetime);
            }
        }, 20L, 20L).getTaskId();
    }

    private synchronized void stopIfExpired(GuiCapabilityLifetime activeLifetime) {
        if (activeLifetime != null && lifetime == activeLifetime
                && activeLifetime.isExpired(System.currentTimeMillis())
                && !activeLifetime.hasActiveOperations()) {
            stop();
        }
    }

    private boolean validProject(ProjectRequest request) {
        if (request == null || request.slug == null || request.mcVersion == null
                || !SLUG.matcher(request.slug).matches() || !MC_VERSION.matcher(request.mcVersion).matches()) {
            return false;
        }
        if (request.versionIds == null) {
            return true;
        }
        return !request.versionIds.isEmpty() && request.versionIds.size() <= 128
                && request.versionIds.stream().allMatch(value -> value != null && VERSION_ID.matcher(value).matches());
    }

    private boolean validModId(String modId) {
        return modId != null && MOD_ID.matcher(modId.toLowerCase(Locale.ROOT)).matches();
    }

    private boolean validAllowlistRemoval(ModIdRequest request) {
        if (request == null || !validModId(request.modId)) {
            return false;
        }
        if (request.hashes == null) {
            return true;
        }
        return !request.hashes.isEmpty() && request.hashes.size() <= 512
                && request.hashes.stream().allMatch(value -> value != null && SHA512.matcher(value).matches());
    }

    private byte[] resource(String path) {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            return input == null ? new byte[0] : input.readAllBytes();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private String randomToken() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private enum Mode {
        ALLOWLIST,
        BLACKLIST
    }

    private static final class ProjectRequest {
        String slug;
        String mcVersion;
        List<String> versionIds;
    }

    private static final class ModIdRequest {
        String modId;
        List<String> hashes;
    }

    private static final class ViewerRequest {
        String id;
    }

    private record ApiEnvelope(boolean ok, String message, Object data) {
    }

    private record BlacklistItem(String modId, String slug) {
    }

    private record BlacklistMutation(String modId, int changed, int skipped, boolean cacheHit) {
    }

    private final class OperationLease implements AutoCloseable {
        private final GuiCapabilityLifetime operationLifetime;
        private boolean closed;

        private OperationLease(GuiCapabilityLifetime operationLifetime) {
            this.operationLifetime = operationLifetime;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            mutationRunning.set(false);
            if (operationLifetime.complete()) {
                stopIfExpired(operationLifetime);
            }
        }
    }
}

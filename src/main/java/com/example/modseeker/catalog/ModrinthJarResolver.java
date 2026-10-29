// SPDX-FileCopyrightText: 2025 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.catalog;

import com.example.modseeker.ModSeekerPlugin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class ModrinthJarResolver {
    private static final String API_BASE = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "ModSeeker/3.0.0 (ModSeeker anti-cheat plugin)";
    private static final String CDN_HOST = "cdn.modrinth.com";
    private static final int MAX_API_BYTES = 2 * 1024 * 1024;
    private static final long MAX_JAR_BYTES = 256L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final Pattern SLUG = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern MC_VERSION = Pattern.compile("[A-Za-z0-9._+\\-]{1,32}");
    private static final Pattern SHA512 = Pattern.compile("[0-9a-f]{128}");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Gson gson = new Gson();
    private final GuiModIdCache cache;
    private final Path tempRoot;
    private final JarInspector inspector;

    public ModrinthJarResolver(ModSeekerPlugin plugin, GuiModIdCache cache) {
        this.cache = cache;
        this.tempRoot = plugin.getDataFolder().toPath().resolve("gui-temp").toAbsolutePath().normalize();
        this.inspector = new JarInspector(tempRoot.toFile());
    }

    public ResolveResult resolve(String slug, String mcVersion) {
        if (!valid(slug, SLUG) || !valid(mcVersion, MC_VERSION)) {
            return ResolveResult.failure("Invalid project or Minecraft version.");
        }
        String cached = cache.get(slug, "fabric", mcVersion);
        if (cached != null) {
            return ResolveResult.success(cached, true);
        }

        Path temp = null;
        try {
            Files.createDirectories(tempRoot);
            FileSelection selection = selectFile(slug, mcVersion);
            if (selection.error != null) {
                return ResolveResult.failure(selection.error);
            }
            temp = Files.createTempFile(tempRoot, "modrinth-", ".jar");
            String downloadedHash = download(selection.url, temp);
            if (!MessageDigest.isEqual(selection.sha512.getBytes(StandardCharsets.US_ASCII),
                    downloadedHash.getBytes(StandardCharsets.US_ASCII))) {
                return ResolveResult.failure("Downloaded jar did not match Modrinth's SHA-512.");
            }
            JarInspector.Result inspected = inspector.inspect(temp.toFile());
            if (!inspected.ok()) {
                return ResolveResult.failure("Could not read the mod id: " + inspected.error());
            }
            if (!MessageDigest.isEqual(selection.sha512.getBytes(StandardCharsets.US_ASCII),
                    inspected.sha512().getBytes(StandardCharsets.US_ASCII))) {
                return ResolveResult.failure("Jar changed while it was being inspected.");
            }
            if (!cache.put(slug, "fabric", mcVersion, inspected.modId())) {
                return ResolveResult.failure("Could not update gui_modid_cache.json.");
            }
            return ResolveResult.success(inspected.modId(), false);
        } catch (Exception e) {
            return ResolveResult.failure("Could not resolve the mod id from Modrinth.");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private FileSelection selectFile(String slug, String mcVersion) throws Exception {
        String url = API_BASE + "/project/" + encode(slug) + "/version?loaders="
                + encode("[\"fabric\"]") + "&game_versions=" + encode("[\"" + mcVersion + "\"]");
        HttpResponse<String> response = apiGet(url);
        if (response.statusCode() == 404) {
            return FileSelection.failure("Mod not found on Modrinth.");
        }
        if (response.statusCode() == 429) {
            return FileSelection.failure("Modrinth rate-limited the request. Try again shortly.");
        }
        if (response.statusCode() != 200) {
            return FileSelection.failure("Modrinth returned HTTP " + response.statusCode() + ".");
        }
        List<Version> versions = gson.fromJson(response.body(), new TypeToken<List<Version>>() { }.getType());
        if (versions == null) {
            return FileSelection.failure("Modrinth returned invalid version data.");
        }
        versions.sort((left, right) -> published(right).compareTo(published(left)));
        for (Version version : versions) {
            if (version == null || version.files == null) {
                continue;
            }
            for (FileInfo file : version.files) {
                if (file == null || !file.primary || unusableJar(file.filename)
                        || file.url == null || file.hashes == null) {
                    continue;
                }
                String sha512 = file.hashes.get("sha512");
                if (sha512 == null) {
                    continue;
                }
                sha512 = sha512.toLowerCase(Locale.ROOT);
                if (!SHA512.matcher(sha512).matches()) {
                    continue;
                }
                URI uri = URI.create(file.url);
                if (!validCdn(uri)) {
                    return FileSelection.failure("Modrinth returned an unapproved download host.");
                }
                return new FileSelection(uri, sha512, null);
            }
        }
        return FileSelection.failure("No compatible primary Fabric jar was found.");
    }

    private String download(URI initial, Path target) throws Exception {
        URI current = initial;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (!validCdn(current)) {
                throw new IllegalArgumentException("unapproved download host");
            }
            HttpRequest request = HttpRequest.newBuilder(current)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/java-archive,application/octet-stream")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (isRedirect(status)) {
                response.body().close();
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IllegalStateException("redirect without location"));
                current = current.resolve(location);
                continue;
            }
            if (status != 200) {
                response.body().close();
                throw new IllegalStateException("download returned HTTP " + status);
            }
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (contentLength > MAX_JAR_BYTES) {
                response.body().close();
                throw new IllegalStateException("jar is too large");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            long total = 0;
            try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_JAR_BYTES) {
                        throw new IllegalStateException("jar is too large");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        throw new IllegalStateException("too many redirects");
    }

    private HttpResponse<String> apiGet(String url) throws Exception {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"api.modrinth.com".equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IllegalArgumentException("invalid API URL");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, BoundedBodyHandlers.utf8(MAX_API_BYTES));
        if (response.statusCode() >= 300 && response.statusCode() < 400) {
            throw new IllegalStateException("API redirects are not accepted");
        }
        if (response.body() != null && response.body().getBytes(StandardCharsets.UTF_8).length > MAX_API_BYTES) {
            throw new IllegalStateException("API response is too large");
        }
        return response;
    }

    private boolean validCdn(URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme())
                && CDN_HOST.equalsIgnoreCase(uri.getHost()) && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private boolean unusableJar(String filename) {
        if (filename == null) {
            return true;
        }
        String normalized = filename.toLowerCase(Locale.ROOT);
        return !normalized.endsWith(".jar") || normalized.endsWith("-sources.jar")
                || normalized.endsWith("-dev.jar") || normalized.endsWith("-javadoc.jar");
    }

    private String published(Version version) {
        return version == null || version.date_published == null ? "" : version.date_published;
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private boolean valid(String value, Pattern pattern) {
        return value != null && pattern.matcher(value.trim()).matches();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final class Version {
        String date_published;
        List<FileInfo> files;
    }

    private static final class FileInfo {
        Map<String, String> hashes;
        String url;
        String filename;
        boolean primary;
    }

    private record FileSelection(URI url, String sha512, String error) {
        static FileSelection failure(String error) {
            return new FileSelection(null, null, error);
        }
    }

    public record ResolveResult(boolean ok, String modId, boolean cacheHit, String error) {
        static ResolveResult success(String modId, boolean cacheHit) {
            return new ResolveResult(true, modId, cacheHit, null);
        }

        static ResolveResult failure(String error) {
            return new ResolveResult(false, null, false, error);
        }
    }
}

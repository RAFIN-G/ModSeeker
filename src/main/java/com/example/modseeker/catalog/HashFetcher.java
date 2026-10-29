// SPDX-FileCopyrightText: 2025 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.catalog;

import com.example.modseeker.policy.ModAllowlistManager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

public class HashFetcher {

    private static final String API_BASE = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "ModSeeker/3.0.0 (ModSeeker anti-cheat plugin)";
    private static final int TIMEOUT_SECONDS = 8;
    private static final int MAX_BODY_BYTES = 20 * 1024 * 1024;
    private static final int MAX_ICON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ICON_CACHE_ENTRIES = 256;
    private static final Pattern SLUG = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern VERSION_ID = Pattern.compile("[A-Za-z0-9]{1,64}");
    private static final Pattern MC_VERSION = Pattern.compile("[A-Za-z0-9._+\\-]{1,32}");

    private static final List<String> ESSENTIAL_SLUGS = List.of("fabric-api", "hidder");

    private final HttpClient http;
    private final Function<URI, HttpResponse<String>> textRequester;
    private final Gson gson = new Gson();
    private final Map<String, URI> iconUris = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, URI> eldest) {
            return size() > MAX_ICON_CACHE_ENTRIES;
        }
    };
    private final Map<String, IconResult> iconResults = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, IconResult> eldest) {
            return size() > MAX_ICON_CACHE_ENTRIES;
        }
    };

    public HashFetcher() {
        this(null);
    }

    HashFetcher(Function<URI, HttpResponse<String>> textRequester) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.textRequester = textRequester != null ? textRequester : this::sendText;
    }

    public static Set<String> essentialModIds() {
        return Set.copyOf(ESSENTIAL_SLUGS);
    }

    public static class FetchResult {
        public boolean ok;
        public String errorMessage;
        public String projectName;
        public List<ModAllowlistManager.AllowEntry> entries = new ArrayList<>();
    }

    public record EssentialFetchResult(boolean ok, String mcVersion,
            List<ModAllowlistManager.AllowEntry> entries, String error) {
        public EssentialFetchResult {
            entries = List.copyOf(entries);
        }
    }

    private static class MrProject {
        String slug;
        String title;
        String icon_url;
    }

    private static class MrSearchResponse {
        List<MrSearchHit> hits;
    }

    private static class MrSearchHit {
        String slug;
        String title;
        String description;
        String icon_url;
    }

    public record SearchHit(String slug, String title, String description) {
    }

    public record VersionView(String id, String version, String type, String hash) {
    }

    public record CatalogResult<T>(boolean ok, T data, String error) {
        static <T> CatalogResult<T> success(T data) {
            return new CatalogResult<>(true, data, null);
        }

        static <T> CatalogResult<T> failure(String error, T fallback) {
            return new CatalogResult<>(false, fallback, error);
        }
    }

    public record IconResult(boolean ok, byte[] bytes, String contentType) {
        static IconResult missing() {
            return new IconResult(false, new byte[0], "application/octet-stream");
        }
    }

    public CatalogResult<List<SearchHit>> search(String query, String mcVersion) {
        String normalizedQuery = query == null ? "" : query.trim();
        String normalizedMc = mcVersion == null ? "" : mcVersion.trim();
        if (normalizedQuery.length() > 80 || !MC_VERSION.matcher(normalizedMc).matches()) {
            return CatalogResult.failure("Invalid search or Minecraft version.", List.of());
        }
        try {
            String facets = "[[\"project_type:mod\"],[\"categories:fabric\"],[\"versions:"
                    + normalizedMc + "\"]]";
            String url = API_BASE + "/search?limit=20&index=relevance&query=" + enc(normalizedQuery)
                    + "&facets=" + enc(facets);
            HttpResponse<String> response = get(url);
            if (response == null || response.statusCode() != 200) {
                return CatalogResult.failure(apiError(response), List.of());
            }
            MrSearchResponse parsed = gson.fromJson(response.body(), MrSearchResponse.class);
            if (parsed == null || parsed.hits == null) {
                return CatalogResult.failure("Modrinth returned invalid search data.", List.of());
            }
            List<SearchHit> hits = new ArrayList<>();
            for (MrSearchHit hit : parsed.hits) {
                if (hit == null || !validSlug(hit.slug)) {
                    continue;
                }
                URI iconUri = iconUri(hit.icon_url);
                if (iconUri != null) {
                    rememberIconUri(hit.slug, iconUri);
                }
                hits.add(new SearchHit(hit.slug, bounded(hit.title, 160), bounded(hit.description, 500)));
            }
            return CatalogResult.success(List.copyOf(hits));
        } catch (Exception e) {
            return CatalogResult.failure("Could not reach Modrinth.", List.of());
        }
    }

    public CatalogResult<List<VersionView>> versions(String slug, String mcVersion) {
        if (!validSlug(slug) || mcVersion == null || !MC_VERSION.matcher(mcVersion.trim()).matches()) {
            return CatalogResult.failure("Invalid project or Minecraft version.", List.of());
        }
        try {
            String url = API_BASE + "/project/" + enc(slug) + "/version?loaders=" + enc("[\"fabric\"]")
                    + "&game_versions=" + enc("[\"" + mcVersion.trim() + "\"]");
            HttpResponse<String> response = get(url);
            if (response == null || response.statusCode() != 200) {
                return CatalogResult.failure(apiError(response), List.of());
            }
            List<MrVersion> parsed = gson.fromJson(response.body(), new TypeToken<List<MrVersion>>() { }.getType());
            if (parsed == null) {
                return CatalogResult.failure("Modrinth returned invalid version data.", List.of());
            }
            List<VersionView> versions = new ArrayList<>();
            for (MrVersion version : parsed) {
                if (version == null || version.id == null || version.version_number == null
                        || !VERSION_ID.matcher(version.id).matches()) {
                    continue;
                }
                MrFile file = primaryFile(version);
                String hash = sha512(file);
                if (hash == null) {
                    continue;
                }
                versions.add(new VersionView(version.id, bounded(version.version_number, 128),
                        bounded(version.version_type, 32), hash));
            }
            return CatalogResult.success(List.copyOf(versions));
        } catch (Exception e) {
            return CatalogResult.failure("Could not reach Modrinth.", List.of());
        }
    }

    public IconResult fetchIcon(String slug) {
        if (!validSlug(slug)) {
            return IconResult.missing();
        }
        String key = slug.trim().toLowerCase(Locale.ROOT);
        IconResult cached = cachedIcon(key);
        if (cached != null) {
            return cached;
        }
        try {
            URI uri = cachedIconUri(key);
            if (uri == null) {
                HttpResponse<String> projectResponse = get(API_BASE + "/project/" + enc(slug));
                if (projectResponse == null || projectResponse.statusCode() != 200) {
                    return IconResult.missing();
                }
                MrProject project = gson.fromJson(projectResponse.body(), MrProject.class);
                uri = project == null ? null : iconUri(project.icon_url);
                if (uri == null) {
                    return IconResult.missing();
                }
                rememberIconUri(key, uri);
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "image/png,image/jpeg,image/webp")
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                return IconResult.missing();
            }
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > MAX_ICON_BYTES) {
                response.body().close();
                return IconResult.missing();
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                    .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!contentType.equals("image/png") && !contentType.equals("image/jpeg")
                    && !contentType.equals("image/webp")) {
                response.body().close();
                return IconResult.missing();
            }
            try (InputStream input = response.body(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                int total = 0;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_ICON_BYTES) {
                        return IconResult.missing();
                    }
                    output.write(buffer, 0, read);
                }
                IconResult result = new IconResult(true, output.toByteArray(), contentType);
                rememberIcon(key, result);
                return result;
            }
        } catch (Exception e) {
            return IconResult.missing();
        }
    }

    private static class MrFile {
        Map<String, String> hashes;
        String url;
        String filename;
        boolean primary;
    }

    private static class MrVersion {
        String id;
        String name;
        String version_number;
        List<String> game_versions;
        List<String> loaders;
        String version_type;
        List<MrFile> files;
    }

    public FetchResult fetchForCommand(String link, String mcVersion) {
        return fetchForCommand(link, mcVersion, null);
    }

    public FetchResult fetchForCommand(String link, String mcVersion, Collection<String> versionIds) {
        FetchResult result = new FetchResult();

        String slug = slugFromLink(link);
        String normalizedMc = mcVersion == null ? "" : mcVersion.trim();
        if (slug == null || !MC_VERSION.matcher(normalizedMc).matches()) {
            result.ok = false;
            result.errorMessage = "Invalid Modrinth link or Minecraft version.";
            return result;
        }

        Set<String> selected = null;
        if (versionIds != null) {
            selected = new HashSet<>();
            for (String versionId : versionIds) {
                if (versionId == null || !VERSION_ID.matcher(versionId).matches()) {
                    result.ok = false;
                    result.errorMessage = "Invalid Modrinth version selection.";
                    return result;
                }
                selected.add(versionId);
            }
            if (selected.isEmpty() || selected.size() > 128) {
                result.ok = false;
                result.errorMessage = "Select at least one compatible version.";
                return result;
            }
        }

        String title = fetchProjectTitle(slug);

        List<MrVersion> versions;
        try {
            String url = API_BASE + "/project/" + enc(slug) + "/version"
                    + "?loaders=" + enc("[\"fabric\"]")
                    + "&game_versions=" + enc("[\"" + normalizedMc + "\"]");
            HttpResponse<String> resp = get(url);
            if (resp == null) {
                result.ok = false;
                result.errorMessage = "Couldn't reach Modrinth. Try again.";
                return result;
            }
            int code = resp.statusCode();
            if (code == 404) {
                result.ok = false;
                result.errorMessage = "Mod not found on Modrinth.";
                return result;
            }
            if (code == 429) {
                result.ok = false;
                result.errorMessage = "Modrinth rate-limited, try again shortly.";
                return result;
            }
            if (code != 200) {
                result.ok = false;
                result.errorMessage = "Modrinth returned HTTP " + code + ".";
                return result;
            }
            versions = gson.fromJson(resp.body(), new TypeToken<List<MrVersion>>() {}.getType());
        } catch (Exception e) {
            result.ok = false;
            result.errorMessage = "Couldn't reach Modrinth (" + e.getMessage() + ").";
            return result;
        }

        String projectName = title != null ? title : slug;
        result.projectName = projectName;

        if (versions == null || versions.isEmpty()) {
            result.ok = false;
            result.errorMessage = "No Fabric versions found for " + projectName + " on " + normalizedMc + ".";
            return result;
        }

        Set<String> matched = new HashSet<>();
        for (MrVersion v : versions) {
            if (selected != null && (v == null || v.id == null || !selected.contains(v.id))) {
                continue;
            }
            if (selected != null) {
                matched.add(v.id);
            }
            ModAllowlistManager.AllowEntry entry = toEntry(v, slug, projectName, normalizedMc, false);
            if (entry != null) {
                result.entries.add(entry);
            } else if (selected != null) {
                result.ok = false;
                result.errorMessage = "A selected version has no usable Fabric file.";
                return result;
            }
        }

        if (selected != null && !matched.equals(selected)) {
            result.ok = false;
            result.errorMessage = "A selected version is no longer available for this Minecraft version.";
            result.entries.clear();
            return result;
        }

        if (result.entries.isEmpty()) {
            result.ok = false;
            result.errorMessage = "No usable Fabric file found for " + projectName + " on " + normalizedMc + ".";
            return result;
        }

        result.ok = true;
        return result;
    }

    public EssentialFetchResult fetchEssentials(String requestedMinecraftVersion) {
        String mcVersion = requestedMinecraftVersion == null ? "" : requestedMinecraftVersion.trim();
        if (!MC_VERSION.matcher(mcVersion).matches()) {
            return new EssentialFetchResult(false, mcVersion, List.of(), "Invalid server Minecraft version.");
        }
        List<ModAllowlistManager.AllowEntry> out = new ArrayList<>();
        for (String slug : ESSENTIAL_SLUGS) {
            try {
                String title = fetchProjectTitle(slug);
                String url = API_BASE + "/project/" + enc(slug) + "/version?loaders=" + enc("[\"fabric\"]")
                        + "&game_versions=" + enc("[\"" + mcVersion + "\"]");
                HttpResponse<String> resp = get(url);
                if (resp == null || resp.statusCode() != 200) {
                    return new EssentialFetchResult(false, mcVersion, List.of(), apiError(resp));
                }
                List<MrVersion> versions = gson.fromJson(resp.body(),
                        new TypeToken<List<MrVersion>>() {}.getType());
                if (versions == null || versions.isEmpty()) {
                    return new EssentialFetchResult(false, mcVersion, List.of(),
                            "No Fabric versions found for " + slug + " on " + mcVersion + ".");
                }
                String projectName = title != null ? title : slug;
                for (MrVersion v : versions) {
                    ModAllowlistManager.AllowEntry entry = toEntry(v, slug, projectName, mcVersion, true);
                    if (entry == null) {
                        return new EssentialFetchResult(false, mcVersion, List.of(),
                                "A Modrinth version has no usable Fabric JAR for " + slug
                                        + " on " + mcVersion + ".");
                    }
                    out.add(entry);
                }
            } catch (Exception e) {
                return new EssentialFetchResult(false, mcVersion, List.of(), "Could not reach Modrinth.");
            }
        }
        return new EssentialFetchResult(true, mcVersion, out, "");
    }

    public String reverseName(String sha512) {
        try {
            if (sha512 == null || sha512.isEmpty()) {
                return null;
            }
            String url = API_BASE + "/version_file/" + enc(sha512.toLowerCase(Locale.ROOT)) + "?algorithm=sha512";
            HttpResponse<String> resp = get(url);
            if (resp == null || resp.statusCode() != 200) {
                return null;
            }
            MrVersion v = gson.fromJson(resp.body(), MrVersion.class);
            if (v == null) {
                return null;
            }
            if (v.name != null && !v.name.isEmpty()) {
                return v.name;
            }
            return v.version_number;
        } catch (Exception e) {
            return null;
        }
    }

    private String fetchProjectTitle(String slug) {
        try {
            HttpResponse<String> resp = get(API_BASE + "/project/" + enc(slug));
            if (resp == null || resp.statusCode() != 200) {
                return null;
            }
            MrProject p = gson.fromJson(resp.body(), MrProject.class);
            return p != null ? p.title : null;
        } catch (Exception e) {
            return null;
        }
    }

    private ModAllowlistManager.AllowEntry toEntry(MrVersion v, String slug, String projectName,
            String mcVersion, boolean essential) {
        if (v == null || v.version_number == null || v.version_number.isBlank()
                || mcVersion != null && (v.game_versions == null || !v.game_versions.contains(mcVersion)
                        || v.loaders == null || !v.loaders.contains("fabric"))) {
            return null;
        }
        MrFile chosen = primaryFile(v);
        String sha512 = sha512(chosen);
        if (sha512 == null) {
            return null;
        }

        ModAllowlistManager.AllowEntry e = new ModAllowlistManager.AllowEntry();
        e.modid = slug;
        e.name = projectName;
        e.version = v.version_number;
        e.mcVersion = mcVersion != null ? mcVersion : joinVersions(v.game_versions);
        e.loader = "fabric";
        e.filename = chosen.filename;
        e.algo = "sha512";
        e.hash = sha512.toLowerCase(Locale.ROOT);
        e.essential = essential;
        e.label = slug + " v" + v.version_number;
        return e;
    }

    private MrFile primaryFile(MrVersion version) {
        if (version == null || version.files == null) {
            return null;
        }
        MrFile fallback = null;
        for (MrFile file : version.files) {
            if (file == null || file.filename == null) {
                continue;
            }
            String filename = file.filename.toLowerCase(Locale.ROOT);
            if (!filename.endsWith(".jar") || filename.endsWith("-sources.jar") || filename.endsWith("-dev.jar")
                    || filename.endsWith("-javadoc.jar") || sha512(file) == null) {
                continue;
            }
            if (file.primary) {
                return file;
            }
            if (fallback == null) {
                fallback = file;
            }
        }
        return fallback;
    }

    private String sha512(MrFile file) {
        if (file == null || file.hashes == null) {
            return null;
        }
        String hash = file.hashes.get("sha512");
        return hash == null || hash.isEmpty() ? null : hash.toLowerCase(Locale.ROOT);
    }

    private String joinVersions(List<String> versions) {
        if (versions == null || versions.isEmpty()) {
            return "";
        }
        return String.join(";", versions);
    }

    private HttpResponse<String> get(String url) throws Exception {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"api.modrinth.com".equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IllegalArgumentException("Invalid Modrinth API URL");
        }
        HttpResponse<String> resp = textRequester.apply(uri);
        if (resp == null || resp.statusCode() >= 300 && resp.statusCode() < 400) {
            return null;
        }
        if (resp.body() != null && resp.body().getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return null;
        }
        return resp;
    }

    private HttpResponse<String> sendText(URI uri) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build();
        try {
            return http.send(req, BoundedBodyHandlers.utf8(MAX_BODY_BYTES));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.io.IOException | UncheckedIOException e) {
            return null;
        }
    }

    private String apiError(HttpResponse<?> response) {
        if (response == null) {
            return "Could not reach Modrinth.";
        }
        if (response.statusCode() == 404) {
            return "Mod not found on Modrinth.";
        }
        if (response.statusCode() == 429) {
            return "Modrinth rate-limited the request. Try again shortly.";
        }
        return "Modrinth returned HTTP " + response.statusCode() + ".";
    }

    private boolean validSlug(String slug) {
        return slug != null && SLUG.matcher(slug.trim()).matches();
    }

    private String bounded(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private boolean validCdn(URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme())
                && "cdn.modrinth.com".equalsIgnoreCase(uri.getHost()) && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private URI iconUri(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            return validCdn(uri) ? uri : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private URI cachedIconUri(String slug) {
        synchronized (iconUris) {
            return iconUris.get(slug);
        }
    }

    private void rememberIconUri(String slug, URI uri) {
        synchronized (iconUris) {
            iconUris.put(slug.toLowerCase(Locale.ROOT), uri);
        }
    }

    private IconResult cachedIcon(String slug) {
        synchronized (iconResults) {
            return iconResults.get(slug);
        }
    }

    private void rememberIcon(String slug, IconResult result) {
        synchronized (iconResults) {
            iconResults.put(slug, result);
        }
    }

    private static String slugFromLink(String link) {
        if (link == null) {
            return null;
        }
        String s = link.trim();
        if (s.isEmpty()) {
            return null;
        }

        String slug = null;
        if (s.startsWith("http://") || s.startsWith("https://")) {
            URI uri;
            try {
                uri = URI.create(s);
            } catch (Exception e) {
                return null;
            }
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            if (!host.equals("modrinth.com") && !host.equals("www.modrinth.com")
                    && !host.equals("api.modrinth.com")) {
                return null;
            }
            String path = uri.getPath();
            if (path == null) {
                return null;
            }
            String[] parts = path.split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                if (parts[i].equals("mod") || parts[i].equals("project") || parts[i].equals("plugin")
                        || parts[i].equals("datapack") || parts[i].equals("resourcepack")
                        || parts[i].equals("shader") || parts[i].equals("modpack")) {
                    slug = parts[i + 1];
                    break;
                }
            }
            if (slug == null) {
                for (String p : parts) {
                    if (!p.isEmpty()) {
                        slug = p;
                    }
                }
            }
        } else {
            slug = s;
        }

        if (slug == null || slug.isEmpty()) {
            return null;
        }
        if (!SLUG.matcher(slug).matches()) {
            return null;
        }
        return slug;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

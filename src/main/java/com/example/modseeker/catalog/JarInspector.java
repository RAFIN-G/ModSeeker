// SPDX-FileCopyrightText: 2026 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.catalog;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class JarInspector {
    private static final long MAX_JAR_BYTES = 256L * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_-]{0,63}");

    private final Path root;

    public JarInspector(File root) {
        this.root = root.toPath().toAbsolutePath().normalize();
    }

    public Result inspect(File file) {
        Path path = file.toPath().toAbsolutePath().normalize();
        String filename = file.getName();
        String hash = "";
        try {
            Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path realPath = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes before = Files.readAttributes(realPath, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!realPath.startsWith(realRoot) || Files.isSymbolicLink(path) || before.isOther()
                    || !before.isRegularFile() || !filename.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return Result.failure(filename, "File is outside the inspection boundary", hash);
            }
            if (before.size() <= 0 || before.size() > MAX_JAR_BYTES) {
                return Result.failure(filename, "Jar size is outside the allowed range", hash);
            }

            hash = hash(realPath);
            Metadata metadata = metadata(realPath);
            BasicFileAttributes after = Files.readAttributes(realPath, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
                return Result.failure(filename, "Jar changed during inspection", "");
            }
            return new Result(true, hash, metadata.modId(), metadata.version(), filename, "");
        } catch (MetadataException e) {
            return Result.failure(filename, e.getMessage(), hash);
        } catch (Exception e) {
            return Result.failure(filename, "Jar inspection failed: " + e.getClass().getSimpleName(), "");
        }
    }

    private String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Metadata metadata(Path path) throws Exception {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            int manifests = 0;
            ZipEntry manifest = null;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().equals("fabric.mod.json")) {
                    manifests++;
                    manifest = entry;
                }
            }
            if (manifests != 1 || manifest == null || manifest.isDirectory()) {
                throw new MetadataException("Expected exactly one root fabric.mod.json");
            }
            byte[] bytes = readBounded(zip.getInputStream(manifest), MAX_MANIFEST_BYTES);
            JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            String id = string(json, "id").toLowerCase(Locale.ROOT);
            String version = string(json, "version");
            if (!MOD_ID.matcher(id).matches()) {
                throw new MetadataException("fabric.mod.json contains an invalid mod id");
            }
            if (version.isBlank() || version.length() > 128 || version.indexOf('|') >= 0 || version.indexOf(',') >= 0) {
                throw new MetadataException("fabric.mod.json contains an invalid version");
            }
            return new Metadata(id, version);
        }
    }

    private byte[] readBounded(InputStream input, int limit) throws Exception {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > limit) {
                    throw new MetadataException("fabric.mod.json exceeds the size limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private String string(JsonObject json, String name) throws MetadataException {
        if (!json.has(name) || !json.get(name).isJsonPrimitive()
                || !json.get(name).getAsJsonPrimitive().isString()) {
            throw new MetadataException("fabric.mod.json is missing " + name);
        }
        return json.get(name).getAsString();
    }

    public record Result(boolean ok, String sha512, String modId, String version, String filename, String error) {
        static Result failure(String filename, String error, String hash) {
            return new Result(false, hash, "", "", filename, error);
        }

        public boolean hasHash() {
            return sha512 != null && sha512.length() == 128;
        }
    }

    private record Metadata(String modId, String version) {
    }

    private static final class MetadataException extends Exception {
        private static final long serialVersionUID = 1L;

        MetadataException(String message) {
            super(message);
        }
    }
}

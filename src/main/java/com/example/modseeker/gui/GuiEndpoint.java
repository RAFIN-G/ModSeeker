// SPDX-FileCopyrightText: 2026 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.gui;

import java.net.InetAddress;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class GuiEndpoint {
    private final String origin;
    private final Set<String> allowedHosts;
    private final boolean insecureRemote;

    private GuiEndpoint(String origin, Set<String> allowedHosts, boolean insecureRemote) {
        this.origin = origin;
        this.allowedHosts = Set.copyOf(allowedHosts);
        this.insecureRemote = insecureRemote;
    }

    static Configuration configure(String externalText) throws Exception {
        URI externalOrigin = parseExternalOrigin(externalText);
        boolean localExternal = externalOrigin == null || isLocalHost(externalOrigin.getHost());
        boolean proxy = externalOrigin != null && externalOrigin.getScheme().equalsIgnoreCase("https");
        InetAddress bindAddress = InetAddress.getByName(localExternal || proxy ? "127.0.0.1" : "0.0.0.0");
        return new Configuration(bindAddress, externalOrigin);
    }

    private static boolean isLocalHost(String host) {
        return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")
                || host.equals("::1") || host.equals("[::1]");
    }

    private static URI parseExternalOrigin(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("gui.externalUrl is invalid", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath();
        if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || path != null && !path.isEmpty() && !path.equals("/")
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("gui.externalUrl must be an HTTP or HTTPS origin without a path");
        }
        return URI.create(canonicalOrigin(uri));
    }

    private static String canonicalOrigin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String renderedHost = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        int port = uri.getPort();
        boolean defaultPort = port == -1 || scheme.equals("http") && port == 80
                || scheme.equals("https") && port == 443;
        return scheme + "://" + renderedHost + (defaultPort ? "" : ":" + port);
    }

    private static String addressHost(InetAddress address) {
        String host = address.getHostAddress();
        int scope = host.indexOf('%');
        if (scope >= 0) {
            host = host.substring(0, scope);
        }
        return host.contains(":") ? "[" + host + "]" : host;
    }

    String link(String token) {
        return origin + "/#" + token;
    }

    boolean acceptsHost(String value) {
        if (value == null || value.length() > 512 || value.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        return allowedHosts.contains(value.toLowerCase(Locale.ROOT));
    }

    boolean acceptsOrigin(String value) {
        if (value == null) {
            return true;
        }
        if (value.equals("null")) {
            return false;
        }
        try {
            URI candidate = URI.create(value);
            String path = candidate.getRawPath();
            return candidate.getScheme() != null && candidate.getHost() != null
                    && candidate.getRawUserInfo() == null && candidate.getRawQuery() == null
                    && candidate.getRawFragment() == null && (path == null || path.isEmpty())
                    && canonicalOrigin(candidate).equals(origin);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    boolean insecureRemote() {
        return insecureRemote;
    }

    record Configuration(InetAddress bindAddress, URI externalOrigin) {
        boolean requiresFixedPort() {
            return externalOrigin != null || !bindAddress.isLoopbackAddress();
        }

        GuiEndpoint activate(int port) {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("GUI port is invalid");
            }
            if (externalOrigin != null) {
                String origin = canonicalOrigin(externalOrigin);
                LinkedHashSet<String> hosts = new LinkedHashSet<>();
                String authority = externalOrigin.getRawAuthority().toLowerCase(Locale.ROOT);
                hosts.add(authority);
                if (externalOrigin.getPort() == -1) {
                    hosts.add(authority + (externalOrigin.getScheme().equalsIgnoreCase("https") ? ":443" : ":80"));
                }
                boolean localHost = isLocalHost(externalOrigin.getHost());
                return new GuiEndpoint(origin, hosts, !bindAddress.isLoopbackAddress()
                        || externalOrigin.getScheme().equalsIgnoreCase("http") && !localHost);
            }
            String authority = addressHost(bindAddress) + ":" + port;
            return new GuiEndpoint("http://" + authority, Set.of(authority.toLowerCase(Locale.ROOT)),
                    !bindAddress.isLoopbackAddress());
        }
    }
}

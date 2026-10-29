// SPDX-FileCopyrightText: 2026 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.catalog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

final class BoundedBodyHandlers {
    private BoundedBodyHandlers() {
    }

    static HttpResponse.BodyHandler<String> utf8(int maximum) {
        if (maximum < 0) {
            throw new IllegalArgumentException("Maximum body size must be non-negative");
        }
        return responseInfo -> HttpResponse.BodySubscribers.mapping(
                HttpResponse.BodySubscribers.ofInputStream(), input -> {
                    try (input) {
                        return readUtf8(input, maximum);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    static String readUtf8(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 64 * 1024));
        byte[] buffer = new byte[8192];
        int total = 0;
        while (true) {
            int read = input.read(buffer);
            if (read < 0) {
                return output.toString(StandardCharsets.UTF_8);
            }
            if (read == 0) {
                continue;
            }
            total = Math.addExact(total, read);
            if (total > maximum) {
                throw new IOException("HTTP response is too large");
            }
            output.write(buffer, 0, read);
        }
    }
}

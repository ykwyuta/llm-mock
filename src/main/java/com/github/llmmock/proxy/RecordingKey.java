package com.github.llmmock.proxy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import com.github.llmmock.core.Provider;

/**
 * Derives the identity a request is matched on when replaying.
 *
 * <p>The key covers provider, method, path, query and body, but deliberately not headers:
 * a recording made with one API key has to replay for a caller sending another. Redacted
 * query parameters are dropped so record and replay agree.
 */
public final class RecordingKey {

    private RecordingKey() {
    }

    public static String of(Provider provider, String method, String path, String query,
                            byte[] body, Collection<String> redactedQueryParams) {
        StringBuilder material = new StringBuilder()
                .append(provider == null ? "" : provider.name()).append('\n')
                .append(method == null ? "" : method.toUpperCase(Locale.ROOT)).append('\n')
                .append(path == null ? "" : path).append('\n')
                .append(canonicalQuery(query, redactedQueryParams)).append('\n');

        MessageDigest digest = sha256();
        digest.update(material.toString().getBytes(StandardCharsets.UTF_8));
        if (body != null) {
            digest.update(body);
        }
        return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
    }

    /**
     * Sorts the query parameters and removes the redacted ones, so a call that differs only
     * in parameter order or in its API key still matches the same recording.
     */
    static String canonicalQuery(String query, Collection<String> redactedQueryParams) {
        if (query == null || query.isBlank()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            boolean redacted = redactedQueryParams != null && redactedQueryParams.stream()
                    .anyMatch(candidate -> candidate.equalsIgnoreCase(name));
            if (!redacted) {
                kept.add(pair);
            }
        }
        kept.sort(String::compareTo);
        return String.join("&", kept);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required but unavailable", ex);
        }
    }
}

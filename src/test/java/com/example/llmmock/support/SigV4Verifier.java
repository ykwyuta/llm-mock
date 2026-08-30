package com.example.llmmock.support;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * An independent AWS SigV4 verifier, written straight from the published signing
 * specification rather than from the SDK that produces the signatures under test.
 *
 * <p>It does what a real AWS endpoint does: take the request as received, rebuild the
 * canonical request from the {@code SignedHeaders} named in the Authorization header,
 * recompute the signature with the shared secret, and compare. A signature that validates
 * here covers the exact host, path, query and body the upstream actually saw - which is
 * the whole point of re-signing a proxied request.
 */
public final class SigV4Verifier {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";

    private SigV4Verifier() {
    }

    /** The fields parsed out of an {@code Authorization: AWS4-HMAC-SHA256 ...} header. */
    public record Credential(String accessKeyId, String date, String region, String service,
                             List<String> signedHeaders, String signature) {
    }

    public static Credential parse(String authorization) {
        if (authorization == null || !authorization.startsWith(ALGORITHM + " ")) {
            throw new IllegalArgumentException("Not a SigV4 Authorization header: " + authorization);
        }
        String accessKeyId = null;
        String date = null;
        String region = null;
        String service = null;
        List<String> signedHeaders = List.of();
        String signature = null;

        for (String part : authorization.substring(ALGORITHM.length() + 1).split(",")) {
            String[] pair = part.trim().split("=", 2);
            switch (pair[0]) {
                case "Credential" -> {
                    String[] scope = pair[1].split("/");
                    accessKeyId = scope[0];
                    date = scope[1];
                    region = scope[2];
                    service = scope[3];
                }
                case "SignedHeaders" -> signedHeaders = List.of(pair[1].split(";"));
                case "Signature" -> signature = pair[1];
                default -> throw new IllegalArgumentException("Unexpected field " + pair[0]);
            }
        }
        return new Credential(accessKeyId, date, region, service, signedHeaders, signature);
    }

    /**
     * Recomputes the signature for a received request and compares it with the one the
     * caller presented.
     *
     * @param path  the raw, still percent-encoded path as it arrived on the wire
     * @param query the raw query string, or null
     */
    public static boolean verify(String method, String path, String query,
                                 Map<String, List<String>> headers, byte[] body,
                                 String secretAccessKey) {
        String authorization = headerValue(headers, "authorization");
        Credential credential = parse(authorization);

        String canonicalRequest = canonicalRequest(method, path, query, headers,
                credential.signedHeaders(), body);
        String amzDate = headerValue(headers, "x-amz-date");
        String scope = credential.date() + "/" + credential.region() + "/"
                + credential.service() + "/aws4_request";
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n"
                + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] key = signingKey(secretAccessKey, credential.date(), credential.region(),
                credential.service());
        String expected = hex(hmac(key, stringToSign.getBytes(StandardCharsets.UTF_8)));
        return expected.equals(credential.signature());
    }

    public static String canonicalRequest(String method, String path, String query,
                                   Map<String, List<String>> headers, List<String> signedHeaders,
                                   byte[] body) {
        StringBuilder canonicalHeaders = new StringBuilder();
        for (String name : signedHeaders) {
            canonicalHeaders.append(name).append(':')
                    .append(collapse(headerValue(headers, name))).append('\n');
        }
        return method + "\n"
                + canonicalUri(path) + "\n"
                + canonicalQuery(query) + "\n"
                + canonicalHeaders + "\n"
                + String.join(";", signedHeaders) + "\n"
                + hex(sha256(body == null ? new byte[0] : body));
    }

    /**
     * Every path segment is URI-encoded once more on top of the encoding already on the
     * wire. This is the rule that applies to every service except S3, and the reason a
     * Bedrock model id containing ':' needs care.
     */
    static String canonicalUri(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String[] segments = path.split("/", -1);
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                encoded.append('/');
            }
            encoded.append(uriEncode(segments[i]));
        }
        return encoded.toString();
    }

    static String canonicalQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        TreeMap<String, List<String>> sorted = new TreeMap<>();
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            sorted.computeIfAbsent(uriEncode(decode(name)), key -> new ArrayList<>())
                    .add(uriEncode(decode(value)));
        }
        List<String> parts = new ArrayList<>();
        sorted.forEach((name, values) -> {
            values.sort(String::compareTo);
            values.forEach(value -> parts.add(name + "=" + value));
        });
        return String.join("&", parts);
    }

    /** RFC 3986 unreserved characters stay; everything else becomes uppercase %XX. */
    static String uriEncode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            char ch = (char) (raw & 0xFF);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                encoded.append(ch);
            } else {
                encoded.append('%').append(String.format("%02X", raw & 0xFF));
            }
        }
        return encoded.toString();
    }

    private static byte[] signingKey(String secretAccessKey, String date, String region,
                                     String service) {
        byte[] key = ("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8);
        key = hmac(key, date.getBytes(StandardCharsets.UTF_8));
        key = hmac(key, region.getBytes(StandardCharsets.UTF_8));
        key = hmac(key, service.getBytes(StandardCharsets.UTF_8));
        return hmac(key, "aws4_request".getBytes(StandardCharsets.UTF_8));
    }

    private static String headerValue(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (header.getKey().equalsIgnoreCase(name)) {
                return String.join(",", header.getValue());
            }
        }
        return null;
    }

    /** Canonical header values have leading/trailing space trimmed and runs collapsed. */
    private static String collapse(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes).toLowerCase(Locale.ROOT);
    }
}

package com.example.llmmock.proxy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.example.llmmock.core.Provider;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One captured request/response exchange, as written to disk.
 *
 * <p>Bodies are stored as readable text whenever the content type is textual, so a
 * recording can be reviewed and hand-edited in a pull request. Binary payloads - notably
 * the Bedrock event stream - fall back to base64.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Recording(
        String key,
        Provider provider,
        Instant recordedAt,
        RecordedRequest request,
        RecordedResponse response) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecordedRequest(
            String method,
            String path,
            String query,
            Map<String, List<String>> headers,
            String body) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecordedResponse(
            int status,
            Map<String, List<String>> headers,
            String body,
            String bodyBase64) {

        public byte[] bytes() {
            if (bodyBase64 != null) {
                return Base64.getDecoder().decode(bodyBase64);
            }
            return body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        }

        public String contentType() {
            if (headers == null) {
                return null;
            }
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                if ("content-type".equalsIgnoreCase(header.getKey())
                        && header.getValue() != null && !header.getValue().isEmpty()) {
                    return header.getValue().get(0);
                }
            }
            return null;
        }
    }

    /** Builds a response body holder, choosing text or base64 from the content type. */
    public static RecordedResponse response(int status, Map<String, List<String>> headers,
                                            byte[] body) {
        RecordedResponse probe = new RecordedResponse(status, headers, null, null);
        if (isTextual(probe.contentType())) {
            return new RecordedResponse(status, headers, new String(body, StandardCharsets.UTF_8),
                    null);
        }
        return new RecordedResponse(status, headers, null, Base64.getEncoder().encodeToString(body));
    }

    private static boolean isTextual(String contentType) {
        if (contentType == null) {
            return false;
        }
        String value = contentType.toLowerCase();
        return value.startsWith("text/") || value.contains("json") || value.contains("xml")
                || value.contains("x-www-form-urlencoded");
    }
}

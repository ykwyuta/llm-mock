package com.github.llmmock.core;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Per-request escape hatch. Any test can bend a single call without registering a stub by
 * sending {@code X-Mock-*} headers; these win over every stored stub rule.
 *
 * <pre>
 * X-Mock-Text:           text the mock should answer with
 * X-Mock-Finish-Reason:  stop | length | tool_use | content_filter | stop_sequence
 * X-Mock-Status:         HTTP status to fail with (>= 400)
 * X-Mock-Error-Type:     provider neutral error type for that failure
 * X-Mock-Error-Message:  message for that failure
 * X-Mock-Delay-Ms:       artificial latency before responding
 * X-Mock-Tool-Name:      emit a tool call with this name
 * X-Mock-Tool-Arguments: raw JSON arguments for that tool call
 * X-Mock-Input-Tokens:   force the reported prompt token count
 * X-Mock-Output-Tokens:  force the reported completion token count
 * X-Mock-Stub:           select a stored stub rule by name, skipping normal matching
 * </pre>
 */
public record MockOverrides(
        String text,
        FinishReason finishReason,
        Integer status,
        String errorType,
        String errorMessage,
        Long delayMs,
        String toolName,
        String toolArguments,
        Integer inputTokens,
        Integer outputTokens,
        String stubName) {

    public static final MockOverrides NONE =
            new MockOverrides(null, null, null, null, null, null, null, null, null, null, null);

    public static MockOverrides from(HttpServletRequest request) {
        if (request == null) {
            return NONE;
        }
        return new MockOverrides(
                header(request, "X-Mock-Text"),
                FinishReason.from(header(request, "X-Mock-Finish-Reason"), null),
                intHeader(request, "X-Mock-Status"),
                header(request, "X-Mock-Error-Type"),
                header(request, "X-Mock-Error-Message"),
                longHeader(request, "X-Mock-Delay-Ms"),
                header(request, "X-Mock-Tool-Name"),
                header(request, "X-Mock-Tool-Arguments"),
                intHeader(request, "X-Mock-Input-Tokens"),
                intHeader(request, "X-Mock-Output-Tokens"),
                header(request, "X-Mock-Stub"));
    }

    /**
     * Present-but-empty is a value, not an absence: {@code X-Mock-Text:} with nothing after
     * it is how a test asks for an answer with no text at all, e.g. a tool call on its own.
     */
    private static String header(HttpServletRequest request, String name) {
        return request.getHeader(name);
    }

    private static Integer intHeader(HttpServletRequest request, String name) {
        String value = header(request, name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            throw MockApiException.invalidRequest(name + " must be an integer, got '" + value + "'");
        }
    }

    private static Long longHeader(HttpServletRequest request, String name) {
        Integer value = intHeader(request, name);
        return value == null ? null : value.longValue();
    }
}

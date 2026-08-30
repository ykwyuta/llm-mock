package com.example.llmmock.core;

/**
 * A failure the mock was asked to simulate (via a stub rule or an {@code X-Mock-Status}
 * header), or a genuine validation failure of the inbound payload. Each provider adapter
 * renders it into that provider's error envelope.
 */
public class MockApiException extends RuntimeException {

    private final int status;
    private final String type;

    public MockApiException(int status, String type, String message) {
        super(message);
        this.status = status;
        this.type = type;
    }

    public int status() {
        return status;
    }

    /** Provider neutral error type, e.g. {@code invalid_request}, {@code rate_limit}. */
    public String type() {
        return type;
    }

    public static MockApiException invalidRequest(String message) {
        return new MockApiException(400, "invalid_request", message);
    }

    public static MockApiException notFound(String message) {
        return new MockApiException(404, "not_found", message);
    }
}

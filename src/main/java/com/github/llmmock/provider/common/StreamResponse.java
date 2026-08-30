package com.github.llmmock.provider.common;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Starts a streaming response on the current request thread.
 *
 * <p>Writing inline rather than handing back a {@code StreamingResponseBody} keeps every
 * endpoint on one code path regardless of the {@code stream} flag, and lets plain MockMvc
 * assert on streamed bodies without an async dispatch. The bodies a mock produces are
 * small, so occupying the container thread for the duration costs nothing.
 */
public final class StreamResponse {

    private StreamResponse() {
    }

    public static OutputStream begin(HttpServletResponse response, String contentType)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(contentType);
        // A charset belongs on the textual streams only. Bedrock's event stream is binary
        // and the real service sends its content type bare, so appending one would differ
        // from what an AWS SDK sees in production.
        if (contentType.startsWith("text/") || contentType.startsWith("application/json")) {
            response.setCharacterEncoding(StandardCharsets.UTF_8);
        }
        response.setHeader("Cache-Control", "no-cache");
        return response.getOutputStream();
    }

    /** Sleeps between chunks so callers can exercise read timeouts and cancellation. */
    public static void pause(long millis) throws IOException {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("stream interrupted", ex);
        }
    }
}

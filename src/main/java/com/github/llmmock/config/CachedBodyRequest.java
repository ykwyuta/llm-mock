package com.github.llmmock.config;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Buffers the request body so it can be read more than once.
 *
 * <p>Spring's {@code ContentCachingRequestWrapper} only records what someone else already
 * consumed, which is enough for logging but not for proxying: replay has to read the body
 * to look up a recording and then, on a miss, hand the very same body to the controller.
 */
public class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;
    private final boolean truncated;

    public CachedBodyRequest(HttpServletRequest request, int maxBytes) throws IOException {
        super(request);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream in = request.getInputStream()) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (maxBytes > 0 && buffer.size() + read > maxBytes) {
                    buffer.write(chunk, 0, Math.max(0, maxBytes - buffer.size()));
                    this.body = buffer.toByteArray();
                    this.truncated = true;
                    return;
                }
                buffer.write(chunk, 0, read);
            }
        }
        this.body = buffer.toByteArray();
        this.truncated = false;
    }

    public byte[] body() {
        return body;
    }

    public String bodyAsString() {
        return body.length == 0 ? null : new String(body, charset());
    }

    /** True when the body exceeded the configured cap and only a prefix was kept. */
    public boolean isTruncated() {
        return truncated;
    }

    private Charset charset() {
        String encoding = getCharacterEncoding();
        if (encoding == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (RuntimeException ex) {
            return StandardCharsets.UTF_8;
        }
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream source = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return source.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return source.read(b, off, len);
            }

            @Override
            public int available() {
                return source.available();
            }

            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException("This request is read synchronously");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), charset()));
    }

    /** Finds the wrapper in a chain of request wrappers, or null when there is none. */
    public static CachedBodyRequest find(ServletRequest request) {
        ServletRequest current = request;
        while (current != null) {
            if (current instanceof CachedBodyRequest cached) {
                return cached;
            }
            if (current instanceof ServletRequestWrapper wrapper) {
                current = wrapper.getRequest();
            } else {
                return null;
            }
        }
        return null;
    }
}

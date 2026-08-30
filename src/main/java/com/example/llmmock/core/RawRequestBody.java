package com.example.llmmock.core;

import java.nio.charset.StandardCharsets;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.ContentCachingRequestWrapper;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Reads back the bytes Spring already consumed for {@code @RequestBody}, so the engine can
 * record exactly what the application under test put on the wire.
 */
public final class RawRequestBody {

    private RawRequestBody() {
    }

    public static String current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }
        return of(servletAttributes.getRequest());
    }

    public static String of(HttpServletRequest request) {
        ContentCachingRequestWrapper wrapper = unwrap(request);
        if (wrapper == null) {
            return null;
        }
        byte[] bytes = wrapper.getContentAsByteArray();
        return bytes.length == 0 ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static ContentCachingRequestWrapper unwrap(HttpServletRequest request) {
        jakarta.servlet.ServletRequest current = request;
        while (current != null) {
            if (current instanceof ContentCachingRequestWrapper wrapper) {
                return wrapper;
            }
            if (current instanceof jakarta.servlet.ServletRequestWrapper wrapper) {
                current = wrapper.getRequest();
            } else {
                return null;
            }
        }
        return null;
    }
}

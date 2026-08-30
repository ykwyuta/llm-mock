package io.github.ykwyuta.llmmock.core;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.github.ykwyuta.llmmock.config.CachedBodyRequest;

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
        CachedBodyRequest cached = CachedBodyRequest.find(request);
        return cached == null ? null : cached.bodyAsString();
    }
}

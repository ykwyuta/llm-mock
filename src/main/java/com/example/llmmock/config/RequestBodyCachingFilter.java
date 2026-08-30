package com.example.llmmock.config;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Keeps a copy of each inbound body so it can be recorded after the controller has parsed
 * it. Only the request is buffered; responses stream through untouched, which keeps SSE
 * and the Bedrock event stream genuinely incremental.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestBodyCachingFilter extends OncePerRequestFilter {

    private final LlmMockProperties properties;

    public RequestBodyCachingFilter(LlmMockProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.getRecording().isCaptureRequestBody()) {
            return true;
        }
        String method = request.getMethod();
        return !"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        chain.doFilter(new ContentCachingRequestWrapper(request, properties.getRecording().getMaxBodyBytes()), response);
    }
}

package io.github.ykwyuta.llmmock.config;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Buffers each inbound body so it can be recorded after the controller parsed it, and so
 * the proxy filter downstream can read it without stealing it from the controller.
 *
 * <p>Only the request is buffered; responses stream through untouched, which keeps SSE and
 * the Bedrock event stream genuinely incremental.
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
        // Proxy and replay need the body regardless of whether recording is switched on.
        if (!properties.getRecording().isCaptureRequestBody() && !properties.anyNonMockMode()) {
            return true;
        }
        String method = request.getMethod();
        return !"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        chain.doFilter(new CachedBodyRequest(request, properties.getRecording().getMaxBodyBytes()),
                response);
    }
}

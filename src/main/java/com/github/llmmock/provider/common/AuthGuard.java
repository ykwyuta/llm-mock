package com.github.llmmock.provider.common;

import org.springframework.stereotype.Component;

import com.github.llmmock.config.LlmMockProperties;
import com.github.llmmock.core.MockApiException;
import com.github.llmmock.core.Provider;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Optional credential check. Off by default because a mock should be easy to point at;
 * turn {@code llm-mock.require-auth} on to test how the caller handles a 401.
 */
@Component
public class AuthGuard {

    private final LlmMockProperties properties;

    public AuthGuard(LlmMockProperties properties) {
        this.properties = properties;
    }

    public void check(Provider provider, HttpServletRequest request) {
        if (!properties.isRequireAuth()) {
            return;
        }
        boolean present = switch (provider) {
            case OPENAI -> hasBearer(request);
            case ANTHROPIC -> notBlank(request.getHeader("x-api-key")) || hasBearer(request);
            case GEMINI -> notBlank(request.getHeader("x-goog-api-key"))
                    || notBlank(request.getParameter("key")) || hasBearer(request);
            // Real Bedrock verifies a SigV4 signature; the mock only checks one is present.
            case BEDROCK -> notBlank(request.getHeader("Authorization"));
            case ANY -> true;
        };
        if (!present) {
            throw new MockApiException(401, "authentication", "Missing credentials for " + provider);
        }
    }

    private boolean hasBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                && header.length() > 7;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}

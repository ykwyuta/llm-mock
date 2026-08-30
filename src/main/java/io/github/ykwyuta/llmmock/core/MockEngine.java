package io.github.ykwyuta.llmmock.core;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ykwyuta.llmmock.config.LlmMockProperties;
import io.github.ykwyuta.llmmock.store.RequestRecorder;
import io.github.ykwyuta.llmmock.store.StubRule;
import io.github.ykwyuta.llmmock.store.StubRuleRepository;
import io.github.ykwyuta.llmmock.store.StubUsage;
import io.github.ykwyuta.llmmock.usage.TokenUsage;
import io.github.ykwyuta.llmmock.usage.UsageSource;
import io.github.ykwyuta.llmmock.usage.UsageTracker;

/**
 * Decides what the mock answers and records what it was asked.
 *
 * <p>Resolution order for every field of the answer is: {@code X-Mock-*} request header,
 * then the highest-priority matching {@link StubRule}, then the configured default. That
 * ordering is the whole contract: a suite can set broad rules once and still bend a single
 * call from inside one test.
 */
@Service
public class MockEngine {

    private static final Logger log = LoggerFactory.getLogger(MockEngine.class);

    private final StubRuleRepository stubs;
    private final StubUsage stubUsage;
    private final RequestRecorder recorder;
    private final TokenCounter tokenCounter;
    private final UsageTracker usageTracker;
    private final LlmMockProperties properties;

    public MockEngine(StubRuleRepository stubs, StubUsage stubUsage, RequestRecorder recorder,
                      TokenCounter tokenCounter, UsageTracker usageTracker,
                      LlmMockProperties properties) {
        this.stubs = stubs;
        this.stubUsage = stubUsage;
        this.recorder = recorder;
        this.tokenCounter = tokenCounter;
        this.usageTracker = usageTracker;
        this.properties = properties;
    }

    @Transactional
    public MockCompletion complete(MockRequest request, MockOverrides overrides) {
        MockOverrides effective = overrides == null ? MockOverrides.NONE : overrides;
        StubRule stub = resolveStub(request, effective);

        sleep(firstNonNull(effective.delayMs(), stub == null ? null : stub.getDelayMs()));

        Integer status = firstNonNull(effective.status(), stub == null ? null : stub.getHttpStatus());
        if (status != null && status >= 400) {
            String type = firstNonNull(effective.errorType(), stub == null ? null : stub.getErrorType(),
                    defaultErrorType(status));
            String message = firstNonNull(effective.errorMessage(),
                    stub == null ? null : stub.getErrorMessage(),
                    "Simulated " + status + " from llm-mock");
            consume(stub);
            record(request, status, stub, null, null, message);
            throw new MockApiException(status, type, message);
        }

        String text = firstNonNull(effective.text(), stub == null ? null : stub.getResponseText(),
                renderDefaultText(request));

        List<ToolCall> toolCalls = buildToolCalls(effective, stub);

        FinishReason finishReason = firstNonNull(effective.finishReason(),
                stub == null ? null : stub.getFinishReason(),
                toolCalls.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_USE);

        int inputTokens = firstNonNull(effective.inputTokens(), stub == null ? null : stub.getInputTokens(),
                tokenCounter.countRequest(request));
        int outputTokens = firstNonNull(effective.outputTokens(), stub == null ? null : stub.getOutputTokens(),
                tokenCounter.countText(text) + toolCalls.stream()
                        .mapToInt(call -> tokenCounter.countText(call.arguments())).sum());

        consume(stub);
        Usage usage = new Usage(inputTokens, outputTokens);
        record(request, 200, stub, inputTokens, outputTokens, text);
        // Tagged MOCK so a cost report can tell synthetic counts from real spend.
        usageTracker.record(request.provider(), request.model(), request.endpoint(),
                request.stream(), UsageSource.MOCK,
                TokenUsage.of(inputTokens, outputTokens));

        return new MockCompletion(Ids.hex(24), request.model(), text, toolCalls, finishReason, usage,
                stub == null ? null : stub.getName());
    }

    // --- stub resolution -------------------------------------------------------------

    private StubRule resolveStub(MockRequest request, MockOverrides overrides) {
        if (overrides.stubName() != null) {
            return stubs.findByName(overrides.stubName())
                    .orElseThrow(() -> MockApiException.notFound(
                            "No stub named '" + overrides.stubName() + "'"));
        }
        String conversation = request.conversationText();
        for (StubRule rule : stubs.findByEnabledTrueOrderByPriorityDescIdAsc()) {
            if (matches(rule, request, conversation)) {
                return rule;
            }
        }
        return null;
    }

    private boolean matches(StubRule rule, MockRequest request, String conversation) {
        if (rule.getRemainingUses() != null && rule.getRemainingUses() <= 0) {
            return false;
        }
        if (!rule.getProvider().matches(request.provider())) {
            return false;
        }
        return regexMatches(rule.getModelPattern(), request.model(), rule)
                && regexMatches(rule.getEndpointPattern(), request.endpoint(), rule)
                && regexMatches(rule.getPromptPattern(), conversation, rule);
    }

    /** A null pattern matches anything; otherwise the regex must be found in the value. */
    private boolean regexMatches(String pattern, String value, StubRule rule) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        try {
            return Pattern.compile(pattern, Pattern.DOTALL).matcher(value == null ? "" : value).find();
        } catch (PatternSyntaxException ex) {
            log.warn("Stub '{}' has an invalid regex '{}' and will not match: {}",
                    rule.getName(), pattern, ex.getMessage());
            return false;
        }
    }

    private void consume(StubRule rule) {
        if (rule != null && rule.getRemainingUses() != null) {
            // Committed separately so a simulated failure still burns the use.
            stubUsage.consume(rule.getId());
        }
    }

    // --- response construction -------------------------------------------------------

    private List<ToolCall> buildToolCalls(MockOverrides overrides, StubRule stub) {
        String name = firstNonNull(overrides.toolName(), stub == null ? null : stub.getToolName());
        if (name == null) {
            return List.of();
        }
        String arguments = firstNonNull(overrides.toolArguments(),
                stub == null ? null : stub.getToolArguments(), "{}");
        return List.of(new ToolCall(Ids.hex(24), name, arguments));
    }

    private String renderDefaultText(MockRequest request) {
        return properties.getDefaultResponseTemplate()
                .replace("{{prompt}}", nullToEmpty(request.lastUserText()))
                .replace("{{model}}", nullToEmpty(request.model()))
                .replace("{{provider}}", request.provider().name().toLowerCase())
                .replace("{{messageCount}}", String.valueOf(request.messages().size()));
    }

    private String defaultErrorType(int status) {
        return switch (status) {
            case 400 -> "invalid_request";
            case 401 -> "authentication";
            case 403 -> "permission";
            case 404 -> "not_found";
            case 408 -> "timeout";
            case 429 -> "rate_limit";
            case 503 -> "service_unavailable";
            default -> status >= 500 ? "server_error" : "invalid_request";
        };
    }

    // --- recording -------------------------------------------------------------------

    private void record(MockRequest request, int status, StubRule stub, Integer inputTokens,
                        Integer outputTokens, String responseText) {
        recorder.record(request.provider(), request.endpoint(), request.model(), request.stream(),
                status, stub == null ? null : stub.getName(), inputTokens, outputTokens, responseText);
    }

    /** Records a non-completion call (list models, count tokens, embeddings). */
    public void recordSimple(Provider provider, String endpoint, String model, int status,
                             String summary) {
        recorder.record(provider, endpoint, model, false, status, null, null, null, summary);
    }

    // --- helpers ---------------------------------------------------------------------

    private void sleep(Long delayMs) {
        if (delayMs == null || delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... candidates) {
        for (T candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public Optional<StubRule> stubByName(String name) {
        return stubs.findByName(name);
    }
}

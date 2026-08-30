package io.github.ykwyuta.llmmock.admin;

import java.time.Instant;

import io.github.ykwyuta.llmmock.core.FinishReason;
import io.github.ykwyuta.llmmock.core.Provider;
import io.github.ykwyuta.llmmock.store.StubRule;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** Wire shape of a stub rule on the admin API. */
public record StubRuleDto(
        Long id,
        @NotBlank String name,
        Provider provider,
        String modelPattern,
        String promptPattern,
        String endpointPattern,
        Integer priority,
        String responseText,
        FinishReason finishReason,
        String toolName,
        String toolArguments,
        @Min(0) Integer inputTokens,
        @Min(0) Integer outputTokens,
        @Min(100) @Max(599) Integer httpStatus,
        String errorType,
        String errorMessage,
        @Min(0) Long delayMs,
        @Min(0) Integer remainingUses,
        Boolean enabled,
        Instant createdAt) {

    public static StubRuleDto from(StubRule rule) {
        return new StubRuleDto(rule.getId(), rule.getName(), rule.getProvider(), rule.getModelPattern(),
                rule.getPromptPattern(), rule.getEndpointPattern(), rule.getPriority(),
                rule.getResponseText(), rule.getFinishReason(), rule.getToolName(),
                rule.getToolArguments(), rule.getInputTokens(), rule.getOutputTokens(),
                rule.getHttpStatus(), rule.getErrorType(), rule.getErrorMessage(), rule.getDelayMs(),
                rule.getRemainingUses(), rule.isEnabled(), rule.getCreatedAt());
    }

    /** Copies every settable field onto the entity. Null means "leave at the default". */
    public void applyTo(StubRule rule) {
        rule.setName(name);
        rule.setProvider(provider == null ? Provider.ANY : provider);
        rule.setModelPattern(modelPattern);
        rule.setPromptPattern(promptPattern);
        rule.setEndpointPattern(endpointPattern);
        rule.setPriority(priority == null ? 0 : priority);
        rule.setResponseText(responseText);
        rule.setFinishReason(finishReason);
        rule.setToolName(toolName);
        rule.setToolArguments(toolArguments);
        rule.setInputTokens(inputTokens);
        rule.setOutputTokens(outputTokens);
        rule.setHttpStatus(httpStatus);
        rule.setErrorType(errorType);
        rule.setErrorMessage(errorMessage);
        rule.setDelayMs(delayMs == null ? 0 : delayMs);
        rule.setRemainingUses(remainingUses);
        rule.setEnabled(enabled == null || enabled);
    }
}

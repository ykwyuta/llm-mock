package com.github.llmmock.store;

import java.time.Instant;

import com.github.llmmock.core.FinishReason;
import com.github.llmmock.core.Provider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * A stored rule describing how the mock should answer a matching request. Rules live in
 * H2 so they survive across requests within a test run and can be managed over the admin
 * API.
 *
 * <p>A rule matches when every non-null criterion matches. Among the matches the highest
 * {@code priority} wins, ties broken by the lowest id (oldest rule first).
 */
@Entity
@Table(name = "stub_rule")
public class StubRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Provider provider = Provider.ANY;

    /** Regex matched against the requested model id. Null matches any model. */
    private String modelPattern;

    /** Regex matched against the flattened conversation. Null matches any prompt. */
    @Lob
    private String promptPattern;

    /** Regex matched against the canonical endpoint name, e.g. {@code chat.completions}. */
    private String endpointPattern;

    @Column(nullable = false)
    private int priority = 0;

    @Lob
    private String responseText;

    @Enumerated(EnumType.STRING)
    private FinishReason finishReason;

    private String toolName;

    @Lob
    private String toolArguments;

    private Integer inputTokens;

    private Integer outputTokens;

    /** When set and >= 400 the rule makes the request fail with this status. */
    private Integer httpStatus;

    private String errorType;

    private String errorMessage;

    @Column(nullable = false)
    private long delayMs = 0;

    /** Null means the rule never expires; otherwise it is consumed once per match. */
    private Integer remainingUses;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public boolean isError() {
        return httpStatus != null && httpStatus >= 400;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider == null ? Provider.ANY : provider; }
    public String getModelPattern() { return modelPattern; }
    public void setModelPattern(String modelPattern) { this.modelPattern = modelPattern; }
    public String getPromptPattern() { return promptPattern; }
    public void setPromptPattern(String promptPattern) { this.promptPattern = promptPattern; }
    public String getEndpointPattern() { return endpointPattern; }
    public void setEndpointPattern(String endpointPattern) { this.endpointPattern = endpointPattern; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public String getResponseText() { return responseText; }
    public void setResponseText(String responseText) { this.responseText = responseText; }
    public FinishReason getFinishReason() { return finishReason; }
    public void setFinishReason(FinishReason finishReason) { this.finishReason = finishReason; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getToolArguments() { return toolArguments; }
    public void setToolArguments(String toolArguments) { this.toolArguments = toolArguments; }
    public Integer getInputTokens() { return inputTokens; }
    public void setInputTokens(Integer inputTokens) { this.inputTokens = inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Integer outputTokens) { this.outputTokens = outputTokens; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public long getDelayMs() { return delayMs; }
    public void setDelayMs(long delayMs) { this.delayMs = delayMs; }
    public Integer getRemainingUses() { return remainingUses; }
    public void setRemainingUses(Integer remainingUses) { this.remainingUses = remainingUses; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

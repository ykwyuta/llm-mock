package com.example.llmmock.admin;

import java.time.Instant;

import com.example.llmmock.core.Provider;
import com.example.llmmock.store.RequestLog;

public record RequestLogDto(
        Long id,
        Provider provider,
        String endpoint,
        String model,
        boolean streaming,
        int httpStatus,
        String matchedStub,
        Integer inputTokens,
        Integer outputTokens,
        String requestBody,
        String responseText,
        Instant createdAt) {

    public static RequestLogDto from(RequestLog entry) {
        return new RequestLogDto(entry.getId(), entry.getProvider(), entry.getEndpoint(),
                entry.getModel(), entry.isStreaming(), entry.getHttpStatus(), entry.getMatchedStub(),
                entry.getInputTokens(), entry.getOutputTokens(), entry.getRequestBody(),
                entry.getResponseText(), entry.getCreatedAt());
    }
}

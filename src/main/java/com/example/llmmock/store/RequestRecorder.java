package com.example.llmmock.store;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.llmmock.config.LlmMockProperties;
import com.example.llmmock.core.Provider;
import com.example.llmmock.core.RawRequestBody;

/**
 * Writes the request log.
 *
 * <p>Each write runs in its own transaction. A simulated failure aborts the caller's
 * transaction by design, and the record of that failure is exactly what a test wants to
 * inspect afterwards, so it must not be rolled back with it.
 */
@Component
public class RequestRecorder {

    private final RequestLogRepository logs;
    private final LlmMockProperties properties;

    public RequestRecorder(RequestLogRepository logs, LlmMockProperties properties) {
        this.logs = logs;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Provider provider, String endpoint, String model, boolean streaming,
                       int status, String matchedStub, Integer inputTokens, Integer outputTokens,
                       String responseText) {
        if (!properties.getRecording().isEnabled()) {
            return;
        }
        RequestLog entry = new RequestLog();
        entry.setProvider(provider);
        entry.setEndpoint(endpoint);
        entry.setModel(model);
        entry.setStreaming(streaming);
        entry.setHttpStatus(status);
        entry.setMatchedStub(matchedStub);
        entry.setInputTokens(inputTokens);
        entry.setOutputTokens(outputTokens);
        entry.setRequestBody(RawRequestBody.current());
        entry.setResponseText(responseText);
        logs.save(entry);
        prune();
    }

    /** Drops the oldest records so a long-running suite cannot exhaust the in-memory database. */
    private void prune() {
        int max = properties.getRecording().getMaxEntries();
        if (max <= 0) {
            return;
        }
        long count = logs.count();
        if (count <= max) {
            return;
        }
        logs.findAll(Sort.by("id").ascending()).stream()
                .limit(count - max)
                .forEach(logs::delete);
    }
}

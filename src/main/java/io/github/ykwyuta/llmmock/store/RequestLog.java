package io.github.ykwyuta.llmmock.store;

import java.time.Instant;

import io.github.ykwyuta.llmmock.core.Provider;

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
 * One recorded interaction. Tests use these to assert on what the application under test
 * actually sent (model, parameters, raw JSON body) rather than only on what came back.
 */
@Entity
@Table(name = "request_log")
public class RequestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Provider provider;

    @Column(nullable = false)
    private String endpoint;

    private String model;

    @Column(nullable = false)
    private boolean streaming;

    @Column(nullable = false)
    private int httpStatus;

    private String matchedStub;

    private Integer inputTokens;

    private Integer outputTokens;

    @Lob
    private String requestBody;

    @Lob
    private String responseText;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public boolean isStreaming() { return streaming; }
    public void setStreaming(boolean streaming) { this.streaming = streaming; }
    public int getHttpStatus() { return httpStatus; }
    public void setHttpStatus(int httpStatus) { this.httpStatus = httpStatus; }
    public String getMatchedStub() { return matchedStub; }
    public void setMatchedStub(String matchedStub) { this.matchedStub = matchedStub; }
    public Integer getInputTokens() { return inputTokens; }
    public void setInputTokens(Integer inputTokens) { this.inputTokens = inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Integer outputTokens) { this.outputTokens = outputTokens; }
    public String getRequestBody() { return requestBody; }
    public void setRequestBody(String requestBody) { this.requestBody = requestBody; }
    public String getResponseText() { return responseText; }
    public void setResponseText(String responseText) { this.responseText = responseText; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

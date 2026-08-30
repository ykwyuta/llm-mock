package com.example.llmmock.usage;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.llmmock.core.Provider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One call's token consumption, with the cost it was priced at. */
@Entity
@Table(name = "token_usage")
public class UsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant recordedAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Provider provider;

    private String model;

    private String endpoint;

    @Column(nullable = false)
    private boolean streaming;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UsageSource source;

    @Column(nullable = false)
    private int inputTokens;

    @Column(nullable = false)
    private int outputTokens;

    @Column(nullable = false)
    private int totalTokens;

    @Column(nullable = false)
    private int cacheReadTokens;

    @Column(nullable = false)
    private int cacheWriteTokens;

    /** Null when no price list entry matched the model. */
    @Column(precision = 20, scale = 10)
    private BigDecimal estimatedCost;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant v) { this.recordedAt = v; }
    public Provider getProvider() { return provider; }
    public void setProvider(Provider v) { this.provider = v; }
    public String getModel() { return model; }
    public void setModel(String v) { this.model = v; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String v) { this.endpoint = v; }
    public boolean isStreaming() { return streaming; }
    public void setStreaming(boolean v) { this.streaming = v; }
    public UsageSource getSource() { return source; }
    public void setSource(UsageSource v) { this.source = v; }
    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int v) { this.inputTokens = v; }
    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int v) { this.outputTokens = v; }
    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int v) { this.totalTokens = v; }
    public int getCacheReadTokens() { return cacheReadTokens; }
    public void setCacheReadTokens(int v) { this.cacheReadTokens = v; }
    public int getCacheWriteTokens() { return cacheWriteTokens; }
    public void setCacheWriteTokens(int v) { this.cacheWriteTokens = v; }
    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(BigDecimal v) { this.estimatedCost = v; }
}

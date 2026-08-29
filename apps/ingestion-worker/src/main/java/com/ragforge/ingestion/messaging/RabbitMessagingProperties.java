package com.ragforge.ingestion.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ragforge.messaging")
public class RabbitMessagingProperties {
    private String exchange = "ragforge.ingestion";
    private String requestedQueue = "ragforge.ingestion.jobs";
    private String retryQueue = "ragforge.ingestion.jobs.retry";
    private String deadLetterQueue = "ragforge.ingestion.jobs.dlq";
    private String statusQueue = "ragforge.ingestion.status";
    private String requestedRoutingKey = "ingestion.job.requested.v1";
    private String retryRoutingKey = "ingestion.job.retry.v1";
    private String deadLetterRoutingKey = "ingestion.job.dlq.v1";
    private String statusRoutingKey = "ingestion.job.status.changed.v1";
    private String sourceSyncQueue = "ragforge.ingestion.source-sync";
    private String sourceSyncRoutingKey = "source.sync.requested.v1";
    private int maxAttempts = 20;

    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }
    public String getRequestedQueue() { return requestedQueue; }
    public void setRequestedQueue(String requestedQueue) { this.requestedQueue = requestedQueue; }
    public String getRetryQueue() { return retryQueue; }
    public void setRetryQueue(String retryQueue) { this.retryQueue = retryQueue; }
    public String getDeadLetterQueue() { return deadLetterQueue; }
    public void setDeadLetterQueue(String deadLetterQueue) { this.deadLetterQueue = deadLetterQueue; }
    public String getStatusQueue() { return statusQueue; }
    public void setStatusQueue(String statusQueue) { this.statusQueue = statusQueue; }
    public String getRequestedRoutingKey() { return requestedRoutingKey; }
    public void setRequestedRoutingKey(String requestedRoutingKey) { this.requestedRoutingKey = requestedRoutingKey; }
    public String getRetryRoutingKey() { return retryRoutingKey; }
    public void setRetryRoutingKey(String retryRoutingKey) { this.retryRoutingKey = retryRoutingKey; }
    public String getDeadLetterRoutingKey() { return deadLetterRoutingKey; }
    public void setDeadLetterRoutingKey(String deadLetterRoutingKey) { this.deadLetterRoutingKey = deadLetterRoutingKey; }
    public String getStatusRoutingKey() { return statusRoutingKey; }
    public void setStatusRoutingKey(String statusRoutingKey) { this.statusRoutingKey = statusRoutingKey; }
    public String getSourceSyncQueue() { return sourceSyncQueue; }
    public void setSourceSyncQueue(String sourceSyncQueue) { this.sourceSyncQueue = sourceSyncQueue; }
    public String getSourceSyncRoutingKey() { return sourceSyncRoutingKey; }
    public void setSourceSyncRoutingKey(String sourceSyncRoutingKey) { this.sourceSyncRoutingKey = sourceSyncRoutingKey; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
}

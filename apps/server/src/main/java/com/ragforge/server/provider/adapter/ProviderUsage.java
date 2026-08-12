package com.ragforge.server.provider.adapter;

public record ProviderUsage(Long promptTokens, Long completionTokens, Long totalTokens, UsageSource source) {
    public ProviderUsage {
        if (promptTokens != null && promptTokens < 0
                || completionTokens != null && completionTokens < 0
                || totalTokens != null && totalTokens < 0) {
            throw new IllegalArgumentException("Provider usage cannot be negative");
        }
        if (promptTokens == null && completionTokens == null && totalTokens == null) {
            throw new IllegalArgumentException("Provider usage must contain a token count");
        }
        if (source == null) {
            throw new IllegalArgumentException("Provider usage source is required");
        }
    }
}

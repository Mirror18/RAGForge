package com.ragforge.server.provider.adapter;

import java.util.UUID;

public final class ProviderAdapterException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final ProviderErrorClass errorClass;
    private final UUID requestId;
    private final int providerStatus;
    private final boolean retryable;

    public ProviderAdapterException(ProviderErrorClass errorClass, String safeDetail) {
        this(errorClass, safeDetail, null, 0, retryable(errorClass));
    }

    public ProviderAdapterException(ProviderErrorClass errorClass, String safeDetail,
                                   UUID requestId, int providerStatus) {
        this(errorClass, safeDetail, requestId, providerStatus, retryable(errorClass));
    }

    public ProviderAdapterException(ProviderErrorClass errorClass, String safeDetail,
                                   UUID requestId, int providerStatus, boolean retryable) {
        super(safeDetail == null || safeDetail.isBlank() ? errorClass.name() : safeDetail);
        this.errorClass = errorClass;
        this.requestId = requestId;
        this.providerStatus = providerStatus;
        this.retryable = retryable;
    }

    public ProviderErrorClass errorClass() {
        return errorClass;
    }

    public UUID requestId() {
        return requestId;
    }

    public int providerStatus() {
        return providerStatus;
    }

    public boolean retryable() {
        return retryable;
    }

    private static boolean retryable(ProviderErrorClass errorClass) {
        return switch (errorClass) {
            case RATE_LIMIT, TIMEOUT, UNAVAILABLE -> true;
            default -> false;
        };
    }
}

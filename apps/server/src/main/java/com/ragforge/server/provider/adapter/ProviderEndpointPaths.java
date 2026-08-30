package com.ragforge.server.provider.adapter;

import java.net.URI;

final class ProviderEndpointPaths {
    private ProviderEndpointPaths() {
    }

    static URI ollamaChat(URI endpoint) {
        return append(endpoint, "/api/chat", "/api", "/api/chat");
    }

    static URI openAiChat(URI endpoint) {
        return append(endpoint, "/v1/chat/completions", "/v1", "/v1/chat/completions");
    }

    static URI ollamaEmbedding(URI endpoint) {
        return append(endpoint, "/api/embeddings", "/api", "/api/embeddings");
    }

    static URI openAiEmbedding(URI endpoint) {
        return append(endpoint, "/v1/embeddings", "/v1", "/v1/embeddings");
    }

    static URI aiRuntimeRerank(URI endpoint) {
        return append(endpoint, "/v1/rerank", "/v1", "/v1/rerank");
    }

    private static URI append(URI endpoint, String defaultPath, String basePath, String fullPath) {
        String current = endpoint.getPath() == null ? "" : endpoint.getPath();
        String path;
        if (current.endsWith(fullPath)) {
            path = current;
        } else if (current.endsWith(basePath)) {
            path = current + fullPath.substring(basePath.length());
        } else {
            path = trimTrailingSlash(current) + defaultPath;
        }
        try {
            return new URI(endpoint.getScheme(), endpoint.getAuthority(), path, null, null);
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalArgumentException("Provider endpoint path is invalid");
        }
    }

    private static String trimTrailingSlash(String path) {
        if (path.isEmpty() || "/".equals(path)) {
            return "";
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}

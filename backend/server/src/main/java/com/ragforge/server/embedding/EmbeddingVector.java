package com.ragforge.server.embedding;

import java.util.ArrayList;
import java.util.List;

/** Validated embedding value kept in the cache boundary. */
public record EmbeddingVector(int dimension, List<Double> values) {

    public EmbeddingVector {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive");
        }
        if (values == null || values.size() != dimension) {
            throw new IllegalArgumentException("embedding values must match dimension");
        }
        values = List.copyOf(new ArrayList<>(values));
        if (values.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("embedding values must be finite");
        }
    }
}

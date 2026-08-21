package com.ragforge.server.chunk;

import java.util.Objects;

/**
 * Immutable chunking configuration matching the Phase 4 chunking-domain
 * contract. Defaults are assumptions that must be validated by the phase
 * evaluation set, not hard-coded truth.
 */
public record ChunkingStrategy(
        String strategyVersion,
        int parentTargetTokens,
        int childTargetTokens,
        int overlapTokens,
        boolean boundaryAware,
        HeadingStrategy headingStrategy,
        TableStrategy tableStrategy,
        CodeStrategy codeStrategy,
        ListStrategy listStrategy) {

    public enum HeadingStrategy { HEADING_BOUNDARY }

    public enum TableStrategy { TABLE_PRESERVED, CELL_ROWS }

    public enum CodeStrategy { CODE_BLOCK_PRESERVED, LINE_BOUNDARY }

    public enum ListStrategy { LIST_BOUNDARY }

    /** Phase 4 default: parent 1200, child 400, overlap 40, boundary aware. */
    public static ChunkingStrategy p4DefaultV1() {
        return new ChunkingStrategy("p4-default-v1", 1200, 400, 40, true,
                HeadingStrategy.HEADING_BOUNDARY, TableStrategy.TABLE_PRESERVED,
                CodeStrategy.CODE_BLOCK_PRESERVED, ListStrategy.LIST_BOUNDARY);
    }

    public ChunkingStrategy {
        Objects.requireNonNull(strategyVersion, "strategyVersion");
        if (strategyVersion.isBlank()) {
            throw new IllegalArgumentException("strategyVersion must not be blank");
        }
        if (parentTargetTokens < 1000 || parentTargetTokens > 1500) {
            throw new IllegalArgumentException("parentTargetTokens must be within [1000, 1500]");
        }
        if (childTargetTokens < 300 || childTargetTokens > 500) {
            throw new IllegalArgumentException("childTargetTokens must be within [300, 500]");
        }
        if (overlapTokens < 0 || overlapTokens > 100) {
            throw new IllegalArgumentException("overlapTokens must be within [0, 100]");
        }
        if (!boundaryAware) {
            throw new IllegalArgumentException("boundaryAware must be true");
        }
        Objects.requireNonNull(headingStrategy, "headingStrategy");
        Objects.requireNonNull(tableStrategy, "tableStrategy");
        Objects.requireNonNull(codeStrategy, "codeStrategy");
        Objects.requireNonNull(listStrategy, "listStrategy");
        if (overlapTokens >= childTargetTokens) {
            throw new IllegalArgumentException("overlapTokens must be smaller than childTargetTokens");
        }
    }
}

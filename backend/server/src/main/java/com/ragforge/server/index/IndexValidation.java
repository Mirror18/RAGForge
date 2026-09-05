package com.ragforge.server.index;

import java.time.Instant;

/** Result of the VALIDATING gate for a candidate index version. */
public record IndexValidation(
        int documentCount,
        int childChunkCount,
        int vectorDimension,
        int orphanChildCount,
        boolean sampleRetrievalPassed,
        boolean spaceFilterPassed,
        Instant checkedAt) {
}

package com.ragforge.ingestion.connector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitRepositoryCheckoutTest {
    @Test
    void rejectsLocalRemotesBeforeStartingGit() {
        assertThatThrownBy(() -> GitRepositoryCheckout.checkout("file:///tmp/repository", "main"))
                .isInstanceOf(ConnectorException.class)
                .extracting("failure").isEqualTo(ConnectorFailure.PATH_INVALID);
    }

    @Test
    void rejectsBlankBranch() {
        assertThatThrownBy(() -> GitRepositoryCheckout.checkout("https://example.invalid/repository.git", " "))
                .isInstanceOf(ConnectorException.class)
                .extracting("failure").isEqualTo(ConnectorFailure.PATH_INVALID);
    }
}

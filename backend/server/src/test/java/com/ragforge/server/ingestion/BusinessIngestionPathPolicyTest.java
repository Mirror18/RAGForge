package com.ragforge.server.ingestion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessIngestionPathPolicyTest {
    @Test
    void preservesSafeFolderRelativeMarkdownPaths() {
        assertThat(BusinessIngestionService.safeName("01-Projects/ragforge/design.md"))
                .isEqualTo("01-Projects/ragforge/design.md");
        assertThat(BusinessIngestionService.safeName("01-Projects\\ragforge\\design.md"))
                .isEqualTo("01-Projects/ragforge/design.md");
    }

    @Test
    void rejectsPathTraversalAndAbsolutePaths() {
        assertThatThrownBy(() -> BusinessIngestionService.safeName("../secret.md"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> BusinessIngestionService.safeName("C:/Users/secret.md"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> BusinessIngestionService.safeName("/etc/secret.md"))
                .isInstanceOf(RuntimeException.class);
    }
}

package com.ragforge.server.ops;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OperationsPropertyPlaceholderTest {
    private static final String OPERATIONS_YAML = """
            ragforge:
              operations:
                enabled: "${RAGFORGE_OPERATIONS_ENABLED:${RAGFORGE_PHASE6_OPERATIONS_ENABLED:false}}"
                cleanup:
                  fixed-delay-ms: "${RAGFORGE_OPERATIONS_CLEANUP_DELAY_MS:${RAGFORGE_PHASE6_CLEANUP_DELAY_MS:3600000}}"
            """;

    @Test
    void quotedNestedPlaceholderFallsBackToLegacyPhase6Env() throws Exception {
        StandardEnvironment environment = environmentWith(
                Map.of("RAGFORGE_PHASE6_OPERATIONS_ENABLED", "true",
                        "RAGFORGE_PHASE6_CLEANUP_DELAY_MS", "15000"));
        environment.getPropertySources().addLast(loadYaml(OPERATIONS_YAML));

        assertThat(environment.getProperty("ragforge.operations.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("ragforge.operations.cleanup.fixed-delay-ms")).isEqualTo("15000");
    }

    @Test
    void canonicalOperationsEnvWinsOverLegacyPhase6() throws Exception {
        StandardEnvironment environment = environmentWith(
                Map.of("RAGFORGE_OPERATIONS_ENABLED", "false",
                        "RAGFORGE_PHASE6_OPERATIONS_ENABLED", "true",
                        "RAGFORGE_OPERATIONS_CLEANUP_DELAY_MS", "999",
                        "RAGFORGE_PHASE6_CLEANUP_DELAY_MS", "15000"));
        environment.getPropertySources().addLast(loadYaml(OPERATIONS_YAML));

        assertThat(environment.getProperty("ragforge.operations.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("ragforge.operations.cleanup.fixed-delay-ms")).isEqualTo("999");
    }

    @Test
    void missingBothEnvVarsUsesYamlDefaults() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(loadYaml(OPERATIONS_YAML));

        assertThat(environment.getProperty("ragforge.operations.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("ragforge.operations.cleanup.fixed-delay-ms")).isEqualTo("3600000");
    }

    private static StandardEnvironment environmentWith(Map<String, Object> values) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-ops-env", values));
        return environment;
    }

    private static PropertySource<?> loadYaml(String yaml) throws Exception {
        return new YamlPropertySourceLoader()
                .load("operations-yaml", new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)))
                .getFirst();
    }
}

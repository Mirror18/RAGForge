package com.ragforge.server.space.infrastructure;

import com.ragforge.server.shared.domain.DevPlatformAdminSeedProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import com.ragforge.server.space.application.PlatformAdminBootstrapService;

@Component
@Profile("dev")
@Order(50)
public class DevPlatformAdminSeedRunner implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(DevPlatformAdminSeedRunner.class);

    private final DevPlatformAdminSeedProperties properties;
    private final PlatformAdminBootstrapService bootstrap;

    public DevPlatformAdminSeedRunner(DevPlatformAdminSeedProperties properties,
                                      PlatformAdminBootstrapService bootstrap) {
        this.properties = properties;
        this.bootstrap = bootstrap;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isConfigured()) {
            return;
        }
        if (bootstrap.status().required()) {
            bootstrap.bootstrapFromDevSeed(properties.getEmail(), properties.getPassword(), properties.getDisplayName());
            LOG.warn("Dev profile seeded platform administrator {} (password from ragforge.bootstrap.admin.dev-seed)",
                    properties.getEmail());
        }
    }
}

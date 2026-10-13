package com.ragforge.server.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = PlatformAdminCreateCli.MODE_PROPERTY, havingValue = "create-platform-admin")
public class PlatformAdminCreatePlatformAdminCliRunner implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(PlatformAdminCreatePlatformAdminCliRunner.class);

    private final PlatformAdminBootstrapService bootstrap;
    private final org.springframework.core.env.Environment environment;

    public PlatformAdminCreatePlatformAdminCliRunner(PlatformAdminBootstrapService bootstrap,
                                                     org.springframework.core.env.Environment environment) {
        this.bootstrap = bootstrap;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = environment.getRequiredProperty(PlatformAdminCreateCli.EMAIL_PROPERTY);
        String displayName = environment.getRequiredProperty(PlatformAdminCreateCli.DISPLAY_NAME_PROPERTY);
        String password = environment.getProperty("ragforge.cli.admin-password", "");
        if (password == null || password.length() < 12) {
            password = PlatformAdminCreateCli.requiredPassword();
        }
        PlatformAdminBootstrapService.BootstrapResult result = bootstrap.bootstrapFromCli(email, password, displayName);
        LOG.info("Platform administrator {} ({}) created via CLI", result.user().email(), result.mode());
    }
}

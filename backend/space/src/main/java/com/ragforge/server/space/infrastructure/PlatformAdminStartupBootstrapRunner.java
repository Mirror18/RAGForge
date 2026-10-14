package com.ragforge.server.space.infrastructure;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import com.ragforge.server.space.application.PlatformAdminStartupTokenIssuer;

@Component
@Order(100)
public class PlatformAdminStartupBootstrapRunner implements ApplicationRunner {
    private final PlatformAdminStartupTokenIssuer issuer;

    public PlatformAdminStartupBootstrapRunner(PlatformAdminStartupTokenIssuer issuer) {
        this.issuer = issuer;
    }

    @Override
    public void run(ApplicationArguments args) {
        issuer.issueIfRequired();
    }
}

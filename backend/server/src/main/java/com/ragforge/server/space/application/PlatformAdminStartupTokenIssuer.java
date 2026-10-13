package com.ragforge.server.space.application;

import com.ragforge.server.shared.domain.BootstrapAdminProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import com.ragforge.server.space.infrastructure.PlatformAdminStartupTokenRepository;
import com.ragforge.server.space.infrastructure.UserRepository;

@Component
public class PlatformAdminStartupTokenIssuer {
    private static final Logger STARTUP_LOG = LoggerFactory.getLogger("ragforge.bootstrap.startup");

    private final UserRepository users;
    private final BootstrapAdminProperties properties;
    private final PlatformAdminStartupTokenRepository startupTokens;

    public PlatformAdminStartupTokenIssuer(UserRepository users, BootstrapAdminProperties properties,
                                           PlatformAdminStartupTokenRepository startupTokens) {
        this.users = users;
        this.properties = properties;
        this.startupTokens = startupTokens;
    }

    public void issueIfRequired() {
        if (!shouldIssue()) {
            return;
        }
        String plaintext = BootstrapTokenSupport.generatePlaintext();
        startupTokens.replace(BootstrapTokenSupport.hash(plaintext), Instant.now());
        STARTUP_LOG.warn("""
                
                ========================================================================
                RAGForge platform administrator bootstrap is required.
                Use this one-time startup code in the login UI (or HTTP header
                X-RAGForge-Bootstrap-Token). It is NOT stored in configuration and
                will change on the next restart until bootstrap completes.
                
                STARTUP_BOOTSTRAP_CODE={}
                ========================================================================
                """, plaintext);
    }

    boolean shouldIssue() {
        return properties.isStartupIssueEnabled()
                && !properties.isConfigured()
                && !users.hasPlatformAdmin();
    }
}

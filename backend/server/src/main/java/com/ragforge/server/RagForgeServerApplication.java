package com.ragforge.server;

import com.ragforge.server.identity.PlatformAdminCreateCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class RagForgeServerApplication {

    public static void main(String[] args) {
        if (PlatformAdminCreateCli.matches(args)) {
            int exitCode = SpringApplication.exit(new SpringApplicationBuilder(RagForgeServerApplication.class)
                    .web(WebApplicationType.NONE)
                    .properties(PlatformAdminCreateCli.propertiesFromArgs(args))
                    .properties("spring.main.banner-mode=off")
                    .run(PlatformAdminCreateCli.serverArgs(args)));
            System.exit(exitCode);
            return;
        }
        SpringApplication.run(RagForgeServerApplication.class, args);
    }
}

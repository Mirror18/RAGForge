package com.ragforge.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.context.TestPropertySource;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "RAGFORGE_GATEWAY_UPSTREAM=http://127.0.0.1:25082",
        "spring.main.web-application-type=reactive"
})
class GatewayRouteConfigurationTest {
    @Autowired
    private RouteLocator routeLocator;

    @Test
    void exposesApiRouteToUpstream() {
        StepVerifier.create(routeLocator.getRoutes().collectList())
                .assertNext(routes -> {
                    assertThat(routes).extracting(route -> route.getId())
                            .containsExactly("ragforge-api");
                })
                .verifyComplete();
    }
}

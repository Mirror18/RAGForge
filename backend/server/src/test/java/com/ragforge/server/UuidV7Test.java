package com.ragforge.server;

import com.ragforge.server.common.UuidV7;
import com.ragforge.server.common.CorrelationIdFilter;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {
    @Test
    void applicationGeneratesUuidV7() {
        UUID first = UuidV7.random();
        UUID second = UuidV7.random();

        assertThat(first.version()).isEqualTo(7);
        assertThat(first.variant()).isEqualTo(2);
        assertThat(second.version()).isEqualTo(7);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void correlationIdAcceptsOnlyUuidV7AndGeneratesUuidV7Otherwise() throws Exception {
        UUID uuidV7 = UuidV7.random();
        assertThat(UuidV7.isUuidV7(uuidV7.toString())).isTrue();
        assertThat(UuidV7.isUuidV7(UUID.randomUUID().toString())).isFalse();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, UUID.randomUUID().toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        new CorrelationIdFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).satisfies(value -> {
            assertThat(value).isNotNull();
            assertThat(UuidV7.isUuidV7(value.toString())).isTrue();
        });
    }
}

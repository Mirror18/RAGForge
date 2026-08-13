package com.ragforge.server.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditOutboxServiceTest {
    @Mock
    JdbcTemplate jdbc;

    @Mock
    ObjectMapper objectMapper;

    @Test
    void causationIsStoredAlongsideAuditAndOutboxEvent() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        AuditOutboxService service = new AuditOutboxService(jdbc, objectMapper);
        UUID causationId = UUID.randomUUID();

        service.record("ingestion.job.requested.v1", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), causationId, Map.of("jobId", UUID.randomUUID()));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(sql.capture(), any(Object[].class));
        List<String> statements = sql.getAllValues();
        assertThat(statements.get(0)).contains("audit_events");
        assertThat(statements.get(1)).contains("causation_id");
    }
}

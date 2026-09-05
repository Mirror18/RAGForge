package com.ragforge.server.ops;

import com.ragforge.server.answer.persistence.JdbcAnswerPersistence;
import com.ragforge.server.run.JdbcRunEventStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Phase6OperationsServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-22T00:00:00Z");
    private static final UUID SPACE_ID = UUID.randomUUID();

    @Test
    void cleanupPurgesAnswersAndDurableEventsWithSameOperatorTime() {
        JdbcAnswerPersistence answers = mock(JdbcAnswerPersistence.class);
        JdbcRunEventStore events = mock(JdbcRunEventStore.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(UUID.class))).thenReturn(List.of(SPACE_ID));
        Phase6OperationsService service = new Phase6OperationsService(answers, events, jdbc,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(answers.purgeExpired(SPACE_ID, NOW)).thenReturn(4);
        when(events.purgeExpired(SPACE_ID, NOW)).thenReturn(7);

        assertThat(service.purgeExpiredData()).isEqualTo(new Phase6OperationsService.CleanupResult(NOW, 4, 7));
        verify(answers).purgeExpired(SPACE_ID, NOW);
        verify(events).purgeExpired(SPACE_ID, NOW);
    }

    @Test
    void auditExportIsSpaceScopedAndOmitsPayloadBody() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Phase6OperationsService service = service(jdbc);
        UUID auditId = UUID.randomUUID();
        when(jdbc.query(anyString(), anyRowMapper(), any(Object[].class))).thenReturn(List.of(
                new Phase6OperationsService.AuditExportRow(auditId, "login", null, null, UUID.randomUUID(), NOW,
                        "a".repeat(64))));

        String csv = service.exportAuditCsv(SPACE_ID, NOW.minusSeconds(60), NOW.plusSeconds(60));

        assertThat(csv).contains("payload_sha256").contains(auditId.toString()).contains("a".repeat(64))
                .doesNotContain("raw-user-question");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), anyRowMapper(), any(Object[].class));
        assertThat(sql.getValue()).contains("WHERE space_id = ?").contains("digest(payload::text, 'sha256')");
    }

    @Test
    void usageCostReturnsAggregatedSpaceScopedRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Phase6OperationsService service = service(jdbc);
        Phase6OperationsService.UsageCostRow expected = new Phase6OperationsService.UsageCostRow(
                "LOCAL_ESTIMATE", "USD", 2, 10, 20, 30, new BigDecimal("0.12"));
        when(jdbc.query(anyString(), anyUsageRowMapper(), any(Object[].class))).thenReturn(List.of(expected));

        assertThat(service.usageCost(SPACE_ID, NOW.minusSeconds(60), NOW.plusSeconds(60))).containsExactly(expected);
    }

    @Test
    void rejectsNullSpaceAndEmptyWindows() {
        Phase6OperationsService service = service(mock(JdbcTemplate.class));

        assertThatThrownBy(() -> service.usageCost(null, NOW, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.exportAuditCsv(SPACE_ID, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Phase6OperationsService service(JdbcTemplate jdbc) {
        return new Phase6OperationsService(mock(JdbcAnswerPersistence.class), mock(JdbcRunEventStore.class), jdbc,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private static RowMapper<Phase6OperationsService.AuditExportRow> anyRowMapper() {
        return (RowMapper<Phase6OperationsService.AuditExportRow>) any(RowMapper.class);
    }

    @SuppressWarnings("unchecked")
    private static RowMapper<Phase6OperationsService.UsageCostRow> anyUsageRowMapper() {
        return (RowMapper<Phase6OperationsService.UsageCostRow>) any(RowMapper.class);
    }

    @Test
    void springCanConstructTheEnabledServiceWithProductionDependencies() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("phase6-test",
                    Map.of("ragforge.phase6.operations.enabled", "true")));
            context.registerBean(JdbcAnswerPersistence.class, () -> mock(JdbcAnswerPersistence.class));
            context.registerBean(JdbcRunEventStore.class, () -> mock(JdbcRunEventStore.class));
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.register(Phase6OperationsService.class);
            context.refresh();

            assertThat(context.getBean(Phase6OperationsService.class)).isNotNull();
        }
    }
}

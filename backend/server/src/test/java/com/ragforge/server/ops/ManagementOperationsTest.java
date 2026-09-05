package com.ragforge.server.ops;

import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.SpaceAuthorization;
import com.ragforge.server.space.SpaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.Authentication;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ManagementOperationsTest {
    private static final UUID SPACE_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final Instant FROM = Instant.parse("2026-08-29T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-30T00:00:00Z");

    @Test
    void unauthenticatedManagementReadIsRejected() {
        ManagementOperationsService operations = mock(ManagementOperationsService.class);
        ManagementController controller = new ManagementController(operations, mock(SpaceAuthorization.class));

        assertThatThrownBy(() -> controller.health(SPACE_ID, FROM, TO, null))
                .hasMessageContaining("A valid session is required");
        verifyNoInteractions(operations);
    }

    @Test
    void viewerManagementReadIsRejected() {
        SpaceAuthorization authorization = mock(SpaceAuthorization.class);
        ManagementOperationsService operations = mock(ManagementOperationsService.class);
        SessionPrincipal principal = principal("viewer@example.test");
        Authentication authentication = authentication(principal);
        when(authorization.requireMember(SPACE_ID, principal)).thenReturn(SpaceRole.VIEWER);

        assertThatThrownBy(() -> new ManagementController(operations, authorization)
                .health(SPACE_ID, FROM, TO, authentication))
                .hasMessageContaining("SPACE_ADMIN");
        verifyNoInteractions(operations);
    }

    @Test
    void spaceAdminManagementReadIsAllowed() {
        SpaceAuthorization authorization = mock(SpaceAuthorization.class);
        ManagementOperationsService operations = mock(ManagementOperationsService.class);
        SessionPrincipal principal = principal("admin@example.test");
        Authentication authentication = authentication(principal);
        when(authorization.requireMember(SPACE_ID, principal)).thenReturn(SpaceRole.SPACE_ADMIN);
        ManagementOperationsService.HealthAggregate expected = new ManagementOperationsService.HealthAggregate(
                SPACE_ID, FROM, TO, new ManagementOperationsService.ProviderHealth(1, 1, 0, 0),
                new ManagementOperationsService.RunHealth(2, 1, 1, 0));
        when(operations.aggregateHealth(SPACE_ID, FROM, TO)).thenReturn(expected);

        assertThat(new ManagementController(operations, authorization).health(SPACE_ID, FROM, TO, authentication))
                .isEqualTo(expected);
    }

    @Test
    void platformAdminManagementReadIsAllowedWithoutSpaceMembership() {
        SpaceAuthorization authorization = mock(SpaceAuthorization.class);
        ManagementOperationsService operations = mock(ManagementOperationsService.class);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                principal("platform@example.test"), "n/a", List.of(new SimpleGrantedAuthority("PLATFORM_ADMIN")));
        ManagementOperationsService.HealthAggregate expected = new ManagementOperationsService.HealthAggregate(
                SPACE_ID, FROM, TO, new ManagementOperationsService.ProviderHealth(0, 0, 0, 0),
                new ManagementOperationsService.RunHealth(0, 0, 0, 0));
        when(operations.aggregateHealth(SPACE_ID, FROM, TO)).thenReturn(expected);

        assertThat(new ManagementController(operations, authorization).health(SPACE_ID, FROM, TO, authentication))
                .isEqualTo(expected);
        verifyNoInteractions(authorization);
    }

    @Test
    void healthQueryIsSpaceAndWindowScoped() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ManagementOperationsService service = new ManagementOperationsService(jdbc);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(SPACE_ID)))
                .thenReturn(new ManagementOperationsService.ProviderHealth(3, 2, 1, 0));
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(SPACE_ID), any(), any()))
                .thenReturn(new ManagementOperationsService.RunHealth(4, 2, 1, 1));

        ManagementOperationsService.HealthAggregate result = service.aggregateHealth(SPACE_ID, FROM, TO);

        assertThat(result.providers().total()).isEqualTo(3);
        assertThat(result.runs().inFlight()).isEqualTo(1);
        verify(jdbc).queryForObject(org.mockito.ArgumentMatchers.contains("WHERE space_id = ?"),
                any(RowMapper.class), eq(SPACE_ID));
    }

    @Test
    void costQueryAggregatesStableUsageRowsWithoutPayload() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ManagementOperationsService service = new ManagementOperationsService(jdbc);
        ManagementOperationsService.UsageCostRow row = new ManagementOperationsService.UsageCostRow(
                "LOCAL_ESTIMATE", "USD", 2, 10, 20, 30, new BigDecimal("0.12"));
        doReturn(List.of(row)).when(jdbc).query(anyString(), any(RowMapper.class), eq(SPACE_ID), any(), any());

        assertThat(service.costUsage(SPACE_ID, FROM, TO).items()).containsExactly(row);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(SPACE_ID), any(), any());
        assertThat(sql.getValue()).contains("WHERE space_id = ?", "ORDER BY usage_source ASC, currency ASC");
    }

    @Test
    void feedbackQueryUsesStableCursorPaginationAndNoReason() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ManagementOperationsService service = new ManagementOperationsService(jdbc);
        UUID id = UUID.randomUUID();
        ManagementOperationsService.FeedbackItem item = new ManagementOperationsService.FeedbackItem(
                id, SPACE_ID, UUID.randomUUID(), UUID.randomUUID(), USER_ID, "HELPFUL", 0, FROM, FROM);
        doReturn(List.of(item)).when(jdbc).query(anyString(), any(RowMapper.class), eq(SPACE_ID), any(), any(), any());

        assertThat(service.listFeedback(SPACE_ID, FROM, TO, null, 20).items()).containsExactly(item);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(SPACE_ID), any(), any(), eq(21));
        assertThat(sql.getValue()).contains("WHERE space_id = ?", "ORDER BY created_at DESC, id DESC")
                .doesNotContain("reason");
    }

    @Test
    void auditExportIsRedactedAndPreservesCorrelationAndStableCursor() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ManagementOperationsService service = new ManagementOperationsService(jdbc);
        UUID id = UUID.randomUUID();
        ManagementOperationsService.AuditExportItem item = new ManagementOperationsService.AuditExportItem(
                id, "run.completed", USER_ID, UUID.randomUUID(), UUID.randomUUID(), FROM, "a".repeat(64));
        doReturn(List.of(item)).when(jdbc).query(anyString(), any(RowMapper.class), eq(SPACE_ID), any(), any(), any());

        assertThat(service.exportAudit(SPACE_ID, FROM, TO, null, 20).items()).containsExactly(item);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(SPACE_ID), any(), any(), eq(21));
        assertThat(sql.getValue()).contains("WHERE space_id = ?", "digest(payload::text, 'sha256')",
                        "ORDER BY occurred_at ASC, id ASC")
                .doesNotContain("payload,");
    }

    @Test
    void invalidManagementWindowAndLimitAreRejected() {
        ManagementOperationsService service = new ManagementOperationsService(mock(JdbcTemplate.class));

        assertThatThrownBy(() -> service.costUsage(SPACE_ID, TO, FROM))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.listFeedback(SPACE_ID, FROM, TO, null, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SessionPrincipal principal(String email) {
        return new SessionPrincipal(USER_ID, UUID.randomUUID(), email, "Management User", "csrf", "USER", Instant.MAX);
    }

    private static Authentication authentication(SessionPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, "n/a");
    }
}

package com.ragforge.server.space;

import com.ragforge.server.audit.AuditOutboxService;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.identity.UserAccount;
import com.ragforge.server.identity.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpaceServiceTest {
    private static final UUID SPACE_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");

    @Mock SpaceRepository spaces;
    @Mock UserRepository users;
    @Mock AuditOutboxService audit;

    @Test
    void addsOnlyKnownActiveUserByExactEmailAndRecordsIdentifierOnlyAudit() {
        when(spaces.findRole(SPACE_ID, ADMIN_ID)).thenReturn(Optional.of(SpaceRole.SPACE_ADMIN));
        when(spaces.findById(SPACE_ID)).thenReturn(Optional.of(activeSpace()));
        when(users.findByEmail("member@example.test")).thenReturn(Optional.of(activeUser()));
        when(spaces.findRole(SPACE_ID, TARGET_ID)).thenReturn(Optional.empty());

        SpaceMember result = service().addMember(admin(), SPACE_ID, " member@example.test ", SpaceRole.EDITOR,
                new MockHttpServletRequest());

        assertThat(result).isEqualTo(new SpaceMember(SPACE_ID, TARGET_ID, SpaceRole.EDITOR, 0));
        verify(spaces).addMembership(eq(SPACE_ID), eq(TARGET_ID), eq(SpaceRole.EDITOR), any(Instant.class));
        ArgumentCaptor<Map<String, ?>> payload = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("space.member.added.v1"), eq(ADMIN_ID), eq(SPACE_ID), eq(SPACE_ID), any(),
                payload.capture());
        assertThat(payload.getValue().get("spaceId")).isEqualTo(SPACE_ID);
        assertThat(payload.getValue().get("userId")).isEqualTo(TARGET_ID);
        assertThat(payload.getValue().get("role")).isEqualTo("EDITOR");
        assertThat(payload.getValue()).doesNotContainKey("email");
    }

    @Test
    void rejectsUnknownOrDisabledUserWithoutWritingMembership() {
        when(spaces.findRole(SPACE_ID, ADMIN_ID)).thenReturn(Optional.of(SpaceRole.SPACE_ADMIN));
        when(spaces.findById(SPACE_ID)).thenReturn(Optional.of(activeSpace()));
        when(users.findByEmail("disabled@example.test")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().addMember(admin(), SPACE_ID, "disabled@example.test", SpaceRole.VIEWER,
                new MockHttpServletRequest()))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(404);
                    assertThat(error.code()).isEqualTo("active_user_not_found");
                });

        verify(spaces, never()).addMembership(any(), any(), any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsExistingMemberInsteadOfSilentlyChangingRole() {
        when(spaces.findRole(SPACE_ID, ADMIN_ID)).thenReturn(Optional.of(SpaceRole.SPACE_ADMIN));
        when(spaces.findById(SPACE_ID)).thenReturn(Optional.of(activeSpace()));
        when(users.findByEmail("member@example.test")).thenReturn(Optional.of(activeUser()));
        when(spaces.findRole(SPACE_ID, TARGET_ID)).thenReturn(Optional.of(SpaceRole.VIEWER));

        assertThatThrownBy(() -> service().addMember(admin(), SPACE_ID, "member@example.test", SpaceRole.EDITOR,
                new MockHttpServletRequest()))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("space_member_already_exists");
                });

        verify(spaces, never()).addMembership(any(), any(), any(), any());
    }

    private SpaceService service() {
        return new SpaceService(spaces, users, audit);
    }

    private SessionPrincipal admin() {
        return new SessionPrincipal(ADMIN_ID, UUID.randomUUID(), "admin@example.test", "Admin", "csrf", "USER",
                Instant.MAX);
    }

    private KnowledgeSpace activeSpace() {
        return new KnowledgeSpace(SPACE_ID, "Space", null, "ACTIVE", null, NOW, NOW, 0);
    }

    private UserAccount activeUser() {
        return new UserAccount(TARGET_ID, "member@example.test", "Member", "hash", "USER", "ACTIVE", NOW, NOW);
    }
}

package com.example.cinema.user.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.cinema.common.error.ForbiddenException;
import com.example.cinema.common.error.ResourceNotFoundException;
import com.example.cinema.common.error.RoleConflictException;
import com.example.cinema.program.domain.ProgramRoleEntity;
import com.example.cinema.program.domain.ProgramRoleType;
import com.example.cinema.program.repository.ProgramRoleRepository;
import com.example.cinema.user.authentication.AuthenticatedUserIdentity;
import com.example.cinema.user.authentication.CurrentUser;

class ContextAwareAuthorizationServiceTest {

    private final UUID programId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final ProgramRoleRepository roles = mock(ProgramRoleRepository.class);
    private final ContextAwareAuthorizationService service = new ContextAwareAuthorizationService(currentUser, roles);

    @Test
    void resolvesProgramRoleAndRolePredicatesCentrally() {
        ProgramRoleEntity role = mock(ProgramRoleEntity.class);
        when(role.getRole()).thenReturn(ProgramRoleType.STAFF);
        when(roles.findRole(programId, userId)).thenReturn(Optional.of(role));

        assertThat(service.programRole(programId, userId)).contains(ProgramRoleType.STAFF);
        assertThat(service.isStaff(programId, userId)).isTrue();
        assertThat(service.isProgrammer(programId, userId)).isFalse();
    }

    @Test
    void rejectsWrongRequiredRoleAndMutuallyExclusiveAssignment() {
        when(currentUser.require()).thenReturn(new AuthenticatedUserIdentity(userId, "alice", "Alice"));
        ProgramRoleEntity role = mock(ProgramRoleEntity.class);
        when(role.getRole()).thenReturn(ProgramRoleType.STAFF);
        when(roles.findRole(programId, userId)).thenReturn(Optional.of(role));

        assertThatThrownBy(() -> service.requireProgrammer(programId)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.requireRoleAvailable(programId, userId, ProgramRoleType.SUBMITTER))
                .isInstanceOf(RoleConflictException.class);
    }

    @Test
    void concealedVisibilityUsesSafeNotFound() {
        assertThatThrownBy(() -> service.requireVisible(Optional.empty()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("The requested resource was not found.");
    }
}

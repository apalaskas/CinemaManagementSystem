package com.example.cinema.user.authorization;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.cinema.common.error.ForbiddenException;
import com.example.cinema.common.error.ResourceNotFoundException;
import com.example.cinema.common.error.RoleConflictException;
import com.example.cinema.program.domain.ProgramRoleType;
import com.example.cinema.program.repository.ProgramRoleRepository;
import com.example.cinema.screening.domain.ScreeningEntity;
import com.example.cinema.user.authentication.AuthenticatedUserIdentity;
import com.example.cinema.user.authentication.CurrentUser;

@Service
public class ContextAwareAuthorizationService {

    private final CurrentUser currentUser;
    private final ProgramRoleRepository programRoleRepository;

    public ContextAwareAuthorizationService(CurrentUser currentUser, ProgramRoleRepository programRoleRepository) {
        this.currentUser = currentUser;
        this.programRoleRepository = programRoleRepository;
    }

    public AuthenticatedUserIdentity currentUser() {
        return currentUser.require();
    }

    public Optional<ProgramRoleType> programRole(UUID programId, UUID userId) {
        return programRoleRepository.findRole(programId, userId).map(role -> role.getRole());
    }

    public boolean isProgrammer(UUID programId, UUID userId) {
        return hasRole(programId, userId, ProgramRoleType.PROGRAMMER);
    }

    public boolean isStaff(UUID programId, UUID userId) {
        return hasRole(programId, userId, ProgramRoleType.STAFF);
    }

    public boolean isSubmitter(UUID programId, UUID userId) {
        return hasRole(programId, userId, ProgramRoleType.SUBMITTER);
    }

    public boolean isScreeningOwner(ScreeningEntity screening, UUID userId) {
        return screening.getSubmitter().getId().equals(userId);
    }

    public boolean isScreeningHandler(ScreeningEntity screening, UUID userId) {
        return screening.getHandler() != null && screening.getHandler().getId().equals(userId);
    }

    public void requireProgrammer(UUID programId) {
        requireRole(programId, ProgramRoleType.PROGRAMMER);
    }

    public void requireStaff(UUID programId) {
        requireRole(programId, ProgramRoleType.STAFF);
    }

    public void requireSubmitter(UUID programId) {
        requireRole(programId, ProgramRoleType.SUBMITTER);
    }

    public void requireOwner(ScreeningEntity screening) {
        if (!isScreeningOwner(screening, currentUser().userId())) {
            throw new ForbiddenException();
        }
    }

    public void requireHandler(ScreeningEntity screening) {
        if (!isScreeningHandler(screening, currentUser().userId())) {
            throw new ForbiddenException();
        }
    }

    public void requireRoleAvailable(UUID programId, UUID userId, ProgramRoleType requestedRole) {
        programRole(programId, userId).ifPresent(existing -> {
            if (existing != requestedRole) {
                throw new RoleConflictException();
            }
        });
    }

    public <T> T requireVisible(Optional<T> visibleResource) {
        return visibleResource.orElseThrow(ResourceNotFoundException::new);
    }

    private boolean hasRole(UUID programId, UUID userId, ProgramRoleType role) {
        return programRole(programId, userId).filter(role::equals).isPresent();
    }

    private void requireRole(UUID programId, ProgramRoleType role) {
        if (!hasRole(programId, currentUser().userId(), role)) {
            throw new ForbiddenException();
        }
    }
}

package com.example.cinema.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.cinema.audit.domain.AuditLogEntity;
import com.example.cinema.audit.repository.AuditLogRepository;
import com.example.cinema.user.domain.UserEntity;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.ObjectMapper;

class AuditLoggingServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void removesSecretsRecursivelyFromSnapshots() {
        AuditSnapshotSanitizer sanitizer = new AuditSnapshotSanitizer(new ObjectMapper());

        String json = sanitizer.safeJson(Map.of(
                "name", "safe",
                "passwordHashOrExternalReference", "$2a$secret",
                "nested", Map.of("Authorization", "Basic secret", "value", 7)));

        assertThat(json).contains("safe", "value", "7")
                .doesNotContain("password", "$2a$secret", "Authorization", "Basic secret");
    }

    @Test
    void auditWriteRequiresCallerTransactionAndFailurePropagates() throws ReflectiveOperationException {
        Transactional annotation = AuditLoggingService.class.getMethod("recordUserAction",
                UUID.class, String.class, String.class, UUID.class, Map.class, Map.class, String.class)
                .getAnnotation(Transactional.class);
        assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);

        AuditLogRepository repository = mock(AuditLogRepository.class);
        EntityManager entityManager = mock(EntityManager.class);
        UUID actorId = UUID.randomUUID();
        when(entityManager.getReference(UserEntity.class, actorId)).thenReturn(mock(UserEntity.class));
        when(repository.save(any(AuditLogEntity.class))).thenThrow(new IllegalStateException("audit unavailable"));
        AuditLoggingService service = new AuditLoggingService(repository,
                new AuditSnapshotSanitizer(new ObjectMapper()), entityManager,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.recordUserAction(actorId, "PROGRAM_CREATED", "PROGRAM",
                UUID.randomUUID(), null, Map.of("name", "Festival"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
    }

    @Test
    void recordsActorSanitizedSnapshotsAndClockTimestamp() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        EntityManager entityManager = mock(EntityManager.class);
        UUID actorId = UUID.randomUUID();
        UserEntity actor = new UserEntity(actorId, "alice", "$2a$credential", "Alice");
        when(entityManager.getReference(UserEntity.class, actorId)).thenReturn(actor);
        when(repository.save(any(AuditLogEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AuditLoggingService service = new AuditLoggingService(repository,
                new AuditSnapshotSanitizer(new ObjectMapper()), entityManager, Clock.fixed(NOW, ZoneOffset.UTC));
        UUID targetId = UUID.randomUUID();

        service.recordUserAction(actorId, "PROGRAM_UPDATED", "PROGRAM", targetId,
                Map.of("name", "Before"),
                Map.of("name", "After", "authorization", "Basic credential"), "correction");

        ArgumentCaptor<AuditLogEntity> saved = ArgumentCaptor.forClass(AuditLogEntity.class);
        org.mockito.Mockito.verify(repository).save(saved.capture());
        AuditLogEntity entry = saved.getValue();
        assertThat(entry.getActor()).isSameAs(actor);
        assertThat(entry.getActionType()).isEqualTo("PROGRAM_UPDATED");
        assertThat(entry.getTargetEntityType()).isEqualTo("PROGRAM");
        assertThat(entry.getTargetEntityId()).isEqualTo(targetId);
        assertThat(entry.getOldValue()).contains("Before");
        assertThat(entry.getNewValue()).contains("After").doesNotContain("authorization", "credential");
        assertThat(entry.getReason()).isEqualTo("correction");
        assertThat(entry.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void systemActionIsTheExplicitNullableActorPath() throws ReflectiveOperationException {
        Transactional annotation = AuditLoggingService.class.getMethod("recordSystemAction",
                String.class, String.class, UUID.class, Map.class, Map.class, String.class)
                .getAnnotation(Transactional.class);
        assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
        AuditLogRepository repository = mock(AuditLogRepository.class);
        when(repository.save(any(AuditLogEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AuditLoggingService service = new AuditLoggingService(repository,
                new AuditSnapshotSanitizer(new ObjectMapper()), mock(EntityManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC));

        AuditLogEntity entry = service.recordSystemAction(
                "AUTOMATIC_REJECTION", "SCREENING", UUID.randomUUID(), null, Map.of("state", "REJECTED"),
                "Program entered DECISION");

        assertThat(entry.getActor()).isNull();
        assertThat(entry.getCreatedAt()).isEqualTo(NOW);
    }
}

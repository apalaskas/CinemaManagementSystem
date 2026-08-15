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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.cinema.audit.domain.AuditLogEntity;
import com.example.cinema.audit.repository.AuditLogRepository;
import com.example.cinema.user.domain.UserEntity;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.ObjectMapper;

class AuditLoggingServiceTest {

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
}

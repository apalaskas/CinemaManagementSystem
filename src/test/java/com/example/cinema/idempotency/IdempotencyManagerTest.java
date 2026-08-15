package com.example.cinema.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.cinema.common.config.CinemaProperties;
import com.example.cinema.common.error.ConflictException;
import com.example.cinema.common.error.InvalidInputException;
import com.example.cinema.user.authentication.AuthenticatedUserIdentity;
import com.example.cinema.user.authentication.CurrentUser;

import tools.jackson.databind.ObjectMapper;

class IdempotencyManagerTest {

    private final UUID userId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final IdempotencyRecordRepository repository = mock(IdempotencyRecordRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final IdempotencyHasher hasher = new IdempotencyHasher(new ObjectMapper());
    private final IdempotencyManager manager = new IdempotencyManager(
            repository, hasher, currentUser, Clock.fixed(now, ZoneOffset.UTC), properties());

    @BeforeEach
    void authenticate() {
        when(currentUser.require()).thenReturn(new AuthenticatedUserIdentity(userId, "alice", "Alice"));
    }

    @Test
    void replaysCompletedResponseWithoutInvokingMutation() {
        Map<String, Object> request = Map.of("name", "Festival");
        IdempotencyRecordEntity record = record(hasher.hash("PROGRAM.CREATE", request));
        record.complete(201, "{\"id\":\"one\"}");
        when(repository.findForUpdate(userId, "key-1")).thenReturn(Optional.of(record));
        AtomicBoolean invoked = new AtomicBoolean();

        IdempotencyResult result = manager.execute("PROGRAM.CREATE", "key-1", request, () -> {
            invoked.set(true);
            return new StoredCommandResponse(201, "different");
        });

        assertThat(result).isEqualTo(new IdempotencyResult(201, "{\"id\":\"one\"}", true));
        assertThat(invoked).isFalse();
    }

    @Test
    void storesOnlyTheSuccessfulResponseAfterExecutingANewCommand() {
        Map<String, Object> request = Map.of("name", "Festival");
        when(repository.findForUpdate(userId, "key-1")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        IdempotencyResult result = manager.execute("PROGRAM.CREATE", "key-1", request,
                () -> new StoredCommandResponse(201, "{\"id\":\"one\"}"));

        assertThat(result).isEqualTo(new IdempotencyResult(201, "{\"id\":\"one\"}", false));
        ArgumentCaptor<IdempotencyRecordEntity> completed = ArgumentCaptor.forClass(IdempotencyRecordEntity.class);
        verify(repository).save(completed.capture());
        assertThat(completed.getValue().getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(completed.getValue().getResponseStatus()).isEqualTo(201);
        assertThat(completed.getValue().getResponseBody()).isEqualTo("{\"id\":\"one\"}");
        assertThat(completed.getValue().getExpiresAt()).isEqualTo(now.plus(Duration.ofHours(24)));
    }

    @Test
    void rejectsSameKeyWithDifferentCanonicalPayload() {
        IdempotencyRecordEntity record = record(hasher.hash("PROGRAM.CREATE", Map.of("name", "First")));
        when(repository.findForUpdate(userId, "key-1")).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> manager.execute("PROGRAM.CREATE", "key-1", Map.of("name", "Second"),
                () -> new StoredCommandResponse(201, "{}")))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).errorCode())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void rejectsUnexpiredInProgressRecordAsRetryable() {
        Map<String, Object> request = Map.of("name", "Festival");
        when(repository.findForUpdate(userId, "key-1"))
                .thenReturn(Optional.of(record(hasher.hash("PROGRAM.CREATE", request))));

        assertThatThrownBy(() -> manager.execute("PROGRAM.CREATE", "key-1", request,
                () -> new StoredCommandResponse(201, "{}")))
                .isInstanceOfSatisfying(ConflictException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("IDEMPOTENCY_REQUEST_IN_PROGRESS");
                    assertThat(error.retryable()).isTrue();
                });
    }

    @Test
    void commandFailureDoesNotStoreACompletedResult() {
        when(repository.findForUpdate(userId, "key-1")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> manager.execute("PROGRAM.CREATE", "key-1", Map.of("name", "Festival"),
                () -> { throw new IllegalStateException("mutation failed"); }))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any(IdempotencyRecordEntity.class));
    }

    @Test
    void replacesAnExpiredClaimBeforeExecutingTheCommand() {
        IdempotencyRecordEntity expired = new IdempotencyRecordEntity(
                UUID.randomUUID(), userId, "PROGRAM.CREATE", "key-1",
                hasher.hash("PROGRAM.CREATE", Map.of("name", "Old")),
                now.minus(Duration.ofHours(25)), now);
        when(repository.findForUpdate(userId, "key-1")).thenReturn(Optional.of(expired));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        IdempotencyResult result = manager.execute("PROGRAM.CREATE", "key-1", Map.of("name", "New"),
                () -> new StoredCommandResponse(201, "{}"));

        assertThat(result.replayed()).isFalse();
        verify(repository).delete(expired);
        verify(repository).flush();
    }

    @Test
    void rejectsInvalidKeySyntaxBeforeCreatingAClaim() {
        assertThatThrownBy(() -> manager.execute("PROGRAM.CREATE", "contains a space", Map.of(),
                () -> new StoredCommandResponse(201, "{}")))
                .isInstanceOf(InvalidInputException.class)
                .extracting(error -> ((InvalidInputException) error).errorCode())
                .isEqualTo("INVALID_IDEMPOTENCY_KEY");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsReuseOfAUserKeyByAnotherOperation() {
        Map<String, Object> original = Map.of("name", "Festival");
        IdempotencyRecordEntity record = record(hasher.hash("PROGRAM.CREATE", original));
        when(repository.findForUpdate(userId, "key-1")).thenReturn(Optional.of(record));
        AtomicBoolean invoked = new AtomicBoolean();

        assertThatThrownBy(() -> manager.execute(
                "SCREENING.SUBMIT",
                "key-1",
                Map.of("screeningId", UUID.randomUUID()),
                () -> {
                    invoked.set(true);
                    return new StoredCommandResponse(200, "{}");
                }))
                .isInstanceOfSatisfying(ConflictException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(invoked).isFalse();
    }

    @Test
    void retryAfterRolledBackCommandFailureCanClaimAndCompleteTheKey() {
        when(repository.findForUpdate(userId, "key-1")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Map<String, Object> request = Map.of("screeningId", UUID.randomUUID());

        assertThatThrownBy(() -> manager.execute(
                "SCREENING.SUBMIT", "key-1", request,
                () -> { throw new IllegalStateException("simulated rollback"); }))
                .isInstanceOf(IllegalStateException.class);

        IdempotencyResult retry = manager.execute(
                "SCREENING.SUBMIT", "key-1", request,
                () -> new StoredCommandResponse(200, "{\"state\":\"SUBMITTED\"}"));

        assertThat(retry).isEqualTo(new IdempotencyResult(
                200, "{\"state\":\"SUBMITTED\"}", false));
    }

    @Test
    void canonicalHashDoesNotDependOnObjectFieldOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("b", 2);
        first.put("a", 1);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", 1);
        second.put("b", 2);

        assertThat(hasher.hash("PROGRAM.CREATE", first)).isEqualTo(hasher.hash("PROGRAM.CREATE", second));
    }

    @Test
    void cleanupUsesTheInjectedClock() {
        when(repository.deleteExpired(now)).thenReturn(3);

        assertThat(manager.cleanupExpiredRecords()).isEqualTo(3);
        verify(repository).deleteExpired(now);
    }

    private IdempotencyRecordEntity record(byte[] hash) {
        return new IdempotencyRecordEntity(
                UUID.randomUUID(), userId, "PROGRAM.CREATE", "key-1", hash, now, now.plus(Duration.ofHours(24)));
    }

    private static CinemaProperties properties() {
        var policy = new CinemaProperties.Policy(10, Duration.ofMinutes(1));
        return new CinemaProperties(new CinemaProperties.Pagination(20, 100),
                new CinemaProperties.RateLimit(policy, policy, policy, policy, 100, Duration.ofMinutes(5)),
                new CinemaProperties.Idempotency(Duration.ofHours(24)));
    }
}

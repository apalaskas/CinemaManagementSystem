package com.example.cinema.idempotency;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.cinema.common.config.CinemaProperties;
import com.example.cinema.common.error.IdempotencyConflictException;
import com.example.cinema.common.error.InvalidInputException;
import com.example.cinema.user.authentication.CurrentUser;

@Service
public class IdempotencyManager {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._~:/+=-]{1,255}");
    private static final Pattern OPERATION_PATTERN = Pattern.compile("[A-Z0-9_.:-]{1,100}");

    private final IdempotencyRecordRepository repository;
    private final IdempotencyHasher hasher;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final CinemaProperties properties;

    public IdempotencyManager(
            IdempotencyRecordRepository repository,
            IdempotencyHasher hasher,
            CurrentUser currentUser,
            Clock clock,
            CinemaProperties properties) {
        this.repository = repository;
        this.hasher = hasher;
        this.currentUser = currentUser;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional
    public IdempotencyResult execute(
            String operation,
            String idempotencyKey,
            Object canonicalRequestContent,
            Supplier<StoredCommandResponse> command) {
        UUID userId = currentUser.require().userId();
        validate(operation, idempotencyKey);
        byte[] requestHash = hasher.hash(operation, canonicalRequestContent);
        Instant now = clock.instant();

        IdempotencyRecordEntity existing = repository.findForUpdate(userId, idempotencyKey).orElse(null);
        if (existing != null && !now.isBefore(existing.getExpiresAt())) {
            repository.delete(existing);
            repository.flush();
            existing = null;
        }
        if (existing != null) {
            if (!existing.belongsToOperation(operation) || !existing.hasRequestHash(requestHash)) {
                throw new IdempotencyConflictException("IDEMPOTENCY_KEY_REUSED",
                        "The Idempotency-Key was already used for another request.", false);
            }
            if (existing.getStatus() == IdempotencyStatus.IN_PROGRESS) {
                throw new IdempotencyConflictException("IDEMPOTENCY_REQUEST_IN_PROGRESS",
                        "A request with this Idempotency-Key is already in progress.", true);
            }
            return new IdempotencyResult(existing.getResponseStatus(), existing.getResponseBody(), true);
        }

        IdempotencyRecordEntity record = new IdempotencyRecordEntity(
                UUID.randomUUID(), userId, operation, idempotencyKey, requestHash, now,
                now.plus(properties.idempotency().retention()));
        try {
            repository.saveAndFlush(record);
        } catch (DataIntegrityViolationException race) {
            throw new IdempotencyConflictException("IDEMPOTENCY_REQUEST_IN_PROGRESS",
                    "A request with this Idempotency-Key is already in progress.", true);
        }

        StoredCommandResponse response = command.get();
        record.complete(response.status(), response.body());
        repository.save(record);
        return new IdempotencyResult(response.status(), response.body(), false);
    }

    @Transactional
    public int cleanupExpiredRecords() {
        return repository.deleteExpired(clock.instant());
    }

    private static void validate(String operation, String key) {
        if (operation == null || !OPERATION_PATTERN.matcher(operation).matches()) {
            throw new InvalidInputException("INVALID_IDEMPOTENCY_OPERATION", "The idempotent operation is invalid.");
        }
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new InvalidInputException("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must contain 1 to 255 permitted ASCII characters.");
        }
    }
}

package com.example.cinema.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class IdempotencyMigrationContractTest {

    @Test
    void v2DefinesBoundedIdempotencyPersistenceWithUniquenessAndExpiryIndexes() throws IOException {
        String migration;
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/V2__create_idempotency_record.sql")) {
            if (input == null) {
                throw new AssertionError("V2 idempotency migration is missing");
            }
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration)
                .contains("CREATE TABLE idempotency_record")
                .contains("UNIQUE (user_id, operation, idempotency_key)")
                .contains("request_hash BINARY(32) NOT NULL")
                .contains("status IN ('IN_PROGRESS', 'COMPLETED')")
                .contains("idx_idempotency_expiry")
                .contains("idx_idempotency_status_expiry")
                .contains("ENGINE = InnoDB")
                .contains("COLLATE = utf8mb4_0900_ai_ci");
    }
}

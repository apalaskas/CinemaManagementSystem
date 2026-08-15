package com.example.cinema.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ProgramManagementMigrationContractTest {

    @Test
    void v1MakesProgramNamesCaseInsensitiveAndRaceSafe() throws IOException {
        String migration = migration();

        assertThat(tableDefinition(migration, "program", "program_role"))
                .contains("CONSTRAINT uk_program_name UNIQUE (name)")
                .contains("COLLATE = utf8mb4_0900_ai_ci")
                .contains("CONSTRAINT chk_program_date_range CHECK (end_date >= start_date)")
                .contains("CONSTRAINT chk_program_version CHECK (version >= 0)");
    }

    @Test
    void v1EnforcesOneRolePerUserAndCascadesOnlyProgramOwnedData() throws IOException {
        String migration = migration();
        String roles = tableDefinition(migration, "program_role", "screening");
        String screenings = tableDefinition(migration, "screening", "review");
        String reviews = tableDefinition(migration, "review", "audit_log");

        assertThat(roles)
                .contains("CONSTRAINT pk_program_role PRIMARY KEY (program_id, user_id)")
                .contains("REFERENCES program (program_id) ON DELETE CASCADE")
                .contains("REFERENCES cms_user (user_id)")
                .doesNotContain("REFERENCES cms_user (user_id) ON DELETE CASCADE");
        assertThat(screenings)
                .contains("REFERENCES program (program_id) ON DELETE CASCADE")
                .doesNotContain("REFERENCES cms_user (user_id) ON DELETE CASCADE");
        assertThat(reviews)
                .contains("REFERENCES screening (screening_id) ON DELETE CASCADE")
                .doesNotContain("REFERENCES cms_user (user_id) ON DELETE CASCADE");
    }

    @Test
    void v1PreservesAuditSnapshotsWithoutAProgramForeignKey() throws IOException {
        String audit = tableDefinition(migration(), "audit_log", null);

        assertThat(audit)
                .contains("target_entity_id BINARY(16) NOT NULL")
                .doesNotContain("REFERENCES program")
                .contains("REFERENCES cms_user (user_id) ON DELETE SET NULL");
    }

    private String migration() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/V1__create_domain_schema.sql")) {
            if (input == null) {
                throw new AssertionError("V1 domain migration is missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String tableDefinition(String migration, String table, String followingTable) {
        int start = migration.indexOf("CREATE TABLE " + table + " (");
        if (start < 0) {
            throw new AssertionError("Missing table " + table);
        }
        int end = followingTable == null
                ? migration.length()
                : migration.indexOf("CREATE TABLE " + followingTable + " (", start);
        if (end < 0) {
            throw new AssertionError("Missing following table " + followingTable);
        }
        return migration.substring(start, end);
    }
}

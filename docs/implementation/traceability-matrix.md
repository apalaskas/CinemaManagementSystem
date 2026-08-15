# Requirement and use-case traceability matrix

Initial baseline status: **PLANNED**  
This matrix is planning evidence only. No business feature existed when it was created.

Prompt 1 added the build, configuration, Flyway, JPA entity, repository, and database-independent domain-test foundation. All numbered requirements and use cases remain `PLANNED`: no business service, controller, authorization policy, idempotency workflow, or public API behavior has been implemented yet.

## Status rules

- `PLANNED`: mapped but no implementation claim.
- `IMPLEMENTED`: code and focused tests exist, but end-to-end verification may remain.
- `VERIFIED`: relevant automated tests have passed in the required profile and evidence is recorded.
- `BLOCKED`: use only with a concrete external blocker and explanation.

Every implementation task must update affected rows with actual class/test names and evidence. Never bulk-promote statuses based on intention.

## Numbered requirements

| ID | Scope / use case | Planned endpoint(s) | Planned component(s) | Planned test coverage | Status |
|---|---|---|---|---|---|
| FR-1 | Program creation; UC-P1 | `POST /api/v1/programs` | Program REST Controller; Program Service; Validation Service; Idempotency Manager; Audit Logging Service; Program/User-Role repositories | Program creation service unit tests; request/response MockMvc tests; MySQL IT for case-insensitive unique constraint, atomic Program/creator-role/audit/idempotency persistence | PLANNED |
| FR-2 | Program details, PROGRAMMER roles, deletion; UC-P2 | `PATCH /api/v1/programs/{programId}`; `DELETE /api/v1/programs/{programId}`; `POST /api/v1/programs/{programId}/roles`; `DELETE /api/v1/programs/{programId}/roles/{userId}` | Program Service; Context-Aware Authorization Service; Program/User-Role repositories; Audit Logging Service | Detail/role/delete service state tables; creator-removal denial; concurrency tests; controller authorization/error tests; MySQL role uniqueness IT | PLANNED |
| FR-3 | STAFF management/freeze; UC-P2 | `POST /api/v1/programs/{programId}/roles`; `DELETE /api/v1/programs/{programId}/roles/{userId}`; `POST /api/v1/programs/{programId}/transitions` | Program Service; Context-Aware Authorization Service; User and Role Repository; Audit Logging Service | CREATED-only STAFF mutation tests; role-conflict tests; freeze-on-SUBMISSION transaction IT | PLANNED |
| FR-4 | Strict Program lifecycle; UC-P3 | `POST /api/v1/programs/{programId}/transitions` | Program REST Controller; Program Service; Screening Service collaborator; Program/Screening repositories; Transaction Manager; Audit Logging Service | Full legal transition parameterized tests; skip/rollback/prerequisite/concurrency tests; automatic rejection/rollback MySQL IT | PLANNED |
| FR-5 | Screening draft creation/update; UC-S1 | `POST /api/v1/programs/{programId}/screenings`; `PATCH /api/v1/screenings/{screeningId}` | Screening REST Controller; Screening Service; Validation Service; Authorization Service; Idempotency/Audit services; Screening/User-Role repositories | Partial-valid-draft and owner update tests; programmer/staff exclusion; explicit-time validation; candidate-overbooking tests; controller DTO tests | PLANNED |
| FR-6 | Submission and withdrawal; UC-S1, UC-S2 | `POST /api/v1/screenings/{screeningId}/submit`; `DELETE /api/v1/screenings/{screeningId}` | Screening Service; Rate Limiter; Idempotency Manager; Authorization Service; Screening/Program repositories; Audit Logging Service | Completeness/state/ownership tests; CREATED and pre-ASSIGNMENT SUBMITTED withdrawal matrix; soft-delete query tests; idempotent replay and rollback IT | PLANNED |
| FR-7 | Handler assignment/review; UC-S3 | `POST /api/v1/screenings/{screeningId}/handler`; `POST /api/v1/screenings/{screeningId}/review` | Screening Service; Authorization Service; User and Role Repository; Screening Repository; Audit Logging Service | Frozen-STAFF/exactly-one handler tests; assigned-handler-only review; score boundary/comment tests; one-review unique constraint IT | PLANNED |
| FR-8 | Decision, final submission, scheduling, automatic rejection; UC-S4 and UC-P3 | `POST /api/v1/screenings/{screeningId}/decision`; `POST /api/v1/screenings/{screeningId}/final-submission`; `POST /api/v1/screenings/{screeningId}/schedule`; `POST /api/v1/programs/{programId}/transitions` | Screening Service; Schedule Conflict Checker; Program/Screening repositories; Transaction Manager; Audit Logging Service | PROGRAMMER decision/reason tests; owner final-submit/freeze tests; half-open adjacent/overlap tests; case-insensitive auditorium and concurrent scheduling MySQL IT; automatic rejection tests | PLANNED |
| FR-9 | Program search; UC-P4 | `GET /api/v1/programs`; `GET /api/v1/programs/{programId}` | Search and View REST Controller; Search and Visibility Service; Program Repository | Filter AND/case/trim/sort/page tests; anonymous vs managed visibility MockMvc tests; MySQL query/projection and stable pagination IT | PLANNED |
| FR-10 | Screening search; UC-S5 | `GET /api/v1/programs/{programId}/screenings`; `GET /api/v1/screenings/{screeningId}` | Search and View REST Controller; Search and Visibility Service; Screening Repository | All-word per-field search tests; filter AND semantics; general/timetable sort; page stability; MySQL execution IT | PLANNED |
| FR-11 | Redaction/visibility; UC-P4, UC-S5 | `GET /api/v1/programs`; `GET /api/v1/programs/{programId}`; `GET /api/v1/programs/{programId}/screenings`; `GET /api/v1/screenings/{screeningId}` | Search and Visibility Service; Context-Aware Authorization Service; query projections; Global Exception Handler | Role-by-field projection matrix; pre/post-decision Review redaction; concealed 404; soft-delete/public projection MockMvc and MySQL IT | PLANNED |
| NFR-1 | Performance; chiefly UC-P4/UC-S5 | Collection/detail GET endpoints | Search and Visibility Service; indexed repositories; Rate Limiter/observability | Representative MySQL data-set query-plan/index tests; pagination regression tests; documented 5-10 second acceptance check under defined load | PLANNED |
| NFR-2 | Reliability, safe errors, idempotency, transactions; all commands | All command endpoints; Global error surface | Global Exception Handler; Validation Service; Idempotency Manager/Repository; Transaction Manager; all application services | Problem response leak tests; duplicate/same-vs-different-hash tests; concurrent claim tests; transaction rollback/failure-injection MySQL IT; default database-independent unit/web suite | PLANNED |
| NFR-3 | Authentication, contextual authorization, rate limiting; all use cases | All endpoints | Rate Limiter; Authentication Adapter; Spring Security; Context-Aware Authorization Service | Basic/anonymous/invalid credential MockMvc tests; program-context role matrix; concealment tests; `429` tests; BCrypt adapter unit/IT | PLANNED |
| NFR-4 | Audit trail; all critical command use cases | All mutation endpoints | Audit Logging Service; Audit Log Repository; Program/Screening services | Audit field/action tests; system actor/reason tests; no-secret tests; same-transaction commit/rollback MySQL IT | PLANNED |

## Use cases

| ID | Canonical goal | Requirement(s) | Planned endpoint(s) | Planned component/test evidence | Status |
|---|---|---|---|---|---|
| UC-P1 | Create Program | FR-1; NFR-2/3/4 | `POST /api/v1/programs` | Program Controller/Service, auth, validation, idempotency, audit; AD-1 success/error/replay unit + MockMvc + MySQL IT scenarios | PLANNED |
| UC-P2 | Manage Program Details and Roles | FR-2, FR-3; NFR-2/3/4 | `PATCH /api/v1/programs/{programId}`; `DELETE /api/v1/programs/{programId}`; `POST /api/v1/programs/{programId}/roles`; `DELETE /api/v1/programs/{programId}/roles/{userId}` | Program Service and User/Role Repository; AD-2 branches, creator protection, STAFF freeze, transaction/audit tests | PLANNED |
| UC-P3 | Advance Program Lifecycle | FR-4, FR-8.5; NFR-2/3/4 | `POST /api/v1/programs/{programId}/transitions` | Locked Program Service transition orchestration; SD-1 legal/prerequisite/auto-reject/rollback/concurrency tests | PLANNED |
| UC-P4 | Search and View Programs | FR-9, FR-11; NFR-1/3 | `GET /api/v1/programs`; `GET /api/v1/programs/{programId}` | Search/Visibility Service and SQL projections; AD-4 filter/access/redaction/sort/page tests | PLANNED |
| UC-S1 | Prepare Screening | FR-5, FR-6.3; NFR-2/3/4 | `POST /api/v1/programs/{programId}/screenings`; `PATCH /api/v1/screenings/{screeningId}`; `DELETE /api/v1/screenings/{screeningId}` | Screening Service; AD-3 partial draft, role separation, validation, owner update/soft-withdraw and audit tests | PLANNED |
| UC-S2 | Submit Screening | FR-6.1/6.2; NFR-2/3/4 | `POST /api/v1/screenings/{screeningId}/submit` | Screening Service, Rate Limiter, Idempotency Manager; SD-2 rate/auth/replay/completeness/locking/rollback tests | PLANNED |
| UC-S3 | Assign and Review Screening | FR-7; NFR-2/3/4 | `POST /api/v1/screenings/{screeningId}/handler`; `POST /api/v1/screenings/{screeningId}/review` | Screening Service and role/review persistence; SD-3 phase/handler/score/single-review/transaction tests | PLANNED |
| UC-S4 | Decide and Finalize Screening | FR-8; NFR-2/3/4 | `POST /api/v1/screenings/{screeningId}/decision`; `POST /api/v1/screenings/{screeningId}/final-submission`; `POST /api/v1/screenings/{screeningId}/schedule` | Screening Service and Schedule Conflict Checker; SD-4 approve/reject/final-submit/auto-reject/conflict/locking tests | PLANNED |
| UC-S5 | Search and View Screenings | FR-10, FR-11; NFR-1/3 | `GET /api/v1/programs/{programId}/screenings`; `GET /api/v1/screenings/{screeningId}` | Search/Visibility Service and SQL projections; AD-5 filter/all-word/access/redaction/sort/page tests | PLANNED |

## Planned test naming and profile convention

Exact package/class names may follow the eventual code structure, but maintain these evidence categories:

- `*Test`: fast JUnit/Mockito service and domain tests in the default suite.
- `*WebTest`: MockMvc/Spring Security tests in the default suite without a real database.
- `*MySqlIT`: opt-in `mysql-it` profile tests against the separately installed local MySQL test schema.

Do not add H2, Testcontainers, or container-based fixtures to satisfy any row.

## Persistence-foundation evidence

| Foundation item | Implemented evidence | Verification | Status |
|---|---|---|---|
| Build/toolchain | `pom.xml`; Maven 3.9.16 wrapper; JDK/Maven Enforcer rules; Spring Boot 4.1.0 BOM and required dependencies | `mvnw test`; `mvnw -DskipTests package` | VERIFIED |
| Runtime configuration | `application.yml`; typed `CinemaProperties`; UTC `Clock`; local example; VS Code recommendations/settings/launch configuration | `CinemaPropertiesTest`; `TimeConfigurationTest` | VERIFIED |
| Six-relation physical schema | `database/create_database.sql`; `V1__create_domain_schema.sql` with InnoDB, `utf8mb4_0900_ai_ci`, constraints, foreign keys, and indexes | Source/schema review; real MySQL migration remains for the opt-in profile | IMPLEMENTED |
| JPA domain mapping | `UserEntity`, `ProgramEntity`, `ProgramRoleEntity`/`ProgramRoleId`, `ScreeningEntity`, `ReviewEntity`, `AuditLogEntity`; canonical enums; UUID binary mapping; Program/Screening versions | 20 database-independent tests covering states, identity, conversion, invariants, configuration, UTC clock, JPA mappings, lazy relationships, locking, and interval-query structure | VERIFIED |
| Repository foundation | Six aggregate/table repositories, including row-locking, role, active-screening, review-uniqueness, and half-open scheduling-conflict query methods | Compilation under `--release 26`; query/database behavior awaits `mysql-it` | IMPLEMENTED |
| Deferred infrastructure | `idempotency` package boundary only; no `idempotency_record` table or behavior before Prompt 2 | Schema and dependency inspection | PLANNED |

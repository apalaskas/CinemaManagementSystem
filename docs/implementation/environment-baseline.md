# Environment baseline and accepted architecture decision

Status: **ACCEPTED**  
Applies to: all implementation, build, test, database, and developer-environment work.

## Inspection baseline

At the architecture-decision inspection on 2026-08-14, the workspace contained only the 17 specification artifacts under `docs/specification`. There was no source code, build file, root guidance file, or existing implementation documentation. The supplied directory was not a Git worktree (`git status` reported no `.git` repository). This observation does not authorize a future agent to initialize, commit, push, or otherwise alter Git state.

At the completion audit on 2026-08-15, the workspace was a clean Git worktree on `main` before the audit documentation update. Its tracked project content consisted only of the same 17 immutable specification artifacts, `AGENTS.md`, and the four permanent implementation-guidance documents. No application source, build file, database migration, business test, or container artifact existed. This later observation records current evidence without changing the Git restrictions above.

## Required platform

- Java JDK **26**, without preview features. Compile with release 26 and do not use `--enable-preview`.
- Spring Boot **4.1.0**.
- Maven Wrapper **3.9.16**. Use `mvnw`/`mvnw.cmd` once the build is created; do not rely on a globally installed Maven.
- MySQL Community Server **8.4.x LTS**, installed natively on the host.
- Spring Data JPA with Hibernate, and Flyway for migrations.
- Test stack: JUnit 5, AssertJ, Mockito, Spring Security Test, and MockMvc.
- Recommended VS Code extensions:
  - `vscjava.vscode-java-pack`
  - `vmware.vscode-boot-dev-pack`

Use the MySQL JDBC driver version managed by the Spring Boot dependency-management BOM. Do not pin an unrelated connector version unless a verified compatibility problem is documented with its reproduction and resolution.

## Scope boundaries

This is a REST backend only. No frontend is in scope. Ticket sales, seat reservations, payments, attendee registration, public user registration, and password-management endpoints are out of scope.

Container tooling is prohibited: do not add a Dockerfile, Compose file, Docker command, Testcontainers dependency, container-based test setup, or container-oriented documentation. Do not use H2 or another database as a substitute for MySQL.

## Build and test profiles

The eventual build must maintain two deliberately different test paths:

- **Default automated suite:** database-independent unit and web-slice tests using JUnit 5, AssertJ, Mockito, Spring Security Test, and MockMvc. It must run without MySQL and without any container runtime.
- **Opt-in real-database suite:** integration tests enabled by an explicit Maven profile (canonical profile name: `mysql-it`) and backed by a separately installed local MySQL 8.4 test schema. The profile must not silently reuse a development or production schema. Credentials and schema names come from local configuration/environment, never from committed secrets.

The opt-in profile should bind integration tests separately from the fast default suite and fail clearly when its explicitly required test database configuration is absent. Tests must create fixture data transactionally or through migrations and clean it without dropping an uncontrolled schema.

## Implemented foundation (Prompt 1)

The initial build and persistence foundation uses the canonical identity `com.example:cinema-management-system`, with base package `com.example.cinema`; no earlier Java package identity existed to preserve. The Maven build now enforces JDK 26 and Maven 3.9.16, compiles with `--release 26`, and uses Spring Boot 4.1.0 dependency management. The committed wrapper is Maven 3.9.16.

On Windows only, `mvnw.cmd` detects a non-ASCII workspace path and temporarily maps that workspace to an unused drive letter for the Maven process, then removes the mapping. This works around path corruption observed in the JDK/Maven launcher while leaving project files in place; ASCII workspace paths and the Unix wrapper retain normal wrapper behavior.

The default test lifecycle remains database-independent. The `mysql-it` Maven profile reserves `*MySqlIT` for explicit real-MySQL verification through Failsafe; no such test is executed unless that profile is selected and a separately installed MySQL test schema is configured. Activating the profile requires the separate `MYSQL_TEST_DB_URL`, `MYSQL_TEST_DB_USERNAME`, and `MYSQL_TEST_DB_PASSWORD` environment variables and passes them to tests as the standard application database properties, preventing accidental fallback to the development schema. The application configuration reads `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`, uses `cinema_management` only as the default local schema name, and contains no committed credentials.

Flyway migration `V1__create_domain_schema.sql` creates the six conceptual relations only. Flyway migration `V2__create_idempotency_record.sql` adds the `idempotency_record` infrastructure table; Flyway schema history remains framework-owned infrastructure. The domain JPA model and repositories remain persistence scaffolding, not an implementation of the business use cases or REST endpoints.

## Implemented shared infrastructure (Prompt 2)

The standalone security adapter now authenticates normalized usernames against `cms_user` with BCrypt and stateless HTTP Basic. Credential hashes remain confined to the adapter and never enter the authenticated principal. Public Program/Screening GET routes can be anonymous; `/api/v1` mutations require authentication, with contextual role/ownership/handler authorization supplied by `ContextAwareAuthorizationService` for later business services.

The default database-independent suite covers the common ProblemDetail error contract, correlation-ID propagation, fixed-window rate limiting, idempotency claim/hash/replay behavior, and audit snapshot sanitization/transaction participation. Spring Boot 4's modular `spring-boot-starter-webmvc-test` is test-scoped because MockMvc web slices are no longer supplied transitively by the general test starter.

On JDK 26, Maven Surefire starts Mockito's inline mock maker through an explicit `-javaagent`. During `process-test-classes`, Maven Dependency Plugin resolves the Spring Boot-managed `mockito-core` version and copies it to the stable project-local path `target/test-agent/mockito-core-agent.jar`; Surefire uses that copy instead of a potentially non-ASCII user-profile/Maven-repository path. This is the documented Java 21+ explicit-agent approach and avoids unsupported runtime self-attachment. The configuration is machine-independent and Surefire prepends the late-evaluated `@{argLine}` value so existing or future test JVM arguments are retained; the project defines that property as empty by default.

Rate limits are configured under `cinema.rate-limit` for Program search, Screening search, creation, and Screening submission. The in-process map has an explicit maximum key count and idle-entry TTL. Anonymous callers are keyed by the servlet container's directly resolved remote address; untrusted forwarding headers are not accepted implicitly. Authenticated callers are keyed by domain user ID. This implementation is suitable only for the single-instance academic deployment. A distributed deployment must replace it with a shared limiter; Redis, containers, and other distributed infrastructure are intentionally absent.

Idempotency retention is configured by `cinema.idempotency.retention` (environment override `IDEMPOTENCY_RETENTION`, default 24 hours). Rate-limit capacities/windows, map bound, and entry TTL likewise have environment overrides in `application.yml`. No secret value is committed.

## Persistence baseline

Flyway owns schema creation and evolution. Configure:

```properties
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.properties.hibernate.jdbc.time_zone=UTC
```

Application and JDBC configuration must normalize timestamps to UTC. Local developer documentation may explain how to install and initialize native MySQL, but must not introduce a container path.

## MySQL physical design

- Storage engine: **InnoDB**.
- Character set: **utf8mb4**.
- Collation: **utf8mb4_0900_ai_ci**.
- Persist UUIDs as `BINARY(16)`; expose canonical lowercase-hyphenated UUID strings through REST DTOs.
- The JPA representation is `java.util.UUID` with an explicit binary JDBC mapping. `BINARY(16)` is used because it preserves the full 128-bit value in a compact, fixed-width, index-friendly representation; boundary DTOs must perform canonical string conversion.
- Store timestamps in UTC and normalize all timestamp request/response values to UTC. API timestamps use ISO 8601 with an offset; responses use `Z`.
- Creation, audit, deletion, final-submission, and Screening interval timestamps are represented as `Instant` in Java and as UTC-normalized `DATETIME(6)` in MySQL. MySQL `DATETIME` carries no zone, so the JDBC/Hibernate UTC setting and request/response normalization are mandatory parts of the mapping.
- Map conceptual `USER` to physical `cms_user` to avoid problematic SQL naming.
- Preserve six conceptual/domain relations: `cms_user`, `program`, `program_role`, `screening`, `review`, and `audit_log`.
- Add `idempotency_record` as a technical infrastructure table for the idempotency component and NFR-2.3. It is not a seventh conceptual domain entity. Flyway's schema-history table is infrastructure as well.
- Store optimistic-lock versions for Program and Screening and map them with `@Version`.
- Persist lifecycle values as native constrained string/ENUM-compatible values whose spelling exactly matches the canonical diagrams.
- Index foreign keys, lifecycle states, search predicates, final scheduled interval lookup, and stable pagination tie-breakers.
- Enforce simple row-local constraints in MySQL. Enforce cross-row and workflow invariants transactionally in services with appropriate locking.
- Program name uniqueness is case-insensitive and is guaranteed by the database collation plus a unique constraint. A pre-insert query may improve the message but is not the integrity mechanism.
- Candidate-auditorium overbooking is allowed. Conflict checks occur only against active `SCHEDULED` screenings using the final auditorium and final interval.

The technical `idempotency_record` uniquely identifies `(authenticated user, operation, Idempotency-Key)`, retains a SHA-256 request hash and the original successful HTTP status/body, and supports atomic claim/replay behavior. V2 stores UUIDs as `BINARY(16)`, the hash as `BINARY(32)`, case-sensitive ASCII operation/key values, constrained `IN_PROGRESS`/`COMPLETED` status, and indexed expiry. Never store credentials or unredacted security-sensitive data in it.

## Expected dependency direction

The component diagram is authoritative for boundaries:

```text
REST controllers
  -> authentication/context authorization
  -> application services
  -> validation, idempotency, audit, visibility, schedule-conflict collaborators
  -> Spring Data repositories / transaction manager
  -> MySQL
```

Controllers do not call repositories directly. Repositories do not contain workflow orchestration. Authentication is accessed through `AuthenticationAdapter`; the standalone academic deployment supplies a shared-database implementation using BCrypt password hashes and HTTP Basic authentication.

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
- Store timestamps in UTC and normalize all timestamp request/response values to UTC. API timestamps use ISO 8601 with an offset; responses use `Z`.
- Map conceptual `USER` to physical `cms_user` to avoid problematic SQL naming.
- Preserve six conceptual/domain relations: `cms_user`, `program`, `program_role`, `screening`, `review`, and `audit_log`.
- Add `idempotency_record` as a technical infrastructure table for the idempotency component and NFR-2.3. It is not a seventh conceptual domain entity. Flyway's schema-history table is infrastructure as well.
- Store optimistic-lock versions for Program and Screening and map them with `@Version`.
- Persist lifecycle values as native constrained string/ENUM-compatible values whose spelling exactly matches the canonical diagrams.
- Index foreign keys, lifecycle states, search predicates, final scheduled interval lookup, and stable pagination tie-breakers.
- Enforce simple row-local constraints in MySQL. Enforce cross-row and workflow invariants transactionally in services with appropriate locking.
- Program name uniqueness is case-insensitive and is guaranteed by the database collation plus a unique constraint. A pre-insert query may improve the message but is not the integrity mechanism.
- Candidate-auditorium overbooking is allowed. Conflict checks occur only against active `SCHEDULED` screenings using the final auditorium and final interval.

The technical `idempotency_record` must uniquely identify `(authenticated user, operation, Idempotency-Key)`, retain a request hash and the original successful HTTP response data, and support atomic claim/replay behavior. Never store credentials or unredacted security-sensitive data in it.

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

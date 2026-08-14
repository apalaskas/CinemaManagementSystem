# Cinema Management System implementation rules

This repository implements the backend described by the immutable sources under `docs/specification`. Preserve that directory name and every source file exactly. Do not rename it, move it, edit it, or create the misspelled `docs/specifiacation` alternative.

## Required reading and precedence

Before changing application code, database migrations, tests, build files, or public API documentation, read:

1. `docs/implementation/environment-baseline.md`
2. `docs/implementation/specification-resolution.md`
3. `docs/implementation/api-contract.md`
4. `docs/implementation/traceability-matrix.md`

Apply this precedence whenever sources differ:

1. Explicit technology constraints recorded in the environment baseline.
2. Refined numbered requirements in `docs/specification/01-system-requirements.docx`.
3. Second-part diagrams; sequence and activity diagrams define detailed flow and transaction boundaries.
4. `docs/specification/00-assignment.pdf` for scope and Third Project Part deliverables.
5. A clearly documented implementation assumption only when all higher-precedence sources are silent.

Do not reopen an accepted decision in the permanent guidance without a new explicit user instruction. If a genuinely new ambiguity is found, document the assumption and its rationale before implementing it.

## Architecture and implementation guardrails

- Build a backend-only REST API under `/api/v1`; no frontend belongs in this repository.
- Keep REST controllers thin. Controllers perform transport mapping and Jakarta validation, then delegate. Put transactions, authorization decisions, lifecycle rules, locking, audit logging, idempotency coordination, and workflow orchestration in application services.
- Respect the component boundaries in `specification-resolution.md`: controllers, security adapters/services, application services, infrastructure services, repositories, and persistence are separate concerns.
- Never expose JPA entities directly. Use explicit request and response DTOs and map role-appropriate projections/redacted responses.
- Apply Jakarta validation at the request boundary and repeat invariant enforcement in services where current database state is required.
- Enforce service-level, program-context authorization even when Spring Security has already authenticated the request.
- Use safe centralized errors only. Never expose stack traces, SQL, table names, entity internals, credentials, password hashes, or sensitive existence information.
- Use Spring transactions for every state-changing use case. Apply optimistic `@Version` checks and pessimistic locking where the permanent decisions require them.
- Write an audit entry in the same transaction as every critical mutation. Never audit password material.
- Enforce the canonical `Idempotency-Key` behavior for command endpoints identified in the API contract.
- Use MySQL-backed filtering, visibility predicates, sorting, and pagination. Never fetch an unrestricted result set and filter it in Java.
- Keep Flyway as the schema authority and Hibernate in `validate` mode.
- Add tests with every implementation: database-independent unit/web tests in the default suite and real-MySQL integration tests only in the opt-in profile.
- Never add Dockerfiles, Compose files, Docker commands, Testcontainers, container-oriented test setup/documentation, H2, or another substitute database.
- Never add ticket sales, seat reservations, payments, attendee registration, a public user-registration API, or password-management endpoints.

## Change discipline

- Preserve existing user work and inspect Git status before edits when a Git worktree exists.
- Do not commit, push, create or rewrite repositories/remotes, rewrite history, or invite users unless explicitly instructed.
- Treat `docs/implementation/api-contract.md` as the canonical public contract. Update it before or with any deliberate API change.
- Update `docs/implementation/traceability-matrix.md` whenever a requirement or use case is implemented or its endpoint/component/test mapping changes. Use evidence-based statuses only; do not mark work implemented or verified prematurely.
- Keep business feature implementation out of architecture/context-only tasks.

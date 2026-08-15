# Specification inventory and permanent resolution

Status: **CANONICAL IMPLEMENTATION GUIDANCE**  
Initial inspection completed: 2026-08-14

Completion audit revalidated: 2026-08-15 (all 17 artifacts reopened; all 9 PDF pages, all 12 rendered Word pages, and all 15 diagrams inspected)

This document records the reconciled meaning of the complete specification set. Later implementation work must apply these decisions and must not re-litigate the listed conflicts without an explicit new user instruction.

## Source precedence

When sources disagree, use this order:

1. The explicit technology constraints in `environment-baseline.md`.
2. The refined numbered requirements in `docs/specification/01-system-requirements.docx`.
3. The second-part diagrams, with sequence and activity diagrams defining detailed flow and transaction boundaries.
4. `docs/specification/00-assignment.pdf` for overall scope and Third Project Part deliverables.
5. A documented implementation assumption only where the sources remain silent.

Entity structure comes from the ER diagram and relational schema. Component boundaries come from the component diagram. Behavioral orchestration comes jointly from the activity and sequence diagrams. The five activity diagrams and four sequence diagrams collectively cover UC-P1 through UC-P4 and UC-S1 through UC-S5.

## Inspected source inventory

Every file below was opened and inspected, not inferred from its name.

| File | Kind | Material content used |
|---|---|---|
| `00-assignment.pdf` | 9-page PDF | Scope, roles, lifecycle narrative, entities, assumptions, and Third Project Part/report deliverables. Contains the superseded `FINAL_SUBMISSION` wording and the incorrect SUBMITTER approval sentence resolved below. |
| `01-system-requirements.docx` | 12 rendered pages, 249 paragraphs, 2 tables | Refined FR-1..FR-11 and NFR-1..NFR-4, role model, integrity rules, scope, rationales, and compliance checklist. |
| `02-activity-create-program.png` | AD-1 | Rate limit/authenticate/idempotency/validation flow; database-backed case-insensitive uniqueness; atomic creation, creator PROGRAMMER role, audit, stored idempotent response. |
| `03-activity-manage-program-details-and-roles.png` | AD-2 | PROGRAMMER authorization; detail update, PROGRAMMER management, STAFF-before-SUBMISSION rule, creator protection, CREATED-only deletion; atomic mutation and audit. |
| `04-activity-prepare-screening.png` | AD-3 | Partial CREATED draft, individually valid supplied values, candidate overbooking, owner-only update/withdraw, role separation, soft deletion, audit and transaction boundaries. |
| `05-activity-search-view-programs.png` | AD-4 | Program search/view, access-safe 404, database filtering/sorting/pagination, PROGRAMMER managed-program visibility, public ANNOUNCED projection. |
| `06-activity-search-view-screenings.png` | AD-5 | Screening search/view, all-word text matching, general/timetable sorts, database execution, role visibility and redaction, concealed resources. |
| `07-component-architecture.png` | Component diagram | Four layers and dependencies: REST/error handling; security; application/domain services; repositories/transactions; rate limiter, authentication adapter, idempotency and audit boundaries. |
| `08-system-context.png` | Context diagram | Actors and external shared user source, backend-only boundary, inputs/outputs and explicit out-of-scope functions. |
| `09-entity-relationship.png` | ER diagram | Six conceptual entities, attributes, cardinalities, optional participation, uniqueness, allowed state values, and business constraints. |
| `10-relational-schema.png` | Relational schema | Six relations, columns, PK/FK/UK/nullability, composite `PROGRAM_ROLE` key, nullable-until-stage Screening fields, and cross-row constraint notes. |
| `11-sequence-advance-program-lifecycle.png` | SD-1 | PROGRAMMER authorization, `SELECT ... FOR UPDATE`, exact next-state validation, transition side effects, atomic update/audit/rollback. |
| `12-sequence-submit-screening.png` | SD-2 | Rate limit, ownership/role, idempotency replay, locked screening, completeness validation, atomic SUBMITTED transition/audit/result storage. |
| `13-sequence-assign-review-screening.png` | SD-3 | Frozen-STAFF handler assignment during ASSIGNMENT; assigned-handler review during REVIEW; locking, single handler/review, atomic audit. |
| `14-sequence-decide-finalize-screening.png` | SD-4 | PROGRAMMER approve/reject during SCHEDULING, SUBMITTER final submission during FINAL_PUBLICATION, automatic rejection and conflict-safe final scheduling during DECISION. |
| `15-use-case-program-management.png` | Program use cases | UC-P1 Create Program, UC-P2 Manage Program Details and Roles, UC-P3 Advance Program Lifecycle, UC-P4 Search and View Programs. |
| `16-use-case-screening-management.png` | Screening use cases | UC-S1 Prepare Screening, UC-S2 Submit Screening, UC-S3 Assign and Review Screening, UC-S4 Decide and Finalize Screening, UC-S5 Search and View Screenings. |

No required source was missing or unreadable.

## Canonical requirement-to-use-case mapping

| Requirement | Canonical subject | Use case(s) |
|---|---|---|
| FR-1 | Program creation and initialization | UC-P1 |
| FR-2 | Program details and PROGRAMMER management | UC-P2 |
| FR-3 | STAFF management and freezing | UC-P2 |
| FR-4 | Strict Program lifecycle | UC-P3 |
| FR-5 | Screening draft creation and update | UC-S1 |
| FR-6 | Screening submission and withdrawal | UC-S1, UC-S2 |
| FR-7 | Handler assignment and review | UC-S3 |
| FR-8 | Approval/rejection, final submission, scheduling, automatic rejection | UC-S4; UC-P3 for transition-triggered automatic rejection |
| FR-9 | Program search | UC-P4 |
| FR-10 | Screening search | UC-S5 |
| FR-11 | Role-aware visibility and redaction | UC-P4, UC-S5 |
| NFR-1 | Performance | UC-P4, UC-S5, and shared infrastructure |
| NFR-2 | Safe errors, reliability, idempotency, transactions | All command use cases and shared infrastructure |
| NFR-3 | Authentication, contextual authorization, rate limiting | All use cases and shared infrastructure |
| NFR-4 | Audit trail | UC-P1, UC-P2, UC-P3, UC-S1, UC-S2, UC-S3, UC-S4 |

## Accepted ambiguity resolutions

These are closed decisions:

1. The canonical Program state is `FINAL_PUBLICATION`. `FINAL_SUBMISSION` in the original PDF is an older synonym only and must not appear in persisted or API state values.
2. A `PROGRAMMER`, not a `SUBMITTER`, approves or rejects screenings. The contrary PDF sentence is a typo contradicted by the refined requirements and diagrams.
3. Program states and only-forward transitions are strictly: `CREATED -> SUBMISSION -> ASSIGNMENT -> REVIEW -> SCHEDULING -> FINAL_PUBLICATION -> DECISION -> ANNOUNCED`. No skip or rollback is allowed.
4. Screening states are `CREATED`, `SUBMITTED`, `REVIEWED`, `APPROVED`, `SCHEDULED`, and `REJECTED`. `SCHEDULED` and `REJECTED` are final.
5. Screening creation produces an editable draft. Film, candidate-auditorium, and start/end data may be partial in `CREATED`, following AD-3 and relational nullability. Every supplied value must still be individually valid. Completeness is mandatory at submission.
6. Start and end times are explicitly supplied. Require `endTime > startTime` and `(endTime - startTime) >= durationMinutes`. Never silently calculate or overwrite an explicitly supplied end time.
7. An owner may withdraw an active `CREATED` Screening. They may also withdraw an active `SUBMITTED` Screening only while its Program remains in `SUBMISSION`, before `ASSIGNMENT`. Withdrawal is a soft delete via `deleted_at` and is excluded from active workflows/search.
8. The `STAFF` set may change only while the Program is `CREATED`; it freezes when the Program enters `SUBMISSION`.
9. The Program creator is permanently a `PROGRAMMER` of that Program and cannot be removed.
10. `PROGRAMMER`, `STAFF`, and `SUBMITTER` are mutually exclusive within one Program. A user has at most one program-specific role but may have different roles in different Programs.
11. A `PROGRAMMER` cannot create or submit a Screening in a Program they manage.
12. A `STAFF` user cannot become a `SUBMITTER` in the same Program. More generally, the single-role constraint prevents all conflicting combinations.
13. Every active submitted Screening has exactly one handler before the Program enters `REVIEW`. The handler must belong to that Program's frozen `STAFF` set.
14. A Review score is in the implementation range **0.00 through 10.00 inclusive** and detailed comments are nonblank. There is at most one Review per Screening.
15. A `SUBMITTER` may see their Review only after a `PROGRAMMER` has made an `APPROVE` or `REJECT` decision. Before that, review fields are redacted even from the owner.
16. Public Program auditorium information is derived from its active `SCHEDULED` Screenings; Program has no auditorium column.
17. Scheduling conflict intervals are half-open: `existingStart < requestedEnd AND existingEnd > requestedStart`. Adjacent intervals are allowed.
18. Conflict checks compare final auditorium case-insensitively and the final requested interval against active `SCHEDULED` screenings. Candidate auditorium conflicts are never checked.
19. Search filtering, access predicates, sorting, and pagination execute in MySQL. Loading an unrestricted set and filtering in Java is prohibited.
20. Text filters are trimmed and case-insensitive. Screening title/cast/genre filters split input into words; every entered word must occur within the corresponding field. Different filters combine with AND.
21. Every non-idempotent command endpoint requires `Idempotency-Key`. Reuse by the same user and operation with the same request hash replays the original successful response without a duplicate mutation. Same key/user/operation with different content returns `409`.
22. Authentication reads shared `cms_user` data through an `AuthenticationAdapter`. The standalone academic deployment uses BCrypt password hashes and HTTP Basic authentication. There are no user-registration or password-management endpoints.
23. Public search/view endpoints permit anonymous access. Mutation endpoints require authentication plus service-level program-context authorization.
24. REST errors use: `400` validation, `401` unauthenticated, `403` authenticated but forbidden, `404` missing or intentionally concealed, `409` lifecycle/concurrency/scheduling/idempotency conflict, and `429` rate limit.
25. Error responses never contain stack traces, SQL, table names, entity internals, password data, or sensitive resource-existence details.

## Canonical domain and physical model

The domain relations are exactly:

- `cms_user`: `user_id`, unique `username`, `password_hash_or_external_reference`, `full_name`.
- `program`: `program_id`, `creator_user_id`, unique case-insensitive `name`, `description`, `start_date`, `end_date`, `created_at`, `state`, `version`.
- `program_role`: composite key (`program_id`, `user_id`), `role`, `assigned_at`, nullable `assigned_by_user_id`.
- `screening`: `screening_id`, `program_id`, `submitter_user_id`, nullable `handler_user_id`, draft-capable film/candidate/time fields, nullable final auditorium, `state`, conditional notes, final-submission timestamp, rejection reason, creation/deletion timestamps, and `version`.
- `review`: `review_id`, unique `screening_id`, `staff_user_id`, numeric score, detailed comments, `created_at`.
- `audit_log`: `audit_id`, nullable actor, action type, target type/id, optional old/new values, optional reason, `created_at`.

`idempotency_record` and Flyway schema history are infrastructure, not domain entities. UUIDs are `BINARY(16)` physically and canonical strings at the API. All schema, collation, time, index, versioning, and migration decisions in `environment-baseline.md` are mandatory.

## Component allocation

- **Rate Limiter:** request-abuse controls, especially search and submission commands; produces `429`.
- **Program REST Controller:** Program command transport only.
- **Screening REST Controller:** Screening command transport only.
- **Search and View REST Controller:** Program/Screening query transport and role-appropriate DTO selection.
- **Global Exception Handler:** safe uniform error translation.
- **Authentication Adapter:** abstracts the shared user source; standalone adapter uses `cms_user` plus BCrypt/HTTP Basic.
- **Context-Aware Authorization Service:** resolves authenticated identity and one Program-specific role, ownership, assignment, and concealment decisions.
- **Program Service:** UC-P1, UC-P2, UC-P3 orchestration and transaction boundaries.
- **Screening Service:** UC-S1 through UC-S4 orchestration and transaction boundaries.
- **Search and Visibility Service:** UC-P4/UC-S5 SQL predicates, projections, redaction, sorting, and pagination.
- **Validation Service:** reusable row-independent input checks; stateful invariants remain in the owning application service transaction.
- **Idempotency Manager:** atomic request claim/hash/replay/storage.
- **Audit Logging Service:** same-transaction audit persistence.
- **Schedule Conflict Checker:** Screening Service collaborator defined by SD-4; performs locked/indexed final-interval conflict queries.
- **Repositories and Transaction Manager:** persistence only; expose required lock/query operations without moving workflow logic into repositories.

## Transaction and locking boundaries

- All command validation that depends on current state and all resulting writes occur in one service transaction.
- Lock the Program row for lifecycle transitions. Lock each Screening row for submission, handler assignment, review, decision, final submission, withdrawal, and scheduling as the sequence diagrams show.
- Scheduling must serialize conflict-sensitive work sufficiently to prevent two concurrent, conflicting intervals from both committing. Use an indexed query over active `SCHEDULED` rows and an appropriate MySQL locking strategy in the same transaction.
- Program transition side effects are atomic with the Program state change and audit records: STAFF freeze on `SUBMISSION`; automatic rejection of `APPROVED` but not finally submitted Screenings on entry to `DECISION`; publication lock on `ANNOUNCED`.
- A failure rolls back domain changes, audit entries, and any newly claimed successful idempotency result. Only committed successful responses are replayable.

## Documented implementation assumptions where sources are silent

- Dates use inclusive business bounds and require `endDate >= startDate`, as AD-1 states.
- Transition requests name the required next state rather than exposing unrelated action-specific endpoints.
- `PROGRAMMER` and `STAFF` may be assigned manually through the roles endpoint; `SUBMITTER` is created/verified automatically when a user creates their first Screening in a Program.
- Before `ASSIGNMENT -> REVIEW`, every active `SUBMITTED` Screening must have exactly one handler. Before `REVIEW -> SCHEDULING`, every such Screening must be `REVIEWED`. Before `SCHEDULING -> FINAL_PUBLICATION`, every reviewed Screening must have been decided `APPROVED` or `REJECTED`.
- Stable page ordering always ends with the entity UUID as a tie-breaker.
- API mutation concurrency uses an explicit expected version as defined in `api-contract.md`; stale versions return `409`.
- Audit old/new values store allowlisted business fields only, preferably structured JSON, and never store password material or raw authentication headers.
- Java domain identifiers use `UUID` and map directly to MySQL `BINARY(16)`; REST DTOs will expose only canonical UUID strings. This compact fixed-width physical representation avoids storing textual formatting and gives predictable index width without changing identifier semantics.
- Screening `start_time` and `end_time`, like all other timestamp columns, are persisted as microsecond-precision UTC `DATETIME(6)` values and represented as `Instant` in Java. API boundaries must accept offset-bearing values, normalize them to UTC, and emit `Z`; local wall-clock values without an offset are not a valid API representation.
- Physical delete actions preserve identity and audit history: required user references use `RESTRICT`, nullable assigning/actor references use `SET NULL`, and deleting a Program after business-layer authorization cascades its dependent roles and Screenings (and their Reviews). These foreign-key actions are integrity fallbacks, not substitutes for service authorization, audit logging, or lifecycle validation.
- Shared-database authentication normalizes a supplied username by trimming it and applying locale-independent lowercase before repository lookup. MySQL's accepted case-insensitive collation still defines username matching; the BCrypt hash never leaves the authentication adapter or enters the security principal.
- Accepted correlation IDs contain 1-100 ASCII letters, digits, `.`, `_`, or `-`; invalid/missing values are replaced by UUIDs. The effective ID is returned in `X-Correlation-ID`, placed in logging MDC for the request, and removed afterward.
- Idempotency keys use the restricted 1-255-character ASCII syntax recorded in `api-contract.md`. Operation identifiers are stable uppercase values up to 100 characters. SHA-256 covers operation plus recursively key-sorted canonical JSON content. Records expire after a configurable retention period, defaulting to 24 hours.
- The academic rate limiter is a bounded, idle-expiring, in-process fixed-window implementation. It trusts the servlet container's direct remote address, not arbitrary forwarding headers, for anonymous keys. A multi-instance deployment requires a shared limiter but does not justify Redis/container infrastructure in this project.
- Audit snapshots are JSON and recursively omit field names indicating passwords, Authorization, credentials, secrets, tokens, API keys, or idempotency keys. A nullable actor is available only through the explicit system-action method. Audit writes use mandatory transaction propagation so a critical mutation cannot silently commit after an audit failure.

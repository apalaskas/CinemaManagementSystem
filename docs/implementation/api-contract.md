# Canonical REST API contract

Status: **CANONICAL**  
Base path: `/api/v1`  
Media types: request/response JSON is `application/json`; errors are `application/problem+json`.

This is a backend contract only. JPA entities are never serialized. Every endpoint uses request/response DTOs and role-aware projections.

## Cross-cutting protocol

### Identity and authorization

- Program and Screening search/view endpoints allow anonymous requests.
- Commands require HTTP Basic authentication through the shared-database `AuthenticationAdapter` and then service-level authorization in the relevant Program context.
- Authentication failure is `401`. An authenticated caller lacking permission receives `403`, except where resource existence must be concealed, in which case return `404`.
- There are no registration, login-token issuance, password-reset, or password-management endpoints.

### UUIDs, dates, and times

- All IDs are canonical UUID strings (`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`) even though persistence uses `BINARY(16)`.
- Dates are ISO 8601 `YYYY-MM-DD`.
- Timestamp inputs must contain an offset. Normalize them to UTC and return ISO 8601 UTC timestamps ending in `Z`.

### Idempotency

`Idempotency-Key` is required on every non-idempotent command: all command `POST` endpoints and command `PATCH` endpoints below. Its accepted syntax is 1-255 ASCII characters from `A-Z`, `a-z`, `0-9`, `.`, `_`, `~`, `:`, `/`, `+`, `=`, and `-`. Never log the key or credentials.

The idempotency identity is `(authenticated user ID, key)`. A user cannot reuse an unexpired key for another canonical operation: operation mismatch is `409 IDEMPOTENCY_KEY_REUSED`. Canonical operation names are uppercase stable identifiers of at most 100 characters and remain part of the stored record and SHA-256 request hash. The caller-provided canonical content must include route identifiers and the expected concurrency version where they affect the mutation; it never includes credentials.

- Same identity, operation, and hash after a successful commit: replay the original status and response body; do not execute again.
- Same identity and different hash: `409 IDEMPOTENCY_KEY_REUSED`.
- A concurrent in-progress duplicate is resolved deterministically; it may wait briefly for the first transaction or return `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`, but must never double-execute.
- Failed/rolled-back operations are not stored as successful replay results.

Records expire according to `cinema.idempotency.retention` (24 hours by default). An expired identity may be claimed as new. Successful result storage and the domain mutation share the caller's transaction; authentication/authorization failures are not successful replay results.

`DELETE` is kept HTTP-idempotent and does not require this header: repeating the same authorized deletion/withdrawal must not create another mutation or audit event.

### Optimistic concurrency

Program and Screening representations include `version`. Commands that mutate an existing Program or Screening require:

```http
If-Match: "<current numeric version>"
```

This applies to PATCH, DELETE, roles, transitions, submission, handler assignment, review, decision, final submission, and scheduling. Missing/malformed `If-Match` is `400`; a stale version or an optimistic-lock race is `409 CONCURRENT_MODIFICATION`. Successful entity responses return the new `version` and matching `ETag`.

### Pagination and ordering

List endpoints accept `page` (zero-based, default `0`) and `size` (default `20`, maximum `100`). Invalid values return `400`. A page response has:

```json
{
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0,
  "content": []
}
```

Filtering, visibility predicates, sorting, count queries, and pagination run in MySQL. Every order ends in the entity UUID as a stable tie-breaker.

### Error shape

Use a safe Problem Details derivative:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "The screening conflicts with an existing scheduled interval.",
  "instance": "/api/v1/screenings/…/schedule",
  "errorCode": "SCHEDULING_CONFLICT",
  "timestamp": "2026-08-15T10:15:30Z",
  "fieldErrors": [
    { "field": "endTime", "message": "endTime must be after startTime" }
  ],
  "traceId": "safe-correlation-id"
}
```

Every error includes `status`, `title`, `detail`, `errorCode`, `timestamp`, `traceId`, and `instance`; omit `fieldErrors` when irrelevant. Safe status mapping is: `400` malformed/validation, `401` unauthenticated, `403` authenticated but forbidden, `404` missing or concealed, `409` lifecycle/concurrency/uniqueness/schedule/idempotency conflict, and `429` rate limit. A rate-limit response includes `Retry-After` in seconds and `retryable: true`. Never expose stack traces, exception class names, SQL, relation/column names, entity dumps, password data, Basic credentials, or sensitive existence information.

Clients may send `X-Correlation-ID` using 1-100 letters, digits, `.`, `_`, or `-`. Invalid/missing values are replaced with a generated UUID. Every response echoes the effective ID; server logs correlate internal failures with it without logging Authorization, password, or idempotency-key values.

## Shared DTO fields

### Program command fields

- `name`: nonblank, trimmed, case-insensitively unique under database collation.
- `description`: nonblank after trimming.
- `startDate`, `endDate`: required on creation; `endDate >= startDate`.

Program reads use two explicit allowlisted DTOs. `PublicProgramResponse` contains only `programId`, `name`, `description`, `startDate`, `endDate`, `programmerDisplayNames`, and distinct `finalAuditoriumNames` derived from active `SCHEDULED` Screenings. `FullProgramResponse` adds `state`, `createdAt`, `version`, creator information, role summaries, and a Screening summary with active/scheduled counts and the Program Screening collection URL. Command responses continue to use `ProgramDetailResponse`.

The Program command projection currently returned by create and update is:

```json
{
  "programId": "canonical-uuid",
  "name": "Spring Retrospective 2027",
  "description": "Curated seasonal programme",
  "startDate": "2027-03-01",
  "endDate": "2027-05-31",
  "state": "CREATED",
  "createdAt": "2026-08-15T10:15:30Z",
  "version": 0,
  "creator": {
    "userId": "canonical-uuid",
    "username": "normalized-username",
    "fullName": "Display Name"
  }
}
```

It is an explicit DTO and never contains credential data. `ProgramRoleResponse` contains `programId`, `userId`, `fullName`, `role`, `assignedAt`, `assignedByUserId`, and `programVersion`; `programVersion` is the value represented by the response `ETag`.

Public callers receive only `ANNOUNCED` Programs: ID, name, description, dates, programmer display names, and auditorium information derived from active `SCHEDULED` Screenings. A managing `PROGRAMMER` receives full fields for their Programs in any lifecycle state. Other relationships do not broaden Program visibility beyond the documented public projection.

### Screening draft/final fields

- `filmTitle`: nonblank when supplied; required at submission.
- `cast`: text, nonblank when supplied; required at submission.
- `genre`: nonblank when supplied; required at submission.
- `durationMinutes`: positive integer when supplied; required at submission.
- `candidateAuditoriumName`: nonblank when supplied; required at submission and only a preference.
- `startTime`, `endTime`: explicit timestamps. When both and duration are present, require `endTime > startTime` and elapsed minutes at least `durationMinutes`. Both are required at submission.

Partial draft updates never replace an explicitly supplied end time with a calculated value. `finalAuditorium` is separate and is set only by scheduling.

`ScreeningDetailResponse` contains the allowlisted owner fields `screeningId`, `programId`, `filmTitle`, `cast`, `genre`, `durationMinutes`, `candidateAuditoriumName`, `finalAuditoriumName`, `startTime`, `endTime`, `state`, `conditionalNotes`, `finalSubmittedAt`, `rejectionReason`, submitter/handler summaries, `createdAt`, and `version`. Review data will be added to the appropriate role-aware response when UC-S3/UC-S5 implements Review reads; a CREATED draft has no Review.

Visibility:

- Anonymous/ordinary authenticated caller: only active `SCHEDULED` Screenings in `ANNOUNCED` Programs; only ID, Program ID, film title, genre, scheduled start/end, and final auditorium.
- Managing `PROGRAMMER`: full fields for all active Screenings in that Program.
- Assigned `STAFF`: full fields only for assigned Screenings; public projection for others.
- Owning `SUBMITTER`: full fields for owned Screenings, except Review score/comments remain redacted until a PROGRAMMER decision sets `APPROVED` or `REJECTED`; public projection for others.
- Soft-deleted Screenings are absent from ordinary reads. Conceal with `404` unless a privileged audit use case is added by a future explicit contract change.

## Program endpoints

### POST `/api/v1/programs`

UC-P1 / FR-1. Authenticated user; `Idempotency-Key` required.

```json
{
  "name": "Spring Retrospective 2027",
  "description": "Curated seasonal programme",
  "startDate": "2027-03-01",
  "endDate": "2027-05-31"
}
```

Atomically create the Program in `CREATED`, assign requester as creator and permanent `PROGRAMMER`, audit, and store the successful idempotent result. Return `201 Created`, `Location`, `ETag`, and `ProgramDetailResponse`. Duplicate case-insensitive name is `409 PROGRAM_NAME_EXISTS`.

### PATCH `/api/v1/programs/{programId}`

UC-P2 / FR-2. Managing `PROGRAMMER`; `Idempotency-Key` and Program `If-Match` required.

Body contains at least one of `name`, `description`, `startDate`, `endDate`. Null is not a delete signal. Validate the complete resulting DTO. Changes are allowed before `ANNOUNCED`; `ANNOUNCED` is locked. Return `200` with updated details/ETag; uniqueness, lifecycle, or concurrency conflicts are `409`.

### DELETE `/api/v1/programs/{programId}`

UC-P2 / FR-2.4. Managing `PROGRAMMER`; Program `If-Match` required. Allowed only in `CREATED`. Audit atomically and return `204 No Content`. The migration-defined `ON DELETE CASCADE` removes dependent Program roles and Screenings (and their dependent Reviews), while shared `cms_user` rows and the Program audit snapshot remain. A later repeat does not create another mutation or audit entry and receives the same access-safe generic `404` used for an unavailable Program, without exposing prior private existence.

### POST `/api/v1/programs/{programId}/roles`

UC-P2 / FR-2.3, FR-3. Managing `PROGRAMMER`; `Idempotency-Key` and Program `If-Match` required.

```json
{ "userId": "canonical-uuid", "role": "PROGRAMMER" }
```

Manually accepted role values are `PROGRAMMER` and `STAFF`; `SUBMITTER` is assigned/verified through Screening creation. Target user must exist. Enforce one role per Program and creator protection. `STAFF` assignment is allowed only in `CREATED`; PROGRAMMER management remains allowed until `ANNOUNCED`. Return `201` with `ProgramRoleResponse` and updated Program ETag. Duplicate/conflicting role or frozen STAFF is `409`.

An exact duplicate assignment is `409 PROGRAM_ROLE_EXISTS`; assignment where the target already has a different `PROGRAMMER`, `STAFF`, or `SUBMITTER` role is `409 ROLE_CONFLICT`. An exact completed idempotency replay returns the stored `201` instead of re-running either check.

### DELETE `/api/v1/programs/{programId}/roles/{userId}`

UC-P2 / FR-2.3, FR-3. Managing `PROGRAMMER`; Program `If-Match` required. Remove a non-creator PROGRAMMER before `ANNOUNCED`, or STAFF only while `CREATED`. The creator cannot be removed. Return `204`; lifecycle/creator conflict is `409`. A missing managed assignment is the safe `404 PROGRAM_ROLE_NOT_FOUND`; a `SUBMITTER` assignment is intentionally treated the same way because this endpoint does not expose SUBMITTER removal.

### POST `/api/v1/programs/{programId}/transitions`

UC-P3 / FR-4. Managing `PROGRAMMER`; `Idempotency-Key` and Program `If-Match` required.

```json
{ "targetState": "SUBMISSION" }
```

Only the exact next state is accepted. Validate transition prerequisites under a Program lock and apply side effects atomically:

- Enter `SUBMISSION`: require at least one STAFF assignment, then freeze STAFF through the Program state; no copied snapshot is used.
- Enter `ASSIGNMENT`: close the submission/withdrawal window governed by Program state.
- Enter `REVIEW`: every active `SUBMITTED` Screening has exactly one handler who still belongs to the frozen STAFF set.
- Enter `SCHEDULING`: every active Screening that entered the review workflow is currently `REVIEWED` and has its Review row; untouched `CREATED` drafts are ignored.
- Enter `FINAL_PUBLICATION`: every active Screening that entered the review workflow is `APPROVED` or `REJECTED`; untouched `CREATED` drafts are ignored.
- Enter `DECISION`: automatically reject every active `APPROVED` Screening without `finalSubmittedAt`, with a system reason.
- Enter `ANNOUNCED`: require every active Screening that reached the decision workflow to be `SCHEDULED` or `REJECTED`, then lock Program content/schedule for public publication.

The Program row is selected `FOR UPDATE`; automatic-rejection candidates are also selected `FOR UPDATE`. Program state/version update, Screening side effects, one Program transition audit, one system audit per automatic rejection, and the stored idempotency result share one transaction. Failure rolls everything back.

Return `200`, an ETag matching the new Program version, and:

```json
{
  "programId": "canonical-uuid",
  "oldState": "FINAL_PUBLICATION",
  "newState": "DECISION",
  "version": 6,
  "transitionedAt": "2026-08-15T10:15:30Z",
  "automaticallyRejectedScreenings": 2
}
```

Skip, reverse, terminal-state, invalid-prerequisite, or concurrency conflict is a safe `409`.

### GET `/api/v1/programs`

UC-P4 / FR-9, FR-11. Anonymous allowed.

Optional parameters are `name`, `description`, `fromDate`, `toDate`, `filmTitle`, `auditorium`, `direction`, `page`, and `size`. Blank text filters are absent. `fromDate <= toDate` when both are supplied. Date filtering uses interval overlap: `program.startDate <= toDate` and `program.endDate >= fromDate`. Text searches are case-insensitive contains searches with literal `%`, `_`, and escape characters escaped before use in `LIKE`.

All filter categories combine with AND. `filmTitle` uses active Screening existence subqueries: anonymous/ordinary callers can match only public active `SCHEDULED` Screenings, while a managing PROGRAMMER can also match private active Screenings in that same managed Program. `auditorium` always matches only final auditorium data from active `SCHEDULED` Screenings; candidate auditorium data is not searchable here. Existence subqueries prevent duplicate Programs.

Accept `direction=ASC|DESC` (default `ASC`). Order Program start date, case-insensitive Program name, then Program UUID in that direction. `page` defaults to `0`; `size` defaults to the configured pagination default (`20`) and cannot exceed the configured maximum (`100`). Visibility, filters, ordering, count, and pagination execute in MySQL. Bounded batch projections avoid N+1 loading and `SearchAndVisibilityService` selects `PublicProgramResponse` or `FullProgramResponse` independently for each result. Return `PageResponse<ProgramViewResponse>` using `content` rather than Spring Data Page serialization. Program collection search uses the Program-search rate-limit group and returns safe `429` with `Retry-After` when exhausted.

### GET `/api/v1/programs/{programId}`

UC-P4 / FR-11. Anonymous allowed. Return `200` with `PublicProgramResponse` for an `ANNOUNCED` Program unless the caller is a PROGRAMMER of that exact Program, in which case return `FullProgramResponse` in any state. A non-ANNOUNCED Program not managed by the caller and a missing Program share the same safe `404 RESOURCE_NOT_FOUND` response.

## Screening endpoints

### POST `/api/v1/programs/{programId}/screenings`

UC-S1 / FR-5. Authenticated user; `Idempotency-Key` required.

Body may contain any subset of the Screening draft fields. Link to an existing Program and validate every supplied value. The requester must not be a PROGRAMMER or STAFF in that Program. Atomically create an active `CREATED` draft and create/verify the requester's `SUBMITTER` Program role. Candidate overbooking is allowed. Return `201`, `Location`, Screening `ETag`, and owner `ScreeningDetailResponse`.

```json
{
  "filmTitle": "A Film",
  "cast": "Lead One, Lead Two",
  "genre": "Drama",
  "durationMinutes": 120,
  "candidateAuditoriumName": "Auditorium A",
  "startTime": "2027-04-10T17:00:00Z",
  "endTime": "2027-04-10T19:00:00Z"
}
```

Every field is optional for a draft. Supplied strings are trimmed and nonblank, supplied duration is positive, both supplied times require `endTime > startTime`, and when both times plus duration are present the interval must cover the duration. Creation is available only while the Program is `CREATED` or `SUBMISSION`. The Program row is locked while phase and mutually exclusive role checks plus optional `SUBMITTER` assignment are performed.

### PATCH `/api/v1/screenings/{screeningId}`

UC-S1 / FR-5. Owning `SUBMITTER`; `Idempotency-Key` and Screening `If-Match` required. Allowed only for active `CREATED` drafts while the Program is `CREATED` or `SUBMISSION`. Body contains at least one draft field; null is not an implicit clear unless a future field-specific contract explicitly permits it. Only the seven creation fields above are editable; internal identity, ownership, workflow, handler, final scheduling, review, audit timestamp, deletion, and version fields are rejected at deserialization. Validate the complete resulting draft and return `200` plus updated ETag. The transaction resolves the active Screening's Program, locks that Program before loading and mutating the active draft, and rechecks ownership, `SUBMITTER` membership, current Program phase, Screening state, and optimistic version under that lifecycle-serialization lock. An exact completed idempotency replay returns the stored response without re-running resource/state checks or mutation.

### DELETE `/api/v1/screenings/{screeningId}`

UC-S1 / FR-6.3. Owning `SUBMITTER`; Screening `If-Match` required. Soft-delete when active and either:

- Screening is `CREATED`; or
- Screening is `SUBMITTED` and Program is still `SUBMISSION`.

Audit atomically and return `204`. The transaction first resolves the active Screening's Program, locks the Program, and then selects the active Screening with a pessimistic write lock, matching lifecycle lock order; the Screening still uses its optimistic version. Ownership, `SUBMITTER` membership, phase, state, and version are rechecked after those locks. After Program enters `ASSIGNMENT`, withdrawal is `409`; a repeated withdrawal receives the same generic safe `404` as another unavailable Screening and does not add another audit record.

### POST `/api/v1/screenings/{screeningId}/submit`

UC-S2 / FR-6. Owning `SUBMITTER`; `Idempotency-Key` and Screening `If-Match` required. Body is empty; any supplied body is `400 UNEXPECTED_REQUEST_BODY`.

Apply the Screening-submission rate-limit group before controller/service execution. Authenticate and verify active ownership plus the Program-specific `SUBMITTER` role before idempotency processing. An exact completed replay returns the stored response. For a new request, select the active Screening `FOR UPDATE`, load its Program in the same transaction, and recheck ownership, role, optimistic version, Screening `CREATED`, and Program `SUBMISSION`. Require nonblank `filmTitle`, `cast`, `genre`, and `candidateAuditoriumName`; positive `durationMinutes`; both times; `endTime > startTime`; and an interval at least as long as the duration. Completeness failures return `400 SCREENING_SUBMISSION_INVALID` with per-field errors. Candidate overbooking is allowed and no final-auditorium conflict query runs. Atomically freeze regular draft editing by setting `SUBMITTED`, flush the version, write a safe old/new Screening audit, store the successful `200` idempotency result, and commit. `finalSubmittedAt` remains null. Persistence/audit/idempotency failure rolls back the transition and claim.

### POST `/api/v1/screenings/{screeningId}/handler`

UC-S3 / FR-7. Managing `PROGRAMMER`; `Idempotency-Key` and Screening `If-Match` required.

```json
{ "staffUserId": "canonical-uuid" }
```

Authenticate and establish the active Screening's Program before idempotency processing, require the requester to be that Program's `PROGRAMMER`, and recheck authorization after taking the Program pessimistic-write lock. Require Program `ASSIGNMENT`, a registered target whose sole Program role is `STAFF` in the frozen set, and an active `SUBMITTED` Screening selected `FOR UPDATE` without a handler. The target therefore cannot simultaneously be `PROGRAMMER` or `SUBMITTER`. Check the Screening `If-Match` version, set exactly one handler without changing Screening state, flush the optimistic version, write a safe handler-assignment audit, store the successful idempotency result, and return `200` with `screeningId`, handler summary, `SUBMITTED` state, `version`, and updated ETag. Reassignment is not part of the canonical contract. Exact completed retries replay the stored result; a changed target, operation, or request hash returns `409`.

### POST `/api/v1/screenings/{screeningId}/review`

UC-S3 / FR-7. Assigned `STAFF` handler; `Idempotency-Key` and Screening `If-Match` required.

```json
{ "numericScore": 8.50, "detailedComments": "Detailed nonblank assessment." }
```

Authenticate and require both assigned-handler identity and that Program's `STAFF` role before idempotency processing, then recheck them after taking the Program and active Screening pessimistic-write locks. Require Program `REVIEW`, active `SUBMITTED`, `numericScore` in `0.00..10.00` inclusive with at most two fractional digits, trimmed nonblank `detailedComments` of at most 4,000 Unicode characters, and no existing Review. Atomically insert one Review, set Screening `REVIEWED`, flush the optimistic Screening version, write one safe audit, store the successful idempotency result, and return `201` with `reviewId`, `screeningId`, `REVIEWED` state, score, comments, reviewer display summary, `createdAt`, `screeningVersion`, and ETag. The Review `screening_id` unique constraint is the final concurrent-duplicate defense. Duplicate/concurrent attempts, stale versions, lifecycle conflicts, and lock failures return safe `409` responses; any insert, Screening update, audit, or idempotency failure rolls back the complete command.

### POST `/api/v1/screenings/{screeningId}/decision`

UC-S4 / FR-8. Managing `PROGRAMMER`; `Idempotency-Key` and Screening `If-Match` required.

Approve:

```json
{ "decision": "APPROVE", "conditionalNotes": "Optional requested final changes" }
```

Reject:

```json
{ "decision": "REJECT", "reason": "Required nonblank rejection reason" }
```

Require Program `SCHEDULING` and an active `REVIEWED` Screening. `APPROVE` sets `APPROVED` and stores trimmed optional `conditionalNotes`; `REJECT` requires a trimmed nonblank `reason`, records it, and sets final `REJECTED`. During Program `DECISION`, the only permitted decision is manual `REJECT` of an active `APPROVED` Screening whose `finalSubmittedAt` is present, again with a required nonblank reason; new approval is forbidden. Lock Program and Screening, recheck Program-specific `PROGRAMMER` authorization and `If-Match`, audit old/new state and decision data, store the idempotent result, and return `200` with `screeningId`, state, conditional notes/rejection reason, version, and ETag. `SCHEDULED` and `REJECTED` are immutable final states. A successful decision makes Review fields visible to the owner.

### POST `/api/v1/screenings/{screeningId}/final-submission`

UC-S4 / FR-8.3. Owning `SUBMITTER`; `Idempotency-Key` and Screening `If-Match` required.

Body may be empty to finalize the approved content unchanged, or may include only `filmTitle`, `cast`, `genre`, `durationMinutes`, `candidateAuditoriumName`, `startTime`, and `endTime`; final auditorium and all identity/state/role/review/audit fields are excluded. Require the authenticated owner and Program-specific `SUBMITTER` role before idempotency and after locking. Require Program `FINAL_PUBLICATION`, active `APPROVED`, no prior final submission, and complete valid resulting content: every text field nonblank, positive duration, both times, `endTime > startTime`, and interval at least the duration. Candidate conflicts are not checked. Atomically apply only supplied changes, set `finalSubmittedAt` from the UTC Clock, flush the optimistic version, audit the safe final snapshot/timestamp, store the idempotent result, and return `200` with the owner detail and ETag while state remains `APPROVED`. A non-replay second final submission returns `409`.

### POST `/api/v1/screenings/{screeningId}/schedule`

UC-S4 / FR-8.4. Managing `PROGRAMMER`; `Idempotency-Key` and Screening `If-Match` required.

```json
{
  "finalAuditoriumName": "Auditorium A",
  "startTime": "2027-04-10T17:00:00Z",
  "endTime": "2027-04-10T19:15:00Z"
}
```

Require Program-specific `PROGRAMMER`, Program `DECISION`, active `APPROVED`, nonnull `finalSubmittedAt`, a trimmed nonblank `finalAuditoriumName`, and explicit final times whose interval is positive and at least the persisted duration. The final auditorium may confirm or replace the candidate auditorium. Conflict query compares final auditorium case-insensitively across every other active `SCHEDULED` Screening using:

```text
existingStart < requestedEnd AND existingEnd > requestedStart
```

Candidate auditorium never participates. Scheduling runs in a MySQL `SERIALIZABLE` transaction and uses the indexed pessimistic-locking overlap query, including next-key/gap protection for an empty result, so cross-Program concurrent requests cannot both pass. No conflict atomically sets final auditorium/times and final state `SCHEDULED`, flushes the optimistic version, audits, stores the idempotent result, and returns `200` with safe final scheduling data plus ETag. Conflict returns `409 SCHEDULING_CONFLICT` without identifying the conflicting Screening. Exact retries replay; payload/key reuse mismatches return `409`.

### GET `/api/v1/programs/{programId}/screenings`

UC-S5 / FR-10, FR-11. Anonymous allowed.

Optional trimmed filters: `filmTitle`, `cast`, `genre`, `dateFrom`, `dateTo`; all supplied filters combine with AND. Each textual filter is case-insensitive, is split on whitespace after trimming, and requires every word to occur in that same field. Date bounds apply to `startTime`.

`view=general` (default) orders by genre, film title, UUID. `view=timetable` orders by start time, UUID. Apply relationship-aware visibility and redaction before pagination in MySQL and return a page of Screening summaries.

### GET `/api/v1/screenings/{screeningId}`

UC-S5 / FR-11. Anonymous allowed. Load Screening with Program and return the caller's role-appropriate detail. Missing, soft-deleted, nonpublic, or otherwise concealed resources return `404`.

## Rate-limit and audit expectations

Rate limiting is configuration-driven and covers Screening submission, Program/Screening creation, and both search collections. The filter executes before HTTP Basic authentication so rejected requests stop before credential verification; it uses an already-established trusted identity when available and otherwise keys traffic by the directly resolved client address. The single-instance fixed-window limiter expires idle keys and bounds its map. Return `429` with `Retry-After` and safe retry metadata; limits are not business constants in DTOs. Multi-instance deployment requires a shared limiter, which is outside this academic deployment.

The same transaction records critical create, delete/withdraw, role change, lifecycle/state change, handler assignment, review, decision, final submission, automatic rejection, and scheduling actions. Audit fields follow NFR-4: UTC timestamp, actor user ID when applicable, action type, target entity ID/type, allowlisted old/new values where useful, and rejection reason. System-triggered automatic rejection may have a null actor plus an explicit system action type/reason.

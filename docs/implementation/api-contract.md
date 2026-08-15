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

The idempotency identity is `(authenticated user ID, canonical operation, key)`. Canonical operation names are uppercase stable identifiers of at most 100 characters. `IdempotencyManager` hashes the operation plus canonical request content with SHA-256. The caller-provided canonical content must include route identifiers and the expected concurrency version where they affect the mutation; it never includes credentials.

- Same identity and same hash after a successful commit: replay the original status and response body; do not execute again.
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
  "items": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
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

`ProgramSummaryResponse` contains `programId`, `name`, `description`, `startDate`, `endDate`, `state`, and the role-allowed public auditorium/programmer information. `ProgramDetailResponse` additionally contains `createdAt`, `version`, creator and role details when visible, and managed Screening links/summaries when requested by the service projection.

Public callers receive only `ANNOUNCED` Programs: ID, name, description, dates, programmer display names, and auditorium information derived from active `SCHEDULED` Screenings. A managing `PROGRAMMER` receives full fields for their Programs in any lifecycle state. Other relationships do not broaden Program visibility beyond the documented public projection.

### Screening draft/final fields

- `filmTitle`: nonblank when supplied; required at submission.
- `cast`: text, nonblank when supplied; required at submission.
- `genre`: nonblank when supplied; required at submission.
- `durationMinutes`: positive integer when supplied; required at submission.
- `candidateAuditorium`: nonblank when supplied; required at submission and only a preference.
- `startTime`, `endTime`: explicit timestamps. When both and duration are present, require `endTime > startTime` and elapsed minutes at least `durationMinutes`. Both are required at submission.

Partial draft updates never replace an explicitly supplied end time with a calculated value. `finalAuditorium` is separate and is set only by scheduling.

`ScreeningDetailResponse` contains allowlisted fields from: `screeningId`, `programId`, film fields, candidate/final auditorium, start/end times, state, conditional notes, final-submission timestamp, rejection reason, review, handler/submitter display information, `createdAt`, and `version`.

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

UC-P2 / FR-2.4. Managing `PROGRAMMER`; Program `If-Match` required. Allowed only in `CREATED`. Audit atomically and return `204 No Content`. A repeated authorized delete has no additional effect.

### POST `/api/v1/programs/{programId}/roles`

UC-P2 / FR-2.3, FR-3. Managing `PROGRAMMER`; `Idempotency-Key` and Program `If-Match` required.

```json
{ "userId": "canonical-uuid", "role": "PROGRAMMER" }
```

Manually accepted role values are `PROGRAMMER` and `STAFF`; `SUBMITTER` is assigned/verified through Screening creation. Target user must exist. Enforce one role per Program and creator protection. `STAFF` assignment is allowed only in `CREATED`; PROGRAMMER management remains allowed until `ANNOUNCED`. Return `201` with `ProgramRoleResponse` and updated Program ETag. Duplicate/conflicting role or frozen STAFF is `409`.

### DELETE `/api/v1/programs/{programId}/roles/{userId}`

UC-P2 / FR-2.3, FR-3. Managing `PROGRAMMER`; Program `If-Match` required. Remove a non-creator PROGRAMMER before `ANNOUNCED`, or STAFF only while `CREATED`. The creator cannot be removed. Return `204`; lifecycle/creator conflict is `409`.

### POST `/api/v1/programs/{programId}/transitions`

UC-P3 / FR-4. Managing `PROGRAMMER`; `Idempotency-Key` and Program `If-Match` required.

```json
{ "targetState": "SUBMISSION" }
```

Only the exact next state is accepted. Validate transition prerequisites under a Program lock and apply side effects atomically:

- Enter `SUBMISSION`: freeze STAFF.
- Enter `REVIEW`: every active submitted Screening has exactly one frozen-STAFF handler.
- Enter `SCHEDULING`: all active submitted Screenings are reviewed.
- Enter `FINAL_PUBLICATION`: every reviewed Screening is `APPROVED` or `REJECTED`.
- Enter `DECISION`: automatically reject every active `APPROVED` Screening without `finalSubmittedAt`, with a system reason.
- Enter `ANNOUNCED`: lock Program content/schedule for public publication.

Return `200` with updated Program details/ETag. Skip, rollback, invalid prerequisite, or concurrency conflict is `409`.

### GET `/api/v1/programs`

UC-P4 / FR-9, FR-11. Anonymous allowed.

Optional trimmed filters: `name`, `description`, `startDateFrom`, `startDateTo`, `endDateFrom`, `endDateTo`, `filmTitle`, `auditorium`. All supplied filters combine with AND and are case-insensitive where textual. `auditorium` is derived from final auditorium data of active `SCHEDULED` Screenings.

Accept `sortDirection=asc|desc` (default `asc`), then order by Program start date, name, UUID. Apply access predicates and redaction in SQL/projection and return a page of `ProgramSummaryResponse`.

### GET `/api/v1/programs/{programId}`

UC-P4 / FR-11. Anonymous allowed. Return `200 ProgramDetailResponse` with the caller's projection. A non-ANNOUNCED Program not managed by the caller is intentionally concealed as `404`.

## Screening endpoints

### POST `/api/v1/programs/{programId}/screenings`

UC-S1 / FR-5. Authenticated user; `Idempotency-Key` required.

Body may contain any subset of the Screening draft fields. Link to an existing Program and validate every supplied value. The requester must not be a PROGRAMMER or STAFF in that Program. Atomically create an active `CREATED` draft and create/verify the requester's `SUBMITTER` Program role. Candidate overbooking is allowed. Return `201`, `Location`, Screening `ETag`, and owner `ScreeningDetailResponse`.

### PATCH `/api/v1/screenings/{screeningId}`

UC-S1 / FR-5. Owning `SUBMITTER`; `Idempotency-Key` and Screening `If-Match` required. Allowed only for active `CREATED` drafts. Body contains at least one draft field; null is not an implicit clear unless a future field-specific contract explicitly permits it. Validate resulting supplied combinations and return `200` plus updated ETag.

### DELETE `/api/v1/screenings/{screeningId}`

UC-S1 / FR-6.3. Owning `SUBMITTER`; Screening `If-Match` required. Soft-delete when active and either:

- Screening is `CREATED`; or
- Screening is `SUBMITTED` and Program is still `SUBMISSION`.

Audit atomically and return `204`. After Program enters `ASSIGNMENT`, withdrawal is `409`.

### POST `/api/v1/screenings/{screeningId}/submit`

UC-S2 / FR-6. Owning `SUBMITTER`; `Idempotency-Key` and Screening `If-Match` required. Body is empty.

Rate-limit, lock the Screening, and require active `CREATED`, Program `SUBMISSION`, and all complete film/candidate/time fields. Freeze editable details, set `SUBMITTED`, audit, commit, and return `200` with updated response/ETag.

### POST `/api/v1/screenings/{screeningId}/handler`

UC-S3 / FR-7. Managing `PROGRAMMER`; `Idempotency-Key` and Screening `If-Match` required.

```json
{ "staffUserId": "canonical-uuid" }
```

Require Program `ASSIGNMENT`, active `SUBMITTED` Screening without a handler, and target in frozen STAFF. Set exactly one handler, audit, and return `200` with updated response/ETag. Reassignment is not part of the canonical contract.

### POST `/api/v1/screenings/{screeningId}/review`

UC-S3 / FR-7. Assigned `STAFF` handler; `Idempotency-Key` and Screening `If-Match` required.

```json
{ "score": 8.50, "detailedComments": "Detailed nonblank assessment." }
```

Require Program `REVIEW`, active `SUBMITTED`, score `0.00..10.00`, nonblank detailed comments, and no existing Review. Atomically insert the one Review, set Screening `REVIEWED`, audit, and return `201` with the staff-visible detail/ETag.

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

Require Program `SCHEDULING` and active `REVIEWED`. Approve sets `APPROVED`; reject records reason and sets final `REJECTED`. Audit and return `200` with updated detail/ETag. A successful decision makes Review fields visible to the owner.

### POST `/api/v1/screenings/{screeningId}/final-submission`

UC-S4 / FR-8.3. Owning `SUBMITTER`; `Idempotency-Key` and Screening `If-Match` required.

Body may include final changes to the film, candidate auditorium, and explicit start/end fields allowed in the draft DTO; final auditorium is excluded. Require Program `FINAL_PUBLICATION`, active `APPROVED`, no prior final submission, complete valid resulting data. Atomically apply changes, set `finalSubmittedAt`, freeze details while keeping state `APPROVED`, audit, and return `200` with updated detail/ETag.

### POST `/api/v1/screenings/{screeningId}/schedule`

UC-S4 / FR-8.4. Managing `PROGRAMMER`; `Idempotency-Key` and Screening `If-Match` required.

```json
{
  "finalAuditorium": "Auditorium A",
  "startTime": "2027-04-10T17:00:00Z",
  "endTime": "2027-04-10T19:15:00Z"
}
```

Require Program `DECISION`, active `APPROVED`, and nonnull `finalSubmittedAt`. Validate explicit final interval against duration. Conflict query compares final auditorium case-insensitively across other active `SCHEDULED` Screenings using:

```text
existingStart < requestedEnd AND existingEnd > requestedStart
```

Candidate auditorium never participates. Under conflict-safe locking, no conflict atomically sets final auditorium/times and final state `SCHEDULED`, audits, and returns `200` plus updated ETag. Conflict returns `409 SCHEDULE_CONFLICT` without state change.

### GET `/api/v1/programs/{programId}/screenings`

UC-S5 / FR-10, FR-11. Anonymous allowed.

Optional trimmed filters: `filmTitle`, `cast`, `genre`, `dateFrom`, `dateTo`; all supplied filters combine with AND. Each textual filter is case-insensitive, is split on whitespace after trimming, and requires every word to occur in that same field. Date bounds apply to `startTime`.

`view=general` (default) orders by genre, film title, UUID. `view=timetable` orders by start time, UUID. Apply relationship-aware visibility and redaction before pagination in MySQL and return a page of Screening summaries.

### GET `/api/v1/screenings/{screeningId}`

UC-S5 / FR-11. Anonymous allowed. Load Screening with Program and return the caller's role-appropriate detail. Missing, soft-deleted, nonpublic, or otherwise concealed resources return `404`.

## Rate-limit and audit expectations

Rate limiting is configuration-driven and covers Screening submission, Program/Screening creation, and both search collections. The single-instance fixed-window limiter keys authenticated traffic by user ID and anonymous traffic by the directly resolved client address, expires idle keys, and bounds its map. Return `429` with `Retry-After` and safe retry metadata; limits are not business constants in DTOs. Multi-instance deployment requires a shared limiter, which is outside this academic deployment.

The same transaction records critical create, delete/withdraw, role change, lifecycle/state change, handler assignment, review, decision, final submission, automatic rejection, and scheduling actions. Audit fields follow NFR-4: UTC timestamp, actor user ID when applicable, action type, target entity ID/type, allowlisted old/new values where useful, and rejection reason. System-triggered automatic rejection may have a null actor plus an explicit system action type/reason.

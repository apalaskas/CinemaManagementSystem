package com.example.cinema.screening.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.cinema.common.api.EntityTagParser;
import com.example.cinema.common.error.ApiProblemFactory;
import com.example.cinema.common.error.ApiProblemFactory.FieldErrorDetail;
import com.example.cinema.common.error.FieldValidationException;
import com.example.cinema.common.error.ForbiddenException;
import com.example.cinema.common.error.GlobalExceptionHandler;
import com.example.cinema.common.error.IdempotencyConflictException;
import com.example.cinema.common.error.InvalidInputException;
import com.example.cinema.common.error.InvalidStateException;
import com.example.cinema.common.error.OptimisticConcurrencyConflictException;
import com.example.cinema.common.error.ProblemResponseWriter;
import com.example.cinema.common.error.ResourceNotFoundException;
import com.example.cinema.common.error.RoleConflictException;
import com.example.cinema.common.infrastructure.CorrelationIdFilter;
import com.example.cinema.common.ratelimit.InProcessRateLimiter;
import com.example.cinema.program.api.UserSummaryResponse;
import com.example.cinema.screening.domain.ScreeningState;
import com.example.cinema.screening.service.ScreeningCommandResult;
import com.example.cinema.screening.service.ScreeningAssignmentReviewService;
import com.example.cinema.screening.service.ScreeningFinalizationService;
import com.example.cinema.screening.service.ScreeningPreparationService;
import com.example.cinema.screening.service.ScreeningSubmissionService;
import com.example.cinema.search.visibility.SearchAndVisibilityService;
import com.example.cinema.user.authentication.CurrentUser;

@WebMvcTest(controllers = ScreeningController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
        EntityTagParser.class,
        ApiProblemFactory.class,
        GlobalExceptionHandler.class,
        CorrelationIdFilter.class,
        ScreeningControllerWebTest.ClockConfiguration.class
})
class ScreeningControllerWebTest {

    private static final UUID PROGRAM_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID SCREENING_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant NOW = Instant.parse("2026-08-15T09:00:00Z");

    @Autowired MockMvc mockMvc;
    @MockitoBean ScreeningPreparationService service;
    @MockitoBean ScreeningSubmissionService submissionService;
    @MockitoBean ScreeningAssignmentReviewService assignmentReviewService;
    @MockitoBean ScreeningFinalizationService finalizationService;
    @MockitoBean SearchAndVisibilityService searchAndVisibilityService;
    @MockitoBean InProcessRateLimiter rateLimiter;
    @MockitoBean CurrentUser currentUser;
    @MockitoBean ProblemResponseWriter problemResponseWriter;

    @Test
    void createsOwnerDraftWithLocationEtagAndNoPrivateCredentialData() throws Exception {
        when(service.create(eq(PROGRAM_ID), any(), eq("create-key")))
                .thenReturn(new ScreeningCommandResult<>(201, response(0), false));

        mockMvc.perform(post("/api/v1/programs/{programId}/screenings", PROGRAM_ID)
                        .with(user("alice"))
                        .header("Idempotency-Key", "create-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/screenings/" + SCREENING_ID))
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.screeningId").value(SCREENING_ID.toString()))
                .andExpect(jsonPath("$.programId").value(PROGRAM_ID.toString()))
                .andExpect(jsonPath("$.state").value("CREATED"))
                .andExpect(jsonPath("$.submitter.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.handler").doesNotExist())
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("hash-never-exposed"))));
        verify(service).create(eq(PROGRAM_ID), eq(new ScreeningCreateRequest(
                "Film", "Cast", "Drama", 120, "Candidate Hall",
                Instant.parse("2027-02-01T10:00:00Z"),
                Instant.parse("2027-02-01T12:00:00Z"))), eq("create-key"));
    }

    @Test
    void allowsAnEmptyPartialCreationBodyButRequiresIdempotencyKey() throws Exception {
        when(service.create(eq(PROGRAM_ID), any(), eq("partial")))
                .thenReturn(new ScreeningCommandResult<>(201, response(0), false));
        mockMvc.perform(post("/api/v1/programs/{programId}/screenings", PROGRAM_ID)
                        .with(user("alice"))
                        .header("Idempotency-Key", "partial")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/programs/{programId}/screenings", PROGRAM_ID)
                        .with(user("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_REQUIRED_HEADER"));
    }

    @ParameterizedTest
    @MethodSource("invalidCreationBodies")
    void rejectsMalformedOrIndividuallyInvalidCreationFields(String body) throws Exception {
        mockMvc.perform(post("/api/v1/programs/{programId}/screenings", PROGRAM_ID)
                        .with(user("alice"))
                        .header("Idempotency-Key", "invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
        verify(service, never()).create(any(), any(), any());
    }

    @Test
    void mapsCrossFieldValidationAndRoleOrStateConflictsSafely() throws Exception {
        when(service.create(eq(PROGRAM_ID), any(), eq("interval"))).thenThrow(
                new InvalidInputException(
                        "INVALID_SCREENING_INTERVAL", "endTime must be after startTime."));
        assertCreateError("interval", 400, "INVALID_SCREENING_INTERVAL");

        when(service.create(eq(PROGRAM_ID), any(), eq("role"))).thenThrow(new RoleConflictException());
        assertCreateError("role", 409, "ROLE_CONFLICT");

        when(service.create(eq(PROGRAM_ID), any(), eq("state"))).thenThrow(new InvalidStateException());
        assertCreateError("state", 409, "INVALID_STATE");
    }

    @Test
    void updatesDraftWithIfMatchIdempotencyAndUpdatedEtag() throws Exception {
        when(service.update(eq(SCREENING_ID), eq(2L), any(), eq("update-key")))
                .thenReturn(new ScreeningCommandResult<>(200, response(3), false));

        mockMvc.perform(patch("/api/v1/screenings/{screeningId}", SCREENING_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .header("Idempotency-Key", "update-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"genre\":\"Documentary\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"3\""))
                .andExpect(jsonPath("$.version").value(3));
        verify(service).update(eq(SCREENING_ID), eq(2L), any(), eq("update-key"));
    }

    @Test
    void updateRequiresBothIfMatchAndIdempotencyHeaders() throws Exception {
        mockMvc.perform(patch("/api/v1/screenings/{screeningId}", SCREENING_ID)
                        .with(user("alice"))
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"genre\":\"Drama\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_REQUIRED_HEADER"));

        mockMvc.perform(patch("/api/v1/screenings/{screeningId}", SCREENING_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"genre\":\"Drama\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_REQUIRED_HEADER"));
    }

    @ParameterizedTest
    @MethodSource("forbiddenUpdateBodies")
    void rejectsEveryForbiddenScreeningFieldBeforeTheService(String body) throws Exception {
        mockMvc.perform(patch("/api/v1/screenings/{screeningId}", SCREENING_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
        verify(service, never()).update(any(), any(Long.class), any(), any());
    }

    @Test
    void mapsOwnerVisibilityStateConcurrencyAndIdempotencyErrors() throws Exception {
        when(service.update(eq(SCREENING_ID), eq(2L), any(), eq("forbidden")))
                .thenThrow(new ForbiddenException());
        assertUpdateError("forbidden", 403, "FORBIDDEN");
        when(service.update(eq(SCREENING_ID), eq(2L), any(), eq("concealed")))
                .thenThrow(new ResourceNotFoundException());
        assertUpdateError("concealed", 404, "RESOURCE_NOT_FOUND");
        when(service.update(eq(SCREENING_ID), eq(2L), any(), eq("state")))
                .thenThrow(new InvalidStateException());
        assertUpdateError("state", 409, "INVALID_STATE");
        when(service.update(eq(SCREENING_ID), eq(2L), any(), eq("stale")))
                .thenThrow(new OptimisticConcurrencyConflictException());
        assertUpdateError("stale", 409, "CONCURRENT_MODIFICATION");
        when(service.update(eq(SCREENING_ID), eq(2L), any(), eq("mismatch")))
                .thenThrow(new IdempotencyConflictException(
                        "IDEMPOTENCY_KEY_REUSED", "The key was reused.", false));
        assertUpdateError("mismatch", 409, "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void exactCreationAndUpdateReplayReturnTheStoredStatusAndBody() throws Exception {
        when(service.create(eq(PROGRAM_ID), any(), eq("create-replay")))
                .thenReturn(new ScreeningCommandResult<>(201, response(0), true));
        mockMvc.perform(post("/api/v1/programs/{programId}/screenings", PROGRAM_ID)
                        .with(user("alice"))
                        .header("Idempotency-Key", "create-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.screeningId").value(SCREENING_ID.toString()));

        when(service.update(eq(SCREENING_ID), eq(2L), any(), eq("update-replay")))
                .thenReturn(new ScreeningCommandResult<>(200, response(3), true));
        mockMvc.perform(patch("/api/v1/screenings/{screeningId}", SCREENING_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .header("Idempotency-Key", "update-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"genre\":\"Documentary\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void withdrawsWithIfMatchAndReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/screenings/{screeningId}", SCREENING_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"4\""))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(service).withdraw(SCREENING_ID, 4);
    }

    @Test
    void withdrawalUsesSafeMissingAndConflictResponses() throws Exception {
        org.mockito.Mockito.doThrow(new ResourceNotFoundException())
                .when(service).withdraw(SCREENING_ID, 2);
        assertWithdrawalError(404, "RESOURCE_NOT_FOUND");
        org.mockito.Mockito.doThrow(new InvalidStateException())
                .when(service).withdraw(SCREENING_ID, 2);
        assertWithdrawalError(409, "INVALID_STATE");
        org.mockito.Mockito.doThrow(new OptimisticConcurrencyConflictException())
                .when(service).withdraw(SCREENING_ID, 2);
        assertWithdrawalError(409, "CONCURRENT_MODIFICATION");
    }

    @Test
    void submitsScreeningWithRequiredHeadersAndReturnsFrozenOwnerResponse() throws Exception {
        when(submissionService.submit(SCREENING_ID, 2, "submit-key"))
                .thenReturn(new ScreeningCommandResult<>(200, response(3, ScreeningState.SUBMITTED), false));

        mockMvc.perform(post("/api/v1/screenings/{screeningId}/submit", SCREENING_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .header("Idempotency-Key", "submit-key"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"3\""))
                .andExpect(jsonPath("$.screeningId").value(SCREENING_ID.toString()))
                .andExpect(jsonPath("$.programId").value(PROGRAM_ID.toString()))
                .andExpect(jsonPath("$.state").value("SUBMITTED"))
                .andExpect(jsonPath("$.finalSubmittedAt").doesNotExist())
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("hash-never-exposed"))));
        verify(submissionService).submit(SCREENING_ID, 2, "submit-key");
    }

    @Test
    void submissionRequiresIfMatchAndIdempotencyKeyAndRejectsABody() throws Exception {
        mockMvc.perform(post("/api/v1/screenings/{screeningId}/submit", SCREENING_ID)
                        .with(user("alice"))
                        .header("Idempotency-Key", "key"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_REQUIRED_HEADER"));

        mockMvc.perform(post("/api/v1/screenings/{screeningId}/submit", SCREENING_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_REQUIRED_HEADER"));

        mockMvc.perform(post("/api/v1/screenings/{screeningId}/submit", SCREENING_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UNEXPECTED_REQUEST_BODY"));
        verify(submissionService, never()).submit(any(), any(Long.class), any());
    }

    @Test
    void submissionReturnsFieldSpecificCompletenessErrors() throws Exception {
        when(submissionService.submit(SCREENING_ID, 2, "incomplete"))
                .thenThrow(new FieldValidationException(
                        "SCREENING_SUBMISSION_INVALID",
                        "The Screening is incomplete or invalid for submission.",
                        List.of(
                                new FieldErrorDetail("filmTitle", "must be nonblank for submission"),
                                new FieldErrorDetail("endTime", "is required for submission"))));

        mockMvc.perform(post("/api/v1/screenings/{screeningId}/submit", SCREENING_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .header("Idempotency-Key", "incomplete"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("SCREENING_SUBMISSION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("filmTitle"))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("endTime"))
                .andExpect(content().string(not(containsString("SQL"))))
                .andExpect(content().string(not(containsString("stackTrace"))));
    }

    @Test
    void submissionMapsAuthorizationVisibilityStateConcurrencyAndIdempotencyErrorsSafely() throws Exception {
        when(submissionService.submit(SCREENING_ID, 2, "forbidden")).thenThrow(new ForbiddenException());
        assertSubmissionError("forbidden", 403, "FORBIDDEN");
        when(submissionService.submit(SCREENING_ID, 2, "concealed")).thenThrow(new ResourceNotFoundException());
        assertSubmissionError("concealed", 404, "RESOURCE_NOT_FOUND");
        when(submissionService.submit(SCREENING_ID, 2, "state")).thenThrow(new InvalidStateException());
        assertSubmissionError("state", 409, "INVALID_STATE");
        when(submissionService.submit(SCREENING_ID, 2, "stale"))
                .thenThrow(new OptimisticConcurrencyConflictException());
        assertSubmissionError("stale", 409, "CONCURRENT_MODIFICATION");
        when(submissionService.submit(SCREENING_ID, 2, "lock-timeout"))
                .thenThrow(new PessimisticLockingFailureException("database lock details"));
        assertSubmissionError("lock-timeout", 409, "CONCURRENT_MODIFICATION");
        when(submissionService.submit(SCREENING_ID, 2, "mismatch"))
                .thenThrow(new IdempotencyConflictException(
                        "IDEMPOTENCY_KEY_REUSED", "The key was reused.", false));
        assertSubmissionError("mismatch", 409, "IDEMPOTENCY_KEY_REUSED");
        when(submissionService.submit(SCREENING_ID, 2, "in-progress"))
                .thenThrow(new IdempotencyConflictException(
                        "IDEMPOTENCY_REQUEST_IN_PROGRESS", "The request is in progress.", true));
        assertSubmissionError("in-progress", 409, "IDEMPOTENCY_REQUEST_IN_PROGRESS");
    }

    @Test
    void submissionReplayReturnsStoredSuccessAndUnexpectedFailureIsControlled() throws Exception {
        when(submissionService.submit(SCREENING_ID, 2, "replay"))
                .thenReturn(new ScreeningCommandResult<>(200, response(3, ScreeningState.SUBMITTED), true));
        mockMvc.perform(post("/api/v1/screenings/{screeningId}/submit", SCREENING_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .header("Idempotency-Key", "replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUBMITTED"));

        when(submissionService.submit(SCREENING_ID, 2, "failure"))
                .thenThrow(new IllegalStateException("SQL repository screening failed"));
        mockMvc.perform(post("/api/v1/screenings/{screeningId}/submit", SCREENING_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .header("Idempotency-Key", "failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("SQL"))))
                .andExpect(content().string(not(containsString("repository"))))
                .andExpect(content().string(not(containsString("stackTrace"))));
    }

    private void assertCreateError(String key, int expectedStatus, String errorCode) throws Exception {
        mockMvc.perform(post("/api/v1/programs/{programId}/screenings", PROGRAM_ID)
                        .with(user("alice"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.errorCode").value(errorCode))
                .andExpect(content().string(not(containsString("SQL"))));
    }

    private void assertUpdateError(String key, int expectedStatus, String errorCode) throws Exception {
        mockMvc.perform(patch("/api/v1/screenings/{screeningId}", SCREENING_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"genre\":\"Drama\"}"))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.errorCode").value(errorCode))
                .andExpect(content().string(not(containsString("SQL"))));
    }

    private void assertWithdrawalError(int expectedStatus, String errorCode) throws Exception {
        mockMvc.perform(delete("/api/v1/screenings/{screeningId}", SCREENING_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.errorCode").value(errorCode))
                .andExpect(content().string(not(containsString("SQL"))));
    }

    private void assertSubmissionError(String key, int expectedStatus, String errorCode) throws Exception {
        mockMvc.perform(post("/api/v1/screenings/{screeningId}/submit", SCREENING_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .header("Idempotency-Key", key))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.errorCode").value(errorCode))
                .andExpect(content().string(not(containsString("SQL"))))
                .andExpect(content().string(not(containsString("stackTrace"))));
    }

    private static ScreeningDetailResponse response(long version) {
        return response(version, ScreeningState.CREATED);
    }

    private static ScreeningDetailResponse response(long version, ScreeningState state) {
        return new ScreeningDetailResponse(
                SCREENING_ID,
                PROGRAM_ID,
                "Film",
                "Cast",
                "Drama",
                120,
                "Candidate Hall",
                null,
                Instant.parse("2027-02-01T10:00:00Z"),
                Instant.parse("2027-02-01T12:00:00Z"),
                state,
                null,
                null,
                null,
                new UserSummaryResponse(USER_ID, "alice", "Alice Submitter"),
                null,
                NOW,
                version);
    }

    private static String validBody() {
        return """
                {"filmTitle":"Film","cast":"Cast","genre":"Drama","durationMinutes":120,
                 "candidateAuditoriumName":"Candidate Hall",
                 "startTime":"2027-02-01T10:00:00Z","endTime":"2027-02-01T12:00:00Z"}
                """;
    }

    private static Stream<String> invalidCreationBodies() {
        return Stream.of(
                "{\"filmTitle\":\"   \"}",
                "{\"cast\":\"   \"}",
                "{\"genre\":\"   \"}",
                "{\"candidateAuditoriumName\":\"   \"}",
                "{\"durationMinutes\":0}",
                "{\"startTime\":\"2027-02-01T10:00:00\"}",
                "{\"state\":\"SUBMITTED\"}");
    }

    private static Stream<String> forbiddenUpdateBodies() {
        return Stream.of(
                "{\"programId\":\"" + PROGRAM_ID + "\"}",
                "{\"submitterUserId\":\"" + USER_ID + "\"}",
                "{\"submitter\":{\"userId\":\"" + USER_ID + "\"}}",
                "{\"handlerUserId\":\"" + USER_ID + "\"}",
                "{\"handler\":{\"userId\":\"" + USER_ID + "\"}}",
                "{\"state\":\"SUBMITTED\"}",
                "{\"finalAuditoriumName\":\"Final Hall\"}",
                "{\"finalSubmittedAt\":\"2027-02-01T12:00:00Z\"}",
                "{\"rejectionReason\":\"reason\"}",
                "{\"review\":{}}",
                "{\"conditionalNotes\":\"notes\"}",
                "{\"createdAt\":\"2026-08-15T09:00:00Z\"}",
                "{\"deletedAt\":\"2026-08-15T09:00:00Z\"}",
                "{\"screeningId\":\"" + SCREENING_ID + "\"}",
                "{\"id\":\"" + SCREENING_ID + "\"}",
                "{\"version\":3}");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}

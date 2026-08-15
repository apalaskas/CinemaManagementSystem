package com.example.cinema.screening.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
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
import com.example.cinema.common.error.ForbiddenException;
import com.example.cinema.common.error.GlobalExceptionHandler;
import com.example.cinema.common.error.IdempotencyConflictException;
import com.example.cinema.common.error.InvalidStateException;
import com.example.cinema.common.error.OptimisticConcurrencyConflictException;
import com.example.cinema.common.error.ProblemResponseWriter;
import com.example.cinema.common.error.SchedulingConflictException;
import com.example.cinema.common.infrastructure.CorrelationIdFilter;
import com.example.cinema.common.ratelimit.InProcessRateLimiter;
import com.example.cinema.program.api.UserSummaryResponse;
import com.example.cinema.screening.domain.ScreeningState;
import com.example.cinema.screening.service.ScreeningAssignmentReviewService;
import com.example.cinema.screening.service.ScreeningCommandResult;
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
        ScreeningFinalizationControllerWebTest.ClockConfiguration.class
})
class ScreeningFinalizationControllerWebTest {

    private static final UUID SCREENING_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID PROGRAM_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID SUBMITTER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant NOW = Instant.parse("2027-05-01T09:30:00Z");
    private static final Instant START = Instant.parse("2027-06-01T10:00:00Z");
    private static final Instant END = Instant.parse("2027-06-01T12:00:00Z");

    @Autowired MockMvc mockMvc;
    @MockitoBean ScreeningPreparationService preparationService;
    @MockitoBean ScreeningSubmissionService submissionService;
    @MockitoBean ScreeningAssignmentReviewService assignmentReviewService;
    @MockitoBean ScreeningFinalizationService finalizationService;
    @MockitoBean SearchAndVisibilityService searchAndVisibilityService;
    @MockitoBean InProcessRateLimiter rateLimiter;
    @MockitoBean CurrentUser currentUser;
    @MockitoBean ProblemResponseWriter problemResponseWriter;

    @Test
    void approvesWithSafeDecisionResponseAndUpdatedEtag() throws Exception {
        ScreeningDecisionResponse response = new ScreeningDecisionResponse(
                SCREENING_ID, ScreeningDecision.APPROVE, ScreeningState.APPROVED,
                "Requested changes", null, 4);
        when(finalizationService.decide(eq(SCREENING_ID), eq(3L), any(), eq("decision-key")))
                .thenReturn(new ScreeningCommandResult<>(200, response, false));

        mockMvc.perform(post("/api/v1/screenings/{screeningId}/decision", SCREENING_ID)
                        .with(user("programmer"))
                        .header(HttpHeaders.IF_MATCH, "\"3\"")
                        .header("Idempotency-Key", "decision-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"conditionalNotes\":\"Requested changes\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"4\""))
                .andExpect(jsonPath("$.screeningId").value(SCREENING_ID.toString()))
                .andExpect(jsonPath("$.decision").value("APPROVE"))
                .andExpect(jsonPath("$.state").value("APPROVED"))
                .andExpect(jsonPath("$.conditionalNotes").value("Requested changes"))
                .andExpect(jsonPath("$.rejectionReason").doesNotExist())
                .andExpect(content().string(not(containsString("password"))));
        verify(finalizationService).decide(
                SCREENING_ID, 3,
                new ScreeningDecisionRequest(ScreeningDecision.APPROVE, "Requested changes", null),
                "decision-key");
    }

    @Test
    void recordsOwnerFinalSubmissionWithOnlyOwnerAppropriateFields() throws Exception {
        when(finalizationService.finalSubmit(eq(SCREENING_ID), eq(4L), any(), eq("final-key")))
                .thenReturn(new ScreeningCommandResult<>(200, finalResponse(), false));

        mockMvc.perform(post("/api/v1/screenings/{screeningId}/final-submission", SCREENING_ID)
                        .with(user("submitter"))
                        .header(HttpHeaders.IF_MATCH, "\"4\"")
                        .header("Idempotency-Key", "final-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filmTitle\":\"Final Film\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"5\""))
                .andExpect(jsonPath("$.state").value("APPROVED"))
                .andExpect(jsonPath("$.filmTitle").value("Final Film"))
                .andExpect(jsonPath("$.finalSubmittedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.submitter.userId").value(SUBMITTER_ID.toString()))
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("hash-never-exposed"))));
        verify(finalizationService).finalSubmit(eq(SCREENING_ID), eq(4L), any(), eq("final-key"));
    }

    @Test
    void schedulesWithCanonicalFinalAuditoriumFieldAndEtag() throws Exception {
        ScreeningScheduleResponse response = new ScreeningScheduleResponse(
                SCREENING_ID, ScreeningState.SCHEDULED, "Final Hall", START, END, 6);
        when(finalizationService.schedule(eq(SCREENING_ID), eq(5L), any(), eq("schedule-key")))
                .thenReturn(new ScreeningCommandResult<>(200, response, false));

        mockMvc.perform(post("/api/v1/screenings/{screeningId}/schedule", SCREENING_ID)
                        .with(user("programmer"))
                        .header(HttpHeaders.IF_MATCH, "\"5\"")
                        .header("Idempotency-Key", "schedule-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"finalAuditoriumName":"Final Hall",
                                 "startTime":"2027-06-01T10:00:00Z",
                                 "endTime":"2027-06-01T12:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"6\""))
                .andExpect(jsonPath("$.state").value("SCHEDULED"))
                .andExpect(jsonPath("$.finalAuditoriumName").value("Final Hall"))
                .andExpect(jsonPath("$.startTime").value(START.toString()))
                .andExpect(jsonPath("$.endTime").value(END.toString()));
        verify(finalizationService).schedule(
                SCREENING_ID, 5, new ScreeningScheduleRequest("Final Hall", START, END), "schedule-key");
    }

    @Test
    void validatesDecisionAndCompleteScheduleBodiesBeforeService() throws Exception {
        mockMvc.perform(post("/api/v1/screenings/{screeningId}/decision", SCREENING_ID)
                        .with(user("programmer"))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "invalid-decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("decision"));

        mockMvc.perform(post("/api/v1/screenings/{screeningId}/schedule", SCREENING_ID)
                        .with(user("programmer"))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "invalid-schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"finalAuditoriumName\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(3));
        verify(finalizationService, never()).schedule(any(), any(Long.class), any(), any());
    }

    @Test
    void rejectsMissingOrBlankRejectionReasonAtTheServiceBoundary() throws Exception {
        when(finalizationService.decide(eq(SCREENING_ID), eq(3L), any(), eq("missing-reason")))
                .thenThrow(new com.example.cinema.common.error.FieldValidationException(
                        "SCREENING_DECISION_INVALID",
                        "The Screening decision is invalid.",
                        java.util.List.of(new ApiProblemFactory.FieldErrorDetail(
                                "reason", "must be nonblank for REJECT"))));

        mockMvc.perform(post("/api/v1/screenings/{screeningId}/decision", SCREENING_ID)
                        .with(user("programmer"))
                        .header(HttpHeaders.IF_MATCH, "\"3\"")
                        .header("Idempotency-Key", "missing-reason")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("SCREENING_DECISION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reason"))
                .andExpect(content().string(not(containsString("SQL"))));
    }

    @Test
    void allThreeCommandsRequireIfMatchAndIdempotencyHeaders() throws Exception {
        mockMvc.perform(post("/api/v1/screenings/{screeningId}/decision", SCREENING_ID)
                        .with(user("programmer"))
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_REQUIRED_HEADER"));

        mockMvc.perform(post("/api/v1/screenings/{screeningId}/final-submission", SCREENING_ID)
                        .with(user("submitter"))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_REQUIRED_HEADER"));

        mockMvc.perform(post("/api/v1/screenings/{screeningId}/schedule", SCREENING_ID)
                        .with(user("programmer"))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validScheduleBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_REQUIRED_HEADER"));
    }

    @Test
    void finalSubmissionRejectsEveryForbiddenOrUnknownField() throws Exception {
        for (String field : new String[] {
                "screeningId", "programId", "submitterUserId", "handler", "state",
                "finalAuditoriumName", "finalSubmittedAt", "rejectionReason", "review",
                "conditionalNotes", "createdAt", "deletedAt", "version", "unexpected"
        }) {
            mockMvc.perform(post("/api/v1/screenings/{screeningId}/final-submission", SCREENING_ID)
                            .with(user("submitter"))
                            .header(HttpHeaders.IF_MATCH, "\"4\"")
                            .header("Idempotency-Key", "forbidden-" + field)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"" + field + "\":\"value\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
        }
        verify(finalizationService, never()).finalSubmit(any(), any(Long.class), any(), any());
    }

    @Test
    void mapsSchedulingAndOtherCommandConflictsWithoutPrivateDetails() throws Exception {
        when(finalizationService.schedule(eq(SCREENING_ID), eq(5L), any(), eq("conflict")))
                .thenThrow(new SchedulingConflictException());
        mockMvc.perform(post("/api/v1/screenings/{screeningId}/schedule", SCREENING_ID)
                        .with(user("programmer"))
                        .header(HttpHeaders.IF_MATCH, "\"5\"")
                        .header("Idempotency-Key", "conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validScheduleBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SCHEDULING_CONFLICT"))
                .andExpect(content().string(not(containsString("conflictingScreening"))))
                .andExpect(content().string(not(containsString("SQL"))));

        when(finalizationService.decide(eq(SCREENING_ID), eq(3L), any(), eq("state")))
                .thenThrow(new InvalidStateException());
        assertDecisionError("state", 409, "INVALID_STATE");

        when(finalizationService.decide(eq(SCREENING_ID), eq(3L), any(), eq("forbidden")))
                .thenThrow(new ForbiddenException());
        assertDecisionError("forbidden", 403, "FORBIDDEN");

        when(finalizationService.decide(eq(SCREENING_ID), eq(3L), any(), eq("mismatch")))
                .thenThrow(new IdempotencyConflictException(
                        "IDEMPOTENCY_KEY_REUSED", "The key was reused.", false));
        assertDecisionError("mismatch", 409, "IDEMPOTENCY_KEY_REUSED");

        when(finalizationService.decide(eq(SCREENING_ID), eq(3L), any(), eq("optimistic")))
                .thenThrow(new OptimisticConcurrencyConflictException());
        assertDecisionError("optimistic", 409, "CONCURRENT_MODIFICATION");

        when(finalizationService.schedule(eq(SCREENING_ID), eq(5L), any(), eq("pessimistic")))
                .thenThrow(new PessimisticLockingFailureException("lock timeout"));
        mockMvc.perform(post("/api/v1/screenings/{screeningId}/schedule", SCREENING_ID)
                        .with(user("programmer"))
                        .header(HttpHeaders.IF_MATCH, "\"5\"")
                        .header("Idempotency-Key", "pessimistic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validScheduleBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONCURRENT_MODIFICATION"))
                .andExpect(content().string(not(containsString("lock timeout"))))
                .andExpect(content().string(not(containsString("SQL"))));
    }

    private void assertDecisionError(String key, int expectedStatus, String code) throws Exception {
        mockMvc.perform(post("/api/v1/screenings/{screeningId}/decision", SCREENING_ID)
                        .with(user("programmer"))
                        .header(HttpHeaders.IF_MATCH, "\"3\"")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.errorCode").value(code))
                .andExpect(content().string(not(containsString("SQL"))))
                .andExpect(content().string(not(containsString("stackTrace"))));
    }

    private static ScreeningDetailResponse finalResponse() {
        return new ScreeningDetailResponse(
                SCREENING_ID, PROGRAM_ID, "Final Film", "Cast", "Drama", 90,
                "Candidate Hall", null, START, END, ScreeningState.APPROVED,
                null, NOW, null,
                new UserSummaryResponse(SUBMITTER_ID, "submitter", "Submitter Person"),
                null, NOW.minusSeconds(3600), 5);
    }

    private static String validScheduleBody() {
        return """
                {"finalAuditoriumName":"Final Hall",
                 "startTime":"2027-06-01T10:00:00Z",
                 "endTime":"2027-06-01T12:00:00Z"}
                """;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}

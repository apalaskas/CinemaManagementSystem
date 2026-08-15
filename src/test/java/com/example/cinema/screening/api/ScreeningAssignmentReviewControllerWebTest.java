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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import com.example.cinema.common.error.ProblemResponseWriter;
import com.example.cinema.common.error.ResourceNotFoundException;
import com.example.cinema.common.error.ReviewAlreadyExistsException;
import com.example.cinema.common.error.RoleConflictException;
import com.example.cinema.common.infrastructure.CorrelationIdFilter;
import com.example.cinema.common.ratelimit.InProcessRateLimiter;
import com.example.cinema.program.api.UserSummaryResponse;
import com.example.cinema.screening.domain.ReviewEntity;
import com.example.cinema.screening.domain.ScreeningState;
import com.example.cinema.screening.service.ScreeningAssignmentReviewService;
import com.example.cinema.screening.service.ScreeningCommandResult;
import com.example.cinema.screening.service.ScreeningPreparationService;
import com.example.cinema.screening.service.ScreeningSubmissionService;
import com.example.cinema.user.authentication.CurrentUser;

@WebMvcTest(controllers = ScreeningController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
        EntityTagParser.class,
        ApiProblemFactory.class,
        GlobalExceptionHandler.class,
        CorrelationIdFilter.class,
        ScreeningAssignmentReviewControllerWebTest.ClockConfiguration.class
})
class ScreeningAssignmentReviewControllerWebTest {

    private static final UUID SCREENING_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final UUID STAFF_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID REVIEW_ID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    private static final Instant NOW = Instant.parse("2027-03-01T10:15:30Z");

    @Autowired MockMvc mockMvc;
    @MockitoBean ScreeningPreparationService preparationService;
    @MockitoBean ScreeningSubmissionService submissionService;
    @MockitoBean ScreeningAssignmentReviewService assignmentReviewService;
    @MockitoBean InProcessRateLimiter rateLimiter;
    @MockitoBean CurrentUser currentUser;
    @MockitoBean ProblemResponseWriter problemResponseWriter;

    @Test
    void assignsHandlerWithCommandHeadersEtagAndRedactedSummary() throws Exception {
        when(assignmentReviewService.assignHandler(eq(SCREENING_ID), eq(3L), any(), eq("handler-key")))
                .thenReturn(new ScreeningCommandResult<>(200, handlerResponse(), false));

        mockMvc.perform(post("/api/v1/screenings/{screeningId}/handler", SCREENING_ID)
                        .with(user("programmer"))
                        .header(HttpHeaders.IF_MATCH, "\"3\"")
                        .header("Idempotency-Key", "handler-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffUserId\":\"" + STAFF_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"4\""))
                .andExpect(jsonPath("$.screeningId").value(SCREENING_ID.toString()))
                .andExpect(jsonPath("$.handler.userId").value(STAFF_ID.toString()))
                .andExpect(jsonPath("$.handler.fullName").value("Staff Person"))
                .andExpect(jsonPath("$.state").value("SUBMITTED"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("hash-never-exposed"))));
        verify(assignmentReviewService).assignHandler(
                SCREENING_ID, 3, new ScreeningHandlerAssignmentRequest(STAFF_ID), "handler-key");
    }

    @Test
    void createsReviewWithCanonicalNumericScoreAndNoCredentialLeakage() throws Exception {
        when(assignmentReviewService.submitReview(eq(SCREENING_ID), eq(4L), any(), eq("review-key")))
                .thenReturn(new ScreeningCommandResult<>(201, reviewResponse(), false));

        mockMvc.perform(post("/api/v1/screenings/{screeningId}/review", SCREENING_ID)
                        .with(user("staff"))
                        .header(HttpHeaders.IF_MATCH, "\"4\"")
                        .header("Idempotency-Key", "review-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"numericScore\":8.50,\"detailedComments\":\"Strong review\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"5\""))
                .andExpect(jsonPath("$.reviewId").value(REVIEW_ID.toString()))
                .andExpect(jsonPath("$.screeningId").value(SCREENING_ID.toString()))
                .andExpect(jsonPath("$.state").value("REVIEWED"))
                .andExpect(jsonPath("$.numericScore").value(8.5))
                .andExpect(jsonPath("$.detailedComments").value("Strong review"))
                .andExpect(jsonPath("$.reviewer.userId").value(STAFF_ID.toString()))
                .andExpect(jsonPath("$.createdAt").value(NOW.toString()))
                .andExpect(jsonPath("$.screeningVersion").value(5))
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("hash-never-exposed"))));
        verify(assignmentReviewService).submitReview(
                SCREENING_ID, 4,
                new ScreeningReviewRequest(new BigDecimal("8.50"), "Strong review"), "review-key");
    }

    @ParameterizedTest
    @MethodSource("invalidReviewBodies")
    void rejectsEveryInvalidReviewFieldAtTheBoundary(String body, String field) throws Exception {
        mockMvc.perform(post("/api/v1/screenings/{screeningId}/review", SCREENING_ID)
                        .with(user("staff"))
                        .header(HttpHeaders.IF_MATCH, "\"4\"")
                        .header("Idempotency-Key", "invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == '" + field + "')]").exists());
        verify(assignmentReviewService, never()).submitReview(any(), any(Long.class), any(), any());
    }

    @Test
    void handlerBodyAndBothCommandHeadersAreMandatory() throws Exception {
        mockMvc.perform(post("/api/v1/screenings/{screeningId}/handler", SCREENING_ID)
                        .with(user("programmer"))
                        .header(HttpHeaders.IF_MATCH, "\"3\"")
                        .header("Idempotency-Key", "handler-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("staffUserId"));

        mockMvc.perform(post("/api/v1/screenings/{screeningId}/handler", SCREENING_ID)
                        .with(user("programmer"))
                        .header("Idempotency-Key", "handler-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffUserId\":\"" + STAFF_ID + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_REQUIRED_HEADER"));

        mockMvc.perform(post("/api/v1/screenings/{screeningId}/review", SCREENING_ID)
                        .with(user("staff"))
                        .header(HttpHeaders.IF_MATCH, "\"4\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"numericScore\":8.5,\"detailedComments\":\"Good\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_REQUIRED_HEADER"));
    }

    @ParameterizedTest
    @MethodSource("safeCommandErrors")
    void mapsAuthorizationStateRoleDuplicateAndIdempotencyErrorsSafely(
            RuntimeException exception, int expectedStatus, String expectedCode) throws Exception {
        when(assignmentReviewService.assignHandler(eq(SCREENING_ID), eq(3L), any(), eq("error-key")))
                .thenThrow(exception);

        mockMvc.perform(post("/api/v1/screenings/{screeningId}/handler", SCREENING_ID)
                        .with(user("programmer"))
                        .header(HttpHeaders.IF_MATCH, "\"3\"")
                        .header("Idempotency-Key", "error-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffUserId\":\"" + STAFF_ID + "\"}"))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.errorCode").value(expectedCode))
                .andExpect(content().string(not(containsString("SQL"))))
                .andExpect(content().string(not(containsString("repository"))))
                .andExpect(content().string(not(containsString("stackTrace"))));
    }

    @Test
    void unexpectedReviewFailureReturnsControlledRedacted500() throws Exception {
        when(assignmentReviewService.submitReview(eq(SCREENING_ID), eq(4L), any(), eq("failure")))
                .thenThrow(new IllegalStateException("review table SQL password hash-never-exposed"));
        mockMvc.perform(post("/api/v1/screenings/{screeningId}/review", SCREENING_ID)
                        .with(user("staff"))
                        .header(HttpHeaders.IF_MATCH, "\"4\"")
                        .header("Idempotency-Key", "failure")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"numericScore\":8.5,\"detailedComments\":\"Good\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("review table"))))
                .andExpect(content().string(not(containsString("hash-never-exposed"))));
    }

    private static ScreeningHandlerAssignmentResponse handlerResponse() {
        return new ScreeningHandlerAssignmentResponse(
                SCREENING_ID,
                new UserSummaryResponse(STAFF_ID, "staff", "Staff Person"),
                ScreeningState.SUBMITTED,
                4);
    }

    private static ScreeningReviewResponse reviewResponse() {
        return new ScreeningReviewResponse(
                REVIEW_ID,
                SCREENING_ID,
                ScreeningState.REVIEWED,
                new BigDecimal("8.50"),
                "Strong review",
                new UserSummaryResponse(STAFF_ID, "staff", "Staff Person"),
                NOW,
                5);
    }

    private static Stream<Arguments> invalidReviewBodies() {
        return Stream.of(
                Arguments.of("{\"detailedComments\":\"Good\"}", "numericScore"),
                Arguments.of("{\"numericScore\":-0.01,\"detailedComments\":\"Good\"}", "numericScore"),
                Arguments.of("{\"numericScore\":10.01,\"detailedComments\":\"Good\"}", "numericScore"),
                Arguments.of("{\"numericScore\":8.001,\"detailedComments\":\"Good\"}", "numericScore"),
                Arguments.of("{\"numericScore\":8.5}", "detailedComments"),
                Arguments.of("{\"numericScore\":8.5,\"detailedComments\":\"   \"}", "detailedComments"),
                Arguments.of("{\"numericScore\":8.5,\"detailedComments\":\""
                        + "x".repeat(ReviewEntity.MAXIMUM_COMMENT_LENGTH + 1) + "\"}", "detailedComments"));
    }

    private static Stream<Arguments> safeCommandErrors() {
        return Stream.of(
                Arguments.of(new ForbiddenException(), 403, "FORBIDDEN"),
                Arguments.of(new ResourceNotFoundException(), 404, "RESOURCE_NOT_FOUND"),
                Arguments.of(new InvalidStateException(), 409, "INVALID_STATE"),
                Arguments.of(new RoleConflictException(), 409, "ROLE_CONFLICT"),
                Arguments.of(new ReviewAlreadyExistsException(), 409, "REVIEW_ALREADY_EXISTS"),
                Arguments.of(new IdempotencyConflictException(
                        "IDEMPOTENCY_KEY_REUSED", "The key was reused.", false),
                        409, "IDEMPOTENCY_KEY_REUSED"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}

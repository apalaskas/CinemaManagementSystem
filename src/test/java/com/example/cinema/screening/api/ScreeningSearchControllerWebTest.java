package com.example.cinema.screening.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.cinema.common.api.EntityTagParser;
import com.example.cinema.common.api.PageResponse;
import com.example.cinema.common.error.ApiProblemFactory;
import com.example.cinema.common.error.GlobalExceptionHandler;
import com.example.cinema.common.error.InvalidInputException;
import com.example.cinema.common.error.ProblemResponseWriter;
import com.example.cinema.common.error.ResourceNotFoundException;
import com.example.cinema.common.infrastructure.CorrelationIdFilter;
import com.example.cinema.common.ratelimit.InProcessRateLimiter;
import com.example.cinema.program.api.UserSummaryResponse;
import com.example.cinema.screening.domain.ScreeningState;
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
        ScreeningSearchControllerWebTest.ClockConfiguration.class
})
class ScreeningSearchControllerWebTest {

    private static final UUID PROGRAM_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID SCREENING_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant NOW = Instant.parse("2026-08-15T09:00:00Z");

    @Autowired MockMvc mockMvc;
    @MockitoBean ScreeningPreparationService preparationService;
    @MockitoBean ScreeningSubmissionService submissionService;
    @MockitoBean ScreeningAssignmentReviewService assignmentReviewService;
    @MockitoBean ScreeningFinalizationService finalizationService;
    @MockitoBean SearchAndVisibilityService searchService;
    @MockitoBean InProcessRateLimiter rateLimiter;
    @MockitoBean CurrentUser currentUser;
    @MockitoBean ProblemResponseWriter problemResponseWriter;

    @Test
    void mapsAllSearchParametersAndReturnsExplicitPageResponse() throws Exception {
        when(searchService.searchScreenings(eq(PROGRAM_ID), any())).thenReturn(
                new PageResponse<>(2, 5, 11, 3, List.of(publicResponse())));

        mockMvc.perform(get("/api/v1/programs/{programId}/screenings", PROGRAM_ID)
                        .param("filmTitle", " Dark Night ")
                        .param("cast", " Alice Bob ")
                        .param("genre", " Drama ")
                        .param("fromDateTime", "2027-01-01T10:00:00Z")
                        .param("toDateTime", "2027-01-31T20:00:00Z")
                        .param("view", "TIMETABLE")
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(11))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content", hasSize(1)));

        verify(searchService).searchScreenings(PROGRAM_ID, new ScreeningSearchParameters(
                " Dark Night ", " Alice Bob ", " Drama ",
                Instant.parse("2027-01-01T10:00:00Z"),
                Instant.parse("2027-01-31T20:00:00Z"), "TIMETABLE", 2, 5));
    }

    @Test
    void appliesDefaultsAndRejectsMalformedOrInvalidParametersSafely() throws Exception {
        when(searchService.searchScreenings(eq(PROGRAM_ID), any())).thenReturn(
                new PageResponse<>(0, 20, 0, 0, List.of()));
        mockMvc.perform(get("/api/v1/programs/{programId}/screenings", PROGRAM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
        verify(searchService).searchScreenings(PROGRAM_ID,
                new ScreeningSearchParameters(null, null, null, null, null, null, 0, null));

        mockMvc.perform(get("/api/v1/programs/{programId}/screenings", PROGRAM_ID)
                        .param("fromDateTime", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST_PARAMETER"));
        mockMvc.perform(get("/api/v1/programs/{programId}/screenings", PROGRAM_ID)
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void publicProjectionSerializesOnlyThePublicAllowlist() throws Exception {
        when(searchService.viewScreening(SCREENING_ID)).thenReturn(publicResponse());

        mockMvc.perform(get("/api/v1/screenings/{screeningId}", SCREENING_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screeningId").value(SCREENING_ID.toString()))
                .andExpect(jsonPath("$.programId").value(PROGRAM_ID.toString()))
                .andExpect(jsonPath("$.filmTitle").value("Dark Night"))
                .andExpect(jsonPath("$.genre").value("Drama"))
                .andExpect(jsonPath("$.finalAuditoriumName").value("Main Hall"))
                .andExpect(jsonPath("$.candidateAuditoriumName").doesNotExist())
                .andExpect(jsonPath("$.submitter").doesNotExist())
                .andExpect(jsonPath("$.handler").doesNotExist())
                .andExpect(jsonPath("$.review").doesNotExist())
                .andExpect(jsonPath("$.state").doesNotExist())
                .andExpect(jsonPath("$.conditionalNotes").doesNotExist())
                .andExpect(jsonPath("$.rejectionReason").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(content().string(not(containsString("hash-never-exposed"))));
    }

    @Test
    void mixedFullAndPublicResultsAreRedactedPerScreening() throws Exception {
        when(searchService.searchScreenings(eq(PROGRAM_ID), any())).thenReturn(
                new PageResponse<>(0, 20, 2, 1, List.of(fullResponse(), publicResponse())));

        mockMvc.perform(get("/api/v1/programs/{programId}/screenings", PROGRAM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].state").value("REVIEWED"))
                .andExpect(jsonPath("$.content[0].candidateAuditoriumName").value("Candidate Hall"))
                .andExpect(jsonPath("$.content[0].submitter.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.content[0].review.numericScore").value(8.5))
                .andExpect(jsonPath("$.content[0].version").value(3))
                .andExpect(jsonPath("$.content[1].state").doesNotExist())
                .andExpect(jsonPath("$.content[1].candidateAuditoriumName").doesNotExist())
                .andExpect(jsonPath("$.content[1].submitter").doesNotExist())
                .andExpect(jsonPath("$.content[1].review").doesNotExist())
                .andExpect(jsonPath("$.content[1].version").doesNotExist())
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("hash-never-exposed"))));
    }

    @Test
    void directMissingDeletedAndConcealedCasesShareSafe404() throws Exception {
        when(searchService.viewScreening(SCREENING_ID)).thenThrow(new ResourceNotFoundException());

        mockMvc.perform(get("/api/v1/screenings/{screeningId}", SCREENING_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("The requested resource was not found."))
                .andExpect(content().string(not(containsString("Screening exists"))))
                .andExpect(content().string(not(containsString("SQL"))));
    }

    @Test
    void serviceValidationUsesSafe400Problem() throws Exception {
        when(searchService.searchScreenings(eq(PROGRAM_ID), any())).thenThrow(
                new InvalidInputException("INVALID_SCREENING_VIEW", "view must be GENERAL or TIMETABLE."));

        mockMvc.perform(get("/api/v1/programs/{programId}/screenings", PROGRAM_ID)
                        .param("view", "general"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SCREENING_VIEW"))
                .andExpect(jsonPath("$.detail").value("view must be GENERAL or TIMETABLE."));
    }

    private static PublicScreeningResponse publicResponse() {
        return new PublicScreeningResponse(SCREENING_ID, PROGRAM_ID, "Dark Night", "Drama",
                Instant.parse("2027-01-10T10:00:00Z"), Instant.parse("2027-01-10T12:00:00Z"),
                "Main Hall");
    }

    private static FullScreeningResponse fullResponse() {
        UserSummaryResponse user = new UserSummaryResponse(USER_ID, "alice", "Alice User");
        return new FullScreeningResponse(
                SCREENING_ID, PROGRAM_ID, "Dark Night", "Alice Bob", "Drama", 90,
                "Candidate Hall", null, Instant.parse("2027-01-10T10:00:00Z"),
                Instant.parse("2027-01-10T12:00:00Z"), ScreeningState.REVIEWED,
                null, null, null, user, user,
                new ScreeningReviewDetailResponse(UUID.randomUUID(), new java.math.BigDecimal("8.50"),
                        "Detailed comments", user, NOW), NOW, 3);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}

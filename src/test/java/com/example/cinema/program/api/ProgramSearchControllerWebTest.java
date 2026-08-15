package com.example.cinema.program.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import com.example.cinema.common.error.ResourceNotFoundException;
import com.example.cinema.common.infrastructure.CorrelationIdFilter;
import com.example.cinema.common.error.ProblemResponseWriter;
import com.example.cinema.common.ratelimit.InProcessRateLimiter;
import com.example.cinema.program.domain.ProgramRoleType;
import com.example.cinema.program.domain.ProgramState;
import com.example.cinema.program.service.ProgramLifecycleService;
import com.example.cinema.program.service.ProgramManagementService;
import com.example.cinema.search.visibility.SearchAndVisibilityService;
import com.example.cinema.user.authentication.CurrentUser;

@WebMvcTest(controllers = ProgramController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
        EntityTagParser.class,
        ApiProblemFactory.class,
        GlobalExceptionHandler.class,
        CorrelationIdFilter.class,
        ProgramSearchControllerWebTest.ClockConfiguration.class
})
class ProgramSearchControllerWebTest {

    private static final UUID PROGRAM_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID OTHER_PROGRAM_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant NOW = Instant.parse("2026-08-15T09:00:00Z");

    @Autowired MockMvc mockMvc;
    @MockitoBean ProgramManagementService managementService;
    @MockitoBean ProgramLifecycleService lifecycleService;
    @MockitoBean SearchAndVisibilityService searchService;
    @MockitoBean InProcessRateLimiter rateLimiter;
    @MockitoBean CurrentUser currentUser;
    @MockitoBean ProblemResponseWriter problemResponseWriter;

    @Test
    void mapsEverySearchParameterAndReturnsExplicitPageResponse() throws Exception {
        when(searchService.searchPrograms(any())).thenReturn(new PageResponse<>(
                2, 5, 11, 3, List.of(publicResponse())));

        mockMvc.perform(get("/api/v1/programs")
                        .param("name", " Fest ")
                        .param("description", " archive ")
                        .param("fromDate", "2027-01-01")
                        .param("toDate", "2027-12-31")
                        .param("filmTitle", " Film ")
                        .param("auditorium", " Hall ")
                        .param("direction", "DESC")
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(11))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].programmerDisplayNames[0]").value("Alice Programmer"))
                .andExpect(jsonPath("$.content[0].finalAuditoriumNames[0]").value("Main Hall"))
                .andExpect(jsonPath("$.items").doesNotExist());
        verify(searchService).searchPrograms(eq(new ProgramSearchParameters(
                " Fest ", " archive ", LocalDate.parse("2027-01-01"),
                LocalDate.parse("2027-12-31"), " Film ", " Hall ", "DESC", 2, 5)));
    }

    @Test
    void appliesControllerDefaultsWithoutInventingFilterValues() throws Exception {
        when(searchService.searchPrograms(any())).thenReturn(new PageResponse<>(
                0, 20, 0, 0, List.of()));

        mockMvc.perform(get("/api/v1/programs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());

        verify(searchService).searchPrograms(eq(new ProgramSearchParameters(
                null, null, null, null, null, null, null, 0, null)));
    }

    @Test
    void rejectsMalformedDatesAndInvalidPaginationSafely() throws Exception {
        mockMvc.perform(get("/api/v1/programs").param("fromDate", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST_PARAMETER"));
        mockMvc.perform(get("/api/v1/programs").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("page"));
        mockMvc.perform(get("/api/v1/programs").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
    }

    @Test
    void mapsServiceRangeMaximumAndDirectionValidationToClearSafeErrors() throws Exception {
        when(searchService.searchPrograms(any())).thenThrow(new InvalidInputException(
                "INVALID_PAGE_SIZE", "size must be between 1 and 100."));
        mockMvc.perform(get("/api/v1/programs").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PAGE_SIZE"))
                .andExpect(jsonPath("$.detail").value("size must be between 1 and 100."));

        reset(searchService);
        when(searchService.searchPrograms(any())).thenThrow(new InvalidInputException(
                "INVALID_SORT_DIRECTION", "direction must be ASC or DESC."));
        mockMvc.perform(get("/api/v1/programs").param("direction", "sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SORT_DIRECTION"));
    }

    @Test
    void publicProjectionNeverSerializesPrivateProgramFields() throws Exception {
        when(searchService.viewProgram(PROGRAM_ID)).thenReturn(publicResponse());

        mockMvc.perform(get("/api/v1/programs/{programId}", PROGRAM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.programId").value(PROGRAM_ID.toString()))
                .andExpect(jsonPath("$.name").value("Festival"))
                .andExpect(jsonPath("$.state").doesNotExist())
                .andExpect(jsonPath("$.creator").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.roles").doesNotExist())
                .andExpect(jsonPath("$.screenings").doesNotExist())
                .andExpect(content().string(not(containsString("hash-never-exposed"))));
    }

    @Test
    void fullProjectionContainsManagedDetailsAndNoPasswordData() throws Exception {
        when(searchService.viewProgram(PROGRAM_ID)).thenReturn(fullResponse());

        mockMvc.perform(get("/api/v1/programs/{programId}", PROGRAM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CREATED"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.creator.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.roles[0].role").value("PROGRAMMER"))
                .andExpect(jsonPath("$.screenings.activeScreeningCount").value(3))
                .andExpect(jsonPath("$.screenings.scheduledScreeningCount").value(1))
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("hash-never-exposed"))));
    }

    @Test
    void directMissingAndConcealedProgramsShareTheSameSafe404() throws Exception {
        when(searchService.viewProgram(PROGRAM_ID)).thenThrow(new ResourceNotFoundException());
        when(searchService.viewProgram(OTHER_PROGRAM_ID)).thenThrow(new ResourceNotFoundException());

        mockMvc.perform(get("/api/v1/programs/{programId}", PROGRAM_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("The requested resource was not found."))
                .andExpect(content().string(not(containsString("Program exists"))))
                .andExpect(content().string(not(containsString("state"))))
                .andExpect(content().string(not(containsString("SQL"))));

        mockMvc.perform(get("/api/v1/programs/{programId}", OTHER_PROGRAM_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("The requested resource was not found."))
                .andExpect(content().string(not(containsString("Program exists"))))
                .andExpect(content().string(not(containsString("state"))))
                .andExpect(content().string(not(containsString("SQL"))));
    }

    @Test
    void mixedPageSerializesPublicAndFullElementsWithPerProgramRedaction() throws Exception {
        when(searchService.searchPrograms(any())).thenReturn(new PageResponse<>(
                0, 20, 2, 1, List.of(fullResponse(), publicResponse())));

        mockMvc.perform(get("/api/v1/programs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].state").value("CREATED"))
                .andExpect(jsonPath("$.content[0].creator").exists())
                .andExpect(jsonPath("$.content[0].version").value(4))
                .andExpect(jsonPath("$.content[0].roles").isArray())
                .andExpect(jsonPath("$.content[0].screenings").exists())
                .andExpect(jsonPath("$.content[1].state").doesNotExist())
                .andExpect(jsonPath("$.content[1].creator").doesNotExist())
                .andExpect(jsonPath("$.content[1].version").doesNotExist())
                .andExpect(jsonPath("$.content[1].roles").doesNotExist())
                .andExpect(jsonPath("$.content[1].screenings").doesNotExist());
    }

    private static PublicProgramResponse publicResponse() {
        return new PublicProgramResponse(
                PROGRAM_ID, "Festival", "Description",
                LocalDate.parse("2027-01-01"), LocalDate.parse("2027-02-01"),
                List.of("Alice Programmer"), List.of("Main Hall"));
    }

    private static FullProgramResponse fullResponse() {
        return new FullProgramResponse(
                PROGRAM_ID, "Festival", "Description",
                LocalDate.parse("2027-01-01"), LocalDate.parse("2027-02-01"),
                List.of("Alice Programmer"), List.of("Main Hall"),
                ProgramState.CREATED, NOW, 4,
                new UserSummaryResponse(USER_ID, "alice", "Alice Programmer"),
                List.of(new ProgramRoleSummaryResponse(
                        USER_ID, "alice", "Alice Programmer", ProgramRoleType.PROGRAMMER, NOW, USER_ID)),
                new ProgramScreeningSummaryResponse(
                        3, 1, "/api/v1/programs/" + PROGRAM_ID + "/screenings"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}

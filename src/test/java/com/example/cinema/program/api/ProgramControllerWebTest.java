package com.example.cinema.program.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.cinema.common.api.EntityTagParser;
import com.example.cinema.common.error.ApiProblemFactory;
import com.example.cinema.common.error.CreatorRoleRequiredException;
import com.example.cinema.common.error.ForbiddenException;
import com.example.cinema.common.error.GlobalExceptionHandler;
import com.example.cinema.common.error.IdempotencyConflictException;
import com.example.cinema.common.error.InvalidInputException;
import com.example.cinema.common.error.InvalidStateException;
import com.example.cinema.common.error.OptimisticConcurrencyConflictException;
import com.example.cinema.common.error.ProgramNameExistsException;
import com.example.cinema.common.error.ProgramRoleExistsException;
import com.example.cinema.common.error.ProgramRoleNotFoundException;
import com.example.cinema.common.error.ProblemResponseWriter;
import com.example.cinema.common.error.RoleConflictException;
import com.example.cinema.common.infrastructure.CorrelationIdFilter;
import com.example.cinema.common.ratelimit.InProcessRateLimiter;
import com.example.cinema.common.ratelimit.RateLimitDecision;
import com.example.cinema.program.domain.ProgramRoleType;
import com.example.cinema.program.domain.ProgramState;
import com.example.cinema.program.service.ProgramCommandResult;
import com.example.cinema.program.service.ProgramManagementService;
import com.example.cinema.user.authentication.CurrentUser;

@WebMvcTest(controllers = ProgramController.class)
@Import({
        EntityTagParser.class,
        ApiProblemFactory.class,
        GlobalExceptionHandler.class,
        CorrelationIdFilter.class,
        ProgramControllerWebTest.ClockConfiguration.class
})
class ProgramControllerWebTest {

    private static final UUID PROGRAM_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID ACTOR_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TARGET_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant NOW = Instant.parse("2026-08-15T09:00:00Z");

    @Autowired MockMvc mockMvc;
    @MockitoBean ProgramManagementService service;
    @MockitoBean InProcessRateLimiter rateLimiter;
    @MockitoBean CurrentUser currentUser;
    @MockitoBean ProblemResponseWriter problemResponseWriter;

    @BeforeEach
    void permitRateLimitedRoutes() {
        when(currentUser.optional()).thenReturn(Optional.empty());
        when(rateLimiter.tryAcquire(any(), any())).thenReturn(RateLimitDecision.permit());
    }

    @Test
    void createsProgramWithLocationEtagAndDtoOnlyResponse() throws Exception {
        when(service.create(any(), eq("create-key")))
                .thenReturn(new ProgramCommandResult<>(201, programResponse(0), false));

        mockMvc.perform(post("/api/v1/programs")
                        .with(user("alice"))
                        .header("Idempotency-Key", "create-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/programs/" + PROGRAM_ID))
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.programId").value(PROGRAM_ID.toString()))
                .andExpect(jsonPath("$.state").value("CREATED"))
                .andExpect(jsonPath("$.creator.userId").value(ACTOR_ID.toString()))
                .andExpect(jsonPath("$.creator.fullName").value("Alice Programmer"))
                .andExpect(jsonPath("$.creator.passwordHashOrExternalReference").doesNotExist());
    }

    @ParameterizedTest
    @MethodSource("invalidCreationBodies")
    void rejectsEveryMissingBlankOrMalformedCreationField(String body) throws Exception {
        mockMvc.perform(post("/api/v1/programs")
                        .with(user("alice"))
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(
                        org.hamcrest.Matchers.anyOf(
                                org.hamcrest.Matchers.is("VALIDATION_FAILED"),
                                org.hamcrest.Matchers.is("MALFORMED_REQUEST"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Exception"))));
    }

    @Test
    void rejectsInvalidDateRangeAndMissingIdempotencyKeySafely() throws Exception {
        when(service.create(any(), eq("key"))).thenThrow(new InvalidInputException(
                "INVALID_DATE_RANGE", "endDate must be greater than or equal to startDate."));

        mockMvc.perform(post("/api/v1/programs")
                        .with(user("alice"))
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Festival","description":"Description",
                                 "startDate":"2027-02-02","endDate":"2027-02-01"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_DATE_RANGE"));

        mockMvc.perform(post("/api/v1/programs")
                        .with(user("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_REQUIRED_HEADER"));
    }

    @Test
    void returnsStoredCreationOnReplayAndReportsPayloadMismatch() throws Exception {
        when(service.create(any(), eq("replay-key")))
                .thenReturn(new ProgramCommandResult<>(201, programResponse(0), true));
        mockMvc.perform(post("/api/v1/programs")
                        .with(user("alice"))
                        .header("Idempotency-Key", "replay-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.programId").value(PROGRAM_ID.toString()));

        when(service.create(any(), eq("mismatch-key"))).thenThrow(new IdempotencyConflictException(
                "IDEMPOTENCY_KEY_REUSED",
                "The Idempotency-Key was already used with different request content.",
                false));
        mockMvc.perform(post("/api/v1/programs")
                        .with(user("alice"))
                        .header("Idempotency-Key", "mismatch-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void updatesDetailsWithIfMatchAndMapsAuthorizationStateAndConcurrencyErrors() throws Exception {
        when(service.update(eq(PROGRAM_ID), eq(2L), any(), eq("update-key")))
                .thenReturn(new ProgramCommandResult<>(200, programResponse(3), false));
        mockMvc.perform(patch("/api/v1/programs/{id}", PROGRAM_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .header("Idempotency-Key", "update-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Updated\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"3\""))
                .andExpect(jsonPath("$.version").value(3));
        verify(service).update(eq(PROGRAM_ID), eq(2L), any(), eq("update-key"));

        when(service.update(eq(PROGRAM_ID), eq(2L), any(), eq("forbidden")))
                .thenThrow(new ForbiddenException());
        assertUpdateError("forbidden", 403, "FORBIDDEN");
        when(service.update(eq(PROGRAM_ID), eq(2L), any(), eq("announced")))
                .thenThrow(new InvalidStateException());
        assertUpdateError("announced", 409, "INVALID_STATE");
        when(service.update(eq(PROGRAM_ID), eq(2L), any(), eq("stale")))
                .thenThrow(new OptimisticConcurrencyConflictException());
        assertUpdateError("stale", 409, "CONCURRENT_MODIFICATION");
    }

    @Test
    void rejectsMissingOrMalformedIfMatch() throws Exception {
        mockMvc.perform(patch("/api/v1/programs/{id}", PROGRAM_ID)
                        .with(user("alice"))
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Updated\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_REQUIRED_HEADER"));

        mockMvc.perform(patch("/api/v1/programs/{id}", PROGRAM_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "2")
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Updated\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_IF_MATCH"));
    }

    @ParameterizedTest
    @MethodSource("readOnlyUpdateBodies")
    void rejectsEveryReadOnlyProgramFieldOnPatch(String body) throws Exception {
        mockMvc.perform(patch("/api/v1/programs/{id}", PROGRAM_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
        verify(service, org.mockito.Mockito.never()).update(
                any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test
    void requiresIdempotencyKeyOnUpdateAndRoleAddition() throws Exception {
        mockMvc.perform(patch("/api/v1/programs/{id}", PROGRAM_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Updated\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_REQUIRED_HEADER"));

        mockMvc.perform(post("/api/v1/programs/{id}/roles", PROGRAM_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + TARGET_ID + "\",\"role\":\"STAFF\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_REQUIRED_HEADER"));
    }

    @Test
    void addsManagedRoleAndRemovesRoleWithUpdatedProgramEtag() throws Exception {
        ProgramRoleResponse role = new ProgramRoleResponse(
                PROGRAM_ID, TARGET_ID, "Bob Target", ProgramRoleType.PROGRAMMER,
                NOW, ACTOR_ID, 5);
        when(service.addRole(eq(PROGRAM_ID), eq(4L), any(), eq("role-key")))
                .thenReturn(new ProgramCommandResult<>(201, role, false));

        mockMvc.perform(post("/api/v1/programs/{id}/roles", PROGRAM_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"4\"")
                        .header("Idempotency-Key", "role-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + TARGET_ID + "\",\"role\":\"PROGRAMMER\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"5\""))
                .andExpect(jsonPath("$.role").value("PROGRAMMER"))
                .andExpect(jsonPath("$.fullName").value("Bob Target"));

        when(service.removeRole(PROGRAM_ID, TARGET_ID, 5)).thenReturn(6L);
        mockMvc.perform(delete("/api/v1/programs/{id}/roles/{userId}", PROGRAM_ID, TARGET_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"5\""))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.ETAG, "\"6\""))
                .andExpect(content().string(""));
    }

    @Test
    void deletesCreatedProgramWithNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/programs/{id}", PROGRAM_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(service).delete(PROGRAM_ID, 1);
    }

    @Test
    void mapsProgramAndRoleConflictsToStableSafeProblems() throws Exception {
        when(service.create(any(), eq("duplicate-name"))).thenThrow(new ProgramNameExistsException());
        mockMvc.perform(post("/api/v1/programs")
                        .with(user("alice"))
                        .header("Idempotency-Key", "duplicate-name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROGRAM_NAME_EXISTS"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("SQL"))));

        when(service.addRole(eq(PROGRAM_ID), eq(2L), any(), eq("duplicate-role")))
                .thenThrow(new ProgramRoleExistsException());
        assertRoleAddError("duplicate-role", "PROGRAM_ROLE_EXISTS");
        when(service.addRole(eq(PROGRAM_ID), eq(2L), any(), eq("conflicting-role")))
                .thenThrow(new RoleConflictException());
        assertRoleAddError("conflicting-role", "ROLE_CONFLICT");

        org.mockito.Mockito.doThrow(new ProgramRoleNotFoundException())
                .when(service).removeRole(PROGRAM_ID, TARGET_ID, 2);
        assertRoleRemovalError(404, "PROGRAM_ROLE_NOT_FOUND");
        org.mockito.Mockito.doThrow(new CreatorRoleRequiredException())
                .when(service).removeRole(PROGRAM_ID, TARGET_ID, 2);
        assertRoleRemovalError(409, "CREATOR_PROGRAMMER_REQUIRED");
    }

    private void assertUpdateError(String key, int expectedStatus, String errorCode) throws Exception {
        mockMvc.perform(patch("/api/v1/programs/{id}", PROGRAM_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Updated\"}"))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.errorCode").value(errorCode));
    }

    private void assertRoleAddError(String key, String errorCode) throws Exception {
        mockMvc.perform(post("/api/v1/programs/{id}/roles", PROGRAM_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + TARGET_ID + "\",\"role\":\"STAFF\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(errorCode));
    }

    private void assertRoleRemovalError(int expectedStatus, String errorCode) throws Exception {
        mockMvc.perform(delete("/api/v1/programs/{id}/roles/{userId}", PROGRAM_ID, TARGET_ID)
                        .with(user("alice"))
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.errorCode").value(errorCode));
    }

    private static ProgramDetailResponse programResponse(long version) {
        return new ProgramDetailResponse(
                PROGRAM_ID,
                "Festival",
                "Description",
                LocalDate.parse("2027-01-01"),
                LocalDate.parse("2027-02-01"),
                ProgramState.CREATED,
                NOW,
                version,
                new UserSummaryResponse(ACTOR_ID, "alice", "Alice Programmer"));
    }

    private static String validCreateBody() {
        return """
                {"name":"Festival","description":"Description",
                 "startDate":"2027-01-01","endDate":"2027-02-01"}
                """;
    }

    private static Stream<String> invalidCreationBodies() {
        return Stream.of(
                "{\"description\":\"Description\",\"startDate\":\"2027-01-01\",\"endDate\":\"2027-02-01\"}",
                "{\"name\":\"Festival\",\"startDate\":\"2027-01-01\",\"endDate\":\"2027-02-01\"}",
                "{\"name\":\"Festival\",\"description\":\"Description\",\"endDate\":\"2027-02-01\"}",
                "{\"name\":\"Festival\",\"description\":\"Description\",\"startDate\":\"2027-01-01\"}",
                "{\"name\":\"   \",\"description\":\"Description\",\"startDate\":\"2027-01-01\",\"endDate\":\"2027-02-01\"}",
                "{\"name\":\"Festival\",\"description\":\"   \",\"startDate\":\"2027-01-01\",\"endDate\":\"2027-02-01\"}",
                "{\"name\":\"Festival\",\"description\":\"Description\",\"startDate\":\"\",\"endDate\":\"2027-02-01\"}",
                "{\"name\":\"Festival\",\"description\":\"Description\",\"startDate\":\"2027-01-01\",\"endDate\":\"\"}");
    }

    private static Stream<String> readOnlyUpdateBodies() {
        return Stream.of(
                "{\"state\":\"ANNOUNCED\"}",
                "{\"creator\":{\"userId\":\"" + ACTOR_ID + "\"}}",
                "{\"creatorUserId\":\"" + ACTOR_ID + "\"}",
                "{\"createdAt\":\"2026-08-15T09:00:00Z\"}",
                "{\"roles\":[]}",
                "{\"programId\":\"" + PROGRAM_ID + "\"}",
                "{\"id\":\"" + PROGRAM_ID + "\"}",
                "{\"userId\":\"" + ACTOR_ID + "\"}",
                "{\"version\":3}");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}

package com.example.cinema.common.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.cinema.common.api.InfrastructureWebSecurityTest.InfrastructureTestController;
import com.example.cinema.common.config.CinemaProperties;
import com.example.cinema.common.error.ApiProblemFactory;
import com.example.cinema.common.error.ForbiddenException;
import com.example.cinema.common.error.GlobalExceptionHandler;
import com.example.cinema.common.error.ProblemResponseWriter;
import com.example.cinema.common.infrastructure.CorrelationIdFilter;
import com.example.cinema.common.ratelimit.InProcessRateLimiter;
import com.example.cinema.common.ratelimit.RateLimitFilter;
import com.example.cinema.user.authentication.AuthenticatedUserIdentity;
import com.example.cinema.user.authentication.CmsAuthenticationProvider;
import com.example.cinema.user.authentication.CurrentUser;
import com.example.cinema.user.authentication.RestAccessDeniedHandler;
import com.example.cinema.user.authentication.RestAuthenticationEntryPoint;
import com.example.cinema.user.authentication.SecurityConfiguration;
import com.example.cinema.user.authentication.SecurityContextCurrentUser;
import com.example.cinema.user.authentication.SharedDatabaseAuthenticationAdapter;
import com.example.cinema.user.domain.UserEntity;
import com.example.cinema.user.repository.UserRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@WebMvcTest(controllers = InfrastructureTestController.class)
@ImportAutoConfiguration({
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class
})
@Import({
        InfrastructureWebSecurityTest.InfrastructureTestConfiguration.class,
        InfrastructureTestController.class,
        SecurityConfiguration.class,
        SharedDatabaseAuthenticationAdapter.class,
        CmsAuthenticationProvider.class,
        SecurityContextCurrentUser.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        CorrelationIdFilter.class,
        ApiProblemFactory.class,
        ProblemResponseWriter.class,
        GlobalExceptionHandler.class,
        InProcessRateLimiter.class,
        RateLimitFilter.class
})
class InfrastructureWebSecurityTest {

    @Autowired MockMvc mockMvc;
    @Autowired PasswordEncoder passwordEncoder;
    @MockitoBean UserRepository userRepository;

    @BeforeEach
    void user() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new UserEntity(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "alice", passwordEncoder.encode("correct"), "Alice Example")));
    }

    @Test
    void permitsAnonymousPublicGetAndProtectsMutation() throws Exception {
        mockMvc.perform(get("/api/v1/programs"))
                .andExpect(status().isOk())
                .andExpect(content().string("public"));

        mockMvc.perform(post("/api/v1/test/protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Basic realm=\"cinema-management\""))
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void acceptsValidBasicAuthenticationAndRejectsInvalidCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/test/protected").with(httpBasic("  ALICE ", "correct")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.userId").value("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

        mockMvc.perform(post("/api/v1/test/protected").with(httpBasic("alice", "wrong")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void distinguishesAuthenticatedForbiddenFromUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/programs/forbidden").with(httpBasic("alice", "correct")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void formatsValidationFailuresWithoutInternalDetails() throws Exception {
        mockMvc.perform(post("/api/v1/test/validation")
                        .with(httpBasic("alice", "correct"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors", hasSize(1)))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Exception"))));
    }

    @Test
    void returnsControlledGenericErrorWithoutSqlStackOrExceptionMessage() throws Exception {
        mockMvc.perform(post("/api/v1/test/failure").with(httpBasic("alice", "correct")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("cms_user"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SQL"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("stackTrace"))));
    }

    @Test
    void propagatesValidCorrelationIdAndReplacesInvalidOne() throws Exception {
        mockMvc.perform(get("/api/v1/programs").header("X-Correlation-ID", "request-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", "request-123"));

        String replacement = mockMvc.perform(get("/api/v1/programs").header("X-Correlation-ID", "invalid id"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("X-Correlation-ID");
        org.assertj.core.api.Assertions.assertThat(replacement).matches("[0-9a-f-]{36}");
    }

    @RestController
    static class InfrastructureTestController {
        private final CurrentUser currentUser;
        InfrastructureTestController(CurrentUser currentUser) { this.currentUser = currentUser; }

        @GetMapping("/api/v1/programs")
        String publicPrograms() { return "public"; }

        @PostMapping("/api/v1/test/protected")
        Map<String, Object> protectedCommand() {
            AuthenticatedUserIdentity user = currentUser.require();
            return Map.of("userId", user.userId(), "username", user.username());
        }

        @GetMapping("/api/v1/programs/forbidden")
        void forbidden() { throw new ForbiddenException(); }

        @PostMapping("/api/v1/test/validation")
        void validation(@Valid @RequestBody NameRequest request) { }

        @PostMapping("/api/v1/test/failure")
        void failure() { throw new IllegalStateException("SQL table cms_user failed"); }
    }

    record NameRequest(@NotBlank String name) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class InfrastructureTestConfiguration {
        @Bean Clock clock() {
            return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        }

        @Bean CinemaProperties cinemaProperties() {
            var policy = new CinemaProperties.Policy(100, Duration.ofMinutes(1));
            return new CinemaProperties(new CinemaProperties.Pagination(20, 100),
                    new CinemaProperties.RateLimit(policy, policy, policy, policy, 1000, Duration.ofMinutes(5)),
                    new CinemaProperties.Idempotency(Duration.ofHours(24)));
        }
    }
}

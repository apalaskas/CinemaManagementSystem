package com.example.cinema.user.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.example.cinema.user.domain.UserEntity;
import com.example.cinema.user.repository.UserRepository;

class SharedDatabaseAuthenticationAdapterTest {

    @Test
    void normalizesUsernameVerifiesBcryptAndReturnsNoCredentialMaterial() {
        UserRepository repository = mock(UserRepository.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity(userId, "alice", encoder.encode("correct"), "Alice Example");
        when(repository.findByUsername("alice")).thenReturn(Optional.of(user));
        AuthenticationAdapter adapter = new SharedDatabaseAuthenticationAdapter(repository, encoder);

        Optional<AuthenticatedUserIdentity> result = adapter.authenticate("  ALICE ", "correct");

        assertThat(result).contains(new AuthenticatedUserIdentity(userId, "alice", "Alice Example"));
        assertThat(AuthenticatedUserIdentity.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("passwordHashOrExternalReference", "password", "credentials");
        verify(repository).findByUsername("alice");
    }

    @Test
    void rejectsInvalidPassword() {
        UserRepository repository = mock(UserRepository.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        when(repository.findByUsername("alice")).thenReturn(Optional.of(new UserEntity(
                UUID.randomUUID(), "alice", encoder.encode("correct"), "Alice Example")));

        assertThat(new SharedDatabaseAuthenticationAdapter(repository, encoder).authenticate("alice", "wrong"))
                .isEmpty();
    }
}

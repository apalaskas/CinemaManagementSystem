package com.example.cinema.user.domain;

import static com.example.cinema.common.domain.DomainAssertions.requireNonBlank;
import static java.util.Objects.requireNonNull;

import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "cms_user")
public class UserEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "username", nullable = false, unique = true, length = 255)
    private String username;

    @Column(name = "password_hash_or_external_reference", nullable = false, length = 255)
    private String passwordHashOrExternalReference;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    protected UserEntity() {
    }

    public UserEntity(UUID id, String username, String passwordHashOrExternalReference, String fullName) {
        this.id = requireNonNull(id, "id");
        this.username = requireNonBlank(username, "username");
        this.passwordHashOrExternalReference = requireNonBlank(
                passwordHashOrExternalReference, "passwordHashOrExternalReference");
        this.fullName = requireNonBlank(fullName, "fullName");
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHashOrExternalReference() {
        return passwordHashOrExternalReference;
    }

    public String getFullName() {
        return fullName;
    }
}

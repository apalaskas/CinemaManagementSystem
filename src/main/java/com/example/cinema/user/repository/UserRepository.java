package com.example.cinema.user.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.example.cinema.user.domain.UserEntity;

public interface UserRepository extends Repository<UserEntity, UUID> {

    Optional<UserEntity> findById(UUID id);

    Optional<UserEntity> findByUsername(String username);
}

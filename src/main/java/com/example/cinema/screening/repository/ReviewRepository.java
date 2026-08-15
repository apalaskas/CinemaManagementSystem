package com.example.cinema.screening.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.cinema.screening.domain.ReviewEntity;

public interface ReviewRepository extends JpaRepository<ReviewEntity, UUID> {

    @Query("select count(r) > 0 from ReviewEntity r where r.screening.id = :screeningId")
    boolean existsByScreeningId(@Param("screeningId") UUID screeningId);

    @Query("select r from ReviewEntity r where r.screening.id = :screeningId")
    Optional<ReviewEntity> findByScreeningId(@Param("screeningId") UUID screeningId);

    @Query("select r from ReviewEntity r join fetch r.staff where r.screening.id in :screeningIds")
    List<ReviewEntity> findAllWithStaffByScreeningIds(@Param("screeningIds") List<UUID> screeningIds);
}

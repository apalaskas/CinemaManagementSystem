package com.example.cinema.program.repository;

import java.util.Optional;
import java.util.UUID;

import com.example.cinema.program.domain.ProgramEntity;

public interface ProgramSearchRepository {

    ProgramSearchPage searchVisible(
            ProgramSearchCriteria criteria,
            UUID requesterUserId,
            int page,
            int size);

    Optional<ProgramEntity> findVisibleById(UUID programId, UUID requesterUserId);
}

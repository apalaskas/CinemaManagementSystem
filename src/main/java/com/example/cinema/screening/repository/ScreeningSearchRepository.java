package com.example.cinema.screening.repository;

import java.util.Optional;
import java.util.UUID;

import com.example.cinema.program.domain.ProgramRoleType;
import com.example.cinema.screening.domain.ScreeningEntity;

public interface ScreeningSearchRepository {

    ScreeningSearchPage searchVisible(UUID programId, ScreeningSearchCriteria criteria,
            UUID requesterUserId, ProgramRoleType requesterRole, int page, int size);

    Optional<ScreeningEntity> findVisibleDetail(
            UUID screeningId, UUID requesterUserId, ProgramRoleType requesterRole);
}

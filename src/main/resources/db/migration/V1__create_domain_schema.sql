CREATE TABLE cms_user (
    user_id BINARY(16) NOT NULL,
    username VARCHAR(255) NOT NULL,
    password_hash_or_external_reference VARCHAR(255) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    CONSTRAINT pk_cms_user PRIMARY KEY (user_id),
    CONSTRAINT uk_cms_user_username UNIQUE (username),
    CONSTRAINT chk_cms_user_username_nonblank CHECK (CHAR_LENGTH(TRIM(username)) > 0),
    CONSTRAINT chk_cms_user_credential_nonblank CHECK (CHAR_LENGTH(TRIM(password_hash_or_external_reference)) > 0),
    CONSTRAINT chk_cms_user_full_name_nonblank CHECK (CHAR_LENGTH(TRIM(full_name)) > 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE program (
    program_id BINARY(16) NOT NULL,
    creator_user_id BINARY(16) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    created_at DATETIME(6) NOT NULL,
    state VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT pk_program PRIMARY KEY (program_id),
    CONSTRAINT uk_program_name UNIQUE (name),
    CONSTRAINT fk_program_creator FOREIGN KEY (creator_user_id) REFERENCES cms_user (user_id),
    CONSTRAINT chk_program_name_nonblank CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_program_description_nonblank CHECK (CHAR_LENGTH(TRIM(description)) > 0),
    CONSTRAINT chk_program_date_range CHECK (end_date >= start_date),
    CONSTRAINT chk_program_state CHECK (
        state IN ('CREATED', 'SUBMISSION', 'ASSIGNMENT', 'REVIEW', 'SCHEDULING',
                  'FINAL_PUBLICATION', 'DECISION', 'ANNOUNCED')
    ),
    CONSTRAINT chk_program_version CHECK (version >= 0),
    INDEX idx_program_creator (creator_user_id),
    INDEX idx_program_state_start_name (state, start_date, name, program_id),
    INDEX idx_program_end_date (end_date, program_id),
    FULLTEXT INDEX idx_program_name_search (name),
    FULLTEXT INDEX idx_program_description_search (description)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE program_role (
    program_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    role VARCHAR(32) NOT NULL,
    assigned_at DATETIME(6) NOT NULL,
    assigned_by_user_id BINARY(16) NULL,
    CONSTRAINT pk_program_role PRIMARY KEY (program_id, user_id),
    CONSTRAINT fk_program_role_program FOREIGN KEY (program_id)
        REFERENCES program (program_id) ON DELETE CASCADE,
    CONSTRAINT fk_program_role_user FOREIGN KEY (user_id) REFERENCES cms_user (user_id),
    CONSTRAINT fk_program_role_assigner FOREIGN KEY (assigned_by_user_id)
        REFERENCES cms_user (user_id) ON DELETE SET NULL,
    CONSTRAINT chk_program_role_value CHECK (role IN ('PROGRAMMER', 'STAFF', 'SUBMITTER')),
    INDEX idx_program_role_user (user_id, program_id),
    INDEX idx_program_role_type (program_id, role, user_id),
    INDEX idx_program_role_assigner (assigned_by_user_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE screening (
    screening_id BINARY(16) NOT NULL,
    program_id BINARY(16) NOT NULL,
    submitter_user_id BINARY(16) NOT NULL,
    handler_user_id BINARY(16) NULL,
    film_title VARCHAR(255) NULL,
    cast_text TEXT NULL,
    genre VARCHAR(255) NULL,
    duration_minutes INTEGER NULL,
    candidate_auditorium_name VARCHAR(255) NULL,
    final_auditorium_name VARCHAR(255) NULL,
    start_time DATETIME(6) NULL,
    end_time DATETIME(6) NULL,
    state VARCHAR(32) NOT NULL,
    conditional_notes TEXT NULL,
    final_submitted_at DATETIME(6) NULL,
    rejection_reason TEXT NULL,
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    version BIGINT NOT NULL,
    CONSTRAINT pk_screening PRIMARY KEY (screening_id),
    CONSTRAINT fk_screening_program FOREIGN KEY (program_id)
        REFERENCES program (program_id) ON DELETE CASCADE,
    CONSTRAINT fk_screening_submitter FOREIGN KEY (submitter_user_id) REFERENCES cms_user (user_id),
    CONSTRAINT fk_screening_handler FOREIGN KEY (handler_user_id) REFERENCES cms_user (user_id),
    CONSTRAINT chk_screening_state CHECK (
        state IN ('CREATED', 'SUBMITTED', 'REVIEWED', 'APPROVED', 'SCHEDULED', 'REJECTED')
    ),
    CONSTRAINT chk_screening_duration CHECK (duration_minutes IS NULL OR duration_minutes > 0),
    CONSTRAINT chk_screening_interval_order CHECK (
        start_time IS NULL OR end_time IS NULL OR end_time > start_time
    ),
    CONSTRAINT chk_screening_interval_duration CHECK (
        start_time IS NULL OR end_time IS NULL OR duration_minutes IS NULL
        OR TIMESTAMPDIFF(MICROSECOND, start_time, end_time) >= duration_minutes * 60000000
    ),
    CONSTRAINT chk_screening_film_title_nonblank CHECK (
        film_title IS NULL OR CHAR_LENGTH(TRIM(film_title)) > 0
    ),
    CONSTRAINT chk_screening_cast_nonblank CHECK (
        cast_text IS NULL OR CHAR_LENGTH(TRIM(cast_text)) > 0
    ),
    CONSTRAINT chk_screening_genre_nonblank CHECK (
        genre IS NULL OR CHAR_LENGTH(TRIM(genre)) > 0
    ),
    CONSTRAINT chk_screening_candidate_nonblank CHECK (
        candidate_auditorium_name IS NULL OR CHAR_LENGTH(TRIM(candidate_auditorium_name)) > 0
    ),
    CONSTRAINT chk_screening_final_auditorium_nonblank CHECK (
        final_auditorium_name IS NULL OR CHAR_LENGTH(TRIM(final_auditorium_name)) > 0
    ),
    CONSTRAINT chk_screening_version CHECK (version >= 0),
    INDEX idx_screening_program_state (program_id, state, deleted_at, screening_id),
    INDEX idx_screening_owner (program_id, submitter_user_id, deleted_at, screening_id),
    INDEX idx_screening_handler (handler_user_id, deleted_at, screening_id),
    INDEX idx_screening_timetable (program_id, start_time, screening_id),
    INDEX idx_screening_general_list (program_id, genre, film_title, screening_id),
    INDEX idx_screening_scheduling_conflict (
        state, deleted_at, final_auditorium_name, start_time, end_time, screening_id
    ),
    FULLTEXT INDEX idx_screening_film_title_search (film_title),
    FULLTEXT INDEX idx_screening_cast_search (cast_text),
    FULLTEXT INDEX idx_screening_genre_search (genre)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE review (
    review_id BINARY(16) NOT NULL,
    screening_id BINARY(16) NOT NULL,
    staff_user_id BINARY(16) NOT NULL,
    numeric_score DECIMAL(4, 2) NOT NULL,
    detailed_comments TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_review PRIMARY KEY (review_id),
    CONSTRAINT uk_review_screening UNIQUE (screening_id),
    CONSTRAINT fk_review_screening FOREIGN KEY (screening_id)
        REFERENCES screening (screening_id) ON DELETE CASCADE,
    CONSTRAINT fk_review_staff FOREIGN KEY (staff_user_id) REFERENCES cms_user (user_id),
    CONSTRAINT chk_review_score CHECK (numeric_score >= 0.00 AND numeric_score <= 10.00),
    CONSTRAINT chk_review_comments_nonblank CHECK (CHAR_LENGTH(TRIM(detailed_comments)) > 0),
    INDEX idx_review_staff (staff_user_id, created_at, review_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE audit_log (
    audit_id BINARY(16) NOT NULL,
    actor_user_id BINARY(16) NULL,
    action_type VARCHAR(100) NOT NULL,
    target_entity_type VARCHAR(100) NOT NULL,
    target_entity_id BINARY(16) NOT NULL,
    old_value JSON NULL,
    new_value JSON NULL,
    reason TEXT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_audit_log PRIMARY KEY (audit_id),
    CONSTRAINT fk_audit_log_actor FOREIGN KEY (actor_user_id)
        REFERENCES cms_user (user_id) ON DELETE SET NULL,
    CONSTRAINT chk_audit_action_nonblank CHECK (CHAR_LENGTH(TRIM(action_type)) > 0),
    CONSTRAINT chk_audit_target_type_nonblank CHECK (CHAR_LENGTH(TRIM(target_entity_type)) > 0),
    INDEX idx_audit_actor_created (actor_user_id, created_at, audit_id),
    INDEX idx_audit_target_created (target_entity_type, target_entity_id, created_at, audit_id),
    INDEX idx_audit_action_created (action_type, created_at, audit_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

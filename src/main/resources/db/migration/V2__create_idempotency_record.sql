CREATE TABLE idempotency_record (
    idempotency_record_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    operation VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash BINARY(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    response_status INTEGER NULL,
    response_body LONGTEXT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_idempotency_record PRIMARY KEY (idempotency_record_id),
    CONSTRAINT uk_idempotency_user_operation_key UNIQUE (user_id, operation, idempotency_key),
    CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id) REFERENCES cms_user (user_id),
    CONSTRAINT chk_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT chk_idempotency_response CHECK (
        (status = 'IN_PROGRESS' AND response_status IS NULL AND response_body IS NULL)
        OR (status = 'COMPLETED' AND response_status BETWEEN 200 AND 299 AND response_body IS NOT NULL)
    ),
    CONSTRAINT chk_idempotency_expiry CHECK (expires_at > created_at),
    INDEX idx_idempotency_expiry (expires_at, idempotency_record_id),
    INDEX idx_idempotency_status_expiry (status, expires_at, idempotency_record_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

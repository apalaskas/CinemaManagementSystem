DELETE older
FROM idempotency_record older
JOIN idempotency_record newer
  ON newer.user_id = older.user_id
 AND newer.idempotency_key = older.idempotency_key
 AND (
      newer.created_at > older.created_at
      OR (
          newer.created_at = older.created_at
          AND newer.idempotency_record_id > older.idempotency_record_id
      )
 );

ALTER TABLE idempotency_record
    DROP INDEX uk_idempotency_user_operation_key,
    ADD CONSTRAINT uk_idempotency_user_key UNIQUE (user_id, idempotency_key);

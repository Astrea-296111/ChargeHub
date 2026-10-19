-- Append-only evidence and durable, fenced finance operations; no updates to cash or bills.
ALTER TABLE refund_order ADD COLUMN retry_attempt INT NOT NULL DEFAULT 0;
ALTER TABLE reconcile_item ADD COLUMN channel_snapshot JSON NULL;
ALTER TABLE reconcile_batch ADD COLUMN source_complete TINYINT NOT NULL DEFAULT 0, ADD COLUMN source_content MEDIUMTEXT NULL;

CREATE TABLE finance_operation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 operator_id BIGINT NOT NULL,
 scope VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 idem_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_payload JSON NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'NEW',
 attempt_count INT NOT NULL DEFAULT 0,
 lease_token BIGINT NOT NULL DEFAULT 0,
 lease_until DATETIME(3) NULL,
 response_payload JSON NULL,
 last_error VARCHAR(128) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_finance_operation(operator_id,scope,idem_key),
 KEY ix_finance_operation(status,lease_until,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_evidence (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 operation_id BIGINT NOT NULL,
 operation_attempt INT NOT NULL,
 batch_id BIGINT NULL,
 item_id BIGINT NULL,
 biz_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 evidence_type VARCHAR(32) NOT NULL,
 request_payload JSON NOT NULL,
 response_payload JSON NULL,
 response_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 verified TINYINT NOT NULL DEFAULT 0,
 outcome VARCHAR(64) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 KEY ix_finance_evidence_item(item_id,id),
 KEY ix_finance_evidence_biz(biz_no,id),
 KEY ix_finance_evidence_operation(operation_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE reconcile_recovery_task (
 batch_id BIGINT NOT NULL PRIMARY KEY,
 operator_id BIGINT NOT NULL,
 reason VARCHAR(255) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'READY',
 attempt_count INT NOT NULL DEFAULT 0,
 lease_token BIGINT NOT NULL DEFAULT 0,
 lease_until DATETIME(3) NULL,
 next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 last_error VARCHAR(128) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 KEY ix_reconcile_recovery(status,next_attempt_at,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

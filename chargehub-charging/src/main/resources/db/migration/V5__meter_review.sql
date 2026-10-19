-- Metering evidence and two-person decisions are append-only. Never edit issued bills.
ALTER TABLE charge_session ADD COLUMN stop_confirmed_at DATETIME(3) NULL,
  ADD COLUMN stop_confirmed_event_id BIGINT NULL;
ALTER TABLE connector ADD COLUMN reported_device_status VARCHAR(24) NULL,
  ADD COLUMN reported_status_at DATETIME(3) NULL;

CREATE TABLE device_event_conflict (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 original_event_id BIGINT NOT NULL,
 payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payload JSON NOT NULL,
 received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_device_conflict_payload(original_event_id,payload_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE meter_replay_request (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 request_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 order_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 session_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 from_seq BIGINT NOT NULL,
 to_seq BIGINT NOT NULL,
 requested_by BIGINT NOT NULL,
 reason VARCHAR(255) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 received_count INT NOT NULL DEFAULT 0,
 response_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 result_code VARCHAR(64) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_meter_replay_request(request_no),
 KEY ix_meter_replay_order(order_no,id),
 CHECK(from_seq>=0 AND to_seq>=from_seq AND to_seq-from_seq<10000),
 CHECK(status IN ('PENDING','WAITING','PARTIAL','DONE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE meter_review_proposal (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 proposal_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 order_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 session_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 selected_evidence JSON NOT NULL,
 evidence_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 proposed_by BIGINT NOT NULL,
 reason VARCHAR(255) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_meter_proposal(proposal_no),
 KEY ix_meter_proposal_order(order_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE meter_review_decision (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 proposal_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 decision VARCHAR(8) NOT NULL,
 decided_by BIGINT NOT NULL,
 reason VARCHAR(255) NOT NULL,
 evidence_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_meter_decision_proposal(proposal_no),
 CHECK(decision IN ('APPROVE','REJECT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE meter_review_selection (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 session_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 seq_no BIGINT NOT NULL,
 proposal_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 selected_event_id BIGINT NOT NULL,
 selected_payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 covered_through_event_id BIGINT NOT NULL,
 covered_through_conflict_id BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_meter_selection_proposal_seq(proposal_no,seq_no),
 KEY ix_meter_selection_effective(session_no,seq_no,id),
 CHECK(seq_no>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

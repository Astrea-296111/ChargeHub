-- RocketMQ publication evidence, short consumer leases, local completion proof and visible dead letters.
-- This migration is additive. DB polling mode does not need or depend on the message transport.
CREATE TABLE outbox_publication (
  event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  broker_message_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  publication_count INT NOT NULL DEFAULT 1,
  first_published_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_published_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE message_delivery (
  event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  consumer_name VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  broker_message_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'READY',
  attempt_count INT NOT NULL DEFAULT 0,
  lease_owner VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NULL,
  lease_token BIGINT NOT NULL DEFAULT 0,
  lease_until DATETIME(3) NULL,
  last_error VARCHAR(64) NULL,
  completion_json JSON NULL,
  completed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (event_id),
  KEY ix_message_delivery_status (status,updated_at,event_id),
  CHECK (status IN ('READY','PROCESSING','RETRY','COMPLETED','DEAD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE message_quarantine (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  broker_message_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  body_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_quarantine_broker_body (broker_message_id,body_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE message_fault_observation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  phase VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  instance_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  observed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_message_fault_instance (event_id,phase,instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

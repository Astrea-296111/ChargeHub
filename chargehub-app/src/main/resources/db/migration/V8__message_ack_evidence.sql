-- Optional observations written only after broker Ack succeeds. Missing rows are never treated as proof of Ack.
CREATE TABLE message_ack_evidence (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  broker_message_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  delivery_attempt INT NOT NULL,
  duplicate_delivery TINYINT NOT NULL DEFAULT 0,
  acknowledged_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_message_ack_delivery (event_id,broker_message_id,delivery_attempt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

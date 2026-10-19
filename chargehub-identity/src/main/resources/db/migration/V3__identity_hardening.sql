-- Shared authentication controls; never store submitted passwords or bearer tokens.
ALTER TABLE auth_token
  ADD COLUMN revoked_at DATETIME(3) NULL,
  ADD KEY ix_auth_live_user (user_id, revoked_at, expires_at);

CREATE TABLE auth_login_bucket (
  bucket_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  window_started_at DATETIME(3) NOT NULL,
  attempt_count INT NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  PRIMARY KEY (bucket_key),
  KEY ix_login_bucket_expiry (expires_at),
  CHECK (attempt_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- All role changes acquire this row before the idempotency/user locks.
CREATE TABLE iam_admin_guard (
  id INT NOT NULL,
  PRIMARY KEY (id),
  CHECK (id = 1)
) ENGINE=InnoDB;
INSERT INTO iam_admin_guard(id) VALUES (1);

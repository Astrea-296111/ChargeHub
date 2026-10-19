-- Operational availability is independent from device-reported status.
-- Existing connectors retain their enabled behavior; no historical resource or order is rewritten.
ALTER TABLE connector ADD COLUMN operational_enabled TINYINT NOT NULL DEFAULT 1;
ALTER TABLE connector ADD CONSTRAINT ck_connector_operational_enabled CHECK (operational_enabled IN (0,1));
ALTER TABLE station ADD CONSTRAINT ck_station_status CHECK (status IN ('ENABLED','DISABLED'));
CREATE INDEX ix_coupon_expiry ON user_coupon(status,valid_until,id);

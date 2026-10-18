-- ChargeHub schema baseline 1.0 2026-10-18
-- MySQL 8.4 InnoDB. Execute in a dedicated database with session time_zone='+00:00'.
-- This is a design migration baseline; real MySQL execution is a required T01 gate.
-- Intentionally no DROP, default credentials or implicit data repair.
-- No foreign keys in this baseline: application transactions enforce references;
-- T01 adds integration checks for orphan rows and mandatory relational ownership.
SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- 用户身份与活动订单绑定
CREATE TABLE iam_user (
  id BIGINT NOT NULL AUTO_INCREMENT,
  login_name VARCHAR(80) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  active_order_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_login (login_name),
  UNIQUE KEY uk_user_active_order (active_order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户身份与活动订单绑定';

-- 服务端角色
CREATE TABLE iam_user_role (
  user_id BIGINT NOT NULL,
  role_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id,role_code),
  CHECK (role_code IN ('CUSTOMER','OPS','FINANCE','ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='服务端角色';

-- 站点
CREATE TABLE station (
  id BIGINT NOT NULL AUTO_INCREMENT,
  station_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name VARCHAR(120) NOT NULL,
  address VARCHAR(255) NOT NULL,
  timezone VARCHAR(40) NOT NULL DEFAULT 'Asia/Shanghai',
  active_tariff_id BIGINT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_station_code (station_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站点';

-- 连接器即充电枪
CREATE TABLE connector (
  id BIGINT NOT NULL AUTO_INCREMENT,
  station_id BIGINT NOT NULL,
  device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  connector_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'IDLE',
  active_order_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  version BIGINT NOT NULL DEFAULT 0,
  last_seen_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_connector_device_code (device_id,connector_code),
  UNIQUE KEY uk_connector_active_order (active_order_no),
  KEY ix_connector_station (station_id,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='连接器即充电枪';

-- 发布后不可变费率版本
CREATE TABLE tariff_plan (
  id BIGINT NOT NULL AUTO_INCREMENT,
  station_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  rule_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  published_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tariff_version (station_id,version_no),
  CHECK (version_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发布后不可变费率版本';

-- 每日左闭右开时段跨行覆盖由应用发布校验
CREATE TABLE tariff_slot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tariff_id BIGINT NOT NULL,
  start_minute INT NOT NULL,
  end_minute INT NOT NULL,
  energy_rate_fen_per_kwh INT NOT NULL,
  service_rate_fen_per_kwh INT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tariff_start (tariff_id,start_minute),
  CHECK (start_minute >= 0 AND end_minute <= 1440 AND end_minute > start_minute),
  CHECK (energy_rate_fen_per_kwh >= 0 AND service_rate_fen_per_kwh >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每日左闭右开时段跨行覆盖由应用发布校验';

-- 首版由dev种子或授权运营开通不含付费订阅
CREATE TABLE membership (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  plan_code VARCHAR(40) NOT NULL,
  valid_from DATETIME(3) NOT NULL,
  valid_until DATETIME(3) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_membership_user (user_id),
  CHECK (valid_until > valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='首版由dev种子或授权运营开通不含付费订阅';

-- 创建订单业务月份的次数账户
CREATE TABLE member_quota (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  period_month CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  total_count INT NOT NULL DEFAULT 2,
  reserved_count INT NOT NULL DEFAULT 0,
  used_count INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_quota_period (user_id,period_month),
  CHECK (total_count >= 0 AND reserved_count >= 0 AND used_count >= 0),
  CHECK (reserved_count + used_count <= total_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='创建订单业务月份的次数账户';

-- 与额度修改同事务的不可变业务流水
CREATE TABLE quota_ledger (
  id BIGINT NOT NULL AUTO_INCREMENT,
  quota_id BIGINT NOT NULL,
  order_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reserved_delta INT NOT NULL,
  used_delta INT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_quota_order_action (quota_id,order_no,action),
  CHECK (action IN ('RESERVE','CONSUME','RELEASE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='与额度修改同事务的不可变业务流水';

-- 首版服务费固定金额券每用户每模板限领一张
CREATE TABLE coupon_template (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(120) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  claim_from DATETIME(3) NOT NULL,
  claim_until DATETIME(3) NOT NULL,
  use_valid_from DATETIME(3) NOT NULL,
  use_valid_until DATETIME(3) NOT NULL,
  threshold_fen BIGINT NOT NULL,
  discount_fen BIGINT NOT NULL,
  total_count INT NOT NULL,
  issued_count INT NOT NULL DEFAULT 0,
  scope_snapshot JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY ix_coupon_claim (status,claim_from,claim_until,id),
  CHECK (threshold_fen >= 0 AND discount_fen > 0),
  CHECK (total_count >= 0 AND issued_count >= 0 AND issued_count <= total_count),
  CHECK (claim_until > claim_from AND use_valid_until > use_valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='首版服务费固定金额券每用户每模板限领一张';

-- 用户券及冻结规则
CREATE TABLE user_coupon (
  id BIGINT NOT NULL AUTO_INCREMENT,
  template_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
  locked_order_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  valid_from DATETIME(3) NOT NULL,
  valid_until DATETIME(3) NOT NULL,
  rule_snapshot JSON NOT NULL,
  claimed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  used_at DATETIME(3) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon_user_template (user_id,template_id),
  KEY ix_coupon_user_status (user_id,status,valid_until,id),
  CHECK (status IN ('AVAILABLE','LOCKED','USED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户券及冻结规则';

-- 预占核销释放的审计与业务去重
CREATE TABLE coupon_transition_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  coupon_id BIGINT NOT NULL,
  order_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  from_status VARCHAR(16) NOT NULL,
  to_status VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon_order_action (coupon_id,order_no,action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='预占核销释放的审计与业务去重';

-- 交易聚合资源绑定通过用户与枪行协同约束
CREATE TABLE charge_order (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id BIGINT NOT NULL,
  station_id BIGINT NOT NULL,
  connector_id BIGINT NOT NULL,
  tariff_id BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'WAIT_PAY',
  financial_status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
  prepaid_fen BIGINT NOT NULL DEFAULT 10000,
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CNY',
  tariff_snapshot JSON NOT NULL,
  member_rule_snapshot JSON NULL,
  payment_deadline DATETIME(3) NOT NULL,
  start_deadline DATETIME(3) NULL,
  started_at DATETIME(3) NULL,
  ended_at DATETIME(3) NULL,
  exception_code VARCHAR(64) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_no (order_no),
  KEY ix_order_user_cursor (user_id,created_at,id),
  KEY ix_order_station_cursor (station_id,created_at,id),
  KEY ix_order_timeout (status,payment_deadline,id),
  KEY ix_order_recovery (status,updated_at,id),
  CHECK (prepaid_fen = 10000 AND currency = 'CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='交易聚合资源绑定通过用户与枪行协同约束';

-- 订单权益预占及最终处理
CREATE TABLE benefit_reservation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id BIGINT NOT NULL,
  quota_id BIGINT NULL,
  coupon_id BIGINT NULL,
  member_state VARCHAR(16) NOT NULL DEFAULT 'NONE',
  coupon_state VARCHAR(16) NOT NULL DEFAULT 'NONE',
  member_rule_snapshot JSON NULL,
  coupon_rule_snapshot JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finalized_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_reservation_order (order_no),
  KEY ix_reservation_coupon (coupon_id),
  CHECK (member_state IN ('NONE','RESERVED','CONSUMED','RELEASED')),
  CHECK (coupon_state IN ('NONE','LOCKED','USED','RELEASED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单权益预占及最终处理';

-- START STOP QUERY_SESSION均用稳定commandId重复调用
CREATE TABLE device_command (
  id BIGINT NOT NULL AUTO_INCREMENT,
  command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  command_type VARCHAR(24) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  request_payload JSON NOT NULL,
  response_payload JSON NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_command_id (command_id),
  UNIQUE KEY uk_command_order_type (order_no,command_type),
  KEY ix_command_recovery (status,updated_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='START STOP QUERY_SESSION均用稳定commandId重复调用';

-- 设备会话事实
CREATE TABLE charge_session (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  connector_id BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL,
  start_meter_wh BIGINT NULL,
  end_meter_wh BIGINT NULL,
  final_seq BIGINT NULL,
  started_at DATETIME(3) NULL,
  ended_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_session_no (session_no),
  UNIQUE KEY uk_session_order (order_no),
  CHECK (start_meter_wh IS NULL OR start_meter_wh >= 0),
  CHECK (end_meter_wh IS NULL OR end_meter_wh >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='设备会话事实';

-- 原始收件先持久化冲突不能覆盖
CREATE TABLE device_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  session_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  event_type VARCHAR(24) NOT NULL,
  seq_no BIGINT NULL,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload JSON NOT NULL,
  process_status VARCHAR(24) NOT NULL DEFAULT 'RECEIVED',
  error_code VARCHAR(64) NULL,
  occurred_at DATETIME(3) NOT NULL,
  received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_device_event (device_id,event_id),
  KEY ix_device_session (session_no,seq_no,id),
  KEY ix_device_pending (process_status,received_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='原始收件先持久化冲突不能覆盖';

-- 通过校验的累计电表样本不直接累加重复包
CREATE TABLE meter_sample (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  device_event_id BIGINT NOT NULL,
  seq_no BIGINT NOT NULL,
  meter_wh BIGINT NOT NULL,
  sampled_at DATETIME(3) NOT NULL,
  sample_kind VARCHAR(24) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_meter_session_seq (session_no,seq_no),
  UNIQUE KEY uk_meter_event (device_event_id),
  CHECK (seq_no >= 0 AND meter_wh >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通过校验的累计电表样本不直接累加重复包';

-- 正式账单不可原地修改
CREATE TABLE charge_bill (
  id BIGINT NOT NULL AUTO_INCREMENT,
  bill_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  total_wh BIGINT NOT NULL,
  energy_fen BIGINT NOT NULL,
  service_fen BIGINT NOT NULL,
  member_discount_fen BIGINT NOT NULL DEFAULT 0,
  coupon_discount_fen BIGINT NOT NULL DEFAULT 0,
  payable_fen BIGINT NOT NULL,
  rule_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_bill_order (order_no),
  UNIQUE KEY uk_bill_no (bill_no),
  CHECK (total_wh >= 0 AND energy_fen >= 0 AND service_fen >= 0),
  CHECK (member_discount_fen >= 0 AND coupon_discount_fen >= 0 AND payable_fen >= 0),
  CHECK (member_discount_fen + coupon_discount_fen <= service_fen),
  CHECK (payable_fen = energy_fen + service_fen - member_discount_fen - coupon_discount_fen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='正式账单不可原地修改';

-- 计费分段电量单价与舍入结果
CREATE TABLE charge_bill_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  bill_id BIGINT NOT NULL,
  segment_no INT NOT NULL,
  segment_start DATETIME(3) NOT NULL,
  segment_end DATETIME(3) NOT NULL,
  delta_wh BIGINT NOT NULL,
  energy_rate_fen_per_kwh INT NOT NULL,
  service_rate_fen_per_kwh INT NOT NULL,
  energy_fen BIGINT NOT NULL,
  service_fen BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_bill_segment (bill_id,segment_no),
  CHECK (segment_end > segment_start AND delta_wh >= 0),
  CHECK (energy_rate_fen_per_kwh >= 0 AND service_rate_fen_per_kwh >= 0),
  CHECK (energy_fen >= 0 AND service_fen >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='计费分段电量单价与舍入结果';

-- 首版每单唯一预付支付单不支持追加扣款
CREATE TABLE payment_order (
  id BIGINT NOT NULL AUTO_INCREMENT,
  pay_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  channel VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'MOCK',
  provider_trade_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'INIT',
  amount_fen BIGINT NOT NULL,
  captured_fen BIGINT NOT NULL DEFAULT 0,
  refund_reserved_fen BIGINT NOT NULL DEFAULT 0,
  refund_success_fen BIGINT NOT NULL DEFAULT 0,
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CNY',
  captured_at DATETIME(3) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_no (pay_no),
  UNIQUE KEY uk_pay_order (order_no),
  UNIQUE KEY uk_pay_provider (channel,provider_trade_no),
  KEY ix_pay_recovery (status,updated_at,id),
  CHECK (amount_fen = 10000 AND currency = 'CNY'),
  CHECK (captured_fen >= 0 AND captured_fen <= amount_fen),
  CHECK (refund_reserved_fen >= 0 AND refund_success_fen >= 0),
  CHECK (refund_reserved_fen + refund_success_fen <= captured_fen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='首版每单唯一预付支付单不支持追加扣款';

-- 通过验签的支付与退款通知收件
CREATE TABLE payment_callback (
  id BIGINT NOT NULL AUTO_INCREMENT,
  channel VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  channel_event_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_type VARCHAR(24) NOT NULL,
  pay_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  refund_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload JSON NOT NULL,
  process_status VARCHAR(24) NOT NULL DEFAULT 'RECEIVED',
  error_code VARCHAR(64) NULL,
  received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  processed_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_callback_event (channel,channel_event_id),
  KEY ix_callback_recovery (process_status,received_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通过验签的支付与退款通知收件';

-- 稳定退款号未知状态继续占用额度
CREATE TABLE refund_order (
  id BIGINT NOT NULL AUTO_INCREMENT,
  refund_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  pay_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reason VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  amount_fen BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'INIT',
  provider_refund_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  last_error VARCHAR(128) NULL,
  next_query_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_refund_no (refund_no),
  UNIQUE KEY uk_refund_reason (pay_no,reason),
  KEY ix_refund_recovery (status,next_query_at,id),
  CHECK (amount_fen > 0),
  CHECK (reason IN ('FULL_CANCEL','BALANCE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='稳定退款号未知状态继续占用额度';

-- 不可变资金事实流水不是完整会计总账
CREATE TABLE cash_entry (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  biz_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  biz_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  channel VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  provider_biz_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  amount_fen BIGINT NOT NULL,
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CNY',
  occurred_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_cash_biz (biz_type,biz_no),
  UNIQUE KEY uk_cash_provider (channel,biz_type,provider_biz_no),
  KEY ix_cash_order (order_no,id),
  CHECK (biz_type IN ('CAPTURE','REFUND')),
  CHECK (amount_fen > 0 AND currency = 'CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变资金事实流水不是完整会计总账';

-- 与业务变更同事务写入短租约领取
CREATE TABLE outbox_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  schema_version INT NOT NULL DEFAULT 1,
  aggregate_type VARCHAR(32) NOT NULL,
  aggregate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  aggregate_version BIGINT NOT NULL,
  payload JSON NOT NULL,
  trace_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'READY',
  attempt_count INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  lease_owner VARCHAR(80) NULL,
  lease_token BIGINT NOT NULL DEFAULT 0,
  lease_until DATETIME(3) NULL,
  last_error VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  published_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_outbox_event (event_id),
  UNIQUE KEY uk_outbox_effect (event_name,aggregate_id,aggregate_version),
  KEY ix_outbox_ready (status,next_attempt_at,id),
  KEY ix_outbox_lease (status,lease_until,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='与业务变更同事务写入短租约领取';

-- 与本地消费效果同事务记录
CREATE TABLE consumer_inbox (
  consumer_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  processed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (consumer_name,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='与本地消费效果同事务记录';

-- 写请求摘要与返回结果持久化
CREATE TABLE idempotency_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  principal_id BIGINT NOT NULL,
  scope VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  idem_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL,
  resource_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  http_status INT NULL,
  response_json JSON NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem_scope (principal_id,scope,idem_key),
  KEY ix_idem_expiry (expires_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='写请求摘要与返回结果持久化';

-- 支付退款分别按渠道业务日期导入
CREATE TABLE reconcile_batch (
  id BIGINT NOT NULL AUTO_INCREMENT,
  channel VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  business_date DATE NOT NULL,
  bill_type VARCHAR(16) NOT NULL,
  source_timezone VARCHAR(40) NOT NULL,
  version_no INT NOT NULL DEFAULT 1,
  file_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'IMPORTED',
  total_count INT NOT NULL DEFAULT 0,
  diff_count INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_reconcile_batch (channel,business_date,bill_type,version_no),
  CHECK (bill_type IN ('PAYMENT','REFUND'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付退款分别按渠道业务日期导入';

-- 双向差异与修复证据
CREATE TABLE reconcile_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  batch_id BIGINT NOT NULL,
  provider_biz_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  biz_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  order_no CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  local_amount_fen BIGINT NULL,
  channel_amount_fen BIGINT NULL,
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CNY',
  diff_type VARCHAR(40) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
  resolution VARCHAR(255) NULL,
  resolved_by BIGINT NULL,
  resolved_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_reconcile_item (batch_id,provider_biz_no),
  KEY ix_reconcile_open (status,diff_type,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='双向差异与修复证据';

-- 敏感字段脱敏后的操作记录
CREATE TABLE ops_audit (
  id BIGINT NOT NULL AUTO_INCREMENT,
  operator_id BIGINT NOT NULL,
  action VARCHAR(64) NOT NULL,
  target_type VARCHAR(40) NOT NULL,
  target_no VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reason VARCHAR(255) NOT NULL,
  before_json JSON NULL,
  after_json JSON NULL,
  request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY ix_audit_target (target_type,target_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='敏感字段脱敏后的操作记录';

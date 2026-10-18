-- Read-only integrity gate. Run on a transactionally consistent snapshot with a read-only account.
-- Each SELECT must return zero rows. Rows include a stable check name and the affected business key.
SET time_zone = '+00:00';
SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;

SELECT 'quota_bounds' AS check_name, CAST(id AS CHAR) AS entity_no FROM member_quota
WHERE total_count < 0 OR used_count < 0 OR reserved_count < 0 OR used_count + reserved_count > total_count;

SELECT 'coupon_oversold' AS check_name, CAST(id AS CHAR) AS entity_no FROM coupon_template
WHERE issued_count < 0 OR issued_count > total_count;

SELECT 'coupon_issue_count' AS check_name, CAST(t.id AS CHAR) AS entity_no FROM coupon_template t
LEFT JOIN (SELECT template_id,COUNT(*) AS actual_count FROM user_coupon GROUP BY template_id) c ON c.template_id=t.id
WHERE t.issued_count<>COALESCE(c.actual_count,0);

SELECT 'quota_reservation_count' AS check_name, CAST(q.id AS CHAR) AS entity_no FROM member_quota q
LEFT JOIN (SELECT quota_id,SUM(member_state='RESERVED') AS actual_reserved,SUM(member_state='CONSUMED') AS actual_used
           FROM benefit_reservation WHERE quota_id IS NOT NULL GROUP BY quota_id) r ON r.quota_id=q.id
WHERE q.reserved_count<>COALESCE(r.actual_reserved,0) OR q.used_count<>COALESCE(r.actual_used,0);

SELECT 'refund_bounds' AS check_name, pay_no AS entity_no FROM payment_order
WHERE captured_fen<0 OR captured_fen>amount_fen OR refund_reserved_fen<0 OR refund_success_fen<0
   OR refund_reserved_fen+refund_success_fen>captured_fen;

SELECT 'refund_reservation_count' AS check_name,p.pay_no AS entity_no FROM payment_order p
LEFT JOIN (SELECT pay_no,SUM(CASE WHEN status IN ('INIT','PENDING','PROCESSING','UNKNOWN') THEN amount_fen ELSE 0 END) AS reserved_fen,
                  SUM(CASE WHEN status='SUCCESS' THEN amount_fen ELSE 0 END) AS success_fen
           FROM refund_order GROUP BY pay_no) r ON r.pay_no=p.pay_no
WHERE p.refund_reserved_fen<>COALESCE(r.reserved_fen,0) OR p.refund_success_fen<>COALESCE(r.success_fen,0);

SELECT 'payment_cash_counters' AS check_name,p.pay_no AS entity_no FROM payment_order p
LEFT JOIN (SELECT order_no,SUM(CASE WHEN biz_type='CAPTURE' THEN amount_fen ELSE 0 END) AS capture_fen,
                  SUM(CASE WHEN biz_type='REFUND' THEN amount_fen ELSE 0 END) AS refund_fen
           FROM cash_entry GROUP BY order_no) c ON c.order_no=p.order_no
WHERE p.captured_fen<>COALESCE(c.capture_fen,0) OR p.refund_success_fen<>COALESCE(c.refund_fen,0);

SELECT 'bill_arithmetic' AS check_name,order_no AS entity_no FROM charge_bill
WHERE payable_fen<>energy_fen+service_fen-member_discount_fen-coupon_discount_fen
   OR payable_fen<0 OR member_discount_fen+coupon_discount_fen>service_fen;

SELECT 'bill_segments_sum' AS check_name,b.order_no AS entity_no FROM charge_bill b
LEFT JOIN (SELECT bill_id,SUM(delta_wh) AS wh,SUM(energy_fen) AS energy_fen,SUM(service_fen) AS service_fen
           FROM charge_bill_item GROUP BY bill_id) i ON i.bill_id=b.id
WHERE b.total_wh<>COALESCE(i.wh,0) OR b.energy_fen<>COALESCE(i.energy_fen,0) OR b.service_fen<>COALESCE(i.service_fen,0);

SELECT 'duplicate_session' AS check_name,order_no AS entity_no FROM charge_session GROUP BY order_no HAVING COUNT(*)>1;
SELECT 'duplicate_cash_business' AS check_name,CONCAT(biz_type,':',biz_no) AS entity_no FROM cash_entry GROUP BY biz_type,biz_no HAVING COUNT(*)>1;
SELECT 'duplicate_cash_provider' AS check_name,CONCAT(channel,':',biz_type,':',provider_biz_no) AS entity_no
FROM cash_entry GROUP BY channel,biz_type,provider_biz_no HAVING COUNT(*)>1;

SELECT 'closed_financial_net' AS check_name,o.order_no AS entity_no FROM charge_order o
LEFT JOIN charge_bill b ON b.order_no=o.order_no
LEFT JOIN (SELECT order_no,SUM(CASE WHEN biz_type='CAPTURE' THEN amount_fen ELSE -amount_fen END) AS net_fen
           FROM cash_entry GROUP BY order_no) c ON c.order_no=o.order_no
WHERE o.financial_status='CLOSED' AND COALESCE(c.net_fen,0)<>COALESCE(b.payable_fen,0);

SELECT DISTINCT 'closed_with_reconciliation_difference' AS check_name,o.order_no AS entity_no FROM charge_order o
JOIN reconcile_item i ON i.order_no=o.order_no
WHERE o.financial_status='CLOSED' AND i.status<>'RESOLVED';

SELECT 'order_ownership' AS check_name,o.order_no AS entity_no FROM charge_order o
LEFT JOIN iam_user u ON u.id=o.user_id LEFT JOIN station s ON s.id=o.station_id
LEFT JOIN connector c ON c.id=o.connector_id LEFT JOIN tariff_plan t ON t.id=o.tariff_id
WHERE u.id IS NULL OR s.id IS NULL OR c.id IS NULL OR t.id IS NULL OR c.station_id<>o.station_id OR t.station_id<>o.station_id;

SELECT 'user_active_binding' AS check_name,CAST(u.id AS CHAR) AS entity_no FROM iam_user u
LEFT JOIN charge_order o ON o.order_no=u.active_order_no
WHERE u.active_order_no IS NOT NULL AND (o.id IS NULL OR o.user_id<>u.id OR o.status='CANCELED');

SELECT 'connector_active_binding' AS check_name,CAST(c.id AS CHAR) AS entity_no FROM connector c
LEFT JOIN charge_order o ON o.order_no=c.active_order_no
WHERE c.active_order_no IS NOT NULL AND (o.id IS NULL OR o.connector_id<>c.id OR o.status='CANCELED' OR c.status='IDLE');

SELECT 'active_order_binding_missing' AS check_name,o.order_no AS entity_no FROM charge_order o
JOIN iam_user u ON u.id=o.user_id JOIN connector c ON c.id=o.connector_id
WHERE o.status NOT IN ('COMPLETED','CANCELED')
  AND (u.active_order_no IS NULL OR u.active_order_no<>o.order_no OR c.active_order_no IS NULL OR c.active_order_no<>o.order_no);

SELECT 'multiple_active_user_orders' AS check_name,CAST(user_id AS CHAR) AS entity_no FROM charge_order
WHERE status NOT IN ('COMPLETED','CANCELED') GROUP BY user_id HAVING COUNT(*)>1;
SELECT 'multiple_active_connector_orders' AS check_name,CAST(connector_id AS CHAR) AS entity_no FROM charge_order
WHERE status NOT IN ('COMPLETED','CANCELED') GROUP BY connector_id HAVING COUNT(*)>1;

SELECT 'session_resource_ownership' AS check_name,s.session_no AS entity_no FROM charge_session s
LEFT JOIN charge_order o ON o.order_no=s.order_no LEFT JOIN connector c ON c.id=s.connector_id
WHERE o.id IS NULL OR c.id IS NULL OR s.connector_id<>o.connector_id OR s.device_id<>c.device_id;

SELECT 'live_session_released' AS check_name,s.session_no AS entity_no FROM charge_session s
JOIN charge_order o ON o.order_no=s.order_no JOIN connector c ON c.id=s.connector_id
WHERE s.status IN ('STARTING','RUNNING','CHARGING','STOP_PENDING','UNKNOWN')
  AND (o.status IN ('CANCELED','COMPLETED') OR c.active_order_no IS NULL OR c.active_order_no<>s.order_no OR c.status='IDLE');

SELECT 'benefit_ownership' AS check_name,r.order_no AS entity_no FROM benefit_reservation r
LEFT JOIN charge_order o ON o.order_no=r.order_no LEFT JOIN member_quota q ON q.id=r.quota_id
LEFT JOIN user_coupon c ON c.id=r.coupon_id
WHERE o.id IS NULL OR o.user_id<>r.user_id OR (r.quota_id IS NOT NULL AND (q.id IS NULL OR q.user_id<>r.user_id))
   OR (r.coupon_id IS NOT NULL AND (c.id IS NULL OR c.user_id<>r.user_id));

SELECT 'coupon_locked_binding' AS check_name,CAST(c.id AS CHAR) AS entity_no FROM user_coupon c
LEFT JOIN benefit_reservation r ON r.order_no=c.locked_order_no AND r.coupon_id=c.id
WHERE c.status='LOCKED' AND (c.locked_order_no IS NULL OR r.id IS NULL OR r.coupon_state<>'LOCKED');

SELECT 'reserved_coupon_binding' AS check_name,r.order_no AS entity_no FROM benefit_reservation r
LEFT JOIN user_coupon c ON c.id=r.coupon_id
WHERE r.coupon_state='LOCKED' AND (c.id IS NULL OR c.status<>'LOCKED' OR c.locked_order_no IS NULL OR c.locked_order_no<>r.order_no);

SELECT 'orphan_identity_role' AS check_name,CAST(r.user_id AS CHAR) AS entity_no FROM iam_user_role r LEFT JOIN iam_user u ON u.id=r.user_id WHERE u.id IS NULL;
SELECT 'orphan_connector_station' AS check_name,CAST(c.id AS CHAR) AS entity_no FROM connector c LEFT JOIN station s ON s.id=c.station_id WHERE s.id IS NULL;
SELECT 'orphan_membership_user' AS check_name,CAST(m.id AS CHAR) AS entity_no FROM membership m LEFT JOIN iam_user u ON u.id=m.user_id WHERE u.id IS NULL;
SELECT 'orphan_quota_user' AS check_name,CAST(q.id AS CHAR) AS entity_no FROM member_quota q LEFT JOIN iam_user u ON u.id=q.user_id WHERE u.id IS NULL;
SELECT 'orphan_coupon_owner_or_template' AS check_name,CAST(c.id AS CHAR) AS entity_no FROM user_coupon c LEFT JOIN iam_user u ON u.id=c.user_id LEFT JOIN coupon_template t ON t.id=c.template_id WHERE u.id IS NULL OR t.id IS NULL;
SELECT 'orphan_tariff_station' AS check_name,CAST(t.id AS CHAR) AS entity_no FROM tariff_plan t LEFT JOIN station s ON s.id=t.station_id WHERE s.id IS NULL;
SELECT 'orphan_tariff_slot' AS check_name,CAST(s.id AS CHAR) AS entity_no FROM tariff_slot s LEFT JOIN tariff_plan t ON t.id=s.tariff_id WHERE t.id IS NULL;
SELECT 'active_tariff_ownership' AS check_name,CAST(s.id AS CHAR) AS entity_no FROM station s LEFT JOIN tariff_plan t ON t.id=s.active_tariff_id WHERE s.active_tariff_id IS NOT NULL AND (t.id IS NULL OR t.station_id<>s.id OR t.status<>'PUBLISHED');
SELECT 'orphan_quota_ledger' AS check_name,CAST(l.id AS CHAR) AS entity_no FROM quota_ledger l LEFT JOIN member_quota q ON q.id=l.quota_id LEFT JOIN charge_order o ON o.order_no=l.order_no WHERE q.id IS NULL OR o.id IS NULL;
SELECT 'orphan_coupon_transition' AS check_name,CAST(l.id AS CHAR) AS entity_no FROM coupon_transition_log l LEFT JOIN user_coupon c ON c.id=l.coupon_id LEFT JOIN charge_order o ON o.order_no=l.order_no WHERE c.id IS NULL OR o.id IS NULL;
SELECT 'orphan_device_command' AS check_name,d.command_id AS entity_no FROM device_command d LEFT JOIN charge_order o ON o.order_no=d.order_no LEFT JOIN connector c ON c.id=o.connector_id WHERE o.id IS NULL OR c.device_id<>d.device_id;
SELECT 'orphan_meter_sample' AS check_name,CAST(m.id AS CHAR) AS entity_no FROM meter_sample m LEFT JOIN charge_session s ON s.session_no=m.session_no LEFT JOIN device_event e ON e.id=m.device_event_id WHERE s.id IS NULL OR e.id IS NULL OR e.session_no<>m.session_no;
SELECT 'orphan_bill' AS check_name,b.bill_no AS entity_no FROM charge_bill b LEFT JOIN charge_order o ON o.order_no=b.order_no WHERE o.id IS NULL;
SELECT 'orphan_bill_item' AS check_name,CAST(i.id AS CHAR) AS entity_no FROM charge_bill_item i LEFT JOIN charge_bill b ON b.id=i.bill_id WHERE b.id IS NULL;
SELECT 'orphan_payment' AS check_name,p.pay_no AS entity_no FROM payment_order p LEFT JOIN charge_order o ON o.order_no=p.order_no WHERE o.id IS NULL OR p.amount_fen<>o.prepaid_fen OR p.currency<>o.currency;
SELECT 'orphan_refund' AS check_name,r.refund_no AS entity_no FROM refund_order r LEFT JOIN payment_order p ON p.pay_no=r.pay_no WHERE p.id IS NULL OR r.order_no<>p.order_no;
SELECT 'orphan_cash_capture' AS check_name,c.biz_no AS entity_no FROM cash_entry c LEFT JOIN payment_order p ON p.pay_no=c.biz_no WHERE c.biz_type='CAPTURE' AND (p.id IS NULL OR c.order_no<>p.order_no OR c.channel<>p.channel OR c.currency<>p.currency OR c.amount_fen<>p.amount_fen);
SELECT 'orphan_cash_refund' AS check_name,c.biz_no AS entity_no FROM cash_entry c LEFT JOIN refund_order r ON r.refund_no=c.biz_no WHERE c.biz_type='REFUND' AND (r.id IS NULL OR c.order_no<>r.order_no OR c.amount_fen<>r.amount_fen);
SELECT 'orphan_reconciliation_item' AS check_name,CAST(i.id AS CHAR) AS entity_no FROM reconcile_item i LEFT JOIN reconcile_batch b ON b.id=i.batch_id WHERE b.id IS NULL;

COMMIT;

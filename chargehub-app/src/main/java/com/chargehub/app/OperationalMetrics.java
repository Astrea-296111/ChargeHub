package com.chargehub.app;
import com.chargehub.infrastructure.*;import io.micrometer.core.instrument.*;import io.micrometer.core.instrument.binder.MeterBinder;import org.springframework.stereotype.Component;
@Component
public class OperationalMetrics implements MeterBinder {
 private final Db db;public OperationalMetrics(Db db){this.db=db;}
 public void bindTo(MeterRegistry registry){
  register(registry,"chargehub.outbox.oldest.seconds","SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,created_at,UTC_TIMESTAMP(3))),0) n FROM outbox_event WHERE status IN ('READY','PROCESSING','PUBLISHED')");
  register(registry,"chargehub.outbox.failures","SELECT COALESCE(SUM(attempt_count),0) n FROM outbox_event WHERE last_error IS NOT NULL");
  register(registry,"chargehub.messaging.dead","SELECT COUNT(*) n FROM message_delivery WHERE status='DEAD'");
  register(registry,"chargehub.messaging.quarantined","SELECT COUNT(*) n FROM message_quarantine");
  register(registry,"chargehub.outbox.dead","SELECT COUNT(*) n FROM outbox_event WHERE status='DEAD'");
  register(registry,"chargehub.start.unknown","SELECT COUNT(*) n FROM charge_order WHERE status='EXCEPTION' AND exception_code='START_RESULT_UNKNOWN'");
  register(registry,"chargehub.meter.review","SELECT COUNT(*) n FROM charge_order WHERE status IN ('REVIEW','BILLING')");
  register(registry,"chargehub.refund.unknown","SELECT COUNT(*) n FROM refund_order WHERE status='UNKNOWN'");
  register(registry,"chargehub.funds.differences","SELECT COUNT(*) n FROM charge_order WHERE financial_status IN ('DIFF','SHORTFALL')");
  register(registry,"chargehub.coupon.inventory.violations","SELECT COUNT(*) n FROM coupon_template t WHERE t.issued_count<>(SELECT COUNT(*) FROM user_coupon c WHERE c.template_id=t.id) OR t.issued_count>t.total_count");
 }
 private void register(MeterRegistry r,String name,String sql){Gauge.builder(name,db,source->{try{return (double)Db.num(source.one(sql),"n");}catch(Exception e){return Double.NaN;}}).register(r);}
}

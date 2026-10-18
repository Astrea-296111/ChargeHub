package com.chargehub.app;

import com.chargehub.infrastructure.*;import com.chargehub.station.StationService;import com.chargehub.benefit.BenefitService;import com.chargehub.payment.PaymentService;import com.chargehub.charging.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.context.SpringBootTest;import org.springframework.boot.test.context.TestConfiguration;import org.springframework.context.annotation.Bean;import org.springframework.context.annotation.Primary;import org.springframework.test.context.ActiveProfiles;import org.springframework.context.annotation.Import;import org.flywaydb.core.Flyway;
import java.time.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Actual InnoDB transaction/uniqueness/lease verification. Never uses an embedded substitute. */
@SpringBootTest(properties={"spring.datasource.url=${CHARGEHUB_WORKFLOW_JDBC_URL}","spring.datasource.username=${CHARGEHUB_WORKFLOW_DB_USER}","spring.datasource.password=${CHARGEHUB_WORKFLOW_DB_PASSWORD}","CHARGEHUB_FIXED_TIME=","chargehub.payment-key=test-key-payment-1234567890","chargehub.device-key=test-key-device-1234567890","chargehub.control-key=test-key-control-1234567890","spring.main.banner-mode=off"})
@ActiveProfiles("test") @Import(WorkflowMysqlTest.TestClockConfig.class)
@EnabledIfEnvironmentVariable(named="CHARGEHUB_WORKFLOW_JDBC_URL",matches=".+")
class WorkflowMysqlTest {
 @Autowired Db db;@Autowired ChargeService orders;@Autowired StationService stations;@Autowired BenefitService benefits;@Autowired PaymentService payments;@Autowired SessionService sessions;@Autowired Idempotency idem;@Autowired Outbox outbox;@Autowired Flyway flyway;@Autowired MutableClock clock;
 record Fixture(long user,long other,long gun,long otherGun){}
 static final Instant NOW=Instant.parse("2026-10-18T07:00:00Z");
 @BeforeEach void time(){clock.now.set(NOW);}
 @Test void realMySqlMigrationAndUtcSessionAreRepeatable(){assertEquals("+00:00",db.str(db.one("SELECT @@session.time_zone AS tz"),"tz"));assertTrue(db.str(db.one("SELECT VERSION() v"),"v").startsWith("8.4."));flyway.validate();assertEquals(0,flyway.migrate().migrationsExecuted);}
 Fixture fixture(){
  String suffix=UUID.randomUUID().toString();long u=db.insert("INSERT INTO iam_user(login_name,password_hash) VALUES(?,'disabled-test-login')","u-"+suffix);long other=db.insert("INSERT INTO iam_user(login_name,password_hash) VALUES(?,'disabled-test-login')","v-"+suffix);
  long station=db.insert("INSERT INTO station(station_code,name,address) VALUES(?,'MySQL Test','isolated fixture')",suffix);
  long gun=db.insert("INSERT INTO connector(station_id,device_id,connector_code) VALUES(?,'test-device',?)",station,"a-"+suffix);long gun2=db.insert("INSERT INTO connector(station_id,device_id,connector_code) VALUES(?,'test-device',?)",station,"b-"+suffix);
  Map<String,Object> t=stations.createTariff(Map.of("stationId",String.valueOf(station),"versionNo",1,"slots",List.of(Map.of("startMinute",0,"endMinute",1440,"energyRateFenPerKwh",80,"serviceRateFenPerKwh",50))));stations.publishTariff(Long.parseLong(t.get("tariffId").toString()));return new Fixture(u,other,gun,gun2);
 }
 Map<String,Object> body(long gun,boolean member,Long coupon){Map<String,Object> b=new LinkedHashMap<>();b.put("connectorId",String.valueOf(gun));b.put("expectedTariffVersion",1);b.put("useMembership",member);b.put("couponId",coupon==null?null:coupon.toString());return b;}
 Map<String,Object> create(long u,Map<String,Object>b,String key){return idem.execute(u,"test.create",key,b,()->orders.create(u,b));}
 @Test void sameKeyAndResourceRacesPreserveOneBindingAndRollback()throws Exception{
  Fixture f=fixture();String key=UUID.randomUUID().toString();Map<String,Object>b=body(f.gun,false,null);Map<String,Object>a=create(f.user,b,key);assertEquals(a,create(f.user,b,key));assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(BusinessException.class,()->create(f.user,body(f.otherGun,false,null),key)).code());orders.cancel(f.user,Db.str(a,"orderNo"));
  // Invalid benefit AFTER resource binding must roll back the order and both bindings.
  assertThrows(BusinessException.class,()->create(f.user,body(f.gun,false,Long.MAX_VALUE),UUID.randomUUID().toString()));assertNull(db.one("SELECT active_order_no FROM iam_user WHERE id=?",f.user).get("active_order_no"));assertNull(db.one("SELECT active_order_no FROM connector WHERE id=?",f.gun).get("active_order_no"));
  try(var pool=Executors.newFixedThreadPool(2)){
   CountDownLatch gate=new CountDownLatch(1);List<Future<Boolean>> tries=new ArrayList<>();for(long user:new long[]{f.user,f.other})tries.add(pool.submit(()->{gate.await();try{create(user,body(f.gun,false,null),UUID.randomUUID().toString());return true;}catch(BusinessException expected){assertEquals("CONNECTOR_BUSY",expected.code());return false;}}));gate.countDown();assertEquals(1,(tries.get(0).get()?1:0)+(tries.get(1).get()?1:0));
  }
  assertEquals(1,Db.num(db.one("SELECT COUNT(*) n FROM charge_order WHERE connector_id=? AND status='WAIT_PAY'",f.gun),"n"));
 }
 Map<String,Object> capture(String pay,String event,Instant at){return new LinkedHashMap<>(Map.of("channelEventId",event,"type","PAYMENT_SUCCESS","payNo",pay,"providerBizNo","provider-"+pay,"amountFen",10000,"currency","CNY","occurredAt",at.toString()));}
 Map<String,Object> meter(String session,long gun,int seq,long wh,String type,Instant at){Map<String,Object> e=new LinkedHashMap<>();e.put("eventId",UUID.randomUUID().toString());e.put("deviceId","test-device");e.put("connectorId",String.valueOf(gun));e.put("sessionNo",session);e.put("seqNo",seq);e.put("meterWh",wh);e.put("eventType",type);e.put("occurredAt",at.toString());e.put("sampleKind",seq==0?"START":"STOPPED".equals(type)?"END":"NORMAL");if("STOPPED".equals(type))e.put("finalSeq",seq);return e;}
 @Test void memberCouponDuplicateMoneyAndOutOfOrderMeteringCloseAt2100(){
  Fixture f=fixture();db.update("INSERT INTO membership(user_id,plan_code,valid_from,valid_until) VALUES(?,'DEMO',?,?)",f.user,NOW.minusSeconds(100),NOW.plusSeconds(86400));
  Map<String,Object> template=benefits.createTemplate(Map.of("name","MySQL-demo","claimFrom",NOW.minusSeconds(100).toString(),"claimUntil",NOW.plusSeconds(10000).toString(),"useValidFrom",NOW.minusSeconds(100).toString(),"useValidUntil",NOW.plusSeconds(10000).toString(),"thresholdFen",500,"discountFen",300,"totalCount",1,"scope","ALL_STATIONS"));long templateId=Long.parseLong(template.get("templateId").toString());benefits.publishTemplate(templateId);long coupon=Long.parseLong(benefits.claim(f.user,templateId).get("couponId").toString());
  Map<String,Object> o=create(f.user,body(f.gun,true,coupon),UUID.randomUUID().toString());String no=Db.str(o,"orderNo");String pay=Db.str(orders.payment(f.user,no),"pay_no");
  Map<String,Object> event=capture(pay,"capture-"+pay,NOW);for(int i=0;i<20;i++)orders.notification(event);Map<String,Object> otherEvent=new LinkedHashMap<>(event);otherEvent.put("channelEventId","other-"+pay);orders.notification(otherEvent);
  assertEquals(1,Db.num(db.one("SELECT COUNT(*) n FROM cash_entry WHERE biz_no=? AND biz_type='CAPTURE'",pay),"n"));assertEquals(1,Db.num(db.one("SELECT COUNT(*) n FROM device_command WHERE order_no=? AND command_type='START'",no),"n"));
  String session=Db.str(db.one("SELECT * FROM charge_session WHERE order_no=?",no),"session_no");sessions.ingest(meter(session,f.gun,0,100000,"STARTED",NOW));sessions.ingest(meter(session,f.gun,5,120000,"STOPPED",NOW.plusSeconds(1800)));
  assertNull(db.one("SELECT id FROM charge_bill WHERE order_no=?",no));assertEquals("REVIEW",db.one("SELECT status FROM charge_order WHERE order_no=?",no).get("status"));
  for(int i:new int[]{4,2,3,1})sessions.ingest(meter(session,f.gun,i,100000+i*4000L,"METER",NOW.plusSeconds(i*360L)));
  sessions.tryFinalize(no);Map<String,Object> bill=db.one("SELECT * FROM charge_bill WHERE order_no=?",no);assertNotNull(bill);assertEquals(2100,Db.num(bill,"payable_fen"));assertEquals(200,Db.num(bill,"member_discount_fen"));assertEquals(300,Db.num(bill,"coupon_discount_fen"));
  Map<String,Object> refund=db.one("SELECT * FROM refund_order WHERE order_no=?",no);assertEquals(7900,Db.num(refund,"amount_fen"));assertEquals(7900,Db.num(payments.getByOrder(no),"refund_reserved_fen"));
  Map<String,Object> success=new LinkedHashMap<>(Map.of("channelEventId","refund-"+pay,"type","REFUND_SUCCESS","payNo",pay,"refundNo",refund.get("refund_no"),"providerBizNo","provider-refund-"+pay,"amountFen",7900,"currency","CNY","occurredAt",NOW.plusSeconds(1801).toString()));for(int i=0;i<20;i++)orders.notification(success);
  assertEquals("CLOSED",db.one("SELECT financial_status FROM charge_order WHERE order_no=?",no).get("financial_status"));assertEquals(0,Db.num(payments.getByOrder(no),"refund_reserved_fen"));assertEquals("USED",db.one("SELECT status FROM user_coupon WHERE id=?",coupon).get("status"));assertEquals(1,Db.num(db.one("SELECT used_count FROM member_quota WHERE user_id=?",f.user),"used_count"));assertNull(db.one("SELECT active_order_no FROM connector WHERE id=?",f.gun).get("active_order_no"));
  sessions.tryFinalize(no);assertEquals(1,Db.num(db.one("SELECT COUNT(*) n FROM charge_bill WHERE order_no=?",no),"n"));
 }
 @Test void canceledAndDeadlinePaymentsAreCapturedAndFullyRefundedWithoutStart(){
  for(boolean exactDeadline:new boolean[]{false,true}){
   Fixture f=fixture();String no=Db.str(create(f.user,body(f.gun,false,null),UUID.randomUUID().toString()),"orderNo");String pay=Db.str(orders.payment(f.user,no),"pay_no");
   if(!exactDeadline)orders.cancel(f.user,no);orders.notification(capture(pay,UUID.randomUUID().toString(),exactDeadline?NOW.plusSeconds(300):NOW));
   assertEquals(10000,Db.num(payments.getByOrder(no),"captured_fen"));assertEquals(10000,Db.num(db.one("SELECT amount_fen FROM refund_order WHERE order_no=?",no),"amount_fen"));assertNull(db.one("SELECT id FROM device_command WHERE order_no=? AND command_type='START'",no));assertEquals("CANCELED",db.one("SELECT status FROM charge_order WHERE order_no=?",no).get("status"));
  }
 }
 @Test void leaseTakeoverRejectsStaleAcknowledgement(){
  String no=UUID.randomUUID().toString();outbox.enqueue("LeaseTest",no,Map.of("test",true));
  // Targeted claim SQL exercises the production skip-locked mechanism without consuming another test's intents.
  db.update("UPDATE outbox_event SET next_attempt_at=TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(3)) WHERE aggregate_id<>? AND status='READY'",no);
  Map<String,Object> first=outbox.claim();assertNotNull(first);assertEquals(no,first.get("aggregate_id"));db.update("UPDATE outbox_event SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",first.get("id"));Map<String,Object> second=outbox.claim();assertNotNull(second);assertEquals(Db.num(first,"lease_token")+1,Db.num(second,"lease_token"));assertFalse(outbox.ack(first));assertTrue(outbox.ack(second));
 }
 @TestConfiguration static class TestClockConfig{@Bean @Primary MutableClock controlledClock(){return new MutableClock();}}
 static class MutableClock extends Clock{final AtomicReference<Instant> now=new AtomicReference<>(NOW);public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now.get();}}
}

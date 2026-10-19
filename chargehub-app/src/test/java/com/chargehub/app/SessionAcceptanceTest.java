package com.chargehub.app;

import com.chargehub.benefit.BenefitService;
import com.chargehub.charging.*;
import com.chargehub.infrastructure.*;
import com.chargehub.payment.PaymentService;
import com.chargehub.station.StationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** A20–A32/A36 metering, real InnoDB, no external services and no synthetic DB repair. */
@SpringBootTest(properties={"spring.datasource.url=${CHARGEHUB_METER_TEST_JDBC_URL}","spring.datasource.username=${CHARGEHUB_METER_TEST_DB_USER:${CHARGEHUB_WORKFLOW_DB_USER}}","spring.datasource.password=${CHARGEHUB_METER_TEST_DB_PASSWORD:${CHARGEHUB_WORKFLOW_DB_PASSWORD}}","CHARGEHUB_FIXED_TIME=","chargehub.payment-key=meter-test-payment-key-1234567890","chargehub.device-key=meter-test-device-key-1234567890","chargehub.control-key=meter-test-control-key-1234567890","spring.main.banner-mode=off"})
@ActiveProfiles("test") @Import(SessionAcceptanceTest.ClockConfig.class)
@EnabledIfEnvironmentVariable(named="CHARGEHUB_METER_TEST_JDBC_URL",matches=".+")
class SessionAcceptanceTest {
    static final Instant NOW=Instant.parse("2026-10-18T07:00:00Z");
    @Autowired Db db; @Autowired ChargeService orders; @Autowired StationService stations;
    @Autowired BenefitService benefits; @Autowired PaymentService payments; @Autowired SessionService sessions;
    @Autowired SessionOperationsService operations; @Autowired TestClock clock;
    record F(long user,long gun,long ops,long finance,String no,String pay,String session,Long coupon){}
    @BeforeEach void resetClock(){clock.now.set(NOW);}
    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry properties){
        String url=System.getenv("CHARGEHUB_METER_TEST_JDBC_URL");
        String database=url==null?"":url.substring(url.lastIndexOf('/')+1).split("\\?",2)[0];
        if(!database.matches("[A-Za-z0-9_]+_test"))throw new IllegalArgumentException("Meter acceptance needs a dedicated database ending in _test");
        properties.add("spring.datasource.url",()->url);
        properties.add("chargehub.redis.enabled",()->false);
        properties.add("management.health.redis.enabled",()->false);
    }

    F fixture(boolean member,boolean coupon,int energy,int service){
        return fixture(member,coupon,List.of(Map.of("startMinute",0,"endMinute",1440,"energyRateFenPerKwh",energy,"serviceRateFenPerKwh",service)));
    }
    F fixture(boolean member,boolean coupon,List<Map<String,Object>> slots){
        String key=UUID.randomUUID().toString();
        long user=db.insert("INSERT INTO iam_user(login_name,password_hash) VALUES(?,'disabled-test-login')","meter-user-"+key);
        long ops=db.insert("INSERT INTO iam_user(login_name,password_hash) VALUES(?,'disabled-test-login')","meter-ops-"+key);
        long finance=db.insert("INSERT INTO iam_user(login_name,password_hash) VALUES(?,'disabled-test-login')","meter-finance-"+key);
        db.update("INSERT INTO iam_user_role(user_id,role_code) VALUES(?,'OPS'),(?,'FINANCE')",ops,finance);
        long station=db.insert("INSERT INTO station(station_code,name,address) VALUES(?,'Meter Acceptance','isolated fixture')",key);
        long gun=db.insert("INSERT INTO connector(station_id,device_id,connector_code) VALUES(?,'meter-device',?)",station,key);
        var tariff=stations.createTariff(Map.of("stationId",Long.toString(station),"versionNo",1,"slots",slots));
        stations.publishTariff(Long.parseLong(tariff.get("tariffId").toString()));
        Instant now=clock.instant();
        if(member)db.update("INSERT INTO membership(user_id,plan_code,valid_from,valid_until) VALUES(?,'METER',?,?)",user,now.minusSeconds(100),now.plusSeconds(60L*86400));
        Long couponId=null;
        if(coupon){
            var template=benefits.createTemplate(Map.of("name","Meter-"+key,"claimFrom",now.minusSeconds(100).toString(),"claimUntil",now.plusSeconds(10000).toString(),"useValidFrom",now.minusSeconds(100).toString(),"useValidUntil",now.plusSeconds(1800).toString(),"thresholdFen",500,"discountFen",300,"totalCount",1,"scope","ALL_STATIONS"));
            long id=Long.parseLong(template.get("templateId").toString());benefits.publishTemplate(id);couponId=Long.parseLong(benefits.claim(user,id).get("couponId").toString());
        }
        var body=new LinkedHashMap<String,Object>();body.put("connectorId",Long.toString(gun));body.put("expectedTariffVersion",1);body.put("useMembership",member);body.put("couponId",couponId==null?null:couponId.toString());
        String no=Db.str(orders.create(user,body),"order_no");
        String pay=Db.str(orders.payment(user,no),"pay_no");
        orders.notification(new LinkedHashMap<>(Map.of("channelEventId",UUID.randomUUID().toString(),"type","PAYMENT_SUCCESS","payNo",pay,"providerBizNo","meter-provider-"+pay,"amountFen",10000,"currency","CNY","occurredAt",now.toString())));
        String session=Db.str(db.one("SELECT * FROM charge_session WHERE order_no=?",no),"session_no");
        return new F(user,gun,ops,finance,no,pay,session,couponId);
    }
    Map<String,Object> event(F f,long seq,long wh,String type,Instant at){
        var value=new LinkedHashMap<String,Object>();value.put("eventId",UUID.randomUUID().toString());value.put("deviceId","meter-device");value.put("connectorId",Long.toString(f.gun));value.put("sessionNo",f.session);value.put("seqNo",seq);value.put("meterWh",wh);value.put("eventType",type);value.put("occurredAt",at.toString());value.put("sampleKind",seq==0?"START":type.equals("STOPPED")?"END":"NORMAL");if(type.equals("STOPPED"))value.put("finalSeq",seq);return value;
    }
    void start(F f){sessions.ingest(event(f,0,100000,"STARTED",clock.instant()));}
    Map<String,Object> bill(F f){return db.one("SELECT * FROM charge_bill WHERE order_no=?",f.no);}
    Map<String,Object> order(F f){return db.one("SELECT * FROM charge_order WHERE order_no=?",f.no);}
    long count(String table,F f){return Db.num(db.one("SELECT COUNT(*) n FROM "+table+" WHERE order_no=?",f.no),"n");}
    long rawId(Map<String,Object> event){return Db.num(db.one("SELECT id FROM device_event WHERE event_id=?",event.get("eventId")),"id");}
    Map<String,Object> propose(F f,long...ids){return operations.propose(f.ops,f.no,UUID.randomUUID().toString(),Map.of("reason","设备原始计量逐项复核选择","selectedDeviceEventIds",Arrays.stream(ids).boxed().toList()));}
    void approve(F f,Map<String,Object> proposal){operations.decide(f.finance,f.no,Db.str(proposal,"proposalNo"),UUID.randomUUID().toString(),Map.of("reason","财务独立核对完整设备证据","decision","APPROVE"));}

    @Test void A20_unknownStartDoesNotRefundAndActualStartedRestoresOwnership(){
        F f=fixture(false,false,80,50);orders.deviceUnknown(f.no,"START");assertEquals("EXCEPTION",Db.str(order(f),"status"));
        assertEquals(0,count("refund_order",f));assertEquals(f.no,Db.str(db.one("SELECT active_order_no FROM connector WHERE id=?",f.gun),"active_order_no"));
        start(f);assertEquals("CHARGING",Db.str(order(f),"status"));assertEquals(0,count("refund_order",f));
    }
    @Test void A21_authoritativeNotStartedCancelsAndReservesOneFullRefund(){
        F f=fixture(true,true,80,50);orders.deviceNotStarted(f.no);orders.deviceNotStarted(f.no);
        assertEquals("CANCELED",Db.str(order(f),"status"));assertEquals(1,count("refund_order",f));assertEquals(10000,Db.num(db.one("SELECT amount_fen FROM refund_order WHERE order_no=?",f.no),"amount_fen"));
        assertNull(Db.str(db.one("SELECT active_order_no FROM connector WHERE id=?",f.gun),"active_order_no"));
        assertEquals("RELEASED",Db.str(db.one("SELECT member_state FROM benefit_reservation WHERE order_no=?",f.no),"member_state"));
    }
    @Test void A22_duplicateStartedFactKeepsOneSessionAndOneMeterSample(){
        F f=fixture(false,false,80,50);var started=event(f,0,100000,"STARTED",NOW);
        for(int i=0;i<20;i++)sessions.ingest(started);
        assertEquals(1,count("charge_session",f));assertEquals(1,Db.num(db.one("SELECT COUNT(*) n FROM meter_sample WHERE session_no=?",f.session),"n"));
        assertEquals(1,Db.num(db.one("SELECT COUNT(*) n FROM device_command WHERE order_no=? AND command_type='START'",f.no),"n"));
    }
    @Test void A23_outOfOrderAndDuplicateSamplesNeverAccumulateEnergyTwice(){
        F f=fixture(false,false,80,50);var stopped=event(f,3,120000,"STOPPED",NOW.plusSeconds(1800));
        sessions.ingest(stopped);sessions.ingest(event(f,2,115000,"METER",NOW.plusSeconds(1200)));start(f);
        var meter=event(f,1,110000,"METER",NOW.plusSeconds(600));sessions.ingest(meter);sessions.ingest(meter);sessions.ingest(stopped);
        assertEquals(20000,Db.num(bill(f),"total_wh"));assertEquals(2600,Db.num(bill(f),"payable_fen"));assertEquals(1,count("charge_bill",f));
    }
    @Test void A24_conflictingSequenceNeedsIndependentEvidenceDecisionAndNeverOverwritesFacts(){
        F f=fixture(false,false,80,50);start(f);
        var original=event(f,1,104000,"METER",NOW.plusSeconds(600));sessions.ingest(original);
        var conflict=event(f,1,103999,"METER",NOW.plusSeconds(600));sessions.ingest(conflict);
        sessions.ingest(event(f,2,108000,"STOPPED",NOW.plusSeconds(1200)));
        assertNull(bill(f));assertEquals("REVIEW",Db.str(order(f),"status"));assertEquals(104000,Db.num(db.one("SELECT meter_wh FROM meter_sample WHERE session_no=? AND seq_no=1",f.session),"meter_wh"));
        var proposal=propose(f,rawId(original));
        assertEquals("SECOND_REVIEWER_REQUIRED",assertThrows(BusinessException.class,()->operations.decide(f.ops,f.no,Db.str(proposal,"proposalNo"),UUID.randomUUID().toString(),Map.of("reason","同一个人不得自批此提案","decision","APPROVE"))).code());
        approve(f,proposal);assertEquals(1040,Db.num(bill(f),"payable_fen"));
        assertEquals("REVIEW",Db.str(db.one("SELECT process_status FROM device_event WHERE id=?",rawId(conflict)),"process_status"));
        assertEquals(1,Db.num(db.one("SELECT COUNT(*) n FROM meter_review_selection WHERE session_no=?",f.session),"n"));
        assertTrue(Db.num(db.one("SELECT COUNT(*) n FROM ops_audit WHERE target_no=? AND action LIKE 'METER_REVIEW_%'",f.no),"n")>=2);
    }
    @Test void A25_confirmedStopWithGapReleasesSafeResourcesAndLateReplayCannotReleaseNewOrder(){
        F f=fixture(false,false,80,50);start(f);sessions.ingest(event(f,2,120000,"STOPPED",NOW.plusSeconds(1200)));
        assertNull(bill(f));assertEquals("REVIEW",Db.str(order(f),"status"));
        assertNull(Db.str(db.one("SELECT active_order_no FROM connector WHERE id=?",f.gun),"active_order_no"));
        assertNull(Db.str(db.one("SELECT active_order_no FROM iam_user WHERE id=?",f.user),"active_order_no"));
        String next=Db.str(orders.create(f.user,Map.of("connectorId",Long.toString(f.gun),"expectedTariffVersion",1,"useMembership",false)),"order_no");
        var replay=operations.requestReplay(f.ops,f.no,UUID.randomUUID().toString(),Map.of("reason","补传结束前缺失的设备原序号","fromSeq",1,"toSeq",1));
        operations.fulfillReplay(Db.str(replay,"requestNo"),Map.of("status","STOPPED","events",List.of(event(f,1,110000,"METER",NOW.plusSeconds(600)))));
        assertEquals(2600,Db.num(bill(f),"payable_fen"));assertEquals(next,Db.str(db.one("SELECT active_order_no FROM connector WHERE id=?",f.gun),"active_order_no"));
        assertEquals(next,Db.str(db.one("SELECT active_order_no FROM iam_user WHERE id=?",f.user),"active_order_no"));
    }
    @Test void A26_meterRollbackRetainsEvidenceAndCorrectedDeviceFactNeedsResolution(){
        F f=fixture(false,false,80,50);start(f);var bad=event(f,1,99999,"METER",NOW.plusSeconds(600));sessions.ingest(bad);
        var corrected=event(f,1,110000,"METER",NOW.plusSeconds(600));sessions.ingest(corrected);sessions.ingest(event(f,2,120000,"STOPPED",NOW.plusSeconds(1200)));
        assertNull(bill(f));assertEquals(99999,Db.num(Json.read(Db.str(db.one("SELECT payload FROM device_event WHERE id=?",rawId(bad)),"payload")),"meterWh"));
        approve(f,propose(f,rawId(corrected)));assertEquals(2600,Db.num(bill(f),"payable_fen"));assertEquals(20000,Db.num(bill(f),"total_wh"));
    }
    @Test void A25_completeSequenceWithoutAnActualTariffBoundaryNeverInterpolatesAFormalBill(){
        F f=fixture(false,false,List.of(Map.of("startMinute",0,"endMinute",1080,"energyRateFenPerKwh",80,"serviceRateFenPerKwh",50),
                Map.of("startMinute",1080,"endMinute",1440,"energyRateFenPerKwh",120,"serviceRateFenPerKwh",60)));
        start(f);sessions.ingest(event(f,1,110000,"METER",NOW.plusSeconds(3600)));
        sessions.ingest(event(f,2,120000,"STOPPED",NOW.plusSeconds(4*3600)));
        assertNull(bill(f));assertEquals("MISSING_TARIFF_BOUNDARY",Db.str(order(f),"exception_code"));
        assertEquals("MISSING_TARIFF_BOUNDARY",operations.review(f.no).get("validation"));
        assertNull(Db.str(db.one("SELECT active_order_no FROM connector WHERE id=?",f.gun),"active_order_no"));
    }
    @Test void A27_A28_lostStopAcknowledgementReplayFinalizesExactlyOnce(){
        F f=fixture(true,true,80,50);start(f);orders.stop(f.user,f.no);var stopped=event(f,1,120000,"STOPPED",NOW.plusSeconds(1200));
        for(int i=0;i<20;i++){sessions.ingest(stopped);sessions.tryFinalize(f.no);}
        assertEquals(1,count("charge_bill",f));assertEquals(1,count("refund_order",f));assertEquals(2100,Db.num(bill(f),"payable_fen"));
        assertEquals(1,Db.num(db.one("SELECT used_count FROM member_quota WHERE user_id=?",f.user),"used_count"));
        assertEquals("USED",Db.str(db.one("SELECT status FROM user_coupon WHERE id=?",f.coupon),"status"));
    }
    @Test void A29_couponBelowThresholdIsReleasedAsExpiredAtSettlement(){
        F f=fixture(true,true,80,50);start(f);clock.now.set(NOW.plusSeconds(3600));sessions.ingest(event(f,1,110000,"STOPPED",clock.instant()));
        assertEquals(1200,Db.num(bill(f),"payable_fen"));assertEquals(0,Db.num(bill(f),"coupon_discount_fen"));assertEquals("EXPIRED",Db.str(db.one("SELECT status FROM user_coupon WHERE id=?",f.coupon),"status"));
    }
    @Test void A30_monthBoundaryConsumesTheReservedMonthAndUsesRealMidnightMeter(){
        Instant monthEnd=Instant.parse("2026-10-31T15:59:00Z");clock.now.set(monthEnd);F f=fixture(true,false,80,50);start(f);
        var boundary=event(f,1,101000,"METER",Instant.parse("2026-10-31T16:00:00Z"));boundary.put("sampleKind","DAY_BOUNDARY");sessions.ingest(boundary);
        clock.now.set(Instant.parse("2026-10-31T16:10:00Z"));sessions.ingest(event(f,2,110000,"STOPPED",clock.instant()));
        assertEquals(100,Db.num(bill(f),"member_discount_fen"));assertEquals(1,Db.num(db.one("SELECT used_count FROM member_quota WHERE user_id=? AND period_month='2026-10'",f.user),"used_count"));
        assertNull(db.one("SELECT id FROM member_quota WHERE user_id=? AND period_month='2026-11'",f.user));
    }
    @Test void A31_zeroMemberDiscountReleasesReservedQuota(){
        F f=fixture(true,false,80,0);start(f);sessions.ingest(event(f,1,101000,"STOPPED",NOW.plusSeconds(600)));
        assertEquals(80,Db.num(bill(f),"payable_fen"));assertEquals(0,Db.num(bill(f),"member_discount_fen"));assertEquals(0,Db.num(db.one("SELECT used_count FROM member_quota WHERE user_id=?",f.user),"used_count"));
        assertEquals("RELEASED",Db.str(db.one("SELECT member_state FROM benefit_reservation WHERE order_no=?",f.no),"member_state"));
    }
    @Test void A32_zeroEnergyAtTheSameMillisecondRefundsInFullAndUsesNoBenefits(){
        F f=fixture(true,true,80,50);start(f);sessions.ingest(event(f,1,100000,"STOPPED",NOW));
        assertEquals(0,Db.num(bill(f),"payable_fen"));assertEquals("CANCELED",Db.str(order(f),"status"));assertEquals(10000,Db.num(db.one("SELECT amount_fen FROM refund_order WHERE order_no=?",f.no),"amount_fen"));
        assertEquals(0,Db.num(db.one("SELECT COUNT(*) n FROM charge_bill_item WHERE bill_id=?",Db.num(bill(f),"id")),"n"));
        assertEquals("AVAILABLE",Db.str(db.one("SELECT status FROM user_coupon WHERE id=?",f.coupon),"status"));
    }
    @Test void A36_actualOvershootIsNeverTruncatedAndDebtBlocksFurtherCharging(){
        F f=fixture(false,false,100,100);start(f);sessions.ingest(event(f,1,200000,"STOPPED",NOW.plusSeconds(600)));
        assertEquals(20000,Db.num(bill(f),"payable_fen"));assertEquals("SHORTFALL",Db.str(order(f),"financial_status"));assertEquals(0,count("refund_order",f));
        assertNull(Db.str(db.one("SELECT active_order_no FROM connector WHERE id=?",f.gun),"active_order_no"));
        assertThrows(BusinessException.class,()->orders.create(f.user,Map.of("connectorId",Long.toString(f.gun),"expectedTariffVersion",1,"useMembership",false)));
    }
    @Test void issuedBillsRejectManualChangesAndPreexistingFinancialDifferenceSurvivesBilling(){
        F f=fixture(false,false,80,50);db.update("UPDATE charge_order SET financial_status='DIFF',exception_code='CHANNEL_FACT_MISMATCH' WHERE order_no=?",f.no);start(f);
        assertEquals("CHANNEL_FACT_MISMATCH",Db.str(order(f),"exception_code"));
        sessions.ingest(event(f,2,120000,"STOPPED",NOW.plusSeconds(600)));assertEquals("CHANNEL_FACT_MISMATCH",Db.str(order(f),"exception_code"));
        sessions.ingest(event(f,1,110000,"METER",NOW.plusSeconds(300)));assertEquals(2600,Db.num(bill(f),"payable_fen"));
        assertEquals("DIFF",Db.str(order(f),"financial_status"));assertEquals("CHANNEL_FACT_MISMATCH",Db.str(order(f),"exception_code"));
        assertEquals("BILL_IMMUTABLE",assertThrows(BusinessException.class,()->propose(f,rawId(event(f,0,0,"STARTED",NOW)))).code());
    }
    @Test void eventIdConflictingPayloadIsAppendOnlyAndRejectedForeignDeviceCannotPoisonBilling(){
        F f=fixture(false,false,80,50);var started=event(f,0,100000,"STARTED",NOW);sessions.ingest(started);
        var foreign=event(f,1,110000,"METER",NOW.plusSeconds(300));foreign.put("deviceId","another-device");
        assertThrows(SessionService.DeviceEventRejected.class,()->sessions.ingest(foreign));
        var conflict=new LinkedHashMap<>(started);conflict.put("meterWh",99999);sessions.ingest(conflict);
        assertEquals(100000,Db.num(Json.read(Db.str(db.one("SELECT payload FROM device_event WHERE id=?",rawId(started)),"payload")),"meterWh"));
        assertEquals(1,Db.num(db.one("SELECT COUNT(*) n FROM device_event_conflict WHERE original_event_id=?",rawId(started)),"n"));
        sessions.ingest(event(f,1,120000,"STOPPED",NOW.plusSeconds(600)));approve(f,propose(f,rawId(started)));assertEquals(2600,Db.num(bill(f),"payable_fen"));
    }
    @Test void replayUnknownIsVisiblePendingAndNewEvidenceInvalidatesUnapprovedProposal(){
        F f=fixture(false,false,80,50);start(f);var meter=event(f,1,110000,"METER",NOW.plusSeconds(600));sessions.ingest(meter);
        var bad=event(f,1,109999,"METER",NOW.plusSeconds(600));sessions.ingest(bad);sessions.ingest(event(f,2,120000,"STOPPED",NOW.plusSeconds(1200)));
        var proposal=propose(f,rawId(meter));var request=operations.requestReplay(f.ops,f.no,UUID.randomUUID().toString(),Map.of("reason","设备查单未知必须保留等待状态","fromSeq",1,"toSeq",1));
        assertEquals("DEVICE_QUERY_UNKNOWN",assertThrows(BusinessException.class,()->operations.fulfillReplay(Db.str(request,"requestNo"),Map.of("status","UNKNOWN","events",List.of()))).code());
        assertEquals("WAITING",Db.str(db.one("SELECT status FROM meter_replay_request WHERE request_no=?",request.get("requestNo")),"status"));
        sessions.ingest(event(f,1,109998,"METER",NOW.plusSeconds(600)));
        assertEquals("EVIDENCE_CHANGED",assertThrows(BusinessException.class,()->approve(f,proposal)).code());assertNull(bill(f));
    }
    @TestConfiguration static class ClockConfig{@Bean @Primary TestClock meterAcceptanceClock(){return new TestClock();}}
    static class TestClock extends Clock{final AtomicReference<Instant> now=new AtomicReference<>(NOW);public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}}
}

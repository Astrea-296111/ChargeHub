package com.chargehub.app;

import com.chargehub.infrastructure.*;
import com.chargehub.payment.*;
import com.chargehub.reconcile.*;
import com.chargehub.charging.ChargeService;
import com.chargehub.charging.SessionService;
import com.chargehub.station.StationService;
import com.chargehub.benefit.BenefitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Actual MySQL/InnoDB + production order/payment/reconciliation services; opt-in fresh isolated database only. */
@EnabledIfEnvironmentVariable(named="CHARGEHUB_FINANCE_TEST_JDBC_URL",matches=".+")
class FinanceInvariantIntegrationTest {
    static final Instant NOW=Instant.parse("2026-10-18T07:00:00Z");
    record Fixture(long user,long gun,String order,String pay){}
    @Test void financialFaultsConcurrencyAndAuditedRecoveryPreserveImmutableMoney()throws Exception{
        try(var context=new AnnotationConfigApplicationContext(Config.class)){
            var db=context.getBean(Db.class);var source=context.getBean(DataSource.class);
            try(var connection=source.getConnection()){
                assertEquals("MySQL",connection.getMetaData().getDatabaseProductName());assertTrue(connection.getMetaData().getDatabaseMajorVersion()>=8);
                assertTrue(connection.getCatalog().endsWith("_test"),"Refuse non-test database");
                assertEquals(0,Db.num(db.one("SELECT COUNT(*) n FROM information_schema.tables WHERE table_schema=DATABASE()"),"n"),"Use a fresh database; never erase existing rows");
                Path base=Path.of("../docs/spec/03-schema.sql");if(!base.toFile().exists())base=Path.of("docs/spec/03-schema.sql");
                ScriptUtils.executeSqlScript(connection,new FileSystemResource(base));
                var resolver=new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
                var migrations=new TreeMap<Integer,org.springframework.core.io.Resource>();
                for(var resource:resolver.getResources("classpath*:db/migration/V*__*.sql")){
                    String filename=Objects.requireNonNull(resource.getFilename());int version=Integer.parseInt(filename.substring(1,filename.indexOf("__")));
                    if(version>1)migrations.putIfAbsent(version,resource);
                }
                assertTrue(migrations.keySet().containsAll(Set.of(2,3,4,5,6,7)),"All module migrations must be on the test classpath");
                for(var migration:migrations.values())ScriptUtils.executeSqlScript(connection,migration);
            }
            var orders=context.getBean(ChargeService.class);var payments=context.getBean(PaymentService.class);var gateway=context.getBean(ChannelGateway.class);
            var finance=context.getBean(FinanceRecoveryService.class);var clock=context.getBean(TestClock.class);var station=context.getBean(StationService.class);var sessions=context.getBean(SessionService.class);
            long actor=db.insert("INSERT INTO iam_user(login_name,password_hash) VALUES(?,'disabled-finance-test')","finance-"+UUID.randomUUID());db.update("INSERT INTO iam_user_role(user_id,role_code) VALUES(?,'FINANCE')",actor);

            // A15/A16: distinct signed-event identities cannot duplicate the same transaction or hide currency/amount differences.
            Fixture a=fixture(db,orders,station);var paid=payment(a,"paid-a",10000,"CNY");orders.notification(paid);
            var another=new LinkedHashMap<>(paid);another.put("channelEventId","another-event-for-same-money");orders.notification(another);
            assertEquals(1,Db.num(db.one("SELECT COUNT(*) n FROM cash_entry WHERE biz_no=?",a.pay),"n"));
            for(boolean currency:new boolean[]{false,true}){
                Fixture bad=fixture(db,orders,station);var mismatch=payment(bad,"bad-"+bad.pay,currency?10000:9999,currency?"USD":"CNY");orders.notification(mismatch);
                assertEquals("DIFF",Db.str(db.one("SELECT financial_status FROM charge_order WHERE order_no=?",bad.order),"financial_status"));
                assertNull(db.one("SELECT id FROM device_command WHERE order_no=? AND command_type='START'",bad.order));
                assertEquals(0,Db.num(payments.getByOrder(bad.order),"captured_fen"));orders.closeFinancial(bad.order);
                assertEquals("DIFF",Db.str(db.one("SELECT financial_status FROM charge_order WHERE order_no=?",bad.order),"financial_status"));
            }
            Fixture owned=fixture(db,orders,station);var stolen=payment(owned,"provider-reuse",10000,"CNY");stolen.put("providerBizNo",paid.get("providerBizNo"));orders.notification(stolen);
            assertEquals(0,Db.num(payments.getByOrder(owned.order),"captured_fen"));

            // A18: timeout and payment at the exact deadline contend on actual user/order rows.
            Fixture race=fixture(db,orders,station);clock.now.set(NOW.plusSeconds(300));var deadline=payment(race,"at-deadline",10000,"CNY");deadline.put("occurredAt",clock.instant().toString());
            concurrent(List.of(()->{orders.expire(race.order);return null;},()->{orders.notification(deadline);return null;}));clock.now.set(NOW);
            assertEquals(10000,Db.num(payments.getByOrder(race.order),"captured_fen"));assertEquals(10000,Db.num(db.one("SELECT amount_fen FROM refund_order WHERE order_no=?",race.order),"amount_fen"));
            assertNull(db.one("SELECT id FROM device_command WHERE order_no=? AND command_type='START'",race.order));assertNull(db.one("SELECT active_order_no FROM connector WHERE id=?",race.gun).get("active_order_no"));
            // A19: cancellation before an actual success preserves capture and creates exactly one full refund.
            Fixture canceled=fixture(db,orders,station);orders.cancel(canceled.user,canceled.order);orders.notification(payment(canceled,"late-canceled",10000,"CNY"));
            assertEquals("CANCELED",Db.str(db.one("SELECT status FROM charge_order WHERE order_no=?",canceled.order),"status"));assertEquals(1,Db.num(db.one("SELECT COUNT(*) n FROM refund_order WHERE order_no=?",canceled.order),"n"));

            // A33/A35: timeout retains reservation. A separate independent gateway success is queried by the original number.
            String refund=Db.str(payments.ensureRefund(a.order,7900,"BALANCE"),"refund_no");payments.markRefundUnknown(refund,"TEST_RESPONSE_LOST");
            assertEquals(7900,Db.num(payments.getByOrder(a.order),"refund_reserved_fen"));
            assertEquals("REFUND_LIMIT",assertThrows(BusinessException.class,()->payments.ensureRefund(a.order,2101,"FULL_CANCEL")).code());
            gateway.channelRefund(refund,refundFact(a,refund,"SUCCESS",false,0));
            finance.refund(actor,refund,"recover-lost-refund","渠道成功但响应及通知丢失，按原号核实",false);
            assertEquals(0,Db.num(payments.getByOrder(a.order),"refund_reserved_fen"));assertEquals(7900,Db.num(payments.getByOrder(a.order),"refund_success_fen"));
            // A34: twelve concurrent callback identities for one refund cannot insert a second cash fact.
            List<Callable<Void>> callbacks=new ArrayList<>();for(int i=0;i<12;i++){int n=i;callbacks.add(()->{var event=ChannelFinancialFact.parse(gateway.channelRefund(refund),true).notification();event.put("channelEventId","concurrent-refund-"+n);orders.notification(event);return null;});}concurrent(callbacks);
            assertEquals(1,Db.num(db.one("SELECT COUNT(*) n FROM cash_entry WHERE biz_type='REFUND' AND biz_no=?",refund),"n"));assertEquals(7900,Db.num(payments.getByOrder(a.order),"refund_success_fen"));

            // Explicit retryable failure releases once, then the same refund number reserves once for the next attempt.
            Fixture retry=fixture(db,orders,station);orders.notification(payment(retry,"retry-paid",10000,"CNY"));String retryNo=Db.str(payments.ensureRefund(retry.order,7900,"BALANCE"),"refund_no");
            var failed=refundFact(retry,retryNo,"FAILED",true,0);gateway.channelRefund(retryNo,failed);orders.notification(ChannelFinancialFact.parse(failed,true).notification());
            assertEquals("FAILED_RETRYABLE",Db.str(db.one("SELECT status FROM refund_order WHERE refund_no=?",retryNo),"status"));
            finance.refund(actor,retryNo,"retry-original-refund","已查单确认可重试，原号重新预占",true);
            assertEquals("SUCCESS",Db.str(db.one("SELECT status FROM refund_order WHERE refund_no=?",retryNo),"status"));assertEquals(1,Db.num(db.one("SELECT COUNT(*) n FROM refund_order WHERE order_no=?",retry.order),"n"));

            // A43/A44: channel-only successful fact is repaired through ChargeService, evidence retained and replay harmless.
            Fixture missing=fixture(db,orders,station);gateway.channelPayment(missing.pay,paymentFact(missing));String csv=csv(gateway.channelPayment(missing.pay));
            var imported=finance.importCsv(actor,"import-missing-callback","MOCK","2026-10-18","PAYMENT","Asia/Shanghai",1,csv,"导入独立渠道账单并查询缺失通知");long batch=Long.parseLong(imported.get("batchId").toString());
            assertEquals(imported,finance.importCsv(actor,"import-missing-callback","MOCK","2026-10-18","PAYMENT","Asia/Shanghai",1,csv,"导入独立渠道账单并查询缺失通知"));
            long before=Db.num(db.one("SELECT COUNT(*) n FROM cash_entry WHERE biz_no=?",missing.pay),"n");assertEquals(0,before);
            finance.recoverBatch(actor,batch,"recover-missing-channel","独立查单核对业务号金额币种时间后统一补录");
            assertEquals(1,Db.num(db.one("SELECT COUNT(*) n FROM cash_entry WHERE biz_no=?",missing.pay),"n"));assertTrue(Db.num(db.one("SELECT COUNT(*) n FROM finance_evidence WHERE batch_id=? AND verified=1",batch),"n")>0);
            assertEquals(1,Db.num(db.one("SELECT COUNT(*) n FROM reconcile_item WHERE batch_id=? AND diff_type='CHANNEL_ONLY' AND status='RESOLVED'",batch),"n"));
            // A45: altered CSV amount keeps original cash/order evidence and blocks financial closure.
            long cashBefore=Db.num(db.one("SELECT SUM(amount_fen) n FROM cash_entry WHERE biz_no=?",missing.pay),"n");
            var wrong=finance.importCsv(actor,"import-amount-difference","MOCK","2026-10-18","PAYMENT","Asia/Shanghai",2,csv.replace(",10000,CNY,",",9999,CNY,"),"保留渠道文件金额不一致证据");long wrongBatch=Long.parseLong(wrong.get("batchId").toString());
            finance.recoverBatch(actor,wrongBatch,"query-amount-difference","不能通过改动已成功资金流水调平");orders.closeFinancial(missing.order);
            assertEquals("DIFF",Db.str(db.one("SELECT financial_status FROM charge_order WHERE order_no=?",missing.order),"financial_status"));assertEquals(cashBefore,Db.num(db.one("SELECT SUM(amount_fen) n FROM cash_entry WHERE biz_no=?",missing.pay),"n"));
            // A resolved reconciliation hold must release the old user binding only after exact financial closure.
            Fixture closing=fixture(db,orders,station);orders.notification(payment(closing,"closing-paid",10000,"CNY"));
            String closingSession=Db.str(db.one("SELECT session_no FROM charge_session WHERE order_no=?",closing.order),"session_no");
            sessions.ingest(meter(closing,closingSession,0,100000,"STARTED",NOW));
            orders.financialDifference(closing.order,"RECONCILIATION_DIFFERENCE");
            sessions.ingest(meter(closing,closingSession,1,120000,"STOPPED",NOW.plusSeconds(1800)));
            assertEquals("COMPLETED",Db.str(db.one("SELECT status FROM charge_order WHERE order_no=?",closing.order),"status"));
            assertEquals("DIFF",Db.str(db.one("SELECT financial_status FROM charge_order WHERE order_no=?",closing.order),"financial_status"));
            assertEquals(closing.order,Db.str(db.one("SELECT active_order_no FROM iam_user WHERE id=?",closing.user),"active_order_no"));
            var closingRefund=db.one("SELECT * FROM refund_order WHERE order_no=?",closing.order);assertEquals(7400,Db.num(closingRefund,"amount_fen"));
            var closingFact=refundFact(closing,Db.str(closingRefund,"refund_no"),"SUCCESS",false,0);closingFact.put("amountFen",7400);closingFact.put("occurredAt",NOW.plusSeconds(1801).toString());
            orders.notification(ChannelFinancialFact.parse(closingFact,true).notification());
            assertEquals(closing.order,Db.str(db.one("SELECT active_order_no FROM iam_user WHERE id=?",closing.user),"active_order_no"));
            orders.reconsiderFinancialClose(closing.order);
            assertEquals("CLOSED",Db.str(db.one("SELECT financial_status FROM charge_order WHERE order_no=?",closing.order),"financial_status"));
            assertNull(db.one("SELECT active_order_no FROM iam_user WHERE id=?",closing.user).get("active_order_no"));
            String nextOrder=Db.str(orders.create(closing.user,Map.of("connectorId",Long.toString(closing.gun),"expectedTariffVersion",1,"useMembership",false)),"order_no");
            orders.closeFinancial(closing.order); // repeated closure must not release the newer order or alter the gun
            assertEquals(nextOrder,Db.str(db.one("SELECT active_order_no FROM iam_user WHERE id=?",closing.user),"active_order_no"));
            var newerGun=db.one("SELECT active_order_no,status FROM connector WHERE id=?",closing.gun);assertEquals(nextOrder,Db.str(newerGun,"active_order_no"));assertEquals("RESERVED",Db.str(newerGun,"status"));
            orders.cancel(closing.user,nextOrder);

            // Canceling an unpaid order frees its binding but preserves the device's authoritative FAULT state.
            Fixture fault=fixture(db,orders,station);db.update("UPDATE connector SET reported_device_status='FAULT',reported_status_at=? WHERE id=?",NOW,fault.gun);
            orders.cancel(fault.user,fault.order);var closed=payment(fault,"closed-fault-"+UUID.randomUUID(),10000,"CNY");closed.put("type","PAYMENT_CLOSED");closed.put("status","CLOSED");
            orders.closedPayment(fault.order,closed);
            var faultGun=db.one("SELECT * FROM connector WHERE id=?",fault.gun);assertNull(faultGun.get("active_order_no"));assertEquals("FAULT",Db.str(faultGun,"status"));assertEquals("FAULT",Db.str(faultGun,"reported_device_status"));
            assertEquals("CANCELED",Db.str(db.one("SELECT status FROM charge_order WHERE order_no=?",fault.order),"status"));
            assertTrue(Db.num(db.one("SELECT COUNT(*) n FROM ops_audit WHERE operator_id=?",actor),"n")>=6);
        }
    }
    static Fixture fixture(Db db,ChargeService orders,StationService stations){
        String id=UUID.randomUUID().toString();long user=db.insert("INSERT INTO iam_user(login_name,password_hash) VALUES(?,'disabled-test')","f-"+id);
        long station=db.insert("INSERT INTO station(station_code,name,address) VALUES(?,'Finance test','isolated')",id);long gun=db.insert("INSERT INTO connector(station_id,device_id,connector_code) VALUES(?,'finance-device',?)",station,id);
        var tariff=stations.createTariff(Map.of("stationId",Long.toString(station),"versionNo",1,"slots",List.of(Map.of("startMinute",0,"endMinute",1440,"energyRateFenPerKwh",80,"serviceRateFenPerKwh",50))));stations.publishTariff(Long.parseLong(tariff.get("tariffId").toString()));
        String order=Db.str(orders.create(user,Map.of("connectorId",Long.toString(gun),"expectedTariffVersion",1,"useMembership",false)),"order_no");String pay=Db.str(orders.payment(user,order),"pay_no");return new Fixture(user,gun,order,pay);
    }
    static Map<String,Object> payment(Fixture f,String id,long amount,String currency){return new LinkedHashMap<>(Map.of("channelEventId",id,"type","PAYMENT_SUCCESS","payNo",f.pay,"providerBizNo","provider-"+f.pay,"amountFen",amount,"currency",currency,"occurredAt",NOW.toString()));}
    static Map<String,Object> paymentFact(Fixture f){var map=payment(f,"channel-"+f.pay,10000,"CNY");map.put("status","SUCCESS");map.put("providerTradeNo",map.get("providerBizNo"));return map;}
    static Map<String,Object> refundFact(Fixture f,String no,String status,boolean retryable,int attempt){var map=new LinkedHashMap<String,Object>();map.put("payNo",f.pay);map.put("refundNo",no);map.put("providerRefundNo","refund-provider-"+no);map.put("amountFen",7900);map.put("currency","CNY");map.put("status",status);map.put("occurredAt",NOW.toString());map.put("channelEventId","channel-refund-"+no+"-"+attempt);map.put("retryable",retryable);map.put("attempt",attempt);return map;}
    static Map<String,Object> meter(Fixture f,String session,int seq,long wh,String type,Instant at){var event=new LinkedHashMap<String,Object>();event.put("eventId",UUID.randomUUID().toString());event.put("deviceId","finance-device");event.put("connectorId",Long.toString(f.gun));event.put("sessionNo",session);event.put("seqNo",seq);event.put("meterWh",wh);event.put("eventType",type);event.put("occurredAt",at.toString());event.put("sampleKind",seq==0?"START":"END");if("STOPPED".equals(type))event.put("finalSeq",seq);return event;}
    static String csv(Map<String,Object> f){return ChannelCsv.HEADER+"\nMOCK,PAYMENT,2026-10-18,Asia/Shanghai,"+f.get("providerTradeNo")+","+f.get("payNo")+",,10000,CNY,"+f.get("occurredAt")+",SUCCESS\n";}
    static void concurrent(List<Callable<Void>> actions)throws Exception{var gate=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(actions.size())){List<Future<Void>> futures=new ArrayList<>();for(var action:actions)futures.add(pool.submit(()->{gate.await();return action.call();}));gate.countDown();for(var future:futures)future.get(30,TimeUnit.SECONDS);}}
    static class TestClock extends Clock{final AtomicReference<Instant> now=new AtomicReference<>(NOW);public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now.get();}}
    /** Facts are test-controlled independent channel responses, never exported from platform SQL. */
    static class ChannelGateway extends PaymentService{
        final Map<String,Map<String,Object>> payments=new ConcurrentHashMap<>(),refunds=new ConcurrentHashMap<>();
        ChannelGateway(Db db){super(db,new Hmac(db,new MockEnvironment()),new MockEnvironment());}
        public void channelRefund(String no,Map<String,Object> fact){refunds.put(no,new LinkedHashMap<>(fact));}
        public Map<String,Object> channelRefund(String no){return new LinkedHashMap<>(refunds.get(no));}
        public void channelPayment(String no,Map<String,Object> fact){payments.put(no,new LinkedHashMap<>(fact));}
        public Map<String,Object> channelPayment(String no){return new LinkedHashMap<>(payments.get(no));}
        @Override public Map<String,Object> queryPayment(String no){assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"No DB transaction may span channel I/O");return payments.containsKey(no)?new LinkedHashMap<>(payments.get(no)):Map.of("status","NOT_FOUND","outTradeNo",no);}
        @Override public Map<String,Object> queryRefund(String no){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return refunds.containsKey(no)?new LinkedHashMap<>(refunds.get(no)):Map.of("status","NOT_FOUND","outRefundNo",no);}
        @Override public Map<String,Object> sendRefund(String no){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());var fact=new LinkedHashMap<>(refunds.get(no));fact.put("status","SUCCESS");fact.put("retryable",false);fact.put("attempt",((Number)fact.get("attempt")).intValue()+1);fact.put("channelEventId","retried-"+no);refunds.put(no,fact);return fact;}
        @Override public Map<String,Object> exportBill(String date,String type,String timezone){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());String body=ChannelCsv.HEADER+"\n";for(var fact:payments.values())body+=csv(fact).substring(ChannelCsv.HEADER.length()+1);return Map.of("content",body,"fileHash",Json.hash(body),"complete",true,"totalCount",payments.size());}
    }
    @org.springframework.boot.test.context.TestConfiguration @EnableTransactionManagement(proxyTargetClass=true) static class Config{
        @Bean DataSource source(){return new DriverManagerDataSource(System.getenv("CHARGEHUB_FINANCE_TEST_JDBC_URL"),System.getenv().getOrDefault("CHARGEHUB_FINANCE_TEST_DB_USER","root"),System.getenv("CHARGEHUB_FINANCE_TEST_DB_PASSWORD"));}
        @Bean JdbcTemplate jdbc(DataSource source){return new JdbcTemplate(source);}@Bean Db db(JdbcTemplate jdbc){return new Db(jdbc);}
        @Bean DataSourceTransactionManager transactionManager(DataSource source){return new DataSourceTransactionManager(source);}
        @Bean TestClock clock(){return new TestClock();}@Bean ChannelGateway payment(Db db){return new ChannelGateway(db);}
        @Bean StationService station(Db db,TestClock clock){return new StationService(db,clock);}@Bean BenefitService benefits(Db db,TestClock clock){return new BenefitService(db,clock);}
        @Bean Outbox outbox(Db db,DataSourceTransactionManager tm){return new Outbox(db,tm);}
        @Bean ChargeService orders(Db db,StationService stations,BenefitService benefits,PaymentService payments,Outbox outbox,TestClock clock){return new ChargeService(db,stations,benefits,payments,outbox,clock);}
        @Bean SessionService sessions(Db db,BenefitService benefits,PaymentService payments,Outbox outbox,TestClock clock){return new SessionService(db,benefits,payments,outbox,clock);}
        @Bean ReconciliationService reconciliation(Db db){return new ReconciliationService(db);}
        @Bean FinanceOperationStore operations(Db db,DataSourceTransactionManager tm){return new FinanceOperationStore(db,tm);}
        @Bean FinanceRecoveryService recovery(Db db,PaymentService payments,ReconciliationService reconciliation,FinanceOperationStore operations,ChargeService orders,DataSourceTransactionManager tm){return new FinanceRecoveryService(db,payments,reconciliation,operations,orders,tm);}
    }
}

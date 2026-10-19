package com.chargehub.app;

import com.chargehub.benefit.*;
import com.chargehub.charging.ChargeService;
import com.chargehub.charging.SessionService;
import com.chargehub.infrastructure.*;
import com.chargehub.payment.PaymentService;
import com.chargehub.station.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real InnoDB + Redis acceptance gate; the independent database must have a name ending in _test. */
@SpringBootTest(properties = {
        "spring.datasource.url=${CHARGEHUB_CATALOG_TEST_JDBC_URL}",
        "spring.datasource.username=${CHARGEHUB_CATALOG_TEST_DB_USER}",
        "spring.datasource.password=${CHARGEHUB_CATALOG_TEST_DB_PASSWORD}",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED",
        "CHARGEHUB_FIXED_TIME=", "spring.main.banner-mode=off",
        "chargehub.redis.enabled=true",
        "chargehub.redis.host=${CHARGEHUB_CATALOG_TEST_REDIS_HOST:127.0.0.1}",
        "chargehub.redis.port=${CHARGEHUB_CATALOG_TEST_REDIS_PORT:6379}",
        "chargehub.redis.password=${CHARGEHUB_CATALOG_TEST_REDIS_PASSWORD:}",
        "chargehub.redis.timeout-ms=100", "chargehub.redis.ttl-seconds=30", "chargehub.redis.jitter-seconds=10",
        "management.health.redis.enabled=false",
        "chargehub.payment-key=test-payment-catalog-key-123456789",
        "chargehub.device-key=test-device-catalog-key-123456789",
        "chargehub.control-key=test-control-catalog-key-123456789"
})
@ActiveProfiles("test")
@Import(CatalogMysqlRedisTest.ClockConfiguration.class)
@EnabledIfEnvironmentVariable(named = "CHARGEHUB_CATALOG_TEST_JDBC_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogMysqlRedisTest {
    private static final Instant NOW = Instant.parse("2026-10-18T07:00:00Z");
    private static final String PREFIX = "chargehub:test:catalog:" + UUID.randomUUID() + ":";
    @Autowired Db db;
    @Autowired StationService stations;
    @Autowired CatalogOperationsService catalogue;
    @Autowired BenefitService benefits;
    @Autowired BenefitOperationsService benefitOperations;
    @Autowired ChargeService orders;
    @Autowired SessionService sessions;
    @Autowired PaymentService payments;
    @Autowired Idempotency idempotency;
    @Autowired Outbox outbox;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MutableClock clock;
    @Autowired @Qualifier("catalogRedisConnectionFactory") LettuceConnectionFactory redisFactory;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("chargehub.redis.key-prefix", () -> PREFIX);
    }

    @BeforeEach void timeAndDatabaseGuard() {
        clock.time.set(NOW);
        assertTrue(Db.str(db.one("SELECT DATABASE() db"), "db").endsWith("_test"));
        assertTrue(Db.str(db.one("SELECT VERSION() version"), "version").startsWith("8."));
    }

    record Fixture(long operator, long user, long other, long station, long gun, long otherGun) {}

    Fixture fixture() {
        long operator = user(), user = user(), other = user();
        long station = Db.num(catalogue.createStation(operator, new CatalogCommands.StationInput(
                "T-" + UUID.randomUUID(), "验收模拟站点", "验收模拟地址", "Asia/Shanghai", "验收创建模拟站点")), "id");
        long gun = Db.num(catalogue.createConnector(operator, new CatalogCommands.ConnectorInput(String.valueOf(station),
                "device-" + UUID.randomUUID(), "GUN-A", "验收创建第一把枪")), "id");
        long otherGun = Db.num(catalogue.createConnector(operator, new CatalogCommands.ConnectorInput(String.valueOf(station),
                "device-" + UUID.randomUUID(), "GUN-B", "验收创建第二把枪")), "id");
        long tariff = Long.parseLong(stations.createTariff(Map.of("stationId", station, "versionNo", 1, "slots", List.of(slot(0, 1440, 80)))).get("tariffId").toString());
        stations.publishTariff(tariff);
        return new Fixture(operator, user, other, station, gun, otherGun);
    }

    long user() {
        return db.insert("INSERT INTO iam_user(login_name,password_hash) VALUES(?,'not-a-login-hash')", "catalog-" + UUID.randomUUID());
    }

    static Map<String, Object> slot(int from, int until, int energy) {
        return Map.of("startMinute", from, "endMinute", until, "energyRateFenPerKwh", energy, "serviceRateFenPerKwh", 50);
    }

    Map<String, Object> body(long gun, boolean member, Long coupon) {
        var body = new LinkedHashMap<String, Object>();
        body.put("connectorId", String.valueOf(gun)); body.put("expectedTariffVersion", 1);
        body.put("useMembership", member); body.put("couponId", coupon == null ? null : String.valueOf(coupon));
        return body;
    }

    Map<String, Object> create(long user, Map<String, Object> body, String key) {
        return idempotency.execute(user, "catalog-test.create", key, body, () -> orders.create(user, body));
    }

    long template(int count, Instant end) {
        long id = Long.parseLong(benefits.createTemplate(Map.of("name", "验收优惠券", "scope", "ALL_STATIONS",
                "claimFrom", NOW.minusSeconds(3600).toString(), "claimUntil", end.toString(),
                "useValidFrom", NOW.minusSeconds(3600).toString(), "useValidUntil", end.toString(),
                "thresholdFen", 500, "discountFen", 300, "totalCount", count)).get("templateId").toString());
        benefits.publishTemplate(id);
        return id;
    }

    void member(Fixture fixture, long user) {
        benefitOperations.grantMembership(fixture.operator, user, new BenefitCommands.MembershipInput("DEMO_MONTHLY",
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"), "验收授权演示会员"));
    }

    @Test @Order(1)
    void A03_A04_publicationCoverageFrozenBillingAndCommitOnlyCacheInvalidation() {
        var f = fixture();
        var redis = new StringRedisTemplate(redisFactory);
        assertEquals("PONG", redisFactory.getConnection().ping());
        stations.get(f.station);
        String generation = redis.opsForValue().get(PREFIX + "generation");
        Long ttl = redis.getExpire(PREFIX + generation + ":station:" + f.station, TimeUnit.SECONDS);
        assertNotNull(ttl); assertTrue(ttl > 0 && ttl <= 40);
        long invalid = Long.parseLong(stations.createTariff(Map.of("stationId", f.station, "versionNo", 2,
                "slots", List.of(slot(0, 480, 100), slot(481, 1440, 100)))).get("tariffId").toString());
        assertThrows(BusinessException.class, () -> stations.publishTariff(invalid));
        assertEquals(1, Db.num(stations.tariff(f.gun, null), "tariffVersion"));
        assertThrows(BusinessException.class, () -> stations.createTariff(Map.of("stationId", f.station, "versionNo", 3,
                "slots", List.of(slot(0, 481, 100), slot(480, 1440, 100)))));
        String order = Db.str(create(f.user, body(f.gun, false, null), UUID.randomUUID().toString()), "orderNo");
        long newer = Long.parseLong(stations.createTariff(Map.of("stationId", f.station, "versionNo", 4,
                "slots", List.of(slot(0, 1440, 120)))).get("tariffId").toString());
        var transaction = new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            stations.publishTariff(newer);
            assertEquals(generation, redis.opsForValue().get(PREFIX + "generation"));
            throw new IllegalStateException("Injected transaction rollback");
        }));
        assertEquals(generation, redis.opsForValue().get(PREFIX + "generation"));
        assertEquals(1, Db.num(stations.tariff(f.gun, null), "tariffVersion"));
        stations.publishTariff(newer);
        assertNotEquals(generation, redis.opsForValue().get(PREFIX + "generation"));
        assertEquals(4, Db.num(stations.displayTariff(f.gun), "tariffVersion"));
        bill(order, f.gun);
        assertEquals(1600, Db.num(db.one("SELECT energy_fen FROM charge_bill WHERE order_no=?", order), "energy_fen"));
        assertEquals(2600, Db.num(db.one("SELECT payable_fen FROM charge_bill WHERE order_no=?", order), "payable_fen"));
    }

    @Test @Order(2)
    void A09_A10_A11_A12_A13_resourceContentionIdempotencyAndAllOrNothingBenefits() throws Exception {
        var f = fixture();
        var results = concurrent(() -> create(f.user, body(f.gun, false, null), UUID.randomUUID().toString()),
                () -> create(f.user, body(f.otherGun, false, null), UUID.randomUUID().toString()));
        assertEquals(1, results.stream().filter(Objects::nonNull).count());
        String active = Db.str(db.one("SELECT active_order_no FROM iam_user WHERE id=?", f.user), "active_order_no");
        orders.cancel(f.user, active);

        member(f, f.user); member(f, f.other);
        long template = template(2, NOW.plusSeconds(7200));
        long couponA = Long.parseLong(benefits.claim(f.user, template).get("couponId").toString());
        long couponB = Long.parseLong(benefits.claim(f.other, template).get("couponId").toString());
        results = concurrent(() -> create(f.user, body(f.gun, true, couponA), UUID.randomUUID().toString()),
                () -> create(f.other, body(f.gun, true, couponB), UUID.randomUUID().toString()));
        assertEquals(1, results.stream().filter(Objects::nonNull).count());
        assertEquals(1, Db.num(db.one("SELECT COUNT(*) n FROM user_coupon WHERE template_id=? AND status='LOCKED'", template), "n"));
        assertEquals(1, Db.num(db.one("SELECT COUNT(*) n FROM user_coupon WHERE template_id=? AND status='AVAILABLE'", template), "n"));
        var gun = db.one("SELECT * FROM connector WHERE id=?", f.gun);
        String winner = Db.str(gun, "active_order_no");
        long winnerUser = Db.num(db.one("SELECT user_id FROM charge_order WHERE order_no=?", winner), "user_id");
        orders.cancel(winnerUser, winner);

        var key = UUID.randomUUID().toString();
        var request = body(f.gun, true, couponA);
        var first = create(f.user, request, key);
        assertEquals(first, create(f.user, request, key));
        assertEquals(409, assertThrows(BusinessException.class, () -> create(f.user, body(f.otherGun, true, couponA), key)).status());
        assertEquals(409, assertThrows(BusinessException.class, () -> create(f.user, body(f.gun, true, couponB), key)).status());
        String existingOrder = Db.str(first, "orderNo");
        assertThrows(BusinessException.class, () -> catalogue.stationStatus(f.operator, f.station,
                new CatalogCommands.StatusInput(false, "验收禁止停用活动站")));
        assertThrows(BusinessException.class, () -> catalogue.connectorStatus(f.operator, f.gun,
                new CatalogCommands.StatusInput(false, "验收禁止停用活动枪")));
        assertEquals(existingOrder, Db.str(db.one("SELECT active_order_no FROM connector WHERE id=?", f.gun), "active_order_no"));
        orders.cancel(f.user, existingOrder);

        long orderCount = Db.num(db.one("SELECT COUNT(*) n FROM charge_order WHERE user_id=?", f.user), "n");
        long outboxCount = Db.num(db.one("SELECT COUNT(*) n FROM outbox_event"), "n");
        String rollbackKey = UUID.randomUUID().toString();
        assertThrows(org.springframework.dao.DataAccessException.class, () -> idempotency.execute(f.user, "rollback-benefits", rollbackKey, request, () -> {
            var created = orders.create(f.user, request);
            outbox.enqueue("InjectedRollback", Db.str(created, "order_no"), Map.of("injected", true));
            db.update("INSERT INTO member_quota(column_does_not_exist) VALUES(1)");
            return created;
        }));
        assertEquals(orderCount, Db.num(db.one("SELECT COUNT(*) n FROM charge_order WHERE user_id=?", f.user), "n"));
        assertEquals(outboxCount, Db.num(db.one("SELECT COUNT(*) n FROM outbox_event"), "n"));
        assertNull(Db.str(db.one("SELECT active_order_no FROM connector WHERE id=?", f.gun), "active_order_no"));
        assertNull(Db.str(db.one("SELECT active_order_no FROM iam_user WHERE id=?", f.user), "active_order_no"));
        assertEquals("AVAILABLE", Db.str(db.one("SELECT status FROM user_coupon WHERE id=?", couponA), "status"));
        assertEquals(0, Db.num(db.one("SELECT reserved_count FROM member_quota WHERE user_id=?", f.user), "reserved_count"));
        assertNull(db.one("SELECT id FROM idempotency_record WHERE idem_key=?", rollbackKey));

        // Freeze eligibility under the same station -> connector lock order as operational disable.
        var disableRace = fixture();
        member(disableRace, disableRace.user);
        long raceTemplate = template(1, NOW.plusSeconds(7200));
        long raceCoupon = Long.parseLong(benefits.claim(disableRace.user, raceTemplate).get("couponId").toString());
        var raced = concurrent(
                () -> create(disableRace.user, body(disableRace.gun, true, raceCoupon), UUID.randomUUID().toString()),
                () -> catalogue.stationStatus(disableRace.operator, disableRace.station,
                        new CatalogCommands.StatusInput(false, "验收停用站点与下单竞争")));
        assertEquals(1, raced.stream().filter(Objects::nonNull).count());
        String bound = Db.str(db.one("SELECT active_order_no FROM iam_user WHERE id=?", disableRace.user), "active_order_no");
        if (bound == null) {
            assertEquals("DISABLED", Db.str(db.one("SELECT status FROM station WHERE id=?", disableRace.station), "status"));
            assertEquals("AVAILABLE", Db.str(db.one("SELECT status FROM user_coupon WHERE id=?", raceCoupon), "status"));
            assertEquals(0, Db.num(db.one("SELECT reserved_count FROM member_quota WHERE user_id=?", disableRace.user), "reserved_count"));
        } else {
            assertEquals("ENABLED", Db.str(db.one("SELECT status FROM station WHERE id=?", disableRace.station), "status"));
            assertEquals(bound, Db.str(db.one("SELECT active_order_no FROM connector WHERE id=?", disableRace.gun), "active_order_no"));
            orders.cancel(disableRace.user, bound);
        }
    }

    @Test @Order(3)
    void A29_A30_A31_expiryFrozenMonthZeroDiscountAndPublishedTemplateProtection() {
        var f = fixture(); member(f, f.user);
        long template = template(3, NOW.plusSeconds(120));
        long coupon = Long.parseLong(benefits.claim(f.user, template).get("couponId").toString());
        String no = UUID.randomUUID().toString();
        benefits.reserve(f.user, no, true, coupon);
        benefits.finalizeReservation(no, 0, 0);
        assertEquals("AVAILABLE", Db.str(db.one("SELECT status FROM user_coupon WHERE id=?", coupon), "status"));
        assertEquals(0, Db.num(db.one("SELECT used_count FROM member_quota WHERE user_id=?", f.user), "used_count"));
        String expires = UUID.randomUUID().toString();
        benefits.reserve(f.user, expires, true, coupon);
        clock.time.set(NOW.plusSeconds(121));
        benefits.finalizeReservation(expires, 0, 0);
        assertEquals("EXPIRED", Db.str(db.one("SELECT status FROM user_coupon WHERE id=?", coupon), "status"));
        assertEquals(1, Db.num(db.one("SELECT issued_count FROM coupon_template WHERE id=?", template), "issued_count"));
        clock.time.set(Instant.parse("2026-10-31T15:59:00Z"));
        String crossing = UUID.randomUUID().toString();
        benefits.reserve(f.user, crossing, true, null);
        clock.time.set(Instant.parse("2026-10-31T16:01:00Z"));
        benefitOperations.membershipStatus(f.operator, f.user, new BenefitCommands.StatusInput(false, "验收停用会员不撤销旧权益"));
        benefits.finalizeReservation(crossing, 100, 0);
        assertEquals(1, Db.num(db.one("SELECT used_count FROM member_quota WHERE user_id=? AND period_month='2026-10'", f.user), "used_count"));
        assertNull(db.one("SELECT id FROM member_quota WHERE user_id=? AND period_month='2026-11'", f.user));
        var edit = new BenefitCommands.TemplateInput("禁止覆盖已发布模板", NOW.minusSeconds(100), NOW.plusSeconds(7200), NOW.minusSeconds(100), NOW.plusSeconds(7200), 500L, 300L, 3, "ALL_STATIONS", "验收已发布模板禁止改写");
        assertEquals("COUPON_TEMPLATE_IMMUTABLE", assertThrows(BusinessException.class,
                () -> benefitOperations.updateTemplate(f.operator, template, edit)).code());
    }

    @Test @Order(4)
    void operationalDisableAndMembershipRegrantPreserveDatabaseRulesAndFrozenReservations() {
        var f = fixture();
        member(f, f.user);
        String reservation = UUID.randomUUID().toString();
        benefits.reserve(f.user, reservation, true, null);
        String frozen = Db.str(db.one("SELECT member_rule_snapshot FROM benefit_reservation WHERE order_no=?", reservation), "member_rule_snapshot");
        member(f, f.user);
        assertEquals(1, Db.num(db.one("SELECT reserved_count FROM member_quota WHERE user_id=?", f.user), "reserved_count"));
        assertEquals(2, Db.num(db.one("SELECT total_count FROM member_quota WHERE user_id=?", f.user), "total_count"));
        assertEquals(frozen, Db.str(db.one("SELECT member_rule_snapshot FROM benefit_reservation WHERE order_no=?", reservation), "member_rule_snapshot"));
        benefitOperations.membershipStatus(f.operator, f.user, new BenefitCommands.StatusInput(false, "验收会员停用但保留订单权益"));
        assertThrows(BusinessException.class, () -> benefits.preview(f.user, true, null));
        benefits.finalizeReservation(reservation, 100, 0);
        assertEquals(1, Db.num(db.one("SELECT used_count FROM member_quota WHERE user_id=?", f.user), "used_count"));

        catalogue.connectorStatus(f.operator, f.gun, new CatalogCommands.StatusInput(false, "验收停用空闲连接器"));
        db.update("UPDATE connector SET status='IDLE' WHERE id=?", f.gun); // Late device IDLE does not re-enable operations.
        assertThrows(BusinessException.class, () -> stations.tariff(f.gun, null));
        assertEquals(Boolean.FALSE, stations.connector(f.gun).get("available"));
        catalogue.stationStatus(f.operator, f.station, new CatalogCommands.StatusInput(false, "验收停用空闲模拟站点"));
        assertThrows(BusinessException.class, () -> stations.get(f.station));
        assertTrue(((List<?>) stations.list(null, 100).get("items")).stream()
                .noneMatch(item -> String.valueOf(f.station).equals(((Map<?, ?>) item).get("stationId"))));
        catalogue.stationStatus(f.operator, f.station, new CatalogCommands.StatusInput(true, "验收重新启用模拟站点"));
        assertThrows(BusinessException.class, () -> stations.tariff(f.gun, null));
        assertNotNull(stations.tariff(f.otherGun, null));
        assertEquals(400, assertThrows(BusinessException.class,
                () -> catalogue.createStation(f.operator, null)).status());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> benefitOperations.grantMembership(f.operator, f.user, null)).status());
    }

    @Test @Order(5)
    void A39_redisDisconnectCannotPreventDatabaseReadsOrdersClaimsOrRefundIntents() {
        var f = fixture();
        var redis = new StringRedisTemplate(redisFactory);
        stations.get(f.station); // Populate real Redis first; the gate is not a mock-only cache test.
        assertNotNull(redis.opsForValue().get(PREFIX + "generation"));
        redisFactory.stop(); // Deterministic connection outage, leaving the shared Redis process untouched.
        assertNotNull(stations.get(f.station));
        long template = template(1, NOW.plusSeconds(7200));
        assertNotNull(benefits.claim(f.other, template));
        String order = Db.str(create(f.user, body(f.gun, false, null), UUID.randomUUID().toString()), "orderNo");
        bill(order, f.gun);
        assertEquals(7400, Db.num(db.one("SELECT amount_fen FROM refund_order WHERE order_no=?", order), "amount_fen"));
        assertEquals(0, Db.num(db.one("SELECT COUNT(*) n FROM member_quota WHERE reserved_count+used_count>total_count"), "n"));
        assertEquals(0, Db.num(db.one("SELECT COUNT(*) n FROM coupon_template WHERE issued_count>total_count"), "n"));
        assertEquals(0, Db.num(db.one("SELECT COUNT(*) n FROM payment_order WHERE refund_reserved_fen+refund_success_fen>captured_fen"), "n"));
    }

    void bill(String no, long gun) {
        String pay = Db.str(orders.payment(Db.num(db.one("SELECT user_id FROM charge_order WHERE order_no=?", no), "user_id"), no), "pay_no");
        orders.notification(Map.of("channelEventId", "catalog-" + UUID.randomUUID(), "type", "PAYMENT_SUCCESS", "payNo", pay,
                "providerBizNo", "provider-" + UUID.randomUUID(), "amountFen", 10000, "currency", "CNY", "occurredAt", clock.instant().toString()));
        String session = Db.str(db.one("SELECT session_no FROM charge_session WHERE order_no=?", no), "session_no");
        sessions.ingest(meter(session, gun, "STARTED", 0, 100000, clock.instant()));
        sessions.ingest(meter(session, gun, "STOPPED", 1, 120000, clock.instant().plusSeconds(1800)));
        assertNotNull(db.one("SELECT id FROM charge_bill WHERE order_no=?", no));
    }

    Map<String, Object> meter(String session, long gun, String type, int sequence, long wh, Instant at) {
        var event = new LinkedHashMap<String, Object>();
        event.put("eventId", UUID.randomUUID().toString()); event.put("sessionNo", session);
        event.put("deviceId", Db.str(db.one("SELECT device_id FROM connector WHERE id=?", gun), "device_id"));
        event.put("connectorId", String.valueOf(gun)); event.put("eventType", type); event.put("seqNo", sequence);
        event.put("meterWh", wh); event.put("occurredAt", at.toString()); event.put("sampleKind", sequence == 0 ? "START" : "END");
        if (sequence > 0) event.put("finalSeq", sequence);
        return event;
    }

    @SafeVarargs final List<Map<String, Object>> concurrent(Callable<Map<String, Object>>... calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(calls.length)) {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (var call : calls) futures.add(executor.submit(() -> {
                start.await();
                try { return call.call(); }
                catch (BusinessException conflict) { assertEquals(409, conflict.status()); return null; }
            }));
            start.countDown();
            List<Map<String, Object>> result = new ArrayList<>();
            for (var future : futures) result.add(future.get(20, TimeUnit.SECONDS));
            return result;
        }
    }

    @TestConfiguration static class ClockConfiguration {
        @Bean @Primary MutableClock catalogClock() { return new MutableClock(); }
    }

    static final class MutableClock extends Clock {
        final AtomicReference<Instant> time = new AtomicReference<>(NOW);
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return time.get(); }
    }
}

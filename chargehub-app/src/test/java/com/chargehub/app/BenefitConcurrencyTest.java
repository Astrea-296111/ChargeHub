package com.chargehub.app;

import com.chargehub.benefit.BenefitService;
import com.chargehub.infrastructure.BusinessException;
import com.chargehub.infrastructure.Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real MySQL gate; requires an EMPTY dedicated database with a name ending in _test. */
@EnabledIfEnvironmentVariable(named = "CHARGEHUB_TEST_JDBC_URL", matches = ".+")
class BenefitConcurrencyTest {
    @Test
    void inventoryOwnershipQuotaAndExpiryRemainConsistentUnderConcurrency() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            DataSource source = context.getBean(DataSource.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Db db = context.getBean(Db.class);
            BenefitService benefits = context.getBean(BenefitService.class);
            MutableClock clock = context.getBean(MutableClock.class);
            try (var connection = source.getConnection()) {
                assertEquals("MySQL", connection.getMetaData().getDatabaseProductName(), "Use real MySQL, not a substitute database");
                assertTrue(connection.getMetaData().getDatabaseMajorVersion() >= 8);
                assertTrue(connection.getCatalog().endsWith("_test"), "Refuse to initialize a non-test database");
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class),
                        "Use a fresh empty test database; no existing data is removed");
                Path schema = Path.of("../docs/spec/03-schema.sql");
                if (!schema.toFile().exists()) schema = Path.of("docs/spec/03-schema.sql");
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(schema));
            }

            for (int user = 1; user <= 1002; user++) {
                jdbc.update("INSERT INTO iam_user(id,login_name,password_hash) VALUES(?,?,?)", user, "concurrency-" + user, "disabled-test-login");
            }
            long limited = template(benefits, "100张并发库存", 100);
            List<Callable<Boolean>> attempts = new ArrayList<>();
            for (long user = 1; user <= 1000; user++) {
                long id = user;
                attempts.add(() -> claim(benefits, id, limited));
            }
            assertEquals(100, successes(attempts));
            assertEquals(100L, db.num(db.one("SELECT issued_count FROM coupon_template WHERE id=?", limited), "issued_count"));
            assertEquals(100L, db.num(db.one("SELECT COUNT(*) n FROM user_coupon WHERE template_id=?", limited), "n"));

            long perUser = template(benefits, "同用户并发领取", 100);
            attempts.clear();
            for (int i = 0; i < 32; i++) attempts.add(() -> claim(benefits, 1002, perUser));
            assertEquals(1, successes(attempts));
            assertEquals(1L, db.num(db.one("SELECT issued_count FROM coupon_template WHERE id=?", perUser), "issued_count"));

            jdbc.update("INSERT INTO membership(user_id,plan_code,valid_from,valid_until) VALUES(1001,'DEMO',?,?)",
                    java.sql.Timestamp.from(Instant.parse("2026-10-01T00:00:00Z")), java.sql.Timestamp.from(Instant.parse("2026-10-31T16:00:00Z")));
            String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
            benefits.reserve(1001, first, true, null);
            benefits.reserve(1001, second, true, null);
            BusinessException exhausted = assertThrows(BusinessException.class,
                    () -> benefits.reserve(1001, UUID.randomUUID().toString(), true, null));
            assertEquals("MEMBER_QUOTA_EXHAUSTED", exhausted.code());
            benefits.release(first);
            benefits.release(first); // A repeated compensation cannot create an extra use.
            String third = UUID.randomUUID().toString();
            benefits.reserve(1001, third, true, null);
            benefits.finalizeReservation(second, 100, 0);
            benefits.finalizeReservation(second, 100, 0);
            benefits.release(third);
            var quota = db.one("SELECT * FROM member_quota WHERE user_id=1001 AND period_month='2026-10'");
            assertEquals(1, db.num(quota, "used_count"));
            assertEquals(0, db.num(quota, "reserved_count"));

            long expiryTemplate = template(benefits, "自然过期核销", 2);
            long usedCoupon = Long.parseLong(benefits.claim(1001, expiryTemplate).get("couponId").toString());
            long releasedCoupon = db.num(db.one("SELECT id FROM user_coupon WHERE user_id=1002 AND template_id=?", perUser), "id");
            clock.time.set(Instant.parse("2026-10-31T15:59:00Z"));
            String crossingMonth = UUID.randomUUID().toString(), expiredCancellation = UUID.randomUUID().toString();
            var frozen = benefits.reserve(1001, crossingMonth, true, usedCoupon);
            assertEquals("2026-10", ((Map<?, ?>) frozen.get("memberRuleSnapshot")).get("periodMonth"));
            benefits.reserve(1002, expiredCancellation, false, releasedCoupon);
            clock.time.set(Instant.parse("2026-10-31T16:01:00Z"));
            jdbc.update("UPDATE iam_user SET enabled=0 WHERE id=1001");
            benefits.finalizeReservation(crossingMonth, 200, 300);
            benefits.release(expiredCancellation);
            assertEquals("USED", db.str(db.one("SELECT status FROM user_coupon WHERE id=?", usedCoupon), "status"));
            assertEquals("EXPIRED", db.str(db.one("SELECT status FROM user_coupon WHERE id=?", releasedCoupon), "status"));
            quota = db.one("SELECT * FROM member_quota WHERE user_id=1001 AND period_month='2026-10'");
            assertEquals(2, db.num(quota, "used_count"));
            assertEquals(0, db.num(quota, "reserved_count"));
            assertNull(db.one("SELECT id FROM member_quota WHERE user_id=1001 AND period_month='2026-11'"));
            assertEquals(0L, db.num(db.one("SELECT COUNT(*) n FROM member_quota WHERE reserved_count+used_count>total_count"), "n"));
            assertEquals(0L, db.num(db.one("SELECT COUNT(*) n FROM coupon_template WHERE issued_count>total_count"), "n"));
        }
    }

    private static long template(BenefitService service, String name, int total) {
        long id = Long.parseLong(service.createTemplate(Map.of("name", name, "scope", "ALL_STATIONS",
                "claimFrom", "2026-10-01T00:00:00Z", "claimUntil", "2026-10-31T16:00:00Z",
                "useValidFrom", "2026-10-01T00:00:00Z", "useValidUntil", "2026-10-31T16:00:00Z",
                "thresholdFen", 500, "discountFen", 300, "totalCount", total)).get("templateId").toString());
        service.publishTemplate(id);
        return id;
    }

    private static boolean claim(BenefitService service, long user, long template) {
        try { service.claim(user, template); return true; }
        catch (BusinessException exception) {
            if (!List.of("COUPON_SOLD_OUT", "COUPON_ALREADY_CLAIMED").contains(exception.code())) throw exception;
            return false;
        }
    }

    private static int successes(List<Callable<Boolean>> attempts) throws Exception {
        try (var executor = Executors.newFixedThreadPool(24)) {
            int success = 0;
            for (var result : executor.invokeAll(attempts)) if (result.get()) success++;
            return success;
        }
    }

    static final class MutableClock extends Clock {
        final AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-10-18T07:00:00Z"));
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return time.get(); }
    }

    @TestConfiguration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(System.getenv("CHARGEHUB_TEST_JDBC_URL"),
                    System.getenv().getOrDefault("CHARGEHUB_TEST_DB_USER", "root"), System.getenv("CHARGEHUB_TEST_DB_PASSWORD"));
        }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean Db db(JdbcTemplate jdbc) { return new Db(jdbc); }
        @Bean MutableClock clock() { return new MutableClock(); }
        @Bean BenefitService benefits(Db db, MutableClock clock) { return new BenefitService(db, clock); }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
    }
}

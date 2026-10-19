package com.chargehub.app;

import com.chargehub.identity.*;
import com.chargehub.benefit.*;
import com.chargehub.infrastructure.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Explicitly opted-in, fresh MySQL database. Never truncates or drops an existing database. */
@EnabledIfEnvironmentVariable(named = "CHARGEHUB_IDENTITY_TEST_JDBC_URL", matches = ".+")
class IdentityMysqlTest {
    @Test
    void roleRacesRevocationSharedLimitsAndAuditUseRealInnoDb() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            DataSource source = context.getBean(DataSource.class);
            Db db = context.getBean(Db.class);
            try (var connection = source.getConnection()) {
                assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
                assertTrue(connection.getMetaData().getDatabaseMajorVersion() >= 8);
                assertTrue(connection.getCatalog().endsWith("_test"), "Refuse a non-test database");
                assertEquals(0, Db.num(db.one("SELECT COUNT(*) n FROM information_schema.tables WHERE table_schema=DATABASE()"), "n"),
                        "A new dedicated database is required; existing data is never erased");
                for (String migration : List.of("V1__baseline.sql", "V2__auth_and_nonce.sql", "V3__identity_hardening.sql"))
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + migration));
            }
            var admin = context.getBean(IdentityAdminService.class);
            var tokens = context.getBean(TokenService.class);
            var logout = context.getBean(SessionRevocationService.class);
            var passwords = context.getBean(PasswordEncoder.class);
            long first = user(db, passwords, "first-admin", true, "ADMIN");
            long second = user(db, passwords, "second-admin", true, "ADMIN");
            user(db, passwords, "disabled-admin", false, "ADMIN");
            long customer = user(db, passwords, "customer", true, "CUSTOMER");
            long monitor = user(db, passwords, "__monitor__", true, "OPS");

            CountDownLatch gate = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                List<Future<Boolean>> results = new ArrayList<>();
                for (long actor : new long[]{first, second}) results.add(pool.submit(() -> {
                    gate.await();
                    try {
                        admin.changeRoles(actor, actor, Set.of("CUSTOMER"), "并发撤销自身管理员角色", UUID.randomUUID().toString());
                        return true;
                    } catch (BusinessException exception) {
                        assertEquals("LAST_ADMIN_REQUIRED", exception.code());
                        return false;
                    }
                }));
                gate.countDown();
                assertEquals(1, (results.get(0).get() ? 1 : 0) + (results.get(1).get() ? 1 : 0));
            }
            long remaining = Db.num(db.one("SELECT u.id FROM iam_user u JOIN iam_user_role r ON r.user_id=u.id WHERE u.enabled=1 AND r.role_code='ADMIN'"), "id");
            assertEquals("RESERVED_ACCOUNT", assertThrows(BusinessException.class, () -> admin.changeRoles(remaining, monitor,
                    Set.of("ADMIN"), "禁止借助监控身份绕过管理员保护", UUID.randomUUID().toString())).code());
            assertEquals(1, Db.num(db.one("SELECT COUNT(*) n FROM ops_audit WHERE action='USER_ROLES_CHANGED'"), "n"));
            assertEquals(403, assertThrows(BusinessException.class, () -> admin.changeRoles(customer, customer,
                    Set.of("ADMIN"), "客户不能自行授予管理员角色", UUID.randomUUID().toString())).status());

            String token = token(tokens, "customer");
            String key = UUID.randomUUID().toString();
            var changed = admin.changeRoles(remaining, customer, Set.of("CUSTOMER", "OPS"), "授权测试运营只读权限", key);
            assertEquals(changed, admin.changeRoles(remaining, customer, Set.of("CUSTOMER", "OPS"), "授权测试运营只读权限", key));
            assertTrue(tokens.resolve(token).roles().contains("OPS"));
            assertEquals(409, assertThrows(BusinessException.class, () -> admin.changeRoles(remaining, customer,
                    Set.of("CUSTOMER", "FINANCE"), "授权测试运营只读权限", key)).status());
            admin.changeRoles(remaining, customer, Set.of("CUSTOMER"), "撤销测试运营权限并立即生效", UUID.randomUUID().toString());
            assertFalse(tokens.resolve(token).roles().contains("OPS"));
            admin.revokeTokens(remaining, customer, "管理员强制撤销用户会话", UUID.randomUUID().toString());
            assertNull(tokens.resolve(token));

            String single = token(tokens, "customer"), other = token(tokens, "customer");
            logout.logout(customer, single, false, "用户主动退出当前会话", UUID.randomUUID().toString());
            assertNull(tokens.resolve(single));
            assertNotNull(tokens.resolve(other));
            logout.logout(customer, other, true, "用户主动退出全部会话", UUID.randomUUID().toString());
            assertNull(tokens.resolve(other));
            var page = admin.users(remaining, null, 2);
            assertNotNull(page.get("nextCursor"));
            assertFalse(Json.write(page).contains("password"));
            assertEquals(2, ((List<?>) page.get("items")).size());
            assertEquals(400, assertThrows(BusinessException.class, () -> admin.users(remaining, "0", 2)).status());

            var manager = context.getBean(DataSourceTransactionManager.class);
            Environment settings = new MockEnvironment().withProperty("chargehub.auth.login-account-limit", "4")
                    .withProperty("chargehub.auth.login-address-limit", "50");
            var instanceA = new LoginAttemptLimiter(db, manager, settings);
            var instanceB = new LoginAttemptLimiter(db, manager, settings);
            try (var pool = Executors.newFixedThreadPool(8)) {
                List<Callable<Boolean>> attempts = new ArrayList<>();
                for (int index = 0; index < 24; index++) {
                    int i = index;
                    attempts.add(() -> {
                        try {
                            (i % 2 == 0 ? instanceA : instanceB).check(i % 2 == 0 ? "Shared-Account" : "shared-account", "address-" + i);
                            return true;
                        } catch (BusinessException exception) {
                            assertEquals(429, exception.status());
                            return false;
                        }
                    });
                }
                int accepted = 0;
                for (Future<Boolean> result : pool.invokeAll(attempts)) if (result.get()) accepted++;
                assertEquals(4, accepted, "Both application instances share one persisted account budget");
            }
            String bucket = Json.hash("account:shared-account");
            assertEquals(24, Db.num(db.one("SELECT attempt_count FROM auth_login_bucket WHERE bucket_key=?", bucket), "attempt_count"));
            db.update("UPDATE auth_login_bucket SET window_started_at=TIMESTAMPADD(SECOND,-61,UTC_TIMESTAMP(3)) WHERE bucket_key=?", bucket);
            instanceB.check("shared-account", "new-address");
            assertEquals(1, Db.num(db.one("SELECT attempt_count FROM auth_login_bucket WHERE bucket_key=?", bucket), "attempt_count"));
            var addressSettings = new MockEnvironment().withProperty("chargehub.auth.login-account-limit", "50")
                    .withProperty("chargehub.auth.login-address-limit", "3");
            var addressLimiter = new LoginAttemptLimiter(db, manager, addressSettings);
            for (int i = 0; i < 8; i++) {
                String login = "ip-account-" + i;
                if (i < 3) addressLimiter.check(login, "one-shared-address");
                else assertEquals(429, assertThrows(BusinessException.class,
                        () -> addressLimiter.check(login, "one-shared-address")).status());
            }
            assertNull(db.one("SELECT bucket_key FROM auth_login_bucket WHERE bucket_key=?", Json.hash("account:ip-account-7")),
                    "Blocked addresses must not create rows for arbitrary usernames");
            assertEquals(1, Db.num(db.one("SELECT COUNT(*) n FROM iam_user u JOIN iam_user_role r ON r.user_id=u.id WHERE u.enabled=1 AND r.role_code='ADMIN'"), "n"));

            // Opposite actor/target IDs across IAM and real membership administration must share one lock order.
            long operator = user(db, passwords, "cross-domain-operator", true, "OPS");
            long administrator = user(db, passwords, "cross-domain-admin", true, "ADMIN");
            assertTrue(operator < administrator);
            var membershipWrites = context.getBean(BenefitWriteCoordinator.class);
            var membership = context.getBean(BenefitOperationsService.class);
            var input = new BenefitCommands.MembershipInput("DEMO_MONTHLY", Instant.now().minusSeconds(60),
                    Instant.now().plusSeconds(86400), "交叉身份与会员操作锁序回归");
            try (var pool = Executors.newFixedThreadPool(2)) {
                for (int index = 0; index < 20; index++) {
                    CountDownLatch start = new CountDownLatch(1);
                    boolean roles = index % 2 == 0;
                    Future<?> identity = pool.submit(() -> {
                        start.await();
                        if (roles) admin.changeRoles(administrator, operator, Set.of("OPS"), "交叉身份授权操作锁序回归", UUID.randomUUID().toString());
                        else admin.revokeTokens(administrator, operator, "交叉身份撤销操作锁序回归", UUID.randomUUID().toString());
                        return null;
                    });
                    Future<?> benefit = pool.submit(() -> {
                        start.await();
                        return membershipWrites.membership(operator, administrator, "test.cross.membership/" + administrator,
                                UUID.randomUUID().toString(), Map.of("reason", input.reason()),
                                () -> membership.grantMembership(operator, administrator, input));
                    });
                    start.countDown();
                    assertNotNull(benefit.get(10, TimeUnit.SECONDS));
                    identity.get(10, TimeUnit.SECONDS);
                }
            }
            assertEquals(20, Db.num(db.one("SELECT COUNT(*) n FROM ops_audit WHERE action='MEMBERSHIP_GRANT' AND target_no=?", Long.toString(administrator)), "n"));
            assertEquals(1, Db.num(db.one("SELECT COUNT(*) n FROM membership WHERE user_id=?", administrator), "n"));
        }
    }

    private static String token(TokenService service, String username) {
        return service.login(username, "isolated-test-password", "isolated-test-client").get("accessToken").toString();
    }

    private static long user(Db db, PasswordEncoder encoder, String username, boolean enabled, String role) {
        long id = db.insert("INSERT INTO iam_user(login_name,password_hash,enabled) VALUES(?,?,?)", username, encoder.encode("isolated-test-password"), enabled ? 1 : 0);
        db.update("INSERT INTO iam_user_role(user_id,role_code) VALUES(?,?)", id, role);
        return id;
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource source() {
            var source = new DriverManagerDataSource(System.getenv("CHARGEHUB_IDENTITY_TEST_JDBC_URL"),
                    System.getenv().getOrDefault("CHARGEHUB_IDENTITY_TEST_DB_USER", "root"), System.getenv("CHARGEHUB_IDENTITY_TEST_DB_PASSWORD"));
            var adapter = new IsolationLevelDataSourceAdapter();
            adapter.setTargetDataSource(source);
            adapter.setIsolationLevel(java.sql.Connection.TRANSACTION_READ_COMMITTED);
            return adapter;
        }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean Db db(JdbcTemplate jdbc) { return new Db(jdbc); }
        @Bean DataSourceTransactionManager manager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean Idempotency idempotency(Db db, DataSourceTransactionManager manager) { return new Idempotency(db, manager); }
        @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(4); }
        @Bean LoginAttemptLimiter limiter(Db db, DataSourceTransactionManager manager) { return new LoginAttemptLimiter(db, manager, new MockEnvironment()); }
        @Bean TokenService tokens(Db db, PasswordEncoder encoder, LoginAttemptLimiter limiter) { return new TokenService(db, encoder, limiter); }
        @Bean IdentityAdminService admin(Db db, Idempotency idempotency) { return new IdentityAdminService(db, idempotency); }
        @Bean SessionRevocationService logout(Db db, Idempotency idempotency) { return new SessionRevocationService(db, idempotency); }
        @Bean jakarta.validation.Validator validator() { return jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator(); }
        @Bean BenefitWriteCoordinator membershipWrites(Db db, Idempotency idempotency) { return new BenefitWriteCoordinator(db, idempotency); }
        @Bean BenefitOperationsService memberships(Db db, jakarta.validation.Validator validator) { return new BenefitOperationsService(db, validator); }
    }
}

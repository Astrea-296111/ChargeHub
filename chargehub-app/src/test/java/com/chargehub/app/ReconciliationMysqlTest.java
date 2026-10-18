package com.chargehub.app;

import com.chargehub.infrastructure.*;
import com.chargehub.reconcile.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in test against a fresh dedicated MySQL database; immutable facts below are controlled fixtures. */
@EnabledIfEnvironmentVariable(named = "CHARGEHUB_RECONCILE_TEST_JDBC_URL", matches = ".+")
class ReconciliationMysqlTest {
    @Test void bidirectionalComparisonDedupAndConcurrentImportsKeepMoneyImmutable() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            DataSource source = context.getBean(DataSource.class);
            Db db = context.getBean(Db.class);
            ReconciliationService service = context.getBean(ReconciliationService.class);
            try (var connection = source.getConnection()) {
                assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
                assertTrue(connection.getMetaData().getDatabaseMajorVersion() >= 8);
                assertTrue(connection.getCatalog().endsWith("_test"), "Refuse to initialize a non-test database");
                assertEquals(0, db.num(db.one("SELECT COUNT(*) n FROM information_schema.tables WHERE table_schema=DATABASE()"), "n"), "Use a fresh database; test does not erase existing data");
                Path schema = Path.of("../docs/spec/03-schema.sql");
                if (!schema.toFile().exists()) schema = Path.of("docs/spec/03-schema.sql");
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(schema));
            }
            String pay1 = UUID.randomUUID().toString(), pay2 = UUID.randomUUID().toString(), pay3 = UUID.randomUUID().toString();
            String order1 = UUID.randomUUID().toString(), order2 = UUID.randomUUID().toString(), order3 = UUID.randomUUID().toString();
            payment(db, pay1, order1); payment(db, pay2, order2); payment(db, pay3, order3);
            cash(db, pay1, order1, "provider-1"); cash(db, pay2, order2, "provider-2");
            String partial = ChannelCsv.HEADER + "\n" + line(pay1, "provider-1", 10000) + line(pay3, "provider-3", 10000);
            var imported = service.importCsv(77, "MOCK", "2026-10-18", "PAYMENT", "Asia/Shanghai", 1, partial, "真实数据库双向核对测试");
            long batchId = Long.parseLong(imported.get("batchId").toString());
            assertEquals("INCOMPLETE", imported.get("status"));
            assertEquals(2, db.num(db.one("SELECT COUNT(*) n FROM reconcile_item WHERE batch_id=?", batchId), "n"));
            assertEquals(1, db.num(db.one("SELECT COUNT(*) n FROM reconcile_item WHERE batch_id=? AND diff_type='LOCAL_ONLY' AND status='PENDING_CONFIRMATION'", batchId), "n"));
            assertEquals(1, db.num(db.one("SELECT COUNT(*) n FROM reconcile_item WHERE batch_id=? AND diff_type='CHANNEL_ONLY' AND status='OPEN'", batchId), "n"));
            assertEquals(Set.of(order2, order3), new HashSet<>(service.diffOrderNos(batchId)));
            assertEquals(2, db.num(db.one("SELECT COUNT(*) n FROM cash_entry"), "n"));
            assertEquals(20000, db.num(db.one("SELECT SUM(captured_fen) n FROM payment_order"), "n"));

            assertEquals(imported, service.importCsv(77, "MOCK", "2026-10-18", "PAYMENT", "Asia/Shanghai", 1, partial, "相同文件重放"));
            var conflict = assertThrows(BusinessException.class, () -> service.importCsv(77, "MOCK", "2026-10-18", "PAYMENT", "Asia/Shanghai", 1,
                    partial.replaceFirst(",10000,", ",9999,"), "同批次不同金额"));
            assertEquals(409, conflict.status());
            assertEquals(1, db.num(db.one("SELECT COUNT(*) n FROM ops_audit"), "n"));

            // Controlled fixture represents a fact already verified and inserted by the payment application.
            cash(db, pay3, order3, "provider-3");
            assertEquals(1, service.refreshRecovered(77, batchId, "查单补录后的逐项核验"));
            assertEquals(0, service.refreshRecovered(77, batchId, "重复核验"));
            assertEquals("INCOMPLETE", db.str(db.one("SELECT status FROM reconcile_batch WHERE id=?", batchId), "status"));
            assertEquals(List.of(order2), service.diffOrderNos(batchId));

            String complete = partial + line(pay2, "provider-2", 10000);
            var matched = service.importCsv(77, "MOCK", "2026-10-18", "PAYMENT", "Asia/Shanghai", 2, complete, "完整文件重新导出");
            assertEquals("MATCHED", matched.get("status"));
            var wrongAmount = service.importCsv(77, "MOCK", "2026-10-18", "PAYMENT", "Asia/Shanghai", 3,
                    complete.replaceFirst(",10000,", ",9999,"), "保留原始金额差异");
            assertEquals("DIFF", wrongAmount.get("status"));

            List<Callable<String>> imports = new ArrayList<>();
            for (int i = 0; i < 12; i++) imports.add(() -> service.importCsv(77, "MOCK", "2026-10-18", "PAYMENT", "Asia/Shanghai", 4,
                    complete, "并发同批次去重").get("batchId").toString());
            Set<String> importedIds = new HashSet<>();
            try (var pool = Executors.newFixedThreadPool(6)) {
                for (var future : pool.invokeAll(imports)) importedIds.add(future.get());
            }
            assertEquals(1, importedIds.size());
            assertEquals(4, db.num(db.one("SELECT COUNT(*) n FROM reconcile_batch"), "n"));
            assertEquals(5, db.num(db.one("SELECT COUNT(*) n FROM ops_audit"), "n"));
            assertEquals(3, db.num(db.one("SELECT COUNT(*) n FROM cash_entry"), "n"));
            assertEquals(30000, db.num(db.one("SELECT SUM(amount_fen) n FROM cash_entry"), "n"));
            assertEquals(30000, db.num(db.one("SELECT SUM(captured_fen) n FROM payment_order"), "n"));
        }
    }

    private static String line(String pay, String provider, int amount) {
        return "MOCK,PAYMENT,2026-10-18,Asia/Shanghai," + provider + "," + pay + ",," + amount + ",CNY,2026-10-18T07:00:00Z,SUCCESS\n";
    }
    private static void payment(Db db, String pay, String order) {
        db.insert("INSERT INTO payment_order(pay_no,order_no,channel,amount_fen,currency) VALUES (?,?,'MOCK',10000,'CNY')", pay, order);
    }
    private static void cash(Db db, String pay, String order, String provider) {
        db.insert("INSERT INTO cash_entry(order_no,biz_type,biz_no,channel,provider_biz_no,amount_fen,currency,occurred_at) VALUES (?,'CAPTURE',?,'MOCK',?,10000,'CNY',?)",
                order, pay, provider, Instant.parse("2026-10-18T07:00:00Z"));
        db.update("UPDATE payment_order SET captured_fen=10000,status='SUCCESS',provider_trade_no=?,captured_at=? WHERE pay_no=?", provider,
                Instant.parse("2026-10-18T07:00:00Z"), pay);
    }

    @TestConfiguration
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(System.getenv("CHARGEHUB_RECONCILE_TEST_JDBC_URL"),
                    System.getenv().getOrDefault("CHARGEHUB_RECONCILE_TEST_DB_USER", "root"), System.getenv("CHARGEHUB_RECONCILE_TEST_DB_PASSWORD"));
        }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean Db db(JdbcTemplate jdbc) { return new Db(jdbc); }
        @Bean ReconciliationService service(Db db) { return new ReconciliationService(db); }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
    }
}

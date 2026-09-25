package com.example.starter.water;

import com.example.starter.water.WaterRepository.EmergencyWriteoffRow;
import com.example.starter.water.WaterRepository.WindowRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 应急储备相关持久化约束验证：同一 JVM 内命名 H2 内存库（MODE=MySQL），
 * 验证储备/核销表的唯一约束、CHECK 约束、常规核销账目变动与事务回滚边界，
 * 不使用 mock 或内存 Map 代替数据库边界。
 */
class ReservePersistenceTests {

    private static final String URL_TEMPLATE =
            "jdbc:h2:mem:reserve-%s;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private Fixture newFixture() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(8));
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo = new WaterRepository(new JdbcTemplate(dataSource));
        PlatformTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        return new Fixture(dataSource, repo, new TransactionTemplate(txManager));
    }

    private record Fixture(SimpleDriverDataSource dataSource, WaterRepository repo, TransactionTemplate tx) {
    }

    @Test
    void emergencyWriteoffUniquePerWindowAndEmergencyId() throws Exception {
        Fixture f = newFixture();
        long w1 = f.repo().insertWindow("wk-ew1", "ch", 1L, 2L, new BigDecimal("10"), 1L);
        long w2 = f.repo().insertWindow("wk-ew2", "ch2", 3L, 4L, new BigDecimal("10"), 2L);
        f.repo().insertEmergencyWriteoff("ew-1", w1, "EM-1", "chief", "alice",
                new BigDecimal("3"), new BigDecimal("5"), 10L);
        // 同窗口同应急编号 -> 唯一约束冲突
        assertThrows(DuplicateKeyException.class, () -> f.repo().insertEmergencyWriteoff(
                "ew-2", w1, "EM-1", "chief", "alice", new BigDecimal("1"), new BigDecimal("5"), 11L));
        // 不同窗口同应急编号 -> 合法
        f.repo().insertEmergencyWriteoff("ew-3", w2, "EM-1", "chief", "bob",
                new BigDecimal("2"), new BigDecimal("5"), 12L);
        // 业务键重复 -> 唯一约束冲突
        assertThrows(DuplicateKeyException.class, () -> f.repo().insertEmergencyWriteoff(
                "ew-1", w2, "EM-9", "chief", "bob", new BigDecimal("1"), new BigDecimal("5"), 13L));

        // 全新连接读取：储备快照保留
        WaterRepository reader = new WaterRepository(new JdbcTemplate(
                new SimpleDriverDataSource(new org.h2.Driver(), f.dataSource().getUrl(), "sa", "")));
        EmergencyWriteoffRow row = reader.lockEmergencyWriteoff(w1, "EM-1");
        assertNotNull(row);
        assertEquals(0, row.amount().compareTo(new BigDecimal("3")));
        assertEquals(0, row.reserveSnapshot().compareTo(new BigDecimal("5")));
        assertEquals("chief", row.approver());
        // 每个窗口仅一条流水（同窗口同应急编号被唯一约束拒绝）
        assertEquals(1, reader.listEmergencyWriteoffs(w1).size());
        assertEquals(1, reader.listEmergencyWriteoffs(w2).size());
    }

    @Test
    void reserveCheckConstraintRejectsReserveAbovePlannedVolume() throws Exception {
        Fixture f = newFixture();
        long windowId = f.repo().insertWindow("wk-chk", "ch", 1L, 2L, new BigDecimal("10"), 1L);
        // 储备超过计划水量 -> CHECK 约束拒绝
        Exception ex = assertThrows(Exception.class, () -> f.repo().updateReserveVolume(
                windowId, new BigDecimal("10.001")));
        assertTrue(ex.getMessage() == null || !ex.getMessage().isBlank() || ex.getCause() != null);
        // 合法储备写入并可经新连接读取
        f.repo().updateReserveVolume(windowId, new BigDecimal("4.500"));
        f.repo().bumpWindowVersion(windowId);
        WaterRepository reader = new WaterRepository(new JdbcTemplate(
                new SimpleDriverDataSource(new org.h2.Driver(), f.dataSource().getUrl(), "sa", "")));
        WindowRow reread = reader.findWindowById(windowId);
        assertEquals(new BigDecimal("4.500"), reread.reserveVolume());
        assertEquals(1L, reread.version());
        assertEquals("OPEN", reread.status());
        assertNull(reread.closedNanos());
        // 关闭后状态与关闭时刻持久化
        f.repo().closeWindow(windowId, 99L);
        WindowRow closed = reader.findWindowById(windowId);
        assertEquals("CLOSED", closed.status());
        assertEquals(99L, closed.closedNanos());
        // 关闭后储备快照仍为 4.5
        assertEquals(new BigDecimal("4.500"), closed.reserveVolume());
    }

    @Test
    void regularWriteoffKeepsBalancedLedgerAcrossConnections() throws Exception {
        Fixture f = newFixture();
        long windowId = f.repo().insertWindow("wk-ledger", "ch", 1L, 2L, new BigDecimal("10"), 1L);
        f.repo().insertAllocation("ak-ledger", windowId, "user-1", new BigDecimal("6"), "alice", 2L);
        var allocation = f.repo().findAllocationByKey("ak-ledger");
        f.repo().updateAllocationStatus(allocation.id(), "APPROVED", 3L);
        f.repo().insertRegularWriteoff("rw-1", windowId, "ak-ledger", new BigDecimal("2.5"), "alice", 4L);
        f.repo().applyRegularWriteoff(allocation.id(), new BigDecimal("2.5"), 4L);

        WaterRepository reader = new WaterRepository(new JdbcTemplate(
                new SimpleDriverDataSource(new org.h2.Driver(), f.dataSource().getUrl(), "sa", "")));
        var after = reader.findAllocationByKey("ak-ledger");
        assertEquals(new BigDecimal("3.500"), after.heldAmount());
        assertEquals(new BigDecimal("2.500"), after.regularWrittenOff());
        assertEquals(new BigDecimal("6.000"), after.amount());
        assertEquals(new BigDecimal("2.500"), reader.sumRegularWrittenOff(windowId));
        // APPROVED 持有汇总 = 3.5
        assertEquals(new BigDecimal("3.500"), reader.sumApprovedAmount(windowId));
        // 常规核销流水业务键唯一
        assertThrows(DuplicateKeyException.class, () -> reader.insertRegularWriteoff(
                "rw-1", windowId, "ak-ledger", new BigDecimal("1"), "alice", 5L));
    }

    @Test
    void failedEmergencyBatchRollsBackAllRowsInSingleTransaction() throws Exception {
        Fixture f = newFixture();
        long windowId = f.repo().insertWindow("wk-rollback", "ch", 1L, 2L, new BigDecimal("10"), 1L);
        RuntimeException failure = new RuntimeException("force rollback");
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> f.tx().execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                f.repo().insertEmergencyWriteoff("ew-a", windowId, "EM-A", "chief", "alice",
                        new BigDecimal("1"), new BigDecimal("5"), 10L);
                f.repo().insertEmergencyWriteoff("ew-b", windowId, "EM-B", "chief", "alice",
                        new BigDecimal("1"), new BigDecimal("5"), 11L);
                throw failure;
            }
        }));
        assertEquals(failure, thrown);
        // 整单回滚：两条应急核销均不存在，储备累计为 0
        assertEquals(0, f.repo().listEmergencyWriteoffs(windowId).size());
        assertEquals(0, f.repo().sumEmergencyWrittenOff(windowId).compareTo(BigDecimal.ZERO));
        assertNull(f.repo().lockEmergencyWriteoff(windowId, "EM-A"));

        // 同事务成功提交后，新连接可见全部行
        f.tx().execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                f.repo().insertEmergencyWriteoff("ew-c", windowId, "EM-C", "chief", "alice",
                        new BigDecimal("2"), new BigDecimal("5"), 20L);
                f.repo().insertEmergencyWriteoff("ew-d", windowId, "EM-D", "chief", "alice",
                        new BigDecimal("1"), new BigDecimal("5"), 21L);
            }
        });
        WaterRepository reader = new WaterRepository(new JdbcTemplate(
                new SimpleDriverDataSource(new org.h2.Driver(), f.dataSource().getUrl(), "sa", "")));
        assertEquals(2, reader.listEmergencyWriteoffs(windowId).size());
        assertEquals(0, reader.sumEmergencyWrittenOff(windowId).compareTo(new BigDecimal("3")));
    }

    @Test
    void reserveCommandFingerprintRowIsAbsentAfterRollbackAndPresentAfterCommit() throws Exception {
        Fixture f = newFixture();
        RuntimeException failure = new RuntimeException("force rollback reserve key");
        assertThrows(RuntimeException.class, () -> f.tx().execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                f.repo().insertReserveCommand("rk-1", "EMERGENCY_WRITEOFF", "fp-1", 1L);
                throw failure;
            }
        }));
        // 失败不占键
        assertNull(f.repo().findReserveCommand("rk-1"));

        f.tx().execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                f.repo().insertReserveCommand("rk-2", "RESERVE_ADJUST", "fp-2", 2L);
                f.repo().updateReserveCommandResponse("rk-2", "{\"reserveVolume\":\"3\"}");
            }
        });
        var committed = f.repo().findReserveCommand("rk-2");
        assertNotNull(committed);
        assertEquals("fp-2", committed.fingerprint());
        assertEquals("{\"reserveVolume\":\"3\"}", committed.response());
        // reserveKey 主键唯一
        assertThrows(DuplicateKeyException.class, () -> f.repo().insertReserveCommand(
                "rk-2", "WINDOW_CLOSE", "fp-3", 3L));
    }
}

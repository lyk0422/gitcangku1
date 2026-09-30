package com.example.starter.water;

import com.example.starter.water.EmergencyRepository.ActivationRow;
import com.example.starter.water.EmergencyRepository.EventRow;
import com.example.starter.water.EmergencyRepository.FlowRow;
import com.example.starter.water.EmergencyRepository.ReserveRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 紧急储备持久化验证：同一 JVM 内使用独立命名 H2 内存库（MODE=MySQL），
 * 经全新连接与仓储实例重读验证播种总账、审批/流水留存与精确额度；
 * 并实际验证唯一约束、CHECK 约束与事务回滚。本题不要求跨进程重启恢复。
 */
class EmergencyPersistenceTests {

    private static final String URL_TEMPLATE =
            "jdbc:h2:mem:emergency-%s;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";

    private DataSource newDataSource(String url) {
        return new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
    }

    private void initSchema(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("data.sql"));
        }
    }

    @Test
    void seededLedgerAndActivationReadableFromNewConnection() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        DataSource ds = newDataSource(url);
        initSchema(ds);

        EmergencyRepository repo1 = new EmergencyRepository(new JdbcTemplate(ds));
        WaterRepository water1 = new WaterRepository(new JdbcTemplate(ds));
        // 播种：储备总账 10，已预占 0
        ReserveRow seeded = repo1.lockReserve();
        assertEquals(new BigDecimal("10.000"), seeded.totalVolume());
        assertEquals(0, seeded.reservedVolume().compareTo(BigDecimal.ZERO));

        long windowId = water1.insertWindow("wk-em", "ch-em", 1L, 2L, new BigDecimal("100"), 1L);
        repo1.insertEvent("ev-em", "干渠险情", "紧急输水", 2L);
        repo1.insertActivation("req-em", "ev-em", windowId, new BigDecimal("6.000"), "jia", "yi", 3L);
        repo1.increaseReserved(new BigDecimal("6.000"), 3L);
        repo1.addWrittenOff(repo1.findActivation("req-em").id(), new BigDecimal("2.000"));
        repo1.insertFlow("req-em", "ev-em", windowId, "WRITE_OFF", new BigDecimal("2.000"), "jia", 4L);

        // 全新连接与仓储实例重读
        EmergencyRepository repo2 = new EmergencyRepository(new JdbcTemplate(newDataSource(url)));
        ReserveRow reserve2 = repo2.findReserve();
        assertEquals(new BigDecimal("10.000"), reserve2.totalVolume());
        assertEquals(new BigDecimal("6.000"), reserve2.reservedVolume());

        EventRow event2 = repo2.findEvent("ev-em");
        assertNotNull(event2);
        assertEquals("ACTIVE", event2.status());
        assertEquals("干渠险情", event2.title());

        ActivationRow activation2 = repo2.findActivation("req-em");
        assertEquals("ev-em", activation2.eventKey());
        assertEquals(windowId, activation2.windowId());
        assertEquals(new BigDecimal("6.000"), activation2.volume());
        assertEquals(new BigDecimal("2.000"), activation2.writtenOffVolume());
        assertEquals("jia", activation2.confirmerA());
        assertEquals("yi", activation2.confirmerB());
        // 尚未核销且未归还 = 6 - 2 - 0 = 4
        assertEquals(new BigDecimal("4.000"), activation2.outstanding());

        FlowRow flow2 = repo2.listFlowsByWindow(windowId).get(0);
        assertEquals("WRITE_OFF", flow2.flowType());
        assertEquals(new BigDecimal("2.000"), flow2.volume());
        assertEquals("jia", flow2.actor());

        // 窗口视图聚合：占用 4、核销 2、归还 0
        ActivationRow only = repo2.listActivationsByWindow(windowId).get(0);
        assertEquals(new BigDecimal("4.000"), only.outstanding());
    }

    @Test
    void uniqueConstraintsRejectDuplicateEventAndRequest() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        DataSource ds = newDataSource(url);
        initSchema(ds);
        EmergencyRepository repo = new EmergencyRepository(new JdbcTemplate(ds));
        WaterRepository water = new WaterRepository(new JdbcTemplate(ds));
        long windowId = water.insertWindow("wk-uq", "ch-uq", 1L, 2L, new BigDecimal("100"), 1L);
        repo.insertEvent("ev-uq", "t", "", 1L);
        repo.insertActivation("req-uq", "ev-uq", windowId, new BigDecimal("1"), "a", "b", 2L);

        assertThrows(DataIntegrityViolationException.class,
                () -> repo.insertEvent("ev-uq", "t2", "", 3L));
        assertThrows(DataIntegrityViolationException.class,
                () -> repo.insertActivation("req-uq", "ev-uq", windowId, new BigDecimal("2"), "a", "b", 4L));
        // 外键：不存在的窗口不能挂启用审批
        assertThrows(DataIntegrityViolationException.class,
                () -> repo.insertActivation("req-fk", "ev-uq", 999999999L, new BigDecimal("1"), "a", "b", 5L));
    }

    @Test
    void checkConstraintsRejectOversellAndOverReturn() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        DataSource ds = newDataSource(url);
        initSchema(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 储备总账预占不得超过总量（10）
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE reserve_ledger SET reserved_volume = 10.001 WHERE id = 1"));
        // 储备总账预占不得为负
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE reserve_ledger SET reserved_volume = -0.001 WHERE id = 1"));

        EmergencyRepository repo = new EmergencyRepository(jdbc);
        WaterRepository water = new WaterRepository(jdbc);
        long windowId = water.insertWindow("wk-chk", "ch-chk", 1L, 2L, new BigDecimal("100"), 1L);
        repo.insertEvent("ev-chk", "t", "", 1L);
        repo.insertActivation("req-chk", "ev-chk", windowId, new BigDecimal("6"), "a", "b", 2L);
        long activationId = repo.findActivation("req-chk").id();

        // 核销 + 归还超过启用量 -> CHECK 拒绝
        repo.addWrittenOff(activationId, new BigDecimal("4"));
        assertThrows(DataIntegrityViolationException.class,
                () -> repo.addReturned(activationId, new BigDecimal("3")));
        // 核销计数不得为负（直接 SQL）
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE reserve_activation SET written_off_volume = -0.001 WHERE id = ?",
                        activationId));
        // 启用水量必须大于 0
        assertThrows(DataIntegrityViolationException.class,
                () -> repo.insertActivation("req-zero", "ev-chk", windowId, new BigDecimal("0"), "a", "b", 3L));
        // 流水水量必须大于 0
        assertThrows(DataIntegrityViolationException.class,
                () -> repo.insertFlow("req-chk", "ev-chk", windowId, "RETURN",
                        new BigDecimal("0"), "a", 4L));
    }

    @Test
    void failedActivationRollsBackReserveAndActivation() {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        DataSource ds = newDataSource(url);
        try {
            initSchema(ds);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        EmergencyRepository repo = new EmergencyRepository(jdbc);
        WaterRepository water = new WaterRepository(jdbc);
        long windowId = water.insertWindow("wk-rb", "ch-rb", 1L, 2L, new BigDecimal("100"), 1L);
        repo.insertEvent("ev-rb", "t", "", 1L);
        PlatformTransactionManager tm = new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);

        // 事务内插入启用并把储备预占推到超过总量，CHECK 失败导致整笔回滚
        assertThrows(DataIntegrityViolationException.class, () -> new TransactionTemplate(tm).execute(
                new TransactionCallbackWithoutResult() {
                    @Override
                    protected void doInTransactionWithoutResult(TransactionStatus status) {
                        repo.insertActivation("req-rb", "ev-rb", windowId, new BigDecimal("6"), "a", "b", 2L);
                        jdbc.update("UPDATE reserve_ledger SET reserved_volume = 11.000 WHERE id = 1");
                    }
                }));

        // 回滚后：审批不存在，储备总账仍是播种的 0 预占
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM reserve_activation WHERE request_id = 'req-rb'",
                Integer.class) == 0);
        ReserveRow reserve = repo.findReserve();
        assertEquals(0, reserve.reservedVolume().compareTo(BigDecimal.ZERO));
        assertEquals(new BigDecimal("10.000"), reserve.totalVolume());
    }
}

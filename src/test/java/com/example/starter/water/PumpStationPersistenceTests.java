package com.example.starter.water;

import com.example.starter.water.PumpStationRepository.RequestRow;
import com.example.starter.water.PumpStationRepository.StationRow;
import com.example.starter.water.WaterRepository.AllocationRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 泵站与流量曲线的真实 H2（MODE=MySQL）持久化与事务验证：
 * 命名内存库建表写入后以全新连接重读；回滚不留幂等键；唯一约束真实生效。
 * 内存数据随 JVM 退出丢失，本题不要求跨进程恢复。
 */
class PumpStationPersistenceTests {

    private static final String URL_TEMPLATE =
            "jdbc:h2:mem:pump-%s;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private SimpleDriverDataSource newSchema(String url) throws Exception {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        return dataSource;
    }

    @Test
    void stationCurveAndFreezeAreReadableFromNewConnection() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        SimpleDriverDataSource first = newSchema(url);
        PumpStationRepository pump1 = new PumpStationRepository(new JdbcTemplate(first));
        WaterRepository water1 = new WaterRepository(new JdbcTemplate(first));

        pump1.insertStation("st-persist", new BigDecimal("10.000"), new BigDecimal("10.000"), 1L);
        long windowId = water1.insertWindow("wk-pump", "ch-pump", 0L, 120_000_000_000L,
                new BigDecimal("100.000"), 2L, "st-persist");
        long allocationId = water1.insertAllocation("ak-pump", windowId, "user-1",
                new BigDecimal("10.000"), "alice", 3L);
        // 0、10、0 三个相距一分钟节点
        pump1.insertCurveNode(allocationId, 0, 0L, new BigDecimal("0.000"));
        pump1.insertCurveNode(allocationId, 1, 1L, new BigDecimal("10.000"));
        pump1.insertCurveNode(allocationId, 2, 2L, new BigDecimal("0.000"));
        water1.updateAllocationStatus(allocationId, "APPROVED", 4L);
        water1.freezeStationParams(allocationId, 1L, new BigDecimal("10.000"),
                new BigDecimal("10.000"));
        pump1.insertRequest("req-persist", "CURVE_ALLOCATION_SUBMIT", "alice", "ak-pump",
                "{\"amount\":\"10\"}", 5L);
        pump1.updateRequestResponse("req-persist", "{\"status\":\"REQUESTED\"}");

        // 全新连接与仓储实例重读
        SimpleDriverDataSource second = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        PumpStationRepository pump2 = new PumpStationRepository(new JdbcTemplate(second));
        WaterRepository water2 = new WaterRepository(new JdbcTemplate(second));

        StationRow station = pump2.findByKey("st-persist");
        assertNotNull(station);
        assertEquals(1L, station.version());
        assertEquals(new BigDecimal("10.000"), station.maxFlow());

        assertEquals("st-persist", water2.findWindowById(windowId).stationKey());
        AllocationRow allocation = water2.findAllocationByKey("ak-pump");
        assertEquals(1L, allocation.stationVersion());
        assertEquals(new BigDecimal("10.000"), allocation.frozenMaxFlow());

        // 梯形积分 10 等于申请水量
        List<FlowCurve.Node> curve = pump2.listCurveNodes(allocationId).stream()
                .map(n -> new FlowCurve.Node(n.minuteUtc(), n.flow())).toList();
        assertEquals(3, curve.size());
        assertEquals(0, FlowCurve.integrate(curve).compareTo(new BigDecimal("10.000")));
        assertTrue(pump2.hasCurve(allocationId));

        // 已批准曲线按泵站汇总可用于叠加判定
        List<List<FlowCurve.Node>> approved = pump2.listApprovedCurves("st-persist", 0L);
        assertEquals(1, approved.size());
        FlowCurve.assertWithinLimits(approved, new BigDecimal("10"), new BigDecimal("10"));
        // 取消后不再计入叠加
        water2.updateAllocationStatus(allocationId, "CANCELLED", 6L);
        assertEquals(0, pump2.listApprovedCurves("st-persist", 0L).size());

        RequestRow request = pump2.findRequest("req-persist");
        assertNotNull(request);
        assertEquals("{\"status\":\"REQUESTED\"}", request.response());
        assertEquals("alice", request.actor());
    }

    @Test
    void rollbackLeavesNoRequestRowAndUniqueKeyEnforced() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        SimpleDriverDataSource dataSource = newSchema(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        PumpStationRepository pump = new PumpStationRepository(jdbc);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        // 业务失败回滚：幂等键不被占用，可同名重试成功
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            pump.insertRequest("req-tx", "STATION_CONFIG", "alice", "st-tx", "{}", 1L);
            throw new IllegalStateException("boom");
        }));
        assertNull(pump.findRequest("req-tx"));
        tx.executeWithoutResult(status ->
                pump.insertRequest("req-tx", "STATION_CONFIG", "alice", "st-tx", "{}", 2L));
        assertNotNull(pump.findRequest("req-tx"));

        // 唯一约束真实生效：同泵站键、同请求键均抛 DuplicateKeyException
        pump.insertStation("st-dup", new BigDecimal("1"), new BigDecimal("1"), 3L);
        assertThrows(DuplicateKeyException.class,
                () -> pump.insertStation("st-dup", new BigDecimal("2"), new BigDecimal("2"), 4L));
        assertThrows(DuplicateKeyException.class,
                () -> pump.insertRequest("req-tx", "STATION_CONFIG", "alice", "st-tx", "{}", 5L));
    }
}
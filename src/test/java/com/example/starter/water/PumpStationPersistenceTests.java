package com.example.starter.water;

import com.example.starter.water.WaterRepository.CurveNodeRow;
import com.example.starter.water.WaterRepository.CurveRow;
import com.example.starter.water.WaterRepository.PumpStationRow;
import com.example.starter.water.WaterRepository.RequestRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 泵站与流量曲线持久化验证：同一 JVM 内命名 H2 内存库（MODE=MySQL），
 * 建表写入后以全新连接与仓储实例重新读取，并验证唯一约束。本题不要求跨进程重启恢复。
 */
class PumpStationPersistenceTests {

    private static final String URL_TEMPLATE =
            "jdbc:h2:mem:pump-persist-%s;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    void stationCurveAndRequestLogSurviveNewConnection() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        SimpleDriverDataSource first = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = first.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo1 = new WaterRepository(new JdbcTemplate(first));
        long stationId = repo1.insertPumpStation("st-persist", new BigDecimal("10.000"),
                new BigDecimal("0.500"), 1L);
        long windowId = repo1.insertWindow("wk-pump", "ch-pump", 0L, 120_000_000_000L,
                new BigDecimal("1000.000"), 2L);
        long allocationId = repo1.insertAllocation("ak-pump", windowId, "user-1",
                new BigDecimal("10.000"), "alice", 3L);
        long curveId = repo1.insertCurve(allocationId, stationId, 3, 4L);
        repo1.insertCurveNode(curveId, 0, 0L, new BigDecimal("0.000"));
        repo1.insertCurveNode(curveId, 1, 60_000_000_000L, new BigDecimal("10.000"));
        repo1.insertCurveNode(curveId, 2, 120_000_000_000L, new BigDecimal("0.000"));
        repo1.insertRequest("rq-persist", "{\"operation\":\"PUMP_STATION_UPSERT\"}", 5L);
        repo1.updateRequestResponse("rq-persist", "{\"version\":1}");

        // 唯一约束：泵站业务键、曲线申请、请求键均不可重复
        assertThrows(DuplicateKeyException.class, () -> repo1.insertPumpStation("st-persist",
                new BigDecimal("1.000"), new BigDecimal("1.000"), 6L));
        assertThrows(DuplicateKeyException.class, () -> repo1.insertCurve(allocationId, stationId, 2, 6L));
        assertThrows(DuplicateKeyException.class, () -> repo1.insertRequest("rq-persist", "{}", 6L));

        // 全新连接与仓储实例读取
        WaterRepository repo2 = new WaterRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
        PumpStationRow station = repo2.findPumpStationByKey("st-persist");
        assertNotNull(station);
        assertEquals(1L, station.version());
        assertEquals(new BigDecimal("10.000"), station.maxFlow());
        assertEquals(new BigDecimal("0.500"), station.maxRamp());

        CurveRow curve = repo2.findCurveByAllocationId(allocationId);
        assertNotNull(curve);
        assertEquals(3, curve.nodeCount());
        assertNull(curve.approvedVersion());
        List<CurveNodeRow> nodes = repo2.listCurveNodes(curve.id());
        assertEquals(3, nodes.size());
        assertEquals(60_000_000_000L, nodes.get(1).minuteNanos());
        assertEquals(new BigDecimal("10.000"), nodes.get(1).flow());

        // 批准冻结版本后可按泵站查询已批准曲线
        repo2.updateAllocationStatus(allocationId, "APPROVED", 7L);
        repo2.setCurveApprovedVersion(curveId, 1L);
        List<CurveRow> approved = repo2.listApprovedCurves(stationId);
        assertEquals(1, approved.size());
        assertEquals(1L, approved.get(0).approvedVersion());

        RequestRow request = repo2.findRequest("rq-persist");
        assertNotNull(request);
        assertEquals("{\"version\":1}", request.response());

        // 参数更新递增版本
        repo2.updatePumpStation(stationId, new BigDecimal("8.000"), new BigDecimal("0.500"), 2L, 8L);
        assertEquals(2L, repo2.findPumpStationByKey("st-persist").version());
    }
}
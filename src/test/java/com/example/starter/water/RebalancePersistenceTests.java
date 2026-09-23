package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.RebalanceRow;
import com.example.starter.water.WaterRepository.SliceRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.WaterRepository.WindowSourceRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
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

/**
 * 多水源重平衡持久化验证：同一 JVM 内使用命名 H2 内存库（MODE=MySQL），建表写入窗口状态、
 * 水源配置、额度分片与重平衡冻结快照后，以全新连接与仓储实例重新读取核对。
 */
class RebalancePersistenceTests {

    private static final String URL_TEMPLATE =
            "jdbc:h2:mem:rebalance-persist-%s;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    void sourcesSlicesAndRebalanceSnapshotAreReadableFromNewConnection() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));

        // 第一次“运行”：建表并写入窗口、水源配置、绑定水源的申请与分片、核销与重平衡快照
        SimpleDriverDataSource first = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = first.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo1 = new WaterRepository(new JdbcTemplate(first));
        long windowId = repo1.insertWindow("wk-rb-persist", "ch-rb", 1_000L, 2_000L,
                new BigDecimal("300.000"), 1L);
        repo1.insertWindowSource(windowId, "s1", new BigDecimal("100.000"), 2L);
        repo1.insertWindowSource(windowId, "s2", new BigDecimal("100.000"), 2L);
        long allocId = repo1.insertAllocation("ak-rb-a", windowId, "user-1", new BigDecimal("60.000"),
                "alice", "s1", 3L);
        repo1.insertSlice(allocId, "s1", BigDecimal.ZERO, BigDecimal.ZERO);
        // 批准：持有 60，分片同步 60，版本 1
        repo1.updateAllocationStatus(allocId, "APPROVED", 4L);
        repo1.updateSlice(repo1.findSlice(allocId, "s1").id(), new BigDecimal("60.000"), BigDecimal.ZERO);
        // 核销 20：分片核销量 20，版本 2
        SliceRow slice = repo1.findSlice(allocId, "s1");
        repo1.updateSlice(slice.id(), slice.amount(), new BigDecimal("20.000"));
        repo1.bumpVersion(allocId, 5L);
        // 重平衡 s1->s2 搬 40：s1 后态 20（恰等于已核销量），新增 s2 分片 40，版本 3
        repo1.updateSlice(slice.id(), new BigDecimal("20.000"), new BigDecimal("20.000"));
        repo1.insertSlice(allocId, "s2", new BigDecimal("40.000"), BigDecimal.ZERO);
        repo1.bumpVersion(allocId, 6L);
        String snapshot = "{\"rebalanceKey\":\"rb-persist\",\"status\":\"ACTIVATED\"}";
        repo1.insertRebalance("rb-persist", windowId, "rq-persist", snapshot, 6L);
        repo1.closeWindow(windowId);

        // 第二次读取：全新连接与仓储实例，同一 JVM 内数据仍在
        WaterRepository repo2 = new WaterRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
        WindowRow window = repo2.findWindowById(windowId);
        assertNotNull(window);
        assertEquals("CLOSED", window.status());

        List<WindowSourceRow> sources = repo2.listWindowSources(windowId);
        assertEquals(2, sources.size());
        assertEquals("s1", sources.get(0).sourceId());
        assertEquals(new BigDecimal("100.000"), sources.get(0).supplyCap());

        AllocationRow allocation = repo2.findAllocationByKey("ak-rb-a");
        assertNotNull(allocation);
        assertEquals("s1", allocation.sourceId());
        assertEquals(3, allocation.version());
        assertEquals(new BigDecimal("60.000"), allocation.heldAmount());

        // 分片矩阵：s1=20（核销量 20 保留），s2=40；行总额 60 守恒
        List<SliceRow> slices = repo2.listSlicesByAllocation(allocId);
        assertEquals(2, slices.size());
        assertEquals("s1", slices.get(0).sourceId());
        assertEquals(new BigDecimal("20.000"), slices.get(0).amount());
        assertEquals(new BigDecimal("20.000"), slices.get(0).consumed());
        assertEquals("s2", slices.get(1).sourceId());
        assertEquals(new BigDecimal("40.000"), slices.get(1).amount());
        List<SliceRow> windowSlices = repo2.listSlicesByWindow(windowId);
        assertEquals(2, windowSlices.size());
        assertEquals("ak-rb-a", windowSlices.get(0).allocationKey());

        RebalanceRow rebalance = repo2.findRebalanceByKey("rb-persist");
        assertNotNull(rebalance);
        assertEquals(windowId, rebalance.windowId());
        assertEquals("rq-persist", rebalance.requestId());
        assertEquals(snapshot, rebalance.snapshotJson());
        assertEquals(1, repo2.listRebalances(windowId).size());
        assertNull(repo2.findRebalanceByKey("rb-missing"));
    }
}

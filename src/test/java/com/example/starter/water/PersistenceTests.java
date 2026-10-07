package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.GraphEdgeRow;
import com.example.starter.water.WaterRepository.SegmentRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
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

/**
 * 持久化验证：同一 JVM 内使用命名 H2 内存库（MODE=MySQL），建表写入后以全新连接与仓储实例
 * 重新读取，验证持有额度口径、转让流水与命令日志保持一致。本题不要求跨进程重启恢复。
 */
class PersistenceTests {

    private static final String URL_TEMPLATE =
            "jdbc:h2:mem:persist-%s;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    void heldAmountsAndTransfersAreReadableFromNewConnection() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));

        // 第一次“运行”：建表并写入窗口、已批准申请、转让扣减、生效限供与命令
        SimpleDriverDataSource first = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = first.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo1 = new WaterRepository(new JdbcTemplate(first));
        long windowId = repo1.insertWindow("wk-persist", "ch-persist", 1_000L, 2_000L,
                new BigDecimal("10.000"), null, 1L);
        repo1.insertAllocation("ak-source", windowId, "user-1", new BigDecimal("6.000"), null, "alice", 2L);
        repo1.insertAllocation("ak-target", windowId, "user-2", new BigDecimal("2.500"), null, "bob", 3L);
        AllocationRow source = repo1.findAllocationByKey("ak-source");
        AllocationRow target = repo1.findAllocationByKey("ak-target");
        // 普通批准：源持有额度等于原申请水量
        repo1.updateAllocationStatus(source.id(), "APPROVED", 4L);
        // 转让 2.5：源持有 3.5、目标 APPROVED 持有 2.5、写不可变流水
        repo1.decrementHeldAmount(source.id(), new BigDecimal("2.500"), 5L);
        repo1.updateAllocationStatus(target.id(), "APPROVED", 5L);
        repo1.insertTransfer("tk-persist", windowId, "ak-source", "ak-target",
                new BigDecimal("2.500"), "alice", 5L);
        repo1.insertCurtailment(windowId, new BigDecimal("8.000"), 6L);
        repo1.insertCommand("cmd-persist", "TRANSFER",
                "TRANSFER|tk-persist|ak-source|ak-target|alice", 7L);
        repo1.updateCommandResponse("cmd-persist", "{\"transferKey\":\"tk-persist\"}");

        // 第二次读取：全新连接与仓储实例，同一 JVM 内数据仍在
        WaterRepository repo2 = new WaterRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
        WindowRow window = repo2.findWindowById(windowId);
        assertNotNull(window);
        assertEquals(new BigDecimal("10.000"), window.plannedVolume());
        assertEquals("wk-persist", window.windowKey());

        AllocationRow source2 = repo2.findAllocationByKey("ak-source");
        assertNotNull(source2);
        assertEquals("APPROVED", source2.status());
        // 原申请水量不可改写
        assertEquals(new BigDecimal("6.000"), source2.amount());
        // 转出后持有 3.5
        assertEquals(new BigDecimal("3.500"), source2.heldAmount());
        assertEquals("alice", source2.requester());

        AllocationRow target2 = repo2.findAllocationByKey("ak-target");
        assertEquals("APPROVED", target2.status());
        assertEquals(new BigDecimal("2.500"), target2.amount());
        assertEquals(new BigDecimal("2.500"), target2.heldAmount());

        // 容量统计汇总 APPROVED 申请的当前持有额度：3.5 + 2.5 = 6
        assertEquals(new BigDecimal("6.000"), repo2.sumApprovedAmount(windowId));

        TransferRow transfer = repo2.findTransferByKey("tk-persist");
        assertNotNull(transfer);
        assertEquals(windowId, transfer.windowId());
        assertEquals("ak-source", transfer.sourceAllocationKey());
        assertEquals("ak-target", transfer.targetAllocationKey());
        assertEquals(new BigDecimal("2.500"), transfer.amount());
        assertEquals(1, repo2.listTransfers(windowId).size());

        CurtailmentRow curtailment = repo2.findActiveCurtailment(windowId);
        assertNotNull(curtailment);
        assertEquals(new BigDecimal("8.000"), curtailment.volume());

        assertEquals("{\"transferKey\":\"tk-persist\"}", repo2.findCommand("cmd-persist").response());
    }

    @Test
    void cancelledAllocationHeldAmountIsZero() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo = new WaterRepository(new JdbcTemplate(dataSource));
        long windowId = repo.insertWindow("wk-cancel", "ch-cancel", 1L, 2L, new BigDecimal("5"), null, 1L);
        repo.insertAllocation("ak-cancel", windowId, "user-1", new BigDecimal("3.000"), null, "alice", 2L);
        AllocationRow allocation = repo.findAllocationByKey("ak-cancel");
        repo.updateAllocationStatus(allocation.id(), "APPROVED", 3L);
        assertEquals(new BigDecimal("3.000"), repo.sumApprovedAmount(windowId));
        // 取消：持有额度归零，容量汇总不再计入
        repo.updateAllocationStatus(allocation.id(), "CANCELLED", 4L);
        AllocationRow cancelled = repo.findAllocationByKey("ak-cancel");
        assertEquals("CANCELLED", cancelled.status());
        // 原申请水量保留，持有额度归零
        assertEquals(new BigDecimal("3.000"), cancelled.amount());
        assertEquals(0, cancelled.heldAmount().compareTo(BigDecimal.ZERO));
        assertEquals(0, repo.sumApprovedAmount(windowId).compareTo(BigDecimal.ZERO));
    }

    @Test
    void graphEdgesAndPathSegmentsSurviveNewConnectionAndCancel() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        SimpleDriverDataSource first = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = first.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo1 = new WaterRepository(new JdbcTemplate(first));
        // 渠网窗口：版本 1，节点 A/B/C，渠段 A->B(5)、B->C(5)、C->A(5)
        long windowId = repo1.insertWindow("wk-graph", "ch-graph", 1L, 2L, new BigDecimal("100"), 1, 1L);
        repo1.insertGraphRevision(windowId, 1, "[\"A\",\"B\",\"C\"]", null, 1L);
        repo1.insertGraphEdge(windowId, "A", "B", 5L, 1);
        repo1.insertGraphEdge(windowId, "B", "C", 5L, 1);
        repo1.insertGraphEdge(windowId, "C", "A", 5L, 1);
        // 带路径申请：A->B->C，流量 2，批准后取消
        long allocationId = repo1.insertAllocation("ak-path", windowId, "user-1",
                new BigDecimal("10.000"), 2L, "alice", 2L);
        repo1.insertSegment(allocationId, 0, "A", "B");
        repo1.insertSegment(allocationId, 1, "B", "C");
        repo1.updateAllocationStatus(allocationId, "APPROVED", 3L);
        assertEquals(2, repo1.listContributions(windowId).size());
        repo1.updateAllocationStatus(allocationId, "CANCELLED", 4L);
        // 图修订：移除无贡献的 C->A，版本升到 2
        GraphEdgeRow caEdge = repo1.listCurrentEdges(windowId).stream()
                .filter(e -> e.fromNode().equals("C")).findFirst().orElseThrow();
        repo1.removeGraphEdge(caEdge.id(), 2);
        repo1.insertGraphRevision(windowId, 2, "[\"A\",\"B\",\"C\"]", "admin", 5L);
        repo1.updateWindowGraphVersion(windowId, 2);

        // 全新连接与仓储实例读取：冻结的边全集、有序路径与贡献口径保持一致
        WaterRepository repo2 = new WaterRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
        WindowRow window = repo2.findWindowById(windowId);
        assertEquals(2, window.graphVersion());
        assertEquals(2, repo2.listCurrentEdges(windowId).size());
        assertEquals(3, repo2.listAllEdges(windowId).size());
        GraphEdgeRow removed = repo2.listAllEdges(windowId).stream()
                .filter(e -> e.fromNode().equals("C")).findFirst().orElseThrow();
        assertEquals(1, removed.addedVersion());
        assertEquals(2, removed.removedVersion());
        assertNotNull(repo2.findGraphRevision(windowId, 1));
        assertEquals("admin", repo2.findGraphRevision(windowId, 2).actor());

        // 取消后路径段仍冻结保留且保序，流量贡献归零
        List<SegmentRow> segments = repo2.listSegments(allocationId);
        assertEquals(2, segments.size());
        assertEquals("A", segments.get(0).fromNode());
        assertEquals("B", segments.get(0).toNode());
        assertEquals("B", segments.get(1).fromNode());
        assertEquals("C", segments.get(1).toNode());
        AllocationRow cancelled = repo2.findAllocationByKey("ak-path");
        assertEquals("CANCELLED", cancelled.status());
        assertEquals(2L, cancelled.flowRate());
        assertEquals(0, repo2.listContributions(windowId).size());
    }
}

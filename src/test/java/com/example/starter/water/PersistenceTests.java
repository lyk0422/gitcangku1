package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.BasinPairRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.ExecutionRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.sql.Connection;
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
                new BigDecimal("10.000"), 1L);
        repo1.insertAllocation("ak-source", windowId, "user-1", new BigDecimal("6.000"), "alice", 2L);
        repo1.insertAllocation("ak-target", windowId, "user-2", new BigDecimal("2.500"), "bob", 3L);
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
        long windowId = repo.insertWindow("wk-cancel", "ch-cancel", 1L, 2L, new BigDecimal("5"), 1L);
        repo.insertAllocation("ak-cancel", windowId, "user-1", new BigDecimal("3.000"), "alice", 2L);
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
    void basinVolumesAndExecutionsAreReadableFromNewConnection() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));

        // 第一次“运行”：建表，登记双池（面积 10/10，上 30、下 10），批准申请并合成执行 5
        SimpleDriverDataSource first = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = first.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo1 = new WaterRepository(new JdbcTemplate(first));
        long windowId = repo1.insertWindow("wk-basin", "ch-basin", 1_000L, 2_000L,
                new BigDecimal("100.000"), 1L);
        repo1.insertBasinPair(windowId, 10L, 10L, new BigDecimal("30.000"), new BigDecimal("10.000"),
                new BigDecimal("10.000"), new BigDecimal("40.000"),
                new BigDecimal("5.000"), new BigDecimal("30.000"), 2L);
        repo1.insertAllocation("ak-exec", windowId, "user-1", new BigDecimal("30.000"), "alice", 3L);
        AllocationRow allocation = repo1.findAllocationByKey("ak-exec");
        repo1.updateAllocationStatus(allocation.id(), "APPROVED", 4L);
        // 执行 5：上池 25、下池 15，冻结前后体积与水位展示值
        BasinPairRow basins = repo1.findBasinPairByWindowId(windowId);
        repo1.updateBasinVolumes(basins.id(), new BigDecimal("25.000"), new BigDecimal("15.000"), 5L);
        repo1.addExecutedAmount(allocation.id(), new BigDecimal("5.000"), 5L);
        repo1.insertExecution("ek-persist", "ak-exec", windowId, new BigDecimal("5.000"),
                new BigDecimal("30.000"), new BigDecimal("25.000"),
                new BigDecimal("10.000"), new BigDecimal("15.000"),
                new BigDecimal("3"), new BigDecimal("2.5"), new BigDecimal("1"), new BigDecimal("1.5"),
                "alice", 5L);

        // 第二次读取：全新连接与仓储实例，同一 JVM 内数据仍在
        WaterRepository repo2 = new WaterRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
        BasinPairRow basins2 = repo2.findBasinPairByWindowId(windowId);
        assertNotNull(basins2);
        assertEquals(10L, basins2.upperAreaM2());
        assertEquals(new BigDecimal("25.000"), basins2.upperVolumeM3());
        assertEquals(new BigDecimal("15.000"), basins2.lowerVolumeM3());
        assertEquals(new BigDecimal("10.000"), basins2.upperMinM3());
        assertEquals(new BigDecimal("30.000"), basins2.lowerMaxM3());

        AllocationRow allocation2 = repo2.findAllocationByKey("ak-exec");
        assertEquals("APPROVED", allocation2.status());
        assertEquals(new BigDecimal("5.000"), allocation2.executedAmount());
        assertEquals(new BigDecimal("25.000"), allocation2.heldAmount());

        ExecutionRow execution = repo2.findExecutionByKey("ek-persist");
        assertNotNull(execution);
        assertEquals("ak-exec", execution.allocationKey());
        assertEquals(new BigDecimal("5.000"), execution.volumeM3());
        assertEquals(new BigDecimal("30.000"), execution.upperBeforeM3());
        assertEquals(new BigDecimal("25.000"), execution.upperAfterM3());
        assertEquals(new BigDecimal("10.000"), execution.lowerBeforeM3());
        assertEquals(new BigDecimal("15.000"), execution.lowerAfterM3());
        assertEquals(0, execution.upperLevelBeforeM().compareTo(new BigDecimal("3")));
        assertEquals(0, execution.lowerLevelAfterM().compareTo(new BigDecimal("1.5")));
        assertEquals("alice", execution.actor());
        assertEquals(1, repo2.listExecutionsByAllocation("ak-exec").size());
    }
}

package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.SettlementBatchRow;
import com.example.starter.water.WaterRepository.SettlementInstructionRow;
import com.example.starter.water.WaterRepository.SettlementSnapshotRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void settlementBatchInstructionsAndSnapshotsPersistWithVersionsAndConstraints() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(8));
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo = new WaterRepository(new JdbcTemplate(dataSource));
        long windowId = repo.insertWindow("wk-set", "ch-set", 1L, 2L, new BigDecimal("20"), 1L);
        repo.insertAllocation("ak-a", windowId, "user-a", new BigDecimal("10"), "alice", 2L);
        repo.insertAllocation("ak-b", windowId, "user-b", new BigDecimal("4"), "bob", 3L);
        repo.insertAllocation("ak-c", windowId, "user-c", new BigDecimal("3"), "carol", 4L);
        AllocationRow a = repo.findAllocationByKey("ak-a");
        AllocationRow b = repo.findAllocationByKey("ak-b");
        AllocationRow c = repo.findAllocationByKey("ak-c");
        repo.updateAllocationStatus(a.id(), "APPROVED", 5L);
        repo.updateAllocationStatus(b.id(), "APPROVED", 5L);
        repo.updateAllocationStatus(c.id(), "APPROVED", 5L);
        assertEquals(0, a.version());

        long batchId = repo.insertSettlementBatch("sk-set", "rq-set", "cmd-set", windowId, 3, 6L);
        // 原指令按输入顺序：A->B 4、A->C 1、B->C 2（净额 A=-5 B=+2 C=+3）
        repo.insertSettlementInstruction(batchId, 0, "ik-1", "ak-a", "ak-b", new BigDecimal("4"), 6L);
        repo.insertSettlementInstruction(batchId, 1, "ik-2", "ak-a", "ak-c", new BigDecimal("1"), 6L);
        repo.insertSettlementInstruction(batchId, 2, "ik-3", "ak-b", "ak-c", new BigDecimal("2"), 6L);
        // 非零净额主体：A -5、B +2、C +3；C 净收入后持有 6 > 原申请 3（题目允许，仅要求非负）
        repo.applySettlementNetChange(a.id(), new BigDecimal("-5"), 6L);
        repo.applySettlementNetChange(b.id(), new BigDecimal("2"), 6L);
        repo.applySettlementNetChange(c.id(), new BigDecimal("3"), 6L);
        repo.insertSettlementSnapshot(batchId, "ak-a", new BigDecimal("-5"), new BigDecimal("10"),
                new BigDecimal("5"), 0, 1);
        repo.insertSettlementSnapshot(batchId, "ak-b", new BigDecimal("2"), new BigDecimal("4"),
                new BigDecimal("6"), 0, 1);
        repo.insertSettlementSnapshot(batchId, "ak-c", new BigDecimal("3"), new BigDecimal("3"),
                new BigDecimal("6"), 0, 1);

        // 版本与余额经新连接读回
        WaterRepository reread = new WaterRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
        AllocationRow a2 = reread.findAllocationByKey("ak-a");
        assertEquals(1, a2.version());
        assertEquals(new BigDecimal("5.000"), a2.heldAmount());
        AllocationRow b2 = reread.findAllocationByKey("ak-b");
        assertEquals(new BigDecimal("6.000"), b2.heldAmount());
        AllocationRow c2 = reread.findAllocationByKey("ak-c");
        assertEquals(new BigDecimal("6.000"), c2.heldAmount());

        // 净额为 0 的主体版本仍加一
        reread.incrementAllocationVersion(a2.id(), 7L);
        assertEquals(2, reread.findAllocationByKey("ak-a").version());
        assertEquals(new BigDecimal("5.000"), reread.findAllocationByKey("ak-a").heldAmount());

        // 批次/指令/快照查询
        SettlementBatchRow batch = reread.findSettlementByKey("sk-set");
        assertNotNull(batch);
        assertEquals("rq-set", batch.requestId());
        assertEquals(3, batch.instructionCount());
        assertEquals(batchId, reread.findSettlementByRequestId("rq-set").id());
        List<SettlementInstructionRow> instructions = reread.listSettlementInstructions(batchId);
        assertEquals(3, instructions.size());
        assertEquals("ik-1", instructions.get(0).instructionKey());
        assertEquals(2, instructions.get(2).seqNo());
        assertTrue(reread.existsSettlementInstructionKey("ik-2"));
        List<SettlementSnapshotRow> snapshots = reread.listSettlementSnapshots(batchId);
        assertEquals(3, snapshots.size());
        assertEquals(new BigDecimal("-5.000"), snapshots.get(0).netChange());
        assertEquals(1, snapshots.get(0).versionAfter());

        // 主体历史按批次顺序
        List<SettlementSnapshotRow> history = reread.listSubjectSettlementHistory("ak-b");
        assertEquals(1, history.size());
        assertEquals("ak-b", history.get(0).allocationKey());
        assertEquals(new BigDecimal("2.000"), history.get(0).netChange());

        // 行级约束：指令键全局唯一、持有额度非负（超扣由 H2 CHECK 拒绝）
        assertThrows(DataIntegrityViolationException.class, () ->
                reread.insertSettlementInstruction(batchId, 0, "ik-1", "ak-b", "ak-a",
                        new BigDecimal("1"), 8L));
        assertThrows(DataIntegrityViolationException.class, () ->
                reread.applySettlementNetChange(a2.id(), new BigDecimal("-999"), 9L));
    }
}

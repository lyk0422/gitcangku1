package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.SettlementInstructionRow;
import com.example.starter.water.WaterRepository.SettlementLegRow;
import com.example.starter.water.WaterRepository.SettlementRow;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void settlementSnapshotsAndNetUpdateAreReadableFromNewConnection() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(8));
        SimpleDriverDataSource firstDs = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = firstDs.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo = new WaterRepository(new JdbcTemplate(firstDs));
        long windowId = repo.insertWindow("wk-set", "ch-set", 1L, 2L, new BigDecimal("100.000"), 1L);
        repo.insertAllocation("ak-a", windowId, "user-a", new BigDecimal("10.000"), "alice", 2L);
        repo.insertAllocation("ak-b", windowId, "user-b", new BigDecimal("10.000"), "bob", 2L);
        AllocationRow a = repo.findAllocationByKey("ak-a");
        AllocationRow b = repo.findAllocationByKey("ak-b");
        repo.updateAllocationStatus(a.id(), "APPROVED", 3L);
        repo.updateAllocationStatus(b.id(), "APPROVED", 3L);

        // 模拟一次成功清算：A->B 6（同对两条 3+3，保守取大接收方）；写批次头、指令、主体快照
        long now = 10L;
        long settlementId = repo.insertSettlement("sk-1", windowId, 2, new BigDecimal("6.000"), now);
        repo.insertSettlementInstruction(settlementId, 0, "ik-1", "ak-a", "ak-b", new BigDecimal("3"));
        repo.insertSettlementInstruction(settlementId, 1, "ik-2", "ak-a", "ak-b", new BigDecimal("3"));
        // 非零净额一次更新 + 快照；零净额主体仅版本加一并快照
        assertEquals(1, repo.applySettlementNet("ak-a", new BigDecimal("-6"), 11L));
        repo.insertSettlementLeg(settlementId, "ak-a", new BigDecimal("-6"), new BigDecimal("10"),
                new BigDecimal("4"), 2L, 3L);
        assertEquals(1, repo.applySettlementNet("ak-b", new BigDecimal("6"), 11L));
        repo.insertSettlementLeg(settlementId, "ak-b", new BigDecimal("6"), new BigDecimal("10"),
                new BigDecimal("16"), 2L, 3L);
        // 允许持有额度超过原申请（净额再分配只保证非负与总额守恒），故 16 合法

        // 第二次读取：全新连接与仓储实例
        WaterRepository repo2 = new WaterRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
        SettlementRow head = repo2.findSettlementByKey("sk-1");
        assertNotNull(head);
        assertEquals(2, head.instructionCount());
        assertEquals(new BigDecimal("6.000"), head.totalVolume());
        List<SettlementInstructionRow> instructions = repo2.listSettlementInstructions(settlementId);
        assertEquals(2, instructions.size());
        assertEquals("ik-2", instructions.get(1).instructionKey());
        List<SettlementLegRow> legs = repo2.listSettlementLegs(settlementId);
        assertEquals(2, legs.size());
        assertEquals(new BigDecimal("-6.000"), legs.get(0).netChange());
        assertEquals(new BigDecimal("16.000"), legs.get(1).afterHeld());
        assertEquals(3L, legs.get(0).afterVersion());
        assertTrue(repo2.existsSettlementInstructionKey("ik-1"));
        assertFalse(repo2.existsSettlementInstructionKey("ik-absent"));
        assertEquals(2, repo2.countUsedInstructionKeys(List.of("ik-1", "ik-2", "ik-absent")));
        List<SettlementRow> history = repo2.listSettlementsByAllocationKey("ak-b");
        assertEquals(1, history.size());
        assertEquals("sk-1", history.get(0).settlementKey());

        // 实际持有额度与版本
        AllocationRow a2 = repo2.findAllocationByKey("ak-a");
        assertEquals(new BigDecimal("4.000"), a2.heldAmount());
        assertEquals(3L, a2.quotaVersion());
        AllocationRow b2 = repo2.findAllocationByKey("ak-b");
        assertEquals(new BigDecimal("16.000"), b2.heldAmount());
        assertEquals(3L, b2.quotaVersion());
        // 总额守恒：4 + 16 = 20
        assertEquals(new BigDecimal("20.000"), repo2.sumApprovedAmount(windowId));
    }
}

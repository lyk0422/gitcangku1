package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.UsageRow;
import com.example.starter.water.WaterRepository.WindowRow;
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
        // 转出后持有 3.5；该场景未核销，累计已用为 0（旧数据按 0 解释）
        assertEquals(new BigDecimal("3.500"), source2.heldAmount());
        assertEquals(0, source2.usedAmount().compareTo(BigDecimal.ZERO));
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
    void usageConsumesHeldAddsUsedAndFlowIsReadableFromNewConnection() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(8));
        SimpleDriverDataSource first = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = first.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo1 = new WaterRepository(new JdbcTemplate(first));
        long windowId = repo1.insertWindow("wk-use", "ch-use", 1L, 2L, new BigDecimal("10.000"), 1L);
        repo1.insertAllocation("ak-use", windowId, "user-1", new BigDecimal("6.000"), "alice", 2L);
        AllocationRow allocation = repo1.findAllocationByKey("ak-use");
        repo1.updateAllocationStatus(allocation.id(), "APPROVED", 3L);

        // 核销 2.5：条件更新扣持有、加已用；不足时受影响行数为 0
        int updated = repo1.consumeUsage(allocation.id(), new BigDecimal("2.500"), 4L);
        assertEquals(1, updated);
        AllocationRow afterConsume = repo1.findAllocationByKey("ak-use");
        assertEquals(new BigDecimal("3.500"), afterConsume.heldAmount());
        assertEquals(new BigDecimal("2.500"), afterConsume.usedAmount());
        // 持有 3.5 再核销 4 -> 条件不满足，0 行变化
        assertEquals(0, repo1.consumeUsage(allocation.id(), new BigDecimal("4.000"), 5L));
        AllocationRow unchanged = repo1.findAllocationByKey("ak-use");
        assertEquals(new BigDecimal("3.500"), unchanged.heldAmount());
        assertEquals(new BigDecimal("2.500"), unchanged.usedAmount());

        repo1.insertUsage("uk-use", windowId, "ak-use", new BigDecimal("2.500"),
                new BigDecimal("2.500"), new BigDecimal("3.500"), "alice", 4L);

        // 全新连接与仓储实例读取：流水、计数、容量口径一致
        WaterRepository repo2 = new WaterRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
        AllocationRow read = repo2.findAllocationByKey("ak-use");
        assertEquals(new BigDecimal("3.500"), read.heldAmount());
        assertEquals(new BigDecimal("2.500"), read.usedAmount());
        // 已占用量 = 全部已用 2.5 + APPROVED 持有 3.5 = 6
        assertEquals(new BigDecimal("2.500"), repo2.sumUsedAmount(windowId));
        assertEquals(new BigDecimal("3.500"), repo2.sumApprovedAmount(windowId));

        UsageRow usage = repo2.findUsageByKey("uk-use");
        assertNotNull(usage);
        assertEquals(windowId, usage.windowId());
        assertEquals("ak-use", usage.allocationKey());
        assertEquals(new BigDecimal("2.500"), usage.amount());
        assertEquals(new BigDecimal("2.500"), usage.usedAfter());
        assertEquals(new BigDecimal("3.500"), usage.heldAfter());
        assertEquals("alice", usage.actor());
        List<UsageRow> list = repo2.listUsages(windowId);
        assertEquals(1, list.size());
        assertEquals("uk-use", list.get(0).usageKey());
    }

    @Test
    void cancelledAllocationUsedAmountStillCountsTowardOccupancy() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(8));
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo = new WaterRepository(new JdbcTemplate(dataSource));
        long windowId = repo.insertWindow("wk-used-cancel", "ch-used-cancel", 1L, 2L,
                new BigDecimal("10.000"), 1L);
        repo.insertAllocation("ak", windowId, "user-1", new BigDecimal("6.000"), "alice", 2L);
        long id = repo.findAllocationByKey("ak").id();
        repo.updateAllocationStatus(id, "APPROVED", 3L);
        repo.consumeUsage(id, new BigDecimal("4.000"), 4L);
        // 取消：持有归零，但已用 4 保留
        repo.updateAllocationStatus(id, "CANCELLED", 5L);
        AllocationRow row = repo.findAllocationByKey("ak");
        assertEquals("CANCELLED", row.status());
        assertEquals(0, row.heldAmount().compareTo(BigDecimal.ZERO));
        assertEquals(new BigDecimal("4.000"), row.usedAmount());
        // APPROVED 持有为 0（取消不汇总），全部已用仍为 4
        assertEquals(0, repo.sumApprovedAmount(windowId).compareTo(BigDecimal.ZERO));
        assertEquals(new BigDecimal("4.000"), repo.sumUsedAmount(windowId));
    }

    @Test
    void quotaCheckConstraintAndUsageKeyUniquenessAreEnforcedByH2() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(8));
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        WaterRepository repo = new WaterRepository(jdbc);
        long windowId = repo.insertWindow("wk-chk", "ch-chk", 1L, 2L, new BigDecimal("10.000"), 1L);
        repo.insertAllocation("ak-chk", windowId, "user-1", new BigDecimal("5.000"), "alice", 2L);

        // 直接写库破坏 held + used <= amount：CHECK 约束必须拒绝
        org.springframework.dao.DataIntegrityViolationException ex = assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE allocation SET held_amount = 3, used_amount = 3"
                        + " WHERE allocation_key = ?", "ak-chk"));
        assertTrue(ex.getMessage() == null || ex.getMessage().toUpperCase().contains("CHK")
                || ex.getMostSpecificCause().getMessage().toUpperCase().contains("CONSTRAINT"));
        // 负持有也必须拒绝
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE allocation SET held_amount = -1 WHERE allocation_key = ?",
                        "ak-chk"));

        // usageKey 全局唯一：第二条同键流水拒绝
        long id = repo.findAllocationByKey("ak-chk").id();
        repo.updateAllocationStatus(id, "APPROVED", 3L);
        repo.consumeUsage(id, new BigDecimal("2.000"), 4L);
        repo.insertUsage("uk-dup", windowId, "ak-chk", new BigDecimal("2.000"),
                new BigDecimal("2.000"), new BigDecimal("3.000"), "alice", 4L);
        assertThrows(DuplicateKeyException.class, () -> repo.insertUsage("uk-dup", windowId,
                "ak-chk", new BigDecimal("1.000"), new BigDecimal("3.000"), new BigDecimal("2.000"),
                "alice", 5L));
        assertEquals(1, repo.listUsages(windowId).size());
    }
}

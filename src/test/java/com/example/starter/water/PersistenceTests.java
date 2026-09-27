package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CorrectionRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.EvaporationRateRow;
import com.example.starter.water.WaterRepository.SettlementRow;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void settlementAndCorrectionAreReadableFromNewConnection() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        SimpleDriverDataSource first = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = first.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo1 = new WaterRepository(new JdbcTemplate(first));
        long windowId = repo1.insertWindow("wk-set", "ch-set",
                java.time.OffsetDateTime.parse("2026-10-16T00:00:00Z").toInstant().getEpochSecond() * 1_000_000_000L,
                java.time.OffsetDateTime.parse("2026-10-17T00:00:00Z").toInstant().getEpochSecond() * 1_000_000_000L,
                new BigDecimal("10.000"), 1L);
        long dayStart = java.time.OffsetDateTime.parse("2026-10-16T00:00:00Z").toInstant().getEpochSecond()
                * 1_000_000_000L;
        repo1.insertRate("ch-set", dayStart, new BigDecimal("0.1000"), 2L);
        repo1.updateRate(repo1.findRate("ch-set", dayStart).id(), new BigDecimal("0.1000"), 3L);
        repo1.insertAllocation("ak-up", windowId, "user-up", new BigDecimal("10.000"), "alice", 4L);
        AllocationRow upstream = repo1.findAllocationByKey("ak-up");
        repo1.updateAllocationStatus(upstream.id(), "APPROVED", 5L);
        long occurred = java.time.OffsetDateTime.parse("2026-10-16T08:00:00Z").toInstant().getEpochSecond()
                * 1_000_000_000L;
        // 送达 2.000、率 10%：计入送达 2.00、损耗 0.20、上游扣减 2.20，满足守恒
        long settlementId = repo1.insertSettlement("sk-set", null, windowId, "ch-set", "ak-up", "user-down",
                dayStart, occurred, new BigDecimal("2.000"), new BigDecimal("2.000"),
                new BigDecimal("0.200"), new BigDecimal("2.200"), new BigDecimal("0.1000"), 1L,
                "gauger", 6L);
        repo1.decrementHeldAmount(upstream.id(), new BigDecimal("2.200"), 6L);
        repo1.insertCorrection("xk-set", settlementId, null, windowId, "ch-set", "ak-up", "user-down",
                dayStart, new BigDecimal("2.000"), new BigDecimal("2.000"), new BigDecimal("0.200"),
                new BigDecimal("2.200"), new BigDecimal("0.1000"), 1L, "冲销", "supervisor", 7L);
        repo1.incrementHeldAmount(upstream.id(), new BigDecimal("2.200"), 7L);

        // 全新连接与仓储实例重读
        WaterRepository repo2 = new WaterRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
        EvaporationRateRow rate = repo2.findRate("ch-set", dayStart);
        assertNotNull(rate);
        assertEquals(2L, rate.version());
        assertEquals(new BigDecimal("0.1000"), rate.rate());
        assertEquals(Long.valueOf(6L), repo2.firstSettlementNanos("ch-set", dayStart));

        SettlementRow settlement = repo2.findSettlementByKey("sk-set");
        assertNotNull(settlement);
        assertEquals(windowId, settlement.windowId());
        assertEquals("ch-set", settlement.channelId());
        assertEquals("ak-up", settlement.allocationKey());
        assertEquals("user-down", settlement.downstreamUserId());
        assertEquals(new BigDecimal("2.000"), settlement.deliveredVolume());
        assertEquals(new BigDecimal("2.000"), settlement.settledDeliveredVolume());
        assertEquals(new BigDecimal("0.200"), settlement.lossVolume());
        assertEquals(new BigDecimal("2.200"), settlement.upstreamDeduction());
        assertEquals(new BigDecimal("0.1000"), settlement.rateValue());
        assertEquals(1L, settlement.rateVersion());
        assertEquals("SETTLED", settlement.status());
        assertEquals(1, repo2.listSettlementsByWindow(windowId).size());

        // 回补后上游持有额度恢复为原申请水量 10
        AllocationRow upstream2 = repo2.findAllocationByKey("ak-up");
        assertEquals(0, upstream2.heldAmount().compareTo(new BigDecimal("10.000")));

        CorrectionRow correction = repo2.findCorrectionByKey("xk-set");
        assertNotNull(correction);
        assertEquals(settlementId, correction.originalSettlementId());
        assertEquals("冲销", correction.reason());
        assertEquals(correction.id(), repo2.findCorrectionByOriginalSettlement(settlementId).id());
        assertEquals(1, repo2.listCorrectionsByWindow(windowId).size());
    }

    @Test
    void conservationCheckAndUniqueConstraintsAreEnforcedByH2() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        WaterRepository repo = new WaterRepository(jdbc);
        long windowId = repo.insertWindow("wk-check", "ch-check", 1L, 9_000_000_000_000_000_000L,
                new BigDecimal("10"), 1L);
        repo.insertAllocation("ak-check", windowId, "u", new BigDecimal("10"), "alice", 2L);
        repo.insertRate("ch-check", 100L, new BigDecimal("0.1000"), 3L);

        // 违反守恒（上游扣减 != 计入送达 + 损耗）-> CHECK 约束拒绝
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO settlement (settlement_key, window_id, channel_id, allocation_key,"
                        + " downstream_user_id, day_start_nanos, occurred_nanos, delivered_volume,"
                        + " settled_delivered_volume, loss_volume, upstream_deduction, rate_value,"
                        + " rate_version, status, actor, created_nanos)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SETTLED', ?, ?)",
                "sk-bad", windowId, "ch-check", "ak-check", null, 100L, 200L,
                new BigDecimal("1.000"), new BigDecimal("1.000"), new BigDecimal("0.200"),
                new BigDecimal("1.300"), new BigDecimal("0.1000"), 1L, "alice", 4L));

        // 蒸发率超界 -> CHECK 拒绝
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO evaporation_rate (channel_id, day_start_nanos, rate, version,"
                        + " created_nanos, updated_nanos) VALUES (?, ?, ?, 1, ?, ?)",
                "ch-check", 200L, new BigDecimal("0.3000"), 5L, 5L));

        // 同一渠道日重复登记 -> 唯一约束拒绝
        assertThrows(DataIntegrityViolationException.class, () -> repo.insertRate(
                "ch-check", 100L, new BigDecimal("0.0500"), 6L));

        // 合法核销 + 更正，再次更正同一原核销 -> 唯一约束拒绝
        long settlementId = repo.insertSettlement("sk-ok", null, windowId, "ch-check", "ak-check", null,
                100L, 200L, new BigDecimal("1.000"), new BigDecimal("1.000"), new BigDecimal("0.100"),
                new BigDecimal("1.100"), new BigDecimal("0.1000"), 1L, "alice", 7L);
        repo.insertCorrection("xk-1", settlementId, null, windowId, "ch-check", "ak-check", null, 100L,
                new BigDecimal("1.000"), new BigDecimal("1.000"), new BigDecimal("0.100"),
                new BigDecimal("1.100"), new BigDecimal("0.1000"), 1L, "r", "alice", 8L);
        assertThrows(DataIntegrityViolationException.class, () -> repo.insertCorrection(
                "xk-2", settlementId, null, windowId, "ch-check", "ak-check", null, 100L,
                new BigDecimal("1.000"), new BigDecimal("1.000"), new BigDecimal("0.100"),
                new BigDecimal("1.100"), new BigDecimal("0.1000"), 1L, "r", "alice", 9L));

        // 同键核销 -> 唯一约束拒绝
        assertThrows(DataIntegrityViolationException.class, () -> repo.insertSettlement(
                "sk-ok", null, windowId, "ch-check", "ak-check", null, 100L, 201L,
                new BigDecimal("1.000"), new BigDecimal("1.000"), new BigDecimal("0.100"),
                new BigDecimal("1.100"), new BigDecimal("0.1000"), 1L, "alice", 10L));

        // 失败写入不留半成品：只有 sk-ok 一条核销、xk-1 一条更正
        assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement", Integer.class));
        assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_correction", Integer.class));
        assertNull(repo.findSettlementByKey("sk-bad"));
    }
}

package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.AnomalyRow;
import com.example.starter.water.WaterRepository.ChannelRuleRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.ReadingRow;
import com.example.starter.water.WaterRepository.ReleaseRow;
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
    void sensorAnomalyAndSettlementAreReadableFromNewConnection() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));

        // 第一次“运行”：建表并写入渠道、规则版本、读数、异常、冻结、解除与核销
        SimpleDriverDataSource first = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = first.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo1 = new WaterRepository(new JdbcTemplate(first));
        repo1.ensureChannel("ch-sensor", 1L);
        long ruleId = repo1.insertChannelRule("ch-sensor", 1, new BigDecimal("2.500"), 30, "op", 2L);
        long windowId = repo1.insertWindow("wk-sensor", "ch-sensor", 1_000L, 2_000L,
                new BigDecimal("10.000"), 3L);
        repo1.insertAllocation("ak-sensor", windowId, "user-1", new BigDecimal("6.000"), "alice", 4L);
        AllocationRow allocation = repo1.findAllocationByKey("ak-sensor");
        repo1.updateAllocationStatus(allocation.id(), "APPROVED", 5L);
        long reading1 = repo1.insertReading("ch-sensor", 1_000L, new BigDecimal("100.000"), "sensor-a", 6L);
        long reading2 = repo1.insertReading("ch-sensor", 2_000L, new BigDecimal("50.000"), "sensor-a", 7L);
        long anomalyId = repo1.insertAnomaly("ch-sensor", ruleId, reading2, "REGRESSION",
                new BigDecimal("50.000"), new BigDecimal("100.000"), new BigDecimal("-50.000"),
                1_000L, new BigDecimal("100.000"), 2_000L, new BigDecimal("50.000"), "sensor-a", 8L);
        repo1.insertFreeze(anomalyId, "ch-sensor", 8L);
        assertEquals(1, repo1.countOpenFreezes("ch-sensor"));
        repo1.insertRelease(anomalyId, "ch-sensor", "传感器漂移，已校准", new BigDecimal("80.000"),
                2_000L, "operator-b", 9L);
        repo1.decrementHeldAmount(allocation.id(), new BigDecimal("1.500"), 10L);
        repo1.insertSettlement("sk-sensor", "ak-sensor", windowId, "ch-sensor",
                new BigDecimal("1.500"), "alice", 10L);

        // 第二次读取：全新连接与仓储实例，同一 JVM 内数据仍在
        WaterRepository repo2 = new WaterRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
        assertTrue(repo2.channelExists("ch-sensor"));
        ChannelRuleRow rule = repo2.findCurrentRule("ch-sensor");
        assertNotNull(rule);
        assertEquals(1, rule.version());
        assertEquals(new BigDecimal("2.500"), rule.maxFlowRate());
        assertEquals(30, rule.maxIntervalMinutes());

        List<ReadingRow> readings = repo2.listReadings("ch-sensor");
        assertEquals(2, readings.size());
        assertEquals(reading1, readings.get(0).id());
        assertEquals(reading2, readings.get(1).id());
        assertEquals(new BigDecimal("50.000"), repo2.findLatestReading("ch-sensor").cumulativeValue());

        AnomalyRow anomaly = repo2.findAnomalyById(anomalyId);
        assertNotNull(anomaly);
        assertEquals("REGRESSION", anomaly.type());
        assertEquals(1, anomaly.ruleVersion());
        assertEquals(0, anomaly.deltaValue().compareTo(new BigDecimal("-50.000")));
        assertEquals("sensor-a", anomaly.readingActor());

        // 解除后渠道不再冻结，解除记录不可变字段完整
        assertEquals(0, repo2.countOpenFreezes("ch-sensor"));
        ReleaseRow release = repo2.findReleaseByAnomalyId(anomalyId);
        assertNotNull(release);
        assertEquals("传感器漂移，已校准", release.diagnosis());
        assertEquals(new BigDecimal("80.000"), release.substituteValue());
        assertEquals("operator-b", release.actor());

        // 核销流水与持有额度一致：6 - 1.5 = 4.5
        SettlementRow settlement = repo2.findSettlementByKey("sk-sensor");
        assertNotNull(settlement);
        assertEquals(new BigDecimal("1.500"), settlement.amount());
        assertEquals(windowId, settlement.windowId());
        assertEquals(1, repo2.listSettlements("ak-sensor").size());
        assertEquals(new BigDecimal("4.500"), repo2.findAllocationByKey("ak-sensor").heldAmount());
    }
}

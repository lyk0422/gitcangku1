package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.AnomalyRow;
import com.example.starter.water.WaterRepository.ChannelConfigRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.ReadingRow;
import com.example.starter.water.WaterRepository.ResolutionRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.WaterRepository.WriteoffRow;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void sensorTablesRoundTripConstraintsAndRollback() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo = new WaterRepository(new JdbcTemplate(dataSource));

        // 渠道 + 两个不可变规则版本
        repo.insertChannelIfAbsent("ch-p2", 1L, "alice");
        repo.insertChannelIfAbsent("ch-p2", 2L, "alice"); // 幂等，不覆盖登记信息
        long configId = repo.insertChannelConfig("ch-p2", 1, new BigDecimal("10.000000"),
                new BigDecimal("60.000"), 1_000L, "alice", 1L);
        repo.insertChannelConfig("ch-p2", 2, new BigDecimal("6.500000"),
                new BigDecimal("30.000"), 2_000L, "bob", 2L);
        // 同一渠道版本号唯一
        assertThrows(DuplicateKeyException.class, () -> repo.insertChannelConfig("ch-p2", 2,
                new BigDecimal("1"), new BigDecimal("1"), 3_000L, "carol", 3L));

        // 读数：同一渠道采样时刻唯一
        long r1 = repo.insertReading("ch-p2", 1_000L, new BigDecimal("0.000"), 1, "alice", 3L);
        long r2 = repo.insertReading("ch-p2", 1_500L, new BigDecimal("6.000"), 1, "alice", 4L);
        assertThrows(DuplicateKeyException.class,
                () -> repo.insertReading("ch-p2", 1_500L, new BigDecimal("7.000"), 1, "alice", 5L));

        // 异常：同一读数同一类型唯一
        long anomalyId = repo.insertAnomaly("ch-p2", r2, r1, "FLOW_EXCEEDED", 1,
                1_000L, 1_500L, 1_000L, new BigDecimal("0.000"), 1_500L, new BigDecimal("6.000"),
                new BigDecimal("43.200000"), new BigDecimal("10.000000"),
                new BigDecimal("33.200000"), "FLOW_M3_PER_H", "alice", 5L);
        assertThrows(DuplicateKeyException.class, () -> repo.insertAnomaly("ch-p2", r2, r1,
                "FLOW_EXCEEDED", 1, 1_000L, 1_500L, 1_000L, new BigDecimal("0.000"), 1_500L,
                new BigDecimal("6.000"), new BigDecimal("43.2"), new BigDecimal("10"),
                new BigDecimal("33.2"), "FLOW_M3_PER_H", "alice", 6L));
        assertEquals(1, repo.countOpenAnomalies("ch-p2"));

        // 解除：一条异常至多一条解除；状态只允许翻转一次
        long resolutionId = repo.insertResolution(anomalyId, "bob", "传感器故障已复核",
                1_500L, new BigDecimal("4.500"), 6L);
        assertTrue(repo.resolveAnomaly(anomalyId, resolutionId));
        assertThrows(DuplicateKeyException.class, () -> repo.insertResolution(anomalyId, "carol",
                "重复解除", 1_500L, new BigDecimal("4.500"), 7L));
        assertEquals(0, repo.countOpenAnomalies("ch-p2"));

        // 核销：writeoff_key 全局唯一，held_after 快照不回写
        long windowId = repo.insertWindow("wk-p2", "ch-p2", 1_000L, 9_000L,
                new BigDecimal("100.000"), 7L);
        repo.insertAllocation("ak-p2", windowId, "user-1", new BigDecimal("5.000"), "alice", 8L);
        AllocationRow allocation = repo.findAllocationByKey("ak-p2");
        repo.updateAllocationStatus(allocation.id(), "APPROVED", 9L);
        repo.insertWriteoff("wo-p2", windowId, "ch-p2", "ak-p2", new BigDecimal("1.500"),
                new BigDecimal("3.500"), "carol", 1_200L, 10L);
        repo.decrementHeldAmount(allocation.id(), new BigDecimal("1.500"), 10L);
        assertThrows(DuplicateKeyException.class, () -> repo.insertWriteoff("wo-p2", windowId,
                "ch-p2", "ak-p2", new BigDecimal("1.000"), new BigDecimal("2.500"), "carol",
                1_300L, 11L));

        // 全新连接与仓储实例读取：字段、精度与快照保持一致
        WaterRepository repo2 = new WaterRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
        ChannelConfigRow latest = repo2.findLatestChannelConfig("ch-p2");
        assertNotNull(latest);
        assertEquals(2, latest.version());
        assertEquals(new BigDecimal("6.500000"), latest.maxFlowRate());
        assertEquals(2, repo2.listChannelConfigs("ch-p2").size());
        ChannelConfigRow effective = repo2.findEffectiveChannelConfigAt("ch-p2", 1_500L);
        assertNotNull(effective);
        assertEquals(1, effective.version());
        assertEquals(configId, repo2.listChannelConfigs("ch-p2").get(0).id());

        ReadingRow latestReading = repo2.findLatestReading("ch-p2");
        assertNotNull(latestReading);
        assertEquals(r2, latestReading.id());
        assertEquals(new BigDecimal("6.000"), latestReading.cumulativeReading());
        assertEquals(2, repo2.listReadings("ch-p2").size());

        AnomalyRow anomaly = repo2.findAnomalyById(anomalyId);
        assertNotNull(anomaly);
        assertEquals("RESOLVED", anomaly.status());
        assertEquals(resolutionId, anomaly.resolutionId());
        assertEquals(new BigDecimal("43.200000"), anomaly.actualValue());
        assertEquals(new BigDecimal("33.200000"), anomaly.differenceValue());
        ResolutionRow resolution = repo2.findResolutionByAnomalyId(anomalyId);
        assertNotNull(resolution);
        assertEquals("bob", resolution.resolver());
        assertEquals(new BigDecimal("4.500"), resolution.replacementValue());

        WriteoffRow writeoff = repo2.findWriteoffByKey("wo-p2");
        assertNotNull(writeoff);
        assertEquals(new BigDecimal("1.500"), writeoff.amount());
        assertEquals(new BigDecimal("3.500"), writeoff.heldAfter());
        assertEquals(1, repo2.listWriteoffs(windowId).size());
        // 核销后持有额度确实被扣减，但流水快照保持 3.500 不被后续操作改写
        assertEquals(new BigDecimal("3.500"), repo2.findAllocationByKey("ak-p2").heldAmount());

        // 事务回滚：失败命令不留下任何半成品
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            long rolledBackReading = repo.insertReading("ch-p2", 2_000L, new BigDecimal("9.000"),
                    2, "alice", 12L);
            repo.insertAnomaly("ch-p2", rolledBackReading, null, "REGRESSION", 2, null, 2_000L,
                    null, null, 2_000L, new BigDecimal("9.000"), new BigDecimal("9.000"),
                    new BigDecimal("6.000"), new BigDecimal("3.000"), "CUMULATIVE_M3", "alice", 12L);
            throw new IllegalStateException("force rollback");
        }));
        assertEquals(2, repo2.listReadings("ch-p2").size());
        assertEquals(1, repo2.listAnomalies("ch-p2").size());
        assertNull(repo2.findReadingById(999L));
    }
}

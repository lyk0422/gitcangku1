package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.DrawdownPointInput;
import com.example.starter.water.WaterRepository.HourReservationRow;
import com.example.starter.water.WaterRepository.ReservationInput;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.WaterRepository.WriteoffRow;
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
        long windowId = repo1.insertWindow("wk-persist", "ch-persist", 1_000L, 3_600_000_000_002_000L,
                new BigDecimal("10.000"), 1L);
        repo1.insertAllocation("ak-source", windowId, "user-1", new BigDecimal("6.000"), "alice", 2L,
                1_000L, 3_600_000_000_001_000L);
        repo1.insertAllocation("ak-target", windowId, "user-2", new BigDecimal("2.500"), "bob", 3L,
                null, null);
        AllocationRow source = repo1.findAllocationByKey("ak-source");
        AllocationRow target = repo1.findAllocationByKey("ak-target");
        // 普通批准：源持有额度等于原申请水量；无区间申请批准不固化曲线版本
        repo1.updateAllocationStatus(source.id(), "APPROVED", 4L);
        assertNull(repo1.findAllocationByKey("ak-source").approvalCurveId());
        // 下泄曲线版本与逐小时预留快照：区间申请批准固化版本与每小时分布
        long curveId = repo1.insertCurve(windowId, 1, 4L);
        repo1.insertPoints(curveId, windowId, List.of(
                new DrawdownPointInput(1_000L, new BigDecimal("5.00")),
                new DrawdownPointInput(3_600_000_000_001_000L, new BigDecimal("5.00"))));
        repo1.approveAllocation(target.id(), curveId, 5L);
        repo1.insertReservations(target.id(), windowId, curveId, List.of(
                new ReservationInput(1_000L, new BigDecimal("2.50"))), 5L);
        // 转让 2.5：源持有 3.5、目标 APPROVED 持有 2.5、写不可变流水
        repo1.decrementHeldAmount(source.id(), new BigDecimal("2.500"), 5L);
        repo1.insertTransfer("tk-persist", windowId, "ak-source", "ak-target",
                new BigDecimal("2.500"), "alice", 5L);
        // 核销 1.0：累加同一小时核销计数并写不可变核销流水
        HourReservationRow targetReservation = repo1.listReservationsByAllocation(target.id()).get(0);
        repo1.insertWriteoff("wo-persist", target.id(), "ak-target", windowId, 1_000L,
                new BigDecimal("1.00"), "alice", 6L);
        repo1.addWrittenOff(targetReservation.id(), new BigDecimal("1.00"), 6L);
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

        // 批准固化的曲线版本与逐小时预留快照跨连接可读，后续曲线调整不回改历史
        assertEquals(curveId, target2.approvalCurveId());
        assertEquals(2, repo2.listPoints(curveId).size());
        List<HourReservationRow> reservations = repo2.listReservationsByAllocation(target2.id());
        assertEquals(1, reservations.size());
        assertEquals(1_000L, reservations.get(0).hourStartNanos());
        assertEquals(curveId, reservations.get(0).curveId());
        assertEquals(new BigDecimal("2.50"), reservations.get(0).reservedVolume());
        assertEquals(new BigDecimal("1.00"), reservations.get(0).writtenOffVolume());
        assertEquals(0, reservations.get(0).releasedVolume().compareTo(BigDecimal.ZERO));
        // 核销流水不可变，跨连接可读
        WriteoffRow writeoff = repo2.findWriteoffByKey("wo-persist");
        assertNotNull(writeoff);
        assertEquals("ak-target", writeoff.allocationKey());
        assertEquals(1_000L, writeoff.hourStartNanos());
        assertEquals(new BigDecimal("1.00"), writeoff.volume());
        // 逐小时聚合：固化预留 2.5、释放 0、核销 1.0；实际占用 = 2.5 - 0 = 2.5（含已核销）
        assertEquals(new BigDecimal("2.50"), repo2.aggregateHourUsage(windowId).get(0).reservedTotal());
        assertEquals(new BigDecimal("1.00"), repo2.aggregateHourUsage(windowId).get(0).writtenOffTotal());
        // 取消释放剩余预留 = 2.5 - 1.0 = 1.5，不能释放已核销部分
        repo2.releaseRemainingReservations(target2.id(), 8L);
        HourReservationRow released = repo2.listReservationsByAllocation(target2.id()).get(0);
        assertEquals(new BigDecimal("1.50"), released.releasedVolume());
        // 释放后实际占用 = 2.5 - 1.5 = 1.0（恰为已核销水量）
        assertEquals(new BigDecimal("1.50"), repo2.aggregateHourUsage(windowId).get(0).releasedTotal());

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
        repo.insertAllocation("ak-cancel", windowId, "user-1", new BigDecimal("3.000"), "alice", 2L,
                null, null);
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
}

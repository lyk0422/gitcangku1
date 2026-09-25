package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.ScheduleRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.UsageRow;
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
    void rotationSchedulesAndUsagesAreReadableFromNewConnection() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(8));
        SimpleDriverDataSource first = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = first.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo1 = new WaterRepository(new JdbcTemplate(first));
        long windowId = repo1.insertWindow("wk-sched", "ch-sched", 1_000L, 10_000L,
                new BigDecimal("10.000"), 1L);
        repo1.insertAllocation("ak-sched", windowId, "user-1", new BigDecimal("6.000"), "alice", 2L);
        AllocationRow allocation = repo1.findAllocationByKey("ak-sched");
        repo1.updateAllocationStatus(allocation.id(), "APPROVED", 3L);

        // 排班：固化渠道、窗口、申请、起止与申请时剩余水量快照 6
        long scheduleId = repo1.insertSchedule("sk-1", "ch-sched", windowId, "ak-sched",
                2_000L, 3_000L, new BigDecimal("6.000"), 4L);
        // 左闭右开：端点相接不重叠（在插入相邻时段前先验证）
        assertEquals(0, repo1.findOverlappingActiveSchedules("ch-sched", 3_000L, 3_600L).size());
        long adjacentId = repo1.insertSchedule("sk-2", "ch-sched", windowId, "ak-sched",
                3_000L, 3_600L, new BigDecimal("6.000"), 5L);
        // 同一申请同一窗口持有 2 个生效时段
        assertEquals(2, repo1.countActiveSchedules("ak-sched", windowId));
        // 区间重叠可查出冲突
        List<ScheduleRow> overlaps = repo1.findOverlappingActiveSchedules("ch-sched", 2_500L, 4_000L);
        assertEquals(2, overlaps.size());
        assertEquals(scheduleId, overlaps.get(0).id());

        // 核销 2.5 并累计写回；排班快照不变
        repo1.insertUsage("uk-1", "ak-sched", scheduleId, new BigDecimal("2.500"), 2_500L, 6L);
        repo1.addWrittenOffAmount(allocation.id(), new BigDecimal("2.500"), 6L);
        AllocationRow afterUsage = repo1.findAllocationByKey("ak-sched");
        assertEquals(new BigDecimal("2.500"), afterUsage.writtenOffAmount());
        assertEquals(new BigDecimal("3.500"),
                afterUsage.heldAmount().subtract(afterUsage.writtenOffAmount()));
        assertEquals(new BigDecimal("6.000"), repo1.findScheduleById(scheduleId).remainingSnapshot());
        UsageRow usage = repo1.findUsageByKey("uk-1");
        assertNotNull(usage);
        assertEquals(scheduleId, usage.scheduleId());
        assertEquals(new BigDecimal("2.500"), usage.volume());

        // 取消一个时段立即释放重叠占用，历史记录保留
        repo1.cancelSchedule(scheduleId, 7L);
        assertEquals(1, repo1.countActiveSchedules("ak-sched", windowId));
        assertEquals(0, repo1.findOverlappingActiveSchedules("ch-sched", 2_500L, 2_900L).size());
        assertEquals(2, repo1.listSchedulesByChannel("ch-sched").size());
        assertEquals(1, repo1.listActiveSchedulesByChannel("ch-sched").size());
        assertEquals("CANCELLED", repo1.findScheduleById(scheduleId).status());
        assertEquals(Long.valueOf(7L), repo1.findScheduleById(scheduleId).cancelledNanos());
        // 相邻时段仍生效
        assertEquals("ACTIVE", repo1.findScheduleById(adjacentId).status());

        // 全新连接重读，同一 JVM 内数据保持
        WaterRepository repo2 = new WaterRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
        assertEquals(2, repo2.listSchedulesByAllocation("ak-sched").size());
        assertEquals(new BigDecimal("6.000"), repo2.findScheduleById(scheduleId).remainingSnapshot());
        assertEquals(new BigDecimal("2.500"), repo2.findUsageByKey("uk-1").volume());
        assertEquals(new BigDecimal("2.500"), repo2.findAllocationByKey("ak-sched").writtenOffAmount());
    }
}

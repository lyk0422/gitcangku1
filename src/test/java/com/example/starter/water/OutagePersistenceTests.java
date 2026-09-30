package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.OutageRow;
import com.example.starter.water.WaterRepository.PostponementRow;
import com.example.starter.water.WaterRepository.WaterUsageRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灌渠中断/顺延/核销的真实 H2 持久化测试（MODE=MySQL）：建表、唯一约束、交叉 SQL、
 * 顺延更新与核销快照均在独立命名内存库内验证，不 mock 数据库边界。
 */
class OutagePersistenceTests {

    private static final String URL_TEMPLATE =
            "jdbc:h2:mem:outage-%s;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private static long nanos(String utc) {
        return WaterService.toNanos(Instant.parse(utc));
    }

    private WaterRepository newInitializedRepo(String url) throws Exception {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        return new WaterRepository(new JdbcTemplate(dataSource));
    }

    private long setupChannelWindowAndSchedules(WaterRepository repo, String channel) {
        long windowId = repo.insertWindow("wk-" + channel, channel,
                nanos("2025-10-01T10:00:00Z"), nanos("2025-10-01T12:00:00Z"),
                new BigDecimal("100"), 1L);
        repo.insertChannelIfAbsent(channel, 1L);
        // 10:30-10:45 与 10:45-11:00 两条相邻排班
        repo.insertAllocation("ak-1-" + channel, windowId, "u1", new BigDecimal("10"), "alice",
                nanos("2025-10-01T10:30:00Z"), nanos("2025-10-01T10:45:00Z"), 2L);
        repo.insertAllocation("ak-2-" + channel, windowId, "u2", new BigDecimal("10"), "bob",
                nanos("2025-10-01T10:45:00Z"), nanos("2025-10-01T11:00:00Z"), 3L);
        return windowId;
    }

    @Test
    void crossingSqlUsesHalfOpenIntervalsAndAdjacentIsNotCrossing() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        WaterRepository repo = newInitializedRepo(url);
        String channel = "ch-sql";
        setupChannelWindowAndSchedules(repo, channel);

        // 中断 10:00-11:00：两条排班都与其交叉（10:45<11:00）
        assertEquals(2, repo.listCrossingActiveAllocations(channel,
                nanos("2025-10-01T10:00:00Z"), nanos("2025-10-01T11:00:00Z")).size());
        // 中断 10:00-10:45（右端点等于第二条排班起点）：只命中第一条
        assertEquals(1, repo.listCrossingActiveAllocations(channel,
                nanos("2025-10-01T10:00:00Z"), nanos("2025-10-01T10:45:00Z")).size());
        // 中断 11:00-12:00 与两条排班相邻/分离，不命中
        assertEquals(0, repo.listCrossingActiveAllocations(channel,
                nanos("2025-10-01T11:00:00Z"), nanos("2025-10-01T12:00:00Z")).size());
        // 中断左端点等于第一条排班起点仍交叉（左闭）
        assertEquals(1, repo.listCrossingActiveAllocations(channel,
                nanos("2025-10-01T10:30:00Z"), nanos("2025-10-01T10:45:00Z")).size());

        // 相邻占用判定：空闲且首尾相接的区间不算重叠
        assertFalse(repo.existsOverlappingActiveSchedule(channel,
                nanos("2025-10-01T10:15:00Z"), nanos("2025-10-01T10:30:00Z"), "ak-new"));
        assertFalse(repo.existsOverlappingActiveSchedule(channel,
                nanos("2025-10-01T11:00:00Z"), nanos("2025-10-01T11:15:00Z"), "ak-new"));
        // 压进一分钟即重叠
        assertTrue(repo.existsOverlappingActiveSchedule(channel,
                nanos("2025-10-01T10:44:00Z"), nanos("2025-10-01T11:00:00Z"), "ak-new"));
        // 排除自身后自身时段不重叠
        assertFalse(repo.existsOverlappingActiveSchedule(channel,
                nanos("2025-10-01T10:30:00Z"), nanos("2025-10-01T10:45:00Z"), "ak-1-" + channel));
    }

    @Test
    void outageRequestAndPostponementUniqueConstraintsEnforced() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        WaterRepository repo = newInitializedRepo(url);
        String channel = "ch-uk";
        setupChannelWindowAndSchedules(repo, channel);
        repo.insertOutage("req-1", channel, "中断",
                nanos("2025-10-01T10:00:00Z"), nanos("2025-10-01T11:00:00Z"), 60, 9L);
        // 同 requestId 重复插入 -> 唯一约束
        assertThrows(DuplicateKeyException.class, () -> repo.insertOutage("req-1", channel, "另一个",
                nanos("2025-10-01T10:00:00Z"), nanos("2025-10-01T11:00:00Z"), 30, 9L));

        AllocationRow first = repo.findAllocationByKey("ak-1-" + channel);
        repo.insertPostponement("req-1", channel, first.allocationKey(),
                first.scheduledStartNanos(), first.scheduledEndNanos(),
                nanos("2025-10-01T11:30:00Z"), nanos("2025-10-01T11:45:00Z"), 60, "中断", 9L);
        // 同中断同排班重复顺延关系 -> 唯一约束
        assertThrows(DuplicateKeyException.class, () -> repo.insertPostponement("req-1", channel,
                first.allocationKey(), first.scheduledStartNanos(), first.scheduledEndNanos(),
                nanos("2025-10-01T11:30:00Z"), nanos("2025-10-01T11:45:00Z"), 60, "中断", 9L));

        // 更新排班当前窗口并以新连接验证持久化
        repo.updateAllocationSchedule(first.id(),
                nanos("2025-10-01T11:30:00Z"), nanos("2025-10-01T11:45:00Z"), 10L);
        WaterRepository repo2 = new WaterRepository(new JdbcTemplate(
                new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
        AllocationRow moved = repo2.findAllocationByKey("ak-1-" + channel);
        assertEquals(nanos("2025-10-01T11:30:00Z"), moved.scheduledStartNanos());
        assertEquals(nanos("2025-10-01T11:45:00Z"), moved.scheduledEndNanos());
        OutageRow outage = repo2.findOutageByRequestId("req-1");
        assertNotNull(outage);
        assertEquals(60, outage.postponeMinutes());
        assertEquals("REGISTERED", outage.status());
        List<PostponementRow> postponements = repo2.listPostponementsByOutage("req-1");
        assertEquals(1, postponements.size());
        assertEquals("中断", postponements.get(0).reason());
        assertEquals(1, repo2.listOutagesByChannel(channel).size());
    }

    @Test
    void waterUsageSnapshotsScheduleAndSumsSettledVolume() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        WaterRepository repo = newInitializedRepo(url);
        String channel = "ch-usage";
        long windowId = setupChannelWindowAndSchedules(repo, channel);
        AllocationRow allocation = repo.findAllocationByKey("ak-1-" + channel);
        repo.updateAllocationStatus(allocation.id(), "APPROVED", 4L);
        long originalStart = allocation.scheduledStartNanos();
        long originalEnd = allocation.scheduledEndNanos();

        // 核销当时：快照原排班 10:30-10:45
        repo.insertWaterUsage("uk-1", allocation.allocationKey(), windowId, new BigDecimal("4.000"),
                originalStart, originalEnd, "alice", 5L);
        // 之后中断顺延排班当前窗口到 11:30-11:45
        repo.updateAllocationSchedule(allocation.id(),
                nanos("2025-10-01T11:30:00Z"), nanos("2025-10-01T11:45:00Z"), 6L);

        WaterRepository repo2 = new WaterRepository(new JdbcTemplate(
                new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
        List<WaterUsageRow> usages = repo2.listWaterUsagesByAllocation(allocation.allocationKey());
        assertEquals(1, usages.size());
        WaterUsageRow snapshot = usages.get(0);
        // 已完成核销仍引用当时排班，不被顺延改写
        assertEquals(originalStart, snapshot.scheduleStartNanos());
        assertEquals(originalEnd, snapshot.scheduleEndNanos());
        assertEquals(new BigDecimal("4.000"), snapshot.volume());
        assertEquals(new BigDecimal("4.000"), repo2.sumSettledVolume(allocation.allocationKey()));
        // usageKey 唯一
        assertThrows(DuplicateKeyException.class, () -> repo2.insertWaterUsage("uk-1",
                allocation.allocationKey(), windowId, new BigDecimal("1"), originalStart, originalEnd,
                "alice", 7L));
        // 申请当前窗口已是新窗口
        assertEquals(nanos("2025-10-01T11:30:00Z"),
                repo2.findAllocationByKey(allocation.allocationKey()).scheduledStartNanos());
    }
}

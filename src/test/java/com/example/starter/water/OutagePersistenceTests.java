package com.example.starter.water;

import com.example.starter.water.WaterRepository.OutageRow;
import com.example.starter.water.WaterRepository.PostponementRow;
import com.example.starter.water.WaterRepository.ScheduleRow;
import com.example.starter.water.WaterRepository.SettlementRow;
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
 * 灌渠中断/排班/顺延/核销持久化验证：同一 JVM 内使用独立命名 H2 内存库（MODE=MySQL），
 * 建表写入后以全新连接与仓储实例重读，验证顺延对应关系、核销快照与唯一约束。
 * 不要求跨进程重启恢复。
 */
class OutagePersistenceTests {

    private static final String URL_TEMPLATE =
            "jdbc:h2:mem:outage-%s;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private WaterRepository newRepo(String url) {
        return new WaterRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
    }

    @Test
    void postponementChainAndSettlementSnapshotAreReadableFromNewConnection() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(8));
        SimpleDriverDataSource first = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = first.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo1 = newRepo(url);
        long windowId = repo1.insertWindow("wk-outage", "ch-outage", 1_000L, 10_000L,
                new BigDecimal("100"), 1L);
        long scheduleId = repo1.insertSchedule("sk-1", windowId, "ch-outage",
                3_000L, 3_600L, 2L);
        long outageId = repo1.insertOutage("ok-1", "ch-outage", 3_300L, 4_000L,
                "canal outage", 60, 3L);
        // 首次顺延 10:30 基准 -> +60 分钟（这里以纳秒桩值表达）
        repo1.moveSchedule(scheduleId, 6_600L, 7_200L, 4L);
        repo1.insertPostponement(scheduleId, outageId, "ok-1", "canal outage", 60,
                3_000L, 3_600L, 3_000L, 3_600L, 6_600L, 7_200L, 4L);
        // 核销当时窗口快照
        repo1.insertSettlement("stk-1", scheduleId, "sk-1", 6_600L, 7_200L,
                new BigDecimal("7.250"), 5L);
        repo1.markScheduleCompleted(scheduleId, 5L);

        // 全新连接重读
        WaterRepository repo2 = newRepo(url);
        ScheduleRow schedule = repo2.findScheduleByKey("sk-1");
        assertNotNull(schedule);
        assertEquals("COMPLETED", schedule.status());
        assertEquals(3_000L, schedule.originalStartNanos());
        assertEquals(3_600L, schedule.originalEndNanos());
        assertEquals(6_600L, schedule.startNanos());
        assertEquals(7_200L, schedule.endNanos());

        OutageRow outage = repo2.findOutageByKey("ok-1");
        assertEquals("ch-outage", outage.channelId());
        assertEquals(60, outage.postponeMinutes());

        List<PostponementRow> chain = repo2.listPostponements(scheduleId);
        assertEquals(1, chain.size());
        PostponementRow p = chain.get(0);
        assertEquals("ok-1", p.outageKey());
        assertEquals("canal outage", p.reason());
        assertEquals(3_000L, p.fromStartNanos());
        assertEquals(6_600L, p.toStartNanos());
        assertEquals(1, repo2.listPostponementsByOutage(outageId).size());

        SettlementRow settlement = repo2.findSettlementByKey("stk-1");
        assertNotNull(settlement);
        assertEquals(6_600L, settlement.scheduledStartNanos());
        assertEquals(7_200L, settlement.scheduledEndNanos());
        assertEquals(new BigDecimal("7.250"), settlement.volume());
        // 排班后续若再移动，核销快照不变（直接改库验证快照字段不被级联）
        repo2.moveSchedule(scheduleId, 8_000L, 8_600L, 6L);
        assertEquals(6_600L, repo2.findSettlementByKey("stk-1").scheduledStartNanos());
    }

    @Test
    void uniqueConstraintsAndChannelMutexAreEnforcedOnH2() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(8));
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo = new WaterRepository(new JdbcTemplate(dataSource));
        long windowId = repo.insertWindow("wk-uk", "ch-uk", 1L, 9L, new BigDecimal("1"), 1L);
        repo.insertSchedule("sk-dup", windowId, "ch-uk", 2L, 3L, 2L);
        assertThrows(DuplicateKeyException.class,
                () -> repo.insertSchedule("sk-dup", windowId, "ch-uk", 4L, 5L, 3L));
        repo.insertOutage("ok-dup", "ch-uk", 2L, 6L, "r", 10, 4L);
        assertThrows(DuplicateKeyException.class,
                () -> repo.insertOutage("ok-dup", "ch-uk", 2L, 6L, "r", 10, 5L));

        // 渠道互斥锁行幂等：重复 lockChannel 不报错（同一连接内 FOR UPDATE 可重入）
        repo.lockChannel("ch-uk", 7L);
        repo.lockChannel("ch-uk", 8L);
        Integer mutexCount = new JdbcTemplate(dataSource)
                .queryForObject("SELECT COUNT(*) FROM channel_mutex WHERE channel_id = 'ch-uk'", Integer.class);
        assertTrue(mutexCount != null && mutexCount == 1);

        // 左闭右开交叉查询：相邻不交叉
        List<ScheduleRow> overlapping = repo.listOverlappingSchedules("ch-uk", 3L, 4L);
        assertTrue(overlapping.isEmpty(), "端点相邻不应判为交叉");
        List<ScheduleRow> crossing = repo.listOverlappingSchedules("ch-uk", 2L, 3L);
        // [2,3) 与排班 [2,3) 完全重合，必然交叉
        assertEquals(1, crossing.size());
    }
}

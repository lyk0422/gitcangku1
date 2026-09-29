package com.example.starter.water;

import com.example.starter.water.dto.Dtos.RainRecordResponse;
import com.example.starter.water.dto.Dtos.WriteoffItemRequest;
import com.example.starter.water.dto.Dtos.WriteoffResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 可控时钟测试：注入固定时钟验证登记/确认/生效/核销的 UTC 时间证据精确取自时钟，
 * 以及成功重放返回首次响应快照（时钟推进不改变快照）。使用真实 H2 内存库与真实事务。
 */
class ClockControlledRainTests {

    private SimpleDriverDataSource dataSource;
    private WaterService service;
    private final AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-10-01T00:00:00Z"));

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:clock-" + UUID.randomUUID().toString().substring(0, 8)
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        service = new WaterService(new WaterRepository(jdbc), txManager, new ObjectMapper(), clock::get);
    }

    @Test
    void timestampsFollowControlledClockAndReplayKeepsFirstSnapshot() {
        clock.set(Instant.parse("2026-10-01T00:00:00Z"));
        long windowId = service.createWindow("wc", "wk-clock", "ch-clock",
                "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z", "10").id();

        clock.set(Instant.parse("2026-10-01T01:00:00Z"));
        var allocation = service.submitAllocation("ac", "ak-clock", windowId, "user-1", "5", "0.2", "alice");
        assertEquals("2026-10-01T01:00:00Z", allocation.createdUtc());
        service.approveAllocation("ap", "ak-clock");

        // 登记时刻
        clock.set(Instant.parse("2026-10-01T02:00:00Z"));
        RainRecordResponse registered = service.registerRain("rrc", "rk-clock", windowId,
                "10", "2026-10-01T02:30:00Z", "clerk");
        assertEquals("2026-10-01T02:00:00Z", registered.createdUtc());
        assertEquals("2026-10-01T02:30:00Z", registered.observedUtc());
        assertNull(registered.effectiveUtc());

        // 第一名确认
        clock.set(Instant.parse("2026-10-01T03:00:00Z"));
        RainRecordResponse firstConfirm = service.confirmRain("cf1", "rk-clock", "officer-a");
        assertEquals("2026-10-01T03:00:00Z", firstConfirm.confirmations().get(0).confirmedUtc());
        assertNull(firstConfirm.effectiveUtc());

        // 第二名确认：记录生效，生效时间取当时时钟
        clock.set(Instant.parse("2026-10-01T04:00:00Z"));
        RainRecordResponse effective = service.confirmRain("cf2", "rk-clock", "officer-b");
        assertEquals("EFFECTIVE", effective.status());
        assertEquals("2026-10-01T04:00:00Z", effective.effectiveUtc());

        // 核销时间取当时时钟：10mm * 0.2 = 2 抵扣，核销 5 -> 配额扣 3
        clock.set(Instant.parse("2026-10-01T05:00:00Z"));
        WriteoffItemRequest item = new WriteoffItemRequest("ak-clock", "5");
        WriteoffResponse first = service.writeoffBatch("woc", "wo-clock", windowId, List.of(item), "alice");
        assertEquals("2026-10-01T05:00:00Z", first.createdUtc());
        assertEquals("2", first.totalCredit());
        assertEquals("3", first.totalQuotaDeduction());

        // 时钟推进后同键同参重放：返回首次完整响应快照，时间证据不被改写
        clock.set(Instant.parse("2026-12-31T23:59:59Z"));
        WriteoffResponse replay = service.writeoffBatch("woc", "wo-clock", windowId, List.of(item), "alice");
        assertEquals(first, replay);
        assertEquals("2026-10-01T05:00:00Z", replay.createdUtc());

        // 读取（查询）不改变状态：再查仍是 5:00 快照，持有额度停在 2
        WriteoffResponse queried = service.getWriteoff("wo-clock");
        assertEquals(first, queried);
        assertEquals("2", service.getHistory(windowId).allocations().get(0).heldAmount());
    }
}

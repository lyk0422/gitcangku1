package com.example.starter.maintenance;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 日历周期保养测试：可控时钟驱动的到期边界、时间门禁、幂等重放与并发（真实 H2 内存库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EquipmentCalendarTests {

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM equipment");
        clock.setInstant(Instant.parse("2026-01-01T00:00:00Z"));
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private void register(String equipmentId, long periodMinutes) throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":%d}
                                """.formatted(equipmentId, equipmentId, periodMinutes)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
    }

    private void enableCalendar(String equipmentId, String requestId, long expectedVersion,
                                String commissionedAt, int periodDays, int expectedStatus)
            throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/calendar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","operator":"op-1","expectedVersion":%d,
                                 "commissionedAt":"%s","periodDays":%d}
                                """.formatted(requestId, expectedVersion, commissionedAt, periodDays)))
                .andExpect(status().is(expectedStatus));
    }

    private void addReading(String equipmentId, String requestId, long expectedVersion,
                            String readingId, String sampledAt, long cumulativeMinutes,
                            int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                cumulativeMinutes)))
                .andExpect(status().is(expectedStatus));
    }

    // ---------- 合成场景：1 日周期、投用 1 日 00:00、无运行量 ----------

    @Test
    void calendarCycle_dueTransitionsAndMaintenanceFreeze() throws Exception {
        register("eq-cal", 100000);
        enableCalendar("eq-cal", "cal-1", 1, "2026-01-01T00:00:00Z", 1, 201);

        // 1 日 23:59:59 尚 OK；截止为 2 日 00:00
        clock.setInstant(Instant.parse("2026-01-01T23:59:59Z"));
        mockMvc.perform(get("/api/equipment/eq-cal/status"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.commissionedAt").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.calendarPeriodDays").value(1))
                .andExpect(jsonPath("$.calendarDeadline").value("2026-01-02T00:00:00Z"))
                .andExpect(jsonPath("$.workHoursDue").value(false))
                .andExpect(jsonPath("$.calendarDue").value(false))
                .andExpect(jsonPath("$.status").value("OK"));

        // 2 日 00:00 达到截止即 DUE（仅日历到期，工时远未达周期）
        clock.setInstant(Instant.parse("2026-01-02T00:00:00Z"));
        mockMvc.perform(get("/api/equipment/eq-cal/status"))
                .andExpect(jsonPath("$.workHoursDue").value(false))
                .andExpect(jsonPath("$.calendarDue").value(true))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 按 2 日 00:00 读数保养：仅日历到期也允许保养，不要求先补够工时
        addReading("eq-cal", "cal-r1", 2, "r1", "2026-01-02T00:00:00Z", 0, 201);
        mockMvc.perform(post("/api/equipment/eq-cal/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"cal-mnt-1","expectedVersion":3,"readingId":"r1",
                                 "anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workHoursDue").value(false))
                .andExpect(jsonPath("$.calendarDue").value(true))
                .andExpect(jsonPath("$.newCalendarDeadline").value("2026-01-03T00:00:00Z"))
                .andExpect(jsonPath("$.equipmentVersion").value(4));

        // 保养后截止顺延到 3 日 00:00，状态回到 OK
        mockMvc.perform(get("/api/equipment/eq-cal/status"))
                .andExpect(jsonPath("$.calendarDeadline").value("2026-01-03T00:00:00Z"))
                .andExpect(jsonPath("$.calendarDue").value(false))
                .andExpect(jsonPath("$.status").value("OK"));

        // 时钟推进到 4 日：再次日历到期
        clock.setInstant(Instant.parse("2026-01-04T00:00:00Z"));
        mockMvc.perform(get("/api/equipment/eq-cal/status"))
                .andExpect(jsonPath("$.calendarDue").value(true))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 同键重放：在未来时间仍返回首次截止而不延长，业务效果不重复
        mockMvc.perform(post("/api/equipment/eq-cal/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"cal-mnt-1","expectedVersion":3,"readingId":"r1",
                                 "anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.newCalendarDeadline").value("2026-01-03T00:00:00Z"))
                .andExpect(jsonPath("$.equipmentVersion").value(4));
        mockMvc.perform(get("/api/equipment/eq-cal/maintenances"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-cal/status"))
                .andExpect(jsonPath("$.version").value(4));
    }

    // ---------- 日历启用：参数校验与冲突 ----------

    @Test
    void enableCalendar_validationAndConflicts() throws Exception {
        register("eq-cal-v", 1000);

        // 周期天必须 1 至 365
        enableCalendar("eq-cal-v", "cv-1", 1, "2026-01-01T00:00:00Z", 0, 400);
        enableCalendar("eq-cal-v", "cv-2", 1, "2026-01-01T00:00:00Z", 366, 400);
        // 操作者非空
        mockMvc.perform(post("/api/equipment/eq-cal-v/calendar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"cv-3","operator":"","expectedVersion":1,
                                 "commissionedAt":"2026-01-01T00:00:00Z","periodDays":30}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // requestId 必填
        mockMvc.perform(post("/api/equipment/eq-cal-v/calendar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"op-1","expectedVersion":1,
                                 "commissionedAt":"2026-01-01T00:00:00Z","periodDays":30}
                                """))
                .andExpect(status().isBadRequest());
        // 设备不存在 → 404
        enableCalendar("nope", "cv-4", 1, "2026-01-01T00:00:00Z", 30, 404);
        // 版本冲突 → 409，且失败不占键、不改版本
        enableCalendar("eq-cal-v", "cv-5", 99, "2026-01-01T00:00:00Z", 30, 409);
        mockMvc.perform(get("/api/equipment/eq-cal-v/status"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.calendarDeadline").doesNotExist())
                .andExpect(jsonPath("$.calendarDue").value(false))
                .andExpect(jsonPath("$.status").value("OK"));

        // 首次启用成功：版本加一
        enableCalendar("eq-cal-v", "cv-6", 1, "2026-01-01T00:00:00Z", 30, 201);
        mockMvc.perform(get("/api/equipment/eq-cal-v/status"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.calendarDeadline").value("2026-01-31T00:00:00Z"));
        // 重复启用 → 409（启用后不可改）
        mockMvc.perform(post("/api/equipment/eq-cal-v/calendar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"cv-7","operator":"op-1","expectedVersion":2,
                                 "commissionedAt":"2026-02-01T00:00:00Z","periodDays":60}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CALENDAR_ALREADY_ENABLED"));
    }

    // ---------- 日历启用：幂等 ----------

    @Test
    void enableCalendar_idempotency() throws Exception {
        register("eq-cal-i", 1000);
        String body = """
                {"requestId":"ci-1","operator":"op-1","expectedVersion":1,
                 "commissionedAt":"2026-01-01T00:00:00Z","periodDays":30}
                """;
        MvcResult first = postJson("/api/equipment/eq-cal-i/calendar", body);
        assertEquals(201, first.getResponse().getStatus());

        // 同键同参：重放首次完整响应，版本不再前进
        MvcResult replay = postJson("/api/equipment/eq-cal-i/calendar", body);
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-cal-i/status"))
                .andExpect(jsonPath("$.version").value(2));

        // 同键异参 → 409
        mockMvc.perform(post("/api/equipment/eq-cal-i/calendar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ci-1","operator":"op-1","expectedVersion":1,
                                 "commissionedAt":"2026-01-01T00:00:00Z","periodDays":31}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：版本冲突失败后同 requestId 修正参数可成功（独立设备）
        register("eq-cal-i2", 1000);
        enableCalendar("eq-cal-i2", "ci-2", 99, "2026-01-01T00:00:00Z", 30, 409);
        enableCalendar("eq-cal-i2", "ci-2", 1, "2026-01-01T00:00:00Z", 30, 201);
    }

    // ---------- 保养锚点时间门禁 ----------

    @Test
    void maintenance_anchorTimeGates() throws Exception {
        clock.setInstant(Instant.parse("2026-01-10T00:00:00Z"));
        register("eq-gate", 100000);
        enableCalendar("eq-gate", "g-cal", 1, "2026-01-01T00:00:00Z", 30, 201);
        addReading("eq-gate", "g-r1", 2, "r-old", "2025-12-31T23:00:00Z", 0, 201);
        addReading("eq-gate", "g-r2", 3, "r-ok", "2026-01-09T00:00:00Z", 0, 201);
        addReading("eq-gate", "g-r3", 4, "r-future", "2026-01-11T00:00:00Z", 0, 201);

        // 锚点早于投用时刻 → 422
        mockMvc.perform(post("/api/equipment/eq-gate/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"g-m1","expectedVersion":5,"readingId":"r-old",
                                 "anchorRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ANCHOR_BEFORE_COMMISSIONING"));
        // 锚点晚于服务端当前时间 → 422
        mockMvc.perform(post("/api/equipment/eq-gate/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"g-m2","expectedVersion":5,"readingId":"r-future",
                                 "anchorRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ANCHOR_TIME_IN_FUTURE"));
        // 合法锚点（投用之后、现在之前）→ 201，冻结原因与新截止
        mockMvc.perform(post("/api/equipment/eq-gate/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"g-m3","expectedVersion":5,"readingId":"r-ok",
                                 "anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workHoursDue").value(false))
                .andExpect(jsonPath("$.calendarDue").value(false))
                .andExpect(jsonPath("$.newCalendarDeadline").value("2026-02-08T00:00:00Z"));

        // 未启用日历设备同样执行"不得晚于现在"门禁
        register("eq-gate2", 100000);
        addReading("eq-gate2", "g2-r1", 1, "rf", "2026-01-11T00:00:00Z", 0, 201);
        mockMvc.perform(post("/api/equipment/eq-gate2/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"g2-m1","expectedVersion":2,"readingId":"rf",
                                 "anchorRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ANCHOR_TIME_IN_FUTURE"));
        // 未启用设备只看工时：无日历字段，冻结的日历原因为 false、新截止为 null
        addReading("eq-gate2", "g2-r2", 2, "rp", "2026-01-09T00:00:00Z", 0, 201);
        mockMvc.perform(post("/api/equipment/eq-gate2/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"g2-m2","expectedVersion":3,"readingId":"rp",
                                 "anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.calendarDue").value(false))
                .andExpect(jsonPath("$.newCalendarDeadline").doesNotExist());
        mockMvc.perform(get("/api/equipment/eq-gate2/status"))
                .andExpect(jsonPath("$.calendarDeadline").doesNotExist())
                .andExpect(jsonPath("$.calendarDue").value(false));
    }

    // ---------- 补录读数不替换真正最近保养锚点 ----------

    @Test
    void backfillReading_doesNotReplaceLastMaintenanceAnchor() throws Exception {
        clock.setInstant(Instant.parse("2026-01-02T00:00:00Z"));
        register("eq-bf", 100000);
        enableCalendar("eq-bf", "bf-cal", 1, "2026-01-01T00:00:00Z", 1, 201);
        addReading("eq-bf", "bf-r1", 2, "r1", "2026-01-02T00:00:00Z", 0, 201);
        mockMvc.perform(post("/api/equipment/eq-bf/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"bf-m1","expectedVersion":3,"readingId":"r1",
                                 "anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());

        // 补录一条更接近日历截止（3 日 00:00）的历史读数：邻居单调允许
        addReading("eq-bf", "bf-r2", 4, "r2", "2026-01-02T23:00:00Z", 0, 201);

        // 最近保养锚点与日历截止不因补录而改变
        mockMvc.perform(get("/api/equipment/eq-bf/status"))
                .andExpect(jsonPath("$.lastMaintenanceAnchorSampledAt").value("2026-01-02T00:00:00Z"))
                .andExpect(jsonPath("$.calendarDeadline").value("2026-01-03T00:00:00Z"))
                .andExpect(jsonPath("$.status").value("OK"));
        mockMvc.perform(get("/api/equipment/eq-bf/maintenances"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].readingId").value("r1"));
    }

    // ---------- 并发：日历启用与读数修订共享版本，恰有一个成功 ----------

    @Test
    void concurrentEnableCalendarVsRevise_oneSucceeds() throws Exception {
        register("eq-race-cal", 1000);
        addReading("eq-race-cal", "rc-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> enableFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-race-cal/calendar", """
                    {"requestId":"rc-cal","operator":"op-1","expectedVersion":2,
                     "commissionedAt":"2026-01-01T00:00:00Z","periodDays":30}
                    """);
        });
        Future<MvcResult> reviseFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-race-cal/readings/r1/revisions", """
                    {"requestId":"rc-revise","expectedVersion":2,"cumulativeMinutes":150}
                    """);
        });
        gate.countDown();

        int enableStatus = enableFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int reviseStatus = reviseFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        assertTrue(
                (enableStatus == 201 && reviseStatus == 409)
                        || (enableStatus == 409 && reviseStatus == 201),
                "日历启用与修订并发应恰有一个成功，实际：enable=" + enableStatus
                        + ", revise=" + reviseStatus);

        // 版本只前进一次
        mockMvc.perform(get("/api/equipment/eq-race-cal/status"))
                .andExpect(jsonPath("$.version").value(3));
        // 幂等键各占一个，无唯一键异常泄漏
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id IN ('rc-cal','rc-revise')",
                Integer.class);
        assertEquals(1, idemCount, "失败方不占幂等键，仅成功方占键");
    }
}

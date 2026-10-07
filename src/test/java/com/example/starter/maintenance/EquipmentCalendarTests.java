package com.example.starter.maintenance;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import com.jayway.jsonpath.JsonPath;

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
 * 日历周期保养测试：可控时钟下的合成场景、时间门禁、幂等重放与状态判定（真实 H2 内存库）。
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

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM equipment_calendar");
        jdbc.update("DELETE FROM equipment");
        clock.setInstant(Instant.parse("2026-01-01T00:00:00Z"));
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
                .andExpect(status().isCreated());
    }

    private MvcResult enableCalendar(String equipmentId, String requestId, long expectedVersion,
                                     String operator, String commissionedAt,
                                     int periodDays) throws Exception {
        return postJson("/api/equipment/" + equipmentId + "/calendar", """
                {"requestId":"%s","expectedVersion":%d,"operator":"%s",
                 "commissionedAt":"%s","periodDays":%d}
                """.formatted(requestId, expectedVersion, operator, commissionedAt, periodDays));
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

    // ---------- 合成场景：1 日周期、投用 UTC 1 日 00:00、无运行量 ----------

    @Test
    void synthetic_oneDayPeriod_dueAtDeadlineAndFrozenReplay() throws Exception {
        register("eq-cal", 100000);
        MvcResult enable = enableCalendar("eq-cal", "cal-1", 1, "op-zhang",
                "2026-01-01T00:00:00Z", 1);
        assertEquals(201, enable.getResponse().getStatus());
        String enableBody = enable.getResponse().getContentAsString();
        assertEquals(2, (int) JsonPath.read(enableBody, "$.equipmentVersion"));
        assertEquals("op-zhang", JsonPath.read(enableBody, "$.operator"));

        // 截止前 1 秒（1 日 23:59:59）：仍 OK
        clock.setInstant(Instant.parse("2026-01-01T23:59:59Z"));
        mockMvc.perform(get("/api/equipment/eq-cal/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calendarEnabled").value(true))
                .andExpect(jsonPath("$.calendarPeriodDays").value(1))
                .andExpect(jsonPath("$.commissionedAt").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.calendarDeadline").value("2026-01-02T00:00:00Z"))
                .andExpect(jsonPath("$.hoursDue").value(false))
                .andExpect(jsonPath("$.calendarDue").value(false))
                .andExpect(jsonPath("$.status").value("OK"));

        // 到达截止（2 日 00:00）：无运行量也日历到期，与工时原因取或 → DUE
        clock.setInstant(Instant.parse("2026-01-02T00:00:00Z"));
        mockMvc.perform(get("/api/equipment/eq-cal/status"))
                .andExpect(jsonPath("$.hoursDue").value(false))
                .andExpect(jsonPath("$.calendarDue").value(true))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 按 2 日 00:00 读数保养：累计工时 0（仅日历到期也允许保养，不要求补够工时）
        addReading("eq-cal", "cal-r1", 2, "r1", "2026-01-02T00:00:00Z", 0, 201);
        MvcResult maintenance = postJson("/api/equipment/eq-cal/maintenances", """
                {"requestId":"cal-mnt-1","expectedVersion":3,"readingId":"r1","anchorRevisionNo":1}
                """);
        assertEquals(201, maintenance.getResponse().getStatus());
        String maintenanceBody = maintenance.getResponse().getContentAsString();
        Boolean frozenHoursDue = JsonPath.read(maintenanceBody, "$.hoursDue");
        Boolean frozenCalendarDue = JsonPath.read(maintenanceBody, "$.calendarDue");
        assertFalse(frozenHoursDue);
        assertTrue(frozenCalendarDue);
        assertEquals("2026-01-02T00:00:00Z", JsonPath.read(maintenanceBody, "$.anchorSampledAt"));
        assertEquals("2026-01-03T00:00:00Z", JsonPath.read(maintenanceBody, "$.newCalendarDeadline"));

        // 保养后新截止 3 日 00:00，当前 2 日 00:00 → OK
        mockMvc.perform(get("/api/equipment/eq-cal/status"))
                .andExpect(jsonPath("$.calendarDeadline").value("2026-01-03T00:00:00Z"))
                .andExpect(jsonPath("$.calendarDue").value(false))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.version").value(4));

        // 同键重放（未来 4 日）：仍返回首次截止 3 日 00:00，不延长，业务效果不重复
        clock.setInstant(Instant.parse("2026-01-05T00:00:00Z"));
        MvcResult replay = postJson("/api/equipment/eq-cal/maintenances", """
                {"requestId":"cal-mnt-1","expectedVersion":3,"readingId":"r1","anchorRevisionNo":1}
                """);
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(maintenanceBody, replay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-cal/maintenances"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].calendarDue").value(true))
                .andExpect(jsonPath("$[0].newCalendarDeadline").value("2026-01-03T00:00:00Z"));
    }

    // ---------- 日历启用：参数校验、版本共享、启用后不可改 ----------

    @Test
    void enableCalendar_validationConflictAndImmutable() throws Exception {
        register("eq-cal2", 1000);
        // 周期必须 1 至 365 整数
        assertEquals(400, enableCalendar("eq-cal2", "c2-a", 1, "op",
                "2026-01-01T00:00:00Z", 0).getResponse().getStatus());
        assertEquals(400, enableCalendar("eq-cal2", "c2-b", 1, "op",
                "2026-01-01T00:00:00Z", 366).getResponse().getStatus());
        // 操作者非空
        assertEquals(400, enableCalendar("eq-cal2", "c2-c", 1, " ",
                "2026-01-01T00:00:00Z", 30).getResponse().getStatus());
        // 与其他写操作共享设备 expectedVersion
        MvcResult wrongVersion = enableCalendar("eq-cal2", "c2-d", 99, "op",
                "2026-01-01T00:00:00Z", 30);
        assertEquals(409, wrongVersion.getResponse().getStatus());
        assertEquals("VERSION_CONFLICT",
                JsonPath.read(wrongVersion.getResponse().getContentAsString(), "$.code"));
        // 失败不占键：同 requestId 修正版本后成功
        MvcResult ok = enableCalendar("eq-cal2", "c2-d", 1, "op", "2026-01-01T00:00:00Z", 30);
        assertEquals(201, ok.getResponse().getStatus());

        // 启用后不可改：再次启用（不同 requestId、不同参数）→ 409，档案保持原值
        MvcResult again = enableCalendar("eq-cal2", "c2-e", 2, "op2",
                "2026-02-01T00:00:00Z", 60);
        assertEquals(409, again.getResponse().getStatus());
        assertEquals("CALENDAR_ALREADY_ENABLED",
                JsonPath.read(again.getResponse().getContentAsString(), "$.code"));
        Integer periodDays = jdbc.queryForObject(
                "SELECT period_days FROM equipment_calendar WHERE equipment_id = 'eq-cal2'",
                Integer.class);
        assertEquals(30, periodDays);
        String operator = jdbc.queryForObject(
                "SELECT operator FROM equipment_calendar WHERE equipment_id = 'eq-cal2'",
                String.class);
        assertEquals("op", operator);

        // 启用成功使设备版本 +1
        mockMvc.perform(get("/api/equipment/eq-cal2/status"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.calendarPeriodDays").value(30));

        // 未登记设备 → 404
        assertEquals(404, enableCalendar("nope", "c2-f", 1, "op",
                "2026-01-01T00:00:00Z", 30).getResponse().getStatus());
    }

    // ---------- 日历启用幂等：同参重放、异参 409 ----------

    @Test
    void enableCalendar_idempotentReplayAndMismatch() throws Exception {
        register("eq-cal3", 1000);
        MvcResult first = enableCalendar("eq-cal3", "c3-1", 1, "op",
                "2026-01-01T00:00:00Z", 30);
        assertEquals(201, first.getResponse().getStatus());

        // 同键同参：重放首次完整响应，版本不重复推进
        MvcResult replay = enableCalendar("eq-cal3", "c3-1", 1, "op",
                "2026-01-01T00:00:00Z", 30);
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-cal3/status"))
                .andExpect(jsonPath("$.version").value(2));

        // 同键异参 → 409
        MvcResult mismatch = enableCalendar("eq-cal3", "c3-1", 1, "op",
                "2026-01-01T00:00:00Z", 31);
        assertEquals(409, mismatch.getResponse().getStatus());
        assertEquals("REQUEST_ID_CONFLICT",
                JsonPath.read(mismatch.getResponse().getContentAsString(), "$.code"));
    }

    // ---------- 保养时间门禁：锚点不得晚于服务端现在、不得早于投用 ----------

    @Test
    void maintenance_timeGates() throws Exception {
        clock.setInstant(Instant.parse("2026-01-10T00:00:00Z"));

        // 未启用日历设备同样执行时间门禁：锚点晚于服务端现在 → 422
        register("eq-gate1", 1000);
        addReading("eq-gate1", "g1-r1", 1, "r1", "2026-01-11T00:00:00Z", 100, 201);
        MvcResult future = postJson("/api/equipment/eq-gate1/maintenances", """
                {"requestId":"g1-m1","expectedVersion":2,"readingId":"r1","anchorRevisionNo":1}
                """);
        assertEquals(422, future.getResponse().getStatus());
        assertEquals("ANCHOR_TIME_IN_FUTURE",
                JsonPath.read(future.getResponse().getContentAsString(), "$.code"));

        // 已启用日历设备：锚点早于投用时刻 → 422
        register("eq-gate2", 1000);
        enableCalendar("eq-gate2", "g2-cal", 1, "op", "2026-01-10T00:00:00Z", 30);
        addReading("eq-gate2", "g2-r1", 2, "r1", "2026-01-09T00:00:00Z", 100, 201);
        MvcResult before = postJson("/api/equipment/eq-gate2/maintenances", """
                {"requestId":"g2-m1","expectedVersion":3,"readingId":"r1","anchorRevisionNo":1}
                """);
        assertEquals(422, before.getResponse().getStatus());
        assertEquals("ANCHOR_BEFORE_COMMISSIONING",
                JsonPath.read(before.getResponse().getContentAsString(), "$.code"));

        // 锚点等于投用时刻且不晚于现在：允许（边界相等合法）
        addReading("eq-gate2", "g2-r2", 3, "r2", "2026-01-10T00:00:00Z", 100, 201);
        MvcResult ok = postJson("/api/equipment/eq-gate2/maintenances", """
                {"requestId":"g2-m2","expectedVersion":4,"readingId":"r2","anchorRevisionNo":1}
                """);
        assertEquals(201, ok.getResponse().getStatus());
    }

    // ---------- 补录历史读数不替换真正最近保养 ----------

    @Test
    void backfillReading_doesNotReplaceLastMaintenanceAnchor() throws Exception {
        register("eq-bf", 100000);
        enableCalendar("eq-bf", "bf-cal", 1, "op", "2026-01-01T00:00:00Z", 1);
        clock.setInstant(Instant.parse("2026-01-02T00:00:00Z"));
        addReading("eq-bf", "bf-r1", 2, "r1", "2026-01-02T00:00:00Z", 0, 201);
        // 真正最近保养：锚点 2 日 00:00
        MvcResult mnt = postJson("/api/equipment/eq-bf/maintenances", """
                {"requestId":"bf-m1","expectedVersion":3,"readingId":"r1","anchorRevisionNo":1}
                """);
        assertEquals(201, mnt.getResponse().getStatus());

        // 补录历史读数（仍按邻居单调校验：0 介于无前邻与后邻 0 之间）
        addReading("eq-bf", "bf-r0", 4, "r0", "2026-01-01T12:00:00Z", 0, 201);

        // 状态仍以真正最近保养锚点（2 日 00:00）计算截止：3 日 00:00
        mockMvc.perform(get("/api/equipment/eq-bf/status"))
                .andExpect(jsonPath("$.lastMaintenanceAnchorSampledAt").value("2026-01-02T00:00:00Z"))
                .andExpect(jsonPath("$.calendarDeadline").value("2026-01-03T00:00:00Z"));

        // 补录读数不能作为新保养锚点（不晚于上次保养锚点）→ 422
        MvcResult backfill = postJson("/api/equipment/eq-bf/maintenances", """
                {"requestId":"bf-m2","expectedVersion":5,"readingId":"r0","anchorRevisionNo":1}
                """);
        assertEquals(422, backfill.getResponse().getStatus());
        assertEquals("ANCHOR_TIME_NOT_LATER",
                JsonPath.read(backfill.getResponse().getContentAsString(), "$.code"));
    }

    // ---------- 未启用日历设备：只看工时 ----------

    @Test
    void status_withoutCalendar_hoursOnly() throws Exception {
        register("eq-hours", 60);
        mockMvc.perform(get("/api/equipment/eq-hours/status"))
                .andExpect(jsonPath("$.calendarEnabled").value(false))
                .andExpect(jsonPath("$.calendarPeriodDays").value(nullValue()))
                .andExpect(jsonPath("$.commissionedAt").value(nullValue()))
                .andExpect(jsonPath("$.calendarDeadline").value(nullValue()))
                .andExpect(jsonPath("$.calendarDue").value(false))
                .andExpect(jsonPath("$.status").value("OK"));

        // 工时达到周期 → 工时到期 DUE
        addReading("eq-hours", "h-r1", 1, "r1", "2026-01-01T00:30:00Z", 60, 201);
        mockMvc.perform(get("/api/equipment/eq-hours/status"))
                .andExpect(jsonPath("$.hoursDue").value(true))
                .andExpect(jsonPath("$.calendarDue").value(false))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 工时到期保养：冻结 hoursDue=true、calendarDue=false、新日历截止为 null
        clock.setInstant(Instant.parse("2026-01-01T01:00:00Z"));
        MvcResult mnt = postJson("/api/equipment/eq-hours/maintenances", """
                {"requestId":"h-m1","expectedVersion":2,"readingId":"r1","anchorRevisionNo":1}
                """);
        assertEquals(201, mnt.getResponse().getStatus());
        String body = mnt.getResponse().getContentAsString();
        Boolean hoursDueFrozen = JsonPath.read(body, "$.hoursDue");
        Boolean calendarDueFrozen = JsonPath.read(body, "$.calendarDue");
        assertTrue(hoursDueFrozen);
        assertFalse(calendarDueFrozen);
        assertNull(JsonPath.read(body, "$.newCalendarDeadline"));

        // 保养后工时原因消除，且无日历时钟推进不会引入日历到期
        clock.setInstant(Instant.parse("2027-01-01T00:00:00Z"));
        mockMvc.perform(get("/api/equipment/eq-hours/status"))
                .andExpect(jsonPath("$.hoursDue").value(false))
                .andExpect(jsonPath("$.calendarDue").value(false))
                .andExpect(jsonPath("$.status").value("OK"));
    }
}

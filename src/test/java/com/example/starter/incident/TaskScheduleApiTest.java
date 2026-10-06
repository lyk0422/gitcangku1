package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 执行时段安排 HTTP 层测试：验证 schedule/start/revoke 路由、X-Actor-Id 请求头约束、
 * 422 错误体（SCHEDULE_VIOLATION 含日期与差额、START_GATE）及版本字段。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class TaskScheduleApiTest {

    private static final Instant T0 = Instant.parse("2026-10-06T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_task_schedules");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "REQ-" + UUID.randomUUID();
    }

    private void report(String incidentKey) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey
                                + "\",\"severity\":\"S1\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
    }

    private void takeover(String incidentKey, String actor) throws Exception {
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
    }

    private void createTask(String incidentKey, String actor, String taskKey) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"G\",\"title\":\"t\","
                                + "\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isOk());
    }

    private static String scheduleBody(String requestId, int version, String assignee,
                                       String start, String end) {
        return "{\"requestId\":\"" + requestId + "\",\"expectedTaskVersion\":" + version
                + ",\"assignee\":\"" + assignee + "\",\"startAt\":\"" + start
                + "\",\"endAt\":\"" + end + "\"}";
    }

    @Test
    void scheduleHttpFlow() throws Exception {
        report("INC-600");
        takeover("INC-600", "alice");
        createTask("INC-600", "alice", "T-1");

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/schedule", "INC-600", "T-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(key(), 1, "worker-1",
                                "2026-10-06T10:00:00Z", "2026-10-06T11:00:00Z")))
                .andExpect(status().isBadRequest());

        // 安排成功：版本 1→2，响应含安排视图
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/schedule", "INC-600", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(key(), 1, "worker-1",
                                "2026-10-06T10:00:00Z", "2026-10-06T11:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.schedule.assignee").value("worker-1"))
                .andExpect(jsonPath("$.schedule.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.schedule.startAt").value("2026-10-06T10:00:00Z"));

        // 同人相邻休息不足 → 422，details 给出规则、日期与差额
        createTask("INC-600", "alice", "T-2");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/schedule", "INC-600", "T-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(key(), 1, "worker-1",
                                "2026-10-06T11:20:00Z", "2026-10-06T12:00:00Z")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SCHEDULE_VIOLATION"))
                .andExpect(jsonPath("$.details.rule").value("REST"))
                .andExpect(jsonPath("$.details.date").value("2026-10-06"))
                .andExpect(jsonPath("$.details.minutes").value(10));

        // 未到开始时刻 → 422 START_GATE
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/start", "INC-600", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"expectedTaskVersion\":2}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("START_GATE"));

        // 时钟进入安排区间后开始成功 → 版本 3，STARTED
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-06T10:30:00Z"));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/start", "INC-600", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"expectedTaskVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.schedule.status").value("STARTED"));
    }

    @Test
    void revokeHttpFlow() throws Exception {
        report("INC-610");
        takeover("INC-610", "alice");
        createTask("INC-610", "alice", "T-1");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/schedule", "INC-610", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(key(), 1, "worker-1",
                                "2026-10-06T10:00:00Z", "2026-10-06T11:00:00Z")))
                .andExpect(status().isOk());

        // 撤销成功：状态 REVOKED，版本保持 2
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/schedule/revoke", "INC-610", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"expectedTaskVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.schedule.status").value("REVOKED"));

        // 非当前指挥人 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/schedule", "INC-610", "T-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(key(), 2, "worker-1",
                                "2026-10-06T12:00:00Z", "2026-10-06T13:00:00Z")))
                .andExpect(status().isConflict());
    }
}

package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
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
 * 任务执行安排 HTTP 层测试：验证路由、X-Actor-Id 请求头约束、
 * 400/404/409/422 错误语义与 details 结构化明细（日期与差额）、requestId 幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class TaskAssignmentApiTest {

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
        jdbc.update("DELETE FROM task_assignments");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(java.time.Instant.parse("2026-09-22T00:00:00Z"));
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
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
                                + "\",\"groupCode\":\"G\",\"title\":\"t\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    private static String assignBody(String requestId, long version, String assignee,
                                     String start, String end) {
        return "{\"requestId\":\"" + requestId + "\",\"expectedTaskVersion\":" + version
                + ",\"assignee\":\"" + assignee + "\",\"startAt\":\"" + start
                + "\",\"endAt\":\"" + end + "\"}";
    }

    @Test
    void assignmentHttpFlow() throws Exception {
        report("INC-600");
        takeover("INC-600", "alice");
        createTask("INC-600", "alice", "T-1");

        // 创建安排：版本 1 → 2
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/assignment", "INC-600", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(key(), 1, "worker-1",
                                "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.assignment.assignee").value("worker-1"))
                .andExpect(jsonPath("$.assignment.startAt").value("2026-09-22T10:00:00Z"));

        // 开始：Clock 推进到区间内
        ((ControllableClock) clock).setInstant(java.time.Instant.parse("2026-09-22T10:30:00Z"));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/start", "INC-600", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"expectedTaskVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STARTED"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.startedBy").value("alice"));

        // 完成：版本 3 → 4，安排保留
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-600", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.assignment.assignee").value("worker-1"));
    }

    @Test
    void assignmentHttpErrors() throws Exception {
        report("INC-610");
        takeover("INC-610", "alice");
        createTask("INC-610", "alice", "T-1");

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/assignment", "INC-610", "T-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(key(), 1, "w",
                                "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z")))
                .andExpect(status().isBadRequest());
        // 非整分钟 → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/assignment", "INC-610", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(key(), 1, "w",
                                "2026-09-22T10:00:30Z", "2026-09-22T11:00:00Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        // 事件不存在 → 404
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/assignment", "INC-404", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(key(), 1, "w",
                                "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z")))
                .andExpect(status().isNotFound());
        // 任务不存在 → 404
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/assignment", "INC-610", "T-9")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(key(), 1, "w",
                                "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z")))
                .andExpect(status().isNotFound());
        // 非当前指挥人 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/assignment", "INC-610", "T-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(key(), 1, "w",
                                "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z")))
                .andExpect(status().isConflict());
        // 版本不匹配 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/assignment", "INC-610", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(key(), 7, "w",
                                "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z")))
                .andExpect(status().isConflict());
    }

    @Test
    void restViolation_returns422WithDateAndShortfall() throws Exception {
        report("INC-620");
        takeover("INC-620", "alice");
        createTask("INC-620", "alice", "T-1");
        createTask("INC-620", "alice", "T-2");
        // 合成跨日 23:00 至 01:00 安排
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/assignment", "INC-620", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(key(), 1, "worker-1",
                                "2026-09-22T23:00:00Z", "2026-09-23T01:00:00Z")))
                .andExpect(status().isOk());
        // 01:20 开始 → 422，details 给出日期与休息差额
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/assignment", "INC-620", "T-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(key(), 1, "worker-1",
                                "2026-09-23T01:20:00Z", "2026-09-23T02:20:00Z")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"))
                .andExpect(jsonPath("$.details.date").value("2026-09-23"))
                .andExpect(jsonPath("$.details.requiredRestMinutes").value(30))
                .andExpect(jsonPath("$.details.actualGapMinutes").value(20))
                .andExpect(jsonPath("$.details.shortfallMinutes").value(10));
        // 01:30 开始 → 允许
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/assignment", "INC-620", "T-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(key(), 1, "worker-1",
                                "2026-09-23T01:30:00Z", "2026-09-23T02:30:00Z")))
                .andExpect(status().isOk());
    }

    @Test
    void dailyLimitViolation_returns422WithDateAndExcess() throws Exception {
        report("INC-630");
        takeover("INC-630", "alice");
        createTask("INC-630", "alice", "T-1");
        createTask("INC-630", "alice", "T-2");
        createTask("INC-630", "alice", "T-3");
        // 两段 240 分钟合计 480 分钟（单段上限 240）
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/assignment", "INC-630", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(key(), 1, "worker-1",
                                "2026-09-22T08:00:00Z", "2026-09-22T12:00:00Z")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/assignment", "INC-630", "T-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(key(), 1, "worker-1",
                                "2026-09-22T12:30:00Z", "2026-09-22T16:30:00Z")))
                .andExpect(status().isOk());
        // 当日已有 480 分钟，再排 30 分钟 → 422，details 给出日期与超出差额
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/assignment", "INC-630", "T-3")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(key(), 1, "worker-1",
                                "2026-09-22T17:00:00Z", "2026-09-22T17:30:00Z")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.date").value("2026-09-22"))
                .andExpect(jsonPath("$.details.limitMinutes").value(480))
                .andExpect(jsonPath("$.details.scheduledMinutes").value(510))
                .andExpect(jsonPath("$.details.excessMinutes").value(30));
    }

    @Test
    void requestIdReplay_sameKeySameParamsReturnsFirstResponse() throws Exception {
        report("INC-640");
        takeover("INC-640", "alice");
        createTask("INC-640", "alice", "T-1");
        String requestId = key();
        String body = assignBody(requestId, 1, "worker-1",
                "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/assignment", "INC-640", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 同键同参重放：仍返回首次响应，版本不重复递增
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/assignment", "INC-640", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 同键异参 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/assignment", "INC-640", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(requestId, 1, "worker-2",
                                "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        Integer keys = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                requestId);
        assertThat(keys).isEqualTo(1);
    }

    @Test
    void startOutsideWindow_returns422() throws Exception {
        report("INC-650");
        takeover("INC-650", "alice");
        createTask("INC-650", "alice", "T-1");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/assignment", "INC-650", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(key(), 1, "worker-1",
                                "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z")))
                .andExpect(status().isOk());
        // Clock 固定在 00:00，不在区间内 → 422
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/start", "INC-650", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"expectedTaskVersion\":2}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.windowStartAt").value("2026-09-22T10:00:00Z"))
                .andExpect(jsonPath("$.details.windowEndAt").value("2026-09-22T11:00:00Z"));
    }
}

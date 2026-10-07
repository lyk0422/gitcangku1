package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 整案排程提案 HTTP 层测试：验证路由、X-Actor-Id 请求头约束、
 * 题干矛盾示例的 422 错误语义、可行提案提交与只读查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScheduleApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_schedules");
        jdbc.update("DELETE FROM incident_schedule_state");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "REQ-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey
                                + "\",\"severity\":\"S2\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", commander)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
    }

    private void openTask(String incidentKey, String actor, String taskKey) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"DB\",\"title\":\"扩容\","
                                + "\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isOk());
    }

    private static String proposeBody(String requestId, String constraintsJson) {
        return "{\"requestId\":\"" + requestId + "\",\"baseInstant\":\"2026-10-01T01:00:00Z\","
                + "\"tasks\":["
                + "{\"taskKey\":\"A\",\"durationMinutes\":5,\"earliestStartOffset\":0,"
                + "\"latestFinishOffset\":10},"
                + "{\"taskKey\":\"B\",\"durationMinutes\":5,\"earliestStartOffset\":0,"
                + "\"latestFinishOffset\":10}],"
                + "\"constraints\":" + constraintsJson + "}";
    }

    @Test
    void proposeHttpFlow_infeasibleThenFeasible() throws Exception {
        commanding("INC-H1", "alice");
        openTask("INC-H1", "alice", "A");
        openTask("INC-H1", "alice", "B");

        // 题干示例：A 完后 B 才开始可行，再要求 B 完后 A 才开始形成整体矛盾 → 422
        mvc.perform(post("/api/incidents/{k}/schedules", "INC-H1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposeBody(key(),
                                "[{\"fromTaskKey\":\"A\",\"toTaskKey\":\"B\",\"lowerBound\":0,"
                                        + "\"upperBound\":1000},"
                                        + "{\"fromTaskKey\":\"B\",\"toTaskKey\":\"A\","
                                        + "\"lowerBound\":0,\"upperBound\":1000}]")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SCHEDULE_INFEASIBLE"));
        // 不留方案
        mvc.perform(get("/api/incidents/{k}/schedules", "INC-H1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").doesNotExist())
                .andExpect(jsonPath("$.history.length()").value(0));

        // 仅保留 A→B：可行，提交成功
        mvc.perform(post("/api/incidents/{k}/schedules", "INC-H1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposeBody(key(),
                                "[{\"fromTaskKey\":\"A\",\"toTaskKey\":\"B\",\"lowerBound\":0,"
                                        + "\"upperBound\":5}]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleVersion").value(1))
                .andExpect(jsonPath("$.status").value("CURRENT"))
                .andExpect(jsonPath("$.baseInstant").value("2026-10-01T01:00:00Z"))
                .andExpect(jsonPath("$.stateVersion").value(0))
                .andExpect(jsonPath("$.tasks[0].taskKey").value("A"))
                .andExpect(jsonPath("$.tasks[0].taskStatus").value("OPEN"))
                .andExpect(jsonPath("$.constraints[0].upperBound").value(5))
                .andExpect(jsonPath("$.createdBy").value("alice"));

        // 只读查询：最新版本与历史
        mvc.perform(get("/api/incidents/{k}/schedules", "INC-H1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.scheduleVersion").value(1))
                .andExpect(jsonPath("$.current.status").value("CURRENT"))
                .andExpect(jsonPath("$.history.length()").value(1));
    }

    @Test
    void proposeHttp_actorHeaderRequired() throws Exception {
        commanding("INC-H2", "alice");
        openTask("INC-H2", "alice", "A");
        openTask("INC-H2", "alice", "B");
        // 缺少 X-Actor-Id 请求头 → 400
        mvc.perform(post("/api/incidents/{k}/schedules", "INC-H2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposeBody(key(), "[]")))
                .andExpect(status().isBadRequest());
        // 空白操作者 → 400
        mvc.perform(post("/api/incidents/{k}/schedules", "INC-H2")
                        .header("X-Actor-Id", " ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposeBody(key(), "[]")))
                .andExpect(status().isBadRequest());
        // 非当前指挥人 → 409
        mvc.perform(post("/api/incidents/{k}/schedules", "INC-H2")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposeBody(key(), "[]")))
                .andExpect(status().isConflict());
    }
}

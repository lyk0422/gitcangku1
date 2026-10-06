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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 事件资源到期 HTTP 层测试：验证分配/续期/开始/继续执行/查询路由、
 * X-Actor-Id 约束及 400/404/409/422 错误语义与 details 结构化明细。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class ResourceAssignmentApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM resource_assignment_events");
        jdbc.update("DELETE FROM task_execution_logs");
        jdbc.update("DELETE FROM task_resource_assignments");
        jdbc.update("DELETE FROM incident_resources");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void reportAndTakeover(String incidentKey, String actor) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey
                                + "\",\"severity\":\"S2\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
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
                .andExpect(status().isOk());
    }

    private void assign(String incidentKey, String taskKey, String actor, String resourceKey,
                        String expiresAt) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/resource", incidentKey, taskKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\""
                                + resourceKey + "\",\"authorizationVersion\":1,"
                                + "\"expiresAt\":\"" + expiresAt + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceKey").value(resourceKey))
                .andExpect(jsonPath("$.authorizationVersion").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.expired").value(false));
    }

    @Test
    void resourceHttpFlow() throws Exception {
        reportAndTakeover("INC-H-001", "alice");
        createTask("INC-H-001", "alice", "T-1");
        assign("INC-H-001", "T-1", "alice", "RES-1", "2027-01-01T00:00:00Z");

        // 当前租约明细
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/resource", "INC-H-001", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceKey").value("RES-1"))
                .andExpect(jsonPath("$.assignedBy").value("alice"))
                .andExpect(jsonPath("$.remainingSeconds").isNumber());

        // 开始与继续执行
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/start", "INC-H-001", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"note\":\"开工\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceKey").value("RES-1"));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/executions", "INC-H-001", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"note\":\"推进\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/executions", "INC-H-001", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].entryType").value("START"))
                .andExpect(jsonPath("$[1].entryType").value("PROGRESS"));

        // 续期（不同授权角色）
        mvc.perform(post("/api/incidents/{k}/resources/renew", "INC-H-001")
                        .header("X-Actor-Id", "officer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"items\":[{"
                                + "\"taskKey\":\"T-1\",\"newAuthorizationVersion\":2,"
                                + "\"newExpiresAt\":\"2027-06-01T00:00:00Z\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renewed[0].authorizationVersion").value(2));

        // 证据历史：ASSIGN + RENEW
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/resource/events", "INC-H-001", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].eventType").value("ASSIGN"))
                .andExpect(jsonPath("$[0].oldVersion").doesNotExist())
                .andExpect(jsonPath("$[1].eventType").value("RENEW"))
                .andExpect(jsonPath("$[1].oldVersion").value(1))
                .andExpect(jsonPath("$[1].newVersion").value(2));
    }

    @Test
    void resourceHttpErrors() throws Exception {
        reportAndTakeover("INC-H-002", "alice");
        createTask("INC-H-002", "alice", "T-1");

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/resource", "INC-H-002", "T-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"R\","
                                + "\"authorizationVersion\":1,"
                                + "\"expiresAt\":\"2027-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        // 到期时刻不晚于分配时刻 → 400 且 details 给出差额
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/resource", "INC-H-002", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"R\","
                                + "\"authorizationVersion\":1,"
                                + "\"expiresAt\":\"2020-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.details.requestedExpiresAt").exists())
                .andExpect(jsonPath("$.details.shortfallSeconds").isNumber());
        // 授权版本缺失 → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/resource", "INC-H-002", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"R\","
                                + "\"expiresAt\":\"2027-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        // 任务不存在 → 404
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/resource", "INC-H-002", "T-9")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"R\","
                                + "\"authorizationVersion\":1,"
                                + "\"expiresAt\":\"2027-01-01T00:00:00Z\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/resource", "INC-H-002", "T-1"))
                .andExpect(status().isNotFound());

        // 占用冲突 → 409
        assign("INC-H-002", "T-1", "alice", "RES-1", "2027-01-01T00:00:00Z");
        reportAndTakeover("INC-H-003", "bob");
        createTask("INC-H-003", "bob", "T-1");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/resource", "INC-H-003", "T-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"RES-1\","
                                + "\"authorizationVersion\":1,"
                                + "\"expiresAt\":\"2027-01-01T00:00:00Z\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 续期冲突 → 422 且 details 携带 reason
        mvc.perform(post("/api/incidents/{k}/resources/renew", "INC-H-002")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"items\":[{"
                                + "\"taskKey\":\"T-1\",\"newAuthorizationVersion\":2,"
                                + "\"newExpiresAt\":\"2027-06-01T00:00:00Z\"}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"))
                .andExpect(jsonPath("$.details[0].reason").value("NOT_ASSIGNER_ROLE"));
    }
}

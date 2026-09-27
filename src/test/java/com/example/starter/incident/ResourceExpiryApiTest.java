package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * 事件资源授权 HTTP 层测试：验证分配/执行/续期/回收/到期检查与查询路由、
 * X-Actor-Id 约束、400/404/409/422 错误语义及结构化明细。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class ResourceExpiryApiTest {

    private static final Instant T0 = Instant.parse("2026-09-27T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_resource_events");
        jdbc.update("DELETE FROM incident_task_leases");
        jdbc.update("DELETE FROM resource_locks");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
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

    private void createTask(String incidentKey, String actor, String taskKey) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"DB\",\"title\":\"扩容\","
                                + "\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isOk());
    }

    private void assign(String incidentKey, String taskKey, String actor, String resourceKey,
                        int version, String expiresAt) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/resource", incidentKey, taskKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\""
                                + resourceKey + "\",\"authorizationVersion\":" + version
                                + ",\"expiresAt\":\"" + expiresAt + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void resourceHttpFlow() throws Exception {
        commanding("INC-600", "alice");
        createTask("INC-600", "alice", "T-1");

        // 分配：返回租约明细
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/resource", "INC-600", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"db-pool-1\","
                                + "\"authorizationVersion\":1,"
                                + "\"expiresAt\":\"2026-09-27T00:10:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskKey").value("T-1"))
                .andExpect(jsonPath("$.resourceKey").value("db-pool-1"))
                .andExpect(jsonPath("$.authVersion").value(1))
                .andExpect(jsonPath("$.assignedBy").value("alice"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.expired").value(false));

        // 开始/继续执行
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/start", "INC-600", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/continue", "INC-600", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        // 续期（不同授权角色）
        mvc.perform(post("/api/incidents/{k}/resources/renew", "INC-600")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"items\":[{"
                                + "\"taskKey\":\"T-1\",\"expectedVersion\":1,\"newVersion\":2,"
                                + "\"newExpiresAt\":\"2026-09-27T01:00:00Z\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renewedBy").value("bob"))
                .andExpect(jsonPath("$.items[0].oldVersion").value(1))
                .andExpect(jsonPath("$.items[0].newVersion").value(2))
                .andExpect(jsonPath("$.items[0].extensionSeconds").value(3000));

        // 明细/历史/诊断查询
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/resource", "INC-600", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authVersion").value(2))
                .andExpect(jsonPath("$.expiresAt").value("2026-09-27T01:00:00Z"));
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/resource/events", "INC-600", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].eventType").value("ASSIGN"))
                .andExpect(jsonPath("$[3].eventType").value("RENEW"))
                .andExpect(jsonPath("$[3].oldVersion").value(1))
                .andExpect(jsonPath("$[3].newVersion").value(2));
        mvc.perform(get("/api/incidents/{k}/resources", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidentKey").value("INC-600"))
                .andExpect(jsonPath("$.leases.length()").value(1))
                .andExpect(jsonPath("$.leases[0].taskStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.leases[0].expired").value(false));

        // 显式回收
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/resource/release", "INC-600", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releasedBy").value("alice"));
    }

    @Test
    void resourceHttpErrors() throws Exception {
        commanding("INC-601", "alice");
        createTask("INC-601", "alice", "T-1");

        // 到期时刻不晚于分配时刻 → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/resource", "INC-601", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"r1\","
                                + "\"authorizationVersion\":1,"
                                + "\"expiresAt\":\"2026-09-26T00:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/resource", "INC-601", "T-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"r1\","
                                + "\"authorizationVersion\":1,"
                                + "\"expiresAt\":\"2026-09-27T01:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        // 非当前指挥人 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/resource", "INC-601", "T-1")
                        .header("X-Actor-Id", "mallory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"r1\","
                                + "\"authorizationVersion\":1,"
                                + "\"expiresAt\":\"2026-09-27T01:00:00Z\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        // 任务不存在 → 404
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/resource", "INC-601", "T-9")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"r1\","
                                + "\"authorizationVersion\":1,"
                                + "\"expiresAt\":\"2026-09-27T01:00:00Z\"}"))
                .andExpect(status().isNotFound());
        // 无租约任务的明细/检查 → 404
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/resource", "INC-601", "T-1"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/authorization/check", "INC-601", "T-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isNotFound());
        // 未分配资源开始执行 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/start", "INC-601", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void expiryInterceptionHttp() throws Exception {
        commanding("INC-602", "alice");
        createTask("INC-602", "alice", "T-1");
        assign("INC-602", "T-1", "alice", "r1", 1, "2026-09-27T00:05:00Z");
        // 时钟推进到到期后：开始执行被拦截 → 422 + 结构化明细（实际到期时刻/判定时刻/差额）
        ((ControllableClock) clock).setInstant(T0.plusSeconds(301));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/start", "INC-602", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_EXPIRED"))
                .andExpect(jsonPath("$.details.resourceKey").value("r1"))
                .andExpect(jsonPath("$.details.expiresAt").value("2026-09-27T00:05:00Z"))
                .andExpect(jsonPath("$.details.overdueSeconds").value(1));
        // 任务状态已固化为 AUTHORIZATION_EXPIRED
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-602", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZATION_EXPIRED"));
        // 到期检查路由（换事件）：未到期返回 ACTIVE
        commanding("INC-603", "bob");
        createTask("INC-603", "bob", "T-1");
        assign("INC-603", "T-1", "bob", "r2", 1, "2026-09-27T01:00:00Z");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/authorization/check", "INC-603", "T-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.expired").value(false));
    }
}

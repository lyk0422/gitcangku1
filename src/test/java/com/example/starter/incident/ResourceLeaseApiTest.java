package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
 * 资源租约 HTTP 层测试：验证分配/续期/执行/历史路由、X-Actor-Id 请求头约束、
 * 400/404/409/422 错误语义及 details 结构化明细（差额、闭包缺失）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResourceLeaseApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM resource_lease_events");
        jdbc.update("DELETE FROM incident_task_executions");
        jdbc.update("DELETE FROM resource_leases");
        jdbc.update("DELETE FROM leased_resources");
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

    private void commanding(String incidentKey, String commander) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey
                                + "\",\"severity\":\"S1\",\"summary\":\"s\",\"reporter\":\"r\"}"))
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
                        String expiresAt) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/resource", incidentKey, taskKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\""
                                + resourceKey + "\",\"authVersion\":\"v1\",\"expiresAt\":\""
                                + expiresAt + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void resourceLeaseHttpFlow() throws Exception {
        commanding("INC-600", "alice");
        createTask("INC-600", "alice", "T-1");
        String expires = Instant.now().plusSeconds(3600).toString();

        // 分配资源
        assign("INC-600", "T-1", "alice", "RES-HTTP-1", expires);

        // 任务明细携带活动租约
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-600", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lease.resourceKey").value("RES-HTTP-1"))
                .andExpect(jsonPath("$.lease.authVersion").value("v1"))
                .andExpect(jsonPath("$.lease.leaseVersion").value(1))
                .andExpect(jsonPath("$.lease.status").value("ACTIVE"));

        // 执行 START
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/executions", "INC-600", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"action\":\"START\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        // 续期（授权角色不同于原分配人，新版本）
        String newExpires = Instant.now().plusSeconds(7200).toString();
        mvc.perform(post("/api/incidents/{k}/tasks/renew", "INC-600")
                        .header("X-Actor-Id", "authorizer-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKeys\":[\"T-1\"],"
                                + "\"newAuthVersion\":\"v2\",\"authorizerRole\":\"OPS_LEAD\","
                                + "\"expiresAt\":\"" + newExpires + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].lease.leaseVersion").value(2))
                .andExpect(jsonPath("$.tasks[0].lease.authVersion").value("v2"))
                .andExpect(jsonPath("$.tasks[0].lease.authorizedBy").value("authorizer-1"));

        // 明细与历史：版本、证据、执行日志
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/leases", "INC-600", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentLease.leaseVersion").value(2))
                .andExpect(jsonPath("$.leases.length()").value(2))
                .andExpect(jsonPath("$.events[0].eventType").value("ASSIGN"))
                .andExpect(jsonPath("$.events[1].eventType").value("RENEW"))
                .andExpect(jsonPath("$.events[1].oldAuthVersion").value("v1"))
                .andExpect(jsonPath("$.events[1].newAuthVersion").value("v2"))
                .andExpect(jsonPath("$.executions[0].action").value("START"));
    }

    @Test
    void resourceLeaseHttpErrors() throws Exception {
        commanding("INC-601", "alice");
        createTask("INC-601", "alice", "T-1");

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/resource", "INC-601", "T-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"RES-1\","
                                + "\"authVersion\":\"v1\",\"expiresAt\":\""
                                + Instant.now().plusSeconds(60) + "\"}"))
                .andExpect(status().isBadRequest());

        // 到期时刻不晚于分配时刻 → 400 且 details 返回差额
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/resource", "INC-601", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"RES-1\","
                                + "\"authVersion\":\"v1\",\"expiresAt\":\""
                                + Instant.now().minusSeconds(30) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.details.required").value("expiresAt > assignedAt"))
                .andExpect(jsonPath("$.details.diffSeconds").value(-30));

        // 任务不存在 → 404
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/resource", "INC-601", "T-9")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"RES-1\","
                                + "\"authVersion\":\"v1\",\"expiresAt\":\""
                                + Instant.now().plusSeconds(60) + "\"}"))
                .andExpect(status().isNotFound());

        // 续期闭包未覆盖（事件内无活动租约）→ 422
        mvc.perform(post("/api/incidents/{k}/tasks/renew", "INC-601")
                        .header("X-Actor-Id", "authorizer-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKeys\":[\"T-1\"],"
                                + "\"newAuthVersion\":\"v2\",\"authorizerRole\":\"OPS_LEAD\","
                                + "\"expiresAt\":\"" + Instant.now().plusSeconds(600) + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RENEWAL_CONFLICT"));

        // 历史查询：任务不存在 → 404
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/leases", "INC-601", "T-9"))
                .andExpect(status().isNotFound());
    }
}

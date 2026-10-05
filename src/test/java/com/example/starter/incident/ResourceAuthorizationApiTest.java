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
 * 资源授权 HTTP 层测试：验证分配/续期/明细/证据/诊断路由、
 * X-Actor-Id 请求头约束及 400/404/409/422 错误语义与 details 结构化明细。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class ResourceAuthorizationApiTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM resource_authorization_events");
        jdbc.update("DELETE FROM task_execution_logs");
        jdbc.update("DELETE FROM task_resource_assignments");
        jdbc.update("DELETE FROM resource_locks");
        jdbc.update("DELETE FROM incident_escalations");
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
                                + "\",\"groupCode\":\"DB\",\"title\":\"扩容\","
                                + "\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isOk());
    }

    private void assign(String incidentKey, String taskKey, String actor, String resourceKey,
                        long version, String expiresAt) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/resources", incidentKey, taskKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\""
                                + resourceKey + "\",\"authorizationVersion\":" + version
                                + ",\"expiresAt\":\"" + expiresAt + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceKey").value(resourceKey))
                .andExpect(jsonPath("$.authorizationVersion").value(version))
                .andExpect(jsonPath("$.expired").value(false));
    }

    @Test
    void resourceHttpFlow() throws Exception {
        report("INC-900");
        takeover("INC-900", "alice");
        createTask("INC-900", "alice", "T-1");

        // 分配资源（到期时刻 T0+300）
        assign("INC-900", "T-1", "alice", "RES-1", 1L, "2026-09-22T00:05:00Z");

        // 开始与继续执行
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/start", "INC-900", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/execute", "INC-900", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"note\":\"处理中\"}"))
                .andExpect(status().isOk());

        // 时钟推进到到期后：执行被拦截 409，details 携带资源与时刻
        ((ControllableClock) clock).setInstant(T0.plusSeconds(300));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/execute", "INC-900", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"note\":\"继续\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.details.resourceKey").value("RES-1"))
                .andExpect(jsonPath("$.details.attemptedAction").value("CONTINUE"))
                .andExpect(jsonPath("$.details.expiresAt").value("2026-09-22T00:05:00Z"));

        // 任务视图显示到期状态
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-900", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZATION_EXPIRED"))
                .andExpect(jsonPath("$.authorizationExpired").value(true))
                .andExpect(jsonPath("$.resourceKey").value("RES-1"));

        // 原分配人续期 → 422；不同授权角色续期 → 200
        mvc.perform(post("/api/incidents/{k}/tasks/renewals", "INC-900")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"entries\":[{\"taskKey\":"
                                + "\"T-1\",\"newAuthorizationVersion\":2,"
                                + "\"newExpiresAt\":\"2026-09-22T00:15:00Z\"}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"));
        mvc.perform(post("/api/incidents/{k}/tasks/renewals", "INC-900")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"entries\":[{\"taskKey\":"
                                + "\"T-1\",\"newAuthorizationVersion\":2,"
                                + "\"newExpiresAt\":\"2026-09-22T00:15:00Z\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renewed[0].taskKey").value("T-1"))
                .andExpect(jsonPath("$.renewed[0].oldAuthorizationVersion").value(1))
                .andExpect(jsonPath("$.renewed[0].newAuthorizationVersion").value(2))
                .andExpect(jsonPath("$.renewed[0].taskStatus").value("IN_PROGRESS"));

        // 续期后可完成
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-900", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        // 明细与历史：当前租约 + 证据 + 执行日志
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/resources", "INC-900", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.resourceKey").value("RES-1"))
                .andExpect(jsonPath("$.current.authorizationVersion").value(2))
                .andExpect(jsonPath("$.events.length()").value(4))
                .andExpect(jsonPath("$.events[0].eventType").value("ASSIGN"))
                .andExpect(jsonPath("$.events[1].eventType").value("EXPIRY_DETECTED"))
                .andExpect(jsonPath("$.events[2].eventType").value("EXECUTION_BLOCKED"))
                .andExpect(jsonPath("$.events[3].eventType").value("RENEW"))
                .andExpect(jsonPath("$.executionLogs.length()").value(3));

        // 事件级证据与诊断
        mvc.perform(get("/api/incidents/{k}/resource-events", "INC-900"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
        mvc.perform(get("/api/incidents/{k}/resources/diagnostics", "INC-900"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidentKey").value("INC-900"))
                .andExpect(jsonPath("$.assignments.length()").value(1))
                .andExpect(jsonPath("$.assignments[0].taskStatus").value("DONE"));
    }

    @Test
    void resourceHttpErrors() throws Exception {
        report("INC-901");
        takeover("INC-901", "alice");
        createTask("INC-901", "alice", "T-1");

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/resources", "INC-901", "T-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"RES-1\","
                                + "\"authorizationVersion\":1,"
                                + "\"expiresAt\":\"2026-09-22T00:05:00Z\"}"))
                .andExpect(status().isBadRequest());
        // 到期时刻不晚于分配时刻 → 400 且 details 给出要求
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/resources", "INC-901", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"RES-1\","
                                + "\"authorizationVersion\":1,"
                                + "\"expiresAt\":\"2026-09-21T00:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        // 事件不存在 → 404
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/resources", "INC-404", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"RES-1\","
                                + "\"authorizationVersion\":1,"
                                + "\"expiresAt\":\"2026-09-22T00:05:00Z\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents/{k}/resource-events", "INC-404"))
                .andExpect(status().isNotFound());
        // 未分配资源的任务续期 → 422
        mvc.perform(post("/api/incidents/{k}/tasks/renewals", "INC-901")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"entries\":[{\"taskKey\":"
                                + "\"T-1\",\"newAuthorizationVersion\":2,"
                                + "\"newExpiresAt\":\"2026-09-22T00:15:00Z\"}]}"))
                .andExpect(status().isUnprocessableEntity());
    }
}

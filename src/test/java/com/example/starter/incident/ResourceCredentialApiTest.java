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
 * 资源凭据与任务派发 HTTP 层测试：验证凭据登记/撤销、派发、执行前检查阻断与再派发路由、
 * 400/404/409 语义，以及查询中任务状态、凭据版本、阻断原因和历次派发记录的展示。
 * 时钟固定在 2026-09-22T00:00Z，使用相对失效时刻表达 11:59/12:00 边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class ResourceCredentialApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM task_dispatches");
        jdbc.update("DELETE FROM resource_credentials");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void reportAndTakeover(String incidentKey) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey
                                + "\",\"severity\":\"S1\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
    }

    private void createTask(String incidentKey, String taskKey) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"OPS\",\"title\":\"扩容\",\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isOk());
    }

    private void registerCredential(String resource, String version, String type, String region,
                                    String expiresAt) throws Exception {
        mvc.perform(post("/api/resources/{r}/credentials", resource)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"" + resource
                                + "\",\"version\":\"" + version + "\",\"credentialType\":\"" + type
                                + "\",\"region\":\"" + region + "\",\"expiresAt\":\"" + expiresAt
                                + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(version));
    }

    @Test
    void credentialDispatchBlockRedispatchHttpFlow() throws Exception {
        reportAndTakeover("INC-700");
        createTask("INC-700", "T-1");
        // 时钟固定 T0=2026-09-22T00:00Z；凭据 60 分钟后失效
        registerCredential("R", "v1", "AWS_AK", "A", "2026-09-22T01:00:00Z");

        // 派发通过
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dispatches", "INC-700", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-700-1\",\"resourceKey\":\"R\","
                                + "\"credentialVersion\":\"v1\",\"requiredType\":\"AWS_AK\","
                                + "\"region\":\"A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPATCHED"))
                .andExpect(jsonPath("$.credential.resourceKey").value("R"))
                .andExpect(jsonPath("$.credential.credentialVersion").value("v1"))
                .andExpect(jsonPath("$.credential.region").value("A"))
                .andExpect(jsonPath("$.credential.dispatchStatus").value("DISPATCHED"))
                .andExpect(jsonPath("$.dispatches.length()").value(1))
                .andExpect(jsonPath("$.dispatches[0].seq").value(1))
                .andExpect(jsonPath("$.dispatches[0].requestId").value("REQ-700-1"));

        // 已派发未检查不能完成
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-700", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict());

        // 撤销凭据后执行前检查 → 任务 BLOCKED（HTTP 200，响应体表达状态转移）
        mvc.perform(post("/api/resources/{r}/credentials/{v}/revoke", "R", "v1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/start", "INC-700", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.blockReason").value("CREDENTIAL_REVOKED"))
                .andExpect(jsonPath("$.blockedAt").isNotEmpty())
                .andExpect(jsonPath("$.credential.credentialVersion").value("v1"))
                .andExpect(jsonPath("$.credential.dispatchStatus").value("BLOCKED"));

        // 查询展示任务状态、凭据版本、阻断原因和派发记录
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-700", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.blockReason").value("CREDENTIAL_REVOKED"))
                .andExpect(jsonPath("$.credential.credentialVersion").value("v1"))
                .andExpect(jsonPath("$.dispatches[0].status").value("BLOCKED"))
                .andExpect(jsonPath("$.dispatches[0].blockReason").value("CREDENTIAL_REVOKED"));

        // BLOCKED 不能直接完成
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-700", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict());

        // 登记新版本凭据后再派发、检查、完成
        registerCredential("R", "v2", "AWS_AK", "A", "2026-09-22T05:00:00Z");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dispatches", "INC-700", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-700-2\",\"resourceKey\":\"R\","
                                + "\"credentialVersion\":\"v2\",\"requiredType\":\"AWS_AK\","
                                + "\"region\":\"A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPATCHED"))
                .andExpect(jsonPath("$.credential.credentialVersion").value("v2"));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/start", "INC-700", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTING"));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-700", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        // 历史保留两条派发记录，旧记录区域与凭据依据不变
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-700", "T-1"))
                .andExpect(jsonPath("$.dispatches.length()").value(2))
                .andExpect(jsonPath("$.dispatches[0].seq").value(1))
                .andExpect(jsonPath("$.dispatches[0].credentialVersion").value("v1"))
                .andExpect(jsonPath("$.dispatches[0].region").value("A"))
                .andExpect(jsonPath("$.dispatches[0].status").value("BLOCKED"))
                .andExpect(jsonPath("$.dispatches[1].seq").value(2))
                .andExpect(jsonPath("$.dispatches[1].credentialVersion").value("v2"))
                .andExpect(jsonPath("$.dispatches[1].status").value("EXECUTING"));
    }

    @Test
    void dispatchHttpErrorsAndReplayConflict() throws Exception {
        reportAndTakeover("INC-710");
        createTask("INC-710", "T-1");
        registerCredential("R", "v1", "AWS_AK", "A", "2026-09-22T01:00:00Z");

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dispatches", "INC-710", "T-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-X\",\"resourceKey\":\"R\","
                                + "\"credentialVersion\":\"v1\",\"requiredType\":\"AWS_AK\","
                                + "\"region\":\"A\"}"))
                .andExpect(status().isBadRequest());
        // 类型不匹配 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dispatches", "INC-710", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-T\",\"resourceKey\":\"R\","
                                + "\"credentialVersion\":\"v1\",\"requiredType\":\"AZURE_SP\","
                                + "\"region\":\"A\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        // 区域不覆盖 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dispatches", "INC-710", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-R\",\"resourceKey\":\"R\","
                                + "\"credentialVersion\":\"v1\",\"requiredType\":\"AWS_AK\","
                                + "\"region\":\"B\"}"))
                .andExpect(status().isConflict());
        // 凭据不存在 → 404
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dispatches", "INC-710", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-N\",\"resourceKey\":\"R\","
                                + "\"credentialVersion\":\"v9\",\"requiredType\":\"AWS_AK\","
                                + "\"region\":\"A\"}"))
                .andExpect(status().isNotFound());
        // 合法派发
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dispatches", "INC-710", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-SAME\",\"resourceKey\":\"R\","
                                + "\"credentialVersion\":\"v1\",\"requiredType\":\"AWS_AK\","
                                + "\"region\":\"A\"}"))
                .andExpect(status().isOk());
        // 同 requestId 换资源 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dispatches", "INC-710", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-SAME\",\"resourceKey\":\"R2\","
                                + "\"credentialVersion\":\"v1\",\"requiredType\":\"AWS_AK\","
                                + "\"region\":\"A\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        // 未派发任务直接 start → 409
        createTask("INC-710", "T-2");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/start", "INC-710", "T-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict());
        // 凭据登记参数缺失 → 400
        mvc.perform(post("/api/resources/R/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"R\","
                                + "\"version\":\" \",\"credentialType\":\"T\",\"region\":\"A\","
                                + "\"expiresAt\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isBadRequest());
    }
}

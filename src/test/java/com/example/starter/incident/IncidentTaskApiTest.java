package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
 * 处置任务 HTTP 层测试：验证任务路由、X-Actor-Id 请求头约束、
 * 400/404/409 错误语义及 details 结构化明细（未解除阻塞事件、解决门禁未完成项），
 * 以及凭据登记/派发/执行前检查/阻断/重新派发的完整 HTTP 流程与 requestId 幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IncidentTaskApiTest {

    private static final String RESOURCE = "RES-R";
    private static final String REGION = "A";
    private static final String CRED_TYPE = "TOKEN";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM task_dispatches");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM resource_credentials");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private static String reqId() {
        return "REQ-" + UUID.randomUUID();
    }

    private static String iso(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant);
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

    private void createTask(String incidentKey, String actor, String taskKey,
                            String blockersJson) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"DB\",\"title\":\"扩容\","
                                + "\"resourceKey\":\"" + RESOURCE + "\",\"region\":\"" + REGION
                                + "\",\"blockerIncidentKeys\":" + blockersJson + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskKey").value(taskKey))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.resourceKey").value(RESOURCE))
                .andExpect(jsonPath("$.region").value(REGION));
    }

    private void registerCredential(String actor, String resourceKey, String credType,
                                    String region, Instant expiresAt) throws Exception {
        mvc.perform(post("/api/incidents/credentials")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\""
                                + resourceKey + "\",\"credentialType\":\"" + credType
                                + "\",\"region\":\"" + region + "\",\"expiresAt\":\""
                                + iso(expiresAt) + "\"}"))
                .andExpect(status().isCreated());
    }

    private void registerValidCredential(String actor) throws Exception {
        registerCredential(actor, RESOURCE, CRED_TYPE, REGION,
                Instant.now().plus(1, ChronoUnit.HOURS));
    }

    private void dispatch(String incidentKey, String taskKey, String actor,
                          String requestId, String resourceKey, String credType) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dispatch", incidentKey, taskKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"resourceKey\":\""
                                + resourceKey + "\",\"credentialType\":\"" + credType + "\"}"))
                .andExpect(status().isOk());
    }

    private void execute(String incidentKey, String taskKey, String actor) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/execute", incidentKey, taskKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
    }

    private void changeStatus(String incidentKey, String actor, String target) throws Exception {
        mvc.perform(post("/api/incidents/{k}/status", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\""
                                + target + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void taskHttpFlow() throws Exception {
        report("INC-500");
        report("INC-501");
        takeover("INC-500", "alice");
        takeover("INC-501", "bob");
        registerValidCredential("alice");

        // 创建任务（带阻塞事件）
        createTask("INC-500", "alice", "T-1", "[\"INC-501\"]");

        // 按事件分组查询
        mvc.perform(get("/api/incidents/{k}/tasks", "INC-500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidentKey").value("INC-500"))
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].blockers[0].incidentKey").value("INC-501"))
                .andExpect(jsonPath("$.tasks[0].blockers[0].resolved").value(false));

        // 单任务明细
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-500", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupCode").value("DB"))
                .andExpect(jsonPath("$.createdBy").value("alice"))
                .andExpect(jsonPath("$.credentialVersion").doesNotExist())
                .andExpect(jsonPath("$.dispatches.length()").value(0));

        // 未派发时执行 → 409；派发→执行
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/execute", "INC-500", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict());
        dispatch("INC-500", "T-1", "alice", reqId(), RESOURCE, CRED_TYPE);
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-500", "T-1"))
                .andExpect(jsonPath("$.status").value("DISPATCHED"))
                .andExpect(jsonPath("$.credentialVersion").value(1))
                .andExpect(jsonPath("$.dispatches.length()").value(1))
                .andExpect(jsonPath("$.dispatches[0].credentialVersion").value(1))
                .andExpect(jsonPath("$.dispatches[0].region").value(REGION));
        execute("INC-500", "T-1", "alice");

        // 阻塞未解除时完成 → 409 且 details 返回未解除事件列表
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-500", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.details[0]").value("INC-501"));

        // 阻塞事件遏制后完成成功
        changeStatus("INC-501", "bob", "CONTAINED");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-500", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.doneBy").value("alice"))
                .andExpect(jsonPath("$.blockers[0].resolved").value(true));
        // 历史派发记录保留当时区域与凭据版本
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-500", "T-1"))
                .andExpect(jsonPath("$.dispatches.length()").value(1))
                .andExpect(jsonPath("$.dispatches[0].credentialVersion").value(1));

        // 取消另一个任务
        createTask("INC-500", "alice", "T-2", "[]");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/cancel", "INC-500", "T-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value("alice"));
    }

    @Test
    void credentialDispatchRejectAndReplay() throws Exception {
        report("INC-530");
        takeover("INC-530", "alice");
        createTask("INC-530", "alice", "T-1", "[]");

        // 资源无凭据：派发 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dispatch", "INC-530", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + reqId() + "\",\"resourceKey\":\""
                                + RESOURCE + "\",\"credentialType\":\"" + CRED_TYPE + "\"}"))
                .andExpect(status().isConflict());

        // 登记凭据：类型不符 / 区域不符 / 已过期 各拒绝
        registerCredential("alice", RESOURCE, "OTHER", REGION,
                Instant.now().plus(1, ChronoUnit.HOURS));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dispatch", "INC-530", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + reqId() + "\",\"resourceKey\":\""
                                + RESOURCE + "\",\"credentialType\":\"" + CRED_TYPE + "\"}"))
                .andExpect(status().isConflict());
        registerCredential("alice", RESOURCE, CRED_TYPE, "B",
                Instant.now().plus(1, ChronoUnit.HOURS));
        registerCredential("alice", RESOURCE, CRED_TYPE, REGION,
                Instant.now().minus(1, ChronoUnit.MINUTES));
        // 最新版本(v3)已过期 → 409；登记 v4 有效后才派发成功
        registerCredential("alice", RESOURCE, CRED_TYPE, REGION,
                Instant.now().plus(1, ChronoUnit.HOURS));
        String firstRequest = reqId();
        dispatch("INC-530", "T-1", "alice", firstRequest, RESOURCE, CRED_TYPE);
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-530", "T-1"))
                .andExpect(jsonPath("$.status").value("DISPATCHED"))
                .andExpect(jsonPath("$.credentialVersion").value(4));

        // 同 requestId 重放：返回首次任务与凭据版本，不新增派发记录
        dispatch("INC-530", "T-1", "alice", firstRequest, RESOURCE, CRED_TYPE);
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-530", "T-1"))
                .andExpect(jsonPath("$.credentialVersion").value(4))
                .andExpect(jsonPath("$.dispatches.length()").value(1));
        // 同 requestId 换资源 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dispatch", "INC-530", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + firstRequest + "\",\"resourceKey\":\"RES-OTHER\""
                                + ",\"credentialType\":\"" + CRED_TYPE + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void taskHttpErrors() throws Exception {
        report("INC-510");
        takeover("INC-510", "alice");

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-510")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-1\","
                                + "\"groupCode\":\"G\",\"title\":\"t\"}"))
                .andExpect(status().isBadRequest());
        // groupCode 为空 → 400
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-510")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-1\","
                                + "\"groupCode\":\" \",\"title\":\"t\",\"resourceKey\":\""
                                + RESOURCE + "\",\"region\":\"" + REGION + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        // 非当前指挥人 → 409
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-510")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-1\","
                                + "\"groupCode\":\"G\",\"title\":\"t\",\"resourceKey\":\""
                                + RESOURCE + "\",\"region\":\"" + REGION + "\"}"))
                .andExpect(status().isConflict());
        // 事件不存在 → 404
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-404")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-1\","
                                + "\"groupCode\":\"G\",\"title\":\"t\",\"resourceKey\":\""
                                + RESOURCE + "\",\"region\":\"" + REGION + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents/{k}/tasks", "INC-404"))
                .andExpect(status().isNotFound());
        // 任务不存在 → 404
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-510", "T-9"))
                .andExpect(status().isNotFound());
    }

    @Test
    void taskHttpCycleAndResolveGate() throws Exception {
        report("INC-520");
        report("INC-521");
        takeover("INC-520", "alice");
        takeover("INC-521", "bob");
        createTask("INC-520", "alice", "T-1", "[\"INC-521\"]");

        // 反向依赖成环 → 409
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-521")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-1\","
                                + "\"groupCode\":\"G\",\"title\":\"t\",\"resourceKey\":\""
                                + RESOURCE + "\",\"region\":\"" + REGION + "\","
                                + "\"blockerIncidentKeys\":[\"INC-520\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 仍有 OPEN 任务时推进 RESOLVED → 409 且 details 按 groupCode、taskKey 返回
        changeStatus("INC-520", "alice", "CONTAINED");
        mvc.perform(post("/api/incidents/{k}/status", "INC-520")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"targetStatus\":\"RESOLVED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0].groupCode").value("DB"))
                .andExpect(jsonPath("$.details[0].taskKey").value("T-1"));

        // 取消任务后解决成功
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/cancel", "INC-520", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
        changeStatus("INC-520", "alice", "RESOLVED");
    }
}

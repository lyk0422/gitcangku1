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
 * 共享资源 HTTP 层测试：验证资源/租约/抢占/启动路由、X-Actor-Id 请求头约束、
 * 400/404/409/422 错误语义及 422 的 details 结构化明细（闭包缺失租约列表）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResourceApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM resource_leases");
        jdbc.update("DELETE FROM shared_resources");
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

    private void createResource(String resourceKey, int capacity) throws Exception {
        mvc.perform(post("/api/resources")
                        .header("X-Actor-Id", "ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceKey\":\"" + resourceKey + "\",\"capacity\":"
                                + capacity + "}"))
                .andExpect(status().isCreated());
    }

    private void commanding(String incidentKey, String severity, String commander)
            throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey + "\",\"severity\":\""
                                + severity + "\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", commander)
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
                                + "\",\"groupCode\":\"G\",\"title\":\"t\","
                                + "\"blockerIncidentKeys\":" + blockersJson + "}"))
                .andExpect(status().isOk());
    }

    private void requestLease(String incidentKey, String taskKey, String actor,
                              String resourceKey, String leaseKey, int units, long taskVersion)
            throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/leases", incidentKey, taskKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"resourceKey\":\""
                                + resourceKey + "\",\"leaseKey\":\"" + leaseKey
                                + "\",\"units\":" + units + ",\"taskVersion\":" + taskVersion
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseKey").value(leaseKey))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void resourceHttpFlow() throws Exception {
        // 创建资源：201 与占用视图
        mvc.perform(post("/api/resources")
                        .header("X-Actor-Id", "ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceKey\":\"RES-1\",\"capacity\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resourceKey").value("RES-1"))
                .andExpect(jsonPath("$.capacity").value(2))
                .andExpect(jsonPath("$.activeUnits").value(0))
                .andExpect(jsonPath("$.availableUnits").value(2));
        // 容量非法 → 400；缺 X-Actor-Id → 400；重复 resourceKey → 409
        mvc.perform(post("/api/resources")
                        .header("X-Actor-Id", "ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceKey\":\"RES-2\",\"capacity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(post("/api/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceKey\":\"RES-2\",\"capacity\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/resources")
                        .header("X-Actor-Id", "ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceKey\":\"RES-1\",\"capacity\":9}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        // 资源不存在 → 404
        mvc.perform(get("/api/resources/{k}", "RES-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void leaseAndStartHttpFlow() throws Exception {
        createResource("RES-L", 1);
        commanding("INC-L", "S2", "alice");
        createTask("INC-L", "alice", "T-1", "[]");

        // 无租约启动 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/start", "INC-L", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict());
        // 申请租约（任务版本 1）
        requestLease("INC-L", "T-1", "alice", "RES-L", "LK-1", 1, 1L);
        // 容量已满：第二个租约 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/leases", "INC-L", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"resourceKey\":\"RES-L\","
                                + "\"leaseKey\":\"LK-2\",\"units\":1,\"taskVersion\":1}"))
                .andExpect(status().isConflict());
        // 占用与历史查询
        mvc.perform(get("/api/resources/{k}", "RES-L"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeUnits").value(1))
                .andExpect(jsonPath("$.availableUnits").value(0))
                .andExpect(jsonPath("$.activeLeases[0].leaseKey").value("LK-1"));
        mvc.perform(get("/api/resources/{k}/leases", "RES-L"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceKey").value("RES-L"))
                .andExpect(jsonPath("$.leases.length()").value(1))
                .andExpect(jsonPath("$.leases[0].status").value("ACTIVE"));
        // 启动成功
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/start", "INC-L", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STARTED"))
                .andExpect(jsonPath("$.startedBy").value("alice"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void preemptHttpFlow_closure422AndSuccess() throws Exception {
        createResource("RES-P", 3);
        commanding("INC-B", "S3", "bob");
        commanding("INC-A", "S3", "alice");
        commanding("INC-H", "S1", "carol");
        createTask("INC-B", "bob", "TB", "[]");
        createTask("INC-A", "alice", "TA", "[\"INC-B\"]");
        createTask("INC-H", "carol", "TH", "[]");
        requestLease("INC-B", "TB", "bob", "RES-P", "LK-B", 1, 1L);
        requestLease("INC-A", "TA", "alice", "RES-P", "LK-A", 1, 1L);

        // 闭包查询（只读）：缺少 LK-A
        mvc.perform(get("/api/resources/{k}/preemption-closure", "RES-P")
                        .param("leases", "LK-B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listedVictims.length()").value(1))
                .andExpect(jsonPath("$.requiredAdditionalLeases[0].leaseKey").value("LK-A"))
                .andExpect(jsonPath("$.startedTasks.length()").value(0));

        // 计划未包含闭包租约 → 422，details 给出缺失列表
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/preempt", "INC-H", "TH")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"resourceKey\":\"RES-P\","
                                + "\"leaseKey\":\"LK-H\",\"units\":2,\"taskVersion\":1,"
                                + "\"victims\":[{\"leaseKey\":\"LK-B\",\"version\":1}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE_PLAN"))
                .andExpect(jsonPath("$.details[0]").value("LK-A"));

        // 完整闭包 → 200，原子撤销并授予
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/preempt", "INC-H", "TH")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"resourceKey\":\"RES-P\","
                                + "\"leaseKey\":\"LK-H\",\"units\":2,\"taskVersion\":1,"
                                + "\"victims\":[{\"leaseKey\":\"LK-B\",\"version\":1},"
                                + "{\"leaseKey\":\"LK-A\",\"version\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grantedLease.leaseKey").value("LK-H"))
                .andExpect(jsonPath("$.grantedLease.status").value("ACTIVE"))
                .andExpect(jsonPath("$.revokedVictims.length()").value(2))
                .andExpect(jsonPath("$.revokedVictims[0].status").value("REVOKED"));

        // 历史保留全部租约
        mvc.perform(get("/api/resources/{k}/leases", "RES-P"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leases.length()").value(3));
    }
}

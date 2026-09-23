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
 * 共享资源与租约 HTTP 层测试：验证资源/租约/开始/抢占/闭包/占用/历史路由、
 * X-Actor-Id 请求头约束及 400/404/409/422 错误语义。
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
        jdbc.update("DELETE FROM resource_leases");
        jdbc.update("DELETE FROM shared_resources");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void report(String k, String severity) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + k + "\",\"severity\":\"" + severity
                                + "\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
    }

    private void takeover(String k, String actor) throws Exception {
        mvc.perform(post("/api/incidents/{k}/takeover", k)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
    }

    private void task(String incident, String actor, String taskKey, String blockers) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks", incident)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"G\",\"title\":\"t\","
                                + "\"blockerIncidentKeys\":" + blockers + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void resourceHttpFlow() throws Exception {
        // 创建资源
        mvc.perform(post("/api/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceKey\":\"R-1\",\"name\":\"专线\",\"capacity\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resourceKey").value("R-1"))
                .andExpect(jsonPath("$.capacity").value(1))
                .andExpect(jsonPath("$.usedCapacity").value(0));

        // 容量非正 400
        mvc.perform(post("/api/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceKey\":\"R-2\",\"name\":\"x\",\"capacity\":0}"))
                .andExpect(status().isBadRequest());

        report("INC-V", "S2");
        takeover("INC-V", "victor");
        task("INC-V", "victor", "V-1", "[]");
        report("INC-H", "S1");
        takeover("INC-H", "hank");
        task("INC-H", "hank", "H-1", "[]");

        // 申请租约
        mvc.perform(post("/api/incidents/INC-V/tasks/V-1/leases")
                        .header("X-Actor-Id", "victor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"R-1\","
                                + "\"taskVersion\":0,\"quantity\":1,\"leaseKey\":\"L-V1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.incidentKey").value("INC-V"));

        // 占用查询
        mvc.perform(get("/api/resources/R-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource.usedCapacity").value(1))
                .andExpect(jsonPath("$.activeLeases[0].leaseKey").value("L-V1"));

        // 开始任务
        mvc.perform(post("/api/incidents/INC-V/tasks/V-1/start")
                        .header("X-Actor-Id", "victor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"leaseKeys\":[\"L-V1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STARTED"))
                .andExpect(jsonPath("$.version").value(1));

        // STARTED 受害租约不得抢占 → 422（ILLEGAL_TRANSITION 与 UNPROCESSABLE 均为 422）
        mvc.perform(post("/api/incidents/INC-H/preemptions")
                        .header("X-Actor-Id", "hank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-1\",\"taskKey\":\"H-1\",\"taskVersion\":0,"
                                + "\"newLeaseKey\":\"L-H1\",\"quantity\":1,\"victims\":["
                                + "{\"leaseKey\":\"L-V1\",\"version\":1}]}"))
                .andExpect(status().isUnprocessableEntity());

        // 历史只读
        mvc.perform(get("/api/resources/R-1/leases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void preemptClosureIncomplete_422_thenSuccess() throws Exception {
        mvc.perform(post("/api/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceKey\":\"R-1\",\"name\":\"n\",\"capacity\":2}"))
                .andExpect(status().isCreated());
        report("INC-V", "S2");
        takeover("INC-V", "victor");
        task("INC-V", "victor", "V-1", "[]");
        report("INC-M", "S2");
        takeover("INC-M", "mary");
        task("INC-M", "mary", "M-1", "[\"INC-V\"]");
        report("INC-H", "S1");
        takeover("INC-H", "hank");
        task("INC-H", "hank", "H-1", "[]");

        mvc.perform(post("/api/incidents/INC-V/tasks/V-1/leases")
                        .header("X-Actor-Id", "victor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"R-1\","
                                + "\"taskVersion\":0,\"quantity\":1,\"leaseKey\":\"L-V1\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/INC-M/tasks/M-1/leases")
                        .header("X-Actor-Id", "mary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"R-1\","
                                + "\"taskVersion\":0,\"quantity\":1,\"leaseKey\":\"L-M1\"}"))
                .andExpect(status().isOk());

        // 只读闭包返回两条
        mvc.perform(post("/api/incidents/INC-H/preemptions/closure")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"victimLeaseKeys\":[\"L-V1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.victims.length()").value(2));

        // 闭包不完整 422
        mvc.perform(post("/api/incidents/INC-H/preemptions")
                        .header("X-Actor-Id", "hank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-C\",\"taskKey\":\"H-1\",\"taskVersion\":0,"
                                + "\"newLeaseKey\":\"L-HC\",\"quantity\":1,\"victims\":["
                                + "{\"leaseKey\":\"L-V1\",\"version\":1}]}"))
                .andExpect(status().isUnprocessableEntity());

        // 补全闭包成功（同一 requestId，失败不占键）
        mvc.perform(post("/api/incidents/INC-H/preemptions")
                        .header("X-Actor-Id", "hank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-C\",\"taskKey\":\"H-1\",\"taskVersion\":0,"
                                + "\"newLeaseKey\":\"L-HC\",\"quantity\":1,\"victims\":["
                                + "{\"leaseKey\":\"L-V1\",\"version\":1},"
                                + "{\"leaseKey\":\"L-M1\",\"version\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked.length()").value(2))
                .andExpect(jsonPath("$.granted.leaseKey").value("L-HC"));

        // 占用只剩新租约
        mvc.perform(get("/api/resources/R-1"))
                .andExpect(jsonPath("$.resource.usedCapacity").value(1))
                .andExpect(jsonPath("$.activeLeases[0].leaseKey").value("L-HC"));
    }

    @Test
    void requiresActorHeader() throws Exception {
        mvc.perform(post("/api/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceKey\":\"R-9\",\"name\":\"n\",\"capacity\":1}"))
                .andExpect(status().isCreated());
        report("INC-A", "S2");
        takeover("INC-A", "alice");
        task("INC-A", "alice", "T-1", "[]");
        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/INC-A/tasks/T-1/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceKey\":\"R-9\","
                                + "\"taskVersion\":0,\"quantity\":1,\"leaseKey\":\"L-1\"}"))
                .andExpect(status().isBadRequest());
    }
}

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
 * 跨事件互助资源 HTTP 层测试：验证路由、X-Actor-Id 请求头约束、
 * 征调/归还/查询主流程及 400/404/409/422 错误语义、requestId 重放与改参冲突。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MutualAidApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM incident_resource_returns");
        jdbc.update("DELETE FROM incident_resource_leases");
        jdbc.update("DELETE FROM incident_resource_inventories");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "K-" + UUID.randomUUID();
    }

    private void report(String incidentKey) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey
                                + "\",\"severity\":\"S2\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
    }

    private void takeover(String incidentKey, String actor) throws Exception {
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
    }

    private void register(String incidentKey, String actor, String resourceCode, long qty)
            throws Exception {
        mvc.perform(post("/api/incidents/{k}/resources", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"resourceCode\":\""
                                + resourceCode + "\",\"quantity\":" + qty + "}"))
                .andExpect(status().isCreated());
    }

    @Test
    void apiMainFlow_requestReturnQuery() throws Exception {
        report("INC-A");
        report("INC-B");
        takeover("INC-A", "alice");
        takeover("INC-B", "bob");
        register("INC-A", "alice", "R", 5);

        // 征调 3 件：200，A 侧扣减在租约关联视图中可查
        mvc.perform(post("/api/incidents/{k}/resources/aid-requests", "INC-B")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-1\",\"sourceIncidentKey\":\"INC-A\","
                                + "\"resourceCode\":\"R\",\"quantity\":3,\"leaseMinutes\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("REQ-1"))
                .andExpect(jsonPath("$.sourceIncidentKey").value("INC-A"))
                .andExpect(jsonPath("$.targetIncidentKey").value("INC-B"))
                .andExpect(jsonPath("$.quantity").value(3))
                .andExpect(jsonPath("$.returnedQty").value(0))
                .andExpect(jsonPath("$.outstandingQty").value(3))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.closedAt").value(org.hamcrest.Matchers.nullValue()));

        // 归还 2
        mvc.perform(post("/api/incidents/{k}/resources/aid-requests/{r}/returns", "INC-B", "REQ-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnedQty").value(2))
                .andExpect(jsonPath("$.outstandingQty").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"));

        // 再归还 2 超过待还 1：422
        mvc.perform(post("/api/incidents/{k}/resources/aid-requests/{r}/returns", "INC-B", "REQ-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));

        // 结清
        mvc.perform(post("/api/incidents/{k}/resources/aid-requests/{r}/returns", "INC-B", "REQ-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").exists());

        // 关联查询：租约 + 来源台账（可用恢复 5）+ 2 条归还流水
        mvc.perform(get("/api/incidents/{k}/resources/aid-requests/{r}", "INC-B", "REQ-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lease.status").value("CLOSED"))
                .andExpect(jsonPath("$.sourceInventory.incidentKey").value("INC-A"))
                .andExpect(jsonPath("$.sourceInventory.availableQty").value(5))
                .andExpect(jsonPath("$.returns.length()").value(2))
                .andExpect(jsonPath("$.returns[0].quantity").value(2))
                .andExpect(jsonPath("$.returns[0].returnedBy").value("bob"))
                .andExpect(jsonPath("$.returns[1].quantity").value(1));

        // 列表：来源与目标事件各自能查到该历史租约
        mvc.perform(get("/api/incidents/{k}/resources/aid-requests", "INC-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidentKey").value("INC-A"))
                .andExpect(jsonPath("$.leases.length()").value(1))
                .andExpect(jsonPath("$.leases[0].requestId").value("REQ-1"));
    }

    @Test
    void apiFailures_insufficientOccupiedReplayConflict() throws Exception {
        report("INC-A");
        report("INC-B");
        takeover("INC-A", "alice");
        takeover("INC-B", "bob");
        register("INC-A", "alice", "R", 5);

        // 来源不足：征调 6 > 5，422
        mvc.perform(post("/api/incidents/{k}/resources/aid-requests", "INC-B")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-2\",\"sourceIncidentKey\":\"INC-A\","
                                + "\"resourceCode\":\"R\",\"quantity\":6,\"leaseMinutes\":60}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));

        // 失败不占键：同 requestId 合法数量 3 征调成功，A 账面可用 2
        mvc.perform(post("/api/incidents/{k}/resources/aid-requests", "INC-B")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-2\",\"sourceIncidentKey\":\"INC-A\","
                                + "\"resourceCode\":\"R\",\"quantity\":3,\"leaseMinutes\":60}"))
                .andExpect(status().isOk());

        // 同键同参重放：200 + 首次结果
        mvc.perform(post("/api/incidents/{k}/resources/aid-requests", "INC-B")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-2\",\"sourceIncidentKey\":\"INC-A\","
                                + "\"resourceCode\":\"R\",\"quantity\":3,\"leaseMinutes\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(3));

        // 同键改数量：409
        mvc.perform(post("/api/incidents/{k}/resources/aid-requests", "INC-B")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-2\",\"sourceIncidentKey\":\"INC-A\","
                                + "\"resourceCode\":\"R\",\"quantity\":1,\"leaseMinutes\":60}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 排他占用：R 账面仍有 2 件可用，但首笔 3 件未还完，再征 2 返回 422
        mvc.perform(post("/api/incidents/{k}/resources/aid-requests", "INC-B")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-3\",\"sourceIncidentKey\":\"INC-A\","
                                + "\"resourceCode\":\"R\",\"quantity\":2,\"leaseMinutes\":60}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));

        // 缺 X-Actor-Id：400；未知租约：404
        mvc.perform(post("/api/incidents/{k}/resources/aid-requests", "INC-B")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-4\",\"sourceIncidentKey\":\"INC-A\","
                                + "\"resourceCode\":\"R\",\"quantity\":1,\"leaseMinutes\":60}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/incidents/{k}/resources/aid-requests/{r}", "INC-B", "NO-SUCH"))
                .andExpect(status().isNotFound());
    }
}

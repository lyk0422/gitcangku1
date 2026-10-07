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
 * 指令计划 HTTP 层测试：验证路由、X-Actor-Id 请求头约束、
 * 400/403/404/409 错误语义及计划执行/取消/补偿的完整 HTTP 流程。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommandPlanApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM command_plan_compensations");
        jdbc.update("DELETE FROM command_plan_steps");
        jdbc.update("DELETE FROM command_plans");
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

    private void createPlan(String incidentKey, String actor, String planKey) throws Exception {
        mvc.perform(post("/api/incidents/{k}/plans", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"planKey\":\"" + planKey + "\","
                                + "\"steps\":["
                                + "{\"stepKey\":\"S1\",\"effectText\":\"效果1\",\"compensationText\":\"补偿1\"},"
                                + "{\"stepKey\":\"S2\",\"effectText\":\"效果2\",\"compensationText\":\"补偿2\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planKey").value(planKey))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.steps.length()").value(2));
    }

    private void execute(String incidentKey, String planKey, String stepKey, String actor,
                        long version) throws Exception {
        mvc.perform(post("/api/incidents/{k}/plans/{p}/steps/{s}/execute",
                        incidentKey, planKey, stepKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"expectedVersion\":" + version + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void planHttpFlow() throws Exception {
        reportAndTakeover("INC-600", "alice");
        createPlan("INC-600", "alice", "PLAN-1");
        execute("INC-600", "PLAN-1", "S1", "alice", 1L);

        // 查询明细
        mvc.perform(get("/api/incidents/{k}/plans/{p}", "INC-600", "PLAN-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.steps[1].status").value("PENDING"));
        // 列表
        mvc.perform(get("/api/incidents/{k}/plans", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans.length()").value(1))
                .andExpect(jsonPath("$.plans[0].planKey").value("PLAN-1"));

        // 取消 → COMPENSATING
        mvc.perform(post("/api/incidents/{k}/plans/{p}/cancel", "INC-600", "PLAN-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPENSATING"))
                .andExpect(jsonPath("$.cancelledBy").value("alice"));

        // 补偿 S1 → CANCELLED
        mvc.perform(post("/api/incidents/{k}/plans/{p}/steps/{s}/compensate",
                        "INC-600", "PLAN-1", "S1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key()
                                + "\",\"evidence\":\"补偿证据\",\"expectedVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.steps[0].status").value("COMPENSATED"))
                .andExpect(jsonPath("$.compensations[0].outcome").value("SUCCESS"));
    }

    @Test
    void planHttpErrors() throws Exception {
        reportAndTakeover("INC-610", "alice");
        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/plans", "INC-610")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"planKey\":\"P\","
                                + "\"steps\":[{\"stepKey\":\"S1\",\"effectText\":\"e\","
                                + "\"compensationText\":\"c\"}]}"))
                .andExpect(status().isBadRequest());
        // 非当前指挥人 → 403
        mvc.perform(post("/api/incidents/{k}/plans", "INC-610")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"planKey\":\"P\","
                                + "\"steps\":[{\"stepKey\":\"S1\",\"effectText\":\"e\","
                                + "\"compensationText\":\"c\"}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // 事件不存在 → 404
        mvc.perform(post("/api/incidents/{k}/plans", "INC-404")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"planKey\":\"P\","
                                + "\"steps\":[{\"stepKey\":\"S1\",\"effectText\":\"e\","
                                + "\"compensationText\":\"c\"}]}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents/{k}/plans", "INC-404"))
                .andExpect(status().isNotFound());
        // 计划不存在 → 404
        mvc.perform(get("/api/incidents/{k}/plans/{p}", "INC-610", "PLAN-9"))
                .andExpect(status().isNotFound());
    }
}

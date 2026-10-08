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
import org.springframework.test.web.servlet.MvcResult;

/**
 * 指令计划 HTTP 层测试：路由、X-Actor-Id/X-Request-Id 请求头、403 指挥权、
 * 409 跳步/状态冲突、幂等重放及只读查询均经过真实 MockMvc + H2 链路验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommandPlanApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_plan_compensations");
        jdbc.update("DELETE FROM command_plan_steps");
        jdbc.update("DELETE FROM command_plans");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "KEY-" + UUID.randomUUID();
    }

    private static String rid() {
        return "REQ-" + UUID.randomUUID();
    }

    private void reportAndTakeover(String incidentKey, String commander) throws Exception {
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

    private long createThreeStepPlan(String incidentKey, String commander) throws Exception {
        String body = "{\"commandKey\":\"" + key() + "\",\"steps\":["
                + "{\"stepKey\":\"S1\",\"effectDesc\":\"效果1\",\"compensationDesc\":\"补偿1\"},"
                + "{\"stepKey\":\"S2\",\"effectDesc\":\"效果2\",\"compensationDesc\":\"补偿2\"},"
                + "{\"stepKey\":\"S3\",\"effectDesc\":\"效果3\",\"compensationDesc\":\"补偿3\"}]}";
        MvcResult result = mvc.perform(post("/api/incidents/{k}/plans", incidentKey)
                        .header("X-Actor-Id", commander)
                        .header("X-Request-Id", rid())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.nextStepKey").value("S1"))
                .andExpect(jsonPath("$.steps.length()").value(3))
                .andReturn();
        String json = result.getResponse().getContentAsString();
        int planIdValue = objectMapper.readTree(json).get("planId").asInt();
        return Long.valueOf(planIdValue);
    }

    @Test
    void fullCompensationFlowOverHttp() throws Exception {
        String ik = "INC-HTTP-1";
        reportAndTakeover(ik, "alice");
        long planId = createThreeStepPlan(ik, "alice");

        executeExpect(ik, planId, "S1", "alice", true);
        executeExpect(ik, planId, "S2", "alice", true);
        // S2 之后 S3 为当前下一步，执行成功
        executeExpect(ik, planId, "S3", "alice", true);
        // 重复执行已成功的 S3：409
        executeExpect(ik, planId, "S3", "alice", false);

        // 取消：COMPLETED → COMPENSATING
        mvc.perform(post("/api/incidents/{k}/plans/{p}/cancel", ik, planId)
                        .header("X-Actor-Id", "alice").header("X-Request-Id", rid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPENSATING"))
                .andExpect(jsonPath("$.compensationStepKey").value("S3"));

        // 取消后执行旧的下一步 S3：409
        executeExpect(ik, planId, "S3", "alice", false);
        // 跳步补偿 S2：409
        compensate(ik, planId, "S2", "alice", "{\"commandKey\":\"" + key()
                + "\",\"evidence\":\"回滚S2\"}", false);
        // 补偿 S3 失败：仅诊断
        compensate(ik, planId, "S3", "alice", "{\"commandKey\":\"" + key()
                + "\",\"failureDiagnostic\":\"超时\"}", true);
        mvc.perform(get("/api/incidents/{k}/plans/{p}", ik, planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPENSATING"))
                .andExpect(jsonPath("$.steps[2].status").value("APPLIED"))
                .andExpect(jsonPath("$.steps[2].attempts[0].status").value("FAILED"))
                .andExpect(jsonPath("$.steps[2].attempts[0].failureDiagnostic").value("超时"));
        // 换新键补偿 S3 成功
        compensate(ik, planId, "S3", "alice", "{\"commandKey\":\"" + key()
                + "\",\"evidence\":\"回滚S3\"}", true);
        // S2、S1 依次成功
        compensate(ik, planId, "S2", "alice", "{\"commandKey\":\"" + key()
                + "\",\"evidence\":\"回滚S2\"}", true);
        mvc.perform(post("/api/incidents/{k}/plans/{p}/steps/{s}/compensate", ik, planId, "S1")
                        .header("X-Actor-Id", "alice").header("X-Request-Id", rid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"evidence\":\"回滚S1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.compensationStepKey").value(org.hamcrest.Matchers.nullValue()));

        // 列表只读
        mvc.perform(get("/api/incidents/{k}/plans", ik))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("CANCELLED"));
    }

    private void executeExpect(String ik, long planId, String stepKey, String actor, boolean ok)
            throws Exception {
        var req = mvc.perform(post("/api/incidents/{k}/plans/{p}/steps/{s}/execute", ik, planId,
                        stepKey)
                        .header("X-Actor-Id", actor).header("X-Request-Id", rid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"expectedVersion\":null}"));
        req.andExpect(ok ? status().isOk() : status().isConflict());
    }

    private void compensate(String ik, long planId, String stepKey, String actor, String body,
                            boolean ok) throws Exception {
        mvc.perform(post("/api/incidents/{k}/plans/{p}/steps/{s}/compensate", ik, planId, stepKey)
                        .header("X-Actor-Id", actor).header("X-Request-Id", rid())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(ok ? status().isOk() : status().isConflict());
    }

    @Test
    void missingHeadersAndNonCommander_rejected() throws Exception {
        String ik = "INC-HTTP-2";
        reportAndTakeover(ik, "alice");
        String body = "{\"commandKey\":\"" + key() + "\",\"steps\":["
                + "{\"stepKey\":\"S1\",\"effectDesc\":\"e\",\"compensationDesc\":\"c\"}]}";
        // 缺 X-Request-Id：400
        mvc.perform(post("/api/incidents/{k}/plans", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        // 非当前指挥人：403
        mvc.perform(post("/api/incidents/{k}/plans", ik)
                        .header("X-Actor-Id", "bob").header("X-Request-Id", rid())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void idempotentReplaySameKey_differentParamConflicts() throws Exception {
        String ik = "INC-HTTP-3";
        reportAndTakeover(ik, "alice");
        long planId = createThreeStepPlan(ik, "alice");
        String commandKey = key();
        String payload = "{\"commandKey\":\"" + commandKey + "\",\"expectedVersion\":null}";
        String first = mvc.perform(post("/api/incidents/{k}/plans/{p}/steps/{s}/execute",
                        ik, planId, "S1")
                        .header("X-Actor-Id", "alice").header("X-Request-Id", "REQ-1")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // 同键同参重放
        mvc.perform(post("/api/incidents/{k}/plans/{p}/steps/{s}/execute", ik, planId, "S1")
                        .header("X-Actor-Id", "alice").header("X-Request-Id", "REQ-1")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().json(first));
        // 同键换操作者（异参）409
        mvc.perform(post("/api/incidents/{k}/plans/{p}/steps/{s}/execute", ik, planId, "S1")
                        .header("X-Actor-Id", "alice").header("X-Request-Id", "REQ-2")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isConflict());
    }

    @Test
    void compensatingPlan_blocksResolve() throws Exception {
        String ik = "INC-HTTP-4";
        reportAndTakeover(ik, "alice");
        long planId = createThreeStepPlan(ik, "alice");
        executeExpect(ik, planId, "S1", "alice", true);
        mvc.perform(post("/api/incidents/{k}/plans/{p}/cancel", ik, planId)
                        .header("X-Actor-Id", "alice").header("X-Request-Id", rid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPENSATING"));
        // COMMANDING → CONTAINED 允许；CONTAINED → RESOLVED 被 COMPENSATING 阻止
        mvc.perform(post("/api/incidents/{k}/status", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\"CONTAINED\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/status", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\"RESOLVED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0].status").value("COMPENSATING"));
    }
}

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
 * 残余风险模型 HTTP 层测试：验证风险模型路由、X-Actor-Id 请求头约束、
 * JSON 结构（精确残余分数、冻结快照）及 400/404/409/422 错误语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IncidentRiskApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_containment_snapshots");
        jdbc.update("DELETE FROM incident_risk_barriers");
        jdbc.update("DELETE FROM incident_risk_models");
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

    private void setupModel(String incidentKey, String actor, int r, int l) throws Exception {
        mvc.perform(post("/api/incidents/{k}/risk-model", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"initialRisk\":" + r
                                + ",\"residualLimit\":" + l + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialRisk").value(r))
                .andExpect(jsonPath("$.residualLimit").value(l))
                .andExpect(jsonPath("$.version").value(0));
    }

    private void createTask(String incidentKey, String actor, String taskKey) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"G\",\"title\":\"t\"}"))
                .andExpect(status().isOk());
    }

    private void registerBarrier(String incidentKey, String actor, String barrierKey,
                                 String taskKey, int p, int q) throws Exception {
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"barrierKey\":\"" + barrierKey
                                + "\",\"taskKey\":\"" + taskKey + "\",\"numerator\":" + p
                                + ",\"denominator\":" + q + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.barriers[0].barrierKey").value(barrierKey))
                .andExpect(jsonPath("$.barriers[0].effective").value(false));
    }

    @Test
    void riskModelHttpFlow() throws Exception {
        reportAndTakeover("INC-600", "alice");
        setupModel("INC-600", "alice", 100, 20);
        createTask("INC-600", "alice", "T-1");
        registerBarrier("INC-600", "alice", "B-1", "T-1", 4, 5);

        // 任务未完成：残余仍为 R=100 > 20，不可遏制
        mvc.perform(get("/api/incidents/{k}/risk-model", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.residualNumerator").value(100))
                .andExpect(jsonPath("$.residualDenominator").value(1))
                .andExpect(jsonPath("$.containable").value(false))
                .andExpect(jsonPath("$.containmentSnapshot.length()").value(0));

        // 遏制尝试 → 422
        mvc.perform(post("/api/incidents/{k}/status", "INC-600")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"targetStatus\":\"CONTAINED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));

        // 完成任务后残余 100×1/5 = 20 ≤ 20，遏制成功并冻结快照
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-600", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/status", "INC-600")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"targetStatus\":\"CONTAINED\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/incidents/{k}/risk-model", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.residualNumerator").value(20))
                .andExpect(jsonPath("$.containable").value(true))
                .andExpect(jsonPath("$.containmentSnapshot.length()").value(1))
                .andExpect(jsonPath("$.containmentSnapshot[0].barrierKey").value("B-1"))
                .andExpect(jsonPath("$.containmentSnapshot[0].taskStatus").value("DONE"))
                .andExpect(jsonPath("$.containmentSnapshot[0].numerator").value(4))
                .andExpect(jsonPath("$.containmentSnapshot[0].denominator").value(5));

        // CONTAINED 后撤销该屏障（后态残余 100 超限）→ 409
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers/{b}/revoke", "INC-600", "B-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void riskModelHttpErrors() throws Exception {
        reportAndTakeover("INC-610", "alice");

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/risk-model", "INC-610")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"initialRisk\":100,\"residualLimit\":20}"))
                .andExpect(status().isBadRequest());
        // 事件不存在 → 404
        mvc.perform(post("/api/incidents/{k}/risk-model", "INC-404")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"initialRisk\":100,\"residualLimit\":20}"))
                .andExpect(status().isNotFound());
        // 模型未设置时查询 → 404
        mvc.perform(get("/api/incidents/{k}/risk-model", "INC-610"))
                .andExpect(status().isNotFound());
        // L 不小于 R → 400
        mvc.perform(post("/api/incidents/{k}/risk-model", "INC-610")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"initialRisk\":100,\"residualLimit\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        setupModel("INC-610", "alice", 100, 20);
        createTask("INC-610", "alice", "T-1");
        registerBarrier("INC-610", "alice", "B-1", "T-1", 1, 2);

        // 屏障不存在 → 404；expectedVersion 不匹配 → 409
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers/{b}", "INC-610", "B-9")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-1\","
                                + "\"numerator\":1,\"denominator\":2,\"expectedVersion\":0}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers/{b}", "INC-610", "B-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-1\","
                                + "\"numerator\":1,\"denominator\":2,\"expectedVersion\":7}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        // 非当前指挥人 → 409
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers/{b}/revoke", "INC-610", "B-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict());
    }
}

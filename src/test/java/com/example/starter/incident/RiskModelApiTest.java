package com.example.starter.incident;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
 * 残余风险模型 HTTP 层测试（合成模型，真实 H2 事务）。
 * 合成场景：R=100、L=20，两条各削减 1/2 的屏障残余 25 拒绝遏制；
 * 完成削减 1/5 的屏障任务后残余 20 允许；撤销该屏障后拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RiskModelApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_containment_barriers");
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
        return "REQ-" + UUID.randomUUID();
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
                                + "\",\"groupCode\":\"G\",\"title\":\"t\"}"))
                .andExpect(status().isOk());
    }

    private void completeTask(String incidentKey, String actor, String taskKey) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", incidentKey, taskKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    private void initModel(String incidentKey, String actor, String requestId, int r, int l)
            throws Exception {
        mvc.perform(put("/api/incidents/{k}/risk-model", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"initialRisk\":" + r
                                + ",\"residualLimit\":" + l + "}"))
                .andExpect(status().isOk());
    }

    private void registerBarrier(String incidentKey, String actor, String barrierKey,
                                 String taskKey, int p, int q) throws Exception {
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"barrierKey\":\"" + barrierKey
                                + "\",\"taskKey\":\"" + taskKey + "\",\"reduceP\":" + p
                                + ",\"reduceQ\":" + q + "}"))
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

    /**
     * 合成场景准备：R=100、L=20；屏障登记时任务均 OPEN，登记后完成 T-A/T-B，
     * T-C 仍 OPEN。准备完成后模型版本为 4（初始化 1 + 三次登记）。
     */
    private void prepareSynthetic(String incidentKey) throws Exception {
        report(incidentKey);
        takeover(incidentKey, "alice");
        createTask(incidentKey, "alice", "T-A");
        createTask(incidentKey, "alice", "T-B");
        createTask(incidentKey, "alice", "T-C");
        initModel(incidentKey, "alice", key(), 100, 20);
        registerBarrier(incidentKey, "alice", "B-A", "T-A", 1, 2);
        registerBarrier(incidentKey, "alice", "B-B", "T-B", 1, 2);
        registerBarrier(incidentKey, "alice", "B-C", "T-C", 1, 5);
        completeTask(incidentKey, "alice", "T-A");
        completeTask(incidentKey, "alice", "T-B");
    }

    @Test
    void syntheticContainmentFlow() throws Exception {
        prepareSynthetic("RISK-100");

        // 两条 1/2 屏障生效：残余 100×1/2×1/2 = 25 > 20，拒绝遏制
        mvc.perform(get("/api/incidents/{k}/risk-model", "RISK-100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialRisk").value(100))
                .andExpect(jsonPath("$.residualLimit").value(20))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.residual").value("25/1"))
                .andExpect(jsonPath("$.residualWithinLimit").value(false))
                .andExpect(jsonPath("$.barriers.length()").value(3))
                .andExpect(jsonPath("$.barriers[0].effective").value(true))
                .andExpect(jsonPath("$.barriers[2].effective").value(false))
                .andExpect(jsonPath("$.containment").doesNotExist());
        mvc.perform(post("/api/incidents/{k}/status", "RISK-100")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"targetStatus\":\"CONTAINED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RESIDUAL_RISK_EXCEEDED"));

        // 完成削减 1/5 的屏障任务：残余 100×1/2×1/2×4/5 = 20 <= 20，允许遏制
        completeTask("RISK-100", "alice", "T-C");
        mvc.perform(get("/api/incidents/{k}/risk-model", "RISK-100"))
                .andExpect(jsonPath("$.residual").value("20/1"))
                .andExpect(jsonPath("$.residualWithinLimit").value(true));
        changeStatus("RISK-100", "alice", "CONTAINED");

        // 遏制冻结：有效屏障身份、比率与任务状态定格为决策依据
        mvc.perform(get("/api/incidents/{k}/risk-model", "RISK-100"))
                .andExpect(jsonPath("$.containment.residual").value("20/1"))
                .andExpect(jsonPath("$.containment.barriers.length()").value(3))
                .andExpect(jsonPath("$.containment.barriers[2].barrierKey").value("B-C"))
                .andExpect(jsonPath("$.containment.barriers[2].taskStatus").value("DONE"))
                .andExpect(jsonPath("$.containment.barriers[2].reduceP").value(1))
                .andExpect(jsonPath("$.containment.barriers[2].reduceQ").value(5));
    }

    @Test
    void revokeBarrierBeforeContainmentRejects() throws Exception {
        prepareSynthetic("RISK-101");

        // 撤销 1/5 屏障（版本 4 → 5）：残余回到 25，即使任务完成也不计入
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers/{b}/revoke", "RISK-101", "B-C")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"expectedVersion\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.residual").value("25/1"))
                .andExpect(jsonPath("$.barriers[2].revoked").value(true));
        completeTask("RISK-101", "alice", "T-C");
        mvc.perform(get("/api/incidents/{k}/risk-model", "RISK-101"))
                .andExpect(jsonPath("$.residual").value("25/1"))
                .andExpect(jsonPath("$.barriers[2].effective").value(false));
        mvc.perform(post("/api/incidents/{k}/status", "RISK-101")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"targetStatus\":\"CONTAINED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RESIDUAL_RISK_EXCEEDED"));
    }

    @Test
    void containedWeakeningConflictAndFrozenBasis() throws Exception {
        prepareSynthetic("RISK-102");
        completeTask("RISK-102", "alice", "T-C");
        changeStatus("RISK-102", "alice", "CONTAINED");

        // 已遏制：撤销 B-A 后态残余 100×1/2×4/5 = 40 > 20 → 409
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers/{b}/revoke", "RISK-102", "B-A")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"expectedVersion\":4}"))
                .andExpect(status().isConflict());
        // 修改 B-A 为 2/5：后态 100×3/5×1/2×4/5 = 24 > 20 → 409
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers/{b}", "RISK-102", "B-A")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"reduceP\":2,\"reduceQ\":5,"
                                + "\"expectedVersion\":4}"))
                .andExpect(status().isConflict());
        // 加强 B-A 为 3/5：后态 100×2/5×1/2×4/5 = 16 <= 20 → 200，版本加一
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers/{b}", "RISK-102", "B-A")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"reduceP\":3,\"reduceQ\":5,"
                                + "\"expectedVersion\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.residual").value("16/1"));
        // 合法加强不回改遏制冻结依据
        mvc.perform(get("/api/incidents/{k}/risk-model", "RISK-102"))
                .andExpect(jsonPath("$.containment.residual").value("20/1"))
                .andExpect(jsonPath("$.containment.barriers[0].reduceP").value(1))
                .andExpect(jsonPath("$.containment.barriers[0].reduceQ").value(2));
    }

    @Test
    void versionConflictAndIdempotentReplay() throws Exception {
        report("RISK-103");
        takeover("RISK-103", "alice");
        createTask("RISK-103", "alice", "T-A");
        initModel("RISK-103", "alice", key(), 100, 20);

        String requestId = key();
        String body = "{\"requestId\":\"" + requestId + "\",\"barrierKey\":\"B-A\","
                + "\"taskKey\":\"T-A\",\"reduceP\":1,\"reduceQ\":2}";
        String first = mvc.perform(post("/api/incidents/{k}/risk-model/barriers", "RISK-103")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        completeTask("RISK-103", "alice", "T-A");

        // 期望版本不匹配 → 409
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers/{b}", "RISK-103", "B-A")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"reduceP\":1,\"reduceQ\":3,"
                                + "\"expectedVersion\":1}"))
                .andExpect(status().isConflict());
        // 同 requestId 同参重放 → 返回首次完整结果（版本 2 的视图）
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers", "RISK-103")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(content().json(first))
                .andExpect(jsonPath("$.version").value(2));
        // 同 requestId 改参 → 409
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers", "RISK-103")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"barrierKey\":\"B-A\","
                                + "\"taskKey\":\"T-A\",\"reduceP\":1,\"reduceQ\":3}"))
                .andExpect(status().isConflict());
        // 携带正确期望版本修改成功，版本加一
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers/{b}", "RISK-103", "B-A")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"reduceP\":1,\"reduceQ\":3,"
                                + "\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.residual").value("200/3"));
    }

    @Test
    void permissionCheckedBeforeReplay() throws Exception {
        report("RISK-104");
        takeover("RISK-104", "alice");
        String requestId = key();
        String body = "{\"requestId\":\"" + requestId
                + "\",\"initialRisk\":100,\"residualLimit\":20}";
        mvc.perform(put("/api/incidents/{k}/risk-model", "RISK-104")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        // 交接给 bob 后，旧指挥人重放同 requestId 同参：权限先于重放，返回 409 而非首次结果
        mvc.perform(post("/api/incidents/{k}/transfers", "RISK-104")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"toCommander\":\"bob\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/transfers/accept", "RISK-104")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/incidents/{k}/risk-model", "RISK-104")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("指挥人")));
    }

    @Test
    void closedRejectsNewConfig() throws Exception {
        prepareSynthetic("RISK-105");
        completeTask("RISK-105", "alice", "T-C");
        changeStatus("RISK-105", "alice", "CONTAINED");
        changeStatus("RISK-105", "alice", "RESOLVED");
        changeStatus("RISK-105", "alice", "CLOSED");

        // 已 CLOSED：不接受新配置（登记与撤销均 422）
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers", "RISK-105")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"barrierKey\":\"B-D\","
                                + "\"taskKey\":\"T-C\",\"reduceP\":1,\"reduceQ\":2}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers/{b}/revoke", "RISK-105", "B-A")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"expectedVersion\":4}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void validationAndStateErrors() throws Exception {
        report("RISK-106");
        takeover("RISK-106", "alice");
        createTask("RISK-106", "alice", "T-A");
        completeTask("RISK-106", "alice", "T-A");
        createTask("RISK-106", "alice", "T-B");

        // 400：R 越界、L 不小于 R、缺 requestId、空白操作者
        mvc.perform(put("/api/incidents/{k}/risk-model", "RISK-106")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key()
                                + "\",\"initialRisk\":0,\"residualLimit\":20}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/incidents/{k}/risk-model", "RISK-106")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key()
                                + "\",\"initialRisk\":100,\"residualLimit\":100}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/incidents/{k}/risk-model", "RISK-106")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"initialRisk\":100,\"residualLimit\":20}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/incidents/{k}/risk-model", "RISK-106")
                        .header("X-Actor-Id", " ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key()
                                + "\",\"initialRisk\":100,\"residualLimit\":20}"))
                .andExpect(status().isBadRequest());
        // 409：非当前指挥人
        mvc.perform(put("/api/incidents/{k}/risk-model", "RISK-106")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key()
                                + "\",\"initialRisk\":100,\"residualLimit\":20}"))
                .andExpect(status().isConflict());
        // 未初始化：登记 409、查询 404
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers", "RISK-106")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"barrierKey\":\"B-1\","
                                + "\"taskKey\":\"T-B\",\"reduceP\":1,\"reduceQ\":2}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/incidents/{k}/risk-model", "RISK-106"))
                .andExpect(status().isNotFound());

        initModel("RISK-106", "alice", key(), 100, 20);
        // 400：p>q、q=0
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers", "RISK-106")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"barrierKey\":\"B-1\","
                                + "\"taskKey\":\"T-B\",\"reduceP\":3,\"reduceQ\":2}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers", "RISK-106")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"barrierKey\":\"B-1\","
                                + "\"taskKey\":\"T-B\",\"reduceP\":0,\"reduceQ\":0}"))
                .andExpect(status().isBadRequest());
        // 409：绑定非 OPEN 任务；404：任务不存在
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers", "RISK-106")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"barrierKey\":\"B-1\","
                                + "\"taskKey\":\"T-A\",\"reduceP\":1,\"reduceQ\":2}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers", "RISK-106")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"barrierKey\":\"B-1\","
                                + "\"taskKey\":\"T-404\",\"reduceP\":1,\"reduceQ\":2}"))
                .andExpect(status().isNotFound());
        // 重复 barrierKey → 409；未撤销屏障上限 5 个
        registerBarrier("RISK-106", "alice", "B-1", "T-B", 1, 2);
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers", "RISK-106")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"barrierKey\":\"B-1\","
                                + "\"taskKey\":\"T-B\",\"reduceP\":1,\"reduceQ\":3}"))
                .andExpect(status().isConflict());
        registerBarrier("RISK-106", "alice", "B-2", "T-B", 1, 2);
        registerBarrier("RISK-106", "alice", "B-3", "T-B", 1, 2);
        registerBarrier("RISK-106", "alice", "B-4", "T-B", 1, 2);
        registerBarrier("RISK-106", "alice", "B-5", "T-B", 1, 2);
        mvc.perform(post("/api/incidents/{k}/risk-model/barriers", "RISK-106")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"barrierKey\":\"B-6\","
                                + "\"taskKey\":\"T-B\",\"reduceP\":1,\"reduceQ\":2}"))
                .andExpect(status().isConflict());
    }
}

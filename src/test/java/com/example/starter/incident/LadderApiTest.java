package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * 升级阶梯 HTTP 层测试：验证路由、X-Actor-Id 约束、JSON 结构、
 * 400/404/409/422 错误语义与 commandKey 重放；时间推进使用可控 Clock。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class LadderApiTest {

    private static final Instant T0 = Instant.parse("2026-09-30T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_ladder_history");
        jdbc.update("DELETE FROM incident_ladder_states");
        jdbc.update("DELETE FROM incident_ladder_configs");
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

    private void reportAndTakeover(String incidentKey) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey + "\",\"severity\":\"S2\","
                                + "\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
    }

    private void createDefaultConfig() throws Exception {
        mvc.perform(post("/api/incidents/ladder-configs")
                        .header("X-Actor-Id", "ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\","
                                + "\"thresholds\":[10,20,40,70,100],\"cooldownMinutes\":30}"))
                .andExpect(status().isCreated());
    }

    @Test
    void ladderHttpFlow() throws Exception {
        // 创建配置：201；缺 X-Actor-Id → 400；阈值非严格递增 → 422
        mvc.perform(post("/api/incidents/ladder-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\","
                                + "\"thresholds\":[10,20,40,70,100],\"cooldownMinutes\":30}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/incidents/ladder-configs")
                        .header("X-Actor-Id", "ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\","
                                + "\"thresholds\":[10,5,40,70,100],\"cooldownMinutes\":30}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"));
        createDefaultConfig();
        mvc.perform(get("/api/incidents/ladder-configs/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configVersion").value(1))
                .andExpect(jsonPath("$.cooldownMinutes").value(30));

        // 批量分数更新：自动提升至 3 级
        reportAndTakeover("INC-A-1");
        mvc.perform(post("/api/incidents/ladder/scores")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"updates\":["
                                + "{\"incidentKey\":\"INC-A-1\",\"score\":45}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configVersion").value(1))
                .andExpect(jsonPath("$.results[0].incidentKey").value("INC-A-1"))
                .andExpect(jsonPath("$.results[0].previousLevel").value(0))
                .andExpect(jsonPath("$.results[0].currentLevel").value(3))
                .andExpect(jsonPath("$.results[0].escalated").value(true))
                .andExpect(jsonPath("$.results[0].nextLevelThreshold").value(70))
                .andExpect(jsonPath("$.results[0].gapToNextLevel").value(25));

        // 状态与历史查询（只读）
        mvc.perform(get("/api/incidents/{k}/ladder", "INC-A-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskScore").value(45))
                .andExpect(jsonPath("$.currentLevel").value(3))
                .andExpect(jsonPath("$.lastOperator").value("alice"));
        mvc.perform(get("/api/incidents/{k}/ladder/history", "INC-A-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].changeType").value("ESCALATE"))
                .andExpect(jsonPath("$.entries[0].toLevel").value(3));

        // 冷却内降级 → 409 附差额明细；冷却过后由不同指挥员降级 → 200
        ((ControllableClock) clock).setInstant(T0.plus(10, ChronoUnit.MINUTES));
        mvc.perform(post("/api/incidents/{k}/ladder/demote", "INC-A-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.requiredMinutes").value(30))
                .andExpect(jsonPath("$.details.elapsedMinutes").value(10))
                .andExpect(jsonPath("$.details.remainingMinutes").value(20));
        ((ControllableClock) clock).setInstant(T0.plus(30, ChronoUnit.MINUTES));
        String demoteKey = key();
        String demoteBody = "{\"commandKey\":\"" + demoteKey + "\",\"targetLevel\":2}";
        String first = mvc.perform(post("/api/incidents/{k}/ladder/demote", "INC-A-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON).content(demoteBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentLevel").value(2))
                .andExpect(jsonPath("$.lastOperator").value("bob"))
                .andReturn().getResponse().getContentAsString();
        // 同键同参重放 → 首次完整响应快照
        mvc.perform(post("/api/incidents/{k}/ladder/demote", "INC-A-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON).content(demoteBody))
                .andExpect(status().isOk())
                .andExpect(content().json(first));
        // 同键改参 → 409
        mvc.perform(post("/api/incidents/{k}/ladder/demote", "INC-A-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + demoteKey + "\",\"targetLevel\":1}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/incidents/{k}/ladder/history", "INC-A-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[1].changeType").value("DEESCALATE"))
                .andExpect(jsonPath("$.entries[1].toLevel").value(2));
    }

    @Test
    void ladderHttpErrorsAndBatchRollback() throws Exception {
        createDefaultConfig();
        reportAndTakeover("INC-A-2");
        reportAndTakeover("INC-A-3");
        // 关闭 INC-A-3
        for (String target : new String[]{"CONTAINED", "RESOLVED", "CLOSED"}) {
            mvc.perform(post("/api/incidents/{k}/status", "INC-A-3")
                            .header("X-Actor-Id", "alice")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\""
                                    + target + "\"}"))
                    .andExpect(status().isOk());
        }
        // 批量含已关闭事件 → 422，整批回滚
        mvc.perform(post("/api/incidents/ladder/scores")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"updates\":["
                                + "{\"incidentKey\":\"INC-A-2\",\"score\":50},"
                                + "{\"incidentKey\":\"INC-A-3\",\"score\":60}]}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/incidents/{k}/ladder", "INC-A-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskScore").value(org.hamcrest.Matchers.nullValue()));
        // 分数缺失 → 422；事件不存在 → 404；查询不存在事件 → 404
        mvc.perform(post("/api/incidents/ladder/scores")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"updates\":["
                                + "{\"incidentKey\":\"INC-A-2\"}]}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/incidents/ladder/scores")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"updates\":["
                                + "{\"incidentKey\":\"INC-404\",\"score\":10}]}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents/{k}/ladder", "INC-404"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents/{k}/ladder/history", "INC-404"))
                .andExpect(status().isNotFound());
        // 缺 commandKey → 400
        mvc.perform(post("/api/incidents/ladder/scores")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"updates\":[{\"incidentKey\":\"INC-A-2\",\"score\":10}]}"))
                .andExpect(status().isBadRequest());
    }
}

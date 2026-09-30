package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
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
 * 升级阶梯 HTTP 层测试：阈值版本、批量分数、降级与只读查询路由；
 * X-Actor-Id 约束、422/409/404 错误结构与 commandKey 幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class LadderApiTest {

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
        jdbc.update("DELETE FROM incident_ladder_scores");
        jdbc.update("DELETE FROM incident_ladder_states");
        jdbc.update("DELETE FROM ladder_versions");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-30T00:00:00Z"));
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    @Test
    void ladderHttpFlow() throws Exception {
        // 创建阈值版本：冷却 0 便于降级
        mvc.perform(post("/api/ladder/versions")
                        .header("X-Actor-Id", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"versionKey\":\"V1\","
                                + "\"thresholds\":[10,20,30,40,50],\"cooldownMinutes\":0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionKey").value("V1"))
                .andExpect(jsonPath("$.thresholds[4]").value(50));

        // 阈值不严格递增 → 422；冷却非法 → 422
        mvc.perform(post("/api/ladder/versions")
                        .header("X-Actor-Id", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"versionKey\":\"BAD\","
                                + "\"thresholds\":[10,20,20,40,50],\"cooldownMinutes\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_THRESHOLDS"));
        mvc.perform(post("/api/ladder/versions")
                        .header("X-Actor-Id", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"versionKey\":\"BAD2\","
                                + "\"thresholds\":[10,20,30,40,50],\"cooldownMinutes\":99999}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_COOLDOWN"));
        // 缺 X-Actor-Id → 400
        mvc.perform(post("/api/ladder/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"versionKey\":\"BAD3\","
                                + "\"thresholds\":[10,20,30,40,50],\"cooldownMinutes\":0}"))
                .andExpect(status().isBadRequest());

        // 上报并接管事件
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"INC-HTTP-1\",\"severity\":\"S2\",\"summary\":\"s\","
                                + "\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/INC-HTTP-1/takeover")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());

        // 初始阶梯视图：未进入
        mvc.perform(get("/api/incidents/INC-HTTP-1/ladder"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onLadder").value(false))
                .andExpect(jsonPath("$.currentLevel").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.history.length()").value(0))
                .andExpect(jsonPath("$.scores.length()").value(0));

        // 批量分数：80 → 5 级（跨级），响应按键排序并给出实际/要求/差额
        mvc.perform(post("/api/ladder/scores")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"thresholdVersionKey\":\"V1\","
                                + "\"updates\":[{\"incidentKey\":\"INC-HTTP-1\",\"score\":80}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].actualLevel").value(5))
                .andExpect(jsonPath("$.results[0].requiredLevel").value(5))
                .andExpect(jsonPath("$.results[0].levelDelta").value(0))
                .andExpect(jsonPath("$.results[0].changed").value(true));
        mvc.perform(get("/api/incidents/INC-HTTP-1/ladder"))
                .andExpect(jsonPath("$.currentLevel").value(5))
                .andExpect(jsonPath("$.demoteReady").value(true))
                .andExpect(jsonPath("$.history[0].changeType").value("PROMOTE"))
                .andExpect(jsonPath("$.history[0].toLevel").value(5))
                .andExpect(jsonPath("$.scores[0].evaluatedLevel").value(5));

        // 当前指挥人本人降级 → 409；不同指挥员降级成功（逐级到 4）
        mvc.perform(post("/api/incidents/INC-HTTP-1/ladder/demote")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/incidents/INC-HTTP-1/ladder/demote")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentLevel").value(4))
                .andExpect(jsonPath("$.history[1].changeType").value("DEMOTE"))
                .andExpect(jsonPath("$.history[1].operator").value("bob"));

        // 未知事件查询 → 404；版本列表只读
        mvc.perform(get("/api/incidents/INC-404/ladder")).andExpect(status().isNotFound());
        mvc.perform(get("/api/ladder/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void ladderHttpBatchValidationAndIdempotency() throws Exception {
        mvc.perform(post("/api/ladder/versions")
                        .header("X-Actor-Id", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"versionKey\":\"V1\","
                                + "\"thresholds\":[10,20,30,40,50],\"cooldownMinutes\":0}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"INC-HTTP-2\",\"severity\":\"S2\",\"summary\":\"s\","
                                + "\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/INC-HTTP-2/takeover")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());

        // 空批 → 422；分数缺失 → 422；批内重复 → 422；未知版本 → 422
        mvc.perform(post("/api/ladder/scores").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"thresholdVersionKey\":\"V1\","
                                + "\"updates\":[]}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("EMPTY_BATCH"));
        mvc.perform(post("/api/ladder/scores").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"thresholdVersionKey\":\"V1\","
                                + "\"updates\":[{\"incidentKey\":\"INC-HTTP-2\"}]}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SCORE_MISSING"));
        mvc.perform(post("/api/ladder/scores").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"thresholdVersionKey\":\"V1\","
                                + "\"updates\":[{\"incidentKey\":\"INC-HTTP-2\",\"score\":10},"
                                + "{\"incidentKey\":\"INC-HTTP-2\",\"score\":20}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_INCIDENT_IN_BATCH"));
        mvc.perform(post("/api/ladder/scores").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"thresholdVersionKey\":\"NOPE\","
                                + "\"updates\":[{\"incidentKey\":\"INC-HTTP-2\",\"score\":10}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("THRESHOLD_VERSION_NOT_FOUND"));
        // 不存在事件 → 422 整批校验失败明细
        mvc.perform(post("/api/ladder/scores").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"thresholdVersionKey\":\"V1\","
                                + "\"updates\":[{\"incidentKey\":\"INC-GONE\",\"score\":10}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].reason").value("INCIDENT_NOT_FOUND"));

        // 同键同参重放返回首次快照；同键改参 409
        String batchKey = key();
        String body = "{\"commandKey\":\"" + batchKey + "\",\"thresholdVersionKey\":\"V1\","
                + "\"updates\":[{\"incidentKey\":\"INC-HTTP-2\",\"score\":35}]}";
        String first = mvc.perform(post("/api/ladder/scores").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].actualLevel").value(3))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/ladder/scores").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(content().json(first));
        mvc.perform(post("/api/ladder/scores").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + batchKey + "\",\"thresholdVersionKey\":\"V1\","
                                + "\"updates\":[{\"incidentKey\":\"INC-HTTP-2\",\"score\":50}]}"))
                .andExpect(status().isConflict());
    }
}

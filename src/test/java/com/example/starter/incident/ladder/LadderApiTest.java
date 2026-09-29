package com.example.starter.incident.ladder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.example.starter.incident.ControllableClock;
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
 * 升级阶梯 HTTP 层测试（真实 H2 内存库）：验证新路由、X-Actor-Id 约束、JSON 结构、
 * 422/409/404/400 语义、批量整批回滚、幂等同键重放与只读查询。时间用可控 Clock。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class LadderApiTest {

    private static final Instant T0 = Instant.parse("2026-09-29T00:00:00Z");

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
        jdbc.update("DELETE FROM incident_ladder_state");
        jdbc.update("DELETE FROM ladder_current_version");
        jdbc.update("DELETE FROM escalation_threshold_versions");
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

    private void report(String incidentKey) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey + "\",\"severity\":\"S2\","
                                + "\"summary\":\"阶梯\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void publishAndBatchAndDemoteHttpFlow() throws Exception {
        // 未发布版本时批量更新 → 409
        report("INC-W-1");
        mvc.perform(post("/api/ladder/scores/batch")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"items\":["
                                + "{\"incidentKey\":\"INC-W-1\",\"score\":5}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 发布版本
        mvc.perform(post("/api/ladder/versions")
                        .header("X-Actor-Id", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"versionKey\":\"V1\","
                                + "\"thresholds\":[0,10,30,60,100],\"cooldownMinutes\":10,"
                                + "\"note\":\"首版\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionKey").value("V1"))
                .andExpect(jsonPath("$.cooldownMinutes").value(10))
                .andExpect(jsonPath("$.publishedAt").value("2026-09-29T00:00:00Z"));
        mvc.perform(get("/api/ladder/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 阈值非严格递增 → 422；冷却越界 → 422
        mvc.perform(post("/api/ladder/versions")
                        .header("X-Actor-Id", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"versionKey\":\"BAD\","
                                + "\"thresholds\":[0,10,10,60,100],\"cooldownMinutes\":10}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));
        mvc.perform(post("/api/ladder/versions")
                        .header("X-Actor-Id", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"versionKey\":\"BAD2\","
                                + "\"thresholds\":[0,10,30,60,100],\"cooldownMinutes\":-1}"))
                .andExpect(status().isUnprocessableEntity());

        // 批量首次登记：自动等级 1/3/5
        report("INC-W-2");
        report("INC-W-3");
        mvc.perform(post("/api/ladder/scores/batch")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"items\":["
                                + "{\"incidentKey\":\"INC-W-1\",\"score\":5},"
                                + "{\"incidentKey\":\"INC-W-2\",\"score\":35},"
                                + "{\"incidentKey\":\"INC-W-3\",\"score\":100}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedCount").value(3))
                .andExpect(jsonPath("$.initialised").value(3))
                .andExpect(jsonPath("$.totalScoreDelta").value(140))
                .andExpect(jsonPath("$.results[1].toLevel").value(3))
                .andExpect(jsonPath("$.results[2].changeType").value("INIT"));

        // 整批回滚：含不存在事件，INC-W-1 维持原分
        mvc.perform(post("/api/ladder/scores/batch")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"items\":["
                                + "{\"incidentKey\":\"INC-W-1\",\"score\":999},"
                                + "{\"incidentKey\":\"INC-NOPE\",\"score\":1}]}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/ladder/incidents/INC-W-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(5))
                .andExpect(jsonPath("$.actualLevel").value(1));

        // 明细与历史只读
        mvc.perform(get("/api/ladder/incidents/INC-W-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(true))
                .andExpect(jsonPath("$.actualLevel").value(3))
                .andExpect(jsonPath("$.requiredLevel").value(3))
                .andExpect(jsonPath("$.levelGap").value(0))
                .andExpect(jsonPath("$.lastEscalatedBy").value("alice"))
                .andExpect(jsonPath("$.demotable").value(false));
        mvc.perform(get("/api/ladder/incidents/INC-W-2/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].changeType").value("INIT"))
                .andExpect(jsonPath("$.items[0].fromLevel").doesNotExist());

        // 冷却边界：T0+10min 整点降级 → 422；超过 1 秒后由不同指挥员降级成功
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T00:10:00Z"));
        // 明细返回冷却实际值/要求值/差额
        mvc.perform(get("/api/ladder/incidents/INC-W-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cooldownElapsedSeconds").value(600))
                .andExpect(jsonPath("$.cooldownRequiredSeconds").value(600))
                .andExpect(jsonPath("$.cooldownRequiredMinutes").value(10))
                .andExpect(jsonPath("$.cooldownRemainingSeconds").value(0))
                .andExpect(jsonPath("$.demotable").value(false));
        mvc.perform(post("/api/ladder/incidents/INC-W-2/demote")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"expectedLevel\":3,"
                                + "\"note\":\"边界\"}"))
                .andExpect(status().isUnprocessableEntity());
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T00:10:01Z"));
        mvc.perform(post("/api/ladder/incidents/INC-W-2/demote")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"expectedLevel\":3,"
                                + "\"note\":\"风险缓解\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromLevel").value(3))
                .andExpect(jsonPath("$.toLevel").value(2))
                .andExpect(jsonPath("$.actor").value("bob"));
        // 同一指挥员（升级人 alice）降级 → 422
        mvc.perform(post("/api/ladder/incidents/INC-W-3/demote")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"expectedLevel\":5,"
                                + "\"note\":\"自己降\"}"))
                .andExpect(status().isUnprocessableEntity());
        // 缺 X-Actor-Id → 400；锚定不符 → 409
        mvc.perform(post("/api/ladder/incidents/INC-W-3/demote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"expectedLevel\":5,"
                                + "\"note\":\"无头\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/ladder/incidents/INC-W-3/demote")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"expectedLevel\":4,"
                                + "\"note\":\"跳级\"}"))
                .andExpect(status().isConflict());

        // 诊断：稳定排序与计数
        mvc.perform(get("/api/ladder/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thresholdVersionKey").value("V1"))
                .andExpect(jsonPath("$.registeredCount").value(3))
                .andExpect(jsonPath("$.items[0].incidentKey").value("INC-W-3"));

        // 未登记事件明细 registered=false；不存在 404
        report("INC-W-4");
        mvc.perform(get("/api/ladder/incidents/INC-W-4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(false))
                .andExpect(jsonPath("$.blockReason").value("风险分数缺失"));
        mvc.perform(get("/api/ladder/incidents/INC-404"))
                .andExpect(status().isNotFound());
    }

    @Test
    void batchIdempotencyReplay_http() throws Exception {
        mvc.perform(post("/api/ladder/versions")
                        .header("X-Actor-Id", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"versionKey\":\"V1\","
                                + "\"thresholds\":[0,10,30,60,100],\"cooldownMinutes\":10}"))
                .andExpect(status().isCreated());
        report("INC-WR-1");
        String commandKey = key();
        // 集合顺序不同但规范化后同参：同键视为重放，异序在另一键上语义等价
        String bodyA = "{\"commandKey\":\"" + commandKey + "\",\"items\":["
                + "{\"incidentKey\":\"INC-WR-1\",\"score\":35}]}";
        String first = mvc.perform(post("/api/ladder/scores/batch")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyA))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/ladder/scores/batch")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyA))
                .andExpect(status().isOk()).andExpect(content().json(first));
        // 同键异参 → 409
        mvc.perform(post("/api/ladder/scores/batch")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"items\":["
                                + "{\"incidentKey\":\"INC-WR-1\",\"score\":36}]}"))
                .andExpect(status().isConflict());
    }
}

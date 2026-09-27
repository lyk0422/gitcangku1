package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * 事件指挥日志封存 HTTP 层测试：验证封存、列表/详情/验真诊断只读查询、
 * 更正附录的路由、X-Actor-Id 约束与 400/404/409/422 错误语义可区分。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class LogSealApiTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM incident_log_corrections");
        jdbc.update("DELETE FROM incident_log_seal_records");
        jdbc.update("DELETE FROM incident_log_seals");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String ck() {
        return "CMD-" + UUID.randomUUID();
    }

    private void setupCommanding(String incidentKey) throws Exception {
        mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey
                                + "\",\"severity\":\"S2\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + ck() + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/actions", incidentKey)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + ck()
                                + "\",\"actionKey\":\"A1\",\"actionType\":\"NOTE\",\"note\":\"n\","
                                + "\"occurredAt\":\"2026-09-22T00:00:10Z\"}"))
                .andExpect(status().isOk());
        ((ControllableClock) clock).setInstant(T0.plus(1, ChronoUnit.MINUTES));
    }

    @Test
    void seal_queryAndCorrect_overHttp() throws Exception {
        setupCommanding("INC-L1");

        // 封存
        String sealBody = "{\"commandKey\":\"" + ck()
                + "\",\"cutoffAt\":\"2026-09-22T00:00:30Z\"}";
        mvc.perform(post("/api/incidents/{k}/log-seals", "INC-L1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(sealBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.recordCount").value(1))
                .andExpect(jsonPath("$.records[0].recordKey").value("action:A1"))
                .andExpect(jsonPath("$.records[0].recordVersion").value(1));

        // 列表
        mvc.perform(get("/api/incidents/{k}/log-seals", "INC-L1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seals.length()").value(1))
                .andExpect(jsonPath("$.seals[0].version").value(1));

        // 详情
        mvc.perform(get("/api/incidents/{k}/log-seals/1", "INC-L1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seal.records.length()").value(1))
                .andExpect(jsonPath("$.corrections.length()").value(0));

        // 验真诊断
        mvc.perform(get("/api/incidents/{k}/log-seals/1/verify", "INC-L1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));

        // 追加更正附录
        mvc.perform(post("/api/incidents/{k}/log-seals/1/corrections", "INC-L1")
                        .header("X-Actor-Id", "auditor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + ck()
                                + "\",\"recordKey\":\"action:A1\",\"fieldName\":\"note\","
                                + "\"oldValue\":\"n\",\"newValue\":\"n2\","
                                + "\"reason\":\"事实核对\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sealVersion").value(1))
                .andExpect(jsonPath("$.newValue").value("n2"));

        mvc.perform(get("/api/incidents/{k}/log-seals/1", "INC-L1"))
                .andExpect(jsonPath("$.corrections.length()").value(1));
    }

    @Test
    void seal_httpErrorSemantics() throws Exception {
        setupCommanding("INC-L2");

        // 缺 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/log-seals", "INC-L2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + ck()
                                + "\",\"cutoffAt\":\"2026-09-22T00:00:30Z\"}"))
                .andExpect(status().isBadRequest());

        // 事件不存在 → 404
        mvc.perform(get("/api/incidents/{k}/log-seals/1", "INC-NOPE"))
                .andExpect(status().isNotFound());

        // 截止晚于当前时钟 → 422，错误码可区分
        mvc.perform(post("/api/incidents/{k}/log-seals", "INC-L2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + ck()
                                + "\",\"cutoffAt\":\"2026-09-23T00:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CUTOFF_OUT_OF_RANGE"));

        // 同刻指令 → 409
        mvc.perform(post("/api/incidents/{k}/actions", "INC-L2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + ck()
                                + "\",\"actionKey\":\"A2\",\"actionType\":\"NOTE\",\"note\":\"n\","
                                + "\"occurredAt\":\"2026-09-22T00:00:30Z\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/log-seals", "INC-L2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + ck()
                                + "\",\"cutoffAt\":\"2026-09-22T00:00:30Z\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 跨过同刻边界后成功封存 v1
        mvc.perform(post("/api/incidents/{k}/log-seals", "INC-L2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + ck()
                                + "\",\"cutoffAt\":\"2026-09-22T00:00:31Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // 更正不存在的原记录 → 422
        mvc.perform(post("/api/incidents/{k}/log-seals/1/corrections", "INC-L2")
                        .header("X-Actor-Id", "auditor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + ck()
                                + "\",\"recordKey\":\"action:GHOST\",\"fieldName\":\"note\","
                                + "\"oldValue\":\"a\",\"newValue\":\"b\",\"reason\":\"r\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CORRECTION_TARGET_NOT_FOUND"));
    }
}

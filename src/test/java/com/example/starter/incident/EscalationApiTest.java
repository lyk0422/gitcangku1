package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 升级能力 HTTP 层测试：验证新路由、X-Actor-Id 约束、遏制期限/升级 JSON 字段
 * 及 400/404/409 错误语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EscalationApiTest.TestClockConfig.class)
class EscalationApiTest {

    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    private static final Instant T0 = Instant.parse("2026-09-22T10:00:00Z");

    @BeforeEach
    void clean() {
        clock.reset();
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "KEY-" + UUID.randomUUID();
    }

    private void report(String incidentKey, String severity) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey + "\",\"severity\":\""
                                + severity + "\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
    }

    private void takeoverAt(String incidentKey, String actor) throws Exception {
        clock.set(T0);
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void deadlineAndEscalationFlowOverHttp() throws Exception {
        String ik = "INC-H01";
        report(ik, "S1");
        takeoverAt(ik, "alice");

        // 期限 = T0 + 5 分钟
        mvc.perform(get("/api/incidents/{k}", ik))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.containmentDeadline").value("2026-09-22T10:05:00Z"));

        // 逾期前检查：无记录
        clock.set(Instant.parse("2026-09-22T10:04:00Z"));
        mvc.perform(post("/api/incidents/{k}/escalations/check", ik)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.escalation").value(org.hamcrest.Matchers.nullValue()));

        // 逾期查询不隐式写入
        clock.set(Instant.parse("2026-09-22T10:06:00Z"));
        mvc.perform(get("/api/incidents/{k}/escalations", ik))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deadline").value("2026-09-22T10:05:00Z"))
                .andExpect(jsonPath("$.current").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.history.length()").value(0));

        // 逾期后检查：产生 OPEN
        String checkKey = key();
        mvc.perform(post("/api/incidents/{k}/escalations/check", ik)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + checkKey + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.escalation.status").value("OPEN"))
                .andExpect(jsonPath("$.escalation.commander").value("alice"))
                .andExpect(jsonPath("$.escalation.triggeredAt").value("2026-09-22T10:06:00Z"));

        // 同键重放：created 仍为首次结果 true，不新增
        mvc.perform(post("/api/incidents/{k}/escalations/check", ik)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + checkKey + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true));

        // 非指挥人确认 409
        mvc.perform(post("/api/incidents/{k}/escalations/acknowledge", ik)
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"dispositionNote\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 当前指挥人确认成功
        mvc.perform(post("/api/incidents/{k}/escalations/acknowledge", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"dispositionNote\":\"已限流恢复\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.acknowledgedBy").value("alice"));

        mvc.perform(get("/api/incidents/{k}/escalations", ik))
                .andExpect(jsonPath("$.current.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.history.length()").value(1));

        // 完整历史含升级记录
        mvc.perform(get("/api/incidents/{k}/history", ik))
                .andExpect(jsonPath("$.escalations.length()").value(1));
    }

    @Test
    void containCancelsOpenOverHttp() throws Exception {
        String ik = "INC-H02";
        report(ik, "S1");
        takeoverAt(ik, "alice");
        clock.set(Instant.parse("2026-09-22T10:06:00Z"));
        mvc.perform(post("/api/incidents/{k}/escalations/check", ik)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(jsonPath("$.escalation.status").value("OPEN"));
        mvc.perform(post("/api/incidents/{k}/status", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\"CONTAINED\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/incidents/{k}/escalations", ik))
                .andExpect(jsonPath("$.current.status").value("CANCELLED"));
    }

    @Test
    void escalationErrorSemanticsOverHttp() throws Exception {
        // 404
        mvc.perform(get("/api/incidents/{k}/escalations", "INC-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(post("/api/incidents/{k}/escalations/check", "INC-404")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isNotFound());

        String ik = "INC-H03";
        report(ik, "S1");
        takeoverAt(ik, "alice");
        // 400：缺 commandKey
        mvc.perform(post("/api/incidents/{k}/escalations/check", ik)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        // 期限前确认 409
        clock.set(Instant.parse("2026-09-22T10:01:00Z"));
        mvc.perform(post("/api/incidents/{k}/escalations/acknowledge", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"dispositionNote\":\"x\"}"))
                .andExpect(status().isConflict());
        // 400：处置说明空白
        clock.set(Instant.parse("2026-09-22T10:06:00Z"));
        mvc.perform(post("/api/incidents/{k}/escalations/check", ik)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/escalations/acknowledge", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"dispositionNote\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }
}

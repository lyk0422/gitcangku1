package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * 挂起/恢复 HTTP 层测试：验证路由、X-Actor-Id 请求头约束、
 * 400/404/409/422 错误语义与剩余时限 JSON 视图。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class SuspensionApiTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_suspensions");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void at(long seconds) {
        ((ControllableClock) clock).setInstant(T0.plusSeconds(seconds));
    }

    private void setupCommanding(String incidentKey) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey
                                + "\",\"severity\":\"S1\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void suspensionHttpFlow() throws Exception {
        setupCommanding("INC-API-S1");
        at(60);
        // 挂起
        mvc.perform(post("/api/incidents/{k}/suspensions", "INC-API-S1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"suspendKey\":\"SK-1\",\"reason\":\"等待厂商\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspendKey").value("SK-1"))
                .andExpect(jsonPath("$.reason").value("等待厂商"))
                .andExpect(jsonPath("$.suspendedBy").value("alice"))
                .andExpect(jsonPath("$.suspendedAt").value("2026-09-22T00:01:00Z"))
                .andExpect(jsonPath("$.resumedAt").value(org.hamcrest.Matchers.nullValue()));
        // 查询：剩余时限冻结在 240 秒
        at(360);
        mvc.perform(get("/api/incidents/{k}/suspensions", "INC-API-S1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspended").value(true))
                .andExpect(jsonPath("$.remainingSeconds").value(240))
                .andExpect(jsonPath("$.suspendedTotalSeconds").value(300))
                .andExpect(jsonPath("$.overdue").value(false))
                .andExpect(jsonPath("$.suspensions.length()").value(1));
        // 恢复
        mvc.perform(post("/api/incidents/{k}/suspensions/resume", "INC-API-S1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"suspendKey\":\"SK-1\",\"note\":\"厂商已响应\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumedAt").value("2026-09-22T00:06:00Z"))
                .andExpect(jsonPath("$.resumeNote").value("厂商已响应"));
        // 恢复后剩余 = 300 − 60 = 240，实际期限 = 原期限 T0+300 + 挂起 300 秒 = T0+600
        mvc.perform(get("/api/incidents/{k}/suspensions", "INC-API-S1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspended").value(false))
                .andExpect(jsonPath("$.remainingSeconds").value(240))
                .andExpect(jsonPath("$.effectiveDeadlineAt").value("2026-09-22T00:10:00Z"));
    }

    @Test
    void suspensionErrorSemantics() throws Exception {
        setupCommanding("INC-API-E1");
        // 400：缺 X-Actor-Id
        mvc.perform(post("/api/incidents/{k}/suspensions", "INC-API-E1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"suspendKey\":\"SK-1\",\"reason\":\"r\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        // 400：reason 为空
        mvc.perform(post("/api/incidents/{k}/suspensions", "INC-API-E1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"suspendKey\":\"SK-1\",\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        // 404：事件不存在
        mvc.perform(post("/api/incidents/{k}/suspensions", "INC-API-NOPE")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"suspendKey\":\"SK-1\",\"reason\":\"r\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents/{k}/suspensions", "INC-API-NOPE"))
                .andExpect(status().isNotFound());
        // 409：未挂起时恢复
        mvc.perform(post("/api/incidents/{k}/suspensions/resume", "INC-API-E1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"suspendKey\":\"SK-1\",\"note\":\"n\"}"))
                .andExpect(status().isConflict());
        // 409：重复挂起
        mvc.perform(post("/api/incidents/{k}/suspensions", "INC-API-E1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"suspendKey\":\"SK-1\",\"reason\":\"r\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/suspensions", "INC-API-E1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"suspendKey\":\"SK-2\",\"reason\":\"r\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void suspensionLimitReturns422() throws Exception {
        setupCommanding("INC-API-L1");
        // 累计 300 秒（= S1 原时限一倍）后再次挂起 → 422
        at(0);
        mvc.perform(post("/api/incidents/{k}/suspensions", "INC-API-L1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"suspendKey\":\"SK-1\",\"reason\":\"r\"}"))
                .andExpect(status().isOk());
        at(300);
        mvc.perform(post("/api/incidents/{k}/suspensions/resume", "INC-API-L1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"suspendKey\":\"SK-1\",\"note\":\"n\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/suspensions", "INC-API-L1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"suspendKey\":\"SK-2\",\"reason\":\"r\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SUSPENSION_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("300")));
    }
}

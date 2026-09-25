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
 * 遏制时限挂起 HTTP 层测试：验证挂起/恢复/查询路由、X-Actor-Id 约束、JSON 结构
 * 及 400/404/409/422 错误语义；时间推进使用可控 Clock。
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

    private void reportAndTakeover(String incidentKey) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey + "\",\"severity\":\"S1\","
                                + "\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void suspendResumeQuery_httpFlow_andStatusCodes() throws Exception {
        reportAndTakeover("INC-HS-1");
        ((ControllableClock) clock).setInstant(T0.plusSeconds(120));

        // 挂起成功
        mvc.perform(post("/api/incidents/{k}/suspensions", "INC-HS-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"suspendKey\":\"SK-1\","
                                + "\"reason\":\"等待厂商\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspendKey").value("SK-1"))
                .andExpect(jsonPath("$.reason").value("等待厂商"))
                .andExpect(jsonPath("$.suspendedBy").value("alice"))
                .andExpect(jsonPath("$.suspendedAt").value("2026-09-22T00:02:00Z"))
                .andExpect(jsonPath("$.resumedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.suspendedDurationMillis").value(0));

        // 查询：挂起中，剩余 3 分钟
        mvc.perform(get("/api/incidents/{k}/suspensions", "INC-HS-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspended").value(true))
                .andExpect(jsonPath("$.originalDeadlineAt").value("2026-09-22T00:05:00Z"))
                .andExpect(jsonPath("$.remainingMillis").value(180000))
                .andExpect(jsonPath("$.capMillis").value(300000))
                .andExpect(jsonPath("$.intervals.length()").value(1));

        // 重复挂起 409
        mvc.perform(post("/api/incidents/{k}/suspensions", "INC-HS-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"suspendKey\":\"SK-2\","
                                + "\"reason\":\"x\"}"))
                .andExpect(status().isConflict());

        // 错键恢复 409
        mvc.perform(post("/api/incidents/{k}/suspensions/resume", "INC-HS-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"suspendKey\":\"WRONG\","
                                + "\"note\":\"n\"}"))
                .andExpect(status().isConflict());

        // 正确恢复
        ((ControllableClock) clock).setInstant(T0.plusSeconds(240));
        mvc.perform(post("/api/incidents/{k}/suspensions/resume", "INC-HS-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"suspendKey\":\"SK-1\","
                                + "\"note\":\"厂商到场\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumedAt").value("2026-09-22T00:04:00Z"))
                .andExpect(jsonPath("$.resumedBy").value("alice"))
                .andExpect(jsonPath("$.resumeNote").value("厂商到场"))
                .andExpect(jsonPath("$.suspendedDurationMillis").value(120000));

        // 恢复后剩余 = 原时限 5 分钟 - 挂起前已消耗 2 分钟 = 3 分钟；累计挂起 2 分钟
        mvc.perform(get("/api/incidents/{k}/suspensions", "INC-HS-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspended").value(false))
                .andExpect(jsonPath("$.remainingMillis").value(180000))
                .andExpect(jsonPath("$.totalSuspendedMillis").value(120000))
                .andExpect(jsonPath("$.effectiveDeadlineAt").value("2026-09-22T00:07:00Z"))
                .andExpect(jsonPath("$.intervals.length()").value(1));

        // 未挂起恢复 409
        mvc.perform(post("/api/incidents/{k}/suspensions/resume", "INC-HS-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"suspendKey\":\"SK-1\","
                                + "\"note\":\"n\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void suspend_blankReason400_missingActor400_notFound404_cap422() throws Exception {
        reportAndTakeover("INC-HS-2");
        // 空原因 400
        mvc.perform(post("/api/incidents/{k}/suspensions", "INC-HS-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"suspendKey\":\"SK\","
                                + "\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest());
        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/suspensions", "INC-HS-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"suspendKey\":\"SK\","
                                + "\"reason\":\"r\"}"))
                .andExpect(status().isBadRequest());
        // 事件不存在 404
        mvc.perform(get("/api/incidents/{k}/suspensions", "INC-NOPE"))
                .andExpect(status().isNotFound());

        // 累计挂起达上限（S1 = 5 分钟）返回 422
        ((ControllableClock) clock).setInstant(T0.plusSeconds(60));
        mvc.perform(post("/api/incidents/{k}/suspensions", "INC-HS-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"suspendKey\":\"A\","
                                + "\"reason\":\"r\"}"))
                .andExpect(status().isOk());
        ((ControllableClock) clock).setInstant(T0.plusSeconds(360));
        mvc.perform(post("/api/incidents/{k}/suspensions/resume", "INC-HS-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"suspendKey\":\"A\","
                                + "\"note\":\"n\"}"))
                .andExpect(status().isOk());
        ((ControllableClock) clock).setInstant(T0.plusSeconds(420));
        mvc.perform(post("/api/incidents/{k}/suspensions", "INC-HS-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"suspendKey\":\"B\","
                                + "\"reason\":\"r\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("300000")));
    }
}

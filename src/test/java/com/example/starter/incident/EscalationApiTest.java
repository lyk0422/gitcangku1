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
 * 遏制逾期升级 HTTP 层测试：验证新路由、X-Actor-Id 约束、JSON 结构及
 * 400/404/409 错误语义；时间推进使用可控 Clock。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class EscalationApiTest {

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

    @Test
    void escalationHttpFlow() throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"INC-H-1\",\"severity\":\"S1\",\"summary\":\"s\","
                                + "\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/takeover", "INC-H-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deadlineAt").value("2026-09-22T00:05:00Z"));

        // 期限前检查：无记录
        mvc.perform(post("/api/incidents/{k}/escalations/check", "INC-H-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.history.length()").value(0));

        // 只读查询不隐式写入
        mvc.perform(get("/api/incidents/{k}/escalations", "INC-H-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deadlineAt").value("2026-09-22T00:05:00Z"))
                .andExpect(jsonPath("$.current").value(org.hamcrest.Matchers.nullValue()));

        // 越过期限后检查：OPEN
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-22T00:06:00Z"));
        mvc.perform(post("/api/incidents/{k}/escalations/check", "INC-H-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.status").value("OPEN"))
                .andExpect(jsonPath("$.current.triggeredCommander").value("alice"))
                .andExpect(jsonPath("$.history.length()").value(1));

        // 非指挥人确认 → 409；缺 X-Actor-Id → 400；空说明 → 400
        mvc.perform(post("/api/incidents/{k}/escalations/acknowledge", "INC-H-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"note\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mvc.perform(post("/api/incidents/{k}/escalations/acknowledge", "INC-H-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"note\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/incidents/{k}/escalations/acknowledge", "INC-H-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"note\":\"  \"}"))
                .andExpect(status().isBadRequest());

        // 当前指挥人确认成功；历史接口包含升级记录
        mvc.perform(post("/api/incidents/{k}/escalations/acknowledge", "INC-H-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"note\":\"已接手处理\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.acknowledgedBy").value("alice"));
        mvc.perform(get("/api/incidents/{k}/history", "INC-H-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.escalations.length()").value(1))
                .andExpect(jsonPath("$.escalations[0].status").value("ACKNOWLEDGED"));
    }

    @Test
    void escalationHttpIdempotencyAndErrors() throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"INC-H-2\",\"severity\":\"S2\",\"summary\":\"s\","
                                + "\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/takeover", "INC-H-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-22T01:00:00Z"));

        String checkKey = key();
        String body = "{\"commandKey\":\"" + checkKey + "\"}";
        String first = mvc.perform(post("/api/incidents/{k}/escalations/check", "INC-H-2")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/incidents/{k}/escalations/check", "INC-H-2")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().json(first));

        // 缺 commandKey → 400
        mvc.perform(post("/api/incidents/{k}/escalations/check", "INC-H-2")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        // 不存在 → 404
        mvc.perform(get("/api/incidents/{k}/escalations", "INC-404"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/incidents/{k}/escalations/check", "INC-404")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isNotFound());
        // 遏制后检查不再新增，且 OPEN 记录被取消
        String ackKey = key();
        mvc.perform(post("/api/incidents/{k}/escalations/acknowledge", "INC-H-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + ackKey + "\",\"note\":\"处理中\"}"))
                .andExpect(status().isOk());
        // 同键重放确认 → 首次结果；同键改 note → 409
        mvc.perform(post("/api/incidents/{k}/escalations/acknowledge", "INC-H-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + ackKey + "\",\"note\":\"处理中\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
        mvc.perform(post("/api/incidents/{k}/escalations/acknowledge", "INC-H-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + ackKey + "\",\"note\":\"改了\"}"))
                .andExpect(status().isConflict());
    }
}

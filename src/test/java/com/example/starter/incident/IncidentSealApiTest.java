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
 * 日志封存 HTTP 层测试：验证路由、X-Actor-Id 请求头约束及
 * 400/404/409 错误语义可区分，只读查询不改变状态。
 * 使用可控 Clock，sealUntil 取固定 UTC 时刻。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class IncidentSealApiTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    /** 时钟推进后的合法截至时刻（不晚于当前时刻、不早于事件创建）。 */
    private static final String SEAL_UNTIL = "2026-09-22T00:10:00Z";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM incident_log_appendices");
        jdbc.update("DELETE FROM incident_log_seal_edges");
        jdbc.update("DELETE FROM incident_log_seal_records");
        jdbc.update("DELETE FROM incident_log_seals");
        jdbc.update("DELETE FROM command_keys");
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

    private void commanding(String incidentKey, String actor) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey
                                + "\",\"severity\":\"S1\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
        // 推进时钟，使固定 sealUntil 不晚于当前时刻
        ((ControllableClock) clock).setInstant(T0.plusSeconds(1000));
    }

    @Test
    void sealApiMainFlow() throws Exception {
        commanding("INC-600", "alice");
        mvc.perform(post("/api/incidents/{k}/actions", "INC-600")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"actionKey\":\"A1\",\"actionType\":\"NOTE\",\"note\":\"n1\","
                                + "\"occurredAt\":\"2026-09-22T00:01:00Z\"}"))
                .andExpect(status().isOk());

        // 创建封存包
        mvc.perform(post("/api/incidents/{k}/seals", "INC-600")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"sealUntil\":\"" + SEAL_UNTIL + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.recordCount").value(1))
                .andExpect(jsonPath("$.edgeCount").value(0))
                .andExpect(jsonPath("$.sealedBy").value("alice"));

        // 历史查询
        mvc.perform(get("/api/incidents/{k}/seals", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seals.length()").value(1))
                .andExpect(jsonPath("$.seals[0].version").value(1));

        // 明细查询：记录按发生时刻排序，payload 固化
        mvc.perform(get("/api/incidents/{k}/seals/{v}", "INC-600", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seal.version").value(1))
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].recordType").value("ACTION"))
                .andExpect(jsonPath("$.records[0].recordKey").value("A1"))
                .andExpect(jsonPath("$.records[0].payload.note").value("n1"))
                .andExpect(jsonPath("$.appendices.length()").value(0));

        // 追加更正附录
        mvc.perform(post("/api/incidents/{k}/seals/appendices", "INC-600")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"sealVersion\":1,\"recordKey\":\"A1\","
                                + "\"fieldName\":\"note\",\"oldValue\":\"n1\","
                                + "\"newValue\":\"n1-fixed\",\"reason\":\"事实错误\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sealVersion").value(1))
                .andExpect(jsonPath("$.recordKey").value("A1"))
                .andExpect(jsonPath("$.oldValue").value("n1"))
                .andExpect(jsonPath("$.newValue").value("n1-fixed"))
                .andExpect(jsonPath("$.operator").value("alice"));

        // 附录历史与诊断查询
        mvc.perform(get("/api/incidents/{k}/seals/appendices", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appendices.length()").value(1));
        mvc.perform(get("/api/incidents/{k}/seals/diagnostics", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sealCount").value(1))
                .andExpect(jsonPath("$.latestVersion").value(1))
                .andExpect(jsonPath("$.recordCount").value(1))
                .andExpect(jsonPath("$.appendixCount").value(1));
    }

    @Test
    void sealApiErrorSemantics() throws Exception {
        commanding("INC-601", "alice");
        // 400：缺 X-Actor-Id
        mvc.perform(post("/api/incidents/{k}/seals", "INC-601")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"sealUntil\":\"" + SEAL_UNTIL + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        // 400：sealUntil 缺失
        mvc.perform(post("/api/incidents/{k}/seals", "INC-601")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isBadRequest());
        // 400：sealUntil 早于事件创建
        mvc.perform(post("/api/incidents/{k}/seals", "INC-601")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"sealUntil\":\"2000-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        // 400：sealUntil 晚于当前时刻
        mvc.perform(post("/api/incidents/{k}/seals", "INC-601")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"sealUntil\":\"2099-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        // 404：事件不存在
        mvc.perform(get("/api/incidents/{k}/seals", "INC-601-X"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // 404：封存包版本不存在
        mvc.perform(get("/api/incidents/{k}/seals/{v}", "INC-601", 7))
                .andExpect(status().isNotFound());
        // 404：附录引用不存在的封存包
        mvc.perform(post("/api/incidents/{k}/seals/appendices", "INC-601")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"sealVersion\":3,\"recordKey\":\"A1\","
                                + "\"fieldName\":\"note\",\"oldValue\":\"a\","
                                + "\"newValue\":\"b\",\"reason\":\"r\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void sealApiIdempotentReplay() throws Exception {
        commanding("INC-602", "alice");
        String commandKey = key();
        String body = "{\"commandKey\":\"" + commandKey
                + "\",\"sealUntil\":\"" + SEAL_UNTIL + "\"}";
        String first = mvc.perform(post("/api/incidents/{k}/seals", "INC-602")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // 同键同参重放：返回首次结果
        mvc.perform(post("/api/incidents/{k}/seals", "INC-602")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().json(first));
        // 同键改参：409
        mvc.perform(post("/api/incidents/{k}/seals", "INC-602")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey
                                + "\",\"sealUntil\":\"2026-09-22T00:05:00Z\"}"))
                .andExpect(status().isConflict());
        // 重放后仍只有一个封存包
        mvc.perform(get("/api/incidents/{k}/seals", "INC-602"))
                .andExpect(jsonPath("$.seals.length()").value(1));
    }
}

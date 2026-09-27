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
 * 指挥日志封存 HTTP 层测试：验证路由、X-Actor-Id 请求头约束及
 * 400/404/409/422 错误语义可区分；时间推进使用可控 Clock。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class SealApiTest {

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
        jdbc.update("DELETE FROM incident_seal_appendices");
        jdbc.update("DELETE FROM incident_seal_edges");
        jdbc.update("DELETE FROM incident_seal_entries");
        jdbc.update("DELETE FROM incident_seal_packages");
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

    private void commanding(String incidentKey, String commander) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey
                                + "\",\"severity\":\"S2\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", commander)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
    }

    private void seal(String incidentKey, String actor, String cutoff) throws Exception {
        mvc.perform(post("/api/incidents/{k}/seals", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"cutoffAt\":\"" + cutoff
                                + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void sealApiMainFlow() throws Exception {
        commanding("INC-API-1", "alice");
        mvc.perform(post("/api/incidents/{k}/actions", "INC-API-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"actionKey\":\"A1\",\"actionType\":\"NOTE\",\"note\":\"n1\","
                                + "\"occurredAt\":\"2026-09-22T00:00:10Z\"}"))
                .andExpect(status().isOk());

        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));
        // 创建封存包
        mvc.perform(post("/api/incidents/{k}/seals", "INC-API-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"cutoffAt\":\"2026-09-22T00:01:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.cutoffAt").value("2026-09-22T00:01:00Z"))
                .andExpect(jsonPath("$.entryCount").value(3))
                .andExpect(jsonPath("$.sealedBy").value("alice"));

        // 明细：条目按序返回，含固化快照
        mvc.perform(get("/api/incidents/{k}/seals/{v}", "INC-API-1", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[2].recordType").value("ACTION"))
                .andExpect(jsonPath("$.entries[2].recordKey").value("A1"))
                .andExpect(jsonPath("$.entries[2].recordVersion").value(1))
                .andExpect(jsonPath("$.entries[2].payload.note").value("n1"));

        // 历史
        mvc.perform(get("/api/incidents/{k}/seals", "INC-API-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packages.length()").value(1))
                .andExpect(jsonPath("$.appendices.length()").value(0));

        // 诊断
        mvc.perform(get("/api/incidents/{k}/seals/diagnostics", "INC-API-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastVersion").value(1))
                .andExpect(jsonPath("$.pendingActionCount").value(0));

        // 追加更正附录
        mvc.perform(post("/api/incidents/{k}/seals/appendices", "INC-API-1")
                        .header("X-Actor-Id", "auditor-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"packageVersion\":1,\"recordKey\":\"A1\","
                                + "\"newValue\":\"{\\\"note\\\":\\\"n1-fixed\\\"}\","
                                + "\"reason\":\"事实更正\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageVersion").value(1))
                .andExpect(jsonPath("$.recordKey").value("A1"))
                .andExpect(jsonPath("$.oldValue.note").value("n1"))
                .andExpect(jsonPath("$.actor").value("auditor-1"));

        // 原包不被附录改写
        mvc.perform(get("/api/incidents/{k}/seals/{v}", "INC-API-1", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[2].payload.note").value("n1"));
    }

    @Test
    void sealApiErrorSemantics() throws Exception {
        commanding("INC-API-2", "alice");
        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/seals", "INC-API-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"cutoffAt\":\"2026-09-22T00:01:00Z\"}"))
                .andExpect(status().isBadRequest());
        // cutoffAt 缺失 → 400
        mvc.perform(post("/api/incidents/{k}/seals", "INC-API-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isBadRequest());
        // 截至时刻晚于当前时刻 → 400
        mvc.perform(post("/api/incidents/{k}/seals", "INC-API-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"cutoffAt\":\"2026-09-22T00:02:00Z\"}"))
                .andExpect(status().isBadRequest());
        // 事件不存在 → 404
        mvc.perform(post("/api/incidents/{k}/seals", "INC-MISSING")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"cutoffAt\":\"2026-09-22T00:01:00Z\"}"))
                .andExpect(status().isNotFound());
        // 非当前指挥人 → 409
        mvc.perform(post("/api/incidents/{k}/seals", "INC-API-2")
                        .header("X-Actor-Id", "mallory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"cutoffAt\":\"2026-09-22T00:01:00Z\"}"))
                .andExpect(status().isConflict());
        // 封存包版本不存在 → 404
        mvc.perform(get("/api/incidents/{k}/seals/{v}", "INC-API-2", 7))
                .andExpect(status().isNotFound());
    }

    @Test
    void sealApiSameInstantConflictAndClosedRejection() throws Exception {
        commanding("INC-API-3", "alice");
        mvc.perform(post("/api/incidents/{k}/actions", "INC-API-3")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"actionKey\":\"A-SAME\",\"actionType\":\"NOTE\","
                                + "\"note\":\"n\",\"occurredAt\":\"2026-09-22T00:01:00Z\"}"))
                .andExpect(status().isOk());
        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));

        // 同刻指令写入冲突 → 409，明细携带指令标识
        mvc.perform(post("/api/incidents/{k}/seals", "INC-API-3")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"cutoffAt\":\"2026-09-22T00:01:00Z\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("A-SAME"));

        // 换截至时刻成功
        seal("INC-API-3", "alice", "2026-09-22T00:01:01Z");

        // 推进到 CLOSED 后新增封存包 → 422
        for (String target : new String[] {"CONTAINED", "RESOLVED", "CLOSED"}) {
            mvc.perform(post("/api/incidents/{k}/status", "INC-API-3")
                            .header("X-Actor-Id", "alice")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\""
                                    + target + "\"}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/incidents/{k}/seals", "INC-API-3")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"cutoffAt\":\"2026-09-22T00:01:40Z\"}"))
                .andExpect(status().isUnprocessableEntity());
    }
}

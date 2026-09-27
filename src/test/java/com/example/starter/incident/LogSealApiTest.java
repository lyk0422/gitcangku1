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
import org.springframework.test.web.servlet.MvcResult;

/**
 * 日志封存 HTTP 层测试：验证封存/清单/明细/诊断/更正附录路由、X-Actor-Id 约束、
 * 201/400/404/409/422 可区分错误码以及同键重放。使用可控 Clock 构造 UTC 边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class LogSealApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM incident_log_corrections");
        jdbc.update("DELETE FROM incident_log_seal_items");
        jdbc.update("DELETE FROM incident_log_seals");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-22T00:00:00Z"));
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void advance(long seconds) {
        ((ControllableClock) clock).advanceSeconds(seconds);
    }

    private String nowIso() {
        return clock.instant().toString();
    }

    private void reportAndTakeover(String incidentKey) throws Exception {
        mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey
                                + "\",\"severity\":\"S2\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        advance(5);
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
        advance(5);
    }

    @Test
    void sealQueryAndCorrection_mainFlow() throws Exception {
        String ik = "INC-API-1";
        reportAndTakeover(ik);
        mvc.perform(post("/api/incidents/{k}/actions", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"actionKey\":\"A1\",\"actionType\":\"NOTE\",\"note\":\"n\","
                                + "\"occurredAt\":\"" + nowIso() + "\"}"))
                .andExpect(status().isOk());
        advance(10);
        String cutoff = nowIso();

        // 封存 201
        MvcResult sealResult = mvc.perform(post("/api/incidents/{k}/log-seals", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"cutoffAt\":\"" + cutoff
                                + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.recordCount").isNumber())
                .andExpect(jsonPath("$.contentHash").isString())
                .andReturn();
        int count = com.jayway.jsonpath.JsonPath.read(sealResult.getResponse().getContentAsString(),
                "$.recordCount");

        // 清单
        mvc.perform(get("/api/incidents/{k}/log-seals", ik))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestVersion").value(1))
                .andExpect(jsonPath("$.seals[0].version").value(1));
        // 单包明细
        mvc.perform(get("/api/incidents/{k}/log-seals/1", ik))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(count))
                .andExpect(jsonPath("$.items[0].seqNo").value(1));
        // 诊断
        mvc.perform(get("/api/incidents/{k}/log-seals/1/diagnostics", ik))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true))
                .andExpect(jsonPath("$.hashMatch").value(true));

        // 追加更正附录
        mvc.perform(post("/api/incidents/{k}/log-seals/corrections", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"packageVersion\":1,\"recordType\":\"ACTION\","
                                + "\"recordKey\":\"A1\",\"fieldName\":\"note\","
                                + "\"newValue\":\"更正\",\"reason\":\"录错\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.oldValue").value("n"))
                .andExpect(jsonPath("$.operator").value("alice"));
        mvc.perform(get("/api/incidents/{k}/log-seals/corrections", ik))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        // 附录已挂在原包上，原包条目数不变
        mvc.perform(get("/api/incidents/{k}/log-seals/1", ik))
                .andExpect(jsonPath("$.corrections.length()").value(1))
                .andExpect(jsonPath("$.items.length()").value(count));
    }

    @Test
    void seal_errorSemantics() throws Exception {
        String ik = "INC-API-2";
        reportAndTakeover(ik);
        // 400：缺 X-Actor-Id
        mvc.perform(post("/api/incidents/{k}/log-seals", ik)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"cutoffAt\":\"" + nowIso()
                                + "\"}"))
                .andExpect(status().isBadRequest());
        // 404：事件不存在
        mvc.perform(get("/api/incidents/NO-SUCH/log-seals"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // 422：截至时刻早于事件创建
        mvc.perform(post("/api/incidents/{k}/log-seals", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"cutoffAt\":\"2000-01-01T00:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SEAL_CUTOFF_TOO_EARLY"))
                .andExpect(jsonPath("$.details.shortfallSeconds").isNumber());
        // 422：截至时刻晚于当前时钟
        mvc.perform(post("/api/incidents/{k}/log-seals", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"cutoffAt\":\"2099-01-01T00:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SEAL_CUTOFF_IN_FUTURE"))
                .andExpect(jsonPath("$.details.excessSeconds").isNumber());
        // 409：非当前指挥人
        mvc.perform(post("/api/incidents/{k}/log-seals", ik)
                        .header("X-Actor-Id", "mallory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"cutoffAt\":\"" + nowIso()
                                + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void seal_boundaryWrite_conflictAndClosedIncident() throws Exception {
        String ik = "INC-API-3";
        reportAndTakeover(ik);
        String at = nowIso();
        mvc.perform(post("/api/incidents/{k}/actions", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"actionKey\":\"AB\",\"actionType\":\"NOTE\",\"note\":\"n\","
                                + "\"occurredAt\":\"" + at + "\"}"))
                .andExpect(status().isOk());
        // 同刻封存 409 且明细指出冲突记录
        mvc.perform(post("/api/incidents/{k}/log-seals", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"cutoffAt\":\"" + at + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAL_BOUNDARY_WRITE"))
                .andExpect(jsonPath("$.details[0].recordKey").value("AB"));

        // 推进到关闭后：可查询、可追加更正，但封存 422
        advance(10);
        mvc.perform(post("/api/incidents/{k}/log-seals", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"cutoffAt\":\"" + nowIso()
                                + "\"}"))
                .andExpect(status().isCreated());
        for (String target : new String[]{"CONTAINED", "RESOLVED", "CLOSED"}) {
            advance(5);
            mvc.perform(post("/api/incidents/{k}/status", ik)
                            .header("X-Actor-Id", "alice")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\""
                                    + target + "\"}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/incidents/{k}/log-seals", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"cutoffAt\":\"" + nowIso()
                                + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));
        mvc.perform(get("/api/incidents/{k}/log-seals", ik))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestVersion").value(1));
    }

    @Test
    void seal_sameCommandKey_replaysFirstResponse() throws Exception {
        String ik = "INC-API-4";
        reportAndTakeover(ik);
        String commandKey = key();
        String body = "{\"commandKey\":\"" + commandKey + "\",\"cutoffAt\":\"" + nowIso() + "\"}";
        String first = mvc.perform(post("/api/incidents/{k}/log-seals", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/incidents/{k}/log-seals", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThatJsonEqual(first, replay);
        // 异参 409
        advance(10);
        mvc.perform(post("/api/incidents/{k}/log-seals", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"cutoffAt\":\""
                                + nowIso() + "\"}"))
                .andExpect(status().isConflict());
    }

    private static void assertThatJsonEqual(String first, String replay) {
        org.assertj.core.api.Assertions.assertThat(replay).isEqualTo(first);
    }
}

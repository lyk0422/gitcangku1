package com.example.starter.incident;

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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 双人监护在场凭证 HTTP 层测试：验证监护路由、X-Actor-Id 约束、
 * 403 越权、422 凭证缺失/过期及 commandKey 幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class PresenceApiTest {

    private static final Instant T0 = Instant.parse("2026-10-07T10:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private java.time.Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM task_presence_credentials");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void at(Instant instant) {
        ((ControllableClock) clock).setInstant(instant);
    }

    private void supervisedTask(String incidentKey, String taskKey) throws Exception {
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
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"OPS\",\"title\":\"高危处置\","
                                + "\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/supervision", incidentKey, taskKey)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"executor\":\"op-exec\",\"guardian\":\"op-guard\","
                                + "\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.supervisionExecutor").value("op-exec"))
                .andExpect(jsonPath("$.supervisionGuardian").value("op-guard"));
    }

    private void checkIn(String incidentKey, String taskKey, String actor, int generation)
            throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/presence/check-in", incidentKey, taskKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operator").value(actor))
                .andExpect(jsonPath("$.generation").value(generation));
    }

    @Test
    void presenceHttpFlow() throws Exception {
        supervisedTask("INC-H1", "T-1");
        // 10:00:00 执行人签到
        checkIn("INC-H1", "T-1", "op-exec", 1);
        // 10:00:10 监护人签到
        at(T0.plusSeconds(10));
        checkIn("INC-H1", "T-1", "op-guard", 1);
        // 10:00:59 双方凭证有效，完成成功并冻结双方签发、到期时刻
        at(T0.plusSeconds(59));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-H1", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"executorGeneration\":1,\"guardianGeneration\":1,"
                                + "\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.doneExecutorIssuedAt").value("2026-10-07T10:00:00Z"))
                .andExpect(jsonPath("$.doneExecutorExpiresAt").value("2026-10-07T10:01:00Z"))
                .andExpect(jsonPath("$.doneGuardianIssuedAt").value("2026-10-07T10:00:10Z"))
                .andExpect(jsonPath("$.doneGuardianExpiresAt").value("2026-10-07T10:01:10Z"));
    }

    @Test
    void presenceHttpRejections() throws Exception {
        supervisedTask("INC-H2", "T-1");
        // 合成两人 10:00:00、10:00:10 签到
        checkIn("INC-H2", "T-1", "op-exec", 1);
        at(T0.plusSeconds(10));
        checkIn("INC-H2", "T-1", "op-guard", 1);
        // 非固定操作者签到 → 403
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/presence/check-in", "INC-H2", "T-1")
                        .header("X-Actor-Id", "mallory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // 非当前指挥人撤销 → 403
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/presence/revoke", "INC-H2", "T-1")
                        .header("X-Actor-Id", "mallory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"operator\":\"op-exec\"}"))
                .andExpect(status().isForbidden());
        // 缺失双方代次 → 422，任务不变
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-H2", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRESENCE_INVALID"));
        // 10:01:00 第一人（10:00:00 签发）已到期 → 422
        at(T0.plusSeconds(60));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-H2", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"executorGeneration\":1,\"guardianGeneration\":1}"))
                .andExpect(status().isUnprocessableEntity());
        // 重新签到后须引用新代次：旧代 422，新代成功
        checkIn("INC-H2", "T-1", "op-exec", 2);
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-H2", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"executorGeneration\":1,\"guardianGeneration\":1}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-H2", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"executorGeneration\":2,\"guardianGeneration\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    void presenceHttpReplay() throws Exception {
        supervisedTask("INC-H3", "T-1");
        // 同键签到重放：返回首次凭证，不延长有效期
        String checkInKey = key();
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/presence/check-in", "INC-H3", "T-1")
                        .header("X-Actor-Id", "op-exec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + checkInKey + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuedAt").value("2026-10-07T10:00:00Z"));
        at(T0.plusSeconds(30));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/presence/check-in", "INC-H3", "T-1")
                        .header("X-Actor-Id", "op-exec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + checkInKey + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuedAt").value("2026-10-07T10:00:00Z"))
                .andExpect(jsonPath("$.generation").value(1));
        // 同键改参 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/presence/check-in", "INC-H3", "T-1")
                        .header("X-Actor-Id", "op-exec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + checkInKey
                                + "\",\"expectedVersion\":9}"))
                .andExpect(status().isConflict());
        // 撤销后完成 → 422
        checkIn("INC-H3", "T-1", "op-guard", 1);
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/presence/revoke", "INC-H3", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"operator\":\"op-exec\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedAt").value("2026-10-07T10:00:30Z"));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-H3", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"executorGeneration\":1,\"guardianGeneration\":1}"))
                .andExpect(status().isUnprocessableEntity());
    }
}

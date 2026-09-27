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
 * 决策提案 HTTP 层测试：验证路由、X-Actor-Id 请求头约束、
 * 201/200/400/404/409/410/422 状态语义及 details 结构化明细
 * （法定人数差额、无效角色、到期判定）。使用可控 Clock 控制到期时刻。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClock.Config.class)
class DecisionApiTest {

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
        jdbc.update("DELETE FROM incident_decision_votes");
        jdbc.update("DELETE FROM incident_decisions");
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
                        .content("{\"incidentKey\":\"" + incidentKey
                                + "\",\"severity\":\"S1\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
    }

    private void takeover(String incidentKey, String actor) throws Exception {
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
    }

    private void createTask(String incidentKey, String actor, String taskKey) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"DB\",\"title\":\"扩容\","
                                + "\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isOk());
    }

    private void register(String incidentKey, String actor, String proposalKey, String taskKey,
                          String rolesJson, int quorum, String expiresAt) throws Exception {
        mvc.perform(post("/api/incidents/{k}/decisions", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\""
                                + proposalKey + "\",\"taskKey\":\"" + taskKey
                                + "\",\"taskAction\":\"COMPLETE\",\"requiredRoles\":" + rolesJson
                                + ",\"quorum\":" + quorum + ",\"expiresAt\":\"" + expiresAt
                                + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposalKey").value(proposalKey))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    private void vote(String incidentKey, String proposalKey, String actor, String role,
                      String ballot) throws Exception {
        mvc.perform(post("/api/incidents/{k}/decisions/{p}/votes", incidentKey, proposalKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"role\":\"" + role
                                + "\",\"ballot\":\"" + ballot + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAST"));
    }

    @Test
    void decisionHttpFlow() throws Exception {
        report("INC-600");
        takeover("INC-600", "alice");
        createTask("INC-600", "alice", "T-1");
        register("INC-600", "alice", "P-1", "T-1", "[\"ops\",\"dba\"]", 2,
                "2026-09-22T01:00:00Z");

        // 明细 / 列表 / 诊断（只读）
        mvc.perform(get("/api/incidents/{k}/decisions/{p}", "INC-600", "P-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentTaskVersion").value(1))
                .andExpect(jsonPath("$.requiredRoles.length()").value(2))
                .andExpect(jsonPath("$.remainingToQuorum").value(2))
                .andExpect(jsonPath("$.decidedTaskVersion").doesNotExist())
                .andExpect(jsonPath("$.decidedAt").doesNotExist());
        mvc.perform(get("/api/incidents/{k}/decisions", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisions.length()").value(1));
        mvc.perform(get("/api/incidents/{k}/decisions/{p}/diagnostics", "INC-600", "P-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quorum").value(2))
                .andExpect(jsonPath("$.staleCount").value(0));

        // 投票达法定人数 → 生效并原子写入任务变更
        vote("INC-600", "P-1", "carol", "ops", "SUPPORT");
        vote("INC-600", "P-1", "dave", "dba", "SUPPORT");
        mvc.perform(get("/api/incidents/{k}/decisions/{p}", "INC-600", "P-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.decidedTaskVersion").value(1))
                .andExpect(jsonPath("$.supportCount").value(2))
                .andExpect(jsonPath("$.remainingToQuorum").value(0));
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-600", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.doneBy").value("decision:P-1"));

        // 历史：两票证据按投票顺序
        mvc.perform(get("/api/incidents/{k}/decisions/{p}/history", "INC-600", "P-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.votes.length()").value(2))
                .andExpect(jsonPath("$.votes[0].role").value("ops"))
                .andExpect(jsonPath("$.votes[0].taskVersion").value(1))
                .andExpect(jsonPath("$.votes[1].role").value("dba"));

        // 任务修订 → 版本递增（提案已生效不受影响）
        createTask("INC-600", "alice", "T-2");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/revise", "INC-600", "T-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"groupCode\":\"DB\",\"title\":\"扩容v2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.title").value("扩容v2"));
    }

    @Test
    void decisionHttpValidation() throws Exception {
        report("INC-610");
        takeover("INC-610", "alice");
        createTask("INC-610", "alice", "T-1");

        // 角色集合为空 → 422，details 含实际值与要求值
        mvc.perform(post("/api/incidents/{k}/decisions", "INC-610")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-1\","
                                + "\"taskKey\":\"T-1\",\"taskAction\":\"COMPLETE\","
                                + "\"requiredRoles\":[],\"quorum\":1,"
                                + "\"expiresAt\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"))
                .andExpect(jsonPath("$.details.actualRoles").value(0))
                .andExpect(jsonPath("$.details.requiredMinRoles").value(1));
        // 法定人数超过角色集合 → 422，details 含差额
        mvc.perform(post("/api/incidents/{k}/decisions", "INC-610")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-1\","
                                + "\"taskKey\":\"T-1\",\"taskAction\":\"COMPLETE\","
                                + "\"requiredRoles\":[\"ops\"],\"quorum\":2,"
                                + "\"expiresAt\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.actualQuorum").value(2))
                .andExpect(jsonPath("$.details.roleCount").value(1))
                .andExpect(jsonPath("$.details.excess").value(1));
        // 到期时刻早于当前时钟 → 422
        mvc.perform(post("/api/incidents/{k}/decisions", "INC-610")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-1\","
                                + "\"taskKey\":\"T-1\",\"taskAction\":\"COMPLETE\","
                                + "\"requiredRoles\":[\"ops\"],\"quorum\":1,"
                                + "\"expiresAt\":\"2026-09-21T23:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.actualExpiresAt").exists())
                .andExpect(jsonPath("$.details.requiredNotBefore").exists());
        // 缺少 X-Actor-Id → 400；事件不存在 → 404
        mvc.perform(post("/api/incidents/{k}/decisions", "INC-610")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-1\","
                                + "\"taskKey\":\"T-1\",\"taskAction\":\"COMPLETE\","
                                + "\"requiredRoles\":[\"ops\"],\"quorum\":1,"
                                + "\"expiresAt\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/incidents/{k}/decisions/{p}", "INC-610", "P-9"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents/{k}/decisions", "INC-404"))
                .andExpect(status().isNotFound());
    }

    @Test
    void voteHttpErrors() throws Exception {
        report("INC-620");
        takeover("INC-620", "alice");
        createTask("INC-620", "alice", "T-1");
        register("INC-620", "alice", "P-1", "T-1", "[\"ops\",\"dba\"]", 2,
                "2026-09-22T01:00:00Z");

        // 无效角色 → 422，details 含实际角色与要求集合
        mvc.perform(post("/api/incidents/{k}/decisions/{p}/votes", "INC-620", "P-1")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"role\":\"guest\","
                                + "\"ballot\":\"SUPPORT\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"))
                .andExpect(jsonPath("$.details.actualRole").value("guest"));
        // 重复投票 → 409
        vote("INC-620", "P-1", "carol", "ops", "SUPPORT");
        mvc.perform(post("/api/incidents/{k}/decisions/{p}/votes", "INC-620", "P-1")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"role\":\"ops\","
                                + "\"ballot\":\"SUPPORT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        // 撤回 → 200；再次撤回 → 409
        mvc.perform(post("/api/incidents/{k}/decisions/{p}/votes/withdraw", "INC-620", "P-1")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"role\":\"ops\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
        mvc.perform(post("/api/incidents/{k}/decisions/{p}/votes/withdraw", "INC-620", "P-1")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"role\":\"ops\"}"))
                .andExpect(status().isConflict());

        // 时钟越过到期时刻：投票 → 410 GONE，提案转 EXPIRED
        ((ControllableClock) clock).setInstant(T0.plusSeconds(7200));
        mvc.perform(post("/api/incidents/{k}/decisions/{p}/votes", "INC-620", "P-1")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"role\":\"dba\","
                                + "\"ballot\":\"SUPPORT\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("GONE"));
        mvc.perform(get("/api/incidents/{k}/decisions/{p}", "INC-620", "P-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
        // 到期判定接口幂等返回 EXPIRED
        mvc.perform(post("/api/incidents/{k}/decisions/{p}/expire-check", "INC-620", "P-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }
}

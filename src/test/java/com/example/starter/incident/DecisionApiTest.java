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
 * 决策提案 HTTP 层测试：覆盖登记校验（422/400/404/409）、投票与法定人数生效、
 * 到期 410 且提案转 EXPIRED、撤回、任务修订致旧票 STALE、commandKey 幂等重放与改参 409、
 * 失败不占键及历史稳定排序。时间推进使用可控 Clock（UTC）。
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
        jdbc.update("DELETE FROM decision_proposal_votes");
        jdbc.update("DELETE FROM decision_proposal_roles");
        jdbc.update("DELETE FROM decision_proposals");
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
                                + "\",\"groupCode\":\"DB\",\"title\":\"旧标题\","
                                + "\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isOk());
    }

    private void createProposal(String incidentKey, String actor, String proposalKey,
                                String taskKey, String rolesJson, int quorum,
                                String expiresAt) throws Exception {
        mvc.perform(post("/api/incidents/{k}/proposals", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\""
                                + proposalKey + "\",\"taskKey\":\"" + taskKey
                                + "\",\"proposedTitle\":\"新标题\",\"requiredRoles\":" + rolesJson
                                + ",\"quorum\":" + quorum + ",\"expiresAt\":\"" + expiresAt
                                + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposalKey").value(proposalKey))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    private void vote(String incidentKey, String proposalKey, String role, String choice)
            throws Exception {
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/votes", incidentKey, proposalKey)
                        .header("X-Actor-Id", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"vote\":\"" + choice
                                + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void proposalQuorumEffectFlow() throws Exception {
        report("INC-D1");
        takeover("INC-D1", "alice");
        createTask("INC-D1", "alice", "T-1");
        createProposal("INC-D1", "alice", "P-1", "T-1", "[\"ops\",\"dba\"]", 2,
                "2026-09-22T01:00:00Z");

        // 登记快照：角色排序、版本固化、计数为零、差额等于法定人数
        mvc.perform(get("/api/incidents/{k}/proposals/{p}", "INC-D1", "P-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.taskKey").value("T-1"))
                .andExpect(jsonPath("$.proposal.taskVersion").value(1))
                .andExpect(jsonPath("$.proposal.requiredRoles[0]").value("dba"))
                .andExpect(jsonPath("$.proposal.requiredRoles[1]").value("ops"))
                .andExpect(jsonPath("$.proposal.quorum").value(2))
                .andExpect(jsonPath("$.proposal.supportCount").value(0))
                .andExpect(jsonPath("$.proposal.opposeCount").value(0))
                .andExpect(jsonPath("$.proposal.remainingToQuorum").value(2))
                .andExpect(jsonPath("$.proposal.decidedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.proposal.decidedTaskVersion")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.votes.length()").value(0));

        // 第一票：未达法定人数，任务标题不变
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/votes", "INC-D1", "P-1")
                        .header("X-Actor-Id", "dba")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"vote\":\"SUPPORT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.status").value("OPEN"))
                .andExpect(jsonPath("$.proposal.supportCount").value(1))
                .andExpect(jsonPath("$.proposal.remainingToQuorum").value(1))
                .andExpect(jsonPath("$.votes[0].role").value("dba"))
                .andExpect(jsonPath("$.votes[0].vote").value("SUPPORT"))
                .andExpect(jsonPath("$.votes[0].taskVersion").value(1))
                .andExpect(jsonPath("$.votes[0].status").value("CAST"));
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-D1", "T-1"))
                .andExpect(jsonPath("$.title").value("旧标题"))
                .andExpect(jsonPath("$.version").value(1));

        // 第二票达法定人数：生效并原子写入任务变更（版本不递增）
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/votes", "INC-D1", "P-1")
                        .header("X-Actor-Id", "ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"vote\":\"SUPPORT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.proposal.supportCount").value(2))
                .andExpect(jsonPath("$.proposal.remainingToQuorum").value(0))
                .andExpect(jsonPath("$.proposal.decidedAt").value("2026-09-22T00:00:00Z"))
                .andExpect(jsonPath("$.proposal.decidedTaskVersion").value(1))
                .andExpect(jsonPath("$.votes.length()").value(2))
                .andExpect(jsonPath("$.votes[1].role").value("ops"));
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-D1", "T-1"))
                .andExpect(jsonPath("$.title").value("新标题"))
                .andExpect(jsonPath("$.version").value(1));

        // 已生效提案再投票/撤回 → 409
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/votes", "INC-D1", "P-1")
                        .header("X-Actor-Id", "dba")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"vote\":\"OPPOSE\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/withdraw", "INC-D1", "P-1")
                        .header("X-Actor-Id", "dba")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict());

        // 列表查询：按登记顺序
        mvc.perform(get("/api/incidents/{k}/proposals", "INC-D1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidentKey").value("INC-D1"))
                .andExpect(jsonPath("$.proposals.length()").value(1))
                .andExpect(jsonPath("$.proposals[0].status").value("EFFECTIVE"));
    }

    @Test
    void proposalValidationErrors() throws Exception {
        report("INC-D2");
        takeover("INC-D2", "alice");
        createTask("INC-D2", "alice", "T-1");

        // 角色集合为空 → 422
        mvc.perform(post("/api/incidents/{k}/proposals", "INC-D2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-1\","
                                + "\"taskKey\":\"T-1\",\"proposedTitle\":\"t\","
                                + "\"requiredRoles\":[],\"quorum\":1,"
                                + "\"expiresAt\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"));
        // 法定人数超过角色数 → 422
        mvc.perform(post("/api/incidents/{k}/proposals", "INC-D2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-1\","
                                + "\"taskKey\":\"T-1\",\"proposedTitle\":\"t\","
                                + "\"requiredRoles\":[\"dba\",\"ops\"],\"quorum\":3,"
                                + "\"expiresAt\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity());
        // 法定人数小于 1 → 422
        mvc.perform(post("/api/incidents/{k}/proposals", "INC-D2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-1\","
                                + "\"taskKey\":\"T-1\",\"proposedTitle\":\"t\","
                                + "\"requiredRoles\":[\"dba\"],\"quorum\":0,"
                                + "\"expiresAt\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity());
        // 到期时刻早于当前 → 422
        mvc.perform(post("/api/incidents/{k}/proposals", "INC-D2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-1\","
                                + "\"taskKey\":\"T-1\",\"proposedTitle\":\"t\","
                                + "\"requiredRoles\":[\"dba\"],\"quorum\":1,"
                                + "\"expiresAt\":\"2026-09-21T23:59:59Z\"}"))
                .andExpect(status().isUnprocessableEntity());
        // expiresAt 缺失 → 400
        mvc.perform(post("/api/incidents/{k}/proposals", "INC-D2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-1\","
                                + "\"taskKey\":\"T-1\",\"proposedTitle\":\"t\","
                                + "\"requiredRoles\":[\"dba\"],\"quorum\":1}"))
                .andExpect(status().isBadRequest());
        // proposedTitle 空白 → 400
        mvc.perform(post("/api/incidents/{k}/proposals", "INC-D2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-1\","
                                + "\"taskKey\":\"T-1\",\"proposedTitle\":\" \","
                                + "\"requiredRoles\":[\"dba\"],\"quorum\":1,"
                                + "\"expiresAt\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        // 任务不存在 → 404
        mvc.perform(post("/api/incidents/{k}/proposals", "INC-D2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-1\","
                                + "\"taskKey\":\"T-9\",\"proposedTitle\":\"t\","
                                + "\"requiredRoles\":[\"dba\"],\"quorum\":1,"
                                + "\"expiresAt\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isNotFound());
        // 事件不存在 → 404
        mvc.perform(post("/api/incidents/{k}/proposals", "INC-404")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-1\","
                                + "\"taskKey\":\"T-1\",\"proposedTitle\":\"t\","
                                + "\"requiredRoles\":[\"dba\"],\"quorum\":1,"
                                + "\"expiresAt\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isNotFound());
        // 非当前指挥人 → 409
        mvc.perform(post("/api/incidents/{k}/proposals", "INC-D2")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-1\","
                                + "\"taskKey\":\"T-1\",\"proposedTitle\":\"t\","
                                + "\"requiredRoles\":[\"dba\"],\"quorum\":1,"
                                + "\"expiresAt\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isConflict());

        // 角色去重去空白后登记成功
        mvc.perform(post("/api/incidents/{k}/proposals", "INC-D2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-1\","
                                + "\"taskKey\":\"T-1\",\"proposedTitle\":\"t\","
                                + "\"requiredRoles\":[\" ops \",\"dba\",\"ops\"],\"quorum\":2,"
                                + "\"expiresAt\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requiredRoles.length()").value(2))
                .andExpect(jsonPath("$.requiredRoles[0]").value("dba"))
                .andExpect(jsonPath("$.requiredRoles[1]").value("ops"));

        // proposalKey 同键不同内容 → 409；同键同内容 → 返回首次提案
        mvc.perform(post("/api/incidents/{k}/proposals", "INC-D2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-1\","
                                + "\"taskKey\":\"T-1\",\"proposedTitle\":\"另一个标题\","
                                + "\"requiredRoles\":[\"dba\",\"ops\"],\"quorum\":2,"
                                + "\"expiresAt\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/incidents/{k}/proposals", "INC-D2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"proposalKey\":\"P-1\","
                                + "\"taskKey\":\"T-1\",\"proposedTitle\":\"t\","
                                + "\"requiredRoles\":[\"dba\",\"ops\"],\"quorum\":2,"
                                + "\"expiresAt\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposalKey").value("P-1"));
    }

    @Test
    void voteRulesAndIdempotency() throws Exception {
        report("INC-D3");
        takeover("INC-D3", "alice");
        createTask("INC-D3", "alice", "T-1");
        createProposal("INC-D3", "alice", "P-1", "T-1", "[\"dba\",\"ops\"]", 2,
                "2026-09-22T01:00:00Z");

        // 投票取向非法 → 400
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/votes", "INC-D3", "P-1")
                        .header("X-Actor-Id", "dba")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"vote\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest());
        // 角色不在集合内 → 409
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/votes", "INC-D3", "P-1")
                        .header("X-Actor-Id", "stranger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"vote\":\"SUPPORT\"}"))
                .andExpect(status().isConflict());
        // 提案不存在 → 404
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/votes", "INC-D3", "P-9")
                        .header("X-Actor-Id", "dba")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"vote\":\"SUPPORT\"}"))
                .andExpect(status().isNotFound());

        // 失败不占键：同一 commandKey 先用于 409 失败请求，修正参数后复用成功
        String reusedKey = key();
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/votes", "INC-D3", "P-1")
                        .header("X-Actor-Id", "stranger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + reusedKey + "\",\"vote\":\"SUPPORT\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/votes", "INC-D3", "P-1")
                        .header("X-Actor-Id", "dba")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + reusedKey + "\",\"vote\":\"SUPPORT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.supportCount").value(1));

        // 同键同参重放：返回首次响应快照
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/votes", "INC-D3", "P-1")
                        .header("X-Actor-Id", "dba")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + reusedKey + "\",\"vote\":\"SUPPORT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.supportCount").value(1));
        // 同键改参 → 409
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/votes", "INC-D3", "P-1")
                        .header("X-Actor-Id", "dba")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + reusedKey + "\",\"vote\":\"OPPOSE\"}"))
                .andExpect(status().isConflict());

        // 同一角色重复投票（新 commandKey）→ 409
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/votes", "INC-D3", "P-1")
                        .header("X-Actor-Id", "dba")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"vote\":\"SUPPORT\"}"))
                .andExpect(status().isConflict());

        // 反对票计入 opposeCount 但不计入生效人数
        vote("INC-D3", "P-1", "ops", "OPPOSE");
        mvc.perform(get("/api/incidents/{k}/proposals/{p}", "INC-D3", "P-1"))
                .andExpect(jsonPath("$.proposal.status").value("OPEN"))
                .andExpect(jsonPath("$.proposal.supportCount").value(1))
                .andExpect(jsonPath("$.proposal.opposeCount").value(1))
                .andExpect(jsonPath("$.proposal.remainingToQuorum").value(1));

        // 创建快照重放：提案生效后重放创建 commandKey 仍返回登记时快照
        String createKey = key();
        mvc.perform(post("/api/incidents/{k}/proposals", "INC-D3")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + createKey + "\",\"proposalKey\":\"P-3\","
                                + "\"taskKey\":\"T-1\",\"proposedTitle\":\"快照标题\","
                                + "\"requiredRoles\":[\"dba\"],\"quorum\":1,"
                                + "\"expiresAt\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));
        vote("INC-D3", "P-3", "dba", "SUPPORT");
        mvc.perform(get("/api/incidents/{k}/proposals/{p}", "INC-D3", "P-3"))
                .andExpect(jsonPath("$.proposal.status").value("EFFECTIVE"));
        mvc.perform(post("/api/incidents/{k}/proposals", "INC-D3")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + createKey + "\",\"proposalKey\":\"P-3\","
                                + "\"taskKey\":\"T-1\",\"proposedTitle\":\"快照标题\","
                                + "\"requiredRoles\":[\"dba\"],\"quorum\":1,"
                                + "\"expiresAt\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposalKey").value("P-3"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void expiryTurns410AndProposalExpired() throws Exception {
        report("INC-D4");
        takeover("INC-D4", "alice");
        createTask("INC-D4", "alice", "T-1");
        createProposal("INC-D4", "alice", "P-1", "T-1", "[\"dba\",\"ops\"]", 2,
                "2026-09-22T00:10:00Z");
        vote("INC-D4", "P-1", "dba", "SUPPORT");

        // 成功投票的 commandKey 在到期后重放仍返回首次快照（不触发 410）
        String replayKey = key();
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/votes", "INC-D4", "P-1")
                        .header("X-Actor-Id", "ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + replayKey + "\",\"vote\":\"OPPOSE\"}"))
                .andExpect(status().isOk());

        // 推进到到期时刻（左闭右开：等于到期时刻即到期）
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-22T00:10:00Z"));
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/votes", "INC-D4", "P-1")
                        .header("X-Actor-Id", "ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"vote\":\"SUPPORT\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("GONE"));

        // 到期判定已固化：提案 EXPIRED，记录裁决时刻与任务版本；历史票不改写
        mvc.perform(get("/api/incidents/{k}/proposals/{p}", "INC-D4", "P-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.status").value("EXPIRED"))
                .andExpect(jsonPath("$.proposal.decidedAt").value("2026-09-22T00:10:00Z"))
                .andExpect(jsonPath("$.proposal.decidedTaskVersion").value(1))
                .andExpect(jsonPath("$.proposal.supportCount").value(1))
                .andExpect(jsonPath("$.proposal.opposeCount").value(1))
                .andExpect(jsonPath("$.votes.length()").value(2))
                .andExpect(jsonPath("$.votes[0].status").value("CAST"));

        // 已 EXPIRED：再投票/撤回均 410
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/votes", "INC-D4", "P-1")
                        .header("X-Actor-Id", "dba")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"vote\":\"SUPPORT\"}"))
                .andExpect(status().isGone());
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/withdraw", "INC-D4", "P-1")
                        .header("X-Actor-Id", "dba")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isGone());

        // 到期前的成功响应快照重放不受 410 影响
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/votes", "INC-D4", "P-1")
                        .header("X-Actor-Id", "ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + replayKey + "\",\"vote\":\"OPPOSE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.status").value("OPEN"));

        // 任务标题未被写入（提案未生效）
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-D4", "T-1"))
                .andExpect(jsonPath("$.title").value("旧标题"));
    }

    @Test
    void withdrawFlow() throws Exception {
        report("INC-D5");
        takeover("INC-D5", "alice");
        createTask("INC-D5", "alice", "T-1");
        createProposal("INC-D5", "alice", "P-1", "T-1", "[\"dba\",\"ops\"]", 2,
                "2026-09-22T01:00:00Z");

        // 没有有效票时撤回 → 409
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/withdraw", "INC-D5", "P-1")
                        .header("X-Actor-Id", "dba")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict());

        vote("INC-D5", "P-1", "dba", "SUPPORT");
        // 撤回后计数回落，历史保留 WITHDRAWN 记录
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/withdraw", "INC-D5", "P-1")
                        .header("X-Actor-Id", "dba")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.supportCount").value(0))
                .andExpect(jsonPath("$.proposal.remainingToQuorum").value(2))
                .andExpect(jsonPath("$.votes[0].role").value("dba"))
                .andExpect(jsonPath("$.votes[0].status").value("WITHDRAWN"));
        // 重复撤回 → 409
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/withdraw", "INC-D5", "P-1")
                        .header("X-Actor-Id", "dba")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict());

        // 撤回后可重新投票（新记录），达法定人数生效
        vote("INC-D5", "P-1", "dba", "SUPPORT");
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/votes", "INC-D5", "P-1")
                        .header("X-Actor-Id", "ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"vote\":\"SUPPORT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.votes.length()").value(3))
                .andExpect(jsonPath("$.votes[0].status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.votes[1].status").value("CAST"))
                .andExpect(jsonPath("$.votes[2].status").value("CAST"));
    }

    @Test
    void reviseStalesOldVersionVotes() throws Exception {
        report("INC-D6");
        takeover("INC-D6", "alice");
        createTask("INC-D6", "alice", "T-1");
        createProposal("INC-D6", "alice", "P-1", "T-1", "[\"dba\",\"ops\",\"sec\"]", 2,
                "2026-09-22T01:00:00Z");
        vote("INC-D6", "P-1", "dba", "SUPPORT");
        vote("INC-D6", "P-1", "ops", "OPPOSE");

        // 非指挥人修订 → 409；任务不存在 → 404
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/revise", "INC-D6", "T-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"title\":\"v2\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/revise", "INC-D6", "T-9")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"title\":\"v2\"}"))
                .andExpect(status().isNotFound());

        // 修订：版本 +1，旧版本票转 STALE，不计入新版本
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/revise", "INC-D6", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"title\":\"v2 标题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.title").value("v2 标题"));
        mvc.perform(get("/api/incidents/{k}/proposals/{p}", "INC-D6", "P-1"))
                .andExpect(jsonPath("$.proposal.status").value("OPEN"))
                .andExpect(jsonPath("$.proposal.taskVersion").value(1))
                .andExpect(jsonPath("$.proposal.supportCount").value(0))
                .andExpect(jsonPath("$.proposal.opposeCount").value(0))
                .andExpect(jsonPath("$.proposal.remainingToQuorum").value(2))
                .andExpect(jsonPath("$.votes[0].status").value("STALE"))
                .andExpect(jsonPath("$.votes[0].taskVersion").value(1))
                .andExpect(jsonPath("$.votes[1].status").value("STALE"));

        // 新版本重新投票：历史 STALE 记录保留，新票固化新版本号
        vote("INC-D6", "P-1", "dba", "SUPPORT");
        mvc.perform(post("/api/incidents/{k}/proposals/{p}/votes", "INC-D6", "P-1")
                        .header("X-Actor-Id", "sec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"vote\":\"SUPPORT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.proposal.decidedTaskVersion").value(2))
                .andExpect(jsonPath("$.votes.length()").value(4))
                .andExpect(jsonPath("$.votes[2].taskVersion").value(2))
                .andExpect(jsonPath("$.votes[3].taskVersion").value(2));
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-D6", "T-1"))
                .andExpect(jsonPath("$.title").value("新标题"))
                .andExpect(jsonPath("$.version").value(2));

        // 终态任务不能修订：先取消再修订 → 409
        createTask("INC-D6", "alice", "T-2");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/cancel", "INC-D6", "T-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/revise", "INC-D6", "T-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"title\":\"v2\"}"))
                .andExpect(status().isConflict());
    }
}

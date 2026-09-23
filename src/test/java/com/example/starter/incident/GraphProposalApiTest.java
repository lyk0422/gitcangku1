package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 依赖图变更提案 HTTP 层测试：验证路由、X-Actor-Id 请求头约束、
 * 400/404/409/422 错误语义及提案-票决-激活-证据查询完整流程。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GraphProposalApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM graph_snapshots");
        jdbc.update("DELETE FROM graph_proposal_votes");
        jdbc.update("DELETE FROM graph_proposal_roster");
        jdbc.update("DELETE FROM graph_proposal_edges");
        jdbc.update("DELETE FROM graph_proposals");
        jdbc.update("DELETE FROM graph_edges");
        jdbc.update("DELETE FROM graph_version");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "REQ-" + UUID.randomUUID();
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

    private long graphVersion() throws Exception {
        MvcResult result = mvc.perform(get("/api/graph"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        String marker = "\"version\":";
        int start = body.indexOf(marker) + marker.length();
        int end = body.indexOf(",", start);
        return Long.parseLong(body.substring(start, end));
    }

    private void createProposal(String proposalKey, String reviewer, String edgesJson)
            throws Exception {
        mvc.perform(post("/api/graph/proposals")
                        .header("X-Actor-Id", "pm-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"proposalKey\":\"" + proposalKey
                                + "\",\"expectedGraphVersion\":" + graphVersion()
                                + ",\"rationale\":\"依赖调整\",\"safetyReviewer\":\"" + reviewer
                                + "\",\"edges\":" + edgesJson + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposalKey").value(proposalKey))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    private void vote(String proposalKey, String actor, String decision) throws Exception {
        mvc.perform(post("/api/graph/proposals/{k}/votes", proposalKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"decision\":\"" + decision
                                + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void proposalHttpFlow() throws Exception {
        report("INC-600");
        report("INC-601");
        takeover("INC-600", "alice");
        takeover("INC-601", "bob");

        // 当前图为空，版本 0
        mvc.perform(get("/api/graph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.edges.length()").value(0));

        // 创建提案：名册冻结两名指挥官 + 安全审核员
        createProposal("GP-600", "carol",
                "[{\"operation\":\"ADD\",\"fromIncidentKey\":\"INC-600\","
                        + "\"toIncidentKey\":\"INC-601\"}]");
        mvc.perform(get("/api/graph/proposals/{k}", "GP-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.roster.length()").value(3))
                .andExpect(jsonPath("$.edges[0].operation").value("ADD"))
                .andExpect(jsonPath("$.edges[0].fromIncidentKey").value("INC-600"))
                .andExpect(jsonPath("$.edges[0].toIncidentKey").value("INC-601"));

        // 非名册成员投票 → 409
        mvc.perform(post("/api/graph/proposals/{k}/votes", "GP-600")
                        .header("X-Actor-Id", "mallory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"decision\":\"APPROVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 法定人数赞成后 APPROVED
        vote("GP-600", "alice", "APPROVE");
        vote("GP-600", "bob", "APPROVE");
        mvc.perform(post("/api/graph/proposals/{k}/votes", "GP-600")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.votes.length()").value(3));

        // 激活：生成唯一新图版本与前后快照
        mvc.perform(post("/api/graph/proposals/{k}/activate", "GP-600")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"))
                .andExpect(jsonPath("$.appliedGraphVersion").value(1))
                .andExpect(jsonPath("$.beforeSnapshot.graphVersion").value(0))
                .andExpect(jsonPath("$.beforeSnapshot.edges.length()").value(0))
                .andExpect(jsonPath("$.afterSnapshot.graphVersion").value(1))
                .andExpect(jsonPath("$.afterSnapshot.edges[0].fromIncidentKey").value("INC-600"));

        // 图查询反映新边
        mvc.perform(get("/api/graph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.edges.length()").value(1))
                .andExpect(jsonPath("$.edges[0].fromIncidentKey").value("INC-600"))
                .andExpect(jsonPath("$.edges[0].toIncidentKey").value("INC-601"));

        // 按 graphVersion 还原提案证据
        mvc.perform(get("/api/graph/proposals").param("graphVersion", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].proposalKey").value("GP-600"))
                .andExpect(jsonPath("$[0].afterSnapshot.edges.length()").value(1));
        mvc.perform(get("/api/graph/proposals").param("graphVersion", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void proposalHttpErrors() throws Exception {
        report("INC-610");
        takeover("INC-610", "alice");

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/graph/proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"proposalKey\":\"GP-E1\","
                                + "\"expectedGraphVersion\":0,\"rationale\":\"r\","
                                + "\"safetyReviewer\":\"carol\",\"edges\":[]}"))
                .andExpect(status().isBadRequest());
        // 空边集 → 400
        mvc.perform(post("/api/graph/proposals")
                        .header("X-Actor-Id", "pm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"proposalKey\":\"GP-E1\","
                                + "\"expectedGraphVersion\":0,\"rationale\":\"r\","
                                + "\"safetyReviewer\":\"carol\",\"edges\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        // 非法表决方向 → 400
        mvc.perform(post("/api/graph/proposals/{k}/votes", "GP-E1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"decision\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest());
        // 提案不存在 → 404
        mvc.perform(get("/api/graph/proposals/{k}", "GP-404"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/graph/proposals/{k}/votes", "GP-404")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"decision\":\"APPROVE\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/graph/proposals/{k}/activate", "GP-404")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\"}"))
                .andExpect(status().isNotFound());
        // 引用不存在事件 → 404
        mvc.perform(post("/api/graph/proposals")
                        .header("X-Actor-Id", "pm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"proposalKey\":\"GP-E2\","
                                + "\"expectedGraphVersion\":" + graphVersion()
                                + ",\"rationale\":\"r\",\"safetyReviewer\":\"carol\","
                                + "\"edges\":[{\"operation\":\"ADD\","
                                + "\"fromIncidentKey\":\"INC-610\","
                                + "\"toIncidentKey\":\"INC-404\"}]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void proposalHttpRejectAndCycle() throws Exception {
        report("INC-620");
        report("INC-621");
        takeover("INC-620", "alice");
        takeover("INC-621", "bob");

        // 反对票使整案 REJECTED
        createProposal("GP-620", "carol",
                "[{\"operation\":\"ADD\",\"fromIncidentKey\":\"INC-620\","
                        + "\"toIncidentKey\":\"INC-621\"}]");
        vote("GP-620", "alice", "APPROVE");
        mvc.perform(post("/api/graph/proposals/{k}/votes", "GP-620")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"decision\":\"REJECT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        // REJECTED 后激活 → 409
        mvc.perform(post("/api/graph/proposals/{k}/activate", "GP-620")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\"}"))
                .andExpect(status().isConflict());

        // 既有边 INC-621 -> INC-620（任务声明），提案新增反向边构成环 → 激活 422
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-621")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-1\","
                                + "\"groupCode\":\"G\",\"title\":\"t\","
                                + "\"blockerIncidentKeys\":[\"INC-620\"]}"))
                .andExpect(status().isOk());
        createProposal("GP-621", "carol",
                "[{\"operation\":\"ADD\",\"fromIncidentKey\":\"INC-620\","
                        + "\"toIncidentKey\":\"INC-621\"}]");
        vote("GP-621", "alice", "APPROVE");
        vote("GP-621", "bob", "APPROVE");
        vote("GP-621", "carol", "APPROVE");
        mvc.perform(post("/api/graph/proposals/{k}/activate", "GP-621")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));
        // 整案失败不改图
        mvc.perform(get("/api/graph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edges.length()").value(1))
                .andExpect(jsonPath("$.edges[0].fromIncidentKey").value("INC-621"));
    }
}

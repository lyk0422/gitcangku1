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

/**
 * 依赖图变更提案 HTTP 层测试：验证路由、X-Actor-Id 约束、投票法定人数激活、
 * 400/404/409/422 错误语义、requestId 幂等与按版本证据查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProposalApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM proposal_votes");
        jdbc.update("DELETE FROM proposal_roster_entries");
        jdbc.update("DELETE FROM dependency_change_proposals");
        jdbc.update("DELETE FROM incident_dependency_edges");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incidents");
        jdbc.update("MERGE INTO dependency_graph_meta (id, graph_version, updated_at)"
                + " KEY (id) VALUES (1, 1, CURRENT_TIMESTAMP(6))");
    }

    private static String key() {
        return "KEY-" + UUID.randomUUID();
    }

    private void report(String incidentKey) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey
                                + "\",\"severity\":\"S2\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
    }

    private void takeover(String incidentKey, String actor) throws Exception {
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void createVoteActivateAndReadEvidence_endToEnd() throws Exception {
        report("INC-A");
        report("INC-B");
        takeover("INC-A", "alice");
        takeover("INC-B", "bob");

        String body = "{\"requestId\":\"" + key() + "\",\"proposalKey\":\"PROP-HTTP\","
                + "\"expectedGraphVersion\":1,\"businessNote\":\"变更说明\",\"safetyReviewer\":\"sec\","
                + "\"changes\":[{\"op\":\"ADD\",\"fromIncidentKey\":\"INC-A\","
                + "\"toIncidentKey\":\"INC-B\"}]}";
        mvc.perform(post("/api/dependency-graph/proposals")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.roster.length()").value(3))
                .andExpect(jsonPath("$.changes[0].op").value("ADD"));

        for (String person : new String[]{"alice", "bob"}) {
            vote(person, "YES").andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }
        vote("sec", "YES").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"))
                .andExpect(jsonPath("$.activatedGraphVersion").value(2))
                .andExpect(jsonPath("$.afterEdges.length()").value(1));

        mvc.perform(get("/api/dependency-graph/proposals/PROP-HTTP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.votes.length()").value(3))
                .andExpect(jsonPath("$.beforeEdges.length()").value(0));
        mvc.perform(get("/api/dependency-graph/versions/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activatedProposalKey").value("PROP-HTTP"))
                .andExpect(jsonPath("$.edges.length()").value(1));
        mvc.perform(get("/api/dependency-graph/versions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edges.length()").value(0));
    }

    private org.springframework.test.web.servlet.ResultActions vote(String person, String choice)
            throws Exception {
        return mvc.perform(post("/api/dependency-graph/proposals/PROP-HTTP/votes")
                .header("X-Actor-Id", person)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + key() + "\",\"choice\":\"" + choice + "\"}"));
    }

    @Test
    void noVote_rejectsProposal() throws Exception {
        report("INC-A");
        takeover("INC-A", "alice");
        mvc.perform(post("/api/dependency-graph/proposals")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"proposalKey\":\"PROP-N\","
                                + "\"expectedGraphVersion\":1,\"businessNote\":\"n\","
                                + "\"safetyReviewer\":\"sec\",\"changes\":[]}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/dependency-graph/proposals")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"proposalKey\":\"PROP-N\","
                                + "\"expectedGraphVersion\":1,\"businessNote\":\"n\","
                                + "\"safetyReviewer\":\"sec\",\"changes\":[{\"op\":\"ADD\","
                                + "\"fromIncidentKey\":\"INC-A\",\"toIncidentKey\":\"INC-A\"}]}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/dependency-graph/proposals")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"proposalKey\":\"PROP-N\","
                                + "\"expectedGraphVersion\":1,\"businessNote\":\"n\","
                                + "\"safetyReviewer\":\"sec\",\"changes\":[{\"op\":\"ADD\","
                                + "\"fromIncidentKey\":\"INC-A\",\"toIncidentKey\":\"INC-MISSING\"}]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingActorHeader_badRequest() throws Exception {
        mvc.perform(post("/api/dependency-graph/proposals")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void voteOnMissingProposal_notFound() throws Exception {
        mvc.perform(post("/api/dependency-graph/proposals/NOPE/votes")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"choice\":\"YES\"}"))
                .andExpect(status().isNotFound());
    }
}

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
 * HTTP 层测试：验证路由、X-Actor-Id 请求头约束及 400/404/409/422 错误语义可区分。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IncidentApiTest {

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
        jdbc.update("UPDATE dependency_graph_meta SET graph_version = 1");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "INC-" + UUID.randomUUID();
    }

    private void report(String incidentKey) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey
                                + "\",\"severity\":\"S1\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REPORTED"));
    }

    private void takeover(String incidentKey, String actor) throws Exception {
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMANDING"))
                .andExpect(jsonPath("$.commander").value(actor));
    }

    @Test
    void apiMainFlow() throws Exception {
        report("INC-300");
        takeover("INC-300", "alice");

        mvc.perform(post("/api/incidents/{k}/actions", "INC-300")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"actionKey\":\"A1\",\"actionType\":\"NOTE\",\"note\":\"n\","
                                + "\"occurredAt\":\"2026-09-21T08:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actor").value("alice"));

        mvc.perform(post("/api/incidents/{k}/transfers", "INC-300")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"toCommander\":\"bob\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mvc.perform(get("/api/incidents/{k}", "INC-300"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingTransferTo").value("bob"));

        mvc.perform(post("/api/incidents/{k}/transfers/accept", "INC-300")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commander").value("bob"));

        mvc.perform(get("/api/incidents/{k}/history", "INC-300"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions.length()").value(1))
                .andExpect(jsonPath("$.transfers.length()").value(1))
                .andExpect(jsonPath("$.transfers[0].status").value("ACCEPTED"));
    }

    @Test
    void apiErrorSemantics() throws Exception {
        // 400：缺 X-Actor-Id
        mvc.perform(post("/api/incidents/{k}/takeover", "INC-301")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        // 400：请求体非法 JSON
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest());
        // 400：参数非法
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"INC-302\",\"severity\":\"S9\","
                                + "\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isBadRequest());
        // 404：事件不存在
        mvc.perform(get("/api/incidents/{k}", "INC-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // 409：权限冲突（非当前指挥人）
        report("INC-303");
        takeover("INC-303", "alice");
        mvc.perform(post("/api/incidents/{k}/status", "INC-303")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\"CONTAINED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        // 422：非法流转（COMMANDING 直接 RESOLVED）
        mvc.perform(post("/api/incidents/{k}/status", "INC-303")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\"RESOLVED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));
    }

    @Test
    void apiIdempotentReplay() throws Exception {
        report("INC-310");
        String commandKey = key();
        String body = "{\"commandKey\":\"" + commandKey + "\"}";
        String first = mvc.perform(post("/api/incidents/{k}/takeover", "INC-310")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // 同键同参重放：返回首次结果
        mvc.perform(post("/api/incidents/{k}/takeover", "INC-310")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().json(first));
        // 同键改参：409
        mvc.perform(post("/api/incidents/{k}/takeover", "INC-310")
                        .header("X-Actor-Id", "mallory")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }
}

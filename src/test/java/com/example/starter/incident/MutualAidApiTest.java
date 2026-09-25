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
 * 跨事件互助交接 HTTP 层测试：验证资源登记、批量交接、责任查询路由与 X-Actor-Id 约束，
 * 以及 422 可区分错误码（目标等于来源、接收人无权限）和失败不留交接行。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MutualAidApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM handoff_settlements");
        jdbc.update("DELETE FROM resource_handoffs");
        jdbc.update("DELETE FROM incident_receiving_delegates");
        jdbc.update("DELETE FROM incident_resources");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void reportAndTakeover(String incidentKey, String actor) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey
                                + "\",\"severity\":\"S2\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void registerHandoffAndQueryResponsibility_overHttp() throws Exception {
        reportAndTakeover("INC-A", "alice");
        reportAndTakeover("INC-B", "bob");

        mvc.perform(post("/api/incidents/INC-A/resources")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"resourceKey\":\"RES-1\",\"label\":\"挖掘机\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.ownerIncidentKey").value("INC-A"));

        String body = "{\"targetIncidentKey\":\"INC-B\",\"receiver\":\"bob\",\"items\":["
                + "{\"handoffKey\":\"HK-1\",\"resourceKey\":\"RES-1\","
                + "\"leaseStart\":\"2026-09-26T00:00:00Z\","
                + "\"leaseEnd\":\"2026-09-26T01:00:00Z\"}]}";
        mvc.perform(post("/api/incidents/INC-A/handoffs")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.handoffs[0].handoffKey").value("HK-1"))
                .andExpect(jsonPath("$.handoffs[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.handoffs[0].receiver").value("bob"));

        mvc.perform(get("/api/resources/RES-1/responsibility"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responsibleParty").value("TARGET"))
                .andExpect(jsonPath("$.responsibleIncidentKey").value("INC-B"))
                .andExpect(jsonPath("$.activeHandoffKey").value("HK-1"));

        mvc.perform(get("/api/resources/RES-1/settlements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void handoffToSelf_returns422WithCode() throws Exception {
        reportAndTakeover("INC-A", "alice");
        mvc.perform(post("/api/incidents/INC-A/resources")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"resourceKey\":\"RES-1\",\"label\":\"x\"}"))
                .andExpect(status().isCreated());

        String body = "{\"targetIncidentKey\":\"INC-A\",\"receiver\":\"alice\",\"items\":["
                + "{\"handoffKey\":\"HK-BAD\",\"resourceKey\":\"RES-1\","
                + "\"leaseStart\":\"2026-09-26T00:00:00Z\","
                + "\"leaseEnd\":\"2026-09-26T01:00:00Z\"}]}";
        mvc.perform(post("/api/incidents/INC-A/handoffs")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("HANDOFF_SAME_INCIDENT"));
    }

    @Test
    void handoffUnauthorizedReceiver_returns422AndNoRow() throws Exception {
        reportAndTakeover("INC-A", "alice");
        reportAndTakeover("INC-B", "bob");
        mvc.perform(post("/api/incidents/INC-A/resources")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"resourceKey\":\"RES-1\",\"label\":\"x\"}"))
                .andExpect(status().isCreated());

        String body = "{\"targetIncidentKey\":\"INC-B\",\"receiver\":\"carol\",\"items\":["
                + "{\"handoffKey\":\"HK-NOAUTH\",\"resourceKey\":\"RES-1\","
                + "\"leaseStart\":\"2026-09-26T00:00:00Z\","
                + "\"leaseEnd\":\"2026-09-26T01:00:00Z\"}]}";
        mvc.perform(post("/api/incidents/INC-A/handoffs")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("HANDOFF_RECEIVER_UNAUTHORIZED"));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_handoffs WHERE handoff_key='HK-NOAUTH'",
                Integer.class);
        org.assertj.core.api.Assertions.assertThat(count).isZero();
    }

    @Test
    void missingActorHeader_returns400() throws Exception {
        reportAndTakeover("INC-A", "alice");
        mvc.perform(post("/api/incidents/INC-A/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"resourceKey\":\"RES-1\",\"label\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }
}

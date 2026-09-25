package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * 外部机构回执 HTTP 层测试：验证配置/回执/查询路由、X-Actor-Id 约束、
 * 400/404/409/422 错误语义及 422 details 未确认机构明细、任务门禁原因 JSON。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgencyAckApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_agency_receipts");
        jdbc.update("DELETE FROM incident_agency_configs");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
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
    }

    @Test
    void agencyHttpFlow() throws Exception {
        commanding("INC-600", "alice");

        // 配置必需机构：去重排序，版本 1
        mvc.perform(put("/api/incidents/{k}/agency-config", "INC-600")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,"
                                + "\"agencyCodes\":[\"POLICE\",\"FIRE\",\"POLICE\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.agencyCodes[0]").value("FIRE"))
                .andExpect(jsonPath("$.agencyCodes[1]").value("POLICE"))
                .andExpect(jsonPath("$.pendingAgencies.length()").value(2));

        // 缺少 X-Actor-Id → 400
        mvc.perform(put("/api/incidents/{k}/agency-config", "INC-600")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"agencyCodes\":[]}"))
                .andExpect(status().isBadRequest());

        // 版本不一致 → 409
        mvc.perform(put("/api/incidents/{k}/agency-config", "INC-600")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"agencyCodes\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 创建 HIGH 任务：门禁原因可查
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-600")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-H\","
                                + "\"groupCode\":\"G\",\"title\":\"高危\","
                                + "\"blockerIncidentKeys\":[],\"priority\":\"HIGH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.agencyGate.gated").value(true))
                .andExpect(jsonPath("$.agencyGate.reason").value("AGENCY_ACK_PENDING"));

        // 机构确认回执
        mvc.perform(post("/api/incidents/{k}/agency-acks", "INC-600")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ackKey\":\"" + key() + "\",\"agencyCode\":\"FIRE\","
                                + "\"configVersion\":1,\"type\":\"CONFIRM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agencyCode").value("FIRE"))
                .andExpect(jsonPath("$.type").value("CONFIRM"))
                .andExpect(jsonPath("$.configVersion").value(1));

        // 查询配置版本与回执
        mvc.perform(get("/api/incidents/{k}/agency-config", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.receipts.length()").value(1))
                .andExpect(jsonPath("$.receipts[0].agencyCode").value("FIRE"))
                .andExpect(jsonPath("$.pendingAgencies[0]").value("POLICE"));

        // HIGH 任务完成被门禁拦截：422 + 未确认机构明细
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-600", "T-H")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGENCY_GATE"))
                .andExpect(jsonPath("$.details[0]").value("POLICE"));

        // 拒绝缺说明 → 400
        mvc.perform(post("/api/incidents/{k}/agency-acks", "INC-600")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ackKey\":\"" + key() + "\",\"agencyCode\":\"POLICE\","
                                + "\"configVersion\":1,\"type\":\"REJECT\"}"))
                .andExpect(status().isBadRequest());

        // 机构拒绝 → 事件进入 EXTERNAL_BLOCKED
        mvc.perform(post("/api/incidents/{k}/agency-acks", "INC-600")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ackKey\":\"" + key() + "\",\"agencyCode\":\"POLICE\","
                                + "\"configVersion\":1,\"type\":\"REJECT\","
                                + "\"reason\":\"无法支援\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("REJECT"))
                .andExpect(jsonPath("$.reason").value("无法支援"));
        mvc.perform(get("/api/incidents/{k}", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXTERNAL_BLOCKED"))
                .andExpect(jsonPath("$.blockedFrom").value("COMMANDING"));

        // 任务门禁原因变为 EXTERNAL_BLOCKED
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-600", "T-H"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agencyGate.gated").value(true))
                .andExpect(jsonPath("$.agencyGate.reason").value("EXTERNAL_BLOCKED"))
                .andExpect(jsonPath("$.agencyGate.rejectedAgencies[0]").value("POLICE"));

        // 替换配置（空集合）→ 恢复原状态，门禁解除
        mvc.perform(put("/api/incidents/{k}/agency-config", "INC-600")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"agencyCodes\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.incidentStatus").value("COMMANDING"));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-600", "T-H")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    void agencyHttp_notFound() throws Exception {
        mvc.perform(get("/api/incidents/{k}/agency-config", "INC-404"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/incidents/{k}/agency-config", "INC-404")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"agencyCodes\":[]}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/incidents/{k}/agency-acks", "INC-404")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ackKey\":\"" + key() + "\",\"agencyCode\":\"FIRE\","
                                + "\"configVersion\":1,\"type\":\"CONFIRM\"}"))
                .andExpect(status().isNotFound());
    }
}

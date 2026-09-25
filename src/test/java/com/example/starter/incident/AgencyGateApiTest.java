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
 * 外部机构回执门禁 HTTP 层测试：验证配置/回执/查询路由、X-Actor-Id 约束、
 * 400/404/409/422 错误语义及 422 响应的未确认机构明细。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgencyGateApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM agency_ack_keys");
        jdbc.update("DELETE FROM incident_agency_acks");
        jdbc.update("DELETE FROM incident_agency_configs");
        jdbc.update("DELETE FROM command_keys");
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

    private static String ackKey() {
        return "ACK-" + UUID.randomUUID();
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

    private void configure(String incidentKey, String actor, int expected, String codesJson)
            throws Exception {
        mvc.perform(post("/api/incidents/{k}/agency-config", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"expectedVersion\":" + expected
                                + ",\"agencyCodes\":" + codesJson + "}"))
                .andExpect(status().isOk());
    }

    private void ack(String incidentKey, String actor, String agency, String type, String reason)
            throws Exception {
        String reasonJson = reason == null ? "null" : "\"" + reason + "\"";
        mvc.perform(post("/api/incidents/{k}/agency-acks", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ackKey\":\"" + ackKey() + "\",\"agencyCode\":\"" + agency
                                + "\",\"ackType\":\"" + type + "\",\"reason\":" + reasonJson + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void agencyGateHttpFlow() throws Exception {
        report("INC-600");
        takeover("INC-600", "alice");

        // 配置两个必需机构（乱序+重复 → 去重排序）
        mvc.perform(post("/api/incidents/{k}/agency-config", "INC-600")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"expectedVersion\":0,"
                                + "\"agencyCodes\":[\"B\",\"A\",\"A\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.configs[0].agencyCodes[0]").value("A"))
                .andExpect(jsonPath("$.configs[0].agencyCodes[1]").value("B"))
                .andExpect(jsonPath("$.configs[0].current").value(true));

        // 创建 HIGH 任务
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-600")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-H\","
                                + "\"groupCode\":\"NET\",\"title\":\"高优\",\"priority\":\"HIGH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.gateReason[0]").value("A"))
                .andExpect(jsonPath("$.gateReason[1]").value("B"));

        // 未确认前完成 HIGH → 422 且 details 列出未确认机构
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-600", "T-H")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGENCY_GATE"))
                .andExpect(jsonPath("$.details[0]").value("A"))
                .andExpect(jsonPath("$.details[1]").value("B"));

        // 机构确认
        ack("INC-600", "agency-A", "A", "CONFIRM", null);
        ack("INC-600", "agency-B", "B", "CONFIRM", null);

        // 查询配置版本与回执
        mvc.perform(get("/api/incidents/{k}/agency-gate", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidentKey").value("INC-600"))
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.acks.length()").value(2))
                .andExpect(jsonPath("$.acks[0].agencyCode").value("A"))
                .andExpect(jsonPath("$.acks[0].ackType").value("CONFIRM"));

        // 全部确认后 HIGH 可完成
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-600", "T-H")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    void rejectBlocksAndReconfigureRestores() throws Exception {
        report("INC-601");
        takeover("INC-601", "alice");
        configure("INC-601", "alice", 0, "[\"A\"]");
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-601")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-H\","
                                + "\"groupCode\":\"NET\",\"title\":\"高优\",\"priority\":\"HIGH\"}"))
                .andExpect(status().isOk());

        // 拒绝 → 事件 EXTERNAL_BLOCKED
        ack("INC-601", "agency-A", "A", "REJECT", "资源不足");
        mvc.perform(get("/api/incidents/{k}", "INC-601"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXTERNAL_BLOCKED"));

        // HIGH 完成 → 422
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-601", "T-H")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 替换为空配置 → 阻断解除，恢复 COMMANDING
        configure("INC-601", "alice", 1, "[]");
        mvc.perform(get("/api/incidents/{k}", "INC-601"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMANDING"));

        // 历史回执保留且归属 v1
        mvc.perform(get("/api/incidents/{k}/agency-gate", "INC-601"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.acks.length()").value(1))
                .andExpect(jsonPath("$.acks[0].configVersion").value(1))
                .andExpect(jsonPath("$.acks[0].ackType").value("REJECT"))
                .andExpect(jsonPath("$.acks[0].reason").value("资源不足"));
    }

    @Test
    void agencyGateHttpErrors() throws Exception {
        report("INC-602");
        takeover("INC-602", "alice");

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/agency-config", "INC-602")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"agencyCodes\":[\"A\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/incidents/{k}/agency-acks", "INC-602")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ackKey\":\"" + ackKey() + "\",\"agencyCode\":\"A\","
                                + "\"ackType\":\"CONFIRM\"}"))
                .andExpect(status().isBadRequest());
        // 事件不存在 → 404
        mvc.perform(post("/api/incidents/{k}/agency-config", "INC-404")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"agencyCodes\":[\"A\"]}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents/{k}/agency-gate", "INC-404"))
                .andExpect(status().isNotFound());
        // 超过 5 个机构 → 400
        mvc.perform(post("/api/incidents/{k}/agency-config", "INC-602")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"expectedVersion\":0,"
                                + "\"agencyCodes\":[\"A\",\"B\",\"C\",\"D\",\"E\",\"F\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        // 版本不符 → 409
        configure("INC-602", "alice", 0, "[\"A\"]");
        mvc.perform(post("/api/incidents/{k}/agency-config", "INC-602")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"expectedVersion\":0,"
                                + "\"agencyCodes\":[\"B\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        // REJECT 缺少说明 → 400
        mvc.perform(post("/api/incidents/{k}/agency-acks", "INC-602")
                        .header("X-Actor-Id", "agency-A")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ackKey\":\"" + ackKey() + "\",\"agencyCode\":\"A\","
                                + "\"ackType\":\"REJECT\"}"))
                .andExpect(status().isBadRequest());
        // 重复终态回执 → 409
        ack("INC-602", "agency-A", "A", "CONFIRM", null);
        mvc.perform(post("/api/incidents/{k}/agency-acks", "INC-602")
                        .header("X-Actor-Id", "agency-A")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ackKey\":\"" + ackKey() + "\",\"agencyCode\":\"A\","
                                + "\"ackType\":\"CONFIRM\"}"))
                .andExpect(status().isConflict());
    }
}

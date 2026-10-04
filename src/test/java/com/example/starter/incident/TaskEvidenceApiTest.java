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
 * 任务验收证据链 HTTP 层测试：验证证据路由、X-Actor-Id 请求头约束、
 * 400/404/409/422 错误语义及 422 的缺失类别结构化明细。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaskEvidenceApiTest {

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
        jdbc.update("DELETE FROM incident_task_evidences");
        jdbc.update("DELETE FROM incident_task_required_categories");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "REQ-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey
                                + "\",\"severity\":\"S1\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", commander)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", commander)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-1\","
                                + "\"groupCode\":\"DB\",\"title\":\"合成任务\","
                                + "\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isOk());
    }

    private void setRequirements(String incidentKey, String actor, int version,
                                 String categoriesJson) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidence-requirements",
                        incidentKey, "T-1")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"expectedTaskVersion\":"
                                + version + ",\"categories\":" + categoriesJson + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirementVersion").value(version + 1));
    }

    private void upload(String incidentKey, String actor, String evidenceKey, String category)
            throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidences", incidentKey, "T-1")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"evidenceKey\":\""
                                + evidenceKey + "\",\"category\":\"" + category
                                + "\",\"note\":\"合成说明\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    private void confirm(String incidentKey, String actor, String evidenceKey) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidences/{e}/confirm",
                        incidentKey, "T-1", evidenceKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void evidenceHttpFlow() throws Exception {
        commanding("INC-600", "alice");
        setRequirements("INC-600", "alice", 0, "[\"PHOTO\",\"READING\"]");
        upload("INC-600", "executor-1", "EV-1", "PHOTO");
        confirm("INC-600", "verifier-1", "EV-1");

        // 缺 READING：完成 422，details 返回缺失类别
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-600", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_INSUFFICIENT"))
                .andExpect(jsonPath("$.details[0]").value("READING"));

        // 补齐 READING 后完成成功
        upload("INC-600", "executor-2", "EV-2", "READING");
        confirm("INC-600", "verifier-1", "EV-2");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-600", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.requiredCategories[0]").value("PHOTO"));

        // 证据链查询：两条证据均已确认且冻结
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/evidences", "INC-600", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskKey").value("T-1"))
                .andExpect(jsonPath("$.requirementVersion").value(1))
                .andExpect(jsonPath("$.evidences.length()").value(2))
                .andExpect(jsonPath("$.evidences[0].frozen").value(true))
                .andExpect(jsonPath("$.evidences[1].confirmedBy").value("verifier-1"));
    }

    @Test
    void evidenceHttpErrors() throws Exception {
        commanding("INC-601", "alice");
        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidence-requirements", "INC-601", "T-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key()
                                + "\",\"expectedTaskVersion\":0,\"categories\":[\"PHOTO\"]}"))
                .andExpect(status().isBadRequest());
        // 非指挥人配置 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidence-requirements", "INC-601", "T-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key()
                                + "\",\"expectedTaskVersion\":0,\"categories\":[\"PHOTO\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        // 类别超上限 → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidence-requirements", "INC-601", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"expectedTaskVersion\":0,"
                                + "\"categories\":[\"A\",\"B\",\"C\",\"D\",\"E\",\"F\"]}"))
                .andExpect(status().isBadRequest());
        // 任务不存在 → 404
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidences", "INC-601", "T-9")
                        .header("X-Actor-Id", "executor-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"evidenceKey\":\"EV-1\","
                                + "\"category\":\"PHOTO\",\"note\":\"n\"}"))
                .andExpect(status().isNotFound());
        // 上传人自验 → 409
        upload("INC-601", "executor-1", "EV-1", "PHOTO");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidences/{e}/confirm",
                        "INC-601", "T-1", "EV-1")
                        .header("X-Actor-Id", "executor-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\"}"))
                .andExpect(status().isConflict());
        // 非指挥人作废 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidences/{e}/void",
                        "INC-601", "T-1", "EV-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\"}"))
                .andExpect(status().isConflict());
        // 指挥人作废成功
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidences/{e}/void",
                        "INC-601", "T-1", "EV-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"))
                .andExpect(jsonPath("$.voidedBy").value("alice"));
    }
}
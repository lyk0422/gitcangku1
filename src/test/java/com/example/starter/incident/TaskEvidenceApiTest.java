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
 * 400/404/409/422 错误语义及 details 结构化明细（缺失证据类别列表）。
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
        jdbc.update("DELETE FROM task_evidence");
        jdbc.update("DELETE FROM task_required_categories");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "REQ-" + UUID.randomUUID();
    }

    private void reportAndTakeover(String incidentKey, String commander) throws Exception {
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
    }

    private void createTask(String incidentKey, String actor, String taskKey) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"DB\",\"title\":\"处置\",\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isOk());
    }

    private void configRequirements(String incidentKey, String taskKey, String actor,
                                    long expectedVersion, String categoriesJson) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidence-requirements",
                        incidentKey, taskKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"expectedTaskVersion\":"
                                + expectedVersion + ",\"categories\":" + categoriesJson + "}"))
                .andExpect(status().isOk());
    }

    private void upload(String incidentKey, String taskKey, String actor, String evidenceNo,
                        String category) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidence", incidentKey, taskKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"evidenceNo\":\"" + evidenceNo
                                + "\",\"category\":\"" + category + "\",\"description\":\"合成证据\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    private void confirm(String incidentKey, String taskKey, String actor,
                         String evidenceNo) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidence/{e}/confirm",
                        incidentKey, taskKey, evidenceNo)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void evidenceHttpFlow() throws Exception {
        reportAndTakeover("INC-600", "alice");
        createTask("INC-600", "alice", "T-1");

        // 配置必需类别：版本 1 升到 2
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidence-requirements", "INC-600", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key()
                                + "\",\"expectedTaskVersion\":1,\"categories\":[\"PHOTO\",\"READING\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskKey").value("T-1"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.categories[0]").value("PHOTO"))
                .andExpect(jsonPath("$.categories[1]").value("READING"));

        // 上传与确认（验收人与上传人不同）
        upload("INC-600", "T-1", "exec-1", "EV-1", "PHOTO");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidence/{e}/confirm",
                        "INC-600", "T-1", "EV-1")
                        .header("X-Actor-Id", "exec-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\"}"))
                .andExpect(status().isConflict());
        confirm("INC-600", "T-1", "rev-1", "EV-1");

        // 仅确认 PHOTO 不能完成：422 且 details 返回缺失类别
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-600", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_INSUFFICIENT"))
                .andExpect(jsonPath("$.details[0]").value("READING"));

        // 补齐 READING 后完成
        upload("INC-600", "T-1", "exec-2", "EV-2", "READING");
        confirm("INC-600", "T-1", "rev-2", "EV-2");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-600", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        // 证据链查询：必需类别与证据齐全，完成已冻结
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/evidence", "INC-600", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskVersion").value(2))
                .andExpect(jsonPath("$.requiredCategories.length()").value(2))
                .andExpect(jsonPath("$.evidence.length()").value(2))
                .andExpect(jsonPath("$.evidence[0].frozenAt").isNotEmpty())
                .andExpect(jsonPath("$.evidence[0].confirmedBy").value("rev-1"));

        // 完成后禁止作废已冻结证据
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidence/{e}/void",
                        "INC-600", "T-1", "EV-1")
                        .header("X-Actor-Id", "exec-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void evidenceHttpValidation() throws Exception {
        reportAndTakeover("INC-601", "alice");
        createTask("INC-601", "alice", "T-1");
        // 缺少 X-Actor-Id：400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidence-requirements", "INC-601", "T-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key()
                                + "\",\"expectedTaskVersion\":1,\"categories\":[\"PHOTO\"]}"))
                .andExpect(status().isBadRequest());
        // 版本不匹配：409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidence-requirements", "INC-601", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key()
                                + "\",\"expectedTaskVersion\":7,\"categories\":[\"PHOTO\"]}"))
                .andExpect(status().isConflict());
        // 任务不存在：404
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidence", "INC-601", "T-9")
                        .header("X-Actor-Id", "exec-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key()
                                + "\",\"evidenceNo\":\"EV-1\",\"category\":\"PHOTO\",\"description\":\"d\"}"))
                .andExpect(status().isNotFound());
        // 证据不存在：404
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidence/{e}/confirm",
                        "INC-601", "T-1", "EV-9")
                        .header("X-Actor-Id", "rev-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\"}"))
                .andExpect(status().isNotFound());
    }
}
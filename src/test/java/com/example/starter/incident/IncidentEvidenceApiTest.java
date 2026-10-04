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
 * 400/404/409/422 错误语义及缺失类别的结构化 details。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IncidentEvidenceApiTest {

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
        jdbc.update("DELETE FROM incident_task_evidence_requirements");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void reportAndTakeover(String incidentKey, String actor) throws Exception {
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

    private void createTask(String incidentKey, String actor, String taskKey) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"G\",\"title\":\"t\"}"))
                .andExpect(status().isOk());
    }

    private void setRequirements(String incidentKey, String taskKey, String actor,
                                 String categoriesJson, long expectedVersion) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidence-requirements",
                        incidentKey, taskKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"categories\":"
                                + categoriesJson + ",\"expectedTaskVersion\":"
                                + expectedVersion + "}"))
                .andExpect(status().isOk());
    }

    private void upload(String incidentKey, String taskKey, String actor, String evidenceKey,
                        String category) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidences", incidentKey, taskKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"evidenceKey\":\""
                                + evidenceKey + "\",\"category\":\"" + category
                                + "\",\"note\":\"n\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenceKey").value(evidenceKey))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.uploadedBy").value(actor));
    }

    private void confirm(String incidentKey, String taskKey, String evidenceKey, String actor)
            throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidences/{e}/confirm",
                        incidentKey, taskKey, evidenceKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedBy").value(actor));
    }

    @Test
    void evidenceHttpFlow() throws Exception {
        reportAndTakeover("INC-600", "alice");
        createTask("INC-600", "alice", "T-1");

        // 设置必需类别并回读任务视图
        setRequirements("INC-600", "T-1", "alice", "[\"PHOTO\",\"READING\"]", 1L);
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-600", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.requiredEvidenceCategories[0]").value("PHOTO"))
                .andExpect(jsonPath("$.requiredEvidenceCategories[1]").value("READING"));

        // 上传并确认 PHOTO，列表查询
        upload("INC-600", "T-1", "exec-1", "EV-P1", "PHOTO");
        confirm("INC-600", "T-1", "EV-P1", "acceptor-1");
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/evidences", "INC-600", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskKey").value("T-1"))
                .andExpect(jsonPath("$.evidences.length()").value(1))
                .andExpect(jsonPath("$.evidences[0].status").value("CONFIRMED"));

        // 缺 READING：完成 → 422 且 details 返回缺失类别
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-600", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_INSUFFICIENT"))
                .andExpect(jsonPath("$.details[0]").value("READING"));

        // 作废 PHOTO 证据后确认失效
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidences/{e}/void",
                        "INC-600", "T-1", "EV-P1")
                        .header("X-Actor-Id", "exec-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOID"))
                .andExpect(jsonPath("$.voidedBy").value("exec-1"));
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-600", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.length()").value(2));

        // 补齐两类证据后完成
        upload("INC-600", "T-1", "exec-1", "EV-P2", "PHOTO");
        confirm("INC-600", "T-1", "EV-P2", "acceptor-1");
        upload("INC-600", "T-1", "exec-2", "EV-R1", "READING");
        confirm("INC-600", "T-1", "EV-R1", "acceptor-2");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-600", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    void evidenceHttpErrors() throws Exception {
        reportAndTakeover("INC-610", "alice");
        createTask("INC-610", "alice", "T-1");

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidences", "INC-610", "T-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"evidenceKey\":\"EV-1\","
                                + "\"category\":\"PHOTO\",\"note\":\"n\"}"))
                .andExpect(status().isBadRequest());
        // 类别超过 5 个 → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidence-requirements",
                        "INC-610", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"categories\":"
                                + "[\"A\",\"B\",\"C\",\"D\",\"E\",\"F\"],"
                                + "\"expectedTaskVersion\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        // 非当前指挥人设置必需类别 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidence-requirements",
                        "INC-610", "T-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"categories\":[\"PHOTO\"],"
                                + "\"expectedTaskVersion\":1}"))
                .andExpect(status().isConflict());
        // 事件/任务/证据不存在 → 404
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/evidences", "INC-404", "T-1"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/evidences", "INC-610", "T-9"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidences/{e}/confirm",
                        "INC-610", "T-1", "EV-9")
                        .header("X-Actor-Id", "acceptor-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\"}"))
                .andExpect(status().isNotFound());
        // 验收人与上传人相同 → 409
        upload("INC-610", "T-1", "exec-1", "EV-1", "PHOTO");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/evidences/{e}/confirm",
                        "INC-610", "T-1", "EV-1")
                        .header("X-Actor-Id", "exec-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }
}
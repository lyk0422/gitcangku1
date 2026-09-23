package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 联合指挥交接 HTTP 层测试：验证路由、X-Actor-Id 请求头、400/403/409/422 错误语义、
 * 冻结→接受完整 JSON 流程，以及闭包/快照/历史只读查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class JointHandoverApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private static String key() {
        return "API-" + UUID.randomUUID();
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM joint_handover_snapshot_escalations");
        jdbc.update("DELETE FROM joint_handover_snapshot_tasks");
        jdbc.update("DELETE FROM joint_handover_snapshot_incidents");
        jdbc.update("DELETE FROM joint_handover_members");
        jdbc.update("DELETE FROM joint_handovers");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
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

    private void taskWithBlocker(String incidentKey, String taskKey, String blockerKey)
            throws Exception {
        String blockers = blockerKey == null ? "[]" : "[\"" + blockerKey + "\"]";
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"G\",\"title\":\"t\",\"blockerIncidentKeys\":"
                                + blockers + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void freezeAcceptQuery_fullHttpFlow() throws Exception {
        report("JA");
        takeover("JA", "alice");
        report("JB");
        takeover("JB", "alice");
        report("JC");
        takeover("JC", "alice");
        taskWithBlocker("JA", "JT", "JB");

        // 遗漏闭包事件：提交 {JA,JC}，JA 的 OPEN 任务未完成依赖 JB，闭包 {JA,JB,JC}，422 列缺失
        mvc.perform(post("/api/handovers")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"handoverKey\":\"JH-1\",\"toCommander\":\"bob\","
                                + "\"incidentKeys\":[\"JA\",\"JC\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLOSURE_NOT_COVERED"))
                .andExpect(jsonPath("$.details[0]").value("JB"));

        // 恰好覆盖：冻结成功
        MvcResult freezeResult = mvc.perform(post("/api/handovers")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"handoverKey\":\"JH-1\",\"toCommander\":\"bob\","
                                + "\"incidentKeys\":[\"JA\",\"JB\",\"JC\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.fromCommander").value("alice"))
                .andExpect(jsonPath("$.closureIncidentKeys[0]").value("JA"))
                .andExpect(jsonPath("$.closureIncidentKeys[1]").value("JB"))
                .andExpect(jsonPath("$.closureIncidentKeys[2]").value("JC"))
                .andReturn();
        JsonNode frozen = objectMapper.readTree(freezeResult.getResponse().getContentAsString());
        String version = frozen.get("handoverVersion").asText();

        // 非指定接收人接受：403
        mvc.perform(post("/api/handovers/JH-1/accept")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody(version, frozen.get("summary"))))
                .andExpect(status().isForbidden());

        // 指定接收人接受成功
        mvc.perform(post("/api/handovers/JH-1/accept")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody(version, frozen.get("summary"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.toCommander").value("bob"));

        // 全部事件指挥人已切换
        mvc.perform(get("/api/incidents/JA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commander").value("bob"));
        mvc.perform(get("/api/incidents/JB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commander").value("bob"));

        // 不可变闭包快照
        mvc.perform(get("/api/handovers/JH-1/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidents.length()").value(3))
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].taskKey").value("JT"))
                .andExpect(jsonPath("$.tasks[0].blockerKeys[0]").value("JB"));

        // 历史查询只读
        mvc.perform(get("/api/handovers/by-incident/JA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].handoverKey").value("JH-1"));
    }

    @Test
    void httpErrorBranches() throws Exception {
        report("KA");
        takeover("KA", "alice");
        report("KB");
        takeover("KB", "carol");

        // 混入非本人指挥事件：403
        mvc.perform(post("/api/handovers")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"handoverKey\":\"JH-2\",\"toCommander\":\"bob\","
                                + "\"incidentKeys\":[\"KA\",\"KB\"]}"))
                .andExpect(status().isForbidden());

        // 数量不足：400
        mvc.perform(post("/api/handovers")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"handoverKey\":\"JH-3\",\"toCommander\":\"bob\","
                                + "\"incidentKeys\":[\"KA\"]}"))
                .andExpect(status().isBadRequest());

        // 重复键：400
        mvc.perform(post("/api/handovers")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"handoverKey\":\"JH-4\",\"toCommander\":\"bob\","
                                + "\"incidentKeys\":[\"KA\",\"KA\"]}"))
                .andExpect(status().isBadRequest());

        // 不存在的交接单：404
        mvc.perform(get("/api/handovers/NOPE")).andExpect(status().isNotFound());
    }

    @Test
    void acceptWithStaleVersion_returns409() throws Exception {
        report("LA");
        takeover("LA", "alice");
        report("LB");
        takeover("LB", "alice");

        MvcResult freezeResult = mvc.perform(post("/api/handovers")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"handoverKey\":\"JH-5\",\"toCommander\":\"bob\","
                                + "\"incidentKeys\":[\"LA\",\"LB\"]}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode frozen = objectMapper.readTree(freezeResult.getResponse().getContentAsString());

        mvc.perform(post("/api/handovers/JH-5/accept")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody("0".repeat(64), frozen.get("summary"))))
                .andExpect(status().isConflict());
    }

    private String acceptBody(String version, JsonNode summary) throws Exception {
        var body = objectMapper.createObjectNode();
        body.put("commandKey", key());
        body.put("expectedHandoverVersion", version);
        body.set("summary", summary);
        return objectMapper.writeValueAsString(body);
    }
}

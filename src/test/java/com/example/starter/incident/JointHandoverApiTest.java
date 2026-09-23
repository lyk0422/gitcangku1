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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 联合交接 HTTP 层测试：验证路由、X-Actor-Id 请求头、400/403/404/409/422 错误语义、
 * 冻结摘要回传接受主流程及只读闭包/历史查询。
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

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM joint_handover_snapshots");
        jdbc.update("DELETE FROM joint_handover_incidents");
        jdbc.update("DELETE FROM joint_handovers");
        jdbc.update("DELETE FROM incident_escalations");
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

    private void createTask(String incidentKey, String actor, String taskKey, String blockers)
            throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"G\",\"title\":\"t\",\"blockerIncidentKeys\":"
                                + blockers + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void apiMainFlow_previewAcceptAndQueries() throws Exception {
        report("HA");
        report("HB");
        takeover("HA", "alice");
        takeover("HB", "alice");
        createTask("HA", "alice", "TA", "[\"HB\"]");

        // 发起联合交接（换序不影响闭包）
        MvcResult previewResult = mvc.perform(post("/api/handovers")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"handoverKey\":\"JH-1\","
                                + "\"toCommander\":\"bob\",\"incidentKeys\":[\"HB\",\"HA\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.handoverKey").value("JH-1"))
                .andExpect(jsonPath("$.fromCommander").value("alice"))
                .andExpect(jsonPath("$.toCommander").value("bob"))
                .andExpect(jsonPath("$.closureIncidentKeys.length()").value(2))
                .andExpect(jsonPath("$.closureIncidentKeys[0]").value("HA"))
                .andExpect(jsonPath("$.summary.incidents[0].incidentKey").value("HA"))
                .andExpect(jsonPath("$.summary.incidents[0].openTasks[0].blockers[0]").value("HB"))
                .andReturn();
        JsonNode preview = objectMapper.readTree(previewResult.getResponse().getContentAsString());
        String version = preview.get("handoverVersion").asText();
        JsonNode summary = preview.get("summary");

        // 非指定接收人 → 409
        mvc.perform(post("/api/handovers/{hk}/accept", "JH-1")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"expectedHandoverVersion\":\"" + version
                                + "\",\"summary\":" + objectMapper.writeValueAsString(summary) + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 指定接收人携带完整摘要接受
        mvc.perform(post("/api/handovers/{hk}/accept", "JH-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"expectedHandoverVersion\":\"" + version
                                + "\",\"summary\":" + objectMapper.writeValueAsString(summary) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        mvc.perform(get("/api/incidents/{k}", "HA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commander").value("bob"));

        // 闭包详情含不可变快照
        mvc.perform(get("/api/handovers/{hk}", "JH-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handover.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.closureIncidents.length()").value(2))
                .andExpect(jsonPath("$.snapshots.length()").value(2))
                .andExpect(jsonPath("$.snapshots[0].commander").value("bob"))
                .andExpect(jsonPath("$.snapshots[0].incidentVersion").value(2));

        // 历史查询只读
        mvc.perform(get("/api/handovers/by-commander/{c}", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handovers.length()").value(1))
                .andExpect(jsonPath("$.handovers[0].closureIncidents.length()").value(2));
    }

    @Test
    void apiErrorSemantics() throws Exception {
        report("HA");
        report("HB");
        takeover("HA", "alice");
        takeover("HB", "alice");
        createTask("HA", "alice", "TA", "[\"HB\"]");
        report("HC");
        takeover("HC", "carol");
        report("HD");
        takeover("HD", "alice");

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/handovers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"handoverKey\":\"JE-0\","
                                + "\"toCommander\":\"bob\",\"incidentKeys\":[\"HA\",\"HB\"]}"))
                .andExpect(status().isBadRequest());

        // 422：遗漏闭包事件 HB，details 列出缺失键
        mvc.perform(post("/api/handovers")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"handoverKey\":\"JE-1\","
                                + "\"toCommander\":\"bob\",\"incidentKeys\":[\"HA\",\"HD\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLOSURE_MISMATCH"))
                .andExpect(jsonPath("$.details[0]").value("HB"));

        // 403：混入非本人指挥事件
        mvc.perform(post("/api/handovers")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"handoverKey\":\"JE-2\","
                                + "\"toCommander\":\"bob\",\"incidentKeys\":[\"HA\",\"HC\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // 400：重复键
        mvc.perform(post("/api/handovers")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"handoverKey\":\"JE-3\","
                                + "\"toCommander\":\"bob\",\"incidentKeys\":[\"HA\",\"HA\"]}"))
                .andExpect(status().isBadRequest());

        // 404：交接单不存在
        mvc.perform(get("/api/handovers/{hk}", "NOPE"))
                .andExpect(status().isNotFound());

        // 404：事件不存在
        mvc.perform(post("/api/handovers")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"handoverKey\":\"JE-4\","
                                + "\"toCommander\":\"bob\",\"incidentKeys\":[\"HA\",\"NOPE\"]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void apiStaleAccept_409() throws Exception {
        report("HA");
        report("HB");
        takeover("HA", "alice");
        takeover("HB", "alice");

        MvcResult previewResult = mvc.perform(post("/api/handovers")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"handoverKey\":\"JS-1\","
                                + "\"toCommander\":\"bob\",\"incidentKeys\":[\"HA\",\"HB\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode preview = objectMapper.readTree(previewResult.getResponse().getContentAsString());
        String version = preview.get("handoverVersion").asText();
        JsonNode summary = preview.get("summary");

        // 冻结后事件状态推进 → expectedHandoverVersion 失效 → 409
        mvc.perform(post("/api/incidents/{k}/status", "HB")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"targetStatus\":\"CONTAINED\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/handovers/{hk}/accept", "JS-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"expectedHandoverVersion\":\"" + version
                                + "\",\"summary\":" + objectMapper.writeValueAsString(summary) + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }
}

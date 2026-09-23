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

/**
 * 联合指挥交接 HTTP 层测试：验证路由、X-Actor-Id 约束及
 * 400/403/404/409/422 错误语义可区分。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HandoverApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_handover_incidents");
        jdbc.update("DELETE FROM incident_handovers");
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

    private void commanding(String incidentKey, String commander) throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentKey\":\"" + incidentKey
                                + "\",\"severity\":\"S2\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", commander)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void apiMainFlow() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        // INC-A 的 OPEN 任务被 INC-B 阻塞，闭包为 {INC-A, INC-B}
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-A")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"taskKey\":\"T-A\",\"groupCode\":\"G1\",\"title\":\"t\","
                                + "\"blockerIncidentKeys\":[\"INC-B\"]}"))
                .andExpect(status().isOk());

        // 发起：恰好覆盖闭包，返回摘要与 handoverVersion
        MvcResult initiated = mvc.perform(post("/api/handovers")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"handoverKey\":\"HO-API-1\",\"toCommander\":\"bob\","
                                + "\"incidentKeys\":[\"INC-A\",\"INC-B\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handover.status").value("PENDING"))
                .andExpect(jsonPath("$.handover.incidentKeys.length()").value(2))
                .andExpect(jsonPath("$.handoverVersion").isString())
                .andExpect(jsonPath("$.summary.incidents[0].incidentKey").value("INC-A"))
                .andExpect(jsonPath("$.summary.incidents[0].openTasks[0].dependencies[0]")
                        .value("INC-B"))
                .andReturn();
        String body = initiated.getResponse().getContentAsString();
        String version = com.jayway.jsonpath.JsonPath.read(body, "$.handoverVersion");
        Object summary = com.jayway.jsonpath.JsonPath.read(body, "$.summary");

        // 接受：提交完整摘要与版本，闭包全部事件指挥人切换
        mvc.perform(post("/api/handovers/{k}/accept", "HO-API-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"expectedHandoverVersion\":\"" + version
                                + "\",\"summary\":" + objectMapper.writeValueAsString(summary)
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handover.status").value("ACCEPTED"));

        mvc.perform(get("/api/incidents/{k}", "INC-A"))
                .andExpect(jsonPath("$.commander").value("bob"));
        mvc.perform(get("/api/incidents/{k}", "INC-B"))
                .andExpect(jsonPath("$.commander").value("bob"));

        // 详情：ACCEPTED 返回不可变快照
        mvc.perform(get("/api/handovers/{k}", "HO-API-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handover.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.handoverVersion").value(version));
        // 事件维度历史
        mvc.perform(get("/api/incidents/{k}/handovers", "INC-B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handovers.length()").value(1))
                .andExpect(jsonPath("$.handovers[0].handoverKey").value("HO-API-1"));
    }

    @Test
    void apiErrorSemantics() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        commanding("INC-D", "alice");
        commanding("INC-C", "carol");
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-A")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"taskKey\":\"T-A\",\"groupCode\":\"G1\",\"title\":\"t\","
                                + "\"blockerIncidentKeys\":[\"INC-B\"]}"));

        // 400：缺 X-Actor-Id
        mvc.perform(post("/api/handovers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"handoverKey\":\"HO-E1\",\"toCommander\":\"bob\","
                                + "\"incidentKeys\":[\"INC-A\",\"INC-B\"]}"))
                .andExpect(status().isBadRequest());
        // 400：重复键
        mvc.perform(post("/api/handovers")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"handoverKey\":\"HO-E1\",\"toCommander\":\"bob\","
                                + "\"incidentKeys\":[\"INC-A\",\"INC-A\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        // 422：遗漏闭包事件，details 列出缺失
        mvc.perform(post("/api/handovers")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"handoverKey\":\"HO-E1\",\"toCommander\":\"bob\","
                                + "\"incidentKeys\":[\"INC-A\",\"INC-D\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLOSURE_INCOMPLETE"))
                .andExpect(jsonPath("$.details[0]").value("INC-B"));
        // 403：混入非本人指挥事件
        mvc.perform(post("/api/handovers")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"handoverKey\":\"HO-E1\",\"toCommander\":\"bob\","
                                + "\"incidentKeys\":[\"INC-A\",\"INC-B\",\"INC-C\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // 404：交接单不存在
        mvc.perform(get("/api/handovers/{k}", "HO-NONE"))
                .andExpect(status().isNotFound());
        // 404：事件不存在
        mvc.perform(get("/api/incidents/{k}/handovers", "INC-NONE"))
                .andExpect(status().isNotFound());
    }
}

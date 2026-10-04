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
 * 交接简报 HTTP 层测试：验证回读/刷新/简报读取路由、403 权限语义、
 * 错误摘要 409 与接收必须引用简报（缺 briefingId 为 400）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransferBriefingApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM transfer_readbacks");
        jdbc.update("DELETE FROM transfer_briefings");
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
                                + "\",\"severity\":\"S1\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", commander)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
    }

    private void initiate(String incidentKey, String toCommander) throws Exception {
        mvc.perform(post("/api/incidents/{k}/transfers", incidentKey)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"toCommander\":\""
                                + toCommander + "\",\"note\":\"夜班注意事项\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    private String currentBriefingBody(String incidentKey, String actor) throws Exception {
        return mvc.perform(get("/api/incidents/{k}/transfers/briefing", incidentKey)
                        .header("X-Actor-Id", actor))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static long briefingIdOf(String body) {
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private static String digestOf(String body) {
        return com.jayway.jsonpath.JsonPath.read(body, "$.digest");
    }

    private void readback(String incidentKey, String actor, long briefingId, String digest)
            throws Exception {
        mvc.perform(post("/api/incidents/{k}/transfers/readback", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"briefingId\":" + briefingId
                                + ",\"digest\":\"" + digest + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reader").value(actor));
    }
    @Test
    void briefingFlow_overHttp() throws Exception {
        commanding("INC-HTTP-1", "alice");
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-HTTP-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T1\","
                                + "\"groupCode\":\"G1\",\"title\":\"疏散\",\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isOk());
        initiate("INC-HTTP-1", "bob");

        // 接班人可读简报，内容冻结 T1 与补充说明
        String body = currentBriefingBody("INC-HTTP-1", "bob");
        long briefingId = briefingIdOf(body);
        String digest = digestOf(body);
        mvc.perform(get("/api/incidents/{k}/transfers/briefing", "INC-HTTP-1")
                        .header("X-Actor-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.content.incidentStatus").value("COMMANDING"))
                .andExpect(jsonPath("$.content.note").value("夜班注意事项"))
                .andExpect(jsonPath("$.content.unfinishedTasks[0].taskKey").value("T1"));

        // 交出人与接班人之外读取 403
        mvc.perform(get("/api/incidents/{k}/transfers/briefing", "INC-HTTP-1")
                        .header("X-Actor-Id", "carol"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/incidents/{k}/transfers/briefings/{id}", "INC-HTTP-1", briefingId)
                        .header("X-Actor-Id", "carol"))
                .andExpect(status().isForbidden());

        // 非接班人回读 403
        mvc.perform(post("/api/incidents/{k}/transfers/readback", "INC-HTTP-1")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"briefingId\":" + briefingId
                                + ",\"digest\":\"" + digest + "\"}"))
                .andExpect(status().isForbidden());

        // 错误摘要 409，不产生确认
        mvc.perform(post("/api/incidents/{k}/transfers/readback", "INC-HTTP-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"briefingId\":" + briefingId
                                + ",\"digest\":\"" + "0".repeat(64) + "\"}"))
                .andExpect(status().isConflict());

        // 未回读直接接收 409
        mvc.perform(post("/api/incidents/{k}/transfers/accept", "INC-HTTP-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"briefingId\":" + briefingId
                                + "}"))
                .andExpect(status().isConflict());

        // 回读后接收成功，简报冻结且记录回读人
        readback("INC-HTTP-1", "bob", briefingId, digest);
        mvc.perform(post("/api/incidents/{k}/transfers/accept", "INC-HTTP-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"briefingId\":" + briefingId
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commander").value("bob"));
        mvc.perform(get("/api/incidents/{k}/transfers/briefings/{id}", "INC-HTTP-1", briefingId)
                        .header("X-Actor-Id", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"))
                .andExpect(jsonPath("$.readbackBy").value("bob"));
    }

    @Test
    void accept_withoutBriefingId_badRequest() throws Exception {
        commanding("INC-HTTP-2", "alice");
        initiate("INC-HTTP-2", "bob");
        mvc.perform(post("/api/incidents/{k}/transfers/accept", "INC-HTTP-2")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void refresh_overHttp_versionIncrements() throws Exception {
        commanding("INC-HTTP-3", "alice");
        initiate("INC-HTTP-3", "bob");
        String body = currentBriefingBody("INC-HTTP-3", "bob");
        long v1Id = briefingIdOf(body);

        // 交出人刷新：版本加一，说明可替换
        mvc.perform(post("/api/incidents/{k}/transfers/briefing/refresh", "INC-HTTP-3")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"note\":\"更新后的说明\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.content.note").value("更新后的说明"));

        // 旧简报保留，仍含原说明
        mvc.perform(get("/api/incidents/{k}/transfers/briefings/{id}", "INC-HTTP-3", v1Id)
                        .header("X-Actor-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.content.note").value("夜班注意事项"));

        // 接班人不能刷新
        mvc.perform(post("/api/incidents/{k}/transfers/briefing/refresh", "INC-HTTP-3")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\"}"))
                .andExpect(status().isForbidden());
    }
}
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
 * 重复事件合并 HTTP 层测试：验证合并路由、X-Actor-Id 请求头约束、
 * 合并记录查询端点及 400/404/409 错误语义（含任务键冲突 details）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IncidentMergeApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_merges");
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

    private void createTask(String incidentKey, String actor, String taskKey) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"G\",\"title\":\"t\","
                                + "\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isOk());
    }

    private static String mergeBody(String commandKey, String mergeKey,
                                    String surviving, String merged, long sv, long mv) {
        return "{\"commandKey\":\"" + commandKey + "\",\"mergeKey\":\"" + mergeKey
                + "\",\"survivingIncidentKey\":\"" + surviving
                + "\",\"mergedIncidentKey\":\"" + merged
                + "\",\"survivingExpectedVersion\":" + sv
                + ",\"mergedExpectedVersion\":" + mv + "}";
    }

    @Test
    void mergeHttpFlow() throws Exception {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        createTask("INC-M", "alice", "TM-1");

        // 合并成功：响应含双方版本与迁移任务键
        mvc.perform(post("/api/incidents/merges")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(key(), "MRG-1", "INC-S", "INC-M", 0L, 0L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mergeKey").value("MRG-1"))
                .andExpect(jsonPath("$.survivingIncidentKey").value("INC-S"))
                .andExpect(jsonPath("$.mergedIncidentKey").value("INC-M"))
                .andExpect(jsonPath("$.survivingVersion").value(1))
                .andExpect(jsonPath("$.mergedVersion").value(1))
                .andExpect(jsonPath("$.movedTaskKeys[0]").value("TM-1"))
                .andExpect(jsonPath("$.actor").value("alice"));

        // 被并入事件进入 MERGED 终态并记录存续事件
        mvc.perform(get("/api/incidents/{k}", "INC-M"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERGED"))
                .andExpect(jsonPath("$.mergedIntoIncidentKey").value("INC-S"))
                .andExpect(jsonPath("$.version").value(1));

        // 合并后任务归属查询：任务归存续事件并记录来源
        mvc.perform(get("/api/incidents/{k}/tasks", "INC-S"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].taskKey").value("TM-1"))
                .andExpect(jsonPath("$.tasks[0].originIncidentKey").value("INC-M"));

        // 合并记录查询：列表与单条
        mvc.perform(get("/api/incidents/merges"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merges.length()").value(1))
                .andExpect(jsonPath("$.merges[0].mergeKey").value("MRG-1"))
                .andExpect(jsonPath("$.merges[0].survivingIncidentKey").value("INC-S"))
                .andExpect(jsonPath("$.merges[0].mergedIncidentKey").value("INC-M"));
        mvc.perform(get("/api/incidents/merges/{mk}", "MRG-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mergeKey").value("MRG-1"))
                .andExpect(jsonPath("$.actor").value("alice"));
        mvc.perform(get("/api/incidents/merges/{mk}", "MRG-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void mergeHttp_taskKeyConflictReturns409WithDetails() throws Exception {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        createTask("INC-S", "alice", "T-1");
        createTask("INC-M", "alice", "T-1");

        mvc.perform(post("/api/incidents/merges")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(key(), "MRG-C", "INC-S", "INC-M", 0L, 0L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.details[0]").value("T-1"));

        // 冲突后双方状态不变，无合并记录
        mvc.perform(get("/api/incidents/{k}", "INC-M"))
                .andExpect(jsonPath("$.status").value("COMMANDING"));
        mvc.perform(get("/api/incidents/merges"))
                .andExpect(jsonPath("$.merges.length()").value(0));
    }

    @Test
    void mergeHttp_errorSemantics() throws Exception {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/merges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(key(), "MRG-H", "INC-S", "INC-M", 0L, 0L)))
                .andExpect(status().isBadRequest());
        // 缺少必填字段 → 400
        mvc.perform(post("/api/incidents/merges")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        // 事件不存在 → 404
        mvc.perform(post("/api/incidents/merges")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(key(), "MRG-N", "INC-S", "INC-404", 0L, 0L)))
                .andExpect(status().isNotFound());
        // 版本不匹配 → 409
        mvc.perform(post("/api/incidents/merges")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(key(), "MRG-V", "INC-S", "INC-M", 5L, 0L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        // 非当前指挥人 → 409
        mvc.perform(post("/api/incidents/merges")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(key(), "MRG-P", "INC-S", "INC-M", 0L, 0L)))
                .andExpect(status().isConflict());
    }
}

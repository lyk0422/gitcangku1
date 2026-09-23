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
 * 任务依赖修订 HTTP 层测试：验证替换与修订历史路由、任务明细版本字段、
 * X-Actor-Id 请求头约束及 400/404/409 错误语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IncidentTaskRevisionApiTest {

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
        jdbc.update("DELETE FROM incident_task_revisions");
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

    private void createTask(String incidentKey, String actor, String taskKey,
                            String blockersJson) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"DB\",\"title\":\"扩容\","
                                + "\"blockerIncidentKeys\":" + blockersJson + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void replaceBlockersHttpFlow() throws Exception {
        commanding("INC-600", "alice");
        commanding("INC-601", "bob");
        commanding("INC-602", "carol");
        createTask("INC-600", "alice", "T-1", "[\"INC-601\"]");

        // 整体替换依赖：版本 1 → 2，响应含版本与最新阻塞列表
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/blockers", "INC-600", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"expectedTaskVersion\":1,"
                                + "\"blockerIncidentKeys\":[\"INC-602\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.blockers.length()").value(1))
                .andExpect(jsonPath("$.blockers[0].incidentKey").value("INC-602"));

        // 任务明细携带版本
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-600", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 修订历史查询：操作者、前后版本与排序后的依赖列表
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/revisions", "INC-600", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidentKey").value("INC-600"))
                .andExpect(jsonPath("$.taskKey").value("T-1"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.revisions.length()").value(1))
                .andExpect(jsonPath("$.revisions[0].operation").value("REPLACE"))
                .andExpect(jsonPath("$.revisions[0].actor").value("alice"))
                .andExpect(jsonPath("$.revisions[0].fromVersion").value(1))
                .andExpect(jsonPath("$.revisions[0].toVersion").value(2))
                .andExpect(jsonPath("$.revisions[0].blockerIncidentKeys[0]").value("INC-602"))
                .andExpect(jsonPath("$.revisions[0].occurredAt").exists());
    }

    @Test
    void replaceBlockersHttpErrors() throws Exception {
        commanding("INC-610", "alice");
        commanding("INC-611", "bob");
        createTask("INC-610", "alice", "T-1", "[]");

        // 缺少 expectedTaskVersion → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/blockers", "INC-610", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        // 重复目标 → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/blockers", "INC-610", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"expectedTaskVersion\":1,"
                                + "\"blockerIncidentKeys\":[\"INC-611\",\"INC-611\"]}"))
                .andExpect(status().isBadRequest());
        // 阻塞事件不存在 → 404
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/blockers", "INC-610", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"expectedTaskVersion\":1,"
                                + "\"blockerIncidentKeys\":[\"INC-404\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // 版本不匹配 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/blockers", "INC-610", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"expectedTaskVersion\":7,"
                                + "\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        // 非当前指挥人 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/blockers", "INC-610", "T-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"expectedTaskVersion\":1,"
                                + "\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isConflict());
        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/blockers", "INC-610", "T-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"expectedTaskVersion\":1,"
                                + "\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isBadRequest());
        // 历史查询：事件/任务不存在 → 404
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/revisions", "INC-404", "T-1"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/revisions", "INC-610", "T-9"))
                .andExpect(status().isNotFound());
        // 失败请求均未改变数据
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-610", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }
}

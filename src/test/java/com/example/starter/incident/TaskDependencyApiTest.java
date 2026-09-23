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
 * 阻塞列表替换与修订查询的 HTTP 层测试：路由、X-Actor-Id、版本字段、
 * 400/404/409/幂等语义及修订历史结构。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaskDependencyApiTest {

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
        jdbc.update("DELETE FROM incident_task_dependency_revisions");
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

    private void createTask(String incidentKey, String actor, String taskKey,
                            String blockersJson) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"DB\",\"title\":\"t\","
                                + "\"blockerIncidentKeys\":" + blockersJson + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void replaceAndRevisionsHttpFlow() throws Exception {
        report("INC-700");
        report("INC-701");
        takeover("INC-700", "alice");
        takeover("INC-701", "bob");
        createTask("INC-700", "alice", "T-1", "[]");

        // 任务明细初始版本为 1
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-700", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // 替换为依赖 INC-701：版本 2
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dependencies", "INC-700", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"expectedTaskVersion\":1,"
                                + "\"blockerIncidentKeys\":[\"INC-701\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.blockers[0].incidentKey").value("INC-701"));

        // 修订历史查询
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/revisions", "INC-700", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidentKey").value("INC-700"))
                .andExpect(jsonPath("$.taskKey").value("T-1"))
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.revisions.length()").value(1))
                .andExpect(jsonPath("$.revisions[0].revisionNo").value(1))
                .andExpect(jsonPath("$.revisions[0].beforeVersion").value(1))
                .andExpect(jsonPath("$.revisions[0].afterVersion").value(2))
                .andExpect(jsonPath("$.revisions[0].dependencies[0]").value("INC-701"))
                .andExpect(jsonPath("$.revisions[0].actor").value("alice"));

        // 清空依赖：版本 3，历史两条
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dependencies", "INC-700", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"expectedTaskVersion\":2,\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.blockers.length()").value(0));
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/revisions", "INC-700", "T-1"))
                .andExpect(jsonPath("$.revisions.length()").value(2));
    }

    @Test
    void replaceHttpErrors() throws Exception {
        report("INC-710");
        report("INC-711");
        takeover("INC-710", "alice");
        takeover("INC-711", "bob");
        createTask("INC-710", "alice", "T-1", "[]");
        createTask("INC-711", "bob", "T-1", "[]");

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dependencies", "INC-710", "T-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"expectedTaskVersion\":1,\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isBadRequest());
        // 缺少 expectedTaskVersion → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dependencies", "INC-710", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isBadRequest());
        // 重复目标 → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dependencies", "INC-710", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"expectedTaskVersion\":1,"
                                + "\"blockerIncidentKeys\":[\"INC-711\",\"INC-711\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        // 自身依赖 → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dependencies", "INC-710", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"expectedTaskVersion\":1,"
                                + "\"blockerIncidentKeys\":[\"INC-710\"]}"))
                .andExpect(status().isBadRequest());
        // 目标不存在 → 404
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dependencies", "INC-710", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"expectedTaskVersion\":1,"
                                + "\"blockerIncidentKeys\":[\"INC-404\"]}"))
                .andExpect(status().isNotFound());
        // 反向成环 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dependencies", "INC-710", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"expectedTaskVersion\":1,"
                                + "\"blockerIncidentKeys\":[\"INC-711\"]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dependencies", "INC-711", "T-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"expectedTaskVersion\":1,"
                                + "\"blockerIncidentKeys\":[\"INC-710\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        // 成环回滚后版本仍为 1
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-711", "T-1"))
                .andExpect(jsonPath("$.version").value(1));
        // 旧版本号 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/dependencies", "INC-710", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"expectedTaskVersion\":1,"
                                + "\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isConflict());
        // 修订历史 404
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/revisions", "INC-404", "T-1"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/revisions", "INC-710", "T-9"))
                .andExpect(status().isNotFound());
    }

    @Test
    void replaceHttpIdempotentReplay() throws Exception {
        report("INC-720");
        takeover("INC-720", "alice");
        createTask("INC-720", "alice", "T-1", "[]");
        String commandKey = key();
        String body = "{\"commandKey\":\"" + commandKey
                + "\",\"expectedTaskVersion\":1,\"blockerIncidentKeys\":[]}";
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/incidents/{k}/tasks/{t}/dependencies", "INC-720", "T-1")
                            .header("X-Actor-Id", "alice")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(2));
        }
        // 重放不再次替换：历史仅 1 条
        mvc.perform(get("/api/incidents/{k}/tasks/{t}/revisions", "INC-720", "T-1"))
                .andExpect(jsonPath("$.revisions.length()").value(1));
    }
}

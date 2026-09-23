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
 * 处置任务 HTTP 层测试：验证任务路由、X-Actor-Id 请求头约束、
 * 400/404/409 错误语义及 details 结构化明细（未解除阻塞事件、解决门禁未完成项）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IncidentTaskApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM proposal_votes");
        jdbc.update("DELETE FROM proposal_roster_entries");
        jdbc.update("DELETE FROM dependency_change_proposals");
        jdbc.update("DELETE FROM incident_dependency_edges");
        jdbc.update("UPDATE dependency_graph_meta SET graph_version = 1");
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
                                + "\",\"severity\":\"S1\",\"summary\":\"s\",\"reporter\":\"r\"}"))
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
                                + "\",\"groupCode\":\"DB\",\"title\":\"扩容\","
                                + "\"blockerIncidentKeys\":" + blockersJson + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskKey").value(taskKey))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    private void changeStatus(String incidentKey, String actor, String target) throws Exception {
        mvc.perform(post("/api/incidents/{k}/status", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\""
                                + target + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void taskHttpFlow() throws Exception {
        report("INC-500");
        report("INC-501");
        takeover("INC-500", "alice");
        takeover("INC-501", "bob");

        // 创建任务（带阻塞事件）
        createTask("INC-500", "alice", "T-1", "[\"INC-501\"]");

        // 按事件分组查询
        mvc.perform(get("/api/incidents/{k}/tasks", "INC-500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidentKey").value("INC-500"))
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].blockers[0].incidentKey").value("INC-501"))
                .andExpect(jsonPath("$.tasks[0].blockers[0].resolved").value(false));

        // 单任务明细
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-500", "T-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupCode").value("DB"))
                .andExpect(jsonPath("$.createdBy").value("alice"));

        // 阻塞未解除时完成 → 409 且 details 返回未解除事件列表
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-500", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.details[0]").value("INC-501"));

        // 阻塞事件遏制后完成成功
        changeStatus("INC-501", "bob", "CONTAINED");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-500", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.doneBy").value("alice"))
                .andExpect(jsonPath("$.blockers[0].resolved").value(true));

        // 取消另一个任务
        createTask("INC-500", "alice", "T-2", "[]");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/cancel", "INC-500", "T-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value("alice"));
    }

    @Test
    void taskHttpErrors() throws Exception {
        report("INC-510");
        takeover("INC-510", "alice");

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-510")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-1\","
                                + "\"groupCode\":\"G\",\"title\":\"t\"}"))
                .andExpect(status().isBadRequest());
        // groupCode 为空 → 400
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-510")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-1\","
                                + "\"groupCode\":\" \",\"title\":\"t\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        // 非当前指挥人 → 409
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-510")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-1\","
                                + "\"groupCode\":\"G\",\"title\":\"t\"}"))
                .andExpect(status().isConflict());
        // 事件不存在 → 404
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-404")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-1\","
                                + "\"groupCode\":\"G\",\"title\":\"t\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents/{k}/tasks", "INC-404"))
                .andExpect(status().isNotFound());
        // 任务不存在 → 404
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-510", "T-9"))
                .andExpect(status().isNotFound());
    }

    @Test
    void taskHttpCycleAndResolveGate() throws Exception {
        report("INC-520");
        report("INC-521");
        takeover("INC-520", "alice");
        takeover("INC-521", "bob");
        createTask("INC-520", "alice", "T-1", "[\"INC-521\"]");

        // 反向依赖成环 → 409
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-521")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T-1\","
                                + "\"groupCode\":\"G\",\"title\":\"t\","
                                + "\"blockerIncidentKeys\":[\"INC-520\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 仍有 OPEN 任务时推进 RESOLVED → 409 且 details 按 groupCode、taskKey 返回
        changeStatus("INC-520", "alice", "CONTAINED");
        mvc.perform(post("/api/incidents/{k}/status", "INC-520")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"targetStatus\":\"RESOLVED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0].groupCode").value("DB"))
                .andExpect(jsonPath("$.details[0].taskKey").value("T-1"));

        // 取消任务后解决成功
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/cancel", "INC-520", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
        changeStatus("INC-520", "alice", "RESOLVED");
    }
}

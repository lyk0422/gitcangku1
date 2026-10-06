package com.example.starter.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 择一前置组 HTTP 层测试：验证路由、X-Actor-Id 约束、400/404/409/422 错误语义
 * 及响应中版本、前置组与完成快照字段。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IncidentTaskPrereqApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM task_prereq_snapshot");
        jdbc.update("DELETE FROM task_prereq_members");
        jdbc.update("DELETE FROM task_prereq_groups");
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

    private static String configureBody(String requestId, int version, String groupsJson) {
        return "{\"requestId\":\"" + requestId + "\",\"expectedTaskVersion\":" + version
                + ",\"groups\":" + groupsJson + "}";
    }

    private static String twoMembers(String i1, String t1, String i2, String t2) {
        return "[[{\"incidentKey\":\"" + i1 + "\",\"taskKey\":\"" + t1
                + "\",\"expectedTaskVersion\":1},{\"incidentKey\":\"" + i2
                + "\",\"taskKey\":\"" + t2 + "\",\"expectedTaskVersion\":1}]]";
    }

    @Test
    void prereqHttpFlow() throws Exception {
        reportAndTakeover("INC-600", "alice");
        createTask("INC-600", "alice", "T");
        createTask("INC-600", "alice", "A");
        createTask("INC-600", "alice", "B");

        // 配置成功：版本 1 → 2，响应含前置组
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/prerequisites", "INC-600", "T")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(key(), 1, twoMembers("INC-600", "A", "INC-600", "B"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.prerequisiteGroups.length()").value(1))
                .andExpect(jsonPath("$.prerequisiteGroups[0].members.length()").value(2))
                .andExpect(jsonPath("$.prerequisiteGroups[0].members[0].incidentKey")
                        .value("INC-600"))
                .andExpect(jsonPath("$.prerequisiteGroups[0].members[0].satisfied").value(false))
                .andExpect(jsonPath("$.completionSnapshot").value(Matchers.nullValue()));

        // 组无 DONE 成员时完成 → 409 且 details 为未满足组序号
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-600", "T")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value(1));

        // 完成 A 后 T 可完成，快照冻结选中依据
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-600", "A")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-600", "T")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.completionSnapshot[0].selectedBasis")
                        .value("INC-600/A"));
    }

    @Test
    void prereqHttpErrors() throws Exception {
        reportAndTakeover("INC-610", "alice");
        createTask("INC-610", "alice", "T");
        createTask("INC-610", "alice", "A");
        createTask("INC-610", "alice", "B");

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/prerequisites", "INC-610", "T")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(key(), 1, twoMembers("INC-610", "A", "INC-610", "B"))))
                .andExpect(status().isBadRequest());
        // 单成员组 → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/prerequisites", "INC-610", "T")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(key(), 1,
                                "[[{\"incidentKey\":\"INC-610\",\"taskKey\":\"A\","
                                        + "\"expectedTaskVersion\":1}]]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        // 非当前指挥人 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/prerequisites", "INC-610", "T")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(key(), 1, twoMembers("INC-610", "A", "INC-610", "B"))))
                .andExpect(status().isConflict());
        // 任务不存在 → 404
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/prerequisites", "INC-610", "T-9")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(key(), 1, twoMembers("INC-610", "A", "INC-610", "B"))))
                .andExpect(status().isNotFound());
        // 旧版本 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/prerequisites", "INC-610", "T")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(key(), 7, twoMembers("INC-610", "A", "INC-610", "B"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void prereqHttpCycle_422() throws Exception {
        reportAndTakeover("INC-620", "alice");
        createTask("INC-620", "alice", "X");
        createTask("INC-620", "alice", "Y");
        createTask("INC-620", "alice", "Z");
        // X → {Y,Z}
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/prerequisites", "INC-620", "X")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(key(), 1, twoMembers("INC-620", "Y", "INC-620", "Z"))))
                .andExpect(status().isOk());
        // Y → {X,Z} 成环 → 422
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/prerequisites", "INC-620", "Y")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"expectedTaskVersion\":1,"
                                + "\"groups\":[[{\"incidentKey\":\"INC-620\",\"taskKey\":\"X\","
                                + "\"expectedTaskVersion\":2},{\"incidentKey\":\"INC-620\","
                                + "\"taskKey\":\"Z\",\"expectedTaskVersion\":1}]]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));
    }
}

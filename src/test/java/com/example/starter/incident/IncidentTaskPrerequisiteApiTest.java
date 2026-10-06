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
 * 择一前置组 HTTP 层测试：验证配置路由、requestId 幂等、版本与组视图 JSON 结构、
 * 400/404/409/422 错误语义及题干合成场景（{A,B} 择一 + 原阻塞事件 C）的端到端表现。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IncidentTaskPrerequisiteApiTest {

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
        jdbc.update("DELETE FROM task_prerequisite_members");
        jdbc.update("DELETE FROM task_prerequisite_groups");
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
                                + "\",\"severity\":\"S2\",\"summary\":\"s\",\"reporter\":\"r\"}"))
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
                                + "\",\"groupCode\":\"G\",\"title\":\"t\","
                                + "\"blockerIncidentKeys\":" + blockersJson + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.prerequisiteGroups.length()").value(0));
    }

    private void changeStatus(String incidentKey, String actor, String target) throws Exception {
        mvc.perform(post("/api/incidents/{k}/status", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\""
                                + target + "\"}"))
                .andExpect(status().isOk());
    }

    private void completeTask(String incidentKey, String actor, String taskKey) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", incidentKey, taskKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
    }

    private static String configureBody(String requestId, int expectedVersion, String groupsJson) {
        return "{\"requestId\":\"" + requestId + "\",\"expectedTaskVersion\":" + expectedVersion
                + ",\"groups\":" + groupsJson + "}";
    }

    /** 题干合成场景：T 需择一组 {A,B} 且原阻塞事件 C 未遏制时 A 完成仍不能完成 T。 */
    @Test
    void syntheticScenarioHttp() throws Exception {
        commanding("INC-T", "alice");
        commanding("INC-C", "carol");
        commanding("INC-A", "anna");
        commanding("INC-B", "beth");
        createTask("INC-T", "alice", "T", "[\"INC-C\"]");
        createTask("INC-A", "anna", "TA", "[]");
        createTask("INC-B", "beth", "TB", "[]");

        // 配置择一组 {A,B}：版本 1→2
        String groups = "[{\"members\":["
                + "{\"incidentKey\":\"INC-A\",\"taskKey\":\"TA\",\"expectedTaskVersion\":1},"
                + "{\"incidentKey\":\"INC-B\",\"taskKey\":\"TB\",\"expectedTaskVersion\":1}]}]";
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/prerequisites", "INC-T", "T")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(key(), 1, groups)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.prerequisiteGroups.length()").value(1))
                .andExpect(jsonPath("$.prerequisiteGroups[0].groupNo").value(1))
                .andExpect(jsonPath("$.prerequisiteGroups[0].members.length()").value(2))
                .andExpect(jsonPath("$.prerequisiteGroups[0].members[0].incidentKey")
                        .value("INC-A"))
                .andExpect(jsonPath("$.prerequisiteGroups[0].members[0].satisfied").value(false))
                .andExpect(jsonPath("$.prerequisiteGroups[0].selectedMember").doesNotExist());

        // A 完成但 C 未遏制：完成 T 仍 409，details 为未解除阻塞事件
        completeTask("INC-A", "anna", "TA");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-T", "T")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("INC-C"));

        // C 遏制后 T 可完成：版本 2→3，冻结选中依据为 A；B 仍 OPEN 不自动取消
        changeStatus("INC-C", "carol", "CONTAINED");
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-T", "T")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.prerequisiteGroups[0].selectedMember.incidentKey")
                        .value("INC-A"))
                .andExpect(jsonPath("$.prerequisiteGroups[0].selectedMember.taskKey")
                        .value("TA"));
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-B", "TB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void configureHttpErrors() throws Exception {
        commanding("INC-E1", "alice");
        commanding("INC-E2", "bob");
        createTask("INC-E1", "alice", "T", "[]");
        createTask("INC-E2", "bob", "M1", "[]");
        createTask("INC-E2", "bob", "M2", "[]");

        // 每组至少 2 个成员 → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/prerequisites", "INC-E1", "T")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(key(), 1, "[{\"members\":["
                                + "{\"incidentKey\":\"INC-E2\",\"taskKey\":\"M1\","
                                + "\"expectedTaskVersion\":1}]}]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        // 组内成员重复 → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/prerequisites", "INC-E1", "T")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(key(), 1, "[{\"members\":["
                                + "{\"incidentKey\":\"INC-E2\",\"taskKey\":\"M1\","
                                + "\"expectedTaskVersion\":1},"
                                + "{\"incidentKey\":\"INC-E2\",\"taskKey\":\"M1\","
                                + "\"expectedTaskVersion\":1}]}]")))
                .andExpect(status().isBadRequest());
        // 成员事件不存在 → 404
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/prerequisites", "INC-E1", "T")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(key(), 1, "[{\"members\":["
                                + "{\"incidentKey\":\"INC-404\",\"taskKey\":\"M1\","
                                + "\"expectedTaskVersion\":1},"
                                + "{\"incidentKey\":\"INC-E2\",\"taskKey\":\"M1\","
                                + "\"expectedTaskVersion\":1}]}]")))
                .andExpect(status().isNotFound());
        // 非当前指挥人 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/prerequisites", "INC-E1", "T")
                        .header("X-Actor-Id", "mallory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(key(), 1, "[{\"members\":["
                                + "{\"incidentKey\":\"INC-E2\",\"taskKey\":\"M1\","
                                + "\"expectedTaskVersion\":1},"
                                + "{\"incidentKey\":\"INC-E2\",\"taskKey\":\"M2\","
                                + "\"expectedTaskVersion\":1}]}]")))
                .andExpect(status().isConflict());
        // 旧版本 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/prerequisites", "INC-E1", "T")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(key(), 9, "[{\"members\":["
                                + "{\"incidentKey\":\"INC-E2\",\"taskKey\":\"M1\","
                                + "\"expectedTaskVersion\":1},"
                                + "{\"incidentKey\":\"INC-E2\",\"taskKey\":\"M2\","
                                + "\"expectedTaskVersion\":1}]}]")))
                .andExpect(status().isConflict());
        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/prerequisites", "INC-E1", "T")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(key(), 1, "[]")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void configureHttpCycleAndIdempotency() throws Exception {
        commanding("INC-H1", "alice");
        commanding("INC-H2", "bob");
        createTask("INC-H1", "alice", "T1", "[]");
        createTask("INC-H1", "alice", "X", "[]");
        createTask("INC-H2", "bob", "T2", "[]");
        createTask("INC-H2", "bob", "Y", "[]");

        // T1 前置 {T2, X} 成功
        String requestId = key();
        String groups = "[{\"members\":["
                + "{\"incidentKey\":\"INC-H2\",\"taskKey\":\"T2\",\"expectedTaskVersion\":1},"
                + "{\"incidentKey\":\"INC-H1\",\"taskKey\":\"X\",\"expectedTaskVersion\":1}]}]";
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/prerequisites", "INC-H1", "T1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(requestId, 1, groups)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 同 requestId 同参（组内换序）重放首次响应，版本不再递增
        String reordered = "[{\"members\":["
                + "{\"incidentKey\":\"INC-H1\",\"taskKey\":\"X\",\"expectedTaskVersion\":1},"
                + "{\"incidentKey\":\"INC-H2\",\"taskKey\":\"T2\",\"expectedTaskVersion\":1}]}]";
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/prerequisites", "INC-H1", "T1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(requestId, 1, reordered)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 同 requestId 异参 → 409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/prerequisites", "INC-H1", "T1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(requestId, 2, "[{\"members\":["
                                + "{\"incidentKey\":\"INC-H2\",\"taskKey\":\"Y\","
                                + "\"expectedTaskVersion\":1},"
                                + "{\"incidentKey\":\"INC-H1\",\"taskKey\":\"X\","
                                + "\"expectedTaskVersion\":1}]}]")))
                .andExpect(status().isConflict());
        // T2 前置 {T1, Y} 与已有 T1→T2 成环 → 422 且不留边
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/prerequisites", "INC-H2", "T2")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(key(), 1, "[{\"members\":["
                                + "{\"incidentKey\":\"INC-H1\",\"taskKey\":\"T1\","
                                + "\"expectedTaskVersion\":2},"
                                + "{\"incidentKey\":\"INC-H2\",\"taskKey\":\"Y\","
                                + "\"expectedTaskVersion\":1}]}]")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_CYCLE"));
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-H2", "T2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prerequisiteGroups.length()").value(0));
    }
}

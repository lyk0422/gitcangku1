package com.example.starter.incident.plan;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * 方案合并 HTTP 层测试：验证路由、X-Actor-Id 请求头约束、
 * 201/200/400/404/409/422 错误语义及合并证据只读查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlanApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM plan_merges");
        jdbc.update("DELETE FROM plan_task_executions");
        jdbc.update("DELETE FROM plan_edges");
        jdbc.update("DELETE FROM plan_tasks");
        jdbc.update("DELETE FROM plan_versions");
        jdbc.update("DELETE FROM command_keys");
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

    private long createPlan(String incidentKey, String actor) throws Exception {
        MvcResult result = mvc.perform(post("/api/incidents/{k}/plan-versions", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\","
                                + "\"tasks\":[{\"taskId\":\"A\",\"title\":\"a\",\"assignee\":\"u1\"},"
                                + "{\"taskId\":\"B\",\"title\":\"b\",\"assignee\":null}],"
                                + "\"edges\":[{\"fromTaskId\":\"B\",\"toTaskId\":\"A\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.tasks[0].taskId").value("A"))
                .andExpect(jsonPath("$.tasks[0].status").value("PENDING"))
                .andExpect(jsonPath("$.edges[0].fromTaskId").value("B"))
                .andExpect(jsonPath("$.edges[0].toIncidentKey").value(incidentKey))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private long createBranch(String incidentKey, String actor, long baseId) throws Exception {
        MvcResult result = mvc.perform(post("/api/incidents/{k}/plan-versions/{v}/branches",
                        incidentKey, baseId)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void planCrud_routes() throws Exception {
        commanding("INC-A1", "alice");
        long baseId = createPlan("INC-A1", "alice");

        // 活动版本与指定版本查询
        mvc.perform(get("/api/incidents/{k}/plan", "INC-A1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(baseId))
                .andExpect(jsonPath("$.tasks.length()").value(2));
        mvc.perform(get("/api/incidents/{k}/plan-versions/{v}", "INC-A1", baseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        // 草稿编辑：改任务、加边、删边
        long draftId = createBranch("INC-A1", "alice", baseId);
        mvc.perform(put("/api/incidents/{k}/plan-versions/{v}/tasks/{t}",
                        "INC-A1", draftId, "A")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"a2\",\"assignee\":\"u1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.tasks[0].title").value("a2"));
        mvc.perform(delete("/api/incidents/{k}/plan-versions/{v}/edges",
                        "INC-A1", draftId)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromTaskId\":\"B\",\"toTaskId\":\"A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edges.length()").value(0));
        // 删除草稿任务
        mvc.perform(delete("/api/incidents/{k}/plan-versions/{v}/tasks/{t}",
                        "INC-A1", draftId, "B")
                        .header("X-Actor-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(1));

        // 任务执行：开始与完成
        mvc.perform(post("/api/incidents/{k}/plan/tasks/{t}/start", "INC-A1", "A")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mvc.perform(post("/api/incidents/{k}/plan/tasks/{t}/complete", "INC-A1", "A")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedBy").value("alice"));
    }

    @Test
    void mergeFlow_routesAndEvidence() throws Exception {
        commanding("INC-A2", "alice");
        long baseId = createPlan("INC-A2", "alice");
        long leftId = createBranch("INC-A2", "alice", baseId);
        long rightId = createBranch("INC-A2", "alice", baseId);
        // 左分支改 A 标题制造字段分歧
        mvc.perform(put("/api/incidents/{k}/plan-versions/{v}/tasks/{t}",
                        "INC-A2", leftId, "A")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"a-left\",\"assignee\":\"u1\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/incidents/{k}/plan-versions/{v}/tasks/{t}",
                        "INC-A2", rightId, "A")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"a-right\",\"assignee\":\"u1\"}"))
                .andExpect(status().isOk());

        // 差异查询：一个 TASK|A 冲突
        mvc.perform(get("/api/incidents/{k}/plan-merge/diff", "INC-A2")
                        .param("baseVersionId", String.valueOf(baseId))
                        .param("leftVersionId", String.valueOf(leftId))
                        .param("rightVersionId", String.valueOf(rightId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflicts.length()").value(1))
                .andExpect(jsonPath("$.conflicts[0].conflictId").value("TASK|A"))
                .andExpect(jsonPath("$.conflicts[0].type").value("FIELD_DIVERGENCE"));

        // 遗漏解决：400
        mvc.perform(post("/api/incidents/{k}/plan-merges", "INC-A2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"mergeKey\":\"MG-A2\","
                                + "\"baseVersionId\":" + baseId + ",\"leftVersionId\":" + leftId
                                + ",\"rightVersionId\":" + rightId
                                + ",\"leftExpectedVersion\":2,\"rightExpectedVersion\":2,"
                                + "\"resolutions\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        // 正确解决：发布成功
        mvc.perform(post("/api/incidents/{k}/plan-merges", "INC-A2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"mergeKey\":\"MG-A2\","
                                + "\"baseVersionId\":" + baseId + ",\"leftVersionId\":" + leftId
                                + ",\"rightVersionId\":" + rightId
                                + ",\"leftExpectedVersion\":2,\"rightExpectedVersion\":2,"
                                + "\"resolutions\":[{\"conflictId\":\"TASK|A\","
                                + "\"choice\":\"MANUAL\",\"manualTask\":{\"title\":\"a-final\","
                                + "\"assignee\":\"u1\"}}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.resolvedConflictCount").value(1))
                .andExpect(jsonPath("$.resultVersionNo").value(4));

        // 合并证据只读查询
        mvc.perform(get("/api/incidents/{k}/plan-merges/{m}", "INC-A2", "MG-A2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mergeKey").value("MG-A2"))
                .andExpect(jsonPath("$.resolutions.length()").value(1))
                .andExpect(jsonPath("$.finalTasks[0].title").value("a-final"))
                .andExpect(jsonPath("$.diff.conflicts.length()").value(1));
        // 原分支已 MERGED
        mvc.perform(get("/api/incidents/{k}/plan-versions/{v}", "INC-A2", leftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERGED"));
    }

    @Test
    void errorSemantics() throws Exception {
        commanding("INC-A3", "alice");
        // 缺少 X-Actor-Id：400
        mvc.perform(post("/api/incidents/{k}/plan-versions", "INC-A3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"tasks\":[],\"edges\":[]}"))
                .andExpect(status().isBadRequest());
        // 事件不存在：404
        mvc.perform(get("/api/incidents/{k}/plan", "INC-404"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents/{k}/plan-merges/{m}", "INC-A3", "MG-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // 非指挥人：409
        mvc.perform(post("/api/incidents/{k}/plan-versions", "INC-A3")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\",\"tasks\":[],\"edges\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        // 成环初始版本：409
        mvc.perform(post("/api/incidents/{k}/plan-versions", "INC-A3")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key() + "\","
                                + "\"tasks\":[{\"taskId\":\"A\",\"title\":\"a\"},"
                                + "{\"taskId\":\"B\",\"title\":\"b\"}],"
                                + "\"edges\":[{\"fromTaskId\":\"A\",\"toTaskId\":\"B\"},"
                                + "{\"fromTaskId\":\"B\",\"toTaskId\":\"A\"}]}"))
                .andExpect(status().isConflict());
    }
}

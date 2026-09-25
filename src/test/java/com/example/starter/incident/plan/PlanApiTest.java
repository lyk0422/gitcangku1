package com.example.starter.incident.plan;

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
 * 方案版本与三方合并 HTTP 层测试：验证路由、X-Actor-Id 请求头约束
 * 及 400/404/409/422 错误语义可区分。
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
        jdbc.update("DELETE FROM plan_task_state");
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
                                + "\",\"severity\":\"S1\",\"summary\":\"s\",\"reporter\":\"r\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/incidents/{k}/takeover", incidentKey)
                        .header("X-Actor-Id", commander)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
    }

    private void createPlan(String ik) throws Exception {
        mvc.perform(post("/api/incidents/{k}/plan/versions", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\","
                                + "\"tasks\":[{\"taskId\":\"A\",\"groupCode\":\"G\","
                                + "\"title\":\"ta\",\"assignee\":\"u1\"}],\"edges\":[]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.tasks[0].status").value("PENDING"));
    }

    private void createDrafts(String ik) throws Exception {
        mvc.perform(post("/api/incidents/{k}/plan/drafts", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"baseVersion\":1,\"branch\":\"LEFT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(2))
                .andExpect(jsonPath("$.status").value("DRAFT"));
        mvc.perform(post("/api/incidents/{k}/plan/drafts", ik)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"baseVersion\":1,\"branch\":\"RIGHT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(3));
    }

    @Test
    void apiMainFlow() throws Exception {
        commanding("INC-API", "alice");
        createPlan("INC-API");
        createDrafts("INC-API");
        // 左支改标题，右支不改 → 无冲突自动合并
        mvc.perform(post("/api/incidents/{k}/plan/drafts/2/tasks", "INC-API")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskId\":\"A\","
                                + "\"groupCode\":\"G\",\"title\":\"左改\",\"assignee\":\"u1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));

        mvc.perform(get("/api/incidents/{k}/plan/diff?base=1&left=2&right=3", "INC-API"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].kind").value("TASK_MODIFIED"))
                .andExpect(jsonPath("$.changes[0].source").value("LEFT"))
                .andExpect(jsonPath("$.conflicts").isEmpty());

        mvc.perform(post("/api/incidents/{k}/plan/merges", "INC-API")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-API\",\"mergeKey\":\"MK-API\","
                                + "\"baseVersion\":1,\"leftVersion\":2,\"rightVersion\":3,"
                                + "\"leftExpectedVersion\":1,\"rightExpectedVersion\":0,"
                                + "\"resolutions\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultVersionNo").value(4))
                .andExpect(jsonPath("$.conflictsResolved").value(0));

        mvc.perform(get("/api/incidents/{k}/plan", "INC-API"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(4))
                .andExpect(jsonPath("$.tasks[0].title").value("左改"));

        mvc.perform(get("/api/incidents/{k}/plan/merges/MK-API", "INC-API"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("REQ-API"))
                .andExpect(jsonPath("$.resultVersionNo").value(4))
                .andExpect(jsonPath("$.tasks[0].taskId").value("A"));

        // 任务执行：启动 → 完成
        mvc.perform(post("/api/incidents/{k}/plan/tasks/A/start", "INC-API")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mvc.perform(post("/api/incidents/{k}/plan/tasks/A/complete", "INC-API")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void apiErrorSemantics() throws Exception {
        commanding("INC-ERR", "alice");
        createPlan("INC-ERR");
        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/incidents/{k}/plan/drafts", "INC-ERR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"baseVersion\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        // 非指挥人 → 409
        mvc.perform(post("/api/incidents/{k}/plan/drafts", "INC-ERR")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"baseVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        // 事件不存在 → 404
        mvc.perform(get("/api/incidents/{k}/plan", "INC-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // 版本不存在 → 404
        mvc.perform(get("/api/incidents/{k}/plan/versions/99", "INC-ERR"))
                .andExpect(status().isNotFound());
        // 参数非法（缺 mergeKey）→ 400
        mvc.perform(post("/api/incidents/{k}/plan/merges", "INC-ERR")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"R1\",\"baseVersion\":1,\"leftVersion\":2,"
                                + "\"rightVersion\":3,\"leftExpectedVersion\":0,"
                                + "\"rightExpectedVersion\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void apiMergeConflictAnd422() throws Exception {
        commanding("INC-422", "alice");
        mvc.perform(post("/api/incidents/{k}/plan/versions", "INC-422")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"tasks\":["
                                + "{\"taskId\":\"A\",\"groupCode\":\"G\",\"title\":\"ta\","
                                + "\"assignee\":\"u1\"}],\"edges\":[]}"))
                .andExpect(status().isCreated());
        createDrafts("INC-422");
        // 双侧对 A 标题分歧 → 冲突；未提交解决 → 400
        mvc.perform(post("/api/incidents/{k}/plan/drafts/2/tasks", "INC-422")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskId\":\"A\","
                                + "\"groupCode\":\"G\",\"title\":\"左\",\"assignee\":\"u1\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/plan/drafts/3/tasks", "INC-422")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskId\":\"A\","
                                + "\"groupCode\":\"G\",\"title\":\"右\",\"assignee\":\"u1\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/incidents/{k}/plan/diff?base=1&left=2&right=3", "INC-422"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflicts[0].conflictId").value("TASK:A"))
                .andExpect(jsonPath("$.conflicts[0].type").value("TASK_FIELD_CONFLICT"));
        // 遗漏解决 → 400
        mvc.perform(post("/api/incidents/{k}/plan/merges", "INC-422")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-4\",\"mergeKey\":\"MK-4\","
                                + "\"baseVersion\":1,\"leftVersion\":2,\"rightVersion\":3,"
                                + "\"leftExpectedVersion\":1,\"rightExpectedVersion\":1,"
                                + "\"resolutions\":[]}"))
                .andExpect(status().isBadRequest());
        // MANUAL 给出完整任务字段 → 成功
        mvc.perform(post("/api/incidents/{k}/plan/merges", "INC-422")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-4\",\"mergeKey\":\"MK-4\","
                                + "\"baseVersion\":1,\"leftVersion\":2,\"rightVersion\":3,"
                                + "\"leftExpectedVersion\":1,\"rightExpectedVersion\":1,"
                                + "\"resolutions\":[{\"conflictId\":\"TASK:A\","
                                + "\"choice\":\"MANUAL\",\"manualTask\":{\"taskId\":\"A\","
                                + "\"groupCode\":\"G\",\"title\":\"手工\",\"assignee\":\"u9\"}}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultVersionNo").value(4));
        mvc.perform(get("/api/incidents/{k}/plan", "INC-422"))
                .andExpect(jsonPath("$.tasks[0].title").value("手工"))
                .andExpect(jsonPath("$.tasks[0].assignee").value("u9"));
    }
}

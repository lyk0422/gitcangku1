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
 * 重复事件合并 HTTP 层测试：验证合并与查询路由、X-Actor-Id 请求头约束、
 * 400/404/409 错误语义及 taskKey 冲突 details 结构化明细。
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
        jdbc.update("DELETE FROM incident_merge_tasks");
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

    private void createTask(String incidentKey, String actor, String taskKey) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"DB\",\"title\":\"扩容\","
                                + "\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isOk());
    }

    private static String mergeBody(String mergeKey, String survivingKey, String mergedKey,
                                    long survivingVersion, long mergedVersion) {
        return "{\"commandKey\":\"" + key() + "\",\"mergeKey\":\"" + mergeKey
                + "\",\"survivingIncidentKey\":\"" + survivingKey
                + "\",\"mergedIncidentKey\":\"" + mergedKey
                + "\",\"survivingExpectedVersion\":" + survivingVersion
                + ",\"mergedExpectedVersion\":" + mergedVersion + "}";
    }

    @Test
    void mergeHttpFlow() throws Exception {
        report("INC-600");
        report("INC-601");
        takeover("INC-600", "alice");
        takeover("INC-601", "alice");
        createTask("INC-601", "alice", "T-1");

        // 合并：INC-601 并入 INC-600
        mvc.perform(post("/api/incidents/merges")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("MR-600", "INC-600", "INC-601", 0, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mergeKey").value("MR-600"))
                .andExpect(jsonPath("$.survivingIncidentKey").value("INC-600"))
                .andExpect(jsonPath("$.mergedIncidentKey").value("INC-601"))
                .andExpect(jsonPath("$.actor").value("alice"))
                .andExpect(jsonPath("$.migratedTaskKeys[0]").value("T-1"));

        // 事件视图携带版本与并入指向
        mvc.perform(get("/api/incidents/{k}", "INC-601"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERGED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.mergedIntoIncidentKey").value("INC-600"));
        mvc.perform(get("/api/incidents/{k}", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.mergedIntoIncidentKey").isEmpty());

        // 合并记录查询（稳定排序）
        mvc.perform(get("/api/incidents/merges"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merges.length()").value(1))
                .andExpect(jsonPath("$.merges[0].mergeKey").value("MR-600"));

        // 合并后任务归属查询
        mvc.perform(get("/api/incidents/{k}/task-ownership", "INC-601"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERGED"))
                .andExpect(jsonPath("$.mergedIntoIncidentKey").value("INC-600"))
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].taskKey").value("T-1"))
                .andExpect(jsonPath("$.tasks[0].ownerIncidentKey").value("INC-600"))
                .andExpect(jsonPath("$.tasks[0].status").value("OPEN"));

        // 迁移后的任务出现在存续事件任务列表
        mvc.perform(get("/api/incidents/{k}/tasks", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].taskKey").value("T-1"));
    }

    @Test
    void mergeHttpErrors() throws Exception {
        report("INC-610");
        report("INC-611");
        takeover("INC-610", "alice");
        takeover("INC-611", "alice");
        createTask("INC-610", "alice", "T-1");
        createTask("INC-611", "alice", "T-1");

        // taskKey 冲突：409 且 details 返回冲突键
        mvc.perform(post("/api/incidents/merges")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("MR-610", "INC-610", "INC-611", 0, 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.details[0]").value("T-1"));

        // 同一事件：409
        mvc.perform(post("/api/incidents/merges")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("MR-611", "INC-610", "INC-610", 0, 0)))
                .andExpect(status().isConflict());

        // 版本不匹配：409
        mvc.perform(post("/api/incidents/merges")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("MR-612", "INC-610", "INC-611", 3, 0)))
                .andExpect(status().isConflict());

        // 事件不存在：404
        mvc.perform(post("/api/incidents/merges")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("MR-613", "INC-610", "INC-999", 0, 0)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // 缺少必填字段 / 缺少 X-Actor-Id：400
        mvc.perform(post("/api/incidents/merges")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"mergeKey\":\"MR-614\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/incidents/merges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("MR-615", "INC-610", "INC-611", 0, 0)))
                .andExpect(status().isBadRequest());

        // 全部失败均无副作用
        mvc.perform(get("/api/incidents/merges"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merges.length()").value(0));
        mvc.perform(get("/api/incidents/{k}", "INC-611"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMANDING"));
    }

    @Test
    void mergeHttp_idempotentReplay() throws Exception {
        report("INC-620");
        report("INC-621");
        takeover("INC-620", "alice");
        takeover("INC-621", "alice");
        String commandKey = key();
        String body = "{\"commandKey\":\"" + commandKey
                + "\",\"mergeKey\":\"MR-620\",\"survivingIncidentKey\":\"INC-620\","
                + "\"mergedIncidentKey\":\"INC-621\",\"survivingExpectedVersion\":0,"
                + "\"mergedExpectedVersion\":0}";

        // 同键同参重放首次结果
        mvc.perform(post("/api/incidents/merges")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mergeKey").value("MR-620"));
        mvc.perform(post("/api/incidents/merges")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mergeKey").value("MR-620"));
        mvc.perform(get("/api/incidents/merges"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merges.length()").value(1));

        // 同键改参：409
        mvc.perform(post("/api/incidents/merges")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey
                                + "\",\"mergeKey\":\"MR-620B\","
                                + "\"survivingIncidentKey\":\"INC-620\","
                                + "\"mergedIncidentKey\":\"INC-621\","
                                + "\"survivingExpectedVersion\":0,"
                                + "\"mergedExpectedVersion\":0}"))
                .andExpect(status().isConflict());
    }
}

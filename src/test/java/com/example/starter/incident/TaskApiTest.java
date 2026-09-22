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
 * 处置任务 HTTP 层测试：验证任务路由、X-Actor-Id 约束、阻塞解除/环检测/解决门禁的
 * 409 结构化明细，以及只读查询不隐式写入。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaskApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_task_blocks");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_escalations");
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

    @Test
    void taskHttpFlow_createBlockedQueryComplete() throws Exception {
        report("INC-T1");
        report("INC-T2");
        takeover("INC-T1", "alice");
        takeover("INC-T2", "bob");

        // 创建：A 的任务阻塞在 B
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-T1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"taskKey\":\"TASK-1\",\"groupCode\":\"NET\",\"title\":\"等 B 遏制\","
                                + "\"blockedIncidentKeys\":[\"INC-T2\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.completable").value(false))
                .andExpect(jsonPath("$.blockedIncidents[0].incidentKey").value("INC-T2"))
                .andExpect(jsonPath("$.blockedIncidents[0].lifted").value(false));

        // 缺少 X-Actor-Id：400
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-T1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"taskKey\":\"X\",\"groupCode\":\"G\",\"title\":\"t\"}"))
                .andExpect(status().isBadRequest());

        // 非指挥人创建：409
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-T1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"taskKey\":\"X\",\"groupCode\":\"G\",\"title\":\"t\"}"))
                .andExpect(status().isConflict());

        // 未解除时完成：409，details 返回仍未解除事件
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-T1", "TASK-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("INC-T2"));

        // 分组查询
        mvc.perform(get("/api/incidents/{k}/tasks", "INC-T1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].groupCode").value("NET"))
                .andExpect(jsonPath("$[0].tasks[0].taskKey").value("TASK-1"));

        // 单任务明细
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-T1", "TASK-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("等 B 遏制"));
        mvc.perform(get("/api/incidents/{k}/tasks/{t}", "INC-T1", "MISSING"))
                .andExpect(status().isNotFound());

        // B 遏制后完成成功
        mvc.perform(post("/api/incidents/{k}/status", "INC-T2")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\"CONTAINED\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-T1", "TASK-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.blockedIncidents[0].lifted").value(true));
    }

    @Test
    void cycleAndResolveGate_returnStructured409() throws Exception {
        report("INC-C1");
        report("INC-C2");
        takeover("INC-C1", "alice");
        takeover("INC-C2", "carol");

        mvc.perform(post("/api/incidents/{k}/tasks", "INC-C1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"taskKey\":\"A1\",\"groupCode\":\"G\",\"title\":\"等 C2\","
                                + "\"blockedIncidentKeys\":[\"INC-C2\"]}"))
                .andExpect(status().isCreated());

        // 反向边直接成环：409 且无部分写入
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-C2")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"taskKey\":\"C1\",\"groupCode\":\"G\",\"title\":\"等 C1\","
                                + "\"blockedIncidentKeys\":[\"INC-C1\"]}"))
                .andExpect(status().isConflict());

        // C1 仍有 OPEN 任务，CONTAINED -> RESOLVED 被门禁拒绝
        mvc.perform(post("/api/incidents/{k}/status", "INC-C1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\"CONTAINED\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/status", "INC-C1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\"RESOLVED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0].groupCode").value("G"))
                .andExpect(jsonPath("$.details[0].taskKey").value("A1"));
    }

    @Test
    void cancelFlow_andIdempotentReplay() throws Exception {
        report("INC-X1");
        takeover("INC-X1", "alice");
        String commandKey = key();
        String body = "{\"commandKey\":\"" + commandKey
                + "\",\"taskKey\":\"K1\",\"groupCode\":\"G\",\"title\":\"将取消\"}";
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-X1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        String cancelKey = key();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/incidents/{k}/tasks/{t}/cancel", "INC-X1", "K1")
                            .header("X-Actor-Id", "alice")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commandKey\":\"" + cancelKey + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
        }
        // 已取消再完成：409
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-X1", "K1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict());
    }
}

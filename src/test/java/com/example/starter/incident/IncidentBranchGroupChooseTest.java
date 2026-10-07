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
 * 分支组选择拒绝分支与幂等语义测试：未知组/成员 404、旧版本 409、
 * 普通完成/取消入口对组内任务 409、同键同参重放首次响应、
 * 同键异参 409、失败后可复用键。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IncidentBranchGroupChooseTest {

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
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_branch_group_members");
        jdbc.update("DELETE FROM incident_branch_groups");
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

    private void createTask(String incidentKey, String actor, String taskKey) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"OP\",\"title\":\"t\","
                                + "\"blockerIncidentKeys\":[]}"))
                .andExpect(status().isOk());
    }

    private void createGroup(String incidentKey, String actor, String groupKey,
                             String membersJson) throws Exception {
        mvc.perform(post("/api/incidents/{k}/branch-groups", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"groupKey\":\"" + groupKey
                                + "\",\"memberTaskKeys\":" + membersJson + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void chooseRejections_notFoundAndVersion() throws Exception {
        commanding("INC-620", "alice");
        createTask("INC-620", "alice", "T1");
        createTask("INC-620", "alice", "T2");
        createTask("INC-620", "alice", "T3");
        createGroup("INC-620", "alice", "BG-1", "[\"T1\",\"T2\"]");

        // 未知组 → 404
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-620", "BG-9")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T1\","
                                + "\"expectedGroupVersion\":1}"))
                .andExpect(status().isNotFound());
        // 未知成员（T3 不在组内）→ 404
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-620", "BG-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T3\","
                                + "\"expectedGroupVersion\":1}"))
                .andExpect(status().isNotFound());
        // 版本不匹配 → 409
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-620", "BG-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T1\","
                                + "\"expectedGroupVersion\":5}"))
                .andExpect(status().isConflict());
        // 缺少 expectedGroupVersion → 400
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-620", "BG-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T1\"}"))
                .andExpect(status().isBadRequest());
        // 非当前指挥人 → 409（权限先于业务）
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-620", "BG-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T1\","
                                + "\"expectedGroupVersion\":1}"))
                .andExpect(status().isConflict());

        // 普通完成/取消入口不能绕开整组选择 → 均 409，任务仍 OPEN
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-620", "T1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/cancel", "INC-620", "T2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/incidents/{k}/tasks", "INC-620"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].status").value("OPEN"))
                .andExpect(jsonPath("$.tasks[1].status").value("OPEN"));
    }

    @Test
    void idempotency_replayAndReuse() throws Exception {
        commanding("INC-630", "alice");
        createTask("INC-630", "alice", "T1");
        createTask("INC-630", "alice", "T2");

        // 同 commandKey 同参建组 → 重放首次响应，组只建一次
        String createKey = key();
        String body = "{\"commandKey\":\"" + createKey + "\",\"groupKey\":\"BG-1\","
                + "\"memberTaskKeys\":[\"T1\",\"T2\"]}";
        mvc.perform(post("/api/incidents/{k}/branch-groups", "INC-630")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(post("/api/incidents/{k}/branch-groups", "INC-630")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/incidents/{k}/branch-groups", "INC-630"))
                .andExpect(jsonPath("$.groups.length()").value(1));

        // 同 commandKey 异参 → 409
        mvc.perform(post("/api/incidents/{k}/branch-groups", "INC-630")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + createKey + "\",\"groupKey\":\"BG-2\","
                                + "\"memberTaskKeys\":[\"T1\",\"T2\"]}"))
                .andExpect(status().isConflict());

        // 选择：同键同参重放首次响应；同键异参 409
        String chooseKey = key();
        String chooseBody = "{\"commandKey\":\"" + chooseKey + "\",\"taskKey\":\"T1\","
                + "\"expectedGroupVersion\":1}";
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-630", "BG-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(chooseBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHOSEN"));
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-630", "BG-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(chooseBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHOSEN"))
                .andExpect(jsonPath("$.chosenTaskKey").value("T1"));
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-630", "BG-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + chooseKey + "\",\"taskKey\":\"T2\","
                                + "\"expectedGroupVersion\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void failedChoose_keyReusableAfterBlockerResolved() throws Exception {
        commanding("INC-640", "alice");
        commanding("INC-641", "bob");
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-640")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T1\","
                                + "\"groupCode\":\"OP\",\"title\":\"t\","
                                + "\"blockerIncidentKeys\":[\"INC-641\"]}"))
                .andExpect(status().isOk());
        createTask("INC-640", "alice", "T2");
        createGroup("INC-640", "alice", "BG-1", "[\"T1\",\"T2\"]");

        // 选择因阻塞未解除失败（409），失败不占键
        String chooseKey = key();
        String chooseBody = "{\"commandKey\":\"" + chooseKey + "\",\"taskKey\":\"T1\","
                + "\"expectedGroupVersion\":1}";
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-640", "BG-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(chooseBody))
                .andExpect(status().isConflict());

        // 阻塞解除后同一 commandKey 重试成功
        mvc.perform(post("/api/incidents/{k}/status", "INC-641")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key()
                                + "\",\"targetStatus\":\"CONTAINED\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-640", "BG-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(chooseBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHOSEN"))
                .andExpect(jsonPath("$.chosenTaskKey").value("T1"));
    }
}

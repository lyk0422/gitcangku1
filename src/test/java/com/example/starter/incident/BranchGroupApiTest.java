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
 * 互斥分支组 HTTP 层测试：覆盖题干主流程（合成组{隔离T1,迁移T2}选 T1 后
 * T1 DONE、T2 CANCELLED，新键再选 T2 拒绝且不改历史）、建组 422/404/400 校验、
 * 选择 404/409 边界、普通完成/取消入口对组内任务的 409 拦截及幂等重放语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BranchGroupApiTest {

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
        jdbc.update("DELETE FROM incident_task_branch_members");
        jdbc.update("DELETE FROM incident_task_branch_groups");
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
                            String title) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"G\",\"title\":\"" + title + "\"}"))
                .andExpect(status().isOk());
    }

    private void changeStatus(String incidentKey, String actor, String target) throws Exception {
        mvc.perform(post("/api/incidents/{k}/status", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\""
                                + target + "\"}"))
                .andExpect(status().isOk());
    }

    private void createGroup(String incidentKey, String actor, String groupKey,
                             String taskKeysJson) throws Exception {
        mvc.perform(post("/api/incidents/{k}/branch-groups", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"groupKey\":\"" + groupKey
                                + "\",\"taskKeys\":" + taskKeysJson + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupKey").value(groupKey))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void branchGroupMainFlow() throws Exception {
        report("INC-600");
        takeover("INC-600", "alice");
        createTask("INC-600", "alice", "T1", "隔离");
        createTask("INC-600", "alice", "T2", "迁移");
        createGroup("INC-600", "alice", "BG-1", "[\"T1\",\"T2\"]");

        // 选择 T1：T1 DONE、T2 CANCELLED，组 CHOSEN 且版本加一
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-600", "BG-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T1\","
                                + "\"expectedGroupVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHOSEN"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.chosenTaskKey").value("T1"))
                .andExpect(jsonPath("$.chosenBy").value("alice"))
                .andExpect(jsonPath("$.members[0].taskKey").value("T1"))
                .andExpect(jsonPath("$.members[0].taskStatus").value("DONE"))
                .andExpect(jsonPath("$.members[1].taskKey").value("T2"))
                .andExpect(jsonPath("$.members[1].taskStatus").value("CANCELLED"));

        // 查询一次看到 T1 为 DONE、T2 为 CANCELLED；原事件状态不自动改变
        mvc.perform(get("/api/incidents/{k}/tasks", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].status").value("DONE"))
                .andExpect(jsonPath("$.tasks[1].status").value("CANCELLED"));
        mvc.perform(get("/api/incidents/{k}", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMANDING"));

        // 以新键再选 T2 → 409，且历史不变
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-600", "BG-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T2\","
                                + "\"expectedGroupVersion\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mvc.perform(get("/api/incidents/{k}/branch-groups/{g}", "INC-600", "BG-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHOSEN"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.chosenTaskKey").value("T1"))
                .andExpect(jsonPath("$.members[1].taskStatus").value("CANCELLED"));

        // 选择不替代事件原完成门禁：全组终态后仍可逐级推进至关闭
        changeStatus("INC-600", "alice", "CONTAINED");
        changeStatus("INC-600", "alice", "RESOLVED");
        changeStatus("INC-600", "alice", "CLOSED");
    }

    @Test
    void branchGroupCreateValidation() throws Exception {
        report("INC-610");
        takeover("INC-610", "alice");
        createTask("INC-610", "alice", "T1", "隔离");
        createTask("INC-610", "alice", "T2", "迁移");
        createTask("INC-610", "alice", "T3", "回滚");

        // 成员数量越界 → 400
        mvc.perform(post("/api/incidents/{k}/branch-groups", "INC-610")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"groupKey\":\"BG-1\","
                                + "\"taskKeys\":[\"T1\"]}"))
                .andExpect(status().isBadRequest());

        // 未知任务 → 404
        mvc.perform(post("/api/incidents/{k}/branch-groups", "INC-610")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"groupKey\":\"BG-1\","
                                + "\"taskKeys\":[\"T1\",\"T9\"]}"))
                .andExpect(status().isNotFound());

        // 非当前指挥人 → 409
        mvc.perform(post("/api/incidents/{k}/branch-groups", "INC-610")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"groupKey\":\"BG-1\","
                                + "\"taskKeys\":[\"T1\",\"T2\"]}"))
                .andExpect(status().isConflict());

        // 混入终态成员 → 422，整次不建组
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/cancel", "INC-610", "T3")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/branch-groups", "INC-610")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"groupKey\":\"BG-1\","
                                + "\"taskKeys\":[\"T1\",\"T3\"]}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));
        mvc.perform(get("/api/incidents/{k}/branch-groups", "INC-610"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(0));

        // 正常建组后，成员已属另一组 → 422
        createGroup("INC-610", "alice", "BG-1", "[\"T1\",\"T2\"]");
        mvc.perform(post("/api/incidents/{k}/branch-groups", "INC-610")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"groupKey\":\"BG-2\","
                                + "\"taskKeys\":[\"T2\",\"T1\"]}"))
                .andExpect(status().is(422));

        // 事件非 COMMANDING → 422
        report("INC-611");
        takeover("INC-611", "alice");
        createTask("INC-611", "alice", "T1", "隔离");
        createTask("INC-611", "alice", "T2", "迁移");
        changeStatus("INC-611", "alice", "CONTAINED");
        mvc.perform(post("/api/incidents/{k}/branch-groups", "INC-611")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"groupKey\":\"BG-1\","
                                + "\"taskKeys\":[\"T1\",\"T2\"]}"))
                .andExpect(status().is(422));
    }

    @Test
    void branchGroupChooseErrors() throws Exception {
        report("INC-620");
        takeover("INC-620", "alice");
        createTask("INC-620", "alice", "T1", "隔离");
        createTask("INC-620", "alice", "T2", "迁移");
        createTask("INC-620", "alice", "T3", "组外任务");
        createGroup("INC-620", "alice", "BG-1", "[\"T1\",\"T2\"]");

        // 未知组 → 404
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-620", "BG-9")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T1\","
                                + "\"expectedGroupVersion\":1}"))
                .andExpect(status().isNotFound());

        // 未知成员（组外任务）→ 404
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-620", "BG-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T3\","
                                + "\"expectedGroupVersion\":1}"))
                .andExpect(status().isNotFound());

        // 旧组版本 → 409
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-620", "BG-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T1\","
                                + "\"expectedGroupVersion\":9}"))
                .andExpect(status().isConflict());

        // 普通完成/取消入口对组内任务 → 409，不能绕开整组选择
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
        // 组外任务不受影响
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/complete", "INC-620", "T3")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());

        // 非当前指挥人选择 → 409
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-620", "BG-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T1\","
                                + "\"expectedGroupVersion\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void branchGroupIdempotency() throws Exception {
        report("INC-630");
        takeover("INC-630", "alice");
        createTask("INC-630", "alice", "T1", "隔离");
        createTask("INC-630", "alice", "T2", "迁移");

        // 失败可复用键：先以同键触发 404（未知任务），修正后同键成功
        String createKey = key();
        mvc.perform(post("/api/incidents/{k}/branch-groups", "INC-630")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + createKey + "\",\"groupKey\":\"BG-1\","
                                + "\"taskKeys\":[\"T1\",\"T9\"]}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/incidents/{k}/branch-groups", "INC-630")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + createKey + "\",\"groupKey\":\"BG-1\","
                                + "\"taskKeys\":[\"T1\",\"T2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // 同键同参重放返回首次完整响应
        mvc.perform(post("/api/incidents/{k}/branch-groups", "INC-630")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + createKey + "\",\"groupKey\":\"BG-1\","
                                + "\"taskKeys\":[\"T1\",\"T2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupKey").value("BG-1"))
                .andExpect(jsonPath("$.status").value("OPEN"));
        // 同键异参 → 409（成员集合按无序比对，不同成员构成异参）
        mvc.perform(post("/api/incidents/{k}/branch-groups", "INC-630")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + createKey + "\",\"groupKey\":\"BG-1\","
                                + "\"taskKeys\":[\"T1\",\"T2\",\"T3\"]}"))
                .andExpect(status().isConflict());

        // 选择同键同参重放一致，同键异参 409
        String chooseKey = key();
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-630", "BG-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + chooseKey + "\",\"taskKey\":\"T1\","
                                + "\"expectedGroupVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHOSEN"));
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-630", "BG-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + chooseKey + "\",\"taskKey\":\"T1\","
                                + "\"expectedGroupVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHOSEN"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.chosenTaskKey").value("T1"));
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-630", "BG-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + chooseKey + "\",\"taskKey\":\"T2\","
                                + "\"expectedGroupVersion\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void branchGroupChooseBlockerGate() throws Exception {
        report("INC-640");
        report("INC-641");
        takeover("INC-640", "alice");
        takeover("INC-641", "bob");
        mvc.perform(post("/api/incidents/{k}/tasks", "INC-640")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T1\","
                                + "\"groupCode\":\"G\",\"title\":\"隔离\","
                                + "\"blockerIncidentKeys\":[\"INC-641\"]}"))
                .andExpect(status().isOk());
        createTask("INC-640", "alice", "T2", "迁移");
        createGroup("INC-640", "alice", "BG-1", "[\"T1\",\"T2\"]");

        // 选中成员阻塞未解除 → 409 且 details 返回未解除事件列表
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-640", "BG-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T1\","
                                + "\"expectedGroupVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("INC-641"));

        // 阻塞解除后选择成功；改选无阻塞的 T2 不受门禁影响
        changeStatus("INC-641", "bob", "CONTAINED");
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-640", "BG-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T1\","
                                + "\"expectedGroupVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chosenTaskKey").value("T1"));
    }

    @Test
    void branchGroupTransferCoArbitration() throws Exception {
        report("INC-650");
        takeover("INC-650", "alice");
        createTask("INC-650", "alice", "T1", "隔离");
        createTask("INC-650", "alice", "T2", "迁移");
        createGroup("INC-650", "alice", "BG-1", "[\"T1\",\"T2\"]");

        // 指挥交接后，旧指挥人失去选择权限，新指挥人接管选择
        mvc.perform(post("/api/incidents/{k}/transfers", "INC-650")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"toCommander\":\"bob\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/transfers/accept", "INC-650")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-650", "BG-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T1\","
                                + "\"expectedGroupVersion\":1}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-650", "BG-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T2\","
                                + "\"expectedGroupVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chosenTaskKey").value("T2"))
                .andExpect(jsonPath("$.chosenBy").value("bob"));
    }
}

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
 * 互斥分支组 HTTP 层测试：覆盖题干主场景（建组{隔离T1,迁移T2}、选择 T1 后
 * 查询看到 T1 DONE / T2 CANCELLED、再以新键选 T2 被拒且不改历史）、
 * 建组 422 校验（终态成员、成员已属别组、事件非 COMMANDING）及成员数边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IncidentBranchGroupApiTest {

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

    private void createTask(String incidentKey, String actor, String taskKey, String title,
                            String blockersJson) throws Exception {
        mvc.perform(post("/api/incidents/{k}/tasks", incidentKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"" + taskKey
                                + "\",\"groupCode\":\"OP\",\"title\":\"" + title + "\","
                                + "\"blockerIncidentKeys\":" + blockersJson + "}"))
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

    @Test
    void chooseBranchFlow_observableOutcome() throws Exception {
        report("INC-600");
        report("INC-601");
        takeover("INC-600", "alice");
        takeover("INC-601", "bob");
        createTask("INC-600", "alice", "T1", "隔离", "[\"INC-601\"]");
        createTask("INC-600", "alice", "T2", "迁移", "[]");

        // 建组 {隔离T1, 迁移T2}：版本初始 1，成员冻结
        mvc.perform(post("/api/incidents/{k}/branch-groups", "INC-600")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"groupKey\":\"BG-1\","
                                + "\"memberTaskKeys\":[\"T1\",\"T2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupKey").value("BG-1"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.chosenTaskKey").doesNotExist());

        // 选中成员阻塞未解除 → 409 且返回未解除事件列表
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-600", "BG-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T1\","
                                + "\"expectedGroupVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("INC-601"));

        // 阻塞解除后选择 T1 成功：T1 DONE、T2 CANCELLED，组 CHOSEN 且版本加一
        changeStatus("INC-601", "bob", "CONTAINED");
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

        // 查询一次看到 T1 为 DONE、T2 为 CANCELLED；事件状态不自动改变
        mvc.perform(get("/api/incidents/{k}/tasks", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].taskKey").value("T1"))
                .andExpect(jsonPath("$.tasks[0].status").value("DONE"))
                .andExpect(jsonPath("$.tasks[0].doneBy").value("alice"))
                .andExpect(jsonPath("$.tasks[1].taskKey").value("T2"))
                .andExpect(jsonPath("$.tasks[1].status").value("CANCELLED"))
                .andExpect(jsonPath("$.tasks[1].cancelledBy").value("alice"));
        mvc.perform(get("/api/incidents/{k}", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMANDING"));

        // 再以新键选择 T2 → 409，且历史不变（T1 仍 DONE、T2 仍 CANCELLED）
        mvc.perform(post("/api/incidents/{k}/branch-groups/{g}/choose", "INC-600", "BG-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"taskKey\":\"T2\","
                                + "\"expectedGroupVersion\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mvc.perform(get("/api/incidents/{k}/branch-groups", "INC-600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(1))
                .andExpect(jsonPath("$.groups[0].status").value("CHOSEN"))
                .andExpect(jsonPath("$.groups[0].version").value(2))
                .andExpect(jsonPath("$.groups[0].chosenTaskKey").value("T1"))
                .andExpect(jsonPath("$.groups[0].members[0].taskStatus").value("DONE"))
                .andExpect(jsonPath("$.groups[0].members[1].taskStatus").value("CANCELLED"));
    }

    @Test
    void createGroupValidation_unprocessable() throws Exception {
        report("INC-610");
        takeover("INC-610", "alice");
        createTask("INC-610", "alice", "T1", "t1", "[]");
        createTask("INC-610", "alice", "T2", "t2", "[]");
        createTask("INC-610", "alice", "T3", "t3", "[]");

        // 成员数越界 → 400
        mvc.perform(post("/api/incidents/{k}/branch-groups", "INC-610")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"groupKey\":\"BG-X\","
                                + "\"memberTaskKeys\":[\"T1\"]}"))
                .andExpect(status().isBadRequest());

        // 取消 T3 后混入终态成员 → 422，整次不建组
        mvc.perform(post("/api/incidents/{k}/tasks/{t}/cancel", "INC-610", "T3")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/branch-groups", "INC-610")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"groupKey\":\"BG-T\","
                                + "\"memberTaskKeys\":[\"T1\",\"T3\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"));

        // 正常建组 BG-A {T1,T2}；再以 T2 建第二组 → 422（成员已属另一组）
        mvc.perform(post("/api/incidents/{k}/branch-groups", "INC-610")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"groupKey\":\"BG-A\","
                                + "\"memberTaskKeys\":[\"T1\",\"T2\"]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{k}/branch-groups", "INC-610")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"groupKey\":\"BG-B\","
                                + "\"memberTaskKeys\":[\"T2\",\"T3\"]}"))
                .andExpect(status().isUnprocessableEntity());

        // 整次不建组：仅 BG-A 存在
        mvc.perform(get("/api/incidents/{k}/branch-groups", "INC-610"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(1))
                .andExpect(jsonPath("$.groups[0].groupKey").value("BG-A"));

        // 事件非 COMMANDING → 422（CONTAINED 后建组）
        report("INC-611");
        takeover("INC-611", "alice");
        createTask("INC-611", "alice", "T1", "t1", "[]");
        createTask("INC-611", "alice", "T2", "t2", "[]");
        changeStatus("INC-611", "alice", "CONTAINED");
        mvc.perform(post("/api/incidents/{k}/branch-groups", "INC-611")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"groupKey\":\"BG-C\","
                                + "\"memberTaskKeys\":[\"T1\",\"T2\"]}"))
                .andExpect(status().isUnprocessableEntity());

        // REPORTED（未接管）建组：非当前指挥人 → 409（权限先于业务）
        report("INC-612");
        mvc.perform(post("/api/incidents/{k}/branch-groups", "INC-612")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\",\"groupKey\":\"BG-D\","
                                + "\"memberTaskKeys\":[\"T1\",\"T2\"]}"))
                .andExpect(status().isConflict());
    }
}

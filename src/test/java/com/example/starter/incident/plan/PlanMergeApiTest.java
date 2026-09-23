package com.example.starter.incident.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 方案三方合并 HTTP 层集成测试（真实 H2 库）：覆盖合并主流程、requestId 幂等、
 * 分支/活动版本并发校验、图与执行事实校验、跨事件权限规则及任务执行门禁。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlanMergeApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper om;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM plan_merges");
        jdbc.update("DELETE FROM plan_edges");
        jdbc.update("DELETE FROM plan_tasks");
        jdbc.update("DELETE FROM plan_versions");
        jdbc.update("DELETE FROM plans");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "K-" + UUID.randomUUID();
    }

    // ---------- 请求构造辅助 ----------

    private Map<String, Object> task(String taskId, String incidentKey, String title,
                                     String assignee, String status) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("taskId", taskId);
        t.put("incidentKey", incidentKey);
        t.put("groupCode", "G1");
        t.put("title", title);
        t.put("assignee", assignee);
        t.put("status", status);
        return t;
    }

    private Map<String, Object> edge(String from, String to) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("fromTaskId", from);
        e.put("toTaskId", to);
        return e;
    }

    private Map<String, Object> resolution(String conflictKey, String choice) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("conflictKey", conflictKey);
        r.put("choice", choice);
        return r;
    }

    private Map<String, Object> mergeBody(String requestId, String mergeKey, int base,
                                          int left, int right, int leftExpected, int rightExpected,
                                          List<Map<String, Object>> resolutions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("mergeKey", mergeKey);
        body.put("baseVersion", base);
        body.put("leftVersion", left);
        body.put("rightVersion", right);
        body.put("leftExpectedVersion", leftExpected);
        body.put("rightExpectedVersion", rightExpected);
        body.put("resolutions", resolutions);
        return body;
    }

    // ---------- HTTP 辅助 ----------

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

    private void createPlan(String planKey, String actor) throws Exception {
        mvc.perform(post("/api/plans")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planKey\":\"" + planKey + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activeVersion").value(1));
    }

    private int createRevision(String planKey, String actor, int baseVersion) throws Exception {
        MvcResult result = mvc.perform(post("/api/plans/{k}/revisions", planKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseVersion\":" + baseVersion + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.expectedVersion").value(1))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString()).get("versionNo").asInt();
    }

    private void updateRevision(String planKey, int versionNo, String actor, int expected,
                                List<Map<String, Object>> tasks,
                                List<Map<String, Object>> edges) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedVersion", expected);
        body.put("tasks", tasks);
        body.put("edges", edges);
        mvc.perform(put("/api/plans/{k}/revisions/{v}", planKey, versionNo)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedVersion").value(expected + 1));
    }

    private MvcResult merge(String planKey, String actor, Map<String, Object> body)
            throws Exception {
        return mvc.perform(post("/api/plans/{k}/merges", planKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode mergeOk(String planKey, String actor, Map<String, Object> body)
            throws Exception {
        MvcResult result = mvc.perform(post("/api/plans/{k}/merges", planKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString());
    }

    private void startTask(String planKey, String taskId, String actor) throws Exception {
        mvc.perform(post("/api/plans/{k}/tasks/{t}/start", planKey, taskId)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    private void completeTask(String planKey, String taskId, String actor) throws Exception {
        mvc.perform(post("/api/plans/{k}/tasks/{t}/complete", planKey, taskId)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedBy").value(actor));
    }

    // ---------- 测试 ----------

    @Test
    void mergeHappyPathPublishesSingleAtomicVersion() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        createPlan("PLAN-1", "alice");
        int left = createRevision("PLAN-1", "alice", 1);
        int right = createRevision("PLAN-1", "alice", 1);
        // 左侧：T-1（左标题）+ T-2 + 边 T-1→T-2；右侧：T-1（右标题）
        updateRevision("PLAN-1", left, "alice", 1,
                List.of(task("T-1", "INC-A", "左标题", "alice", "PENDING"),
                        task("T-2", "INC-B", "任务二", "alice", "PENDING")),
                List.of(edge("T-1", "T-2")));
        updateRevision("PLAN-1", right, "alice", 1,
                List.of(task("T-1", "INC-A", "右标题", "alice", "PENDING")),
                List.of());

        // 差异查询：task:T-1 双侧分歧冲突；T-2 与边为左侧单改自动采用；稳定排序
        mvc.perform(get("/api/plans/{k}/diff", "PLAN-1")
                        .param("baseVersion", "1")
                        .param("leftVersion", String.valueOf(left))
                        .param("rightVersion", String.valueOf(right)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskConflicts.length()").value(1))
                .andExpect(jsonPath("$.taskConflicts[0].conflictKey").value("task:T-1"))
                .andExpect(jsonPath("$.taskConflicts[0].type").value("TASK_FIELD"))
                .andExpect(jsonPath("$.taskConflicts[0].left.title").value("左标题"))
                .andExpect(jsonPath("$.taskConflicts[0].right.title").value("右标题"))
                .andExpect(jsonPath("$.items[0].itemKey").value("edge:T-1->T-2"))
                .andExpect(jsonPath("$.items[0].change").value("LEFT"))
                .andExpect(jsonPath("$.items[1].itemKey").value("task:T-1"))
                .andExpect(jsonPath("$.items[1].change").value("CONFLICT"))
                .andExpect(jsonPath("$.items[2].itemKey").value("task:T-2"))
                .andExpect(jsonPath("$.items[2].change").value("LEFT"));

        // MANUAL 解决 T-1，合并发布（两侧草稿各更新一次，expectedVersion 均为 2）
        Map<String, Object> manual = resolution("task:T-1", "MANUAL");
        manual.put("manualTask", task("T-1", "INC-A", "手工标题", "carol", "PENDING"));
        JsonNode evidence = mergeOk("PLAN-1", "alice",
                mergeBody("REQ-1", "MG-1", 1, left, right, 2, 2, List.of(manual)));

        assertThat(evidence.get("resultVersion").asInt()).isEqualTo(4);
        assertThat(evidence.get("mergeKey").asText()).isEqualTo("MG-1");
        // 最终任务集：T-1 手工内容、T-2 左侧内容；边集：T-1→T-2
        assertThat(evidence.get("tasks")).hasSize(2);
        assertThat(evidence.get("tasks").get(0).get("taskId").asText()).isEqualTo("T-1");
        assertThat(evidence.get("tasks").get(0).get("title").asText()).isEqualTo("手工标题");
        assertThat(evidence.get("tasks").get(0).get("assignee").asText()).isEqualTo("carol");
        assertThat(evidence.get("tasks").get(1).get("taskId").asText()).isEqualTo("T-2");
        assertThat(evidence.get("edges")).hasSize(1);
        assertThat(evidence.get("edges").get(0).get("fromTaskId").asText()).isEqualTo("T-1");
        // 冻结的冲突解决
        assertThat(evidence.get("resolutions")).hasSize(1);
        assertThat(evidence.get("resolutions").get(0).get("choice").asText()).isEqualTo("MANUAL");
        assertThat(evidence.get("diff").get("taskConflicts").get(0).get("conflictKey").asText())
                .isEqualTo("task:T-1");

        // 活动版本前进到 4；原分支标记 MERGED 不可变；新版本 PUBLISHED 且 base 为 1
        mvc.perform(get("/api/plans/{k}", "PLAN-1"))
                .andExpect(jsonPath("$.activeVersion").value(4));
        mvc.perform(get("/api/plans/{k}/versions/{v}", "PLAN-1", left))
                .andExpect(jsonPath("$.status").value("MERGED"));
        mvc.perform(get("/api/plans/{k}/versions/{v}", "PLAN-1", right))
                .andExpect(jsonPath("$.status").value("MERGED"));
        mvc.perform(get("/api/plans/{k}/versions/{v}", "PLAN-1", 4))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.baseVersion").value(1))
                .andExpect(jsonPath("$.tasks.length()").value(2));
        // MERGED 草稿不可再修改
        mvc.perform(put("/api/plans/{k}/revisions/{v}", "PLAN-1", left)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"tasks\":[],\"edges\":[]}"))
                .andExpect(status().isConflict());

        // 证据查询只读且与发布响应一致
        MvcResult queried = mvc.perform(get("/api/plans/{k}/merges/{m}", "PLAN-1", "MG-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultVersion").value(4))
                .andExpect(jsonPath("$.tasks[0].title").value("手工标题"))
                .andReturn();
        assertThat(om.readTree(queried.getResponse().getContentAsString())).isEqualTo(evidence);
    }

    @Test
    void mergeRequestIdReplayReorderEquivalentAndConflict() throws Exception {
        commanding("INC-A", "alice");
        createPlan("PLAN-2", "alice");
        int left = createRevision("PLAN-2", "alice", 1);
        int right = createRevision("PLAN-2", "alice", 1);
        // 两个任务均双侧分歧 → 两个冲突
        updateRevision("PLAN-2", left, "alice", 1,
                List.of(task("T-1", "INC-A", "左一", "alice", "PENDING"),
                        task("T-2", "INC-A", "左二", "alice", "PENDING")),
                List.of());
        updateRevision("PLAN-2", right, "alice", 1,
                List.of(task("T-1", "INC-A", "右一", "alice", "PENDING"),
                        task("T-2", "INC-A", "右二", "alice", "PENDING")),
                List.of());

        List<Map<String, Object>> resolutions = List.of(
                resolution("task:T-1", "LEFT"), resolution("task:T-2", "RIGHT"));
        JsonNode first = mergeOk("PLAN-2", "alice",
                mergeBody("REQ-2", "MG-2", 1, left, right, 2, 2, resolutions));
        assertThat(first.get("tasks").get(0).get("title").asText()).isEqualTo("左一");
        assertThat(first.get("tasks").get(1).get("title").asText()).isEqualTo("右二");

        // 同参重放（冲突项换序等价）：返回首次快照
        List<Map<String, Object>> reordered = List.of(
                resolution("task:T-2", "RIGHT"), resolution("task:T-1", "LEFT"));
        JsonNode replay = mergeOk("PLAN-2", "alice",
                mergeBody("REQ-2", "MG-2", 1, left, right, 2, 2, reordered));
        assertThat(replay).isEqualTo(first);
        // 只创建了一个合并版本
        Integer versionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_versions", Integer.class);
        assertThat(versionCount).isEqualTo(4);

        // 同 requestId 异参 → 409
        mvc.perform(post("/api/plans/{k}/merges", "PLAN-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(mergeBody("REQ-2", "MG-2", 1, left, right,
                                2, 2, List.of(resolution("task:T-1", "RIGHT"),
                                        resolution("task:T-2", "RIGHT"))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 同 mergeKey 不同 requestId → 409
        mvc.perform(post("/api/plans/{k}/merges", "PLAN-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(mergeBody("REQ-2B", "MG-2", 1, left, right,
                                2, 2, resolutions))))
                .andExpect(status().isConflict());
    }

    @Test
    void mergeFailureDoesNotConsumeRequestId() throws Exception {
        commanding("INC-A", "alice");
        createPlan("PLAN-3", "alice");
        int left = createRevision("PLAN-3", "alice", 1);
        int right = createRevision("PLAN-3", "alice", 1);
        updateRevision("PLAN-3", left, "alice", 1,
                List.of(task("T-1", "INC-A", "t", "alice", "PENDING")), List.of());

        // 错误的 rightExpectedVersion → 409，失败不占键
        mvc.perform(post("/api/plans/{k}/merges", "PLAN-3")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(mergeBody("REQ-3", "MG-3", 1, left, right,
                                2, 99, List.of()))))
                .andExpect(status().isConflict());
        // 修正参数后同 requestId 成功
        mergeOk("PLAN-3", "alice", mergeBody("REQ-3", "MG-3", 1, left, right, 2, 1, List.of()));
        mvc.perform(get("/api/plans/{k}", "PLAN-3"))
                .andExpect(jsonPath("$.activeVersion").value(4));
    }

    @Test
    void mergeRejectsStaleBranchAndAdvancedActiveVersion() throws Exception {
        commanding("INC-A", "alice");
        createPlan("PLAN-4", "alice");
        int left = createRevision("PLAN-4", "alice", 1);
        int right = createRevision("PLAN-4", "alice", 1);
        updateRevision("PLAN-4", left, "alice", 1,
                List.of(task("T-1", "INC-A", "t", "alice", "PENDING")), List.of());
        // 左侧草稿再次变更 → expectedVersion 变为 2
        updateRevision("PLAN-4", left, "alice", 2,
                List.of(task("T-1", "INC-A", "t2", "alice", "PENDING")), List.of());

        // 提交旧的 leftExpectedVersion → 409 分支已变化（左侧当前为 3）
        mvc.perform(post("/api/plans/{k}/merges", "PLAN-4")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(mergeBody("REQ-4A", "MG-4A", 1, left, right,
                                2, 1, List.of()))))
                .andExpect(status().isConflict());

        // 正确序号合并成功
        mergeOk("PLAN-4", "alice", mergeBody("REQ-4B", "MG-4B", 1, left, right, 3, 1, List.of()));

        // 活动版本已前进：再以 base=1 合并 → 409
        int left2 = createRevision("PLAN-4", "alice", 4);
        int right2 = createRevision("PLAN-4", "alice", 4);
        mvc.perform(post("/api/plans/{k}/merges", "PLAN-4")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(mergeBody("REQ-4C", "MG-4C", 1, left2,
                                right2, 1, 1, List.of()))))
                .andExpect(status().isConflict());
        // 已 MERGED 的草稿不能再次合并
        mvc.perform(post("/api/plans/{k}/merges", "PLAN-4")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(mergeBody("REQ-4D", "MG-4D", 4, left, right2,
                                2, 1, List.of()))))
                .andExpect(status().isConflict());
    }

    @Test
    void mergeRejectsCycleAndDanglingEdge() throws Exception {
        commanding("INC-A", "alice");
        createPlan("PLAN-5", "alice");
        // 第一阶段：建立 A→B→C 链
        int left = createRevision("PLAN-5", "alice", 1);
        int right = createRevision("PLAN-5", "alice", 1);
        List<Map<String, Object>> tasks = List.of(
                task("A", "INC-A", "a", "alice", "PENDING"),
                task("B", "INC-A", "b", "alice", "PENDING"),
                task("C", "INC-A", "c", "alice", "PENDING"));
        updateRevision("PLAN-5", left, "alice", 1, tasks,
                List.of(edge("A", "B"), edge("B", "C")));
        updateRevision("PLAN-5", right, "alice", 1, tasks,
                List.of(edge("A", "B"), edge("B", "C")));
        mergeOk("PLAN-5", "alice", mergeBody("REQ-5A", "MG-5A", 1, left, right, 2, 2, List.of()));

        // 第二阶段：左侧新增 C→A（单侧改动自动采用）→ 合并后成环 → 422
        int left2 = createRevision("PLAN-5", "alice", 4);
        int right2 = createRevision("PLAN-5", "alice", 4);
        updateRevision("PLAN-5", left2, "alice", 1, tasks,
                List.of(edge("A", "B"), edge("B", "C"), edge("C", "A")));
        mvc.perform(post("/api/plans/{k}/merges", "PLAN-5")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(mergeBody("REQ-5B", "MG-5B", 4, left2,
                                right2, 2, 1, List.of()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));
        // 未生成合并版本，活动版本仍为 4
        mvc.perform(get("/api/plans/{k}", "PLAN-5"))
                .andExpect(jsonPath("$.activeVersion").value(4));

        // 反向边冲突 MANUAL 给出引用不存在任务的边 → 422
        int left3 = createRevision("PLAN-5", "alice", 4);
        int right3 = createRevision("PLAN-5", "alice", 4);
        updateRevision("PLAN-5", left3, "alice", 1, tasks,
                List.of(edge("A", "B"), edge("B", "C"), edge("A", "C")));
        updateRevision("PLAN-5", right3, "alice", 1, tasks,
                List.of(edge("A", "B"), edge("B", "C"), edge("C", "A")));
        Map<String, Object> dangling = resolution("edge:A->C", "MANUAL");
        dangling.put("manualEdge", edge("A", "T-404"));
        mvc.perform(post("/api/plans/{k}/merges", "PLAN-5")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(mergeBody("REQ-5C", "MG-5C", 4, left3,
                                right3, 2, 2, List.of(dangling)))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void mergeRejectsOppositeEdgeWithoutResolutionAndWithResolution() throws Exception {
        commanding("INC-A", "alice");
        createPlan("PLAN-6", "alice");
        int left = createRevision("PLAN-6", "alice", 1);
        int right = createRevision("PLAN-6", "alice", 1);
        List<Map<String, Object>> tasks = List.of(
                task("A", "INC-A", "a", "alice", "PENDING"),
                task("B", "INC-A", "b", "alice", "PENDING"));
        updateRevision("PLAN-6", left, "alice", 1, tasks, List.of(edge("A", "B")));
        updateRevision("PLAN-6", right, "alice", 1, tasks, List.of(edge("B", "A")));

        // 差异查询暴露 EDGE_OPPOSITE 冲突
        mvc.perform(get("/api/plans/{k}/diff", "PLAN-6")
                        .param("baseVersion", "1")
                        .param("leftVersion", String.valueOf(left))
                        .param("rightVersion", String.valueOf(right)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edgeConflicts.length()").value(1))
                .andExpect(jsonPath("$.edgeConflicts[0].type").value("EDGE_OPPOSITE"))
                .andExpect(jsonPath("$.edgeConflicts[0].leftEdge.fromTaskId").value("A"))
                .andExpect(jsonPath("$.edgeConflicts[0].rightEdge.fromTaskId").value("B"));

        // 遗漏解决 → 400
        mvc.perform(post("/api/plans/{k}/merges", "PLAN-6")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(mergeBody("REQ-6A", "MG-6A", 1, left, right,
                                2, 2, List.of()))))
                .andExpect(status().isBadRequest());

        // RIGHT 解决：最终边为 B→A
        JsonNode evidence = mergeOk("PLAN-6", "alice",
                mergeBody("REQ-6B", "MG-6B", 1, left, right, 2, 2,
                        List.of(resolution("edge:A->B", "RIGHT"))));
        assertThat(evidence.get("edges")).hasSize(1);
        assertThat(evidence.get("edges").get(0).get("fromTaskId").asText()).isEqualTo("B");
        assertThat(evidence.get("edges").get(0).get("toTaskId").asText()).isEqualTo("A");
    }

    @Test
    void mergeRejectsCompletedRollbackAndInProgressViolation() throws Exception {
        commanding("INC-A", "alice");
        createPlan("PLAN-7", "alice");
        // 第一阶段：发布 T-1（PENDING）
        int left = createRevision("PLAN-7", "alice", 1);
        int right = createRevision("PLAN-7", "alice", 1);
        updateRevision("PLAN-7", left, "alice", 1,
                List.of(task("T-1", "INC-A", "t", "alice", "PENDING")), List.of());
        mergeOk("PLAN-7", "alice", mergeBody("REQ-7A", "MG-7A", 1, left, right, 2, 1, List.of()));

        // 草稿先于执行创建（仍持有 PENDING 快照）
        int left2 = createRevision("PLAN-7", "alice", 4);
        int right2 = createRevision("PLAN-7", "alice", 4);
        // 活动版本上 T-1 执行完成
        startTask("PLAN-7", "T-1", "alice");
        completeTask("PLAN-7", "T-1", "alice");
        // 合并旧草稿会回退完成事实 → 422
        mvc.perform(post("/api/plans/{k}/merges", "PLAN-7")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(mergeBody("REQ-7B", "MG-7B", 4, left2,
                                right2, 1, 1, List.of()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("不可回退")));

        // 正在执行任务不得更换负责人（草稿在开始执行后创建，携带 IN_PROGRESS 快照）
        createPlan("PLAN-8", "alice");
        int l8 = createRevision("PLAN-8", "alice", 1);
        int r8 = createRevision("PLAN-8", "alice", 1);
        updateRevision("PLAN-8", l8, "alice", 1,
                List.of(task("T-1", "INC-A", "t", "alice", "PENDING")), List.of());
        mergeOk("PLAN-8", "alice", mergeBody("REQ-8A", "MG-8A", 1, l8, r8, 2, 1, List.of()));
        startTask("PLAN-8", "T-1", "alice");
        int l8b = createRevision("PLAN-8", "alice", 4);
        int r8b = createRevision("PLAN-8", "alice", 4);
        Map<String, Object> reassigned = task("T-1", "INC-A", "t", "bob", "IN_PROGRESS");
        updateRevision("PLAN-8", l8b, "alice", 1, List.of(reassigned), List.of());
        mvc.perform(post("/api/plans/{k}/merges", "PLAN-8")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(mergeBody("REQ-8B", "MG-8B", 4, l8b, r8b,
                                2, 1, List.of()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("不得更换负责人")));

        // 正在执行任务不得新增未满足前置依赖（草稿在开始执行后创建）
        createPlan("PLAN-9", "alice");
        int l9 = createRevision("PLAN-9", "alice", 1);
        int r9 = createRevision("PLAN-9", "alice", 1);
        updateRevision("PLAN-9", l9, "alice", 1,
                List.of(task("T-1", "INC-A", "t", "alice", "PENDING")), List.of());
        mergeOk("PLAN-9", "alice", mergeBody("REQ-9A", "MG-9A", 1, l9, r9, 2, 1, List.of()));
        startTask("PLAN-9", "T-1", "alice");
        int l9b = createRevision("PLAN-9", "alice", 4);
        int r9b = createRevision("PLAN-9", "alice", 4);
        Map<String, Object> inProgress = task("T-1", "INC-A", "t", "alice", "IN_PROGRESS");
        updateRevision("PLAN-9", l9b, "alice", 1,
                List.of(inProgress, task("T-2", "INC-A", "pre", "alice", "PENDING")),
                List.of(edge("T-2", "T-1")));
        mvc.perform(post("/api/plans/{k}/merges", "PLAN-9")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(mergeBody("REQ-9B", "MG-9B", 4, l9b, r9b,
                                2, 1, List.of()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("未满足前置依赖")));
    }

    @Test
    void mergeEnforcesCrossIncidentPermissionAndStatus() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        createPlan("PLAN-10", "alice");
        int left = createRevision("PLAN-10", "alice", 1);
        int right = createRevision("PLAN-10", "alice", 1);
        updateRevision("PLAN-10", left, "alice", 1,
                List.of(task("T-1", "INC-A", "a", "alice", "PENDING"),
                        task("T-2", "INC-B", "b", "bob", "PENDING")),
                List.of(edge("T-1", "T-2")));

        // alice 不是 INC-B 的当前指挥人 → 409
        mvc.perform(post("/api/plans/{k}/merges", "PLAN-10")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(mergeBody("REQ-10A", "MG-10A", 1, left,
                                right, 2, 1, List.of()))))
                .andExpect(status().isConflict());
        // bob 不是 INC-A 的当前指挥人 → 409
        mvc.perform(post("/api/plans/{k}/merges", "PLAN-10")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(mergeBody("REQ-10B", "MG-10B", 1, left,
                                right, 2, 1, List.of()))))
                .andExpect(status().isConflict());

        // 引用不存在事件 → 404
        createPlan("PLAN-11", "alice");
        int l11 = createRevision("PLAN-11", "alice", 1);
        int r11 = createRevision("PLAN-11", "alice", 1);
        updateRevision("PLAN-11", l11, "alice", 1,
                List.of(task("T-1", "INC-404", "a", "alice", "PENDING")), List.of());
        mvc.perform(post("/api/plans/{k}/merges", "PLAN-11")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(mergeBody("REQ-11A", "MG-11A", 1, l11, r11,
                                2, 1, List.of()))))
                .andExpect(status().isNotFound());

        // 事件已关闭 → 422
        commanding("INC-C", "alice");
        for (String target : List.of("CONTAINED", "RESOLVED", "CLOSED")) {
            mvc.perform(post("/api/incidents/{k}/status", "INC-C")
                            .header("X-Actor-Id", "alice")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commandKey\":\"" + key() + "\",\"targetStatus\":\""
                                    + target + "\"}"))
                    .andExpect(status().isOk());
        }
        createPlan("PLAN-12", "alice");
        int l12 = createRevision("PLAN-12", "alice", 1);
        int r12 = createRevision("PLAN-12", "alice", 1);
        updateRevision("PLAN-12", l12, "alice", 1,
                List.of(task("T-1", "INC-C", "a", "alice", "PENDING")), List.of());
        mvc.perform(post("/api/plans/{k}/merges", "PLAN-12")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(mergeBody("REQ-12A", "MG-12A", 1, l12, r12,
                                2, 1, List.of()))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void planTaskExecutionGateAndIdempotency() throws Exception {
        commanding("INC-A", "alice");
        createPlan("PLAN-13", "alice");
        int left = createRevision("PLAN-13", "alice", 1);
        int right = createRevision("PLAN-13", "alice", 1);
        updateRevision("PLAN-13", left, "alice", 1,
                List.of(task("T-1", "INC-A", "一", "alice", "PENDING"),
                        task("T-2", "INC-A", "二", "alice", "PENDING")),
                List.of(edge("T-1", "T-2")));
        mergeOk("PLAN-13", "alice",
                mergeBody("REQ-13A", "MG-13A", 1, left, right, 2, 1, List.of()));

        // 前置未完成：T-2 不可开始 → 409 且 details 为未满足前置列表
        mvc.perform(post("/api/plans/{k}/tasks/{t}/start", "PLAN-13", "T-2")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("T-1"));
        // 非指挥人 → 409
        mvc.perform(post("/api/plans/{k}/tasks/{t}/start", "PLAN-13", "T-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict());

        // commandKey 幂等：同键重放首次响应
        String commandKey = key();
        MvcResult first = mvc.perform(post("/api/plans/{k}/tasks/{t}/start", "PLAN-13", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andReturn();
        mvc.perform(post("/api/plans/{k}/tasks/{t}/start", "PLAN-13", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        // 新 commandKey 重复开始 → 409（状态已非 PENDING）
        mvc.perform(post("/api/plans/{k}/tasks/{t}/start", "PLAN-13", "T-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isConflict());
        assertThat(first.getResponse().getContentAsString()).contains("IN_PROGRESS");

        // 完成 T-1 后 T-2 可开始
        completeTask("PLAN-13", "T-1", "alice");
        startTask("PLAN-13", "T-2", "alice");
        // 任务不存在 → 404
        mvc.perform(post("/api/plans/{k}/tasks/{t}/start", "PLAN-13", "T-404")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + key() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void planAndRevisionGuards() throws Exception {
        commanding("INC-A", "alice");
        createPlan("PLAN-14", "alice");
        // planKey 重复 → 409
        mvc.perform(post("/api/plans")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planKey\":\"PLAN-14\"}"))
                .andExpect(status().isConflict());
        // 方案不存在 → 404
        mvc.perform(get("/api/plans/{k}", "PLAN-404"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/plans/{k}/versions/{v}", "PLAN-14", 99))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/plans/{k}/merges/{m}", "PLAN-14", "MG-404"))
                .andExpect(status().isNotFound());

        int draft = createRevision("PLAN-14", "alice", 1);
        // expectedVersion 不匹配 → 409
        mvc.perform(put("/api/plans/{k}/revisions/{v}", "PLAN-14", draft)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":9,\"tasks\":[],\"edges\":[]}"))
                .andExpect(status().isConflict());
        // taskId 重复 → 400
        Map<String, Object> dup = new LinkedHashMap<>();
        dup.put("expectedVersion", 1);
        dup.put("tasks", List.of(task("T-1", "INC-A", "a", "alice", "PENDING"),
                task("T-1", "INC-A", "b", "alice", "PENDING")));
        dup.put("edges", List.of());
        mvc.perform(put("/api/plans/{k}/revisions/{v}", "PLAN-14", draft)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(dup)))
                .andExpect(status().isBadRequest());
        // 边引用不存在任务 → 400
        Map<String, Object> badEdge = new LinkedHashMap<>();
        badEdge.put("expectedVersion", 1);
        badEdge.put("tasks", List.of(task("T-1", "INC-A", "a", "alice", "PENDING")));
        badEdge.put("edges", List.of(edge("T-1", "T-2")));
        mvc.perform(put("/api/plans/{k}/revisions/{v}", "PLAN-14", draft)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(badEdge)))
                .andExpect(status().isBadRequest());
        // COMPLETED 缺完成事实 → 400
        Map<String, Object> badCompleted = new LinkedHashMap<>();
        badCompleted.put("expectedVersion", 1);
        badCompleted.put("tasks", List.of(task("T-1", "INC-A", "a", "alice", "COMPLETED")));
        badCompleted.put("edges", List.of());
        mvc.perform(put("/api/plans/{k}/revisions/{v}", "PLAN-14", draft)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(badCompleted)))
                .andExpect(status().isBadRequest());
        // 非活动 base → 409（先做一次合并使活动版本前进）
        int left = createRevision("PLAN-14", "alice", 1);
        int right = createRevision("PLAN-14", "alice", 1);
        updateRevision("PLAN-14", left, "alice", 1,
                List.of(task("T-1", "INC-A", "a", "alice", "PENDING")), List.of());
        mergeOk("PLAN-14", "alice", mergeBody("REQ-14A", "MG-14A", 1, left, right, 2, 1, List.of()));
        mvc.perform(post("/api/plans/{k}/revisions", "PLAN-14")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseVersion\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void mergeWithDeleteModifyConflictAndExtraResolutionRejected() throws Exception {
        commanding("INC-A", "alice");
        createPlan("PLAN-15", "alice");
        // 第一阶段：发布 T-1、T-2
        int left = createRevision("PLAN-15", "alice", 1);
        int right = createRevision("PLAN-15", "alice", 1);
        updateRevision("PLAN-15", left, "alice", 1,
                List.of(task("T-1", "INC-A", "一", "alice", "PENDING"),
                        task("T-2", "INC-A", "二", "alice", "PENDING")),
                List.of());
        mergeOk("PLAN-15", "alice",
                mergeBody("REQ-15A", "MG-15A", 1, left, right, 2, 1, List.of()));

        // 第二阶段：左侧改 T-2，右侧删 T-2 → 删改并存冲突
        int left2 = createRevision("PLAN-15", "alice", 4);
        int right2 = createRevision("PLAN-15", "alice", 4);
        updateRevision("PLAN-15", left2, "alice", 1,
                List.of(task("T-1", "INC-A", "一", "alice", "PENDING"),
                        task("T-2", "INC-A", "二改", "alice", "PENDING")),
                List.of());
        updateRevision("PLAN-15", right2, "alice", 1,
                List.of(task("T-1", "INC-A", "一", "alice", "PENDING")),
                List.of());
        mvc.perform(get("/api/plans/{k}/diff", "PLAN-15")
                        .param("baseVersion", "4")
                        .param("leftVersion", String.valueOf(left2))
                        .param("rightVersion", String.valueOf(right2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskConflicts[0].type").value("TASK_DELETE_MODIFY"))
                .andExpect(jsonPath("$.taskConflicts[0].right").doesNotExist());

        // 多余解决 → 400
        mvc.perform(post("/api/plans/{k}/merges", "PLAN-15")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(mergeBody("REQ-15B", "MG-15B", 4, left2,
                                right2, 2, 2, List.of(resolution("task:T-2", "LEFT"),
                                        resolution("task:T-99", "LEFT"))))))
                .andExpect(status().isBadRequest());

        // RIGHT 解决（采用删除）：T-2 从最终任务集消失
        JsonNode evidence = mergeOk("PLAN-15", "alice",
                mergeBody("REQ-15C", "MG-15C", 4, left2, right2, 2, 2,
                        List.of(resolution("task:T-2", "RIGHT"))));
        assertThat(evidence.get("tasks")).hasSize(1);
        assertThat(evidence.get("tasks").get(0).get("taskId").asText()).isEqualTo("T-1");
    }
}

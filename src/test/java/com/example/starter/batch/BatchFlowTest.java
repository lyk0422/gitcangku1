package com.example.starter.batch;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批次隔离与放行端到端测试：主流程、400/404/409/422 分支、commandKey/testKey 幂等与并发边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchFlowTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM condition_item");
        jdbc.update("DELETE FROM conditional_release");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程 ----------

    @Test
    void happyFlow_quarantineToReleasedThenRecalled() throws Exception {
        String batchKey = "BK-HAPPY-" + unique();
        createBatch(batchKey, List.of("外观", "含量", "无菌"), 201);

        // 初始隔离，出现在可用列表
        assertEquals("QUARANTINED", currentStatus(batchKey));
        assertTrue(availableKeys().contains(batchKey));

        submitTest(batchKey, testReq("TK-1", "外观", "PASS", "inspect-a"), null, null, 201);
        assertEquals("QUARANTINED", currentStatus(batchKey));
        submitTest(batchKey, testReq("TK-2", "含量", "PASS", "inspect-b"), null, null, 201);
        assertEquals("QUARANTINED", currentStatus(batchKey));
        submitTest(batchKey, testReq("TK-3", "无菌", "PASS", "inspect-c"), null, null, 201);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));

        approve(batchKey, "qa-1", "QUALITY", "CK-AP-1", 201);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AP-2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 召回：立即离开可用列表，但历史完整保留
        recall(batchKey, "any-user", "客户投诉，疑似污染", "CK-RC-1", 201);
        assertEquals("RECALLED", currentStatus(batchKey));
        assertFalse(availableKeys().contains(batchKey));

        MvcResult historyResult = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode history = objectMapper.readTree(historyResult.getResponse().getContentAsString());
        assertEquals(3, history.path("tests").size());
        assertEquals(2, history.path("approvals").size());
        assertEquals("RECALLED", history.path("batch").path("status").asText());
        assertEquals("客户投诉，疑似污染", history.path("recall").path("reason").asText());
        assertEquals("RELEASED", history.path("approvals").get(1).path("batchStatus").asText());
    }

    // ---------- 失败分支 ----------

    @Test
    void anyFail_immediatelyRejected_andBlocksTestsApprovalsAndRecall() throws Exception {
        String batchKey = "BK-FAIL-" + unique();
        createBatch(batchKey, List.of("t1", "t2"), 201);
        submitTest(batchKey, testReq("TK-F", "t1", "FAIL", "insp-1"), null, null, 201);
        assertEquals("REJECTED", currentStatus(batchKey));

        // 已拒绝不能再检验、不能批准、不能召回（召回仅针对 RELEASED）
        submitTest(batchKey, testReq("TK-P", "t2", "PASS", "insp-2"), null, null, 409);
        approve(batchKey, "qa-9", "QUALITY", "CK-A", 409);
        recall(batchKey, "u", "r", "CK-R", 409);
        // REJECTED 批次仍在当前列表中（只有召回才从可用列表移除）
        assertTrue(availableKeys().contains(batchKey));
    }

    @Test
    void unknownTestItem_returns422() throws Exception {
        String batchKey = "BK-BADITEM-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        submitTest(batchKey, testReq("TK-X", "不存在的项目", "PASS", "i"), null, null, 422);
        assertEquals("QUARANTINED", currentStatus(batchKey));
    }

    @Test
    void approveBeforeAllTestsPassed_returns422() throws Exception {
        String batchKey = "BK-EARLY-" + unique();
        createBatch(batchKey, List.of("t1", "t2"), 201);
        submitTest(batchKey, testReq("TK-1", "t1", "PASS", "insp"), null, null, 201);
        approve(batchKey, "qa-1", "QUALITY", "CK-A", 422);
        assertEquals("QUARANTINED", currentStatus(batchKey));
    }

    @Test
    void approvalConstraints_duplicateRole_sameActor_inspectorRejected() throws Exception {
        String batchKey = "BK-CONSTRAINT-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        submitTest(batchKey, testReq("TK-1", "t1", "PASS", "inspector-1"), null, null, 201);

        // 检验人不能批准
        approve(batchKey, "inspector-1", "QUALITY", "CK-A0", 422);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));

        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 201);
        // 重复角色
        approve(batchKey, "ops-1", "QUALITY", "CK-A2", 409);
        // 同一人不能完成两种角色
        approve(batchKey, "qa-1", "OPERATIONS", "CK-A3", 409);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));

        approve(batchKey, "ops-1", "OPERATIONS", "CK-A4", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 召回后不能再次批准放行
        recall(batchKey, "u", "原因", "CK-R1", 201);
        approve(batchKey, "qa-2", "QUALITY", "CK-A5", 409);
    }

    @Test
    void recallOnNonReleasedBatch_returns409() throws Exception {
        String batchKey = "BK-RC-EARLY-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        recall(batchKey, "u", "提前召回", "CK-R", 409);
        assertEquals("QUARANTINED", currentStatus(batchKey));
    }

    // ---------- 400 / 404 ----------

    @Test
    void invalidBodiesAndHeaders_return400() throws Exception {
        // 必做项为空
        String emptyTests = "{\"commandKey\":\"c\",\"batchKey\":\"b\",\"productCode\":\"p\","
                + "\"batchNo\":\"n\",\"producedAt\":\"2026-01-01T00:00:00Z\",\"requiredTests\":[]}";
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(emptyTests))
                .andExpect(status().isBadRequest());

        // 必做项 6 个
        String sixTests = "{\"commandKey\":\"c\",\"batchKey\":\"b2\",\"productCode\":\"p\","
                + "\"batchNo\":\"n\",\"producedAt\":\"2026-01-01T00:00:00Z\","
                + "\"requiredTests\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}";
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(sixTests))
                .andExpect(status().isBadRequest());

        // 检验项重复
        String duplicate = createBody("BK-DUP-ITEM-" + unique(), List.of("a", "a"));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isBadRequest());

        // result 枚举非法
        String badResult = "{\"commandKey\":\"c\",\"testKey\":\"t\",\"testItem\":\"a\","
                + "\"result\":\"MAYBE\",\"inspector\":\"i\"}";
        mockMvc.perform(post("/api/batches/BK-X/tests").contentType(MediaType.APPLICATION_JSON).content(badResult))
                .andExpect(status().isBadRequest());

        // 缺 X-Actor-Id / 非法角色头
        String approveBody = "{\"commandKey\":\"c\"}";
        mockMvc.perform(post("/api/batches/BK-X/approvals")
                        .contentType(MediaType.APPLICATION_JSON).content(approveBody))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/batches/BK-X/approvals")
                        .header("X-Actor-Id", "qa").header("X-Approval-Role", "BOSS")
                        .contentType(MediaType.APPLICATION_JSON).content(approveBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownBatch_returns404() throws Exception {
        mockMvc.perform(post("/api/batches/NO-SUCH-BATCH/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testReq("t", "a", "PASS", "i")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/history"))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateBatchKey_returns409() throws Exception {
        String batchKey = "BK-DUP-" + unique();
        createBatch(batchKey, List.of("a"), 201);
        createBatch(batchKey, List.of("b"), 409);
    }

    // ---------- 幂等 ----------

    @Test
    void commandKey_sameParamsReplaysFirstResult_changedParamsConflicts() throws Exception {
        String batchKey = "BK-IDEM-" + unique();
        String body = createBody(batchKey, List.of("t1"));
        MvcResult first = mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());

        // 同 commandKey 改参（批号不同）→ 409
        String changed = objectMapper.writeValueAsString(new CreateCmd("CK-1", batchKey + "-X",
                "p", "n-changed", Instant.parse("2026-03-01T00:00:00Z"), List.of("t1")));
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());
    }

    @Test
    void testKey_sameContentReplaysOriginal_differentContentConflicts() throws Exception {
        String batchKey = "BK-TK-" + unique();
        createBatch(batchKey, List.of("t1", "t2"), 201);

        String req1 = testReq("TK-1", "t1", "PASS", "insp");
        MvcResult created = mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(req1))
                .andExpect(status().isCreated()).andReturn();
        assertEquals("QUARANTINED", objectMapper
                .readTree(created.getResponse().getContentAsString()).path("batchStatus").asText());
        // 第二项 PASS 后批次进入 PENDING_RELEASE
        submitTest(batchKey, testReq("TK-2", "t2", "PASS", "insp"), null, null, 201);
        // 不同 commandKey、同 testKey 同内容 → 返回原结果，状态仍为首次提交时的 QUARANTINED 快照
        String replay = testReq("TK-1", "t1", "PASS", "insp");
        MvcResult replayResult = mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(replay))
                .andExpect(status().isOk()).andReturn();
        assertEquals("QUARANTINED", objectMapper
                .readTree(replayResult.getResponse().getContentAsString()).path("batchStatus").asText());
        // 同 testKey 不同结论 → 409
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testReq("TK-1", "t1", "FAIL", "insp")))
                .andExpect(status().isConflict());
        // 检验人不同 → 409
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testReq("TK-1", "t1", "PASS", "other")))
                .andExpect(status().isConflict());

        // 原始结果未被改写；同内容重放不新增记录
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        assertEquals(2, node.path("tests").size());
        assertEquals("TK-1", node.path("tests").get(0).path("testKey").asText());
        assertEquals("PASS", node.path("tests").get(0).path("result").asText());
        assertEquals("insp", node.path("tests").get(0).path("inspector").asText());
        assertEquals("TK-2", node.path("tests").get(1).path("testKey").asText());
    }

    @Test
    void approvalAndRecall_commandKeyReplayAndConflict() throws Exception {
        String batchKey = "BK-CMD-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        submitTest(batchKey, testReq("TK-1", "t1", "PASS", "insp"), null, null, 201);

        String body = "{\"commandKey\":\"CK-A1\"}";
        MvcResult first = mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "qa").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "qa").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 同 commandKey 换操作人 → 409
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "qa2").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());

        approve(batchKey, "ops", "OPERATIONS", "CK-A2", 201);
        String recallBody = "{\"commandKey\":\"CK-R1\",\"reason\":\"r1\"}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON).content(recallBody))
                .andExpect(status().isCreated());
        // 同键不同原因 → 409
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-R1\",\"reason\":\"r2\"}"))
                .andExpect(status().isConflict());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentFinalFailAndApprovals_neverReleasesRejectedBatch() throws Exception {
        String batchKey = "BK-RACE-FAIL-" + unique();
        createBatch(batchKey, List.of("t1", "t2"), 201);
        submitTest(batchKey, testReq("TK-1", "t1", "PASS", "insp-a"), null, null, 201);

        // 同时：最后一项 FAIL、QUALITY 批准、OPERATIONS 批准。行锁按提交顺序串行。
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testReq("TK-2", "t2", "FAIL", "insp-b"))),
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "qa").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CA1\"}")),
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "ops").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CA2\"}"))
        );

        // 起始为 QUARANTINED：任一批准先获锁都因检验未全部 PASS 返回 422；
        // FAIL 提交后批次 REJECTED，后续批准一律 409。绝无失败批次被放行。
        assertEquals("REJECTED", currentStatus(batchKey));
        Integer approvals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE batch_key = ?", Integer.class, batchKey);
        assertEquals(0, approvals, "存在 FAIL 的批次不得留下任何批准");
        assertEquals(201, results.get(0).get(30, TimeUnit.SECONDS));
        assertTrue(results.get(1).get(30, TimeUnit.SECONDS) == 409
                || results.get(1).get(30, TimeUnit.SECONDS) == 422);
        assertTrue(results.get(2).get(30, TimeUnit.SECONDS) == 409
                || results.get(2).get(30, TimeUnit.SECONDS) == 422);
    }

    @Test
    void concurrentSameActorTwoRoles_cannotRelease() throws Exception {
        String batchKey = "BK-RACE-ACTOR-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        submitTest(batchKey, testReq("TK-1", "t1", "PASS", "insp"), null, null, 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "same-guy").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CA1\"}")),
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "same-guy").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CA2\"}"))
        );

        int ok = 0;
        int conflict = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                ok++;
            } else if (code == 409) {
                conflict++;
            } else {
                fail("意外状态码: " + code);
            }
        }
        assertEquals(1, ok);
        assertEquals(1, conflict);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey), "同一人不能完成两角色，批次不得 RELEASED");
    }

    @Test
    void concurrentSameCommandKey_create_sameWinnerForBoth() throws Exception {
        String batchKey = "BK-RACE-CMD-" + unique();
        String body = createBody(batchKey, List.of("t1"));
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, batchKey);
        assertEquals(1, count);
    }

    @Test
    void concurrentRecallAndFinalApproval_cannotReleaseAfterRecall() throws Exception {
        String batchKey = "BK-RACE-RC-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        submitTest(batchKey, testReq("TK-1", "t1", "PASS", "insp"), null, null, 201);
        approve(batchKey, "qa", "QUALITY", "CK-Q", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "ops").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-O\"}")),
                () -> callStatus(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-R\",\"reason\":\"临检异常\"}"))
        );

        int secondApproval = results.get(0).get(30, TimeUnit.SECONDS);
        int recall = results.get(1).get(30, TimeUnit.SECONDS);
        String finalStatus = currentStatus(batchKey);
        Integer recallRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recall WHERE batch_key = ?", Integer.class, batchKey);
        if (recallRows > 0) {
            // 第二批准先提交为 RELEASED，召回随后提交：终态 RECALLED，绝不可能再被放行
            assertEquals(201, secondApproval);
            assertEquals(201, recall);
            assertEquals("RECALLED", finalStatus);
        } else {
            // 召回在 RELEASE_REVIEW 阶段先获锁被拒，第二批准随后提交：终态 RELEASED，无召回记录
            assertEquals(409, recall);
            assertEquals(201, secondApproval);
            assertEquals("RELEASED", finalStatus);
        }
        // 批准历史始终可查，未被召回删除
        Integer approvals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE batch_key = ?", Integer.class, batchKey);
        assertTrue(approvals >= 1);
    }

    // ---------- 持久化 ----------

    @Test
    void dataIsPersistedInTables() throws Exception {
        String batchKey = "BK-PERSIST-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        submitTest(batchKey, testReq("TK-1", "t1", "PASS", "insp"), null, null, 201);
        approve(batchKey, "qa", "QUALITY", "CK-A1", 201);
        approve(batchKey, "ops", "OPERATIONS", "CK-A2", 201);
        recall(batchKey, "u", "原因", "CK-R1", 201);

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ? AND status = 'RECALLED'",
                Integer.class, batchKey));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch_required_test WHERE batch_key = ?",
                Integer.class, batchKey));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM test_result WHERE batch_key = ?",
                Integer.class, batchKey));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE batch_key = ?",
                Integer.class, batchKey));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM recall WHERE batch_key = ?",
                Integer.class, batchKey));
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_key IN ('CK-1','CK-A1','CK-A2','CK-R1')",
                Integer.class));
    }

    @Test
    void sameTestItemWithDifferentTestKey_returns409_andHistoryKeepsStatusSnapshots() throws Exception {
        String batchKey = "BK-DUPITEM2-" + unique();
        createBatch(batchKey, List.of("t1", "t2"), 201);
        submitTest(batchKey, testReq("TK-1", "t1", "PASS", "i"), null, null, 201);
        // 同一检验项换 testKey 重复提交 → 409
        submitTest(batchKey, testReq("TK-2", "t1", "PASS", "i"), null, null, 409);
        submitTest(batchKey, testReq("TK-3", "t2", "PASS", "i"), null, null, 201);

        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("QUARANTINED", node.path("tests").get(0).path("batchStatus").asText(),
                "第一条 PASS 时仍在隔离");
        assertEquals("PENDING_RELEASE", node.path("tests").get(1).path("batchStatus").asText(),
                "最后一条 PASS 后进入待放行");
    }

    @Test
    void failTestHistory_snapshotStatusRejected() throws Exception {
        String batchKey = "BK-FAIL-SNAP-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        submitTest(batchKey, testReq("TK-1", "t1", "FAIL", "i"), null, null, 201);
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("REJECTED", node.path("tests").get(0).path("batchStatus").asText());
        assertEquals("REJECTED", node.path("batch").path("status").asText());
    }

    // ---------- helpers ----------

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests) {
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String createBody(String batchKey, List<String> items) throws Exception {
        return objectMapper.writeValueAsString(new CreateCmd("CK-1", batchKey, "PROD-1", "LOT-1",
                Instant.parse("2026-01-02T03:04:05Z"), items));
    }

    private void createBatch(String batchKey, List<String> items, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(batchKey, items)))
                .andExpect(status().is(expectedStatus));
    }

    private String testReq(String testKey, String item, String result, String inspector) throws Exception {
        return objectMapper.writeValueAsString(new TestCmd("CK-T-" + unique(), testKey, item, result, inspector));
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private void submitTest(String batchKey, String body, Object unused1, Object unused2, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private void approve(String batchKey, String actor, String role, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    private void recall(String batchKey, String actor, String reason, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason + "\"}"))
                .andExpect(status().is(expected));
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private List<String> availableKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> keys = new ArrayList<>();
        array.forEach(n -> keys.add(n.path("batchKey").asText()));
        return keys;
    }

    private int callStatus(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
        try {
            return mockMvc.perform(req).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SafeVarargs
    private List<Future<Integer>> runConcurrent(Callable<Integer>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CyclicBarrier barrier = new CyclicBarrier(tasks.length);
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return task.call();
            }));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        return futures;
    }
}

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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 生产线加工顺序与批间清洗验证测试：登记主流程与插队/多线拦截、清洗提交与确认、
 * 残留未舍入判定、前批召回血缘阻断放行、requestId 幂等与并发竞争，全部在真实 H2 事务上验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LineRunCleaningTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM line_run");
        jdbc.update("DELETE FROM cleaning_record");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程 ----------

    @Test
    void happyFlow_differentProductWithConfirmedCleaning_releases() throws Exception {
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        createBatch(a, "PROD-A", List.of("t1"));
        createBatch(b, "PROD-B", List.of("t1"));

        // 首批无需清洗
        MvcResult first = registerRun("LINE-1", a, 1, "op-1", "RID-" + unique(), 201);
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        assertTrue(firstBody.path("prevBatchKey").isNull());
        assertFalse(firstBody.path("cleaningRequired").asBoolean());

        // 不同产品：先提交清洗，再登记后批
        long cleaningId = cleaningIdOf(submitCleaning(a, b, "0.09", "op-1", "RID-" + unique(), 201));
        MvcResult second = registerRun("LINE-1", b, 2, "op-1", "RID-" + unique(), 201);
        JsonNode secondBody = objectMapper.readTree(second.getResponse().getContentAsString());
        assertEquals(a, secondBody.path("prevBatchKey").asText());
        assertTrue(secondBody.path("cleaningRequired").asBoolean());

        // 另一名 QUALITY 确认后清洗生效
        confirmCleaning(cleaningId, "qa-2", "QUALITY", 0, "RID-" + unique(), 200);
        assertEquals("CONFIRMED", cleaningStatus(cleaningId));

        // 原双角色放行规则不变
        passAllTests(b, List.of("t1"));
        approve(b, "qa-1", "QUALITY", "CK-AP1-" + unique(), 201);
        approve(b, "ops-1", "OPERATIONS", "CK-AP2-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(b));
    }

    @Test
    void residual0_100001Blocked_then0_09ConfirmedBySecondPerson_releases() throws Exception {
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        createBatch(a, "PROD-A", List.of("t1"));
        createBatch(b, "PROD-B", List.of("t1"));
        registerRun("LINE-1", a, 1, "op-1", "RID-" + unique(), 201);

        // 残留 0.100001 毫克（未舍入 > 0.1）：确认被阻断，记录判为不合格
        long badId = cleaningIdOf(submitCleaning(a, b, "0.100001", "op-1", "RID-" + unique(), 201));
        registerRun("LINE-1", b, 2, "op-1", "RID-" + unique(), 201);
        MvcResult rejected = confirmCleaning(badId, "qa-2", "QUALITY", 0, "RID-" + unique(), 422);
        assertEquals("REJECTED", objectMapper.readTree(rejected.getResponse().getContentAsString())
                .path("status").asText());
        assertEquals("REJECTED", cleaningStatus(badId));

        // 无合格清洗，后批放行被阻断
        passAllTests(b, List.of("t1"));
        approve(b, "qa-1", "QUALITY", "CK-AP1-" + unique(), 201);
        approve(b, "ops-1", "OPERATIONS", "CK-AP2-" + unique(), 422);
        assertEquals("RELEASE_REVIEW", currentStatus(b));

        // 第二次清洗只新增记录：旧记录保持 REJECTED
        long goodId = cleaningIdOf(submitCleaning(a, b, "0.09", "op-1", "RID-" + unique(), 201));
        assertNotEquals(badId, goodId);
        confirmCleaning(goodId, "qa-3", "QUALITY", 0, "RID-" + unique(), 200);
        assertEquals("REJECTED", cleaningStatus(badId), "旧不合格记录不得被改写为合格");
        assertEquals("CONFIRMED", cleaningStatus(goodId));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM cleaning_record WHERE prev_batch_key = ? AND next_batch_key = ?",
                Integer.class, a, b));

        // 合格清洗到位后放行成功
        approve(b, "ops-1", "OPERATIONS", "CK-AP3-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(b));
    }

    // ---------- 清洗确认约束 ----------

    @Test
    void samePersonConfirm_returns422_recordStaysPending() throws Exception {
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        createBatch(a, "PROD-A", List.of("t1"));
        createBatch(b, "PROD-B", List.of("t1"));
        long id = cleaningIdOf(submitCleaning(a, b, "0.05", "op-1", "RID-" + unique(), 201));

        // 确认人同提交人 → 422，记录保持 PENDING
        confirmCleaning(id, "op-1", "QUALITY", 0, "RID-" + unique(), 422);
        assertEquals("PENDING", cleaningStatus(id));

        // 非 QUALITY 角色 → 422
        confirmCleaning(id, "qa-2", "OPERATIONS", 0, "RID-" + unique(), 422);
        assertEquals("PENDING", cleaningStatus(id));

        // 期望版本不匹配 → 409
        confirmCleaning(id, "qa-2", "QUALITY", 7, "RID-" + unique(), 409);

        // 另一名 QUALITY 确认成功
        confirmCleaning(id, "qa-2", "QUALITY", 0, "RID-" + unique(), 200);
        assertEquals("CONFIRMED", cleaningStatus(id));

        // 已终结记录不可再次确认
        confirmCleaning(id, "qa-3", "QUALITY", 1, "RID-" + unique(), 409);
    }

    @Test
    void residualBoundary_pointOne_confirms() throws Exception {
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        createBatch(a, "PROD-A", List.of("t1"));
        createBatch(b, "PROD-B", List.of("t1"));
        // 0.1 与 0.100000 均未超过 0.1 毫克，确认合格
        long id1 = cleaningIdOf(submitCleaning(a, b, "0.1", "op-1", "RID-" + unique(), 201));
        confirmCleaning(id1, "qa-2", "QUALITY", 0, "RID-" + unique(), 200);
        assertEquals("CONFIRMED", cleaningStatus(id1));
        long id2 = cleaningIdOf(submitCleaning(a, b, "0.100000", "op-1", "RID-" + unique(), 201));
        confirmCleaning(id2, "qa-2", "QUALITY", 0, "RID-" + unique(), 200);
        assertEquals("CONFIRMED", cleaningStatus(id2));
    }

    @Test
    void invalidResidual_returns400() throws Exception {
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        createBatch(a, "PROD-A", List.of("t1"));
        createBatch(b, "PROD-B", List.of("t1"));
        submitCleaning(a, b, "0.1234567", "op-1", "RID-" + unique(), 400); // 超过 6 位小数
        submitCleaning(a, b, "100.000001", "op-1", "RID-" + unique(), 400); // 超过 100 毫克
        submitCleaning(a, b, "-0.5", "op-1", "RID-" + unique(), 400); // 负数
        submitCleaning(a, b, "abc", "op-1", "RID-" + unique(), 400); // 非数字
        submitCleaning(a, b, "1000", "op-1", "RID-" + unique(), 400); // 整数位超限
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cleaning_record", Integer.class));
        // 边界合法值
        submitCleaning(a, b, "100", "op-1", "RID-" + unique(), 201);
        submitCleaning(a, b, "0", "op-1", "RID-" + unique(), 201);
    }
    // ---------- 登记约束 ----------

    @Test
    void registerWithoutCleaning_differentProduct_returns422() throws Exception {
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        createBatch(a, "PROD-A", List.of("t1"));
        createBatch(b, "PROD-B", List.of("t1"));
        registerRun("LINE-1", a, 1, "op-1", "RID-" + unique(), 201);
        // 前后产品不同且无清洗记录 → 登记拒绝
        registerRun("LINE-1", b, 2, "op-1", "RID-" + unique(), 422);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM line_run", Integer.class));
    }

    @Test
    void sameProductNoCleaning_registersAndReleases() throws Exception {
        String a1 = "BK-A1-" + unique();
        String a2 = "BK-A2-" + unique();
        createBatch(a1, "PROD-S", List.of("t1"));
        createBatch(a2, "PROD-S", List.of("t1"));
        registerRun("LINE-A", a1, 1, "op-1", "RID-" + unique(), 201);
        MvcResult run = registerRun("LINE-A", a2, 2, "op-1", "RID-" + unique(), 201);
        assertFalse(objectMapper.readTree(run.getResponse().getContentAsString())
                .path("cleaningRequired").asBoolean());
        passAllTests(a2, List.of("t1"));
        approve(a2, "qa-1", "QUALITY", "CK-AP1-" + unique(), 201);
        approve(a2, "ops-1", "OPERATIONS", "CK-AP2-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(a2));
    }

    @Test
    void sequenceConstraints_duplicateBatchAndJumpRejected_seqIncrements() throws Exception {
        String b1 = "BK-1-" + unique();
        String b2 = "BK-2-" + unique();
        String b3 = "BK-3-" + unique();
        createBatch(b1, "PROD-S", List.of("t1"));
        createBatch(b2, "PROD-S", List.of("t1"));
        createBatch(b3, "PROD-S", List.of("t1"));

        registerRun("LINE-A", b1, 1, "op-1", "RID-" + unique(), 201);
        // 一批挂多线 → 409
        registerRun("LINE-B", b1, 1, "op-1", "RID-" + unique(), 409);
        // 插队：期望顺序应为 2 → 409
        registerRun("LINE-A", b2, 3, "op-1", "RID-" + unique(), 409);
        registerRun("LINE-A", b2, 2, "op-1", "RID-" + unique(), 201);
        registerRun("LINE-A", b3, 3, "op-1", "RID-" + unique(), 201);

        MvcResult runs = mockMvc.perform(get("/api/lines/LINE-A/runs"))
                .andExpect(status().isOk()).andReturn();
        JsonNode array = objectMapper.readTree(runs.getResponse().getContentAsString());
        assertEquals(3, array.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(i + 1, array.get(i).path("seq").asInt());
        }
        assertEquals(b1, array.get(0).path("batchKey").asText());
        assertEquals(b2, array.get(1).path("batchKey").asText());
        assertEquals(b1, array.get(1).path("prevBatchKey").asText());
    }

    @Test
    void cleaningBoundToActualPair_notReusableForOtherPairs() throws Exception {
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        String x = "BK-X-" + unique();
        String c = "BK-C-" + unique();
        createBatch(a, "PROD-A", List.of("t1"));
        createBatch(b, "PROD-B", List.of("t1"));
        createBatch(x, "PROD-A", List.of("t1"));
        createBatch(c, "PROD-B", List.of("t1"));

        // 线 1：A->B 有合格清洗
        registerRun("LINE-1", a, 1, "op-1", "RID-" + unique(), 201);
        long id = cleaningIdOf(submitCleaning(a, b, "0.05", "op-1", "RID-" + unique(), 201));
        confirmCleaning(id, "qa-2", "QUALITY", 0, "RID-" + unique(), 200);
        registerRun("LINE-1", b, 2, "op-1", "RID-" + unique(), 201);

        // 线 2：X->C 产品组合相同，但不得沿用 (A,B) 的清洗记录
        registerRun("LINE-2", x, 1, "op-1", "RID-" + unique(), 201);
        registerRun("LINE-2", c, 2, "op-1", "RID-" + unique(), 422);
    }

    @Test
    void pendingCleaningNotEnough_releaseBlockedUntilConfirmed() throws Exception {
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        createBatch(a, "PROD-A", List.of("t1"));
        createBatch(b, "PROD-B", List.of("t1"));
        registerRun("LINE-1", a, 1, "op-1", "RID-" + unique(), 201);
        long id = cleaningIdOf(submitCleaning(a, b, "0.05", "op-1", "RID-" + unique(), 201));
        registerRun("LINE-1", b, 2, "op-1", "RID-" + unique(), 201);

        // 清洗仅提交未确认：放行被阻断
        passAllTests(b, List.of("t1"));
        approve(b, "qa-1", "QUALITY", "CK-AP1-" + unique(), 201);
        approve(b, "ops-1", "OPERATIONS", "CK-AP2-" + unique(), 422);
        assertEquals("RELEASE_REVIEW", currentStatus(b));

        // 确认后放行成功
        confirmCleaning(id, "qa-2", "QUALITY", 0, "RID-" + unique(), 200);
        approve(b, "ops-1", "OPERATIONS", "CK-AP3-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(b));
    }

    // ---------- 前批召回血缘阻断 ----------

    @Test
    void prevBatchRecalled_blocksReleaseEvenWithConfirmedCleaning() throws Exception {
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        createBatch(a, "PROD-A", List.of("t1"));
        createBatch(b, "PROD-B", List.of("t1"));
        // 前批先放行，再上机
        passAllTests(a, List.of("t1"));
        approve(a, "qa-1", "QUALITY", "CK-A1-" + unique(), 201);
        approve(a, "ops-1", "OPERATIONS", "CK-A2-" + unique(), 201);
        registerRun("LINE-1", a, 1, "op-1", "RID-" + unique(), 201);

        long id = cleaningIdOf(submitCleaning(a, b, "0.05", "op-1", "RID-" + unique(), 201));
        confirmCleaning(id, "qa-2", "QUALITY", 0, "RID-" + unique(), 200);
        registerRun("LINE-1", b, 2, "op-1", "RID-" + unique(), 201);

        passAllTests(b, List.of("t1"));
        approve(b, "qa-9", "QUALITY", "CK-B1-" + unique(), 201);
        // 前批被召回：即使有合格清洗，后批也不得放行
        recall(a, "u-1", "CK-R-" + unique(), 201);
        approve(b, "ops-9", "OPERATIONS", "CK-B2-" + unique(), 422);
        assertEquals("RELEASE_REVIEW", currentStatus(b));
    }

    @Test
    void prevBatchAncestorRecalled_blocksRelease() throws Exception {
        String parent = "BK-P-" + unique();
        String child1 = "BK-C1-" + unique();
        String child2 = "BK-C2-" + unique();
        String b = "BK-B-" + unique();
        createBatch(parent, "PROD-A", List.of("t1"));
        createBatch(b, "PROD-B", List.of("t1"));
        // 父批放行后拆分，子批作为前批上机
        passAllTests(parent, List.of("t1"));
        approve(parent, "qa-1", "QUALITY", "CK-P1-" + unique(), 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-P2-" + unique(), 201);
        String splitBody = "{\"commandKey\":\"CK-SP-" + unique() + "\",\"children\":["
                + "{\"batchKey\":\"" + child1 + "\",\"batchNo\":\"L1\"},"
                + "{\"batchKey\":\"" + child2 + "\",\"batchNo\":\"L2\"}]}";
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(splitBody))
                .andExpect(status().isCreated());

        registerRun("LINE-1", child1, 1, "op-1", "RID-" + unique(), 201);
        long id = cleaningIdOf(submitCleaning(child1, b, "0.05", "op-1", "RID-" + unique(), 201));
        confirmCleaning(id, "qa-2", "QUALITY", 0, "RID-" + unique(), 200);
        registerRun("LINE-1", b, 2, "op-1", "RID-" + unique(), 201);

        passAllTests(b, List.of("t1"));
        approve(b, "qa-9", "QUALITY", "CK-B1-" + unique(), 201);
        // 前批的父批（SPLIT）被召回：前批处于召回血缘，后批不得放行
        recall(parent, "u-1", "CK-R-" + unique(), 201);
        approve(b, "ops-9", "OPERATIONS", "CK-B2-" + unique(), 422);
        assertEquals("RELEASE_REVIEW", currentStatus(b));
    }
    // ---------- requestId 幂等 ----------

    @Test
    void requestId_replaySameParams_conflictsOnChangedParams_failureDoesNotHoldKey() throws Exception {
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        createBatch(a, "PROD-A", List.of("t1"));
        createBatch(b, "PROD-B", List.of("t1"));

        // 同 requestId 同参重放 → 返回首次完整响应
        String rid1 = "RID-" + unique();
        MvcResult first = submitCleaning(a, b, "0.05", "op-1", rid1, 201);
        MvcResult replay = submitCleaning(a, b, "0.05", "op-1", rid1, 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cleaning_record", Integer.class));

        // 同 requestId 异参 → 409
        submitCleaning(a, b, "0.06", "op-1", rid1, 409);

        // 失败不占键：前批不存在 → 404；同 requestId 修正参数后按新命令成功
        String rid2 = "RID-" + unique();
        submitCleaning("NO-SUCH", b, "0.05", "op-1", rid2, 404);
        submitCleaning(a, b, "0.07", "op-1", rid2, 201);

        // 确认人同提交人 → 422 且不占键；同 requestId 换确认人（异参）按新命令执行成功
        long id = cleaningIdOf(first);
        String rid3 = "RID-" + unique();
        confirmCleaning(id, "op-1", "QUALITY", 0, rid3, 422);
        confirmCleaning(id, "qa-2", "QUALITY", 0, rid3, 200);
        assertEquals("CONFIRMED", cleaningStatus(id));
    }

    @Test
    void requestId_registerRun_replayAndConflict() throws Exception {
        String a = "BK-A-" + unique();
        createBatch(a, "PROD-A", List.of("t1"));
        String rid = "RID-" + unique();
        MvcResult first = registerRun("LINE-1", a, 1, "op-1", rid, 201);
        MvcResult replay = registerRun("LINE-1", a, 1, "op-1", rid, 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM line_run", Integer.class));
        // 同 requestId 改期望顺序 → 409
        registerRun("LINE-1", a, 2, "op-1", rid, 409);
    }

    // ---------- 并发 ----------

    @Test
    void concurrentRegistrations_sameLine_oneWinsPerSeq() throws Exception {
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        String c = "BK-C-" + unique();
        createBatch(a, "PROD-S", List.of("t1"));
        createBatch(b, "PROD-S", List.of("t1"));
        createBatch(c, "PROD-S", List.of("t1"));
        registerRun("LINE-C", a, 1, "op-1", "RID-" + unique(), 201);

        // B、C 同时以 expectedSeq=2 登记：恰一个成功，另一个 409，不泄露唯一键异常
        List<Future<Integer>> results = runConcurrent(
                () -> registerRunStatus("LINE-C", b, 2, "RID-" + unique()),
                () -> registerRunStatus("LINE-C", c, 2, "RID-" + unique()));
        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(1, (first == 201 ? 1 : 0) + (second == 201 ? 1 : 0), "恰一个登记成功");
        assertTrue(first == 409 || second == 409);
        String loser = first == 409 ? b : c;

        // 失败方按下一顺序号重试成功，顺序保持连续
        registerRun("LINE-C", loser, 3, "op-1", "RID-" + unique(), 201);
        MvcResult runs = mockMvc.perform(get("/api/lines/LINE-C/runs"))
                .andExpect(status().isOk()).andReturn();
        JsonNode array = objectMapper.readTree(runs.getResponse().getContentAsString());
        assertEquals(3, array.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(i + 1, array.get(i).path("seq").asInt());
        }
    }

    @Test
    void concurrentConfirms_sameCleaning_exactlyOneSucceeds() throws Exception {
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        createBatch(a, "PROD-A", List.of("t1"));
        createBatch(b, "PROD-B", List.of("t1"));
        long id = cleaningIdOf(submitCleaning(a, b, "0.05", "op-1", "RID-" + unique(), 201));

        List<Future<Integer>> results = runConcurrent(
                () -> confirmCleaningStatus(id, "qa-2", "RID-" + unique()),
                () -> confirmCleaningStatus(id, "qa-3", "RID-" + unique()));
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 200) {
                ok++;
            } else if (code == 409) {
                conflict++;
            } else {
                fail("意外状态码: " + code);
            }
        }
        assertEquals(1, ok);
        assertEquals(1, conflict);
        assertEquals("CONFIRMED", cleaningStatus(id));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM cleaning_record WHERE id = ? AND version = 1",
                Integer.class, id));
    }

    @Test
    void concurrentConfirmAndRelease_consistentOutcome() throws Exception {
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        createBatch(a, "PROD-A", List.of("t1"));
        createBatch(b, "PROD-B", List.of("t1"));
        registerRun("LINE-1", a, 1, "op-1", "RID-" + unique(), 201);
        long id = cleaningIdOf(submitCleaning(a, b, "0.05", "op-1", "RID-" + unique(), 201));
        registerRun("LINE-1", b, 2, "op-1", "RID-" + unique(), 201);
        passAllTests(b, List.of("t1"));
        approve(b, "qa-1", "QUALITY", "CK-AP1-" + unique(), 201);

        // 并发：清洗确认 与 第二批准。行锁按提交顺序裁决，结果二选一且终态一致。
        List<Future<Integer>> results = runConcurrent(
                () -> confirmCleaningStatus(id, "qa-2", "RID-" + unique()),
                () -> approveStatus(b, "ops-1", "OPERATIONS", "CK-AP2-" + unique()));
        int confirm = results.get(0).get(30, TimeUnit.SECONDS);
        int release = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(200, confirm);
        assertTrue(release == 201 || release == 422, "放行只能依据确认前后的实际状态裁决");
        if (release == 422) {
            // 放行先获锁时清洗尚未确认：确认已生效后重试放行成功
            approve(b, "ops-1", "OPERATIONS", "CK-AP3-" + unique(), 201);
        }
        assertEquals("CONFIRMED", cleaningStatus(id));
        assertEquals("RELEASED", currentStatus(b));
    }

    @Test
    void concurrentSameRequestId_submitCleaning_singleRecord() throws Exception {
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        createBatch(a, "PROD-A", List.of("t1"));
        createBatch(b, "PROD-B", List.of("t1"));
        String rid = "RID-" + unique();
        List<Future<String>> results = runConcurrentBodies(
                () -> submitCleaningBody(a, b, "0.05", "op-1", rid),
                () -> submitCleaningBody(a, b, "0.05", "op-1", rid));
        String first = results.get(0).get(30, TimeUnit.SECONDS);
        String second = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(first, second, "同键同参并发重放均返回首次响应");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cleaning_record", Integer.class));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createBatch(String batchKey, String productCode, List<String> tests) throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < tests.size(); i++) {
            items.append(i == 0 ? "" : ",").append("\"").append(tests.get(i)).append("\"");
        }
        String body = "{\"commandKey\":\"CK-C-" + unique() + "\",\"batchKey\":\"" + batchKey
                + "\",\"productCode\":\"" + productCode + "\",\"batchNo\":\"LOT-1\","
                + "\"producedAt\":\"2026-01-02T03:04:05Z\",\"requiredTests\":[" + items + "]}";
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void passAllTests(String batchKey, List<String> tests) throws Exception {
        for (String item : tests) {
            String body = "{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-" + unique()
                    + "\",\"testItem\":\"" + item + "\",\"result\":\"PASS\",\"inspector\":\"insp-"
                    + unique() + "\"}";
            mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated());
        }
    }

    private void approve(String batchKey, String actor, String role, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    private int approveStatus(String batchKey, String actor, String role, String commandKey) {
        try {
            return mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                            .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commandKey\":\"" + commandKey + "\"}"))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void recall(String batchKey, String actor, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"质量异常\"}"))
                .andExpect(status().is(expected));
    }
    private MvcResult registerRun(String lineCode, String batchKey, int seq, String actor,
                                  String requestId, int expected) throws Exception {
        return mockMvc.perform(post("/api/lines/" + lineCode + "/runs")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"batchKey\":\"" + batchKey
                                + "\",\"expectedSeq\":" + seq + "}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private int registerRunStatus(String lineCode, String batchKey, int seq, String requestId) {
        try {
            return mockMvc.perform(post("/api/lines/" + lineCode + "/runs")
                            .header("X-Actor-Id", "op-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"" + requestId + "\",\"batchKey\":\"" + batchKey
                                    + "\",\"expectedSeq\":" + seq + "}"))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private MvcResult submitCleaning(String prev, String next, String residual, String actor,
                                     String requestId, int expected) throws Exception {
        return mockMvc.perform(post("/api/cleanings")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"prevBatchKey\":\"" + prev
                                + "\",\"nextBatchKey\":\"" + next + "\",\"residual\":\"" + residual
                                + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private String submitCleaningBody(String prev, String next, String residual, String actor,
                                      String requestId) {
        try {
            return mockMvc.perform(post("/api/cleanings")
                            .header("X-Actor-Id", actor)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"" + requestId + "\",\"prevBatchKey\":\"" + prev
                                    + "\",\"nextBatchKey\":\"" + next + "\",\"residual\":\"" + residual
                                    + "\"}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private MvcResult confirmCleaning(long id, String actor, String role, int version,
                                      String requestId, int expected) throws Exception {
        return mockMvc.perform(post("/api/cleanings/" + id + "/confirm")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"expectedVersion\":" + version
                                + "}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private int confirmCleaningStatus(long id, String actor, String requestId) {
        try {
            return mockMvc.perform(post("/api/cleanings/" + id + "/confirm")
                            .header("X-Actor-Id", actor).header("X-Approval-Role", "QUALITY")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"" + requestId + "\",\"expectedVersion\":0}"))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long cleaningIdOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("cleaningId").asLong();
    }

    private String cleaningStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM cleaning_record WHERE id = ?",
                String.class, id);
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
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

    @SafeVarargs
    private List<Future<String>> runConcurrentBodies(Callable<String>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CyclicBarrier barrier = new CyclicBarrier(tasks.length);
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
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
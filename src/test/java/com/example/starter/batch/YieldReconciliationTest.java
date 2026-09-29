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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 产率平衡核算真实 H2（MODE=MySQL）数据库测试：
 * 题干样例主流程、负数/超限/版本过期等拒绝分支、requestId/commandKey 幂等、
 * 双人处置门禁、冻结上限不回写、核算与放行并发裁决、重复放行并发唯一约束。
 */
@SpringBootTest
@AutoConfigureMockMvc
class YieldReconciliationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM yield_release");
        jdbc.update("DELETE FROM yield_disposition");
        jdbc.update("DELETE FROM yield_reconciliation");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程（题干样例） ----------

    @Test
    void sample_deviationExactlyAtLimit_isReleasable() throws Exception {
        String batchKey = createBatch("BK-Y-OK-", "0.500");
        // 投料100 合格90 损耗8 报废1.5 → 偏差0.5，恰好等于上限0.5 → 合格
        JsonNode body = reconcile(batchKey, "REQ-1", "100.000", "90.000", "8.000", "1.500", 201);
        assertEquals(1, body.path("version").asInt());
        assertDecimal(body, "deviation", "0.500");
        assertDecimal(body, "toleranceLimit", "0.500");
        assertEquals("WITHIN_LIMIT", body.path("status").asText());
        assertDecimal(body, "inputQuantity", "100.000");
        assertDecimal(body, "qualifiedQuantity", "90.000");
        assertDecimal(body, "lossQuantity", "8.000");
        assertDecimal(body, "scrapQuantity", "1.500");
        assertFalse(body.path("released").asBoolean());
        assertTrue(body.path("dispositionHandlers").isEmpty());
        // 线格式锁定：四类数量、偏差与上限统一输出三位小数
        MvcResult raw = mockMvc.perform(get("/api/batches/" + batchKey + "/yield/current"))
                .andExpect(status().isOk()).andReturn();
        String rawJson = raw.getResponse().getContentAsString();
        assertTrue(rawJson.contains("\"inputQuantity\":100.000"));
        assertTrue(rawJson.contains("\"deviation\":0.500"));
        assertTrue(rawJson.contains("\"toleranceLimit\":0.500"));

        JsonNode released = release(batchKey, "qa-1", "REL-1", 201);
        assertEquals(1, released.path("version").asInt());
        assertEquals("REQ-1", released.path("requestId").asText());
        assertEquals("WITHIN_LIMIT", released.path("status").asText());
        assertDecimal(released, "deviation", "0.500");
        assertDecimal(released, "toleranceLimit", "0.500");

        JsonNode current = current(batchKey, 200);
        assertTrue(current.path("released").asBoolean());
        assertNotNull(current.path("releasedAt").asText(null));
    }

    @Test
    void sample_scrapOne_overLimit_needsTwoDifferentHandlers_thenReleased() throws Exception {
        String batchKey = createBatch("BK-Y-OVER-", "0.500");
        // 报废1.0 → 偏差1.0 超限待处置
        JsonNode body = reconcile(batchKey, "REQ-OVER", "100.000", "90.000", "8.000", "1.000", 201);
        assertDecimal(body, "deviation", "1.000");
        assertEquals("PENDING_DISPOSITION", body.path("status").asText());

        // 待处置不能放行
        release(batchKey, "qa-1", "REL-BLOCKED", 422);

        // 第一名处置人确认
        JsonNode first = dispose(batchKey, "h-1", "REQ-OVER", "D-1", 201);
        assertEquals("PENDING_DISPOSITION", first.path("status").asText());
        assertEquals(1, first.path("dispositionHandlers").size());
        // 仅一人仍不能放行
        release(batchKey, "qa-1", "REL-BLOCKED-2", 422);

        // 第二名不同处置人确认 → 门禁解除
        JsonNode second = dispose(batchKey, "h-2", "REQ-OVER", "D-2", 201);
        assertEquals("DISPOSITION_CLEARED", second.path("status").asText());
        assertEquals(2, second.path("dispositionHandlers").size());
        assertEquals("h-1", second.path("dispositionHandlers").get(0).path("handlerId").asText());
        assertEquals("h-2", second.path("dispositionHandlers").get(1).path("handlerId").asText());

        JsonNode released = release(batchKey, "qa-2", "REL-OK", 201);
        assertEquals("DISPOSITION_CLEARED", released.path("status").asText());
        assertEquals(1, released.path("version").asInt());

        // 数据库最终状态核对
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM yield_release WHERE batch_key = ? AND version = 1",
                Integer.class, batchKey));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM yield_disposition WHERE batch_key = ?", Integer.class, batchKey));
    }

    @Test
    void defaultToleranceLimit_isHalf_whenCreateOmitsIt() throws Exception {
        String batchKey = createBatchDefaultLimit("BK-Y-DEF-");
        JsonNode body = reconcile(batchKey, "REQ-DEF", "100.000", "90.000", "8.000", "1.500", 201);
        assertDecimal(body, "toleranceLimit", "0.500");
        assertEquals("WITHIN_LIMIT", body.path("status").asText());
    }

    // ---------- 拒绝分支 ----------

    @Test
    void negativeQuantity_returns422_andLeavesOriginalReconciliationUntouched() throws Exception {
        String batchKey = createBatch("BK-Y-NEG-", "0.500");
        reconcile(batchKey, "REQ-V1", "100.000", "90.000", "8.000", "2.000", 201);

        for (String[] q : new String[][]{
                {"-0.001", "90.000", "8.000", "2.000"},
                {"100.000", "-1.000", "8.000", "2.000"},
                {"100.000", "90.000", "-8.000", "2.000"},
                {"100.000", "90.000", "8.000", "-2.000"}
        }) {
            reconcile(batchKey, "REQ-NEG-" + UUID.randomUUID().toString().substring(0, 6),
                    q[0], q[1], q[2], q[3], 422);
        }

        // 原核算未改动：仍只有版本1，状态仍 WITHIN_LIMIT
        JsonNode history = history(batchKey, 200);
        assertEquals(1, history.path("versions").size());
        assertEquals("WITHIN_LIMIT", history.path("versions").get(0).path("status").asText());
        assertEquals("REQ-V1", current(batchKey, 200).path("requestId").asText());
    }

    @Test
    void dispositionOnStaleVersion_returns422_andChangesNothing() throws Exception {
        String batchKey = createBatch("BK-Y-STALE-", "0.500");
        // v1 超限
        reconcile(batchKey, "REQ-V1", "100.000", "90.000", "8.000", "1.000", 201);
        // v2 也超限（新版本使 v1 过期）
        reconcile(batchKey, "REQ-V2", "100.000", "89.000", "8.000", "1.000", 201);
        assertEquals(2, current(batchKey, 200).path("version").asInt());

        // 处置过期的 v1 → 422
        dispose(batchKey, "h-1", "REQ-V1", "D-STALE", 422);

        // v1 无处置记录，状态保持 PENDING_DISPOSITION；v2 仍待处置
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM yield_disposition WHERE batch_key = ? AND version = 1",
                Integer.class, batchKey));
        JsonNode history = history(batchKey, 200);
        assertEquals("PENDING_DISPOSITION", history.path("versions").get(0).path("status").asText());
        assertEquals("PENDING_DISPOSITION", history.path("versions").get(1).path("status").asText());
        release(batchKey, "qa-1", "REL-NOPE", 422);
    }

    @Test
    void dispositionConstraints_withinLimitNeedsNoDisposition_sameHandlerIdempotent_thirdHandlerConflict()
            throws Exception {
        String batchKey = createBatch("BK-Y-DISP-", "0.500");
        reconcile(batchKey, "REQ-OK", "100.000", "90.000", "8.000", "2.000", 201);
        // 未超限版本不需要处置
        dispose(batchKey, "h-1", "REQ-OK", "D-NEEDLESS", 422);

        String overKey = createBatch("BK-Y-DISP2-", "0.500");
        reconcile(overKey, "REQ-OVER", "100.000", "90.000", "8.000", "1.000", 201);
        dispose(overKey, "h-1", "REQ-OVER", "D-1", 201);
        // 同一处置人换 commandKey 再确认：业务幂等，不新增记录
        JsonNode replay = dispose(overKey, "h-1", "REQ-OVER", "D-1-AGAIN", 200);
        assertEquals(1, replay.path("dispositionHandlers").size());
        // 第三人在门禁解除前不能抢先；第二名不同人确认解除
        dispose(overKey, "h-2", "REQ-OVER", "D-2", 201);
        // 门禁已解除后第三人确认 → 409
        dispose(overKey, "h-3", "REQ-OVER", "D-3", 409);
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM yield_disposition WHERE batch_key = ?", Integer.class, overKey));
    }

    @Test
    void releaseBeforeAnyReconciliation_returns422() throws Exception {
        String batchKey = createBatch("BK-Y-NONE-", "0.500");
        release(batchKey, "qa-1", "REL-EARLY", 422);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM yield_release WHERE batch_key = ?", Integer.class, batchKey));
    }

    @Test
    void afterRelease_newReconciliationAndDispositionAreRejected() throws Exception {
        String batchKey = createBatch("BK-Y-DONE-", "0.500");
        reconcile(batchKey, "REQ-1", "100.000", "90.000", "8.000", "2.000", 201);
        release(batchKey, "qa-1", "REL-1", 201);

        reconcile(batchKey, "REQ-2", "100.000", "89.000", "8.000", "1.000", 409);
        dispose(batchKey, "h-1", "REQ-1", "D-LATE", 409);
        // 放行版本数量未被改写
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM yield_reconciliation WHERE batch_key = ?", Integer.class, batchKey));
    }

    @Test
    void unknownBatchAndRequest_are404_missingActorHeaderIs400() throws Exception {
        reconcile("NO-SUCH-BATCH", "REQ-X", "1", "1", "0", "0", 404);
        release("NO-SUCH-BATCH", "qa", "REL-X", 404);

        String batchKey = createBatch("BK-Y-404-", "0.500");
        dispose(batchKey, "h-1", "REQ-MISSING", "D-X", 404);

        // 缺 X-Actor-Id → 400
        mockMvc.perform(post("/api/batches/" + batchKey + "/yield/dispositions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"D-Z\",\"requestId\":\"REQ\"}"))
                .andExpect(status().isBadRequest());
        // 缺字段 / 空 requestId → 400
        mockMvc.perform(post("/api/batches/" + batchKey + "/yield/reconciliations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"\",\"inputQuantity\":1}"))
                .andExpect(status().isBadRequest());

        // 未提交核算的批次：current 404，history 200 空列表
        mockMvc.perform(get("/api/batches/" + batchKey + "/yield/current"))
                .andExpect(status().isNotFound());
        JsonNode history = history(batchKey, 200);
        assertTrue(history.path("versions").isEmpty());
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestId_acrossDifferentBatches_isScopedPerBatch() throws Exception {
        // requestId 作用域为批次内：不同批次复用同一 requestId 不应互相 409
        String batchA = createBatch("BK-Y-XA-", "0.500");
        String batchB = createBatch("BK-Y-XB-", "0.500");
        JsonNode a = reconcile(batchA, "SHARED-REQ", "100.000", "90.000", "8.000", "1.500", 201);
        JsonNode b = reconcile(batchB, "SHARED-REQ", "100.000", "90.000", "8.000", "1.000", 201);
        assertEquals("WITHIN_LIMIT", a.path("status").asText());
        assertEquals("PENDING_DISPOSITION", b.path("status").asText());
        // 批次内仍幂等：A 重放返回首次结果
        JsonNode replay = reconcile(batchA, "SHARED-REQ", "100.000", "90.000", "8.000", "1.500", 201);
        assertEquals(1, replay.path("version").asInt());
    }

    @Test
    void sameRequestId_replaysFirstDeviationAndStatus_changedQuantitiesConflict() throws Exception {
        String batchKey = createBatch("BK-Y-IDEM-", "0.500");
        String body = reconcileBody("REQ-IDEM", "100.000", "90.000", "8.000", "1.500");
        MvcResult first = mockMvc.perform(
                        post("/api/batches/" + batchKey + "/yield/reconciliations")
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(
                        post("/api/batches/" + batchKey + "/yield/reconciliations")
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM yield_reconciliation WHERE batch_key = ?", Integer.class, batchKey));

        // 同 requestId 换报废数量 → 409，不产生新版本
        mockMvc.perform(post("/api/batches/" + batchKey + "/yield/reconciliations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reconcileBody("REQ-IDEM", "100.000", "90.000", "8.000", "1.000")))
                .andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM yield_reconciliation WHERE batch_key = ?", Integer.class, batchKey));
    }

    @Test
    void dispositionAndRelease_commandKeyReplayAndConflict() throws Exception {
        String batchKey = createBatch("BK-Y-CMD-", "0.500");
        reconcile(batchKey, "REQ-OVER", "100.000", "90.000", "8.000", "1.000", 201);

        String dispositionBody = "{\"commandKey\":\"D-1\",\"requestId\":\"REQ-OVER\"}";
        MvcResult first = mockMvc.perform(post("/api/batches/" + batchKey + "/yield/dispositions")
                        .header("X-Actor-Id", "h-1")
                        .contentType(MediaType.APPLICATION_JSON).content(dispositionBody))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batchKey + "/yield/dispositions")
                        .header("X-Actor-Id", "h-1")
                        .contentType(MediaType.APPLICATION_JSON).content(dispositionBody))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 同 commandKey 换处置人 → 409
        mockMvc.perform(post("/api/batches/" + batchKey + "/yield/dispositions")
                        .header("X-Actor-Id", "h-9")
                        .contentType(MediaType.APPLICATION_JSON).content(dispositionBody))
                .andExpect(status().isConflict());

        dispose(batchKey, "h-2", "REQ-OVER", "D-2", 201);
        String releaseBody = "{\"commandKey\":\"REL-1\"}";
        MvcResult rel1 = mockMvc.perform(post("/api/batches/" + batchKey + "/yield/release")
                        .header("X-Actor-Id", "qa-1")
                        .contentType(MediaType.APPLICATION_JSON).content(releaseBody))
                .andExpect(status().isCreated()).andReturn();
        MvcResult relReplay = mockMvc.perform(post("/api/batches/" + batchKey + "/yield/release")
                        .header("X-Actor-Id", "qa-1")
                        .contentType(MediaType.APPLICATION_JSON).content(releaseBody))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(rel1.getResponse().getContentAsString(),
                relReplay.getResponse().getContentAsString());
        // 同 commandKey 换放行操作人 → 409
        mockMvc.perform(post("/api/batches/" + batchKey + "/yield/release")
                        .header("X-Actor-Id", "qa-2")
                        .contentType(MediaType.APPLICATION_JSON).content(releaseBody))
                .andExpect(status().isConflict());
        // 不同 commandKey 重复放行 → 200 既有裁决，仅一条放行记录
        mockMvc.perform(post("/api/batches/" + batchKey + "/yield/release")
                        .header("X-Actor-Id", "qa-2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"REL-2\"}"))
                .andExpect(status().isOk());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM yield_release WHERE batch_key = ?", Integer.class, batchKey));
    }

    // ---------- 历史与冻结上限 ----------

    @Test
    void history_keepsFrozenLimitAndHandlers_perVersion_notRewrittenWhenConfigChanges()
            throws Exception {
        String batchKey = createBatch("BK-Y-HIST-", "0.500");
        // v1：偏差1.0 超限，双人确认解除
        reconcile(batchKey, "REQ-V1", "100.000", "90.000", "8.000", "1.000", 201);
        dispose(batchKey, "h-1", "REQ-V1", "D-1", 201);
        dispose(batchKey, "h-2", "REQ-V1", "D-2", 201);
        assertEquals("DISPOSITION_CLEARED", current(batchKey, 200).path("status").asText());

        // 模拟冻结配置在库侧被调整为 0.100：历史版本不得回写
        jdbc.update("UPDATE batch SET tolerance_limit = 0.100 WHERE batch_key = ?", batchKey);

        JsonNode history = history(batchKey, 200);
        JsonNode v1 = history.path("versions").get(0);
        assertDecimal(v1, "toleranceLimit", "0.500");
        assertEquals("DISPOSITION_CLEARED", v1.path("status").asText());
        assertEquals(2, v1.path("dispositionHandlers").size());
        assertEquals("h-1", v1.path("dispositionHandlers").get(0).path("handlerId").asText());
        assertEquals("h-2", v1.path("dispositionHandlers").get(1).path("handlerId").asText());

        // 新核算版本按调整后冻结值 0.100 裁决：偏差0.5 > 0.1 → 超限
        JsonNode v2 = reconcile(batchKey, "REQ-V2", "100.000", "90.000", "8.000", "1.500", 201);
        assertDecimal(v2, "toleranceLimit", "0.100");
        assertEquals("PENDING_DISPOSITION", v2.path("status").asText());
        // v1 处置人不结转到 v2
        assertTrue(v2.path("dispositionHandlers").isEmpty());

        history = history(batchKey, 200);
        assertEquals(2, history.path("versions").size());
        assertDecimal(history.path("versions").get(0), "toleranceLimit", "0.500");
        assertDecimal(history.path("versions").get(1), "toleranceLimit", "0.100");
    }

    // ---------- 并发 ----------

    @Test
    void concurrentOverLimitReconciliationAndRelease_neverReleasesOverLimitBatch() throws Exception {
        String batchKey = createBatch("BK-Y-RACE-", "0.500");
        // v1 偏差0.5 恰好合格，门禁本可放行
        reconcile(batchKey, "REQ-V1", "100.000", "90.000", "8.000", "1.500", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/yield/reconciliations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reconcileBody("REQ-V2", "100.000", "90.000", "8.000", "1.000"))),
                () -> callStatus(post("/api/batches/" + batchKey + "/yield/release")
                        .header("X-Actor-Id", "qa-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"REL-1\"}"))
        );

        int reconcileCode = results.get(0).get(30, TimeUnit.SECONDS);
        int releaseCode = results.get(1).get(30, TimeUnit.SECONDS);
        Integer releaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yield_release WHERE batch_key = ?", Integer.class, batchKey);

        if (releaseCount > 0) {
            // 放行先提交：依据 v1 成功，随后超限新核算被拒，版本仍为 1
            assertEquals(201, releaseCode);
            assertEquals(409, reconcileCode);
            Integer releasedVersion = jdbc.queryForObject(
                    "SELECT version FROM yield_release WHERE batch_key = ?", Integer.class, batchKey);
            assertEquals(1, releasedVersion);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM yield_reconciliation WHERE batch_key = ?",
                    Integer.class, batchKey));
        } else {
            // 超限核算先提交：放行必须被 422 拒绝
            assertEquals(201, reconcileCode);
            assertEquals(422, releaseCode);
        }
        // 核心不变量：若存在放行记录，其依据版本绝不能是超限待处置
        Integer pendingReleased = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yield_release r JOIN yield_reconciliation c"
                        + " ON r.batch_key = c.batch_key AND r.version = c.version"
                        + " WHERE r.batch_key = ? AND c.status = 'PENDING_DISPOSITION'",
                Integer.class, batchKey);
        assertEquals(0, pendingReleased, "绝不允许超限待处置版本被放行");
    }

    @Test
    void concurrentSameRequestId_sameQuantities_bothReplaySingleVersion() throws Exception {
        String batchKey = createBatch("BK-Y-RR-", "0.500");
        String body = reconcileBody("REQ-RACE", "100.000", "90.000", "8.000", "1.500");
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/yield/reconciliations")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/" + batchKey + "/yield/reconciliations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM yield_reconciliation WHERE batch_key = ? AND request_id = 'REQ-RACE'",
                Integer.class, batchKey));
    }

    @Test
    void concurrentTwoDifferentHandlers_bothConfirm_gateCleared() throws Exception {
        String batchKey = createBatch("BK-Y-RD-", "0.500");
        reconcile(batchKey, "REQ-OVER", "100.000", "90.000", "8.000", "1.000", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/yield/dispositions")
                        .header("X-Actor-Id", "h-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"D-1\",\"requestId\":\"REQ-OVER\"}")),
                () -> callStatus(post("/api/batches/" + batchKey + "/yield/dispositions")
                        .header("X-Actor-Id", "h-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"D-2\",\"requestId\":\"REQ-OVER\"}"))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS));
        }
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM yield_disposition WHERE batch_key = ?", Integer.class, batchKey));
        assertEquals("DISPOSITION_CLEARED", current(batchKey, 200).path("status").asText());
        // 门禁解除后可放行
        release(batchKey, "qa-1", "REL-1", 201);
    }

    @Test
    void concurrentSameHandlerSameCommandKey_singleDispositionRow() throws Exception {
        String batchKey = createBatch("BK-Y-RS-", "0.500");
        reconcile(batchKey, "REQ-OVER", "100.000", "90.000", "8.000", "1.000", 201);

        String body = "{\"commandKey\":\"D-1\",\"requestId\":\"REQ-OVER\"}";
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/yield/dispositions")
                        .header("X-Actor-Id", "h-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/" + batchKey + "/yield/dispositions")
                        .header("X-Actor-Id", "h-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同人并发确认均重放首次结果");
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM yield_disposition WHERE batch_key = ? AND handler_id = 'h-1'",
                Integer.class, batchKey));
        assertEquals("PENDING_DISPOSITION", current(batchKey, 200).path("status").asText());
    }

    @Test
    void concurrentTwoDistinctReleases_onlyOneRowPersists() throws Exception {
        String batchKey = createBatch("BK-Y-RREL-", "0.500");
        reconcile(batchKey, "REQ-1", "100.000", "90.000", "8.000", "1.500", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/yield/release")
                        .header("X-Actor-Id", "qa-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"REL-1\"}")),
                () -> callStatus(post("/api/batches/" + batchKey + "/yield/release")
                        .header("X-Actor-Id", "qa-2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"REL-2\"}"))
        );
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : results) {
            codes.add(f.get(30, TimeUnit.SECONDS));
        }
        assertTrue(codes.contains(201));
        assertTrue(codes.contains(200), "后提交者读到既有放行裁决返回 200");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM yield_release WHERE batch_key = ?", Integer.class, batchKey));
    }

    // ---------- helpers ----------

    /**
     * 数值相等断言：JSON 树会规范化 BigDecimal 尾随零（0.500→0.5），故按 compareTo 判等；
     * 三位小数字面量另由原始响应字符串断言锁定。
     */
    private void assertDecimal(JsonNode node, String field, String expected) {
        assertEquals(0, node.path(field).decimalValue().compareTo(new BigDecimal(expected)),
                () -> field + " 期望 " + expected + " 实际 " + node.path(field).asText());
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String createBatch(String prefix, String toleranceLimit) throws Exception {
        String batchKey = prefix + unique();
        String body = "{\"commandKey\":\"CK-" + unique() + "\",\"batchKey\":\"" + batchKey + "\","
                + "\"productCode\":\"PROD-1\",\"batchNo\":\"LOT-1\","
                + "\"producedAt\":\"2026-01-02T03:04:05Z\",\"requiredTests\":[\"外观\"],"
                + "\"toleranceLimit\":" + toleranceLimit + "}";
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        return batchKey;
    }

    private String createBatchDefaultLimit(String prefix) throws Exception {
        String batchKey = prefix + unique();
        String body = "{\"commandKey\":\"CK-" + unique() + "\",\"batchKey\":\"" + batchKey + "\","
                + "\"productCode\":\"PROD-1\",\"batchNo\":\"LOT-1\","
                + "\"producedAt\":\"2026-01-02T03:04:05Z\",\"requiredTests\":[\"外观\"]}";
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        return batchKey;
    }

    private String reconcileBody(String requestId, String input, String qualified,
                                 String loss, String scrap) throws Exception {
        return objectMapper.writeValueAsString(new ReconcileCmd(requestId,
                new BigDecimal(input), new BigDecimal(qualified), new BigDecimal(loss),
                new BigDecimal(scrap)));
    }

    private record ReconcileCmd(String requestId, BigDecimal inputQuantity,
                                BigDecimal qualifiedQuantity, BigDecimal lossQuantity,
                                BigDecimal scrapQuantity) {
    }

    private JsonNode reconcile(String batchKey, String requestId, String input, String qualified,
                               String loss, String scrap, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/batches/" + batchKey + "/yield/reconciliations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reconcileBody(requestId, input, qualified, loss, scrap)))
                .andExpect(status().is(expectedStatus)).andReturn();
        if (expectedStatus >= 400) {
            return null;
        }
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode dispose(String batchKey, String handler, String requestId, String commandKey,
                             int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/batches/" + batchKey + "/yield/dispositions")
                                .header("X-Actor-Id", handler)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"commandKey\":\"" + commandKey
                                        + "\",\"requestId\":\"" + requestId + "\"}"))
                .andExpect(status().is(expectedStatus)).andReturn();
        if (expectedStatus >= 400) {
            return null;
        }
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode release(String batchKey, String actor, String commandKey, int expectedStatus)
            throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/batches/" + batchKey + "/yield/release")
                                .header("X-Actor-Id", actor)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expectedStatus)).andReturn();
        if (expectedStatus >= 400) {
            return null;
        }
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode current(String batchKey, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/yield/current"))
                .andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode history(String batchKey, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/yield/history"))
                .andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int callStatus(MockHttpServletRequestBuilder req) {
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

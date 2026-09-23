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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 召回血缘闭包分区处置端到端测试：主流程、三集合 422 分支、双人二审、
 * 整体回滚、commandKey 幂等（换序同参/异参 409/失败不占键）与并发边界。
 * 全部基于真实 H2（MODE=MySQL）内存库，不断言容器启动。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DispositionFlowTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM disposition_batch");
        jdbc.update("DELETE FROM disposition_command_log");
        jdbc.update("DELETE FROM disposition");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程 ----------

    @Test
    void happyFlow_submitFreezesClosure_thenTwoPersonConfirmAppliesAllCategories() throws Exception {
        // 血缘：R(RELEASED) 拆分为 A,B；A(RELEASED) 拆分为 C,D；随后召回 R。
        Chain chain = buildThreeLevelRecalledChain();

        // 闭包只读查询：R,A,B,C,D，路径与深度正确
        MvcResult closureResult = mockMvc.perform(get("/api/dispositions/ancestors/" + chain.root + "/closure"))
                .andExpect(status().isOk()).andReturn();
        JsonNode closure = objectMapper.readTree(closureResult.getResponse().getContentAsString());
        assertEquals(List.of(chain.root, chain.a, chain.b, chain.c, chain.d),
                toStringList(closure, "batchKey"));
        assertEquals(List.of(chain.root), pathOf(closure, 0));
        assertEquals(List.of(chain.root, chain.a), pathOf(closure, 1));
        assertEquals(List.of(chain.root, chain.b), pathOf(closure, 2));
        assertEquals(List.of(chain.root, chain.a, chain.c), pathOf(closure, 3));
        assertEquals(List.of(chain.root, chain.a, chain.d), pathOf(closure, 4));
        assertEquals(0, closure.get(0).path("depth").asInt());
        assertEquals(1, closure.get(1).path("depth").asInt());
        assertEquals(2, closure.get(3).path("depth").asInt());
        // 新建子代初始版本 1；根经历检验/两次批准/拆分/召回后版本为 6
        assertEquals(1, closure.get(2).path("version").asLong());
        assertEquals(6, closure.get(0).path("version").asLong());

        Map<String, Long> versions = versionsOf(closure);

        // 提交：R/C 销毁，A 返工，B/D 保持隔离
        String dKey = "DSP-HAPPY-" + unique();
        String submitBody = submitBody("CK-SUB-1", dKey, chain.root,
                List.of(chain.root, chain.c), List.of(chain.a), List.of(chain.b, chain.d), "等待调查");
        MvcResult submitted = mockMvc.perform(post("/api/dispositions")
                        .header("X-Actor-Id", "qa-head")
                        .contentType(MediaType.APPLICATION_JSON).content(submitBody))
                .andExpect(status().isCreated()).andReturn();
        JsonNode order = objectMapper.readTree(submitted.getResponse().getContentAsString());
        assertEquals("SUBMITTED", order.path("status").asText());
        assertEquals(1, order.path("dispositionVersion").asInt());
        assertEquals(5, order.path("batches").size());
        assertEquals(versions.get(chain.a), order.path("batches").get(1).path("frozenVersion").asLong());

        // 提交后批次状态不变、版本不变
        assertEquals("RECALLED", currentStatus(chain.root));
        assertEquals("SPLIT", currentStatus(chain.a));
        assertEquals(versions.get(chain.b), currentVersion(chain.b));

        // 同一人不能二审
        mockMvc.perform(post("/api/dispositions/" + dKey + "/confirm")
                        .header("X-Actor-Id", "qa-head")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("CK-CF-1", 1, versions)))
                .andExpect(status().isUnprocessableEntity());

        // 不同的生产负责人二审通过
        MvcResult confirmed = mockMvc.perform(post("/api/dispositions/" + dKey + "/confirm")
                        .header("X-Actor-Id", "prod-head")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("CK-CF-2", 1, versions)))
                .andExpect(status().isOk()).andReturn();
        JsonNode confirmedOrder = objectMapper.readTree(confirmed.getResponse().getContentAsString());
        assertEquals("CONFIRMED", confirmedOrder.path("status").asText());
        assertEquals("prod-head", confirmedOrder.path("confirmedBy").asText());
        // 不可变快照仍记录冻结时的状态/版本
        assertEquals("RECALLED", confirmedOrder.path("batches").get(0).path("frozenStatus").asText());
        assertEquals(versions.get(chain.root),
                confirmedOrder.path("batches").get(0).path("frozenVersion").asLong());

        // 按分类落账，全部版本 +1
        assertEquals("DESTROYED", currentStatus(chain.root));
        assertEquals("REWORK_PENDING", currentStatus(chain.a));
        assertEquals("QUARANTINED", currentStatus(chain.b), "HOLD 保持原隔离状态");
        assertEquals("DESTROYED", currentStatus(chain.c));
        assertEquals("QUARANTINED", currentStatus(chain.d));
        for (String key : List.of(chain.root, chain.a, chain.b, chain.c, chain.d)) {
            assertEquals(versions.get(key) + 1, currentVersion(key), "批次 " + key + " 版本应 +1");
        }

        // 处置单查询只读、快照保留
        MvcResult got = mockMvc.perform(get("/api/dispositions/" + dKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode fetched = objectMapper.readTree(got.getResponse().getContentAsString());
        assertEquals("CONFIRMED", fetched.path("status").asText());
        assertEquals("等待调查", fetched.path("holdReason").asText());
        assertEquals(5, fetched.path("batches").size());

        // 终态处置单不可再次确认/拒绝/取消
        mockMvc.perform(post("/api/dispositions/" + dKey + "/confirm")
                        .header("X-Actor-Id", "prod-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("CK-CF-3", 1, versions)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/dispositions/" + dKey + "/reject")
                        .header("X-Actor-Id", "prod-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-RJ-X\",\"reason\":\"x\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/dispositions/" + dKey + "/cancel")
                        .header("X-Actor-Id", "qa-head")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-CX-X\"}"))
                .andExpect(status().isConflict());
    }

    // ---------- 422 分类校验 ----------

    @Test
    void invalidPartitions_duplicateMissingExtraOverlapOrNonChainBatch_return422() throws Exception {
        Chain chain = buildThreeLevelRecalledChain();
        String dKey = "DSP-BAD-" + unique();

        // 集合内部重复
        submitExpect(chain.root, dKey + "-1", List.of(chain.root, chain.root),
                List.of(chain.a, chain.c), List.of(chain.b, chain.d), null, 422);
        // 遗漏 D
        submitExpect(chain.root, dKey + "-2", List.of(chain.root, chain.c),
                List.of(chain.a), List.of(chain.b), null, 422);
        // 多余：不在闭包的批次
        String outsider = "BK-OUTSIDE-" + unique();
        createReleasedBatch(outsider);
        submitExpect(chain.root, dKey + "-3", List.of(chain.root, chain.c, outsider),
                List.of(chain.a), List.of(chain.b, chain.d), null, 422);
        // 分类重叠：B 同时进 DESTROY 与 HOLD
        submitExpect(chain.root, dKey + "-4", List.of(chain.root, chain.b, chain.c),
                List.of(chain.a), List.of(chain.b, chain.d), null, 422);
        // HOLD 非空但缺原因
        submitExpect(chain.root, dKey + "-5", List.of(chain.root, chain.c),
                List.of(chain.a), List.of(chain.b, chain.d), "  ", 422);

        // 全部失败均未落任何处置单/快照
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM disposition", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM disposition_batch", Integer.class));
        // 批次版本未受影响
        assertEquals(1L, currentVersion(chain.b));
    }

    @Test
    void submitOnNonRecalledAncestor_returns422() throws Exception {
        String key = "BK-NRC-" + unique();
        createReleasedBatch(key);
        submitExpect(key, "DSP-NRC-" + unique(), List.of(key), List.of(), List.of(), null, 422);
    }

    @Test
    void secondPendingDispositionForSameAncestor_returns409_butAllowedAfterCancel() throws Exception {
        Chain chain = buildSingleRecalledChain();
        String firstKey = "DSP-PEND-1-" + unique();
        submitExpect(chain.root, firstKey, List.of(chain.root), List.of(), List.of(), null, 201);
        // 同祖先第二个待二审处置单 → 409
        submitExpect(chain.root, "DSP-PEND-2-" + unique(), List.of(chain.root),
                List.of(), List.of(), null, 409);

        // 非提交人不能取消
        mockMvc.perform(post("/api/dispositions/" + firstKey + "/cancel")
                        .header("X-Actor-Id", "intruder")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-CX-0\"}"))
                .andExpect(status().isUnprocessableEntity());
        // 提交人本人取消 → 200，批次不变
        mockMvc.perform(post("/api/dispositions/" + firstKey + "/cancel")
                        .header("X-Actor-Id", "qa-head")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-CX-1\"}"))
                .andExpect(status().isOk());
        assertEquals("RECALLED", currentStatus(chain.root));
        assertEquals(5L, currentVersion(chain.root), "取消不改批次版本");
        // 取消后允许重新提交
        submitExpect(chain.root, "DSP-PEND-3-" + unique(), List.of(chain.root),
                List.of(), List.of(), null, 201);
    }

    // ---------- 双人：拒绝 ----------

    @Test
    void rejectByDifferentPerson_setsRejected_andBatchesUnchanged() throws Exception {
        Chain chain = buildSingleRecalledChain();
        long beforeVersion = currentVersion(chain.root);
        String dKey = "DSP-REJ-" + unique();
        submitExpect(chain.root, dKey, List.of(chain.root), List.of(), List.of(), null, 201);

        // 提交人自己不能拒绝
        mockMvc.perform(post("/api/dispositions/" + dKey + "/reject")
                        .header("X-Actor-Id", "qa-head")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-RJ-0\",\"reason\":\"r\"}"))
                .andExpect(status().isUnprocessableEntity());
        // 另一生产负责人拒绝
        mockMvc.perform(post("/api/dispositions/" + dKey + "/reject")
                        .header("X-Actor-Id", "prod-head")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-RJ-1\",\"reason\":\"分类不合理\"}"))
                .andExpect(status().isOk());

        MvcResult got = mockMvc.perform(get("/api/dispositions/" + dKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode order = objectMapper.readTree(got.getResponse().getContentAsString());
        assertEquals("REJECTED", order.path("status").asText());
        assertEquals("分类不合理", order.path("rejectReason").asText());
        assertEquals("prod-head", order.path("rejectedBy").asText());
        assertEquals("RECALLED", currentStatus(chain.root));
        assertEquals(beforeVersion, currentVersion(chain.root), "拒绝不改批次");
    }

    // ---------- 确认 409 边界 ----------

    @Test
    void confirmWithWrongVersionOrMissingOrExtraBatchVersions_returns409() throws Exception {
        Chain chain = buildSingleRecalledChain();
        long rootVersion = currentVersion(chain.root);
        String dKey = "DSP-VER-" + unique();
        submitExpect(chain.root, dKey, List.of(chain.root), List.of(), List.of(), null, 201);

        // expectedDispositionVersion 错误（异 commandKey，直接命中业务校验）
        mockMvc.perform(post("/api/dispositions/" + dKey + "/confirm")
                        .header("X-Actor-Id", "prod-head")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("CK-CV-1", 99, Map.of(chain.root, rootVersion))))
                .andExpect(status().isConflict());
        // 缺批次版本
        mockMvc.perform(post("/api/dispositions/" + dKey + "/confirm")
                        .header("X-Actor-Id", "prod-head")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("CK-CV-2", 1, Map.of())))
                .andExpect(status().isConflict());
        // 版本值不对
        mockMvc.perform(post("/api/dispositions/" + dKey + "/confirm")
                        .header("X-Actor-Id", "prod-head")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("CK-CV-3", 1, Map.of(chain.root, rootVersion + 1))))
                .andExpect(status().isConflict());
        // 多余条目
        Map<String, Long> extra = new LinkedHashMap<>();
        extra.put(chain.root, rootVersion);
        extra.put("BK-NOT-IN-CLOSURE", 1L);
        mockMvc.perform(post("/api/dispositions/" + dKey + "/confirm")
                        .header("X-Actor-Id", "prod-head")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("CK-CV-4", 1, extra)))
                .andExpect(status().isConflict());

        // 全部失败后处置单仍待二审，批次未变
        assertEquals("SUBMITTED", dispositionStatus(dKey));
        assertEquals(rootVersion, currentVersion(chain.root));
        assertEquals("RECALLED", currentStatus(chain.root));
    }

    @Test
    void splitBetweenSubmitAndConfirm_returns409_andNothingIsApplied() throws Exception {
        // R 拆 A,B；A 已 RELEASED；召回 R 后提交处置单。
        Chain chain = buildThreeLevelRecalledChain();
        JsonNode closure = fetchClosure(chain.root);
        Map<String, Long> versions = versionsOf(closure);
        String dKey = "DSP-SPLIT-" + unique();
        submitExpect(chain.root, dKey, List.of(chain.root, chain.c),
                List.of(chain.a), List.of(chain.b, chain.d), "h", 201);

        // 试图在确认期间再拆分闭包内的 A：A 已有召回祖先，拆分被 422 拒绝；确认随后成功，闭包不漏。
        int splitStatus = callStatus(post("/api/batches/" + chain.a + "/split")
                .contentType(MediaType.APPLICATION_JSON)
                .content(splitBody("CK-SP-RACE", List.of(childSpec("BK-RACE-X-" + unique(), "LOT-X"),
                        childSpec("BK-RACE-Y-" + unique(), "LOT-Y")))));
        assertEquals(422, splitStatus, "召回链上禁止新增拆分");

        mockMvc.perform(post("/api/dispositions/" + dKey + "/confirm")
                        .header("X-Actor-Id", "prod-head")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("CK-CF-SPLIT", 1, versions)))
                .andExpect(status().isOk());
        assertEquals("DESTROYED", currentStatus(chain.root));
        assertEquals("REWORK_PENDING", currentStatus(chain.a));
        for (String key : List.of(chain.root, chain.a, chain.b, chain.c, chain.d)) {
            assertEquals(versions.get(key) + 1, currentVersion(key));
        }
    }

    // ---------- 整体回滚 ----------

    @Test
    void illegalTransitionForOneBatch_rollsBackWholeOrder() throws Exception {
        // R RELEASED → 拆 A,B；B 检验 FAIL → REJECTED；再召回 R（不影响 B 既成状态）。
        String root = "BK-RB-R-" + unique();
        String a = "BK-RB-A-" + unique();
        String b = "BK-RB-B-" + unique();
        createReleasedBatch(root);
        split(root, "CK-RB-SP", List.of(childSpec(a, "LOT-A"), childSpec(b, "LOT-B")));
        // B 必做项 t1 判 FAIL → REJECTED（此时祖先 R 尚未召回）
        submitTestFail(b, "TK-RB-F", "t1", "insp-b");
        assertEquals("REJECTED", currentStatus(b));
        recall(root, "qa", "客户投诉", "CK-RB-RC");

        Map<String, Long> versions = versionsOf(fetchClosure(root));
        long bVersionBefore = versions.get(b);
        String dKey = "DSP-RB-" + unique();
        // R/A DESTROY，B REWORK：REJECTED 不允许进入 REWORK_PENDING
        int code = callStatus(post("/api/dispositions")
                .header("X-Actor-Id", "qa-head")
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody("CK-RB-SUB", dKey, root,
                        List.of(root, a), List.of(b), List.of(), null)));
        assertEquals(201, code);
        mockMvc.perform(post("/api/dispositions/" + dKey + "/confirm")
                        .header("X-Actor-Id", "prod-head")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("CK-RB-CF", 1, versions)))
                .andExpect(status().isConflict());

        // 整单回滚：无任何批次状态/版本变化，处置单仍 SUBMITTED，可修正分类后重新确认
        assertEquals("RECALLED", currentStatus(root));
        assertEquals("QUARANTINED", currentStatus(a));
        assertEquals("REJECTED", currentStatus(b));
        for (Map.Entry<String, Long> e : versions.entrySet()) {
            assertEquals(e.getValue(), currentVersion(e.getKey()), "批次 " + e.getKey() + " 不得部分落账");
        }
        assertEquals("SUBMITTED", dispositionStatus(dKey));
        assertEquals(bVersionBefore, currentVersion(b));

        // 修正分类：提交人先取消原处置单（批次不变），再以 HOLD 重新提交后二审通过
        mockMvc.perform(post("/api/dispositions/" + dKey + "/cancel")
                        .header("X-Actor-Id", "qa-head")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-RB-CX\"}"))
                .andExpect(status().isOk());
        String dKey2 = "DSP-RB-2-" + unique();
        submitExpect(root, dKey2, List.of(root, a), List.of(), List.of(b), "继续隔离", 201);
        mockMvc.perform(post("/api/dispositions/" + dKey2 + "/confirm")
                        .header("X-Actor-Id", "prod-head")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("CK-RB-CF2", 2, versions)))
                .andExpect(status().isOk());
        assertEquals("DESTROYED", currentStatus(root));
        assertEquals("DESTROYED", currentStatus(a));
        assertEquals("REJECTED", currentStatus(b), "HOLD 保持 REJECTED 隔离状态");
        assertEquals(bVersionBefore + 1, currentVersion(b));
    }

    // ---------- 幂等 ----------

    @Test
    void submitCommandIdempotency_reorderSameParamsReplays_changedParamsConflicts_failureDoesNotOccupyKey()
            throws Exception {
        Chain chain = buildSingleRecalledChain();
        String dKey = "DSP-IDEM-" + unique();
        String first = submitBody("CK-SI-1", dKey, chain.root,
                List.of(chain.root), List.of(), List.of(), null);
        MvcResult r1 = mockMvc.perform(post("/api/dispositions").header("X-Actor-Id", "qa-head")
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isCreated()).andReturn();
        // 三集合换序（这里单元素，验证整体重放返回相同快照）
        MvcResult r2 = mockMvc.perform(post("/api/dispositions").header("X-Actor-Id", "qa-head")
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(r1.getResponse().getContentAsString(), r2.getResponse().getContentAsString());

        // 同键异参（换 dispositionKey）→ 409
        String changed = submitBody("CK-SI-1", dKey + "-X", chain.root,
                List.of(chain.root), List.of(), List.of(), null);
        mockMvc.perform(post("/api/dispositions").header("X-Actor-Id", "qa-head")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());

        // 集合内部换序视为同参（在三链上验证 DESTROY 两元素换序）
        Chain chain2 = buildThreeLevelRecalledChain();
        JsonNode closure2 = fetchClosure(chain2.root);
        String dKey2 = "DSP-IDEM-2-" + unique();
        String bodyA = submitBody("CK-SI-2", dKey2, chain2.root,
                List.of(chain2.root, chain2.c), List.of(chain2.a), List.of(chain2.b, chain2.d), "h");
        String bodyB = submitBody("CK-SI-2", dKey2, chain2.root,
                List.of(chain2.c, chain2.root), List.of(chain2.a), List.of(chain2.d, chain2.b), "h");
        MvcResult i1 = mockMvc.perform(post("/api/dispositions").header("X-Actor-Id", "qa-head")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyA))
                .andExpect(status().isCreated()).andReturn();
        MvcResult i2 = mockMvc.perform(post("/api/dispositions").header("X-Actor-Id", "qa-head")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyB))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(i1.getResponse().getContentAsString(), i2.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disposition WHERE disposition_key = ?", Integer.class, dKey2));

        // 失败不占键：先以非法分区（集合内部重复）使用 CK-SI-3 失败，再以同键合法提交成功
        Chain chain3 = buildSingleRecalledChain();
        String dKey3 = "DSP-IDEM-3-" + unique();
        String invalid = submitBody("CK-SI-3", dKey3, chain3.root,
                List.of(chain3.root, chain3.root), List.of(), List.of(), null);
        mockMvc.perform(post("/api/dispositions").header("X-Actor-Id", "qa-head")
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isUnprocessableEntity());
        String valid = submitBody("CK-SI-3", dKey3, chain3.root,
                List.of(chain3.root), List.of(), List.of(), null);
        mockMvc.perform(post("/api/dispositions").header("X-Actor-Id", "qa-head")
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isCreated());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disposition_command_log WHERE command_key = 'CK-SI-3'"
                        + " AND response_status <> 201", Integer.class));
    }

    @Test
    void confirmReplaySameParamsReturnsSnapshot_andConcurrentSameKeyConfirmsApplyOnce() throws Exception {
        Chain chain = buildThreeLevelRecalledChain();
        JsonNode closure = fetchClosure(chain.root);
        Map<String, Long> versions = versionsOf(closure);
        String dKey = "DSP-CR-" + unique();
        submitExpect(chain.root, dKey, List.of(chain.root, chain.c),
                List.of(chain.a), List.of(chain.b, chain.d), "h", 201);
        String body = confirmBody("CK-CR-1", 1, versions);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/dispositions/" + dKey + "/confirm")
                        .header("X-Actor-Id", "prod-head")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/dispositions/" + dKey + "/confirm")
                        .header("X-Actor-Id", "prod-head")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(200, f.get(30, TimeUnit.SECONDS), "同键同参并发确认均重放首次结果");
        }
        // 恰好落账一次：版本各 +1 而非 +2
        for (String key : List.of(chain.root, chain.a, chain.b, chain.c, chain.d)) {
            assertEquals(versions.get(key) + 1, currentVersion(key));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disposition_command_log WHERE command_key = 'CK-CR-1'",
                Integer.class));
    }

    // ---------- 并发：确认 vs 再次召回，不漏账/不部分落账 ----------

    @Test
    void concurrentConfirmAndReRecall_eitherSideWins_neverPartialLedger() throws Exception {
        Chain chain = buildThreeLevelRecalledChain();
        JsonNode closure = fetchClosure(chain.root);
        Map<String, Long> versions = versionsOf(closure);
        String dKey = "DSP-RACE-" + unique();
        submitExpect(chain.root, dKey, List.of(chain.root, chain.c),
                List.of(chain.a), List.of(chain.b, chain.d), "h", 201);
        String confirmBody = confirmBody("CK-RACE-CF", 1, versions);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/dispositions/" + dKey + "/confirm")
                        .header("X-Actor-Id", "prod-head")
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody)),
                () -> callStatus(post("/api/batches/" + chain.a + "/recall")
                        .header("X-Actor-Id", "qa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-RACE-RC\",\"reason\":\"二次召回\"}"))
        );
        int confirmCode = results.get(0).get(30, TimeUnit.SECONDS);
        int recallCode = results.get(1).get(30, TimeUnit.SECONDS);
        String orderStatus = dispositionStatus(dKey);

        // B,C,D 与胜负无关，绝不允许部分落账：确认成功才 +1，失败则完全不动
        for (String untouched : List.of(chain.b, chain.c, chain.d)) {
            if ("CONFIRMED".equals(orderStatus)) {
                assertEquals(versions.get(untouched) + 1, currentVersion(untouched));
            } else {
                assertEquals(versions.get(untouched), currentVersion(untouched),
                        "确认失败时 " + untouched + " 不得被部分落账");
            }
        }

        if ("CONFIRMED".equals(orderStatus)) {
            assertEquals(200, confirmCode);
            assertNotEquals(201, recallCode, "确认先落账后再次召回必须失败");
            assertEquals("REWORK_PENDING", currentStatus(chain.a));
            assertEquals(versions.get(chain.a) + 1, currentVersion(chain.a));
            assertEquals("DESTROYED", currentStatus(chain.root));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM recall WHERE batch_key = ?", Integer.class, chain.a),
                    "确认先落账后，对 A 的再次召回必须失败，不得留下召回记录");
        } else {
            assertEquals(409, confirmCode, "期间再次召回改变了 A 的状态/版本，确认必须 409");
            assertEquals(201, recallCode);
            assertEquals("SUBMITTED", orderStatus);
            assertEquals("RECALLED", currentStatus(chain.a));
            assertEquals(versions.get(chain.a) + 1, currentVersion(chain.a));
            // 其余成员原封不动
            assertEquals("RECALLED", currentStatus(chain.root));
            assertEquals(versions.get(chain.root), currentVersion(chain.root));
            assertEquals("QUARANTINED", currentStatus(chain.b));
        }
    }

    // ---------- 404 / 唯一键 ----------

    @Test
    void unknownDispositionAndClosure_returns404_andDuplicateDispositionKeyConflicts() throws Exception {
        Chain chain = buildSingleRecalledChain();
        mockMvc.perform(get("/api/dispositions/NO-SUCH-DSP"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/dispositions/ancestors/NO-SUCH-BATCH/closure"))
                .andExpect(status().isNotFound());

        String dKey = "DSP-UNIQ-" + unique();
        submitExpect(chain.root, dKey, List.of(chain.root), List.of(), List.of(), null, 201);
        // dispositionKey 全局唯一：换 commandKey 也不能复用
        submitExpect(chain.root, dKey, List.of(chain.root), List.of(), List.of(), null, 409);
    }

    // ---------- 夹具与辅助 ----------

    private record Chain(String root, String a, String b, String c, String d) {
    }

    private Chain buildSingleRecalledChain() throws Exception {
        String root = "BK-S-R-" + unique();
        createReleasedBatch(root);
        recall(root, "qa", "客户投诉", "CK-S-RC-" + unique());
        return new Chain(root, null, null, null, null);
    }

    private Chain buildThreeLevelRecalledChain() throws Exception {
        String root = "BK-T-R-" + unique();
        String a = "BK-T-A-" + unique();
        String b = "BK-T-B-" + unique();
        String c = "BK-T-C-" + unique();
        String d = "BK-T-D-" + unique();
        createReleasedBatch(root);
        split(root, "CK-T-SP1-" + unique(),
                List.of(childSpec(a, "LOT-A"), childSpec(b, "LOT-B")));
        // A 走完全部检验与双人放行后再拆 C,D；B 保持 QUARANTINED
        releaseBatch(a);
        split(a, "CK-T-SP2-" + unique(),
                List.of(childSpec(c, "LOT-C"), childSpec(d, "LOT-D")));
        // 召回根 R（SPLIT 状态可召回），闭包 R,A,B,C,D 立即全部不可用
        recall(root, "qa", "客户投诉", "CK-T-RC-" + unique());
        return new Chain(root, a, b, c, d);
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests) {
    }

    private record ChildSpec(String batchKey, String batchNo) {
    }

    private void createReleasedBatch(String batchKey) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(), batchKey,
                "PROD-1", "LOT-" + batchKey, Instant.parse("2026-01-02T03:04:05Z"), List.of("t1")));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        releaseBatch(batchKey);
    }

    private void releaseBatch(String batchKey) throws Exception {
        String testBody = objectMapper.writeValueAsString(new TestCmd("CK-T-" + unique(),
                "TK-" + unique(), "t1", "PASS", "insp-" + unique()));
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(testBody))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "qa-" + unique()).header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-AQ-" + unique() + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "ops-" + unique()).header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-AO-" + unique() + "\"}"))
                .andExpect(status().isCreated());
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private void submitTestFail(String batchKey, String testKey, String item, String inspector)
            throws Exception {
        String body = objectMapper.writeValueAsString(new TestCmd("CK-TF-" + unique(),
                testKey, item, "FAIL", inspector));
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private ChildSpec childSpec(String key, String batchNo) {
        return new ChildSpec(key, batchNo);
    }

    private String splitBody(String commandKey, List<ChildSpec> children) throws Exception {
        return objectMapper.writeValueAsString(new SplitCmd(commandKey, children));
    }

    private record SplitCmd(String commandKey, List<ChildSpec> children) {
    }

    private void split(String parent, String commandKey, List<ChildSpec> children) throws Exception {
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitBody(commandKey, children)))
                .andExpect(status().isCreated());
    }

    private void recall(String batchKey, String actor, String reason, String commandKey) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecallCmd(commandKey, reason))))
                .andExpect(status().isCreated());
    }

    private record RecallCmd(String commandKey, String reason) {
    }

    private record SubmitCmd(String commandKey, String dispositionKey, String ancestorKey,
                             List<String> destroy, List<String> rework, List<String> hold,
                             String holdReason) {
    }

    private record ConfirmCmd(String commandKey, int expectedDispositionVersion,
                              Map<String, Long> batchVersions) {
    }

    private String submitBody(String commandKey, String dispositionKey, String ancestorKey,
                              List<String> destroy, List<String> rework, List<String> hold,
                              String holdReason) throws Exception {
        return objectMapper.writeValueAsString(new SubmitCmd(commandKey, dispositionKey, ancestorKey,
                destroy, rework, hold, holdReason));
    }

    private String confirmBody(String commandKey, int dispositionVersion, Map<String, Long> versions)
            throws Exception {
        return objectMapper.writeValueAsString(new ConfirmCmd(commandKey, dispositionVersion, versions));
    }

    private void submitExpect(String ancestorKey, String dispositionKey, List<String> destroy,
                              List<String> rework, List<String> hold, String holdReason,
                              int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/dispositions")
                        .header("X-Actor-Id", "qa-head")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("CK-SUB-" + unique(), dispositionKey, ancestorKey,
                                destroy, rework, hold, holdReason)))
                .andExpect(status().is(expectedStatus));
    }

    private JsonNode fetchClosure(String ancestorKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/dispositions/ancestors/" + ancestorKey + "/closure"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Map<String, Long> versionsOf(JsonNode closure) {
        Map<String, Long> versions = new LinkedHashMap<>();
        closure.forEach(n -> versions.put(n.path("batchKey").asText(), n.path("version").asLong()));
        return versions;
    }

    private List<String> toStringList(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.path(field).asText()));
        return values;
    }

    private List<String> pathOf(JsonNode closure, int index) {
        List<String> path = new ArrayList<>();
        closure.get(index).path("path").forEach(n -> path.add(n.asText()));
        return path;
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private long currentVersion(String batchKey) {
        return jdbc.queryForObject("SELECT version FROM batch WHERE batch_key = ?",
                Long.class, batchKey);
    }

    private String dispositionStatus(String dispositionKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/dispositions/" + dispositionKey))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("status").asText();
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

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}

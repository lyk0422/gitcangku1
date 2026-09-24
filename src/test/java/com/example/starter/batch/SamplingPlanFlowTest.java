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
 * 抽样检验计划端到端测试（真实 H2 MySQL 兼容内存库）：
 * 加权计数、登记与判定原子性、批准门禁、计划数量限制、召回拦截、子批隔离、幂等与并发裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SamplingPlanFlowTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM sample_record");
        jdbc.update("DELETE FROM sampling_plan");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 加权计数与 ACCEPTED 主流程 ----------

    @Test
    void weightedDefects_minorZero_majorOne_criticalThree_acceptedWhenWithinAc() throws Exception {
        String batchKey = "SP-ACC-" + unique();
        createBatch(batchKey, List.of("外观"), 201);
        // n=5, Ac=1, Re=2：只有 MAJOR(1) 可在接收限内，CRITICAL 直接拒收
        String planKey = createPlan(batchKey, "PLAN-ACC-" + unique(), 5, 1, 2, "GB/T 2828.1", 201);

        assertRegistration(planKey, 1, "QUALIFIED", "外观完好", 0, "OPEN", 0);
        assertRegistration(planKey, 2, "MINOR", "轻微划痕", 0, "OPEN", 0);
        assertRegistration(planKey, 3, "MAJOR", "标签歪斜", 1, "OPEN", 1);
        assertRegistration(planKey, 4, "QUALIFIED", "合格", 0, "OPEN", 1);
        // 最后一件 MINOR=0：登记完成，累计 1≤Ac=1 → ACCEPTED
        MvcResult last = register(planKey, 5, "MINOR", "小污点", 201);
        JsonNode body = objectMapper.readTree(last.getResponse().getContentAsString());
        assertEquals("ACCEPTED", body.path("planStatus").asText());
        assertEquals(1, body.path("registeredWeightedDefects").asInt());

        JsonNode plan = planJson(planKey);
        assertEquals("ACCEPTED", plan.path("status").asText());
        assertEquals(1, plan.path("weightedDefects").asInt());
        assertEquals(5, plan.path("registeredCount").asInt());
        assertFalse(plan.path("decidedAt").isNull(), "判定时刻必须落库");

        // 落库计数与响应一致，未重复累加
        Integer weighted = jdbc.queryForObject(
                "SELECT weighted_defects FROM sampling_plan WHERE plan_key = ?",
                Integer.class, planKey);
        assertEquals(1, weighted);
    }

    @Test
    void minorDefectsOnly_acceptedWithAcZero() throws Exception {
        String batchKey = "SP-MINOR-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        String planKey = createPlan(batchKey, "PLAN-MINOR-" + unique(), 2, 0, 1, "内控标准", 201);
        // MINOR 记 0：两件 MINOR 后累计 0≤Ac=0 → ACCEPTED
        assertRegistration(planKey, 1, "MINOR", "轻微毛边", 0, "OPEN", 0);
        MvcResult last = register(planKey, 2, "MINOR", "轻微色差", 201);
        assertEquals("ACCEPTED", objectMapper.readTree(last.getResponse().getContentAsString())
                .path("planStatus").asText());
    }

    // ---------- REJECTED 原子判定 ----------

    @Test
    void reachingRe_rejectsAtomicallyInSameTransaction_andBlocksFurtherRegistration() throws Exception {
        String batchKey = "SP-REJ-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        // n=5, Ac=0, Re=3
        String planKey = createPlan(batchKey, "PLAN-REJ-" + unique(), 5, 0, 3, "GB/T 2828.1", 201);
        assertRegistration(planKey, 1, "MAJOR", "主要缺陷1", 1, "OPEN", 1);
        // CRITICAL=3：累计 1+3=4≥Re=3，当件同事务判定 REJECTED
        MvcResult rejecting = register(planKey, 2, "CRITICAL", "致命裂纹", 201);
        JsonNode node = objectMapper.readTree(rejecting.getResponse().getContentAsString());
        assertEquals("REJECTED", node.path("planStatus").asText());
        assertEquals(4, node.path("registeredWeightedDefects").asInt());

        JsonNode plan = planJson(planKey);
        assertEquals("REJECTED", plan.path("status").asText());
        assertEquals(4, plan.path("weightedDefects").asInt());
        assertEquals(2, plan.path("registeredCount").asInt());
        assertFalse(plan.path("decidedAt").isNull());

        // 计划终结后再登记 → 409
        register(planKey, 3, "QUALIFIED", "补充登记", 409);
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_record WHERE plan_key = ?", Integer.class, planKey));
    }

    @Test
    void allSamplesRegistered_aboveAcButBelowRe_failClosedRejected() throws Exception {
        String batchKey = "SP-FC-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        // n=3, Ac=0, Re=3：两件 MAJOR 累计 2，最后一件合格；登记完成时 Ac<累计<Re，不能放行
        String planKey = createPlan(batchKey, "PLAN-FC-" + unique(), 3, 0, 3, "内控标准", 201);
        assertRegistration(planKey, 1, "MAJOR", "d1", 1, "OPEN", 1);
        assertRegistration(planKey, 2, "MAJOR", "d2", 1, "OPEN", 2);
        MvcResult last = register(planKey, 3, "QUALIFIED", "ok", 201);
        assertEquals("REJECTED", objectMapper.readTree(last.getResponse().getContentAsString())
                .path("planStatus").asText());
    }

    // ---------- 批准门禁 ----------

    @Test
    void acceptedPlanIsPrerequisiteForRelease_rejectedOrOpenOrMissingBlocks422() throws Exception {
        // 无计划：必做检验通过后批准 → 422，消息指明无计划
        String none = "SP-GATE-NONE-" + unique();
        createBatch(none, List.of("t1"), 201);
        submitTest(none, "t1", "PASS", "insp", 201);
        MvcResult noPlan = approve(none, "qa", "QUALITY", "CK-G0", 422);
        assertTrue(objectMapper.readTree(noPlan.getResponse().getContentAsString())
                .path("message").asText().contains("无抽样计划"));

        // OPEN 计划：登记一件不终结，批准 → 422，消息指明 OPEN（门禁先于必做检验检查）
        String open = "SP-GATE-OPEN-" + unique();
        createBatch(open, List.of("t1"), 201);
        String openPlan = createPlan(open, "PLAN-OPEN-" + unique(), 3, 0, 2, "std", 201);
        register(openPlan, 1, "QUALIFIED", "ok", 201);
        MvcResult openResp = approve(open, "qa", "QUALITY", "CK-G1", 422);
        assertTrue(objectMapper.readTree(openResp.getResponse().getContentAsString())
                .path("message").asText().contains("OPEN"));
        submitTest(open, "t1", "PASS", "insp", 201);

        // REJECTED 计划：批次仍隔离，批准 → 422，消息指明 REJECTED
        String rejected = "SP-GATE-REJ-" + unique();
        createBatch(rejected, List.of("t1"), 201);
        String rejPlan = createPlan(rejected, "PLAN-R-" + unique(), 2, 0, 1, "std", 201);
        register(rejPlan, 1, "MAJOR", "d", 201);
        MvcResult rejResp = approve(rejected, "qa", "QUALITY", "CK-G2", 422);
        assertTrue(objectMapper.readTree(rejResp.getResponse().getContentAsString())
                .path("message").asText().contains("REJECTED"));

        // REJECTED 后可在仍隔离阶段新建下一计划；ACCEPTED 后再提交必做检验即可双角色批准放行
        String accepted = createPlan(rejected, "PLAN-A-" + unique(), 1, 0, 1, "std-2", 201);
        register(accepted, 1, "QUALIFIED", "ok", 201);
        submitTest(rejected, "t1", "PASS", "insp", 201);
        approve(rejected, "qa", "QUALITY", "CK-G3", 201);
        approve(rejected, "ops", "OPERATIONS", "CK-G4", 201);
        assertEquals("RELEASED", currentStatus(rejected));
    }

    @Test
    void maxThreeRejectedPlans_fourthRejected422() throws Exception {
        String batchKey = "SP-MAX-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        for (int i = 1; i <= 3; i++) {
            String planKey = createPlan(batchKey, "PLAN-MAX-" + i + "-" + unique(), 1, 0, 1,
                    "std", 201);
            register(planKey, 1, "MAJOR", "d" + i, 201);
            assertEquals("REJECTED", planJson(planKey).path("status").asText());
        }
        // 第四个计划 → 422，失败不占键
        createPlan(batchKey, "PLAN-MAX-4-" + unique(), 1, 0, 1, "std", 422);
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sampling_plan WHERE batch_key = ?", Integer.class, batchKey));
    }

    // ---------- 创建失败分支 ----------

    @Test
    void createPlan_conflictsAndValidation() throws Exception {
        String batchKey = "SP-CREATE-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        String planKey = "PLAN-DUP-" + unique();
        createPlan(batchKey, planKey, 2, 0, 1, "std", 201);

        // 同批次存在 OPEN 计划 → 409
        createPlan(batchKey, "PLAN-OTHER-" + unique(), 2, 0, 1, "std", 409);
        // planKey 全局唯一：另一批次复用 → 409
        String another = "SP-CREATE2-" + unique();
        createBatch(another, List.of("t1"), 201);
        createPlan(another, planKey, 2, 0, 1, "std", 409);

        // 参数约束 0≤Ac<Re≤样本量 → 400
        createPlan(batchKey, "PLAN-BAD1-" + unique(), 2, 1, 1, "std", 400);
        createPlan(batchKey, "PLAN-BAD2-" + unique(), 2, 0, 3, "std", 400);
        createPlanRaw(batchKey, planBody("CK-BAD3", "PLAN-BAD3-" + unique(), 0, 0, 1, "std"), 400);
        createPlanRaw(batchKey, planBody("CK-BAD4", "PLAN-BAD4-" + unique(), 201, 0, 1, "std"), 400);
        // basis 为空 → 400
        createPlanRaw(batchKey, planBody("CK-BAD5", "PLAN-BAD5-" + unique(), 2, 0, 1, ""), 400);

        // 非隔离状态：检验全部 PASS 进入 PENDING_RELEASE 后创建 → 409
        submitTest(batchKey, "t1", "PASS", "insp", 201);
        createPlan(batchKey, "PLAN-LATE-" + unique(), 2, 0, 1, "std", 409);

        // 不存在批次 → 404
        createPlan("NO-SUCH-BATCH", "PLAN-404-" + unique(), 2, 0, 1, "std", 404);
    }

    @Test
    void createPlanOnRecalledBatch_returns422() throws Exception {
        String batchKey = "SP-RC-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        // 先建一个可接收计划并放行，再召回
        String planKey = createPlan(batchKey, "PLAN-RC-" + unique(), 1, 0, 1, "std", 201);
        register(planKey, 1, "QUALIFIED", "ok", 201);
        submitTest(batchKey, "t1", "PASS", "insp", 201);
        approve(batchKey, "qa", "QUALITY", "CK-RC-1", 201);
        approve(batchKey, "ops", "OPERATIONS", "CK-RC-2", 201);
        recall(batchKey, "u", "客户投诉", "CK-RC-3", 201);
        // 已召回批次不得创建计划 → 422
        createPlan(batchKey, "PLAN-AFTER-RC-" + unique(), 1, 0, 1, "std", 422);
    }

    // ---------- 登记失败分支 ----------

    @Test
    void registerSample_indexRange_duplicate_unknownPlan_unknownResult() throws Exception {
        String batchKey = "SP-REG-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        String planKey = createPlan(batchKey, "PLAN-REG-" + unique(), 3, 0, 2, "std", 201);

        register(planKey, 0, "QUALIFIED", "bad", 400);
        register(planKey, 4, "QUALIFIED", "bad", 400);
        register(planKey, 1, "QUALIFIED", "ok", 201);
        // 同序号重复 → 409
        register(planKey, 1, "QUALIFIED", "dup", 409);
        // 空描述 → 400
        registerRaw(planKey, sampleBody("CK-D", 2, "QUALIFIED", ""), 400);
        // 非法结果枚举 → 400
        registerRaw(planKey, "{\"commandKey\":\"CK-E\",\"sampleIndex\":2,"
                + "\"result\":\"BROKEN\",\"description\":\"x\"}", 400);
        // 不存在的计划 → 404
        register("NO-SUCH-PLAN", 1, "QUALIFIED", "x", 404);

        // 失败不占键：CK-D 曾以空描述 400，同键合法参数应成功
        registerRaw(planKey, sampleBody("CK-D", 2, "QUALIFIED", "second"), 201);
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_record WHERE plan_key = ?", Integer.class, planKey));
    }

    // ---------- 幂等 ----------

    @Test
    void commandKey_sameParamsReplays_changedParamsConflicts_failureDoesNotOccupy() throws Exception {
        String batchKey = "SP-IDEM-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        String planKey = "PLAN-IDEM-" + unique();
        String planBody = planBody("CK-P1", planKey, 2, 0, 1, "std");
        MvcResult first = mockMvc.perform(post("/api/batches/" + batchKey + "/sampling-plans")
                        .contentType(MediaType.APPLICATION_JSON).content(planBody))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batchKey + "/sampling-plans")
                        .contentType(MediaType.APPLICATION_JSON).content(planBody))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 同键改参（Ac 不同）→ 409
        mockMvc.perform(post("/api/batches/" + batchKey + "/sampling-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody("CK-P1", "PLAN-IDEM-X-" + unique(), 2, 0, 1, "std-changed")))
                .andExpect(status().isConflict());

        // 登记同键同参重放首次快照；同键改描述 → 409
        String sampleBody = sampleBody("CK-S1", 1, "MAJOR", "原始描述");
        MvcResult sFirst = mockMvc.perform(post("/api/sampling-plans/" + planKey + "/samples")
                        .contentType(MediaType.APPLICATION_JSON).content(sampleBody))
                .andExpect(status().isCreated()).andReturn();
        MvcResult sReplay = mockMvc.perform(post("/api/sampling-plans/" + planKey + "/samples")
                        .contentType(MediaType.APPLICATION_JSON).content(sampleBody))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(sFirst.getResponse().getContentAsString(),
                sReplay.getResponse().getContentAsString());
        mockMvc.perform(post("/api/sampling-plans/" + planKey + "/samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sampleBody("CK-S1", 2, "QUALIFIED", "换了参数")))
                .andExpect(status().isConflict());
        // 重放未多写记录
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_record WHERE plan_key = ?", Integer.class, planKey));
    }

    // ---------- 并发：登记最多一条判定，计数不重不累 ----------

    @Test
    void concurrentRegistrations_atMostOneDecision_countsNeverLostOrDoubled() throws Exception {
        String batchKey = "SP-CONC-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        // n=5, Ac=0, Re=3：权重 1+1+3=5 必然在某次登记后越过 Re
        String planKey = createPlan(batchKey, "PLAN-CONC-" + unique(), 5, 0, 3, "std", 201);
        String[][] specs = {
                {"1", "MAJOR", "d1"}, {"2", "MAJOR", "d2"}, {"3", "CRITICAL", "d3"},
                {"4", "QUALIFIED", "d4"}, {"5", "QUALIFIED", "d5"}
        };

        List<Future<Integer>> results = runConcurrent(() -> registerStatus(planKey, specs[0]),
                () -> registerStatus(planKey, specs[1]),
                () -> registerStatus(planKey, specs[2]),
                () -> registerStatus(planKey, specs[3]),
                () -> registerStatus(planKey, specs[4]));

        int success = 0;
        int conflict = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            assertTrue(code == 201 || code == 409, "只允许 201 或终结冲突 409，实际: " + code);
            if (code == 201) {
                success++;
            } else {
                conflict++;
            }
        }

        // 唯一一条判定：终结时刻后到的登记全部 409；成功数=已登记件数
        Integer registeredCount = jdbc.queryForObject(
                "SELECT registered_count FROM sampling_plan WHERE plan_key = ?",
                Integer.class, planKey);
        Integer storedSamples = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_record WHERE plan_key = ?", Integer.class, planKey);
        assertEquals(success, registeredCount);
        assertEquals(success, storedSamples);

        // 加权计数等于所有已落库样本权重之和（不重不累）
        Integer sumWeights = jdbc.queryForObject(
                "SELECT COALESCE(SUM(weight),0) FROM sample_record WHERE plan_key = ?",
                Integer.class, planKey);
        Integer weighted = jdbc.queryForObject(
                "SELECT weighted_defects FROM sampling_plan WHERE plan_key = ?",
                Integer.class, planKey);
        assertEquals(sumWeights, weighted);
        assertTrue(weighted >= 3, "至少 CRITICAL 落库后应达 Re");
        assertEquals("REJECTED", planJson(planKey).path("status").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sampling_plan WHERE plan_key = ? AND decided_at IS NOT NULL",
                Integer.class, planKey));
    }

    // ---------- 召回并发裁决 ----------

    @Test
    void ancestorRecallCommittedFirst_blocksRegistrationAndPlanCreation422() throws Exception {
        // root 放行→拆分；child 隔离中持有 OPEN 计划；召回 root 后 child 登记与新建计划均 422
        String root = "SP-AR-ROOT-" + unique();
        createBatch(root, List.of("t1"), 201);
        releaseBatch(root, "insp-1");
        String child = "SP-AR-CHILD-" + unique();
        split(root, "CK-AR-SPLIT", List.of(new String[]{child, "L1"},
                new String[]{"SP-AR-C2-" + unique(), "L2"}), 201);

        String childPlan = createPlan(child, "PLAN-AR-" + unique(), 3, 0, 2, "std", 201);
        register(childPlan, 1, "QUALIFIED", "before recall", 201);

        recall(root, "u", "原料污染", "CK-AR-RC", 201);

        // 祖先召回后：登记与判定 422（计划仍 OPEN，未被改写）
        register(childPlan, 2, "MAJOR", "after recall", 422);
        assertEquals("OPEN", planJson(childPlan).path("status").asText());
        // 新建计划 422
        createPlan(child, "PLAN-AR-2-" + unique(), 2, 0, 1, "std", 422);
        // 已落库的第一件登记保留
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_record WHERE plan_key = ?", Integer.class, childPlan));
    }

    @Test
    void concurrentRegistrationAndAncestorRecall_commitOrderDecides() throws Exception {
        String root = "SP-CR-ROOT-" + unique();
        createBatch(root, List.of("t1"), 201);
        releaseBatch(root, "insp-1");
        String child = "SP-CR-CHILD-" + unique();
        split(root, "CK-CR-SPLIT", List.of(new String[]{child, "L1"},
                new String[]{"SP-CR-C2-" + unique(), "L2"}), 201);
        // n=1, Ac=0, Re=1：唯一一件 MAJOR 登记成功即 REJECTED
        String childPlan = createPlan(child, "PLAN-CR-" + unique(), 1, 0, 1, "std", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> registerStatus(childPlan,
                        new String[]{"1", "MAJOR", "race"}),
                () -> {
                    try {
                        return callStatus(post("/api/batches/" + root + "/recall")
                                .header("X-Actor-Id", "u")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"commandKey\":\"CK-CR-RC\",\"reason\":\"根批召回\"}"));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        int registration = results.get(0).get(30, TimeUnit.SECONDS);
        int recall = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, recall, "SPLIT 根批始终可召回");
        if (registration == 201) {
            // 登记先提交：判定已落库，历史不可改写
            assertEquals("REJECTED", planJson(childPlan).path("status").asText());
            assertFalse(planJson(childPlan).path("decidedAt").isNull());
        } else {
            // 召回先提交：登记与判定被拦截 422，计划保持 OPEN 无判定时刻
            assertEquals(422, registration);
            assertEquals("OPEN", planJson(childPlan).path("status").asText());
            assertTrue(planJson(childPlan).path("decidedAt").isNull());
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sample_record WHERE plan_key = ?",
                    Integer.class, childPlan));
        }
    }

    // ---------- 查询与历史不可改写、子批不继承 ----------

    @Test
    void planDetail_samplesAndBatchDecisionHistory_immutableAndSorted() throws Exception {
        String batchKey = "SP-HIS-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        String plan1 = createPlan(batchKey, "PLAN-HIS-1-" + unique(), 2, 0, 1, "std1", 201);
        register(plan1, 2, "QUALIFIED", "先登记序号2", 201);
        register(plan1, 1, "MAJOR", "后登记序号1，达 Re 判定", 201);
        String decidedAt1 = planJson(plan1).path("decidedAt").asText();

        String plan2 = createPlan(batchKey, "PLAN-HIS-2-" + unique(), 1, 0, 1, "std2", 201);
        register(plan2, 1, "QUALIFIED", "复检合格", 201);

        // 批次判定历史：按序号升序，两个计划状态与判定时刻各自保留
        MvcResult plansResult = mockMvc.perform(get("/api/batches/" + batchKey + "/sampling-plans"))
                .andExpect(status().isOk()).andReturn();
        JsonNode plans = objectMapper.readTree(plansResult.getResponse().getContentAsString());
        assertEquals(2, plans.size());
        assertEquals(1, plans.get(0).path("seq").asInt());
        assertEquals("REJECTED", plans.get(0).path("status").asText());
        assertEquals(decidedAt1, plans.get(0).path("decidedAt").asText());
        assertEquals(2, plans.get(1).path("seq").asInt());
        assertEquals("ACCEPTED", plans.get(1).path("status").asText());

        // 计划明细：逐件结果按样本序号升序；序号1 落定瞬间即 REJECTED，序号2 落定时仍 OPEN
        MvcResult detailResult = mockMvc.perform(get("/api/sampling-plans/" + plan1))
                .andExpect(status().isOk()).andReturn();
        JsonNode detail = objectMapper.readTree(detailResult.getResponse().getContentAsString());
        assertEquals(2, detail.path("samples").size());
        assertEquals(1, detail.path("samples").get(0).path("sampleIndex").asInt());
        assertEquals("REJECTED", detail.path("samples").get(0).path("planStatus").asText());
        assertEquals(1, detail.path("samples").get(0).path("registeredWeightedDefects").asInt());
        assertEquals(2, detail.path("samples").get(1).path("sampleIndex").asInt());
        assertEquals("OPEN", detail.path("samples").get(1).path("planStatus").asText());

        // /samples 子资源一致
        MvcResult samplesResult = mockMvc.perform(get("/api/sampling-plans/" + plan1 + "/samples"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(2, objectMapper.readTree(samplesResult.getResponse().getContentAsString()).size());

        // 计划1 的判定时刻未被后续计划改写
        assertEquals(decidedAt1, planJson(plan1).path("decidedAt").asText());

        // 不存在 → 404
        mockMvc.perform(get("/api/sampling-plans/NO-SUCH-PLAN"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/sampling-plans"))
                .andExpect(status().isNotFound());
    }

    @Test
    void childBatch_doesNotInheritParentPlansOrDecisions() throws Exception {
        String parent = "SP-INH-P-" + unique();
        createBatch(parent, List.of("t1"), 201);
        // releaseBatch 内部为父批创建并判定唯一一个 ACCEPTED 计划
        releaseBatch(parent, "insp-1");

        String child = "SP-INH-C-" + unique();
        split(parent, "CK-INH-SPLIT", List.of(new String[]{child, "L1"},
                new String[]{"SP-INH-C2-" + unique(), "L2"}), 201);

        // 子批没有任何计划：隔离中批准即被门禁拦截 422，消息指明无抽样计划
        MvcResult childPlans = mockMvc.perform(get("/api/batches/" + child + "/sampling-plans"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, objectMapper.readTree(childPlans.getResponse().getContentAsString()).size());
        MvcResult blocked = approve(child, "qa", "QUALITY", "CK-INH-A0", 422);
        assertTrue(objectMapper.readTree(blocked.getResponse().getContentAsString())
                .path("message").asText().contains("无抽样计划"));

        // 子批在仍隔离阶段独立创建自己的计划并判定 ACCEPTED，再提交必做检验、双角色批准放行
        String childPlan = createPlan(child, "PLAN-INH-C-" + unique(), 1, 0, 1, "std", 201);
        register(childPlan, 1, "QUALIFIED", "子批合格", 201);
        submitTest(child, "t1", "PASS", "insp-2", 201);
        approve(child, "qa2", "QUALITY", "CK-INH-A2", 201);
        approve(child, "ops2", "OPERATIONS", "CK-INH-A3", 201);
        assertEquals("RELEASED", currentStatus(child));
        // 父批计划仍只有 1 个，子批计划独立
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sampling_plan WHERE batch_key = ?", Integer.class, parent));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sampling_plan WHERE batch_key = ?", Integer.class, child));
    }

    // ---------- 并发：同键创建重放 ----------

    @Test
    void concurrentCreatePlanSameCommandKey_bothReplayFirstResult() throws Exception {
        String batchKey = "SP-CCK-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        String planKey = "PLAN-CCK-" + unique();
        String body = planBody("CK-CCK-1", planKey, 2, 0, 1, "std");
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/sampling-plans")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/" + batchKey + "/sampling-plans")
                        .contentType(MediaType.APPLICATION_JSON).content(body)));
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发创建均返回首次结果 201");
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sampling_plan WHERE plan_key = ?", Integer.class, planKey));
    }

    // ---------- helpers ----------

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests) {
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createBatch(String batchKey, List<String> items, int expected) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(), batchKey,
                "PROD-1", "LOT-1", Instant.parse("2026-01-02T03:04:05Z"), items));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private String planBody(String commandKey, String planKey, int sampleSize, int ac, int re,
                            String basis) {
        return "{\"commandKey\":\"" + commandKey + "\",\"planKey\":\"" + planKey
                + "\",\"sampleSize\":" + sampleSize + ",\"acceptNumber\":" + ac
                + ",\"rejectNumber\":" + re + ",\"basis\":\"" + basis + "\"}";
    }

    private String createPlan(String batchKey, String planKey, int sampleSize, int ac, int re,
                              String basis, int expected) throws Exception {
        createPlanRaw(batchKey, planBody("CK-PLAN-" + unique(), planKey, sampleSize, ac, re, basis),
                expected);
        return planKey;
    }

    private void createPlanRaw(String batchKey, String body, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/sampling-plans")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private String sampleBody(String commandKey, int index, String result, String description) {
        return "{\"commandKey\":\"" + commandKey + "\",\"sampleIndex\":" + index
                + ",\"result\":\"" + result + "\",\"description\":\"" + description + "\"}";
    }

    private MvcResult register(String planKey, int index, String result, String description,
                               int expected) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/sampling-plans/" + planKey + "/samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sampleBody("CK-SMP-" + unique(), index, result, description)))
                .andExpect(status().is(expected)).andReturn();
        return r;
    }

    private void registerRaw(String planKey, String body, int expected) throws Exception {
        mockMvc.perform(post("/api/sampling-plans/" + planKey + "/samples")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private int registerStatus(String planKey, String[] spec) {
        try {
            return mockMvc.perform(post("/api/sampling-plans/" + planKey + "/samples")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(sampleBody("CK-SMP-" + unique(), Integer.parseInt(spec[0]),
                                    spec[1], spec[2])))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void assertRegistration(String planKey, int index, String result, String description,
                                    int weight, String planStatus, int cumulative) throws Exception {
        MvcResult r = register(planKey, index, result, description, 201);
        JsonNode node = objectMapper.readTree(r.getResponse().getContentAsString());
        assertEquals(weight, node.path("weight").asInt());
        assertEquals(cumulative, node.path("registeredWeightedDefects").asInt());
        assertEquals(planStatus, node.path("planStatus").asText());
    }

    private JsonNode planJson(String planKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/sampling-plans/" + planKey))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("plan");
    }

    private void submitTest(String batchKey, String item, String result, String inspector,
                            int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TestCmd(
                                "CK-T-" + unique(), "TK-" + unique(), item, result, inspector))))
                .andExpect(status().is(expected));
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private MvcResult approve(String batchKey, String actor, String role, String commandKey,
                              int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private void recall(String batchKey, String actor, String reason, String commandKey,
                        int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason
                                + "\"}"))
                .andExpect(status().is(expected));
    }

    /**
     * 让批次走完 ACCEPTED 抽样计划 + 检验 PASS + 双角色批准进入 RELEASED。
     * 计划必须在隔离中创建，故先登记完计划再提交必做检验项。
     */
    private void releaseBatch(String batchKey, String inspector) throws Exception {
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        List<String> items = new ArrayList<>();
        node.path("batch").path("requiredTests").forEach(n -> items.add(n.asText()));

        String planKey = createPlan(batchKey, "PLAN-REL-" + unique(), 1, 0, 1, "release-std", 201);
        register(planKey, 1, "QUALIFIED", "放行抽样合格", 201);
        for (String item : items) {
            submitTest(batchKey, item, "PASS", inspector, 201);
        }
        approve(batchKey, "qa-" + unique(), "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", "CK-AO-" + unique(), 201);
    }

    private void split(String parentKey, String commandKey, List<String[]> children,
                       int expected) throws Exception {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"children\":[");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"batchKey\":\"").append(children.get(i)[0])
                    .append("\",\"batchNo\":\"").append(children.get(i)[1]).append("\"}");
        }
        sb.append("]}");
        mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(sb.toString()))
                .andExpect(status().is(expected));
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private int callStatus(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        return mockMvc.perform(req).andReturn().getResponse().getStatus();
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

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 抽样检验计划端到端 H2 测试：加权计数、OPEN/ACCEPTED/REJECTED 原子判定、批准门禁、
 * REJECTED 计划限额、召回拦截、commandKey/planKey/样本序号幂等与查询、子批不继承。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SamplingPlanTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM sample_record");
        jdbc.update("DELETE FROM sampling_plan");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 加权计数与判定主流程 ----------

    @Test
    void weightedDefects_minorZero_majorOne_criticalThree() throws Exception {
        String batchKey = newBatch();
        // n=5, Ac=2, Re=4
        String planKey = createPlan(batchKey, "PK-W-" + unique(), 5, 2, 4, "GB/T 2828.1", 201);

        JsonNode p1 = record(planKey, 1, null, "合格件", "CK-R1-" + unique(), 201);
        assertEquals(0, p1.path("weightedDefects").asInt());
        assertEquals("OPEN", p1.path("planStatus").asText());

        JsonNode p2 = record(planKey, 2, "MINOR", "轻微划痕", "CK-R2-" + unique(), 201);
        assertEquals(0, p2.path("weightedDefects").asInt(), "MINOR 加权 0");
        assertEquals("OPEN", p2.path("planStatus").asText());

        JsonNode p3 = record(planKey, 3, "MAJOR", "尺寸超差", "CK-R3-" + unique(), 201);
        assertEquals(1, p3.path("weightedDefects").asInt(), "MAJOR 加权 1");
        assertEquals("OPEN", p3.path("planStatus").asText());

        JsonNode p4 = record(planKey, 4, "CRITICAL", "密封失效", "CK-R4-" + unique(), 201);
        assertEquals(4, p4.path("weightedDefects").asInt(), "累计 0+0+1+3=4 达到 Re");
        assertEquals("REJECTED", p4.path("planStatus").asText());
        assertNotNull(p4.path("decidedAt").asText(null));
        assertTrue(p4.path("conforming").asBoolean() == false);
        assertEquals("CRITICAL", p4.path("grade").asText());

        // 计划明细落库：已登记 4 件、累计 4、REJECTED、判定时刻非空
        JsonNode plan = getPlan(planKey);
        assertEquals("REJECTED", plan.path("status").asText());
        assertEquals(4, plan.path("recordedCount").asInt());
        assertEquals(4, plan.path("weightedDefects").asInt());
        assertFalse(plan.path("decidedAt").isNull());
        // 批次未进入待放行
        assertEquals("QUARANTINED", batchStatus(batchKey));
    }

    @Test
    void allConforming_completesSamples_acceptedAndMovesBatchToPendingRelease() throws Exception {
        String batchKey = newBatch();
        String planKey = createPlan(batchKey, "PK-A-" + unique(), 3, 0, 1, "方案A", 201);

        JsonNode last = null;
        for (int no = 1; no <= 3; no++) {
            last = record(planKey, no, null, "合格 " + no, "CK-A" + no + "-" + unique(), 201);
        }
        assertEquals("ACCEPTED", last.path("planStatus").asText());
        assertEquals(0, last.path("weightedDefects").asInt());
        assertNotNull(last.path("decidedAt").asText(null));
        // 判定驱动隔离批次进入待放行
        assertEquals("PENDING_RELEASE", batchStatus(batchKey));
        assertEquals("ACCEPTED", getPlan(planKey).path("status").asText());
    }

    @Test
    void betweenAcAndReWhenCompleted_staysOpen() throws Exception {
        String batchKey = newBatch();
        // 5 件：3 件 MAJOR(=3) + 2 件 MINOR(=0)，Ac=2 < 3 < Re=4，登记完成且介于两者之间 → 保持 OPEN
        String planKey = createPlan(batchKey, "PK-MID-" + unique(), 5, 2, 4, "方案M", 201);
        JsonNode last = null;
        for (int no = 1; no <= 3; no++) {
            last = record(planKey, no, "MAJOR", "缺陷 " + no, "CK-M" + no + "-" + unique(), 201);
        }
        for (int no = 4; no <= 5; no++) {
            last = record(planKey, no, "MINOR", "轻微 " + no, "CK-M" + no + "-" + unique(), 201);
        }
        assertEquals("OPEN", last.path("planStatus").asText());
        assertNull(last.path("decidedAt").asText(null));
        assertEquals(5, getPlan(planKey).path("recordedCount").asInt());
        assertEquals(3, getPlan(planKey).path("weightedDefects").asInt());
        assertEquals("QUARANTINED", batchStatus(batchKey));
    }

    // ---------- 批准门禁 ----------

    @Test
    void acceptedPlan_allowsTwoRoleApprovalAndRelease() throws Exception {
        String batchKey = newBatch();
        String planKey = createPlan(batchKey, "PK-REL-" + unique(), 2, 0, 2, "方案R", 201);
        record(planKey, 1, "MINOR", "轻微", "CK-P1-" + unique(), 201);
        record(planKey, 2, "MINOR", "轻微", "CK-P2-" + unique(), 201);
        assertEquals("PENDING_RELEASE", batchStatus(batchKey));

        approve(batchKey, "qa-1", "QUALITY", "CK-AP-Q-" + unique(), 201);
        assertEquals("RELEASE_REVIEW", batchStatus(batchKey));
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AP-O-" + unique(), 201);
        assertEquals("RELEASED", batchStatus(batchKey));
    }

    @Test
    void openPlan_blocksApprovalWith422AndStatesPlanStatus() throws Exception {
        String batchKey = newBatch();
        String planKey = createPlan(batchKey, "PK-O-" + unique(), 5, 1, 3, "方案O", 201);
        record(planKey, 1, "MINOR", "轻微", "CK-O1-" + unique(), 201);
        // 批次仍 QUARANTINED；存在 OPEN 计划时批准被门禁拦截，消息指明当前计划状态
        MvcResult result = approveRaw(batchKey, "qa-1", "QUALITY", "CK-OA-" + unique());
        assertEquals(422, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("OPEN"), "422 消息须指明当前计划状态 OPEN: " + body);
        assertEquals("QUARANTINED", batchStatus(batchKey));
    }

    @Test
    void rejectedPlan_blocksApproval_thenNewPlanAcceptedAllowsRelease() throws Exception {
        String batchKey = newBatch();
        String planKey = createPlan(batchKey, "PK-RJ-" + unique(), 2, 0, 1, "方案X", 201);
        record(planKey, 1, "MAJOR", "重缺陷", "CK-RJ1-" + unique(), 201);
        assertEquals("REJECTED", getPlan(planKey).path("status").asText());

        MvcResult blocked = approveRaw(batchKey, "qa-1", "QUALITY", "CK-RJA-" + unique());
        assertEquals(422, blocked.getResponse().getStatus());
        assertTrue(blocked.getResponse().getContentAsString().contains("REJECTED"));

        // REJECTED 后可新建下一计划；全部合格 ACCEPTED 后放行
        String planKey2 = createPlan(batchKey, "PK-RJ2-" + unique(), 1, 0, 1, "方案X-2", 201);
        record(planKey2, 1, null, "合格", "CK-RJ2-1-" + unique(), 201);
        assertEquals("ACCEPTED", getPlan(planKey2).path("status").asText());
        approve(batchKey, "qa-2", "QUALITY", "CK-RJ-Q-" + unique(), 201);
        approve(batchKey, "ops-2", "OPERATIONS", "CK-RJ-O-" + unique(), 201);
        assertEquals("RELEASED", batchStatus(batchKey));
    }

    @Test
    void maxThreeRejectedPlans_fourthReturns422() throws Exception {
        String batchKey = newBatch();
        for (int i = 1; i <= 3; i++) {
            String planKey = createPlan(batchKey, "PK-LIM" + i + "-" + unique(), 1, 0, 1, "方案", 201);
            record(planKey, 1, "MAJOR", "缺陷", "CK-LIM" + i + "-" + unique(), 201);
            assertEquals("REJECTED", getPlan(planKey).path("status").asText());
        }
        // 第 4 个 REJECTED 限额已满：即使前 3 个均 REJECTED，也不得再新建
        createPlan(batchKey, "PK-LIM4-" + unique(), 1, 0, 1, "方案", 422);
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sampling_plan WHERE batch_key = ? AND status = 'REJECTED'",
                Integer.class, batchKey));
    }

    // ---------- 创建计划失败分支 ----------

    @Test
    void createPlan_constraintViolations_return400or409or422() throws Exception {
        String batchKey = newBatch();

        // 样本量越界（注解校验 400）
        createPlanRaw(batchKey, planBody("PK-BAD-" + unique(), 0, 0, 1, "b"), 400);
        createPlanRaw(batchKey, planBody("PK-BAD-" + unique(), 201, 0, 1, "b"), 400);
        // Ac<0、Ac>=Re、Re>样本量（服务层跨字段 400）
        createPlanRaw(batchKey, planBody("PK-BAD-" + unique(), 5, 1, 1, "b"), 400);
        createPlanRaw(batchKey, planBody("PK-BAD-" + unique(), 5, 2, 1, "b"), 400);
        createPlanRaw(batchKey, planBody("PK-BAD-" + unique(), 5, 0, 6, "b"), 400);
        // basis 空白 400
        createPlanRaw(batchKey, planBody("PK-BAD-" + unique(), 5, 0, 1, "  "), 400);

        // 同批次已有 OPEN 计划 → 409
        String openPlan = createPlan(batchKey, "PK-OPEN-" + unique(), 5, 0, 1, "b", 201);
        createPlan(batchKey, "PK-OPEN2-" + unique(), 5, 0, 1, "b", 409);

        // planKey 全局唯一：另一批次重复 planKey → 409
        String otherBatch = newBatch();
        createPlan(otherBatch, openPlan, 5, 0, 1, "b", 409);

        // 批次不存在 → 404
        createPlanRaw("NO-SUCH-BATCH", planBody("PK-404-" + unique(), 5, 0, 1, "b"), 404);

        // 非隔离中批次：另起一批次走完 ACCEPTED（进入 PENDING_RELEASE）后不得再建 → 422
        String batch2 = newBatch();
        String p2 = createPlan(batch2, "PK-DONE-" + unique(), 1, 0, 1, "b", 201);
        record(p2, 1, null, "ok", "CK-DONE-" + unique(), 201);
        createPlan(batch2, "PK-DONE2-" + unique(), 1, 0, 1, "b", 422);
    }

    // ---------- 登记失败分支 ----------

    @Test
    void recordSamples_terminalConflict_duplicateAndOutOfRange() throws Exception {
        String batchKey = newBatch();
        String planKey = createPlan(batchKey, "PK-REC-" + unique(), 3, 0, 3, "方案", 201);

        // 序号越界 400
        recordRaw(planKey, sampleBody("CK-OOR-" + unique(), 0, null, "x"), 400);
        recordRaw(planKey, sampleBody("CK-OOR-" + unique(), 4, null, "x"), 400);
        // 描述空白 400
        recordRaw(planKey, sampleBody("CK-DESC-" + unique(), 1, null, " "), 400);
        // 等级枚举非法 400
        String badGrade = "{\"commandKey\":\"CK-G-" + unique() + "\",\"sampleNo\":1,"
                + "\"grade\":\"FATAL\",\"description\":\"x\"}";
        recordRaw(planKey, badGrade, 400);

        record(planKey, 1, "CRITICAL", "严重", "CK-R1-" + unique(), 201);
        // 序号重复 409
        record(planKey, 1, null, "合格", "CK-R1DUP-" + unique(), 409);
        // 计划已 REJECTED，再登记 409
        record(planKey, 2, null, "合格", "CK-R2-" + unique(), 409);

        // 计划不存在 404
        recordRaw("NO-SUCH-PLAN", sampleBody("CK-404-" + unique(), 1, null, "x"), 404);
    }

    // ---------- 召回拦截 ----------

    @Test
    void recalledBatch_cannotCreatePlan() throws Exception {
        String batchKey = newBatch();
        releaseBatch(batchKey, "insp-1");
        recall(batchKey, "u", "质量原因", "CK-REC-" + unique(), 201);
        createPlan(batchKey, "PK-RC-" + unique(), 2, 0, 1, "b", 422);
    }

    @Test
    void batchWithRecalledAncestor_cannotCreatePlan() throws Exception {
        String parent = newBatch();
        releaseBatch(parent, "insp-p");
        String child = "BK-CHILD-" + unique();
        split(parent, "CK-SPLIT-" + unique(),
                List.of(new String[]{child, "L1"}, new String[]{"BK-C2-" + unique(), "L2"}), 201);
        // 父批（RELEASED）召回
        recall(parent, "u", "上游污染", "CK-RCA-" + unique(), 201);
        // 有召回祖先的隔离子批不得创建计划 → 422
        createPlan(child, "PK-ANC-" + unique(), 2, 0, 1, "b", 422);
    }

    // ---------- 幂等 ----------

    @Test
    void recordCommandKey_sameReplays_diffConflicts_failureDoesNotOccupy() throws Exception {
        String batchKey = newBatch();
        String planKey = createPlan(batchKey, "PK-IDEM-" + unique(), 5, 0, 5, "方案", 201);

        String body = sampleBody("CK-IDEM-1", 1, "MAJOR", "第一件");
        MvcResult first = recordRaw(planKey, body, 201);
        MvcResult replay = recordRaw(planKey, body, 201);
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        // 重放不新增登记
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_record WHERE plan_key = ?", Integer.class, planKey));
        assertEquals(1, getPlan(planKey).path("recordedCount").asInt());

        // 同 commandKey 异参（序号与描述不同）→ 409（指纹不一致优先于业务校验）
        recordRaw(planKey, sampleBody("CK-IDEM-1", 2, "MAJOR", "第二件"), 409);

        // 失败不占键：先用该键制造一次 400（序号越界），再用同键合法登记成功
        String reusable = "CK-IDEM-2";
        recordRaw(planKey, sampleBody(reusable, 99, null, "越界"), 400);
        recordRaw(planKey, sampleBody(reusable, 2, null, "合格第二件"), 201);
    }

    @Test
    void createPlanCommandKey_sameReplays_diffConflicts_failureDoesNotOccupy() throws Exception {
        String batchKey = newBatch();
        String planKey = "PK-CMD-" + unique();
        String commandKey = "CK-CMD-" + unique();
        String body = planBodyWithCommand(commandKey, planKey, 3, 0, 1, "依据");
        MvcResult first = createPlanRaw(batchKey, body, 201);
        MvcResult replay = createPlanRaw(batchKey, body, 201);
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sampling_plan WHERE batch_key = ?", Integer.class, batchKey));

        // 同 commandKey 改参（Ac 不同）→ 409
        createPlanRaw(batchKey, planBodyWithCommand(commandKey, "PK-CMD-OTHER-" + unique(),
                3, 1, 2, "依据"), 409);

        // 失败不占键：在全新批次上先用某键制造 400，再用同键合法创建
        String batchForReuse = newBatch();
        String key = "CK-CP-REUSE-" + unique();
        createPlanRaw(batchForReuse, planBodyWithCommand(key, "PK-X1-" + unique(), 3, 2, 1, "依据"), 400);
        createPlanRaw(batchForReuse, planBodyWithCommand(key, "PK-X2-" + unique(), 3, 0, 1, "依据"), 201);
    }

    // ---------- 查询与历史不可改写 ----------

    @Test
    void queries_planDetail_samplesAndBatchHistory() throws Exception {
        String batchKey = newBatch();
        String planKey = createPlan(batchKey, "PK-Q-" + unique(), 3, 0, 3, "依据Q", 201);
        record(planKey, 1, "MAJOR", "第一件", "CK-Q1-" + unique(), 201);
        record(planKey, 2, "CRITICAL", "第二件", "CK-Q2-" + unique(), 201);

        // 批次判定历史：两个计划中仅一个，状态 REJECTED
        MvcResult plansResult = mockMvc.perform(get("/api/batches/" + batchKey + "/sampling-plans"))
                .andExpect(status().isOk()).andReturn();
        JsonNode plans = objectMapper.readTree(plansResult.getResponse().getContentAsString());
        assertEquals(1, plans.size());
        assertEquals(planKey, plans.get(0).path("planKey").asText());
        assertEquals("依据Q", plans.get(0).path("basis").asText());

        // 逐件结果按序号；第一件落定当时 OPEN，第二件 REJECTED（历史快照不被回填）
        MvcResult samplesResult = mockMvc.perform(get("/api/sampling-plans/" + planKey + "/samples"))
                .andExpect(status().isOk()).andReturn();
        JsonNode samples = objectMapper.readTree(samplesResult.getResponse().getContentAsString());
        assertEquals(2, samples.size());
        assertEquals(1, samples.get(0).path("sampleNo").asInt());
        assertEquals(1, samples.get(0).path("weightedDefects").asInt());
        assertEquals("OPEN", samples.get(0).path("planStatus").asText());
        assertTrue(samples.get(0).path("decidedAt").isNull());
        assertEquals(4, samples.get(1).path("weightedDefects").asInt());
        assertEquals("REJECTED", samples.get(1).path("planStatus").asText());
        assertFalse(samples.get(1).path("decidedAt").isNull());
    }

    @Test
    void rejectedDecisionTimestamp_isNotRewrittenByLaterReads() throws Exception {
        String batchKey = newBatch();
        String planKey = createPlan(batchKey, "PK-TS-" + unique(), 2, 0, 1, "依据", 201);
        record(planKey, 1, "MAJOR", "x", "CK-TS-" + unique(), 201);
        JsonNode plan1 = getPlan(planKey);
        String decidedAt = plan1.path("decidedAt").asText();
        assertNotNull(decidedAt);
        // 再次查询，判定时刻保持不变
        assertEquals(decidedAt, getPlan(planKey).path("decidedAt").asText());
    }

    @Test
    void childBatch_doesNotInheritParentPlanOrDecision() throws Exception {
        String parent = newBatch();
        String planKey = createPlan(parent, "PK-INH-" + unique(), 1, 0, 1, "依据", 201);
        record(planKey, 1, null, "合格", "CK-INH-" + unique(), 201);
        approve(parent, "qa-1", "QUALITY", "CK-IQ-" + unique(), 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-IO-" + unique(), 201);
        assertEquals("RELEASED", batchStatus(parent));

        String child = "BK-INH-CHILD-" + unique();
        split(parent, "CK-INH-S-" + unique(),
                List.of(new String[]{child, "L1"}, new String[]{"BK-INH-C2-" + unique(), "L2"}), 201);

        // 子批没有任何计划
        MvcResult result = mockMvc.perform(get("/api/batches/" + child + "/sampling-plans"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, objectMapper.readTree(result.getResponse().getContentAsString()).size());
        assertEquals("QUARANTINED", batchStatus(child));
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentRegistrations_atMostOneDecision_noLostOrDoubleCountedWeight() throws Exception {
        String batchKey = newBatch();
        // 5 件 MAJOR 各加权 1，Ac=2、Re=4：第 4 件落定 REJECTED，第 5 件因终结 409
        String planKey = createPlan(batchKey, "PK-CR-" + unique(), 5, 2, 4, "方案CR", 201);

        List<Future<Integer>> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CyclicBarrier barrier = new CyclicBarrier(5);
        for (int no = 1; no <= 5; no++) {
            final int sampleNo = no;
            results.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return callStatus(post("/api/sampling-plans/" + planKey + "/samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sampleBody("CK-CR-" + sampleNo + "-" + unique(), sampleNo,
                                "MAJOR", "缺陷 " + sampleNo)));
            }));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        int ok = 0;
        int terminalConflict = 0;
        int rejectedResponses = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                ok++;
            } else if (code == 409) {
                terminalConflict++;
            } else {
                throw new AssertionError("意外状态码: " + code);
            }
        }
        // 恰好 4 件落定、1 件因计划终结被拒；只有第 4 件产生 REJECTED 判定
        assertEquals(4, ok, "Re 达到后第 5 件必须被 409 拒绝");
        assertEquals(1, terminalConflict);
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_record WHERE plan_key = ?", Integer.class, planKey);
        Integer rejectedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_record WHERE plan_key = ? AND plan_status_after = 'REJECTED'",
                Integer.class, planKey);
        assertEquals(4, rows);
        assertEquals(1, rejectedRows, "并发登记最多产生一条判定");
        JsonNode plan = getPlan(planKey);
        assertEquals("REJECTED", plan.path("status").asText());
        assertEquals(4, plan.path("recordedCount").asInt());
        assertEquals(4, plan.path("weightedDefects").asInt(), "加权计数不得重复累加或丢失");
    }

    @Test
    void concurrentSameCommandKeyRegistration_bothReplaySingleRow() throws Exception {
        String batchKey = newBatch();
        String planKey = createPlan(batchKey, "PK-CK-" + unique(), 3, 0, 3, "方案", 201);
        String body = sampleBody("CK-CONCURRENT-1", 1, "MAJOR", "唯一一件");

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/sampling-plans/" + planKey + "/samples")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/sampling-plans/" + planKey + "/samples")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_record WHERE plan_key = ?", Integer.class, planKey));
        assertEquals(1, getPlan(planKey).path("recordedCount").asInt());
    }

    @Test
    void concurrentCreatePlansOnSameBatch_onlyOneOpenSucceeds() throws Exception {
        String batchKey = newBatch();
        String body1 = planBody("PK-CC-1-" + unique(), 3, 0, 1, "依据");
        String body2 = planBody("PK-CC-2-" + unique(), 3, 0, 1, "依据");
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/sampling-plans")
                        .contentType(MediaType.APPLICATION_JSON).content(body1)),
                () -> callStatus(post("/api/batches/" + batchKey + "/sampling-plans")
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
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
                throw new AssertionError("意外状态码: " + code);
            }
        }
        assertEquals(1, ok);
        assertEquals(1, conflict, "同一批次并发只能创建一个未终结计划");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sampling_plan WHERE batch_key = ? AND status = 'OPEN'",
                Integer.class, batchKey));
    }

    @Test
    void concurrentAncestorRecallAndRegistration_commitOrderDecides() throws Exception {
        String parent = newBatch();
        releaseBatch(parent, "insp-p");
        String child = "BK-CR-CHILD-" + unique();
        split(parent, "CK-CR-S-" + unique(),
                List.of(new String[]{child, "L1"}, new String[]{"BK-CR-C2-" + unique(), "L2"}), 201);
        String planKey = createPlan(child, "PK-CR-ANC-" + unique(), 3, 0, 3, "依据", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/sampling-plans/" + planKey + "/samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sampleBody("CK-CR-REG-" + unique(), 1, "MAJOR", "第一件"))),
                () -> callStatus(post("/api/batches/" + parent + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CR-REC-" + unique() + "\",\"reason\":\"根批召回\"}"))
        );

        int registration = results.get(0).get(30, TimeUnit.SECONDS);
        int recall = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, recall, "SPLIT 父批始终可召回");
        Integer sampleRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_record WHERE plan_key = ?", Integer.class, planKey);
        if (registration == 201) {
            // 登记先提交：落定 1 件、计划 OPEN；随后召回不影响已提交登记
            assertEquals(1, sampleRows);
            assertEquals("OPEN", getPlan(planKey).path("status").asText());
            // 召回后该子批不得再登记
            record(planKey, 2, null, "合格", "CK-CR-AFTER-" + unique(), 422);
        } else {
            // 召回先提交：登记被拦截 422，无任何登记落库
            assertEquals(422, registration);
            assertEquals(0, sampleRows);
            assertEquals("OPEN", getPlan(planKey).path("status").asText());
        }
        assertEquals("RECALLED", batchStatus(parent));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests) {
    }

    private String newBatch() throws Exception {
        String batchKey = "BK-SP-" + unique();
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(), batchKey,
                "PROD-1", "LOT-1", Instant.parse("2026-01-02T03:04:05Z"), List.of("t1")));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        return batchKey;
    }

    private String planBody(String planKey, int n, int ac, int re, String basis) {
        return planBodyWithCommand("CK-PLAN-" + unique(), planKey, n, ac, re, basis);
    }

    private String planBodyWithCommand(String commandKey, String planKey, int n, int ac, int re,
                                       String basis) {
        try {
            return objectMapper.writeValueAsString(new PlanCmd(commandKey, planKey, n, ac, re, basis));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private record PlanCmd(String commandKey, String planKey, int sampleSize, int acceptNumber,
                           int rejectNumber, String basis) {
    }

    private String createPlan(String batchKey, String planKey, int n, int ac, int re, String basis,
                              int expected) throws Exception {
        createPlanRaw(batchKey, planBody(planKey, n, ac, re, basis), expected);
        return planKey;
    }

    private MvcResult createPlanRaw(String batchKey, String body, int expected) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/batches/" + batchKey + "/sampling-plans")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
        return result;
    }

    private String sampleBody(String commandKey, int sampleNo, String grade, String description) {
        try {
            return objectMapper.writeValueAsString(new SampleCmd(commandKey, sampleNo, grade, description));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private record SampleCmd(String commandKey, int sampleNo, String grade, String description) {
    }

    private JsonNode record(String planKey, int sampleNo, String grade, String description,
                            String commandKey, int expected) throws Exception {
        MvcResult result = recordRaw(planKey, sampleBody(commandKey, sampleNo, grade, description),
                expected);
        if (expected == 201) {
            return objectMapper.readTree(result.getResponse().getContentAsString());
        }
        return null;
    }

    private MvcResult recordRaw(String planKey, String body, int expected) throws Exception {
        return mockMvc.perform(post("/api/sampling-plans/" + planKey + "/samples")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private JsonNode getPlan(String planKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/sampling-plans/" + planKey))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void approve(String batchKey, String actor, String role, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    private MvcResult approveRaw(String batchKey, String actor, String role, String commandKey)
            throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andReturn();
    }

    private void releaseBatch(String batchKey, String inspector) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TestCmd(
                                "CK-T-" + unique(), "TK-" + unique(), "t1", "PASS", inspector))))
                .andExpect(status().isCreated());
        approve(batchKey, "qa-" + unique(), "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", "CK-AO-" + unique(), 201);
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private void recall(String batchKey, String actor, String reason, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason
                                + "\"}"))
                .andExpect(status().is(expected));
    }

    private String splitBody(String commandKey, List<String[]> children) {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"children\":[");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"batchKey\":\"").append(children.get(i)[0])
                    .append("\",\"batchNo\":\"").append(children.get(i)[1]).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private void split(String parentKey, String commandKey, List<String[]> children, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitBody(commandKey, children)))
                .andExpect(status().is(expected));
    }

    private String batchStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
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

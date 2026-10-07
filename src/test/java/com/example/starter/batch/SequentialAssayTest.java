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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 合成序贯检验端到端测试（真实 H2 内存库 MODE=MySQL）：
 * 正常阈值终止、失败分支与 REJECTED 联动、序号连续/终止冻结、commandKey 幂等、
 * 同序号并发竞争至多一条生效、拆批子批独立重检、祖先召回联动、旧类型批次兼容。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SequentialAssayTest {

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
        jdbc.update("DELETE FROM sequential_result");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- helpers ----------

    private String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests, String assayType) {
    }

    private record SeqCmd(String commandKey, Integer seq, String sample, String inspector) {
    }

    private String createSequentialBatch(String batchKey, List<String> requiredTests) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-CREATE-" + uid(), batchKey,
                "PROD-1", "LOT-1", Instant.parse("2026-01-02T03:04:05Z"),
                requiredTests, "SEQUENTIAL"));
        MvcResult result = mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return result.getResponse().getContentAsString();
    }

    private String createClassicBatch(String batchKey, List<String> requiredTests) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-CREATE-" + uid(), batchKey,
                "PROD-1", "LOT-1", Instant.parse("2026-01-02T03:04:05Z"),
                requiredTests, null));
        return mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private MvcResult submitSeq(String batchKey, String commandKey, int seq, String sample,
                                String inspector, int expectedStatus) throws Exception {
        String body = objectMapper.writeValueAsString(new SeqCmd(commandKey, seq, sample, inspector));
        return mockMvc.perform(post("/api/batches/" + batchKey + "/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private String seqBody(String commandKey, int seq, String sample, String inspector) throws Exception {
        return objectMapper.writeValueAsString(new SeqCmd(commandKey, seq, sample, inspector));
    }

    private record ClassicTestCmd(String commandKey, String testKey, String testItem,
                                  String result, String inspector) {
    }

    private void submitClassicTest(String batchKey, String testKey, String item, String result,
                                   String inspector, int expected) throws Exception {
        String body = objectMapper.writeValueAsString(new ClassicTestCmd(
                "CK-T-" + uid(), testKey, item, result, inspector));
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

    private JsonNode history(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String currentStatus(String batchKey) throws Exception {
        return history(batchKey).path("batch").path("status").asText();
    }

    private JsonNode lastSequential(String batchKey) throws Exception {
        JsonNode rows = history(batchKey).path("sequentialResults");
        return rows.get(rows.size() - 1);
    }

    private int seqRowCount(String batchKey) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sequential_result WHERE batch_key = ?",
                Integer.class, batchKey);
    }

    private void releaseSequentialBatch(String batchKey, List<String> requiredTests) throws Exception {
        createSequentialBatch(batchKey, requiredTests);
        submitSeq(batchKey, "CK-S1", 1, "GOOD", "sampler-a", 201);
        submitSeq(batchKey, "CK-S2", 2, "GOOD", "sampler-a", 201);
        int i = 1;
        for (String item : requiredTests) {
            submitClassicTest(batchKey, "TK-" + i, item, "PASS", "inspector-" + i, 201);
            i++;
        }
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
        approve(batchKey, "qa-1", "QUALITY", "CK-Q", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-O", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
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

    // ---------- 主流程：GOOD GOOD 立即 PASS，再 BAD 409 ----------

    @Test
    void twoGoods_reachRatioNine_pass_andRequiredTestsAndTwoRolesStillNeeded() throws Exception {
        String batchKey = "BK-SEQ-PASS-" + uid();
        String created = createSequentialBatch(batchKey, List.of("外观", "含量"));
        assertEquals("SEQUENTIAL", objectMapper.readTree(created).path("assayType").asText());
        assertEquals("QUARANTINED", currentStatus(batchKey));

        MvcResult r1 = submitSeq(batchKey, "CK-S1", 1, "GOOD", "sampler-a", 201);
        JsonNode j1 = objectMapper.readTree(r1.getResponse().getContentAsString());
        assertEquals(3, j1.path("numerator").asLong());
        assertEquals(1, j1.path("denominator").asLong());
        assertEquals(0, new BigDecimal("3.000000").compareTo(j1.path("likelihoodRatio").decimalValue()),
                "展示小数仅用于呈现，必须等于精确分数 3/1");
        assertEquals("CONTINUE", j1.path("conclusion").asText());
        assertEquals("QUARANTINED", j1.path("batchStatus").asText());

        MvcResult r2 = submitSeq(batchKey, "CK-S2", 2, "GOOD", "sampler-a", 201);
        JsonNode j2 = objectMapper.readTree(r2.getResponse().getContentAsString());
        assertEquals(9, j2.path("numerator").asLong());
        assertEquals(1, j2.path("denominator").asLong());
        assertEquals("PASS", j2.path("conclusion").asText());
        // 序贯 PASS 但经典必做项未齐：批次仍 QUARANTINED，不能批准
        assertEquals("QUARANTINED", j2.path("batchStatus").asText());
        approve(batchKey, "qa-1", "QUALITY", "CK-Q-EARLY", 422);

        // 经典必做项齐全后进入待放行
        submitClassicTest(batchKey, "TK-1", "外观", "PASS", "insp-1", 201);
        submitClassicTest(batchKey, "TK-2", "含量", "PASS", "insp-2", 201);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
        // 序贯抽样人不得批准
        approve(batchKey, "sampler-a", "QUALITY", "CK-Q-INSPECTOR", 422);
        approve(batchKey, "qa-1", "QUALITY", "CK-Q", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-O", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 终止后再提交 BAD：409 且比值 9 不被改写，不能用后续好/坏样本翻转
        submitSeq(batchKey, "CK-S3-BAD", 3, "BAD", "sampler-a", 409);
        submitSeq(batchKey, "CK-S3-GOOD", 3, "GOOD", "sampler-a", 409);
        JsonNode frozen = lastSequential(batchKey);
        assertEquals(2, frozen.path("seq").asInt());
        assertEquals(9, frozen.path("numerator").asLong());
        assertEquals(1, frozen.path("denominator").asLong());
        assertEquals("PASS", frozen.path("conclusion").asText());
        assertEquals(2, seqRowCount(batchKey));
        assertEquals("RELEASED", currentStatus(batchKey));

        // 历史冻结完整前缀
        JsonNode rows = history(batchKey).path("sequentialResults");
        assertEquals(2, rows.size());
        assertEquals(1, rows.get(0).path("seq").asInt());
        assertEquals("CONTINUE", rows.get(0).path("conclusion").asText());
        assertEquals("QUARANTINED", rows.get(0).path("batchStatus").asText());
        assertEquals("PASS", rows.get(1).path("conclusion").asText());
    }

    // ---------- BAD BAD 立即 REJECTED ----------

    @Test
    void twoBads_reachOneNinth_fail_rejectsBatchAndBlocksApproveSplitAndMoreSamples() throws Exception {
        String batchKey = "BK-SEQ-FAIL-" + uid();
        createSequentialBatch(batchKey, List.of("t1"));

        MvcResult r1 = submitSeq(batchKey, "CK-S1", 1, "BAD", "sampler-a", 201);
        JsonNode j1 = objectMapper.readTree(r1.getResponse().getContentAsString());
        assertEquals(1, j1.path("numerator").asLong());
        assertEquals(3, j1.path("denominator").asLong());
        assertEquals(0, new BigDecimal("0.333333").compareTo(j1.path("likelihoodRatio").decimalValue()));
        assertEquals("CONTINUE", j1.path("conclusion").asText());

        MvcResult r2 = submitSeq(batchKey, "CK-S2", 2, "BAD", "sampler-a", 201);
        JsonNode j2 = objectMapper.readTree(r2.getResponse().getContentAsString());
        assertEquals(1, j2.path("numerator").asLong());
        assertEquals(9, j2.path("denominator").asLong());
        assertEquals("FAIL", j2.path("conclusion").asText());
        assertEquals("REJECTED", j2.path("batchStatus").asText());
        assertEquals("REJECTED", currentStatus(batchKey));

        // 后续 GOOD 不能翻转 FAIL
        submitSeq(batchKey, "CK-S3", 3, "GOOD", "sampler-a", 409);
        // 不能批准、不能拆批、不能再提交经典检验
        approve(batchKey, "qa-1", "QUALITY", "CK-Q", 409);
        String splitBody = "{\"commandKey\":\"CK-SPLIT\",\"children\":["
                + "{\"batchKey\":\"CHILD-F-" + uid() + "\",\"batchNo\":\"C1\"},"
                + "{\"batchKey\":\"CHILD-F-" + uid() + "\",\"batchNo\":\"C2\"}]}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(splitBody))
                .andExpect(status().isConflict());
        submitClassicTest(batchKey, "TK-1", "t1", "PASS", "insp-1", 409);
        assertEquals(2, seqRowCount(batchKey));
        JsonNode frozen = lastSequential(batchKey);
        assertEquals(9, frozen.path("denominator").asLong());
        assertEquals("FAIL", frozen.path("conclusion").asText());
        assertEquals("REJECTED", frozen.path("batchStatus").asText());
    }

    // ---------- 满 10 次未达阈值 INCONCLUSIVE，不能批准 ----------

    @Test
    void tenAlternatingResults_inconclusive_cannotApprove_andFrozen() throws Exception {
        String batchKey = "BK-SEQ-INCONC-" + uid();
        createSequentialBatch(batchKey, List.of("t1"));
        submitClassicTest(batchKey, "TK-1", "t1", "PASS", "insp-1", 201);

        MvcResult last = null;
        for (int i = 1; i <= 10; i++) {
            String sample = (i % 2 == 1) ? "GOOD" : "BAD";
            last = submitSeq(batchKey, "CK-S" + i, i, sample, "sampler-a", 201);
        }
        JsonNode j = objectMapper.readTree(last.getResponse().getContentAsString());
        assertEquals("INCONCLUSIVE", j.path("conclusion").asText());
        assertEquals(243, j.path("numerator").asLong());
        assertEquals(243, j.path("denominator").asLong());
        assertEquals(0, BigDecimal.ONE.compareTo(j.path("likelihoodRatio").decimalValue()));
        // 经典项虽齐，但序贯 INCONCLUSIVE：批次保持 QUARANTINED，不能批准
        assertEquals("QUARANTINED", currentStatus(batchKey));
        approve(batchKey, "qa-1", "QUALITY", "CK-Q", 422);
        // 终止后再提交 409，前缀冻结为 10 行
        submitSeq(batchKey, "CK-S11", 11, "GOOD", "sampler-a", 400);
        submitSeq(batchKey, "CK-S10B", 10, "GOOD", "sampler-a", 409);
        assertEquals(10, seqRowCount(batchKey));
    }

    // ---------- 序号必须连续；跳号/重复 409 且不占序号 ----------

    @Test
    void seqMustStartAtOneAndBeContinuous_rejectedDoesNotConsumeSeq() throws Exception {
        String batchKey = "BK-SEQ-GAP-" + uid();
        createSequentialBatch(batchKey, List.of("t1"));

        // 首条必须是 1
        submitSeq(batchKey, "CK-SKIP0", 2, "GOOD", "sampler-a", 409);
        assertEquals(0, seqRowCount(batchKey));
        submitSeq(batchKey, "CK-S1", 1, "GOOD", "sampler-a", 201);
        // 跳号 3：409，不占序号
        submitSeq(batchKey, "CK-SKIP3", 3, "GOOD", "sampler-a", 409);
        assertEquals(1, seqRowCount(batchKey));
        // 重复序号 1：409
        submitSeq(batchKey, "CK-DUP1", 1, "GOOD", "sampler-a", 409);
        // 被拒绝后期望序号仍是 2，可以正常落定
        MvcResult r2 = submitSeq(batchKey, "CK-S2", 2, "GOOD", "sampler-a", 201);
        assertEquals("PASS", objectMapper.readTree(r2.getResponse().getContentAsString())
                .path("conclusion").asText());
        assertEquals(2, seqRowCount(batchKey));
    }

    @Test
    void invalidSeqAndSample_return400() throws Exception {
        String batchKey = "BK-SEQ-400-" + uid();
        createSequentialBatch(batchKey, List.of("t1"));
        String noSeq = "{\"commandKey\":\"CK-X\",\"sample\":\"GOOD\",\"inspector\":\"i\"}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON).content(noSeq))
                .andExpect(status().isBadRequest());
        String seqZero = "{\"commandKey\":\"CK-X\",\"seq\":0,\"sample\":\"GOOD\",\"inspector\":\"i\"}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON).content(seqZero))
                .andExpect(status().isBadRequest());
        String seqEleven = "{\"commandKey\":\"CK-X\",\"seq\":11,\"sample\":\"GOOD\",\"inspector\":\"i\"}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON).content(seqEleven))
                .andExpect(status().isBadRequest());
        String badSample = "{\"commandKey\":\"CK-X\",\"seq\":1,\"sample\":\"UGLY\",\"inspector\":\"i\"}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON).content(badSample))
                .andExpect(status().isBadRequest());
        String blankInspector = "{\"commandKey\":\"CK-X\",\"seq\":1,\"sample\":\"GOOD\",\"inspector\":\" \"}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON).content(blankInspector))
                .andExpect(status().isBadRequest());
        assertEquals(0, seqRowCount(batchKey));
    }

    @Test
    void sequentialResultOnClassicBatch_returns422_andUnknownBatch404() throws Exception {
        String classicKey = "BK-CLASSIC-" + uid();
        createClassicBatch(classicKey, List.of("t1"));
        submitSeq(classicKey, "CK-S1", 1, "GOOD", "sampler-a", 422);
        mockMvc.perform(post("/api/batches/NO-SUCH-SEQ/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seqBody("CK-S1", 1, "GOOD", "sampler-a")))
                .andExpect(status().isNotFound());
    }

    // ---------- commandKey 幂等：同参回放首次完整响应，异参 409，失败不占键 ----------

    @Test
    void commandKey_sameParamsReplaysFullResponse_changedParamsConflicts_failureDoesNotOccupyKey()
            throws Exception {
        String batchKey = "BK-SEQ-IDEM-" + uid();
        createSequentialBatch(batchKey, List.of("t1"));

        String body = seqBody("CK-SEQ-IDEM-1", 1, "GOOD", "sampler-a");
        MvcResult first = mockMvc.perform(post("/api/batches/" + batchKey + "/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batchKey + "/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, seqRowCount(batchKey));

        // 同 commandKey 异参（样本不同）→ 409
        mockMvc.perform(post("/api/batches/" + batchKey + "/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seqBody("CK-SEQ-IDEM-1", 1, "BAD", "sampler-a")))
                .andExpect(status().isConflict());
        // 同 commandKey 异参（序号不同）→ 409
        mockMvc.perform(post("/api/batches/" + batchKey + "/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seqBody("CK-SEQ-IDEM-1", 2, "GOOD", "sampler-a")))
                .andExpect(status().isConflict());
        // 同 commandKey 异参（检验人不同）→ 409
        mockMvc.perform(post("/api/batches/" + batchKey + "/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seqBody("CK-SEQ-IDEM-1", 1, "GOOD", "sampler-b")))
                .andExpect(status().isConflict());

        // 失败命令不占用 commandKey：此前 CK-FAIL-KEY 跳号失败，随后同键以正确参数可成功
        submitSeq(batchKey, "CK-FAIL-KEY", 3, "GOOD", "sampler-a", 409);
        submitSeq(batchKey, "CK-FAIL-KEY", 2, "GOOD", "sampler-a", 201);
        // 同参回放返回首次成功快照（PASS 终止）
        MvcResult replay2 = mockMvc.perform(post("/api/batches/" + batchKey + "/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seqBody("CK-FAIL-KEY", 2, "GOOD", "sampler-a")))
                .andExpect(status().isCreated()).andReturn();
        assertEquals("PASS", objectMapper.readTree(replay2.getResponse().getContentAsString())
                .path("conclusion").asText());
        assertEquals(2, seqRowCount(batchKey));
    }

    // ---------- 并发：两人同争下一序号至多一条生效，拒绝不增序号 ----------

    @Test
    void concurrentSameSeq_competitionAtMostOneWins_loserGets409AndDoesNotConsumeSeq()
            throws Exception {
        String batchKey = "BK-SEQ-RACE-SEQ-" + uid();
        createSequentialBatch(batchKey, List.of("t1"));
        submitSeq(batchKey, "CK-S1", 1, "GOOD", "sampler-a", 201);

        // 两人（不同 commandKey）同时提交序号 2：GOOD（会终止 PASS）与 BAD
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seqBody("CK-RACE-GOOD", 2, "GOOD", "sampler-a"))),
                () -> callStatus(post("/api/batches/" + batchKey + "/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seqBody("CK-RACE-BAD", 2, "BAD", "sampler-b")))
        );
        int code1 = results.get(0).get(30, TimeUnit.SECONDS);
        int code2 = results.get(1).get(30, TimeUnit.SECONDS);
        List<Integer> codes = List.of(code1, code2);
        assertTrue(codes.contains(201) && codes.contains(409),
                "同序号竞争必须恰有一条成功一条 409，实际: " + codes);
        assertEquals(2, seqRowCount(batchKey), "失败竞争不得新增序号行");
        JsonNode winner = lastSequential(batchKey);
        assertEquals(2, winner.path("seq").asInt());
        if ("GOOD".equals(winner.path("sample").asText())) {
            // GOOD 获胜：比值 9 终止 PASS，BAD 不得再改写到序号 2 或追加序号 3
            assertEquals(9, winner.path("numerator").asLong());
            assertEquals("PASS", winner.path("conclusion").asText());
            submitSeq(batchKey, "CK-AFTER-BAD", 3, "BAD", "sampler-b", 409);
        } else {
            // BAD 获胜：前缀为 GOOD(3/1) 后 BAD，精确分数 3/3（比值回到 1），仍 CONTINUE；
            // 下一条期望序号仍是 3，GOOD 后为 9/3（比值 3）仍 CONTINUE
            assertEquals(3, winner.path("numerator").asLong());
            assertEquals(3, winner.path("denominator").asLong());
            assertEquals("CONTINUE", winner.path("conclusion").asText());
            MvcResult r3 = submitSeq(batchKey, "CK-S3", 3, "GOOD", "sampler-b", 201);
            JsonNode j3 = objectMapper.readTree(r3.getResponse().getContentAsString());
            assertEquals(9, j3.path("numerator").asLong());
            assertEquals(3, j3.path("denominator").asLong());
            assertEquals("CONTINUE", j3.path("conclusion").asText());
            assertEquals(3, seqRowCount(batchKey));
        }
    }

    @Test
    void concurrentSameCommandKeySameParams_bothReplayFirstResult() throws Exception {
        String batchKey = "BK-SEQ-RACE-CMD-" + uid();
        createSequentialBatch(batchKey, List.of("t1"));
        String body = seqBody("CK-RACE-SAME", 1, "GOOD", "sampler-a");
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/" + batchKey + "/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        assertEquals(201, results.get(0).get(30, TimeUnit.SECONDS));
        assertEquals(201, results.get(1).get(30, TimeUnit.SECONDS));
        assertEquals(1, seqRowCount(batchKey));
    }

    @Test
    void concurrentSecondGoodAndApprove_neverReleasesBeforeSequentialPass() throws Exception {
        String batchKey = "BK-SEQ-RACE-AP-" + uid();
        createSequentialBatch(batchKey, List.of("t1"));
        submitSeq(batchKey, "CK-S1", 1, "GOOD", "sampler-a", 201);
        submitClassicTest(batchKey, "TK-1", "t1", "PASS", "insp-1", 201);

        // 同时：第二条 GOOD（序贯 PASS，且经典项已齐→PENDING_RELEASE）与 QUALITY 批准
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seqBody("CK-RACE-S2", 2, "GOOD", "sampler-a"))),
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-RACE-Q\"}"))
        );
        int seqCode = results.get(0).get(30, TimeUnit.SECONDS);
        int approveCode = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, seqCode);
        // 批准先获锁时序贯尚未 PASS（QUARANTINED）→422；序贯先提交后批准→201；绝不能跳过序贯放行
        assertTrue(approveCode == 201 || approveCode == 422, "实际批准码: " + approveCode);
        Integer approvalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE batch_key = ?", Integer.class, batchKey);
        // 若批准已成功则状态至少 RELEASE_REVIEW；否则仍 PENDING_RELEASE，补批即可放行
        if (approvalCount == 1) {
            assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
            approve(batchKey, "ops-1", "OPERATIONS", "CK-O", 201);
            assertEquals("RELEASED", currentStatus(batchKey));
        } else {
            assertEquals("PENDING_RELEASE", currentStatus(batchKey));
            approve(batchKey, "qa-1", "QUALITY", "CK-Q", 201);
            approve(batchKey, "ops-1", "OPERATIONS", "CK-O", 201);
            assertEquals("RELEASED", currentStatus(batchKey));
        }
    }

    // ---------- 拆批：子批继承 SEQUENTIAL 类型但比值从 1 独立重检 ----------

    @Test
    void splitSequentialParent_childrenRestartSeqFromOne_parentHistoryFrozen() throws Exception {
        String parent = "BK-SEQ-SP-P-" + uid();
        releaseSequentialBatch(parent, List.of("t1"));

        String child1 = "BK-SEQ-SP-C1-" + uid();
        String child2 = "BK-SEQ-SP-C2-" + uid();
        String splitBody = "{\"commandKey\":\"CK-SPLIT\",\"children\":["
                + "{\"batchKey\":\"" + child1 + "\",\"batchNo\":\"LC1\"},"
                + "{\"batchKey\":\"" + child2 + "\",\"batchNo\":\"LC2\"}]}";
        MvcResult splitResult = mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(splitBody))
                .andExpect(status().isCreated()).andReturn();
        JsonNode splitJson = objectMapper.readTree(splitResult.getResponse().getContentAsString());
        assertEquals("SEQUENTIAL", splitJson.path("children").get(0).path("assayType").asText());
        assertEquals("SPLIT", currentStatus(parent));

        // 父批历史冻结：序贯前缀仍为 2 行、比值 9，不随后代变化
        JsonNode parentHistory = history(parent);
        assertEquals(2, parentHistory.path("sequentialResults").size());
        assertEquals(9, parentHistory.path("sequentialResults").get(1).path("numerator").asLong());
        assertEquals("PASS", parentHistory.path("sequentialResults").get(1).path("conclusion").asText());

        // 子批独立重新检验：不继承父比值，序号从 1、分数从 1 开始
        assertEquals("QUARANTINED", currentStatus(child1));
        assertEquals(0, history(child1).path("sequentialResults").size());
        MvcResult c1 = submitSeq(child1, "CK-CS1", 1, "BAD", "sampler-x", 201);
        JsonNode cj = objectMapper.readTree(c1.getResponse().getContentAsString());
        assertEquals(1, cj.path("numerator").asLong());
        assertEquals(3, cj.path("denominator").asLong());
        assertEquals("CONTINUE", cj.path("conclusion").asText());
        // 另一子批完全独立：child1 的结果不影响 child2
        assertEquals(0, seqRowCount(child2));
        submitSeq(child2, "CK-C2S1", 1, "GOOD", "sampler-y", 201);

        // 祖先召回：召回父批后，子批禁止新增序贯结果与批准，既有序贯前缀不改写
        mockMvc.perform(post("/api/batches/" + parent + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-RC\",\"reason\":\"原料污染\"}"))
                .andExpect(status().isCreated());
        submitSeq(child1, "CK-CS2", 2, "GOOD", "sampler-x", 422);
        approve(child1, "qa-2", "QUALITY", "CK-CQ", 422);
        assertEquals(1, seqRowCount(child1), "祖先召回后子批序贯前缀不得改写");
        assertEquals("QUARANTINED", currentStatus(child1), "后代自身状态不被祖先召回改写");
    }

    // ---------- 旧无类型批次保持原规则 ----------

    @Test
    void classicBatch_keepsOriginalRules_andHasNoSequentialResults() throws Exception {
        String batchKey = "BK-CLS-" + uid();
        String created = createClassicBatch(batchKey, List.of("t1"));
        assertEquals("CLASSIC", objectMapper.readTree(created).path("assayType").asText());
        submitClassicTest(batchKey, "TK-1", "t1", "PASS", "insp-1", 201);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
        approve(batchKey, "qa-1", "QUALITY", "CK-Q", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-O", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
        assertEquals(0, history(batchKey).path("sequentialResults").size());
    }

    @Test
    void sequentialPass_withoutClassicComplete_neverPending() throws Exception {
        String batchKey = "BK-SEQ-ONLY-" + uid();
        createSequentialBatch(batchKey, List.of("t1", "t2"));
        submitSeq(batchKey, "CK-S1", 1, "GOOD", "sampler-a", 201);
        submitSeq(batchKey, "CK-S2", 2, "GOOD", "sampler-a", 201);
        submitClassicTest(batchKey, "TK-1", "t1", "PASS", "insp-1", 201);
        assertEquals("QUARANTINED", currentStatus(batchKey), "序贯 PASS 但经典项未齐，不得待放行");
        approve(batchKey, "qa-1", "QUALITY", "CK-Q", 422);
        submitClassicTest(batchKey, "TK-2", "t2", "PASS", "insp-2", 201);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
    }

    // ---------- 幂等指纹：十进制同值同参回放；文本必须原样一致 ----------

    @Test
    void decimalEquivalentSeq_replaysFirstResponse_butTextMustBeByteIdentical() throws Exception {
        String batchKey = "BK-SEQ-CANON-" + uid();
        createSequentialBatch(batchKey, List.of("t1"));

        // 序号 1 与十进制写法 1.0 是同值同参：回放首次完整响应
        String intBody = seqBody("CK-CANON-1", 1, "GOOD", "sampler-a");
        MvcResult first = mockMvc.perform(post("/api/batches/" + batchKey + "/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON).content(intBody))
                .andExpect(status().isCreated()).andReturn();
        String decimalBody = "{\"commandKey\":\"CK-CANON-1\",\"seq\":1.0,\"sample\":\"GOOD\","
                + "\"inspector\":\"sampler-a\"}";
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batchKey + "/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON).content(decimalBody))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, seqRowCount(batchKey));

        // 文本参数必须原样：检验人首尾空白与原值不同，同一 commandKey 按异参处理返回 409（且不占序号）
        String spacedInspector = "{\"commandKey\":\"CK-CANON-1\",\"seq\":1,\"sample\":\"GOOD\","
                + "\"inspector\":\" sampler-a\"}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON).content(spacedInspector))
                .andExpect(status().isConflict());
        // 枚举文本大小写不等价，无法解析为 400
        String lowerSample = "{\"commandKey\":\"CK-CANON-3\",\"seq\":2,\"sample\":\"good\","
                + "\"inspector\":\"sampler-a\"}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/sequential-results")
                        .contentType(MediaType.APPLICATION_JSON).content(lowerSample))
                .andExpect(status().isBadRequest());
        // 被 409/400 拒绝的请求不占序号，序号 2 仍可正常提交
        submitSeq(batchKey, "CK-CANON-OK", 2, "GOOD", "sampler-a", 201);
        assertEquals(2, seqRowCount(batchKey));
    }
}

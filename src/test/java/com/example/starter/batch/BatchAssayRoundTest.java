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
 * 平行测定轮次测试：合成均值区间 9～11、极差 0.2 下的主流程、封结判定（不舍入）、
 * 轮次/读数版本冲突、requestId 幂等、批准失效、拆批独立测定、祖先召回拦截与并发竞争。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchAssayRoundTest {

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
        jdbc.update("DELETE FROM assay_reading");
        jdbc.update("DELETE FROM assay_round");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程 ----------

    @Test
    void happyFlow_mixedItems_assayPassLeadsToRelease() throws Exception {
        String batchKey = "BK-AS-HAPPY-" + unique();
        createBatch(batchKey, List.of("外观", "含量"), 201);

        // 非平行检验项沿用原判定
        submitTest(batchKey, testReq("TK-1", "外观", "PASS", "insp-a"), 201);
        assertEquals("QUARANTINED", currentStatus(batchKey));

        // 平行测定项：开轮 + 3 读数 10、10.1、10.2 → 均值 10.1 ∈ [9,11]，极差 0.2 ≤ 0.2 → 合格
        openRound(batchKey, "lab-1", openBody("RQ-O1", "含量", "9", "11", "0.2", 0), 201);
        writeReading(batchKey, "lab-1", readBody("RQ-W1", "含量", 1, "10", 0), 201);
        writeReading(batchKey, "lab-1", readBody("RQ-W2", "含量", 2, "10.1", 1), 201);
        MvcResult sealed = writeReading(batchKey, "lab-1", readBody("RQ-W3", "含量", 3, "10.2", 2), 201);
        JsonNode round = objectMapper.readTree(sealed.getResponse().getContentAsString());
        assertEquals("PASSED", round.path("status").asText());
        assertEquals("10.1", round.path("mean").asText());
        assertEquals("0.2", round.path("range").asText());
        assertEquals(3, round.path("readings").size());
        assertEquals("PENDING_RELEASE", round.path("batchStatus").asText());
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));

        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 批准冻结当前轮与三条原值
        String snapshot = jdbc.queryForObject(
                "SELECT assay_snapshot FROM approval WHERE batch_key = ? AND seq = 1",
                String.class, batchKey);
        assertNotNull(snapshot, "平行测定批次批准必须冻结轮次快照");
        assertTrue(snapshot.contains("\"roundNo\":1"));
        assertTrue(snapshot.contains("10.2"));

        // 历史含轮次与读数原值
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        assertEquals(1, node.path("assayRounds").size());
        assertEquals("PASSED", node.path("assayRounds").get(0).path("status").asText());
        assertEquals("10.2", node.path("assayRounds").get(0).path("readings").get(2)
                .path("value").asText());
    }

    @Test
    void rangeExceeded_roundFailsEvenWhenMeanInInterval_thenNewRoundPasses() throws Exception {
        String batchKey = "BK-AS-RANGE-" + unique();
        createBatch(batchKey, List.of("含量"), 201);

        // 10、10、10.201：均值 10.067 ∈ [9,11] 但极差 0.201 > 0.2 → 不合格，不能放行
        openRound(batchKey, "lab-1", openBody("RQ-O1", "含量", "9", "11", "0.2", 0), 201);
        writeReading(batchKey, "lab-1", readBody("RQ-W1", "含量", 1, "10", 0), 201);
        writeReading(batchKey, "lab-1", readBody("RQ-W2", "含量", 2, "10", 1), 201);
        MvcResult sealed = writeReading(batchKey, "lab-1", readBody("RQ-W3", "含量", 3, "10.201", 2), 201);
        JsonNode round = objectMapper.readTree(sealed.getResponse().getContentAsString());
        assertEquals("FAILED", round.path("status").asText());
        assertEquals("10.067", round.path("mean").asText());
        assertEquals("0.201", round.path("range").asText());
        assertEquals("QUARANTINED", currentStatus(batchKey));
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 422);

        // 封结后不能覆写
        writeReading(batchKey, "lab-1", readBody("RQ-W4", "含量", 3, "10.1", 3), 409);

        // 不合格开新轮（第 2 轮），通过后放行
        openRound(batchKey, "lab-2", openBody("RQ-O2", "含量", "9", "11", "0.2", 1), 201);
        writeReading(batchKey, "lab-2", readBody("RQ-W5", "含量", 1, "10", 0), 201);
        writeReading(batchKey, "lab-2", readBody("RQ-W6", "含量", 2, "10.1", 1), 201);
        writeReading(batchKey, "lab-2", readBody("RQ-W7", "含量", 3, "10.2", 2), 201);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
        approve(batchKey, "qa-1", "QUALITY", "CK-A2", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A3", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        Integer failed = jdbc.queryForObject("SELECT COUNT(*) FROM assay_round"
                + " WHERE batch_key = ? AND status = 'FAILED'", Integer.class, batchKey);
        assertEquals(1, failed);
    }

    @Test
    void meanNotRounded_meanAboveIntervalFails() throws Exception {
        String batchKey = "BK-AS-ROUND-" + unique();
        createBatch(batchKey, List.of("含量"), 201);
        // 区间 [9,10]：10、10.1、10.2 均值 10.1，若先取整为 10 会误判合格
        openRound(batchKey, "lab-1", openBody("RQ-O1", "含量", "9", "10", "1", 0), 201);
        writeReading(batchKey, "lab-1", readBody("RQ-W1", "含量", 1, "10", 0), 201);
        writeReading(batchKey, "lab-1", readBody("RQ-W2", "含量", 2, "10.1", 1), 201);
        MvcResult sealed = writeReading(batchKey, "lab-1", readBody("RQ-W3", "含量", 3, "10.2", 2), 201);
        assertEquals("FAILED", objectMapper.readTree(sealed.getResponse().getContentAsString())
                .path("status").asText());
        assertEquals("QUARANTINED", currentStatus(batchKey));
    }

    // ---------- 拒绝分支 ----------

    @Test
    void approveBlocked_whenRoundIncompleteOrFailed() throws Exception {
        String batchKey = "BK-AS-BLOCK-" + unique();
        createBatch(batchKey, List.of("含量"), 201);
        approve(batchKey, "qa-1", "QUALITY", "CK-A0", 422);

        openRound(batchKey, "lab-1", openBody("RQ-O1", "含量", "9", "11", "0.2", 0), 201);
        writeReading(batchKey, "lab-1", readBody("RQ-W1", "含量", 1, "10", 0), 201);
        writeReading(batchKey, "lab-1", readBody("RQ-W2", "含量", 2, "10.1", 1), 201);
        // 当前轮未齐：双角色批准均 422
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 422);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A2", 422);
        assertEquals("QUARANTINED", currentStatus(batchKey));
    }

    @Test
    void expectedVersionMismatch_returns409_andFailureLeavesNoPlaceholder() throws Exception {
        String batchKey = "BK-AS-VER-" + unique();
        createBatch(batchKey, List.of("含量"), 201);

        // 开轮 expectedVersion 不符 → 409，且失败不占键、不留轮次
        openRound(batchKey, "lab-1", openBody("RQ-O1", "含量", "9", "11", "0.2", 1), 409);
        assertEquals(0, countRows("assay_round", batchKey));
        assertEquals(0, countCommand("OPEN_ASSAY_ROUND", "RQ-O1"));
        openRound(batchKey, "lab-1", openBody("RQ-O1", "含量", "9", "11", "0.2", 0), 201);

        // 写读数 expectedVersion 不符 → 409，失败无读数占位
        writeReading(batchKey, "lab-1", readBody("RQ-W1", "含量", 1, "10", 2), 409);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM assay_reading",
                Integer.class));
        assertEquals(0, countCommand("WRITE_ASSAY_READING", "RQ-W1"));
        writeReading(batchKey, "lab-1", readBody("RQ-W1", "含量", 1, "10", 0), 201);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM assay_reading", Integer.class));
    }

    @Test
    void readingSeqUnique_perRound_andRoundLimitThree() throws Exception {
        String batchKey = "BK-AS-SEQ-" + unique();
        createBatch(batchKey, List.of("含量"), 201);
        openRound(batchKey, "lab-1", openBody("RQ-O1", "含量", "9", "11", "0.2", 0), 201);
        writeReading(batchKey, "lab-1", readBody("RQ-W1", "含量", 1, "10", 0), 201);
        // 同轮同序号不同 requestId → 409
        writeReading(batchKey, "lab-1", readBody("RQ-W2", "含量", 1, "10.1", 1), 409);
        writeReading(batchKey, "lab-1", readBody("RQ-W3", "含量", 2, "20", 1), 201);
        writeReading(batchKey, "lab-1", readBody("RQ-W4", "含量", 3, "20", 2), 201);

        // 连续 3 轮不合格后达到上限
        for (int round = 2; round <= 3; round++) {
            openRound(batchKey, "lab-1",
                    openBody("RQ-O" + round, "含量", "9", "11", "0.2", round - 1), 201);
            writeReading(batchKey, "lab-1", readBody("RQ-W" + round + "a", "含量", 1, "20", 0), 201);
            writeReading(batchKey, "lab-1", readBody("RQ-W" + round + "b", "含量", 2, "20", 1), 201);
            writeReading(batchKey, "lab-1", readBody("RQ-W" + round + "c", "含量", 3, "20", 2), 201);
        }
        assertEquals(3, countRows("assay_round", batchKey));
        openRound(batchKey, "lab-1", openBody("RQ-O4", "含量", "9", "11", "0.2", 3), 409);
    }

    @Test
    void assayItemExclusivity_withPlainTestResult() throws Exception {
        String batchKey = "BK-AS-EXCL-" + unique();
        createBatch(batchKey, List.of("外观", "含量"), 201);
        submitTest(batchKey, testReq("TK-1", "外观", "PASS", "insp"), 201);
        // 已有检验结果的项不可开轮
        openRound(batchKey, "lab-1", openBody("RQ-O1", "外观", "9", "11", "0.2", 0), 409);
        // 已开轮的项不能再走原检验判定
        openRound(batchKey, "lab-1", openBody("RQ-O2", "含量", "9", "11", "0.2", 0), 201);
        submitTest(batchKey, testReq("TK-2", "含量", "PASS", "insp"), 409);
        // 非必做项不可开轮
        openRound(batchKey, "lab-1", openBody("RQ-O3", "不存在项", "9", "11", "0.2", 0), 422);
    }

    @Test
    void invalidDecimalAndParams_return400() throws Exception {
        String batchKey = "BK-AS-400-" + unique();
        createBatch(batchKey, List.of("含量"), 201);
        // 超过 4 位小数
        openRound(batchKey, "lab-1", openBody("RQ-O1", "含量", "9.12345", "11", "0.2", 0), 400);
        // 区间倒置
        openRound(batchKey, "lab-1", openBody("RQ-O2", "含量", "11", "9", "0.2", 0), 400);
        // 负极差
        openRound(batchKey, "lab-1", openBody("RQ-O3", "含量", "9", "11", "-0.1", 0), 400);
        // 缺操作人
        mockMvc.perform(post("/api/batches/" + batchKey + "/assay-rounds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(openBody("RQ-O4", "含量", "9", "11", "0.2", 0)))
                .andExpect(status().isBadRequest());

        openRound(batchKey, "lab-1", openBody("RQ-O5", "含量", "9", "11", "0.2", 0), 201);
        // 读数超 4 位小数 / 序号越界
        writeReading(batchKey, "lab-1", readBody("RQ-W1", "含量", 1, "10.12345", 0), 400);
        writeReading(batchKey, "lab-1", readBody("RQ-W2", "含量", 4, "10", 0), 400);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM assay_reading", Integer.class));
    }

    // ---------- 批准失效与放行边界 ----------

    @Test
    void newRoundInvalidatesOldApprovals_cannotReuseOldRoundPass() throws Exception {
        String batchKey = "BK-AS-INVAL-" + unique();
        createBatch(batchKey, List.of("含量"), 201);
        passRound(batchKey, "RQ-R1", "RQ-W1", 1);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 201);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));

        // 开第 2 轮：旧批准失效，批次回退隔离；不能用旧轮合格授权新轮
        openRound(batchKey, "lab-2", openBody("RQ-O2", "含量", "9", "11", "0.2", 1), 201);
        assertEquals("QUARANTINED", currentStatus(batchKey));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM approval"
                + " WHERE batch_key = ? AND valid = 1", Integer.class, batchKey));
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A2", 422);

        // 新轮（第 2 轮已开启）通过后须重新完成双角色批准
        writeReading(batchKey, "lab-2", readBody("RQ-W2-a", "含量", 1, "10", 0), 201);
        writeReading(batchKey, "lab-2", readBody("RQ-W2-b", "含量", 2, "10.1", 1), 201);
        writeReading(batchKey, "lab-2", readBody("RQ-W2-c", "含量", 3, "10.2", 2), 201);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
        approve(batchKey, "qa-1", "QUALITY", "CK-A3", 201);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A4", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 历史保留全部 3 笔批准，仅 2 笔有效
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE batch_key = ?",
                Integer.class, batchKey));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM approval"
                + " WHERE batch_key = ? AND valid = 1", Integer.class, batchKey));
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(3, objectMapper.readTree(history.getResponse().getContentAsString())
                .path("approvals").size());
    }

    @Test
    void releasedBatch_cannotOpenRoundOrWriteReading() throws Exception {
        String batchKey = "BK-AS-REL-" + unique();
        createBatch(batchKey, List.of("含量"), 201);
        passRound(batchKey, "RQ-R1", "RQ-W1", 1);
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        openRound(batchKey, "lab-1", openBody("RQ-O2", "含量", "9", "11", "0.2", 1), 409);
        writeReading(batchKey, "lab-1", readBody("RQ-W9", "含量", 1, "10", 0), 409);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    // ---------- requestId 幂等 ----------

    @Test
    void requestId_sameParamsReplayFirstResponse_changedParamsConflict() throws Exception {
        String batchKey = "BK-AS-IDEM-" + unique();
        createBatch(batchKey, List.of("含量"), 201);

        String openBody = openBody("RQ-O1", "含量", "9", "11", "0.2", 0);
        MvcResult first = openRound(batchKey, "lab-1", openBody, 201);
        MvcResult replay = openRound(batchKey, "lab-1", openBody, 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, countRows("assay_round", batchKey));
        // 同 requestId 改参 → 409
        openRound(batchKey, "lab-1", openBody("RQ-O1", "含量", "9", "12", "0.2", 0), 409);
        openRound(batchKey, "lab-2", openBody("RQ-O1", "含量", "9", "11", "0.2", 0), 409);

        String readBody = readBody("RQ-W1", "含量", 1, "10", 0);
        MvcResult w1 = writeReading(batchKey, "lab-1", readBody, 201);
        MvcResult w1Replay = writeReading(batchKey, "lab-1", readBody, 201);
        assertEquals(w1.getResponse().getContentAsString(),
                w1Replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM assay_reading", Integer.class));
        writeReading(batchKey, "lab-1", readBody("RQ-W1", "含量", 1, "10.1", 0), 409);
    }

    // ---------- 并发 ----------

    @Test
    void concurrentSameReadingRequest_sameWinnerReplayed() throws Exception {
        String batchKey = "BK-AS-CIDEM-" + unique();
        createBatch(batchKey, List.of("含量"), 201);
        openRound(batchKey, "lab-1", openBody("RQ-O1", "含量", "9", "11", "0.2", 0), 201);

        String body = readBody("RQ-W1", "含量", 1, "10", 0);
        List<Future<Integer>> results = runConcurrent(
                () -> readingStatus(batchKey, body),
                () -> readingStatus(batchKey, body));
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM assay_reading", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT version FROM assay_round WHERE batch_key = ?",
                Integer.class, batchKey));
    }

    @Test
    void concurrentReadings_versionConflictYieldsSingleWinner() throws Exception {
        String batchKey = "BK-AS-CVER-" + unique();
        createBatch(batchKey, List.of("含量"), 201);
        openRound(batchKey, "lab-1", openBody("RQ-O1", "含量", "9", "11", "0.2", 0), 201);

        // 两个不同 requestId 同时以 expectedVersion 0 写不同序号：行锁串行后仅一个成功
        List<Future<Integer>> results = runConcurrent(
                () -> readingStatus(batchKey, readBody("RQ-W1", "含量", 1, "10", 0)),
                () -> readingStatus(batchKey, readBody("RQ-W2", "含量", 2, "10.1", 0)));
        int created = 0;
        int conflict = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                created++;
            } else if (code == 409) {
                conflict++;
            } else {
                fail("意外状态码: " + code);
            }
        }
        assertEquals(1, created);
        assertEquals(1, conflict);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM assay_reading", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT version FROM assay_round WHERE batch_key = ?",
                Integer.class, batchKey));
    }

    @Test
    void concurrentOpenRound_sameRequestIdReplays_singleRound() throws Exception {
        String batchKey = "BK-AS-COPEN-" + unique();
        createBatch(batchKey, List.of("含量"), 201);
        String body = openBody("RQ-O1", "含量", "9", "11", "0.2", 0);
        List<Future<Integer>> results = runConcurrent(
                () -> openStatus(batchKey, body),
                () -> openStatus(batchKey, body));
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS));
        }
        assertEquals(1, countRows("assay_round", batchKey));
    }

    // ---------- 拆批与祖先召回 ----------

    @Test
    void splitChild_needsIndependentAssay_notInheritingParentRounds() throws Exception {
        String parent = "BK-AS-SP-P-" + unique();
        createBatch(parent, List.of("含量"), 201);
        passRound(parent, "RQ-R1", "RQ-W1", 1);
        approve(parent, "qa-1", "QUALITY", "CK-A1", 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(parent));

        String child = "BK-AS-SP-C-" + unique();
        String splitBody = "{\"commandKey\":\"CK-SP-1\",\"children\":["
                + "{\"batchKey\":\"" + child + "\",\"batchNo\":\"LOT-C1\"},"
                + "{\"batchKey\":\"" + child + "-2\",\"batchNo\":\"LOT-C2\"}]}";
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(splitBody))
                .andExpect(status().isCreated());

        // 子批不继承父轮，须独立测定
        MvcResult rounds = mockMvc.perform(get("/api/batches/" + child + "/assay-rounds"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, objectMapper.readTree(rounds.getResponse().getContentAsString()).size());
        assertEquals("QUARANTINED", currentStatus(child));
        approve(child, "qa-9", "QUALITY", "CK-CA1", 422);

        passRound(child, "RQ-CR1", "RQ-CW1", 1);
        assertEquals("PENDING_RELEASE", currentStatus(child));
    }

    @Test
    void ancestorRecall_blocksRoundAndReadingOnDescendant() throws Exception {
        String parent = "BK-AS-RC-P-" + unique();
        createBatch(parent, List.of("含量"), 201);
        passRound(parent, "RQ-R1", "RQ-W1", 1);
        approve(parent, "qa-1", "QUALITY", "CK-A1", 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-A2", 201);

        String child = "BK-AS-RC-C-" + unique();
        String splitBody = "{\"commandKey\":\"CK-SP-1\",\"children\":["
                + "{\"batchKey\":\"" + child + "\",\"batchNo\":\"LOT-C1\"},"
                + "{\"batchKey\":\"" + child + "-2\",\"batchNo\":\"LOT-C2\"}]}";
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(splitBody))
                .andExpect(status().isCreated());
        openRound(child, "lab-1", openBody("RQ-CO1", "含量", "9", "11", "0.2", 0), 201);

        recall(parent, "u-1", "CK-RC-1", 201);

        openRound(child, "lab-1", openBody("RQ-CO2", "含量", "9", "11", "0.2", 1), 422);
        writeReading(child, "lab-1", readBody("RQ-CW1", "含量", 1, "10", 0), 422);
        assertEquals("QUARANTINED", currentStatus(child));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String createBody(String batchKey, List<String> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", "CK-CREATE-" + unique());
        body.put("batchKey", batchKey);
        body.put("productCode", "PROD-1");
        body.put("batchNo", "LOT-1");
        body.put("producedAt", Instant.parse("2026-01-02T03:04:05Z").toString());
        body.put("requiredTests", items);
        return toJson(body);
    }

    private void createBatch(String batchKey, List<String> items, int expected) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(batchKey, items)))
                .andExpect(status().is(expected));
    }

    private String testReq(String testKey, String item, String result, String inspector) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", "CK-T-" + unique());
        body.put("testKey", testKey);
        body.put("testItem", item);
        body.put("result", result);
        body.put("inspector", inspector);
        return toJson(body);
    }

    private void submitTest(String batchKey, String body, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private String openBody(String requestId, String testItem, String meanMin, String meanMax,
                            String maxRange, int expectedVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("testItem", testItem);
        body.put("meanMin", meanMin);
        body.put("meanMax", meanMax);
        body.put("maxRange", maxRange);
        body.put("expectedVersion", expectedVersion);
        return toJson(body);
    }

    private String readBody(String requestId, String testItem, int seq, String value,
                            int expectedVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("testItem", testItem);
        body.put("seq", seq);
        body.put("value", value);
        body.put("expectedVersion", expectedVersion);
        return toJson(body);
    }

    private MvcResult openRound(String batchKey, String actor, String body, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/assay-rounds")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult writeReading(String batchKey, String actor, String body, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/assay-readings")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    /**
     * 以合成区间 [9,11]、极差 0.2 开第 roundNo 轮并写入 10、10.1、10.2 使其合格；
     * openRequestId 为 null 时复用 readPrefix 派生键。
     */
    private void passRound(String batchKey, String openRequestId, String readPrefix,
                           int roundNo) throws Exception {
        String openId = openRequestId == null ? readPrefix + "-O" : openRequestId;
        openRound(batchKey, "lab-1", openBody(openId, "含量", "9", "11", "0.2",
                roundNo - 1), 201);
        writeReading(batchKey, "lab-1", readBody(readPrefix + "-a", "含量", 1, "10", 0), 201);
        writeReading(batchKey, "lab-1", readBody(readPrefix + "-b", "含量", 2, "10.1", 1), 201);
        writeReading(batchKey, "lab-1", readBody(readPrefix + "-c", "含量", 3, "10.2", 2), 201);
    }

    private void approve(String batchKey, String actor, String role, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    private void recall(String batchKey, String actor, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"质量风险\"}"))
                .andExpect(status().is(expected));
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private int countRows(String table, String batchKey) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE batch_key = ?",
                Integer.class, batchKey);
        return count == null ? 0 : count;
    }

    private int countCommand(String type, String requestId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM command_log"
                + " WHERE command_type = ? AND command_key = ?", Integer.class, type, requestId);
        return count == null ? 0 : count;
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int openStatus(String batchKey, String body) {
        try {
            return mockMvc.perform(post("/api/batches/" + batchKey + "/assay-rounds")
                            .header("X-Actor-Id", "lab-1")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int readingStatus(String batchKey, String body) {
        try {
            return mockMvc.perform(post("/api/batches/" + batchKey + "/assay-readings")
                            .header("X-Actor-Id", "lab-1")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus();
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

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
 * 平行测定轮次端到端测试：主流程（均值闭区间+极差判定）、拒绝分支、
 * requestId 幂等重放、expectedVersion 乐观锁、并发写读数、新轮使旧批准失效、
 * 拆批独立测定与祖先召回拦截。全部在真实 H2（MODE=MySQL）上验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AssayRoundTest {

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
        jdbc.update("DELETE FROM assay_reading");
        jdbc.update("DELETE FROM assay_round");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程 ----------

    @Test
    void happyFlow_assayRoundPassThenDualApprovalReleases() throws Exception {
        String batchKey = "BK-ASSAY-HAPPY-" + unique();
        createBatch(batchKey, List.of("外观", "含量"), 201);
        // 非平行项沿用旧判定
        submitTest(batchKey, testReq("TK-1", "外观", "PASS", "insp-0"), 201);

        // 平行项：区间 [9,11]，极差 0.2；读数 10、10.1、10.2 合格
        long roundId = openRound(batchKey, "RQ-O1", "含量", "op-1", 0, "9", "11", "0.2", 201);
        writeReading(batchKey, roundId, "RQ-R1", 1, "10", "op-1", 0, 201);
        writeReading(batchKey, roundId, "RQ-R2", 2, "10.1", "op-2", 1, 201);
        assertEquals("QUARANTINED", currentStatus(batchKey));
        MvcResult third = writeReading(batchKey, roundId, "RQ-R3", 3, "10.2", "op-3", 2, 201);
        JsonNode sealed = objectMapper.readTree(third.getResponse().getContentAsString());
        assertEquals("SEALED_PASS", sealed.path("roundStatus").asText());
        assertEquals("PENDING_RELEASE", sealed.path("batchStatus").asText());
        assertEquals(3, sealed.path("version").asInt());
        assertEquals("10.1", sealed.path("mean").asText());
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));

        // 双角色批准放行（读数操作员不得批准，另选批准人）
        approve(batchKey, "qa-1", "QUALITY", "CK-AQ", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AO", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 轮次与三条原值冻结可查
        JsonNode rounds = getRounds(batchKey);
        assertEquals(1, rounds.size());
        JsonNode round = rounds.get(0);
        assertEquals("SEALED_PASS", round.path("round").path("status").asText());
        assertEquals(3, round.path("readings").size());
        assertEquals("10", round.path("readings").get(0).path("value").asText());
        assertEquals("10.1", round.path("readings").get(1).path("value").asText());
        assertEquals("10.2", round.path("readings").get(2).path("value").asText());

        // 真实落库核验
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM assay_round WHERE batch_key = ? AND status = 'SEALED_PASS'",
                Integer.class, batchKey));
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM assay_reading WHERE batch_key = ?", Integer.class, batchKey));
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type LIKE 'ASSAY_%'",
                Integer.class));
    }

    @Test
    void rangeExceeded_meanWithinIntervalStillFails() throws Exception {
        String batchKey = "BK-ASSAY-RANGE-" + unique();
        createBatch(batchKey, List.of("含量"), 201);
        long roundId = openRound(batchKey, "RQ-O1", "含量", "op-1", 0, "9", "11", "0.2", 201);
        // 10、10、10.201：均值 10.067 在区间内，但极差 0.201 > 0.2，不能放行
        writeReading(batchKey, roundId, "RQ-R1", 1, "10", "op-1", 0, 201);
        writeReading(batchKey, roundId, "RQ-R2", 2, "10", "op-2", 1, 201);
        MvcResult third = writeReading(batchKey, roundId, "RQ-R3", 3, "10.201", "op-3", 2, 201);
        JsonNode sealed = objectMapper.readTree(third.getResponse().getContentAsString());
        assertEquals("SEALED_FAIL", sealed.path("roundStatus").asText());
        assertEquals("QUARANTINED", sealed.path("batchStatus").asText());
        assertEquals("QUARANTINED", currentStatus(batchKey));

        // 当前轮不合格，双角色批准 422
        approve(batchKey, "qa-1", "QUALITY", "CK-AQ", 422);

        // 不合格可开新轮次：第 2 轮合格后可放行
        long round2 = openRound(batchKey, "RQ-O2", "含量", "op-1", 1, "9", "11", "0.2", 201);
        writeReading(batchKey, round2, "RQ-R4", 1, "10", "op-1", 0, 201);
        writeReading(batchKey, round2, "RQ-R5", 2, "10.1", "op-2", 1, 201);
        writeReading(batchKey, round2, "RQ-R6", 3, "10.2", "op-3", 2, 201);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
        approve(batchKey, "qa-1", "QUALITY", "CK-AQ2", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AO2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    @Test
    void meanComparisonNotRounded_boundaryAndAboveMax() throws Exception {
        // 闭区间边界：均值恰为上界 11、极差恰为 0.2 → 合格
        String batchKey = "BK-ASSAY-EDGE-" + unique();
        createBatch(batchKey, List.of("含量"), 201);
        long roundId = openRound(batchKey, "RQ-O1", "含量", "op-1", 0, "9", "11", "0.2", 201);
        writeReading(batchKey, roundId, "RQ-R1", 1, "10.9", "op-1", 0, 201);
        writeReading(batchKey, roundId, "RQ-R2", 2, "11", "op-2", 1, 201);
        MvcResult third = writeReading(batchKey, roundId, "RQ-R3", 3, "11.1", "op-3", 2, 201);
        assertEquals("SEALED_PASS", objectMapper.readTree(third.getResponse().getContentAsString())
                .path("roundStatus").asText());

        // 均值 11.0000333… 超出上界：不得先把均值取整为 11.0000 放行
        String batchKey2 = "BK-ASSAY-NOROUND-" + unique();
        createBatch(batchKey2, List.of("含量"), 201);
        long round2 = openRound(batchKey2, "RQ-O2", "含量", "op-1", 0, "9", "11", "1", 201);
        writeReading(batchKey2, round2, "RQ-S1", 1, "11", "op-1", 0, 201);
        writeReading(batchKey2, round2, "RQ-S2", 2, "11", "op-2", 1, 201);
        MvcResult third2 = writeReading(batchKey2, round2, "RQ-S3", 3, "11.0001", "op-3", 2, 201);
        JsonNode sealed2 = objectMapper.readTree(third2.getResponse().getContentAsString());
        assertEquals("SEALED_FAIL", sealed2.path("roundStatus").asText());
        assertEquals("QUARANTINED", currentStatus(batchKey2));
    }

    // ---------- 开轮拒绝分支 ----------

    @Test
    void openRound_rejections() throws Exception {
        String batchKey = "BK-ASSAY-OPEN-" + unique();
        createBatch(batchKey, List.of("外观", "含量"), 201);

        // 非必做项 → 422
        openRound(batchKey, "RQ-X1", "不存在项", "op-1", 0, "9", "11", "0.2", 422);
        // 小数位超过 4 位 → 400
        openRound(batchKey, "RQ-X2", "含量", "op-1", 0, "9.00001", "11", "0.2", 400);
        // 区间下界大于上界 → 400
        openRound(batchKey, "RQ-X3", "含量", "op-1", 0, "11", "9", "0.2", 400);
        // 极差为负 → 400
        openRound(batchKey, "RQ-X4", "含量", "op-1", 0, "9", "11", "-0.1", 400);
        // expectedVersion 不符（首轮应为 0）→ 409
        openRound(batchKey, "RQ-X5", "含量", "op-1", 1, "9", "11", "0.2", 409);

        // 旧式检验项不能开轮；已开轮项不能再走旧式提交
        submitTest(batchKey, testReq("TK-1", "外观", "PASS", "insp-0"), 201);
        openRound(batchKey, "RQ-X6", "外观", "op-1", 0, "9", "11", "0.2", 409);
        long roundId = openRound(batchKey, "RQ-O1", "含量", "op-1", 0, "9", "11", "0.2", 201);
        submitTest(batchKey, testReq("TK-2", "含量", "PASS", "insp-1"), 409);

        // 当前轮未封结不能开新轮
        openRound(batchKey, "RQ-X7", "含量", "op-1", 1, "9", "11", "0.2", 409);

        // 失败开轮不留轮次占位
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM assay_round WHERE batch_key = ?", Integer.class, batchKey));

        // 封结不合格后连续开满 3 轮，第 4 轮 → 409
        failRound(batchKey, roundId, "RQ-F1");
        long round2 = openRound(batchKey, "RQ-O2", "含量", "op-1", 1, "9", "11", "0.2", 201);
        failRound(batchKey, round2, "RQ-F2");
        long round3 = openRound(batchKey, "RQ-O3", "含量", "op-1", 2, "9", "11", "0.2", 201);
        failRound(batchKey, round3, "RQ-F3");
        openRound(batchKey, "RQ-O4", "含量", "op-1", 3, "9", "11", "0.2", 409);
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM assay_round WHERE batch_key = ?", Integer.class, batchKey));
    }

    @Test
    void openRound_releasedBatchRejected() throws Exception {
        String batchKey = "BK-ASSAY-REL-" + unique();
        createBatch(batchKey, List.of("含量"), 201);
        long roundId = openRound(batchKey, "RQ-O1", "含量", "op-1", 0, "9", "11", "0.2", 201);
        passRound(batchKey, roundId, "RQ-P1");
        approve(batchKey, "qa-1", "QUALITY", "CK-AQ", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AO", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
        // 已放行不可开轮
        openRound(batchKey, "RQ-O2", "含量", "op-1", 1, "9", "11", "0.2", 409);
    }

    // ---------- 读数拒绝分支 ----------

    @Test
    void writeReading_rejections() throws Exception {
        String batchKey = "BK-ASSAY-WR-" + unique();
        createBatch(batchKey, List.of("含量"), 201);
        long roundId = openRound(batchKey, "RQ-O1", "含量", "op-1", 0, "9", "11", "0.2", 201);

        // expectedVersion 不符 → 409，且不留读数占位
        writeReading(batchKey, roundId, "RQ-X1", 1, "10", "op-1", 2, 409);
        // 序号越界 → 400
        writeReading(batchKey, roundId, "RQ-X2", 4, "10", "op-1", 0, 400);
        // 读数超过 4 位小数 → 400
        writeReading(batchKey, roundId, "RQ-X3", 1, "10.00001", "op-1", 0, 400);
        // 不存在的轮次 → 404
        writeReading(batchKey, 999999L, "RQ-X4", 1, "10", "op-1", 0, 404);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM assay_reading WHERE round_id = ?", Integer.class, roundId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT version FROM assay_round WHERE id = ?", Integer.class, roundId));

        writeReading(batchKey, roundId, "RQ-R1", 1, "10", "op-1", 0, 201);
        // 同轮同序号唯一
        writeReading(batchKey, roundId, "RQ-X5", 1, "10.1", "op-2", 1, 409);
        writeReading(batchKey, roundId, "RQ-R2", 2, "10.1", "op-2", 1, 201);
        writeReading(batchKey, roundId, "RQ-R3", 3, "10.2", "op-3", 2, 201);
        // 轮次封结后不能覆写
        writeReading(batchKey, roundId, "RQ-X6", 1, "10.3", "op-1", 3, 409);
        writeReading(batchKey, roundId, "RQ-X7", 2, "10.3", "op-1", 3, 409);

        // 三条原值保持不变
        JsonNode rounds = getRounds(batchKey);
        JsonNode readings = rounds.get(0).path("readings");
        assertEquals(3, readings.size());
        assertEquals("10", readings.get(0).path("value").asText());
        assertEquals("10.1", readings.get(1).path("value").asText());
        assertEquals("10.2", readings.get(2).path("value").asText());
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM assay_reading WHERE round_id = ?", Integer.class, roundId));
    }

    @Test
    void writeReading_otherBatchRound_returns404() throws Exception {
        String batchA = "BK-ASSAY-A-" + unique();
        String batchB = "BK-ASSAY-B-" + unique();
        createBatch(batchA, List.of("含量"), 201);
        createBatch(batchB, List.of("含量"), 201);
        long roundA = openRound(batchA, "RQ-O1", "含量", "op-1", 0, "9", "11", "0.2", 201);
        // 轮次不属于该批次 → 404
        writeReading(batchB, roundA, "RQ-X1", 1, "10", "op-1", 0, 404);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM assay_reading WHERE round_id = ?", Integer.class, roundA));
    }

    @Test
    void readingOperator_cannotApprove() throws Exception {
        String batchKey = "BK-ASSAY-OPER-" + unique();
        createBatch(batchKey, List.of("含量"), 201);
        long roundId = openRound(batchKey, "RQ-O1", "含量", "op-1", 0, "9", "11", "0.2", 201);
        passRound(batchKey, roundId, "RQ-P1");
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
        // 读数操作员不得担任批准人
        approve(batchKey, "op-1", "QUALITY", "CK-X1", 422);
        approve(batchKey, "op-2", "OPERATIONS", "CK-X2", 422);
        approve(batchKey, "qa-1", "QUALITY", "CK-AQ", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AO", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    // ---------- 新轮使旧批准失效 ----------

    @Test
    void newRound_invalidatesOldApprovals() throws Exception {
        String batchKey = "BK-ASSAY-INV-" + unique();
        createBatch(batchKey, List.of("含量"), 201);
        long round1 = openRound(batchKey, "RQ-O1", "含量", "op-1", 0, "9", "11", "0.2", 201);
        passRound(batchKey, round1, "RQ-P1");
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
        approve(batchKey, "qa-1", "QUALITY", "CK-AQ", 201);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));

        // 开新轮：批次回退隔离，旧批准失效，不能用旧轮合格授权新轮
        long round2 = openRound(batchKey, "RQ-O2", "含量", "op-1", 1, "9", "11", "0.2", 201);
        assertEquals("QUARANTINED", currentStatus(batchKey));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE batch_key = ?", Integer.class, batchKey));
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AO", 422);

        // 新轮重新测定合格后再走完整双角色批准
        passRound(batchKey, round2, "RQ-P2");
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
        approve(batchKey, "qa-1", "QUALITY", "CK-AQ2", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AO2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    // ---------- requestId 幂等 ----------

    @Test
    void requestId_sameParamsReplay_changedParamsConflict_failureDoesNotOccupyKey() throws Exception {
        String batchKey = "BK-ASSAY-IDEM-" + unique();
        createBatch(batchKey, List.of("含量"), 201);

        // 同 requestId 同参数重放：返回首次完整响应，不新增轮次
        MvcResult first = openRoundRaw(batchKey, "RQ-O1", "含量", "op-1", 0, "9", "11", "0.2", 201);
        MvcResult replay = openRoundRaw(batchKey, "RQ-O1", "含量", "op-1", 0, "9", "11", "0.2", 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 同 requestId 异参 → 409
        openRound(batchKey, "RQ-O1", "含量", "op-1", 0, "9", "11", "0.3", 409);
        openRound(batchKey, "RQ-O1", "含量", "op-2", 0, "9", "11", "0.2", 409);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM assay_round WHERE batch_key = ?", Integer.class, batchKey));

        long roundId = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("roundId").asLong();

        // 失败不占键：先用 RQ-R1 发一个必失败请求（expectedVersion 不符），再同键正确参数成功
        writeReading(batchKey, roundId, "RQ-R1", 1, "10", "op-1", 2, 409);
        MvcResult ok = writeReading(batchKey, roundId, "RQ-R1", 1, "10", "op-1", 0, 201);
        // 同键同参重放返回首次完整响应
        MvcResult okReplay = writeReading(batchKey, roundId, "RQ-R1", 1, "10", "op-1", 0, 201);
        assertEquals(ok.getResponse().getContentAsString(),
                okReplay.getResponse().getContentAsString());
        // 同键异参 → 409
        writeReading(batchKey, roundId, "RQ-R1", 1, "10.5", "op-1", 0, 409);
        writeReading(batchKey, roundId, "RQ-R1", 2, "10", "op-1", 0, 409);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM assay_reading WHERE round_id = ?", Integer.class, roundId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'ASSAY_READING_WRITE'"
                        + " AND command_key = 'RQ-R1'", Integer.class));
    }

    // ---------- 并发 ----------

    @Test
    void concurrentReadings_sameSeqExactlyOneWins() throws Exception {
        String batchKey = "BK-ASSAY-RACE-" + unique();
        createBatch(batchKey, List.of("含量"), 201);
        long roundId = openRound(batchKey, "RQ-O1", "含量", "op-1", 0, "9", "11", "0.2", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/assay-rounds/" + roundId
                                + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingBody("RQ-C1", 1, "10", "op-1", 0))),
                () -> callStatus(post("/api/batches/" + batchKey + "/assay-rounds/" + roundId
                                + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingBody("RQ-C2", 1, "10.1", "op-2", 0)))
        );
        int created = 0;
        int conflicted = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                created++;
            } else if (code == 409) {
                conflicted++;
            } else {
                fail("意外状态码: " + code);
            }
        }
        assertEquals(1, created);
        assertEquals(1, conflicted);
        // 恰好一条读数，版本为 1，无唯一键异常泄露
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM assay_reading WHERE round_id = ?", Integer.class, roundId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM assay_round WHERE id = ?", Integer.class, roundId));
    }

    @Test
    void concurrentOpenRound_sameRequestIdReplaysSameResponse() throws Exception {
        String batchKey = "BK-ASSAY-RACEOPEN-" + unique();
        createBatch(batchKey, List.of("含量"), 201);
        String body = openBody("RQ-O1", "含量", "op-1", 0, "9", "11", "0.2");
        List<Future<String>> results = runConcurrentBodies(
                () -> callBody(post("/api/batches/" + batchKey + "/assay-rounds")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callBody(post("/api/batches/" + batchKey + "/assay-rounds")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        String first = results.get(0).get(30, TimeUnit.SECONDS);
        String second = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(first, second, "同键同参并发重放应返回首次完整响应");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM assay_round WHERE batch_key = ?", Integer.class, batchKey));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'ASSAY_ROUND_OPEN'",
                Integer.class));
    }

    // ---------- 拆批与祖先召回共同判定 ----------

    @Test
    void splitChildren_needOwnAssayRounds() throws Exception {
        String parent = "BK-ASSAY-PARENT-" + unique();
        createBatch(parent, List.of("含量"), 201);
        long roundId = openRound(parent, "RQ-O1", "含量", "op-1", 0, "9", "11", "0.2", 201);
        passRound(parent, roundId, "RQ-P1");
        approve(parent, "qa-1", "QUALITY", "CK-AQ", 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-AO", 201);
        assertEquals("RELEASED", currentStatus(parent));

        String child = "BK-ASSAY-CHILD-" + unique();
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-SP\",\"children\":["
                                + "{\"batchKey\":\"" + child + "\",\"batchNo\":\"LOT-C1\"},"
                                + "{\"batchKey\":\"" + child + "-2\",\"batchNo\":\"LOT-C2\"}]}"))
                .andExpect(status().isCreated());

        // 子批不继承父轮：无轮次记录，未测定前批准 422
        assertEquals(0, getRounds(child).size());
        approve(child, "qa-9", "QUALITY", "CK-CQ", 422);

        // 子批独立测定合格后可放行
        long childRound = openRound(child, "RQ-CO1", "含量", "op-c", 0, "9", "11", "0.2", 201);
        passRound(child, childRound, "RQ-CP1");
        assertEquals("PENDING_RELEASE", currentStatus(child));
        approve(child, "qa-9", "QUALITY", "CK-CQ2", 201);
        approve(child, "ops-9", "OPERATIONS", "CK-CO2", 201);
        assertEquals("RELEASED", currentStatus(child));
    }

    @Test
    void recalledAncestor_blocksOpenRoundAndWriteReading() throws Exception {
        String parent = "BK-ASSAY-RCP-" + unique();
        createBatch(parent, List.of("含量"), 201);
        long parentRound = openRound(parent, "RQ-O1", "含量", "op-1", 0, "9", "11", "0.2", 201);
        passRound(parent, parentRound, "RQ-P1");
        approve(parent, "qa-1", "QUALITY", "CK-AQ", 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-AO", 201);

        String child = "BK-ASSAY-RCC-" + unique();
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-SP\",\"children\":["
                                + "{\"batchKey\":\"" + child + "\",\"batchNo\":\"LOT-C1\"},"
                                + "{\"batchKey\":\"" + child + "-2\",\"batchNo\":\"LOT-C2\"}]}"))
                .andExpect(status().isCreated());
        long childRound = openRound(child, "RQ-CO1", "含量", "op-c", 0, "9", "11", "0.2", 201);
        writeReading(child, childRound, "RQ-CR1", 1, "10", "op-c", 0, 201);

        // 召回父批：后代禁止开轮与写读数
        recall(parent, "qa-1", "CK-RC");
        openRound(child, "RQ-CO2", "含量", "op-c", 1, "9", "11", "0.2", 422);
        writeReading(child, childRound, "RQ-CR2", 2, "10.1", "op-c", 1, 422);
        // 子批数据未被改写
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM assay_reading WHERE batch_key = ?", Integer.class, child));
        assertEquals("QUARANTINED", currentStatus(child));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createBatch(String batchKey, List<String> items, int expected) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCmd("CK-" + unique(),
                                batchKey, "PROD-1", "LOT-1",
                                Instant.parse("2026-01-02T03:04:05Z"), items))))
                .andExpect(status().is(expected));
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests) {
    }

    private String testReq(String testKey, String item, String result, String inspector)
            throws Exception {
        return objectMapper.writeValueAsString(new TestCmd("CK-T-" + unique(), testKey, item,
                result, inspector));
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private void submitTest(String batchKey, String body, int expected) throws Exception {
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

    private void recall(String batchKey, String actor, String commandKey) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"祖先召回\"}"))
                .andExpect(status().isCreated());
    }

    private String openBody(String requestId, String testItem, String operator, int expectedVersion,
                            String meanMin, String meanMax, String maxRange) {
        return "{\"requestId\":\"" + requestId + "\",\"testItem\":\"" + testItem
                + "\",\"operator\":\"" + operator + "\",\"expectedVersion\":" + expectedVersion
                + ",\"meanMin\":\"" + meanMin + "\",\"meanMax\":\"" + meanMax
                + "\",\"maxRange\":\"" + maxRange + "\"}";
    }

    private MvcResult openRoundRaw(String batchKey, String requestId, String testItem,
                                   String operator, int expectedVersion, String meanMin,
                                   String meanMax, String maxRange, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/assay-rounds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(openBody(requestId, testItem, operator, expectedVersion,
                                meanMin, meanMax, maxRange)))
                .andExpect(status().is(expected)).andReturn();
    }

    private long openRound(String batchKey, String requestId, String testItem, String operator,
                           int expectedVersion, String meanMin, String meanMax, String maxRange,
                           int expected) throws Exception {
        MvcResult result = openRoundRaw(batchKey, requestId, testItem, operator, expectedVersion,
                meanMin, meanMax, maxRange, expected);
        if (expected >= 400) {
            return -1L;
        }
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("roundId").asLong();
    }

    private String readingBody(String requestId, int seq, String value, String operator,
                               int expectedVersion) {
        return "{\"requestId\":\"" + requestId + "\",\"seq\":" + seq + ",\"value\":\"" + value
                + "\",\"operator\":\"" + operator + "\",\"expectedVersion\":" + expectedVersion
                + "}";
    }

    private MvcResult writeReading(String batchKey, long roundId, String requestId, int seq,
                                   String value, String operator, int expectedVersion, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/assay-rounds/" + roundId
                        + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingBody(requestId, seq, value, operator, expectedVersion)))
                .andExpect(status().is(expected)).andReturn();
    }

    /**
     * 写入一组合格读数（10、10.1、10.2）使轮次封结合格。
     */
    private void passRound(String batchKey, long roundId, String requestIdPrefix) throws Exception {
        writeReading(batchKey, roundId, requestIdPrefix + "-1", 1, "10", "op-1", 0, 201);
        writeReading(batchKey, roundId, requestIdPrefix + "-2", 2, "10.1", "op-2", 1, 201);
        writeReading(batchKey, roundId, requestIdPrefix + "-3", 3, "10.2", "op-3", 2, 201);
    }

    /**
     * 写入一组不合格读数（10、10、10.201：均值合格但极差超限）使轮次封结不合格。
     */
    private void failRound(String batchKey, long roundId, String requestIdPrefix) throws Exception {
        writeReading(batchKey, roundId, requestIdPrefix + "-1", 1, "10", "op-1", 0, 201);
        writeReading(batchKey, roundId, requestIdPrefix + "-2", 2, "10", "op-2", 1, 201);
        writeReading(batchKey, roundId, requestIdPrefix + "-3", 3, "10.201", "op-3", 2, 201);
    }

    private JsonNode getRounds(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/assay-rounds"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String currentStatus(String batchKey) throws Exception {
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

    private String callBody(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
        try {
            MvcResult result = mockMvc.perform(req).andReturn();
            return result.getResponse().getStatus() + "|" + result.getResponse().getContentAsString();
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

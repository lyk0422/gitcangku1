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
 * 批次运输温控端到端测试：温度区间连续性、异常闭包、温控冻结拦截与解除、
 * commandKey 幂等与并发边界，全部基于真实 H2（MySQL 兼容模式）验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchTransportTemperatureTest {

    private static final String T0 = "2026-02-01T00:00:00Z";
    private static final String T4 = "2026-02-01T04:00:00Z";
    private static final String T8 = "2026-02-01T08:00:00Z";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM excursion_disposition");
        jdbc.update("DELETE FROM temperature_reading");
        jdbc.update("DELETE FROM temperature_hold");
        jdbc.update("DELETE FROM transport_segment");
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
    void happyFlow_normalSegments_batchContinuesInspectionAndRelease() throws Exception {
        String batchKey = "BK-TP-HAPPY-" + unique();
        createBatch(batchKey);

        // 两个首尾相接（左闭右开，不重叠）的运输段
        registerSegment(batchKey, "driver-1", segmentBody("CK-S1", "SEG-1", T0, T4, "2.00", "8.00"), 201);
        registerSegment(batchKey, "driver-1", segmentBody("CK-S2", "SEG-2", T4, T8, "2.00", "8.00"), 201);

        // 段内读数：边界值含端点；相邻间隔恰好 30 分钟不视为异常
        uploadReading(batchKey, "SEG-1", readingBody("CK-R1", "2026-02-01T00:10:00Z", "2.00"), 201);
        uploadReading(batchKey, "SEG-1", readingBody("CK-R2", "2026-02-01T00:40:00Z", "8.00"), 201);
        uploadReading(batchKey, "SEG-2", readingBody("CK-R3", "2026-02-01T04:10:00Z", "5.5"), 201);

        JsonNode hold = holdStatus(batchKey);
        assertFalse(hold.path("held").asBoolean());
        assertEquals("QUARANTINED", hold.path("batchStatus").asText());

        JsonNode segments = segments(batchKey);
        assertEquals(2, segments.size());
        assertEquals("NORMAL", segments.get(0).path("status").asText());
        assertEquals(2, segments.get(0).path("readings").size());
        assertEquals("NORMAL", segments.get(1).path("status").asText());
        assertEquals(0, excursions(batchKey).size());

        // 无异常的连续运输段不影响既有检验与放行流程
        submitPass(batchKey);
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    // ---------- 运输段区间规则 ----------

    @Test
    void segmentRules_overlapInvertedRangeAndDecimals() throws Exception {
        String batchKey = "BK-TP-SEG-" + unique();
        createBatch(batchKey);
        registerSegment(batchKey, "driver-1", segmentBody("CK-S1", "SEG-1", T0, T4, "2.00", "8.00"), 201);

        // 部分重叠 → 422
        registerSegment(batchKey, "driver-1",
                segmentBody("CK-S2", "SEG-2", "2026-02-01T03:00:00Z", "2026-02-01T05:00:00Z", "2.00", "8.00"), 422);
        // 包含关系 → 422
        registerSegment(batchKey, "driver-1",
                segmentBody("CK-S3", "SEG-3", T0, T8, "2.00", "8.00"), 422);
        // 首尾相接（前段结束即后段开始）→ 允许
        registerSegment(batchKey, "driver-1", segmentBody("CK-S4", "SEG-4", T4, T8, "2.00", "8.00"), 201);
        // startAt >= endAt → 422
        registerSegment(batchKey, "driver-1",
                segmentBody("CK-S5", "SEG-5", T8, T8, "2.00", "8.00"), 422);
        // minTemp > maxTemp → 422
        registerSegment(batchKey, "driver-1",
                segmentBody("CK-S6", "SEG-6", "2026-02-01T10:00:00Z", "2026-02-01T12:00:00Z", "9.00", "8.00"), 422);
        // 温度超过两位小数 → 400
        registerSegment(batchKey, "driver-1",
                segmentBody("CK-S7", "SEG-7", "2026-02-01T10:00:00Z", "2026-02-01T12:00:00Z", "2.001", "8.00"), 400);
        // 缺 X-Actor-Id → 400
        mockMvc.perform(post("/api/batches/" + batchKey + "/transport-segments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(segmentBody("CK-S8", "SEG-8", "2026-02-01T10:00:00Z",
                                "2026-02-01T12:00:00Z", "2.00", "8.00")))
                .andExpect(status().isBadRequest());
        // 不存在的批次 → 404
        registerSegment("NO-SUCH-BATCH", "driver-1",
                segmentBody("CK-S9", "SEG-9", T0, T4, "2.00", "8.00"), 404);

        assertEquals(2, segments(batchKey).size(), "仅两个合法运输段入库");
    }

    // ---------- 读数时序规则 ----------

    @Test
    void readingRules_mustBeInsideSegmentAndStrictlyIncreasing() throws Exception {
        String batchKey = "BK-TP-RD-" + unique();
        createBatch(batchKey);
        registerSegment(batchKey, "driver-1", segmentBody("CK-S1", "SEG-1", T0, T4, "2.00", "8.00"), 201);

        // 段开始之前 → 422
        uploadReading(batchKey, "SEG-1", readingBody("CK-R0", "2026-01-31T23:59:59Z", "5.0"), 422);
        // 恰好等于 endAt（右开）→ 422
        uploadReading(batchKey, "SEG-1", readingBody("CK-RX", T4, "5.0"), 422);

        uploadReading(batchKey, "SEG-1", readingBody("CK-R1", "2026-02-01T01:00:00Z", "5.0"), 201);
        // 与上一读数同时刻 → 422
        uploadReading(batchKey, "SEG-1", readingBody("CK-R2", "2026-02-01T01:00:00Z", "5.0"), 422);
        // 时刻回退 → 422
        uploadReading(batchKey, "SEG-1", readingBody("CK-R3", "2026-02-01T00:30:00Z", "5.0"), 422);

        // 失败读数不入库
        JsonNode segments = segments(batchKey);
        assertEquals(1, segments.get(0).path("readings").size());
        // 不存在的运输段 → 404
        uploadReading(batchKey, "NO-SEG", readingBody("CK-R4", "2026-02-01T02:00:00Z", "5.0"), 404);
    }

    // ---------- 异常触发与拦截 ----------

    @Test
    void outOfRangeReading_triggersExcursionAndHold_blocksReleaseSplitAndTransfer() throws Exception {
        String batchKey = "BK-TP-OOR-" + unique();
        createBatch(batchKey);
        submitPass(batchKey);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));

        registerSegment(batchKey, "driver-1", segmentBody("CK-S1", "SEG-1", T0, T4, "2.00", "8.00"), 201);
        uploadReading(batchKey, "SEG-1", readingBody("CK-R1", "2026-02-01T00:10:00Z", "5.0"), 201);

        // 越界读数：段转 EXCURSION，批次对外呈现 TEMPERATURE_HOLD
        MvcResult breach = mockMvc.perform(post(
                        "/api/batches/" + batchKey + "/transport-segments/SEG-1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingBody("CK-R2", "2026-02-01T00:20:00Z", "9.5")))
                .andExpect(status().isCreated()).andReturn();
        JsonNode breachBody = objectMapper.readTree(breach.getResponse().getContentAsString());
        assertFalse(breachBody.path("inRange").asBoolean());
        assertEquals("EXCURSION", breachBody.path("segmentStatus").asText());
        assertEquals("TEMPERATURE_HOLD", breachBody.path("batchStatus").asText());

        JsonNode hold = holdStatus(batchKey);
        assertTrue(hold.path("held").asBoolean());
        assertEquals("TEMPERATURE_HOLD", hold.path("batchStatus").asText());
        assertEquals("PENDING_RELEASE", hold.path("preStatus").asText());
        assertEquals("TEMPERATURE_HOLD", currentStatus(batchKey), "历史查询呈现温控冻结状态");

        // 温控冻结拦截：到货放行 409、继续移交 409
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 409);
        registerSegment(batchKey, "driver-2",
                segmentBody("CK-S2", "SEG-2", T4, T8, "2.00", "8.00"), 409);
        // 检验不受温控冻结拦截
        assertEquals("PENDING_RELEASE", underlyingStatus(batchKey));
    }

    @Test
    void holdOnReleasedBatch_blocksSplit() throws Exception {
        String batchKey = "BK-TP-SPLIT-" + unique();
        createBatch(batchKey);
        submitPass(batchKey);
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        registerSegment(batchKey, "driver-1", segmentBody("CK-S1", "SEG-1", T0, T4, "2.00", "8.00"), 201);
        uploadReading(batchKey, "SEG-1", readingBody("CK-R1", "2026-02-01T00:10:00Z", "10.0"), 201);
        assertEquals("TEMPERATURE_HOLD", currentStatus(batchKey));

        // 拆分被温控冻结拦截
        String splitBody = "{\"commandKey\":\"CK-SP\",\"children\":["
                + "{\"batchKey\":\"" + batchKey + "-C1\",\"batchNo\":\"N1\"},"
                + "{\"batchKey\":\"" + batchKey + "-C2\",\"batchNo\":\"N2\"}]}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(splitBody))
                .andExpect(status().isConflict());
        assertEquals("TEMPERATURE_HOLD", currentStatus(batchKey));
    }

    @Test
    void readingGapOver30Minutes_triggersExcursion() throws Exception {
        String batchKey = "BK-TP-GAP-" + unique();
        createBatch(batchKey);
        registerSegment(batchKey, "driver-1", segmentBody("CK-S1", "SEG-1", T0, T4, "2.00", "8.00"), 201);

        uploadReading(batchKey, "SEG-1", readingBody("CK-R1", "2026-02-01T00:10:00Z", "5.0"), 201);
        // 恰好 30 分钟：不异常
        uploadReading(batchKey, "SEG-1", readingBody("CK-R2", "2026-02-01T00:40:00Z", "5.0"), 201);
        assertEquals("NORMAL", segments(batchKey).get(0).path("status").asText());
        // 31 分钟：超过 30 分钟 → EXCURSION
        uploadReading(batchKey, "SEG-1", readingBody("CK-R3", "2026-02-01T01:11:00Z", "5.0"), 201);

        JsonNode segment = segments(batchKey).get(0);
        assertEquals("EXCURSION", segment.path("status").asText());
        assertTrue(holdStatus(batchKey).path("held").asBoolean());

        JsonNode excursions = excursions(batchKey);
        assertEquals(1, excursions.size());
        JsonNode closure = excursions.get(0);
        assertEquals("SEG-1", closure.path("segmentKey").asText());
        assertTrue(closure.path("reasons").toString().contains("READING_GAP_EXCEEDED"));
        assertEquals(1, closure.path("gapBreaches").size());
        assertEquals(31, closure.path("gapBreaches").get(0).path("gapMinutes").asLong());
        assertEquals(0, closure.path("outOfRangeReadings").size());
        assertTrue(closure.path("disposition").isNull(), "未处置时 disposition 为 null");
    }

    @Test
    void excursionClosure_showsOutOfRangeDetails_andSegmentImmutable() throws Exception {
        String batchKey = "BK-TP-CLO-" + unique();
        createBatch(batchKey);
        registerSegment(batchKey, "driver-1", segmentBody("CK-S1", "SEG-1", T0, T4, "2.00", "8.00"), 201);
        uploadReading(batchKey, "SEG-1", readingBody("CK-R1", "2026-02-01T00:10:00Z", "1.5"), 201);

        JsonNode closure = excursions(batchKey).get(0);
        assertTrue(closure.path("reasons").toString().contains("OUT_OF_RANGE"));
        assertEquals(1, closure.path("outOfRangeReadings").size());
        assertEquals("2026-02-01T00:10:00Z",
                closure.path("outOfRangeReadings").get(0).path("recordedAt").asText());
        assertEquals(0, Double.compare(1.5,
                closure.path("outOfRangeReadings").get(0).path("temperature").asDouble()));

        // 异常段不可继续上传读数（不可改写）
        uploadReading(batchKey, "SEG-1", readingBody("CK-R2", "2026-02-01T00:20:00Z", "5.0"), 409);
        assertEquals(1, segments(batchKey).get(0).path("readings").size());
    }

    // ---------- 温控冻结解除 ----------

    @Test
    void holdRelease_requiresQualityRoleNotRecorderAndFullDisposition() throws Exception {
        String batchKey = "BK-TP-REL-" + unique();
        createBatch(batchKey);
        registerSegment(batchKey, "driver-1", segmentBody("CK-S1", "SEG-1", T0, T4, "2.00", "8.00"), 201);
        registerSegment(batchKey, "driver-1", segmentBody("CK-S2", "SEG-2", T4, T8, "2.00", "8.00"), 201);
        uploadReading(batchKey, "SEG-1", readingBody("CK-R1", "2026-02-01T00:10:00Z", "9.0"), 201);
        uploadReading(batchKey, "SEG-2", readingBody("CK-R2", "2026-02-01T04:10:00Z", "1.0"), 201);
        assertTrue(holdStatus(batchKey).path("held").asBoolean());

        // 非质量角色 → 422
        releaseHold(batchKey, "qa-1", "OPERATIONS",
                releaseBody("CK-H1", "调查", "\"SEG-1\",\"SEG-2\""), 422);
        // 运输录入人不得解除 → 422
        releaseHold(batchKey, "driver-1", "QUALITY",
                releaseBody("CK-H2", "调查", "\"SEG-1\",\"SEG-2\""), 422);
        // 缺一段处置 → 422 且整次不解除
        releaseHold(batchKey, "qa-1", "QUALITY",
                releaseBody("CK-H3", "调查", "\"SEG-1\""), 422);
        assertTrue(holdStatus(batchKey).path("held").asBoolean());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM excursion_disposition WHERE batch_key = ?",
                Integer.class, batchKey));
        // 处置目标不是 EXCURSION 段 → 422
        releaseHold(batchKey, "qa-1", "QUALITY",
                releaseBody("CK-H4", "调查", "\"SEG-1\",\"SEG-2\",\"SEG-UNKNOWN\""), 422);
        // 未冻结批次解除 → 409（另建无冻结批次验证）
        String freeBatch = "BK-TP-FREE-" + unique();
        createBatch(freeBatch);
        releaseHold(freeBatch, "qa-1", "QUALITY",
                releaseBody("CK-H5", "调查", "\"SEG-1\""), 409);
    }

    @Test
    void holdRelease_success_removesGateButKeepsExcursionHistory() throws Exception {
        String batchKey = "BK-TP-REL2-" + unique();
        createBatch(batchKey);
        submitPass(batchKey);
        registerSegment(batchKey, "driver-1", segmentBody("CK-S1", "SEG-1", T0, T4, "2.00", "8.00"), 201);
        uploadReading(batchKey, "SEG-1", readingBody("CK-R1", "2026-02-01T00:10:00Z", "9.0"), 201);
        approve(batchKey, "qa-blocked", "QUALITY", "CK-A0", 409);

        MvcResult released = mockMvc.perform(post("/api/batches/" + batchKey + "/temperature-hold/release")
                        .header("X-Actor-Id", "qa-lead").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody("CK-H1", "冷链车制冷故障，已维修并复测合格", "\"SEG-1\"")))
                .andExpect(status().isCreated()).andReturn();
        JsonNode releaseBody = objectMapper.readTree(released.getResponse().getContentAsString());
        assertEquals("PENDING_RELEASE", releaseBody.path("batchStatus").asText());
        assertEquals("qa-lead", releaseBody.path("actorId").asText());
        assertEquals(1, releaseBody.path("disposedSegmentKeys").size());

        // 解除只移除运输门禁：批次恢复底层状态，可继续放行
        JsonNode hold = holdStatus(batchKey);
        assertFalse(hold.path("held").asBoolean());
        assertEquals("PENDING_RELEASE", hold.path("batchStatus").asText());
        assertEquals("qa-lead", hold.path("releaseActor").asText());
        assertEquals("冷链车制冷故障，已维修并复测合格", hold.path("investigationNote").asText());
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 异常段与读数历史不改写：段仍 EXCURSION，读数与处置记录保留
        JsonNode segment = segments(batchKey).get(0);
        assertEquals("EXCURSION", segment.path("status").asText());
        assertEquals(1, segment.path("readings").size());
        JsonNode closure = excursions(batchKey).get(0);
        assertEquals("SEG-1", closure.path("segmentKey").asText(), "EXCURSION 段保持异常闭包");
        assertEquals("qa-lead", closure.path("disposition").path("actorId").asText());
        assertEquals("处置说明-0", closure.path("disposition").path("disposition").asText());
    }

    // ---------- 幂等 ----------

    @Test
    void commandKey_replaySameParams_conflictsOnChangedParams_failureDoesNotOccupyKey()
            throws Exception {
        String batchKey = "BK-TP-IDEM-" + unique();
        createBatch(batchKey);

        // 运输段登记：同键同参重放首次结果
        String segBody = segmentBody("CK-S1", "SEG-1", T0, T4, "2.00", "8.00");
        MvcResult first = mockMvc.perform(post("/api/batches/" + batchKey + "/transport-segments")
                        .header("X-Actor-Id", "driver-1")
                        .contentType(MediaType.APPLICATION_JSON).content(segBody))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batchKey + "/transport-segments")
                        .header("X-Actor-Id", "driver-1")
                        .contentType(MediaType.APPLICATION_JSON).content(segBody))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, segments(batchKey).size());
        // 同键改参 → 409
        registerSegment(batchKey, "driver-1",
                segmentBody("CK-S1", "SEG-1", T0, T8, "2.00", "8.00"), 409);

        // 失败不占键：先以越界参数失败（422），同键修正参数后成功
        registerSegment(batchKey, "driver-1",
                segmentBody("CK-S2", "SEG-2", T4, T4, "2.00", "8.00"), 422);
        registerSegment(batchKey, "driver-1",
                segmentBody("CK-S2", "SEG-2", T4, T8, "2.00", "8.00"), 201);

        // 读数上传：同键同参重放，同键改参 409
        String rdBody = readingBody("CK-R1", "2026-02-01T00:10:00Z", "5.0");
        MvcResult rdFirst = mockMvc.perform(post(
                        "/api/batches/" + batchKey + "/transport-segments/SEG-1/readings")
                        .contentType(MediaType.APPLICATION_JSON).content(rdBody))
                .andExpect(status().isCreated()).andReturn();
        MvcResult rdReplay = mockMvc.perform(post(
                        "/api/batches/" + batchKey + "/transport-segments/SEG-1/readings")
                        .contentType(MediaType.APPLICATION_JSON).content(rdBody))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(rdFirst.getResponse().getContentAsString(),
                rdReplay.getResponse().getContentAsString());
        assertEquals(1, segments(batchKey).get(0).path("readings").size());
        uploadReading(batchKey, "SEG-1", readingBody("CK-R1", "2026-02-01T00:20:00Z", "5.0"), 409);

        // 解除命令：同键同参重放首次结果
        uploadReading(batchKey, "SEG-1", readingBody("CK-R9", "2026-02-01T00:30:00Z", "9.0"), 201);
        assertTrue(holdStatus(batchKey).path("held").asBoolean());
        String relBody = releaseBody("CK-H1", "调查说明", "\"SEG-1\"");
        MvcResult relFirst = mockMvc.perform(post("/api/batches/" + batchKey + "/temperature-hold/release")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content(relBody))
                .andExpect(status().isCreated()).andReturn();
        MvcResult relReplay = mockMvc.perform(post("/api/batches/" + batchKey + "/temperature-hold/release")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content(relBody))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(relFirst.getResponse().getContentAsString(),
                relReplay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM excursion_disposition WHERE batch_key = ?",
                Integer.class, batchKey));
        // 同键改参 → 409
        releaseHold(batchKey, "qa-1", "QUALITY", releaseBody("CK-H1", "另一个说明", "\"SEG-1\""), 409);
    }

    // ---------- 并发 ----------

    @Test
    void concurrentSameCommandKey_segmentRegistration_singleWinner() throws Exception {
        String batchKey = "BK-TP-CSEG-" + unique();
        createBatch(batchKey);
        String body = segmentBody("CK-S1", "SEG-1", T0, T4, "2.00", "8.00");
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/transport-segments")
                        .header("X-Actor-Id", "driver-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/" + batchKey + "/transport-segments")
                        .header("X-Actor-Id", "driver-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM transport_segment WHERE batch_key = ?",
                Integer.class, batchKey));
    }

    @Test
    void concurrentReadings_sameTimestamp_exactlyOneAccepted() throws Exception {
        String batchKey = "BK-TP-CRD-" + unique();
        createBatch(batchKey);
        registerSegment(batchKey, "driver-1", segmentBody("CK-S1", "SEG-1", T0, T4, "2.00", "8.00"), 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/transport-segments/SEG-1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingBody("CK-RA", "2026-02-01T01:00:00Z", "5.0"))),
                () -> callStatus(post("/api/batches/" + batchKey + "/transport-segments/SEG-1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingBody("CK-RB", "2026-02-01T01:00:00Z", "6.0")))
        );
        int created = 0;
        int rejected = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                created++;
            } else if (code == 422) {
                rejected++;
            } else {
                fail("意外状态码: " + code);
            }
        }
        assertEquals(1, created, "同一时刻读数仅一条被接受");
        assertEquals(1, rejected);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM temperature_reading WHERE batch_key = ?",
                Integer.class, batchKey));
    }

    @Test
    void concurrentExcursionAndApproval_neverReleasedWhileHeld() throws Exception {
        String batchKey = "BK-TP-CGATE-" + unique();
        createBatch(batchKey);
        submitPass(batchKey);
        registerSegment(batchKey, "driver-1", segmentBody("CK-S1", "SEG-1", T0, T4, "2.00", "8.00"), 201);

        // 同时：越界读数（触发冻结）与 QUALITY 批准；行锁按提交顺序串行
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/transport-segments/SEG-1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingBody("CK-R1", "2026-02-01T00:10:00Z", "9.0"))),
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-A1\"}"))
        );
        assertEquals(201, results.get(0).get(30, TimeUnit.SECONDS));
        int approval = results.get(1).get(30, TimeUnit.SECONDS);

        assertTrue(holdStatus(batchKey).path("held").asBoolean(), "越界读数必然建立温控冻结");
        assertEquals("TEMPERATURE_HOLD", currentStatus(batchKey));
        assertNotEquals("RELEASED", underlyingStatus(batchKey), "冻结期间不得被放行");
        if (approval == 201) {
            // 批准先提交：仅完成第一步进入 RELEASE_REVIEW，随后冻结生效
            assertEquals("RELEASE_REVIEW", underlyingStatus(batchKey));
        } else {
            assertEquals(409, approval, "冻结先生效则批准被拦截");
            assertEquals("PENDING_RELEASE", underlyingStatus(batchKey));
        }
    }

    // ---------- 持久化与查询呈现 ----------

    @Test
    void holdPresentedInBatchQueries_andDataPersisted() throws Exception {
        String batchKey = "BK-TP-VIEW-" + unique();
        createBatch(batchKey);
        registerSegment(batchKey, "driver-1", segmentBody("CK-S1", "SEG-1", T0, T4, "2.00", "8.00"), 201);
        uploadReading(batchKey, "SEG-1", readingBody("CK-R1", "2026-02-01T00:10:00Z", "9.0"), 201);

        // 历史查询与可用列表均呈现 TEMPERATURE_HOLD
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("TEMPERATURE_HOLD", objectMapper
                .readTree(history.getResponse().getContentAsString())
                .path("batch").path("status").asText());
        MvcResult available = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        JsonNode entry = null;
        for (JsonNode node : objectMapper.readTree(available.getResponse().getContentAsString())) {
            if (batchKey.equals(node.path("batchKey").asText())) {
                entry = node;
            }
        }
        assertNotNull(entry);
        assertEquals("TEMPERATURE_HOLD", entry.path("status").asText());

        // 数据库落库核验
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM transport_segment WHERE batch_key = ? AND status = 'EXCURSION'",
                Integer.class, batchKey));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM temperature_reading WHERE batch_key = ? AND in_range = FALSE",
                Integer.class, batchKey));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM temperature_hold WHERE batch_key = ? AND released_at IS NULL",
                Integer.class, batchKey));
        assertEquals("QUARANTINED", underlyingStatus(batchKey), "底层状态不被冻结改写");
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createBatch(String batchKey) throws Exception {
        String body = "{\"commandKey\":\"CK-C-" + unique() + "\",\"batchKey\":\"" + batchKey
                + "\",\"productCode\":\"PROD-1\",\"batchNo\":\"LOT-1\","
                + "\"producedAt\":\"2026-01-02T03:04:05Z\",\"requiredTests\":[\"含量\"]}";
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void submitPass(String batchKey) throws Exception {
        String body = "{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-" + unique()
                + "\",\"testItem\":\"含量\",\"result\":\"PASS\",\"inspector\":\"insp-1\"}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void approve(String batchKey, String actor, String role, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    private String segmentBody(String commandKey, String segmentKey, String startAt, String endAt,
                               String minTemp, String maxTemp) {
        return "{\"commandKey\":\"" + commandKey + "\",\"segmentKey\":\"" + segmentKey
                + "\",\"startAt\":\"" + startAt + "\",\"endAt\":\"" + endAt
                + "\",\"minTemp\":" + minTemp + ",\"maxTemp\":" + maxTemp + "}";
    }

    private void registerSegment(String batchKey, String actor, String body, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/transport-segments")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private String readingBody(String commandKey, String recordedAt, String temperature) {
        return "{\"commandKey\":\"" + commandKey + "\",\"recordedAt\":\"" + recordedAt
                + "\",\"temperature\":" + temperature + "}";
    }

    private void uploadReading(String batchKey, String segmentKey, String body, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/transport-segments/"
                        + segmentKey + "/readings")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private String releaseBody(String commandKey, String note, String segmentKeysJsonArray) {
        StringBuilder dispositions = new StringBuilder("[");
        String[] keys = segmentKeysJsonArray.replace("[", "").replace("]", "").split(",");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) {
                dispositions.append(",");
            }
            dispositions.append("{\"segmentKey\":").append(keys[i].trim())
                    .append(",\"disposition\":\"处置说明-").append(i).append("\"}");
        }
        dispositions.append("]");
        return "{\"commandKey\":\"" + commandKey + "\",\"investigationNote\":\"" + note
                + "\",\"dispositions\":" + dispositions + "}";
    }

    private void releaseHold(String batchKey, String actor, String role, String body, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/temperature-hold/release")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private JsonNode holdStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/temperature-hold"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode segments(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/transport-segments"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode excursions(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/temperature-excursions"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private String underlyingStatus(String batchKey) {
        return jdbc.queryForObject("SELECT status FROM batch WHERE batch_key = ?",
                String.class, batchKey);
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

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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运输温控端到端测试（真实 H2 MySQL 兼容内存库）：
 * 覆盖温度区间连续性、越界/间隔异常闭包、到货放行与拆分拦截、解除门禁及并发幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransportTemperatureTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM temperature_release");
        jdbc.update("DELETE FROM excursion_disposition");
        jdbc.update("DELETE FROM transport_reading");
        jdbc.update("DELETE FROM transport_segment");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 运输段区间连续性 ----------

    @Test
    void segments_halfOpenAndAdjacentAllowed_overlapRejected422() throws Exception {
        String batchKey = newBatch();
        // [10:00,11:00)
        register(batchKey, "SEG-1-" + uid(), "2026-05-01T10:00:00Z", "2026-05-01T11:00:00Z",
                "2.00", "8.00", "carrier-a", "CK-SEG-1-" + uid(), 201);
        // 首尾相接 [11:00,12:00) 允许
        register(batchKey, "SEG-2-" + uid(), "2026-05-01T11:00:00Z", "2026-05-01T12:00:00Z",
                "2.00", "8.00", "carrier-a", "CK-SEG-2-" + uid(), 201);
        // 与 SEG-2 重叠（11:30 落在 [11:00,12:00)）
        register(batchKey, "SEG-3-" + uid(), "2026-05-01T11:30:00Z", "2026-05-01T12:30:00Z",
                "2.00", "8.00", "carrier-a", "CK-SEG-3-" + uid(), 422);
        // 完全包含既有段
        register(batchKey, "SEG-4-" + uid(), "2026-05-01T10:30:00Z", "2026-05-01T10:45:00Z",
                "2.00", "8.00", "carrier-a", "CK-SEG-4-" + uid(), 422);

        JsonNode temp = temperature(batchKey);
        assertEquals(2, temp.path("segmentCount").asInt());
        assertFalse(temp.path("temperatureHold").asBoolean());
    }

    @Test
    void register_invalidRangesAndScales_return400_andKeyNotOccupied() throws Exception {
        String batchKey = newBatch();
        String segmentKey = "SEG-BAD-" + uid();
        String commandKey = "CK-BAD-" + uid();
        // 起止颠倒
        register(batchKey, segmentKey, "2026-05-01T12:00:00Z", "2026-05-01T10:00:00Z",
                "2.00", "8.00", "carrier-a", commandKey, 400);
        // 下限大于上限
        register(batchKey, segmentKey, "2026-05-01T10:00:00Z", "2026-05-01T11:00:00Z",
                "9.00", "8.00", "carrier-a", commandKey, 400);
        // 温度三位小数
        register(batchKey, segmentKey, "2026-05-01T10:00:00Z", "2026-05-01T11:00:00Z",
                "2.005", "8.00", "carrier-a", commandKey, 400);
        // 失败不占键：同 commandKey 合法请求应成功
        register(batchKey, segmentKey, "2026-05-01T10:00:00Z", "2026-05-01T11:00:00Z",
                "2.00", "8.00", "carrier-a", commandKey, 201);
    }

    @Test
    void register_duplicateSegmentKey_returns409() throws Exception {
        String b1 = newBatch();
        String b2 = newBatch();
        String sharedKey = "SEG-DUP-" + uid();
        register(b1, sharedKey, "2026-05-01T10:00:00Z", "2026-05-01T11:00:00Z",
                "2", "8", "carrier-a", "CK-D1-" + uid(), 201);
        register(b2, sharedKey, "2026-05-02T10:00:00Z", "2026-05-02T11:00:00Z",
                "2", "8", "carrier-b", "CK-D2-" + uid(), 409);
    }

    // ---------- 读数区间与异常 ----------

    @Test
    void reading_outOfSegmentOrNotIncreasing_returns422_andPersistsNothing() throws Exception {
        String batchKey = newBatch();
        String segmentKey = registerSegment(batchKey, "2026-05-01T10:00:00Z", "2026-05-01T11:00:00Z");

        // 早于开始
        upload(batchKey, segmentKey, "2026-05-01T09:59:59Z", "5.00", "CK-R-" + uid(), 422);
        // 恰为结束时刻（右开）
        upload(batchKey, segmentKey, "2026-05-01T11:00:00Z", "5.00", "CK-R-" + uid(), 422);

        upload(batchKey, segmentKey, "2026-05-01T10:20:00Z", "5.00", "CK-R-" + uid(), 201);
        // 时刻倒退
        upload(batchKey, segmentKey, "2026-05-01T10:19:59Z", "5.00", "CK-R-" + uid(), 422);
        // 时刻相等
        upload(batchKey, segmentKey, "2026-05-01T10:20:00Z", "5.00", "CK-R-" + uid(), 422);

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM transport_reading WHERE segment_key = ?",
                Integer.class, segmentKey));
        assertEquals("NORMAL", jdbc.queryForObject(
                "SELECT status FROM transport_segment WHERE segment_key = ?",
                String.class, segmentKey));
    }

    @Test
    void reading_boundaryTempsInclusiveAndGapExactly30Minutes_ok() throws Exception {
        String batchKey = newBatch();
        String segmentKey = registerSegment(batchKey, "2026-05-01T10:00:00Z", "2026-05-01T13:00:00Z");

        upload(batchKey, segmentKey, "2026-05-01T10:00:00Z", "2.00", "CK-R-" + uid(), 201);
        // 恰 30 分钟间隔，不算异常
        upload(batchKey, segmentKey, "2026-05-01T10:30:00Z", "8.00", "CK-R-" + uid(), 201);
        JsonNode temp = temperature(batchKey);
        assertFalse(temp.path("temperatureHold").asBoolean());
        assertEquals("NORMAL", temp.path("segments").get(0).path("status").asText());
        assertEquals(2, temp.path("segments").get(0).path("readings").size());
    }

    @Test
    void reading_belowMin_marksExcursionAndHold_butReadingKept() throws Exception {
        String batchKey = newBatch();
        String segmentKey = registerSegment(batchKey, "2026-05-01T10:00:00Z", "2026-05-01T12:00:00Z");
        upload(batchKey, segmentKey, "2026-05-01T10:10:00Z", "5.00", "CK-R-" + uid(), 201);

        MvcResult result = uploadResult(batchKey, segmentKey, "2026-05-01T10:20:00Z",
                "1.99", "CK-EXC-" + uid(), 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("EXCURSION", body.path("segmentStatus").asText());
        assertTrue(body.path("temperatureHold").asBoolean());

        // 数据库门禁与异常状态落盘
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "SELECT temperature_hold FROM batch WHERE batch_key = ?", Boolean.class, batchKey));
        assertEquals("EXCURSION", jdbc.queryForObject(
                "SELECT status FROM transport_segment WHERE segment_key = ?",
                String.class, segmentKey));

        // 后续合规读数不改变异常终态；异常段与读数历史不可改写
        upload(batchKey, segmentKey, "2026-05-01T10:40:00Z", "5.00", "CK-R-" + uid(), 201);
        JsonNode temp = temperature(batchKey);
        assertEquals("EXCURSION", temp.path("segments").get(0).path("status").asText());
        assertEquals(3, temp.path("segments").get(0).path("readings").size());
        assertEquals(1, temp.path("excursionCount").asInt());
        assertEquals(segmentKey, temp.path("undisposedExcursionKeys").get(0).asText());
    }

    @Test
    void reading_gapOver30Minutes_marksExcursionAndHold() throws Exception {
        String batchKey = newBatch();
        String segmentKey = registerSegment(batchKey, "2026-05-01T10:00:00Z", "2026-05-01T14:00:00Z");
        upload(batchKey, segmentKey, "2026-05-01T10:00:00Z", "5.00", "CK-R-" + uid(), 201);
        upload(batchKey, segmentKey, "2026-05-01T10:30:01Z", "5.00", "CK-R-" + uid(), 201);

        assertTrue(temperature(batchKey).path("temperatureHold").asBoolean());
        assertEquals(1, temperature(batchKey).path("excursionCount").asInt());
    }

    // ---------- 冻结拦截 ----------

    @Test
    void hold_blocksApproveSplitAndNewSegment_returns409() throws Exception {
        String batchKey = quarantinedToPendingWithOneSegment();
        String segmentKey = segmentOf(batchKey);
        upload(batchKey, segmentKey, "2026-05-01T10:10:00Z", "99.00", "CK-R-" + uid(), 201);
        assertTrue(temperature(batchKey).path("temperatureHold").asBoolean());

        // 到到货放行（批准）409
        approve(batchKey, "qa-1", "QUALITY", "CK-AP-" + uid(), 409);
        // 继续移交（登记新段）409
        register(batchKey, "SEG-HOLD-" + uid(), "2026-05-01T11:00:00Z", "2026-05-01T12:00:00Z",
                "2", "8", "carrier-a", "CK-SEG-" + uid(), 409);
        // 拆分 409：先把批次主状态推到 RELEASED（绕过批准不可能），直接构造一个 RELEASED 冻结批
        String released = releasedHeldBatch();
        split(released, List.of(child("CH-1-" + uid(), "LOT-C1"), child("CH-2-" + uid(), "LOT-C2")),
                "CK-SPLIT-" + uid(), 409);
    }

    // ---------- 异常闭包与解除 ----------

    @Test
    void disposition_constraints_recorderRoleStateAndTwice() throws Exception {
        String batchKey = newBatch();
        String normalSegment = registerSegmentWith(batchKey, "SEG-N-" + uid(), "carrier-a",
                "2026-05-01T10:00:00Z", "2026-05-01T12:00:00Z");
        String excursionSegment = registerSegmentWith(batchKey, "SEG-E-" + uid(), "carrier-a",
                "2026-05-01T12:00:00Z", "2026-05-01T14:00:00Z");
        upload(batchKey, excursionSegment, "2026-05-01T12:10:00Z", "99.00", "CK-R-" + uid(), 201);

        // NORMAL 段不可处置
        dispose(batchKey, normalSegment, "qa-1", "QUALITY", "n", "CK-D-" + uid(), 422);
        // 非 QUALITY 角色 400
        dispose(batchKey, excursionSegment, "qa-1", "OPERATIONS", "n", "CK-D-" + uid(), 400);
        // 录入人本人处置 422
        dispose(batchKey, excursionSegment, "carrier-a", "QUALITY", "n", "CK-D-" + uid(), 422);
        // 合法处置
        dispose(batchKey, excursionSegment, "qa-1", "QUALITY", "更换冷藏车并复检",
                "CK-D-OK-" + uid(), 201);
        // 重复处置 409，闭包不可改写
        dispose(batchKey, excursionSegment, "qa-2", "QUALITY", "再次处置",
                "CK-D-2-" + uid(), 409);

        JsonNode seg = segmentNode(batchKey, excursionSegment);
        assertEquals("更换冷藏车并复检", seg.path("disposition").path("actionNote").asText());
        assertEquals("qa-1", seg.path("disposition").path("actorId").asText());
    }

    @Test
    void release_requiresAllExcursionsDisposed_andDifferentInvestigator_else422() throws Exception {
        String batchKey = newBatch();
        String seg1 = registerSegmentWith(batchKey, "SEG-A-" + uid(), "carrier-a",
                "2026-05-01T10:00:00Z", "2026-05-01T12:00:00Z");
        String seg2 = registerSegmentWith(batchKey, "SEG-B-" + uid(), "carrier-b",
                "2026-05-01T12:00:00Z", "2026-05-01T14:00:00Z");
        upload(batchKey, seg1, "2026-05-01T10:10:00Z", "0.00", "CK-R-" + uid(), 201);
        upload(batchKey, seg2, "2026-05-01T12:10:00Z", "99.00", "CK-R-" + uid(), 201);

        // 调查人是其中一段录入人 → 422
        releaseHold(batchKey, "carrier-a", "QUALITY", "调查完成", "CK-REL-" + uid(), 422);
        // 非质量角色 → 400
        releaseHold(batchKey, "qa-1", "OPERATIONS", "调查完成", "CK-REL-" + uid(), 400);

        // 只处置一段 → 整次不解除 422，门禁保留
        dispose(batchKey, seg1, "qa-1", "QUALITY", "seg1 已处置", "CK-D1-" + uid(), 201);
        releaseHold(batchKey, "qa-1", "QUALITY", "调查完成", "CK-REL-" + uid(), 422);
        assertTrue(temperature(batchKey).path("temperatureHold").asBoolean());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM temperature_release WHERE batch_key = ?",
                Integer.class, batchKey));

        // 全部处置后解除
        dispose(batchKey, seg2, "qa-1", "QUALITY", "seg2 已处置", "CK-D2-" + uid(), 201);
        releaseHold(batchKey, "qa-1", "QUALITY", "两段均已处置，同意解冻",
                "CK-REL-OK-" + uid(), 201);

        JsonNode temp = temperature(batchKey);
        assertFalse(temp.path("temperatureHold").asBoolean());
        assertEquals(0, temp.path("undisposedExcursionKeys").size());
        // 解除不覆盖原始异常状态
        assertEquals("EXCURSION", segmentNode(batchKey, seg1).path("status").asText());
        assertEquals("EXCURSION", segmentNode(batchKey, seg2).path("status").asText());
        assertEquals("qa-1", temp.path("release").path("investigatorId").asText());
        assertEquals(2, temp.path("release").path("disposedSegmentKeys").size());

        // 重复解除 409
        releaseHold(batchKey, "qa-1", "QUALITY", "再次解除", "CK-REL2-" + uid(), 409);
    }

    @Test
    void afterRelease_approvalAndSplitFlowResume() throws Exception {
        String batchKey = quarantinedToPendingWithOneSegment();
        String segmentKey = segmentOf(batchKey);
        upload(batchKey, segmentKey, "2026-05-01T10:10:00Z", "99.00", "CK-R-" + uid(), 201);
        dispose(batchKey, segmentKey, "qa-9", "QUALITY", "异常已查明", "CK-D-" + uid(), 201);
        releaseHold(batchKey, "qa-9", "QUALITY", "调查闭环", "CK-REL-" + uid(), 201);

        // 门禁解除后检验流程不受影响（检验本就允许），两步放行成功
        approve(batchKey, "qa-1", "QUALITY", "CK-AP1-" + uid(), 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AP2-" + uid(), 201);
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("RELEASED", objectMapper.readTree(history.getResponse().getContentAsString())
                .path("batch").path("status").asText());

        // 放行后可拆分
        split(batchKey, List.of(child("CH-A-" + uid(), "LOT-A"), child("CH-B-" + uid(), "LOT-B")),
                "CK-SP-" + uid(), 201);
    }

    @Test
    void releaseThenNewExcursion_rearms_andCanReleaseAgain_terminalSegmentDoesNotRearm() throws Exception {
        String batchKey = newBatch();
        String seg1 = registerSegmentWith(batchKey, "SEG-R1-" + uid(), "carrier-a",
                "2026-05-01T10:00:00Z", "2026-05-01T12:00:00Z");
        upload(batchKey, seg1, "2026-05-01T10:10:00Z", "99.00", "CK-R-" + uid(), 201);
        dispose(batchKey, seg1, "qa-1", "QUALITY", "seg1 处置", "CK-D1-" + uid(), 201);
        releaseHold(batchKey, "qa-1", "QUALITY", "第一次闭环", "CK-REL-1-" + uid(), 201);
        assertFalse(temperature(batchKey).path("temperatureHold").asBoolean());

        // 已解除后，异常终态段上再传越界读数：段保持 EXCURSION，但不重新置位冻结
        upload(batchKey, seg1, "2026-05-01T10:20:00Z", "-50.00", "CK-R-" + uid(), 201);
        assertFalse(temperature(batchKey).path("temperatureHold").asBoolean());
        // 未处于冻结态时重复解除 → 409
        releaseHold(batchKey, "qa-1", "QUALITY", "无冻结再解除", "CK-REL-X-" + uid(), 409);

        // 首尾相接登记新段，新段发生异常 → 冻结再次置位
        String seg2 = registerSegmentWith(batchKey, "SEG-R2-" + uid(), "carrier-b",
                "2026-05-01T12:00:00Z", "2026-05-01T14:00:00Z");
        upload(batchKey, seg2, "2026-05-01T12:10:00Z", "0.00", "CK-R-" + uid(), 201);
        assertTrue(temperature(batchKey).path("temperatureHold").asBoolean());

        // 第二次解除：seg2 未处置时整次 422，处置 seg2 后成功（seg1 闭包仍保留）
        releaseHold(batchKey, "qa-2", "QUALITY", "seg2 未处置", "CK-REL-2-PRE-" + uid(), 422);
        dispose(batchKey, seg2, "qa-2", "QUALITY", "seg2 处置", "CK-D2-" + uid(), 201);
        releaseHold(batchKey, "qa-2", "QUALITY", "第二次闭环", "CK-REL-2-" + uid(), 201);

        JsonNode temp = temperature(batchKey);
        assertFalse(temp.path("temperatureHold").asBoolean());
        assertEquals(2, temp.path("segmentCount").asInt());
        assertEquals(2, temp.path("excursionCount").asInt());
        // 查询返回最近一次解除 episode；两段异常原始状态与闭包均保留
        assertEquals("qa-2", temp.path("release").path("investigatorId").asText());
        assertEquals(2, temp.path("release").path("disposedSegmentKeys").size());
        assertEquals("EXCURSION", segmentNode(batchKey, seg1).path("status").asText());
        assertEquals("EXCURSION", segmentNode(batchKey, seg2).path("status").asText());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM temperature_release WHERE batch_key = ?", Integer.class, batchKey));
    }

    // ---------- 幂等 ----------
    @Test
    void commandKey_sameParamsReplay_changedParamsConflict() throws Exception {
        String batchKey = newBatch();
        String body = segmentBody("SEG-IDEM-" + uid(), "2026-05-01T10:00:00Z", "2026-05-01T11:00:00Z",
                "2.00", "8.00", "CK-IDEM-1");
        MvcResult first = mockMvc.perform(post("/api/batches/" + batchKey + "/transport-segments")
                        .header("X-Actor-Id", "carrier-a")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batchKey + "/transport-segments")
                        .header("X-Actor-Id", "carrier-a")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());

        // 同键改参（上限不同）→ 409
        String changed = segmentBody("SEG-IDEM-" + uid(), "2026-05-01T10:00:00Z", "2026-05-01T11:00:00Z",
                "2.00", "9.00", "CK-IDEM-1");
        mockMvc.perform(post("/api/batches/" + batchKey + "/transport-segments")
                        .header("X-Actor-Id", "carrier-a")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM transport_segment WHERE batch_key = ?", Integer.class, batchKey));
    }

    @Test
    void readingCommandKey_failedValidationDoesNotOccupyKey() throws Exception {
        String batchKey = newBatch();
        String segmentKey = registerSegment(batchKey, "2026-05-01T10:00:00Z", "2026-05-01T12:00:00Z");
        String commandKey = "CK-READ-IDEM-" + uid();
        // 先失败（段外时刻）
        upload(batchKey, segmentKey, "2026-05-01T09:00:00Z", "5.00", commandKey, 422);
        // 同键改为合法参数，应成功且占键
        upload(batchKey, segmentKey, "2026-05-01T10:05:00Z", "5.00", commandKey, 201);
        // 再以同键不同参数重放 → 409
        upload(batchKey, segmentKey, "2026-05-01T10:06:00Z", "5.00", commandKey, 409);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM transport_reading WHERE segment_key = ?", Integer.class, segmentKey));
    }

    // ---------- 并发 ----------

    @Test
    void concurrentReadingsAndApproval_commitOrderAdjudicated() throws Exception {
        String batchKey = quarantinedToPendingWithOneSegment();
        String segmentKey = segmentOf(batchKey);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(readingRequest(batchKey, segmentKey,
                        "2026-05-01T10:10:00Z", "99.00", "CK-C-EXC-" + uid())),
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-C-AP-" + uid() + "\"}"))
        );
        int readingCode = results.get(0).get(30, TimeUnit.SECONDS);
        int approvalCode = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, readingCode);
        // 行锁按提交顺序裁决：异常先提交则批准 409；批准先提交则 201，
        // 但冻结最终一定落盘，后续第二批准仍被拦截（在 afterRelease 用例中另证放行可恢复）
        assertTrue(approvalCode == 201 || approvalCode == 409);
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "SELECT temperature_hold FROM batch WHERE batch_key = ?", Boolean.class, batchKey));
        assertEquals("EXCURSION", jdbc.queryForObject(
                "SELECT status FROM transport_segment WHERE segment_key = ?",
                String.class, segmentKey));
        if (approvalCode == 409) {
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM approval WHERE batch_key = ?", Integer.class, batchKey));
        }
    }

    @Test
    void concurrentSameCommandKeyReading_bothReplayFirstResult_singleRow() throws Exception {
        String batchKey = newBatch();
        String segmentKey = registerSegment(batchKey, "2026-05-01T10:00:00Z", "2026-05-01T12:00:00Z");
        String commandKey = "CK-RACE-READ-" + uid();
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(readingRequest(batchKey, segmentKey,
                        "2026-05-01T10:05:00Z", "5.00", commandKey)),
                () -> callStatus(readingRequest(batchKey, segmentKey,
                        "2026-05-01T10:05:00Z", "5.00", commandKey))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM transport_reading WHERE segment_key = ?", Integer.class, segmentKey));
    }

    @Test
    void concurrentReleaseAndDisposition_allOrNothing() throws Exception {
        String batchKey = newBatch();
        String seg1 = registerSegmentWith(batchKey, "SEG-X-" + uid(), "carrier-a",
                "2026-05-01T10:00:00Z", "2026-05-01T12:00:00Z");
        String seg2 = registerSegmentWith(batchKey, "SEG-Y-" + uid(), "carrier-a",
                "2026-05-01T12:00:00Z", "2026-05-01T14:00:00Z");
        upload(batchKey, seg1, "2026-05-01T10:10:00Z", "0.00", "CK-R-" + uid(), 201);
        upload(batchKey, seg2, "2026-05-01T12:10:00Z", "99.00", "CK-R-" + uid(), 201);
        dispose(batchKey, seg1, "qa-1", "QUALITY", "seg1 处置", "CK-D1-" + uid(), 201);

        // 并发：处置 seg2 与解除同时进行；按提交顺序，要么解除先到（seg2 未处置→422 整次不解除），
        // 要么处置先提交、解除成功。任何顺序下解除记录至多一条且不会出现“半解除”。
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey
                                + "/transport-segments/" + seg2 + "/disposition")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispositionBody("seg2 处置", "CK-D2-" + uid()))),
                () -> callStatus(post("/api/batches/" + batchKey + "/temperature-release")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody("调查闭环", "CK-REL-RACE-" + uid())))
        );
        int disposeCode = results.get(0).get(30, TimeUnit.SECONDS);
        int releaseCode = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, disposeCode);
        assertTrue(releaseCode == 201 || releaseCode == 422);
        Integer releaseRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM temperature_release WHERE batch_key = ?", Integer.class, batchKey);
        if (releaseCode == 201) {
            assertEquals(1, releaseRows);
            assertEquals(Boolean.FALSE, jdbc.queryForObject(
                    "SELECT temperature_hold FROM batch WHERE batch_key = ?", Boolean.class, batchKey));
        } else {
            assertEquals(0, releaseRows, "未处置齐全时整次不解除，不得留下解除记录");
            assertEquals(Boolean.TRUE, jdbc.queryForObject(
                    "SELECT temperature_hold FROM batch WHERE batch_key = ?", Boolean.class, batchKey));
            // 补齐后可正常解除
            releaseHold(batchKey, "qa-1", "QUALITY", "调查闭环", "CK-REL-AFTER-" + uid(), 201);
        }
    }

    @Test
    void unknownBatchOrSegment_returns404_missingHeaders_returns400() throws Exception {
        String segmentBody = segmentBody("SEG-X", "2026-05-01T10:00:00Z", "2026-05-01T11:00:00Z",
                "2.00", "8.00", "CK-X");
        // 批次不存在
        mockMvc.perform(post("/api/batches/NO-SUCH/transport-segments")
                        .header("X-Actor-Id", "carrier-a")
                        .contentType(MediaType.APPLICATION_JSON).content(segmentBody))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH/temperature"))
                .andExpect(status().isNotFound());

        String batchKey = newBatch();
        // 缺 X-Actor-Id → 400
        mockMvc.perform(post("/api/batches/" + batchKey + "/transport-segments")
                        .contentType(MediaType.APPLICATION_JSON).content(segmentBody))
                .andExpect(status().isBadRequest());
        String segmentKey = registerSegment(batchKey, "2026-05-01T10:00:00Z", "2026-05-01T11:00:00Z");

        // 读数上传到不存在的段 → 404
        mockMvc.perform(post("/api/batches/" + batchKey + "/transport-segments/SEG-MISSING/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingBody("2026-05-01T10:05:00Z", "5.00", "CK-R")))
                .andExpect(status().isNotFound());
        // 段属于其他批次（路径批次不匹配）→ 404
        String otherBatch = newBatch();
        mockMvc.perform(post("/api/batches/" + otherBatch
                        + "/transport-segments/" + segmentKey + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingBody("2026-05-01T10:05:00Z", "5.00", "CK-R2")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/" + otherBatch + "/transport-segments/" + segmentKey))
                .andExpect(status().isNotFound());
        // 处置缺角色头：头校验先于资源校验 → 400
        mockMvc.perform(post("/api/batches/" + batchKey
                        + "/transport-segments/" + segmentKey + "/disposition")
                        .header("X-Actor-Id", "qa-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispositionBody("note", "CK-D")))
                .andExpect(status().isBadRequest());
        // 解除缺操作人头 → 400
        mockMvc.perform(post("/api/batches/" + batchKey + "/temperature-release")
                        .header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody("note", "CK-REL")))
                .andExpect(status().isBadRequest());
    }

    // ---------- helpers ----------

    private String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String newBatch() throws Exception {
        String batchKey = "BK-T-" + uid();
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-CREATE-" + uid(), batchKey,
                "PROD-1", "LOT-1", java.time.Instant.parse("2026-04-01T00:00:00Z"), List.of("t1")));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        return batchKey;
    }

    /**
     * 构造必做检验已全部 PASS（PENDING_RELEASE）且含一个未完成正常运输段的批次。
     */
    private String quarantinedToPendingWithOneSegment() throws Exception {
        String batchKey = newBatch();
        registerSegmentWith(batchKey, "SEG-P-" + uid(), "carrier-a",
                "2026-05-01T10:00:00Z", "2026-05-01T12:00:00Z");
        String testBody = objectMapper.writeValueAsString(
                new TestCmd("CK-T-" + uid(), "TK-1", "t1", "PASS", "insp-1"));
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(testBody))
                .andExpect(status().isCreated());
        return batchKey;
    }

    /**
     * 构造主状态 RELEASED 且处于温控冻结的批次：先制造异常冻结，再直接置主状态为 RELEASED
     * （数据库边界），用于验证冻结对拆分的拦截独立于主状态。
     */
    private String releasedHeldBatch() throws Exception {
        String batchKey = quarantinedToPendingWithOneSegment();
        String segmentKey = segmentOf(batchKey);
        upload(batchKey, segmentKey, "2026-05-01T10:10:00Z", "0.00", "CK-R-" + uid(), 201);
        jdbc.update("UPDATE batch SET status = 'RELEASED' WHERE batch_key = ?", batchKey);
        return batchKey;
    }

    private String registerSegment(String batchKey, String start, String end) throws Exception {
        return registerSegmentWith(batchKey, "SEG-" + uid(), "carrier-a", start, end);
    }

    private String registerSegmentWith(String batchKey, String segmentKey, String recorder,
                                       String start, String end) throws Exception {
        register(batchKey, segmentKey, start, end, "2.00", "8.00", recorder,
                "CK-SEG-" + uid(), 201);
        return segmentKey;
    }

    private String segmentOf(String batchKey) throws Exception {
        return temperature(batchKey).path("segments").get(0).path("segmentKey").asText();
    }

    private JsonNode segmentNode(String batchKey, String segmentKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey
                        + "/transport-segments/" + segmentKey))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode temperature(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/temperature"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             java.time.Instant producedAt, List<String> requiredTests) {
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private record SegmentCmd(String commandKey, String segmentKey, java.time.Instant startAt,
                              java.time.Instant endAt, BigDecimal minTemp, BigDecimal maxTemp) {
    }

    private record ReadingCmd(String commandKey, java.time.Instant readAt, BigDecimal temperature) {
    }

    private record DispositionCmd(String commandKey, String actionNote) {
    }

    private record ReleaseCmd(String commandKey, String investigationNote) {
    }

    private record SplitCmd(String commandKey, List<Child> children) {
    }

    private String segmentBody(String segmentKey, String start, String end,
                               String min, String max, String commandKey) throws Exception {
        return objectMapper.writeValueAsString(new SegmentCmd(commandKey, segmentKey,
                java.time.Instant.parse(start), java.time.Instant.parse(end),
                new BigDecimal(min), new BigDecimal(max)));
    }

    private String readingBody(String readAt, String temp, String commandKey) throws Exception {
        return objectMapper.writeValueAsString(new ReadingCmd(commandKey,
                java.time.Instant.parse(readAt), new BigDecimal(temp)));
    }

    private void register(String batchKey, String segmentKey, String start, String end,
                          String min, String max, String actor, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/transport-segments")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(segmentBody(segmentKey, start, end, min, max, commandKey)))
                .andExpect(status().is(expected));
    }

    private void upload(String batchKey, String segmentKey, String readAt, String temp,
                        String commandKey, int expected) throws Exception {
        uploadResult(batchKey, segmentKey, readAt, temp, commandKey, expected);
    }

    private MvcResult uploadResult(String batchKey, String segmentKey, String readAt, String temp,
                                   String commandKey, int expected) throws Exception {
        return mockMvc.perform(readingRequest(batchKey, segmentKey, readAt, temp, commandKey))
                .andExpect(status().is(expected)).andReturn();
    }

    private MockHttpServletRequestBuilder readingRequest(String batchKey, String segmentKey,
                                                         String readAt, String temp, String commandKey)
            throws Exception {
        String body = objectMapper.writeValueAsString(new ReadingCmd(commandKey,
                java.time.Instant.parse(readAt), new BigDecimal(temp)));
        return post("/api/batches/" + batchKey + "/transport-segments/" + segmentKey + "/readings")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String dispositionBody(String note, String commandKey) throws Exception {
        return objectMapper.writeValueAsString(new DispositionCmd(commandKey, note));
    }

    private String releaseBody(String note, String commandKey) throws Exception {
        return objectMapper.writeValueAsString(new ReleaseCmd(commandKey, note));
    }

    private void dispose(String batchKey, String segmentKey, String actor, String role,
                         String note, String commandKey, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey
                        + "/transport-segments/" + segmentKey + "/disposition")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispositionBody(note, commandKey)))
                .andExpect(status().is(expected));
    }

    private void releaseHold(String batchKey, String actor, String role, String note,
                             String commandKey, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/temperature-release")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody(note, commandKey)))
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

    private record Child(String batchKey, String batchNo) {
    }

    private Child child(String key, String no) {
        return new Child(key, no);
    }

    private void split(String batchKey, List<Child> children, String commandKey, int expected)
            throws Exception {
        String body = objectMapper.writeValueAsString(new SplitCmd(commandKey, children));
        mockMvc.perform(post("/api/batches/" + batchKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private int callStatus(MockHttpServletRequestBuilder req) {
        try {
            return mockMvc.perform(req).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
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

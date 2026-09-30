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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 仓储温度偏差端到端测试（真实 H2 内存库 MODE=MySQL，自动建表）：
 * 题干缺测场景、越界、30 分钟边界、双人处置、requestId/commandKey 幂等、限值快照、
 * 放行/拆分/合批门禁、时刻视图与读数-放行并发竞争。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StorageExcursionTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM reading_request");
        jdbc.update("DELETE FROM deviation_confirmation");
        jdbc.update("DELETE FROM temperature_deviation");
        jdbc.update("DELETE FROM temperature_reading");
        jdbc.update("DELETE FROM storage_segment");
        jdbc.update("DELETE FROM merge_parent");
        jdbc.update("DELETE FROM merge_order");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 题干主场景：8℃ 上限，10:00 与 10:31 各 7℃ → 缺测偏差，双人处置前门禁关闭 ----------

    @Test
    void promptScenario_missingReadingBlocksReleaseSplitAndMerge_untilTwoRoleDisposal() throws Exception {
        String batchKey = "BK-ST-MAIN-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        createSegment(batchKey, "SEG-1", "8.00", "2.00", "CK-SEG-1", 201);

        JsonNode r1 = registerReading(batchKey, "SEG-1", "REQ-1", "2026-06-01T10:00:00Z",
                "7.00", 201);
        assertEquals(0, r1.path("deviations").size(), "7℃ 未超 8℃ 上限，首读无相邻间隔，不产生偏差");
        assertEquals("GATE_OPEN", r1.path("gateStatus").asText());

        JsonNode r2 = registerReading(batchKey, "SEG-1", "REQ-2", "2026-06-01T10:31:00Z",
                "7.00", 201);
        assertEquals(1, r2.path("deviations").size());
        JsonNode deviation = r2.path("deviations").get(0);
        assertEquals("MISSING_READING", deviation.path("type").asText());
        assertEquals("OPEN", deviation.path("status").asText());
        assertEquals(1, deviation.path("version").asInt());
        assertEquals("2026-06-01T10:00:00Z", deviation.path("gapStart").asText());
        assertEquals("2026-06-01T10:31:00Z", deviation.path("gapEnd").asText());
        assertEquals(0, new BigDecimal(deviation.path("observedTemperatureCelsius").asText())
                .compareTo(new BigDecimal("7.00")));
        assertEquals(0, new BigDecimal(deviation.path("upperLimitSnapshot").asText())
                .compareTo(new BigDecimal("8.00")));
        assertEquals("GATE_CLOSED", r2.path("gateStatus").asText());
        String deviationKey = deviation.path("deviationKey").asText();

        // 检验全 PASS，但存在未处置偏差：两步放行均被门禁拦截
        submitTest(batchKey, "t1", "PASS", "insp-1", 201);
        // 未放行批次有未处置偏差，首步批准即被拒
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 422);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));

        // 双人处置前不可拆分：先处置偏差放行再验证拆分门禁更直接，此处先验证合批失败整单
        // 为得到 RELEASED 父批，另建一个无偏差已放行批次；本批仍不可合批
        String otherParent = "BK-ST-OTHER-" + unique();
        createBatch(otherParent, List.of("t1"), 201);
        releaseBatch(otherParent, "insp-9");
        // 本批无法放行，无法作为合批父批：合批整单失败且不落任何数据
        merge("CK-MERGE-FAIL", "BK-MERGE-CHILD-FAIL-" + unique(), "LOT-C",
                List.of(batchKey, otherParent), 409);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM merge_order", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM merge_parent", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key LIKE 'BK-MERGE-CHILD-FAIL-%'",
                Integer.class));

        // 单人单角色确认不能解除
        confirm(deviationKey, "qa-lead", "QUALITY", 1, "已评估，留样复检中", "CK-C1", 201);
        JsonNode timelineAfterOne = timeline(batchKey, "SEG-1");
        assertEquals("GATE_CLOSED", timelineAfterOne.path("gateStatus").asText());
        assertEquals("OPEN", timelineAfterOne.path("deviations").get(0).path("status").asText());
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 422);

        // 同角色第二人 / 同人换角色 都不允许
        confirm(deviationKey, "qa-other", "QUALITY", 1, null, "CK-C2", 409);
        confirm(deviationKey, "qa-lead", "OPERATIONS", 1, null, "CK-C3", 409);

        // 另一角色、另一人确认同一版本后解除
        confirm(deviationKey, "ops-lead", "OPERATIONS", 1, "运营确认纠偏措施", "CK-C4", 201);
        JsonNode timelineAfterTwo = timeline(batchKey, "SEG-1");
        assertEquals("GATE_OPEN", timelineAfterTwo.path("gateStatus").asText());
        JsonNode disposed = timelineAfterTwo.path("deviations").get(0);
        assertEquals("DISPOSED", disposed.path("status").asText());
        assertEquals(2, disposed.path("confirmations").size());
        assertEquals("qa-lead", disposed.path("confirmations").get(0).path("actorId").asText());
        assertEquals("QUALITY", disposed.path("confirmations").get(0).path("role").asText());
        assertEquals("ops-lead", disposed.path("confirmations").get(1).path("actorId").asText());
        assertEquals("OPERATIONS", disposed.path("confirmations").get(1).path("role").asText());
        assertFalse(disposed.path("disposedAt").isNull());

        // 门禁解除后完成双角色放行
        approve(batchKey, "qa-1", "QUALITY", "CK-A2", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A3", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
        // 放行后可拆分
        split(batchKey, "CK-SPLIT-1", List.of(
                new String[]{"BK-ST-CH1-" + unique(), "L1"},
                new String[]{"BK-ST-CH2-" + unique(), "L2"}), 201);
        assertEquals("SPLIT", currentStatus(batchKey));
    }

    @Test
    void timelineShowsRawReadingsMissingIntervalAndHandlers() throws Exception {
        String batchKey = "BK-TL-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        createSegment(batchKey, "SEG-TL", "8.00", "2.00", "CK-SEG-TL", 201);
        registerReading(batchKey, "SEG-TL", "REQ-TL-1", "2026-06-01T10:00:00Z", "7.00", 201);
        JsonNode second = registerReading(batchKey, "SEG-TL", "REQ-TL-2",
                "2026-06-01T10:31:00Z", "7.00", 201);
        registerReading(batchKey, "SEG-TL", "REQ-TL-3", "2026-06-01T11:00:00Z", "7.50", 201);

        JsonNode tl = timeline(batchKey, "SEG-TL");
        assertEquals(3, tl.path("readings").size());
        assertEquals("REQ-TL-1", tl.path("readings").get(0).path("requestId").asText());
        assertEquals(2, tl.path("intervals").size());
        JsonNode firstGap = tl.path("intervals").get(0);
        assertEquals(1860, firstGap.path("gapSeconds").asInt());
        assertTrue(firstGap.path("missing").asBoolean());
        JsonNode secondGap = tl.path("intervals").get(1);
        assertEquals(1740, secondGap.path("gapSeconds").asInt());
        assertFalse(secondGap.path("missing").asBoolean());
        assertEquals(1, tl.path("deviations").size());
        String deviationKey = second.path("deviations").get(0).path("deviationKey").asText();
        assertEquals(deviationKey, firstGap.path("missingDeviationKey").asText());
        assertTrue(secondGap.path("missingDeviationKey").isNull());
        // 原始读数均不越界
        tl.path("readings").forEach(r -> assertFalse(r.path("outOfLimit").asBoolean()));

        // 处置人出现在偏差确认中
        confirm(deviationKey, "qa-1", "QUALITY", 1, "h1", "CK-TL-C1", 201);
        confirm(deviationKey, "ops-1", "OPERATIONS", 1, "h2", "CK-TL-C2", 201);
        JsonNode after = timeline(batchKey, "SEG-TL");
        assertEquals("GATE_OPEN", after.path("gateStatus").asText());
        assertEquals(0, after.path("openDeviationCount").asInt());
    }

    // ---------- 30 分钟边界 ----------

    @Test
    void gapExactlyThirtyMinutes_noDeviation() throws Exception {
        String batchKey = "BK-G30-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        createSegment(batchKey, "SEG-G", "8.00", "2.00", "CK-SEG-G", 201);
        registerReading(batchKey, "SEG-G", "REQ-G1", "2026-06-01T10:00:00Z", "7.00", 201);
        JsonNode exact = registerReading(batchKey, "SEG-G", "REQ-G2",
                "2026-06-01T10:30:00Z", "7.00", 201);
        assertEquals(0, exact.path("deviations").size(), "恰好 30 分钟不算间断");
        JsonNode justOver = registerReading(batchKey, "SEG-G", "REQ-G3",
                "2026-06-01T11:00:01Z", "7.00", 201);
        assertEquals(1, justOver.path("deviations").size(), "30 分 1 秒为缺测");
        assertEquals("MISSING_READING", justOver.path("deviations").get(0).path("type").asText());
    }

    // ---------- 越界偏差与 RELEASE_HOLD ----------

    @Test
    void outOfLimitReading_onReleasedBatch_setsHoldAndBlocksUntilDisposal() throws Exception {
        String batchKey = "BK-HOLD-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        createSegment(batchKey, "SEG-H", "8.00", "2.00", "CK-SEG-H", 201);
        registerReading(batchKey, "SEG-H", "REQ-H1", "2026-06-01T08:00:00Z", "7.00", 201);
        releaseBatch(batchKey, "insp-h");
        assertEquals("RELEASED", currentStatus(batchKey));
        assertTrue(availableKeys().contains(batchKey));

        JsonNode hot = registerReading(batchKey, "SEG-H", "REQ-H2",
                "2026-06-01T08:20:00Z", "9.00", 201);
        assertEquals(1, hot.path("deviations").size());
        assertEquals("OUT_OF_LIMIT", hot.path("deviations").get(0).path("type").asText());
        assertEquals("RELEASE_HOLD", currentStatus(batchKey), "已放行批次新发偏差必须挂起");
        assertFalse(availableKeys().contains(batchKey), "挂起批次不可用");

        // 挂起期间禁止拆分与合批
        split(batchKey, "CK-SP-HOLD", List.of(
                new String[]{"BK-HOLD-C1-" + unique(), "L1"},
                new String[]{"BK-HOLD-C2-" + unique(), "L2"}), 422);
        String otherParent = "BK-HOLD-OP-" + unique();
        createBatch(otherParent, List.of("t1"), 201);
        releaseBatch(otherParent, "insp-other");
        merge("CK-M-HOLD", "BK-HOLD-MC-" + unique(), "LOT-M", List.of(batchKey, otherParent), 409);
        assertEquals("RELEASE_HOLD", currentStatus(batchKey));

        // 双人处置后恢复 RELEASED，门禁重开
        String deviationKey = hot.path("deviations").get(0).path("deviationKey").asText();
        confirm(deviationKey, "qa-h", "QUALITY", 1, null, "CK-HC1", 201);
        assertEquals("RELEASE_HOLD", currentStatus(batchKey), "单人确认不恢复");
        confirm(deviationKey, "ops-h", "OPERATIONS", 1, null, "CK-HC2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
        assertTrue(availableKeys().contains(batchKey));
    }

    @Test
    void heldBatch_canBeRecalled_andLaterDisposalDoesNotReviveIt() throws Exception {
        String batchKey = "BK-RC-HOLD-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        createSegment(batchKey, "SEG-RCH", "8.00", "2.00", "CK-SEG-RCH", 201);
        registerReading(batchKey, "SEG-RCH", "REQ-RCH-1",
                "2026-06-01T08:00:00Z", "7.00", 201);
        releaseBatch(batchKey, "insp-rch");
        JsonNode hot = registerReading(batchKey, "SEG-RCH", "REQ-RCH-2",
                "2026-06-01T08:31:00Z", "7.00", 201);
        assertEquals("RELEASE_HOLD", currentStatus(batchKey));
        String deviationKey = hot.path("deviations").get(0).path("deviationKey").asText();

        // 挂起批次可直接召回
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", "u-rch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-RCH-1\",\"reason\":\"挂起期质量风险\"}"))
                .andExpect(status().isCreated());
        assertEquals("RECALLED", currentStatus(batchKey));
        assertFalse(availableKeys().contains(batchKey));

        // 召回后双人确认偏差，批次不得复活为 RELEASED
        confirm(deviationKey, "qa-rch", "QUALITY", 1, null, "CK-RCH-C1", 201);
        confirm(deviationKey, "ops-rch", "OPERATIONS", 1, null, "CK-RCH-C2", 201);
        assertEquals("RECALLED", currentStatus(batchKey), "召回终态不得因偏差处置而恢复");
        assertEquals("DISPOSED", timeline(batchKey, "SEG-RCH")
                .path("deviations").get(0).path("status").asText());
    }

    @Test
    void readingExactlyAtUpperLimit_noDeviation() throws Exception {
        String batchKey = "BK-EQ-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        createSegment(batchKey, "SEG-EQ", "8.00", "2.00", "CK-SEG-EQ", 201);
        JsonNode r = registerReading(batchKey, "SEG-EQ", "REQ-EQ1",
                "2026-06-01T08:00:00Z", "8.00", 201);
        assertEquals(0, r.path("deviations").size(), "恰好等于上限 8℃ 不越界");
    }

    // ---------- requestId 幂等与判异 ----------

    @Test
    void readingRequestId_replaysFirstResult_changedTemperatureConflicts() throws Exception {
        String batchKey = "BK-RID-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        createSegment(batchKey, "SEG-R", "8.00", "2.00", "CK-SEG-R", 201);
        registerReading(batchKey, "SEG-R", "REQ-DUP", "2026-06-01T10:00:00Z", "7.00", 201);

        // 同 requestId 同内容重放（含缺测偏差的首次结果）
        JsonNode first = registerReading(batchKey, "SEG-R", "REQ-DUP2",
                "2026-06-01T10:31:00Z", "7.00", 201);
        JsonNode replay = registerReading(batchKey, "SEG-R", "REQ-DUP2",
                "2026-06-01T10:31:00Z", "7.00", 201);
        assertEquals(first, replay);
        // 重放不重复入库读数、不重复生成偏差
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM temperature_reading WHERE segment_key = 'SEG-R'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM temperature_deviation WHERE batch_key = ?", Integer.class, batchKey));

        // 同 requestId 换温度 → 409
        registerReadingExpect(batchKey, "SEG-R", "REQ-DUP2",
                "2026-06-01T10:31:00Z", "6.50", 409);
        // 同 requestId 换时刻 → 409
        registerReadingExpect(batchKey, "SEG-R", "REQ-DUP2",
                "2026-06-01T10:32:00Z", "7.00", 409);

        // 同 requestId 打到另一仓储段 → 409
        createSegment(batchKey, "SEG-R2", "8.00", "2.00", "CK-SEG-R2", 201);
        registerReadingExpect(batchKey, "SEG-R2", "REQ-DUP2",
                "2026-06-01T10:31:00Z", "7.00", 409);

        // 原始读数未被改写
        JsonNode tl = timeline(batchKey, "SEG-R");
        assertEquals(2, tl.path("readings").size());
        assertEquals(0, new BigDecimal(tl.path("readings").get(1)
                .path("temperatureCelsius").asText()).compareTo(new BigDecimal("7.00")));
    }

    // ---------- 限值修改与历史快照 ----------

    @Test
    void limitChangeOnlyAffectsNewReadings_historySnapshotsNotRewritten() throws Exception {
        String batchKey = "BK-LIM-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        createSegment(batchKey, "SEG-L", "8.00", "2.00", "CK-SEG-L", 201);
        // 8.50 在旧限值 8.00 下越界
        JsonNode oldDeviation = registerReading(batchKey, "SEG-L", "REQ-L1",
                "2026-06-01T08:00:00Z", "8.50", 201);
        String deviationKey = oldDeviation.path("deviations").get(0).path("deviationKey").asText();
        assertEquals(0, new BigDecimal(oldDeviation.path("deviations").get(0)
                .path("upperLimitSnapshot").asText()).compareTo(new BigDecimal("8.00")));

        // 限值放宽到 9℃
        String limitsBody = "{\"commandKey\":\"CK-LIM-1\",\"upperLimit\":9.00,\"lowerLimit\":2.00}";
        mockMvc.perform(put("/api/batches/" + batchKey + "/segments/SEG-L/limits")
                        .contentType(MediaType.APPLICATION_JSON).content(limitsBody))
                .andExpect(status().isOk());

        // 8.60 在新限值下不越界（间隔 20 分钟也不缺测）
        JsonNode within = registerReading(batchKey, "SEG-L", "REQ-L2",
                "2026-06-01T08:20:00Z", "8.60", 201);
        assertEquals(0, within.path("deviations").size());

        // 历史偏差快照不回写，仍为旧限值，处置状态不变
        JsonNode tl = timeline(batchKey, "SEG-L");
        assertEquals(0, new BigDecimal(tl.path("upperLimit").asText())
                .compareTo(new BigDecimal("9.00")), "当前限值已更新");
        JsonNode historical = tl.path("deviations").get(0);
        assertEquals(0, new BigDecimal(historical.path("upperLimitSnapshot").asText())
                .compareTo(new BigDecimal("8.00")), "历史快照不回写");
        assertEquals("OPEN", historical.path("status").asText());
        assertEquals(deviationKey, historical.path("deviationKey").asText());
    }

    // ---------- 合批 ----------

    @Test
    void mergeReleasedBatches_createsQuarantinedChildWithUnionedRequiredTests() throws Exception {
        String p1 = "BK-MG-P1-" + unique();
        String p2 = "BK-MG-P2-" + unique();
        createBatch(p1, List.of("外观", "含量"), 201);
        createBatch(p2, List.of("含量", "无菌"), 201);
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");

        String child = "BK-MG-C-" + unique();
        MvcResult result = merge("CK-MG-1", child, "LOT-MERGED", List.of(p1, p2), 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(child, body.path("childKey").asText());
        assertEquals("QUARANTINED", body.path("status").asText());
        assertEquals("PROD-1", body.path("productCode").asText());
        assertEquals(List.of("外观", "含量", "无菌"), objectMapper.convertValue(body.path("requiredTests"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals(List.of(p1, p2), objectMapper.convertValue(body.path("parentKeys"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));

        // 父批不被消耗（与拆分不同），仍 RELEASED；子批有两个父批血缘
        assertEquals("RELEASED", currentStatus(p1));
        assertEquals("RELEASED", currentStatus(p2));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE child_key = ?", Integer.class, child));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM merge_parent mp JOIN merge_order mo ON mp.merge_id = mo.id"
                        + " WHERE mo.child_key = ?", Integer.class, child));
        // 子批祖先查询含两个父批
        MvcResult ancestors = mockMvc.perform(get("/api/batches/" + child + "/ancestors"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(2, objectMapper.readTree(ancestors.getResponse().getContentAsString()).size());

        // 子批键重复 → 409
        merge("CK-MG-2", child, "LOT-OTHER", List.of(p1, p2), 409);
    }

    @Test
    void mergeGuards_failWholeOrder_withoutAnyData() throws Exception {
        String released1 = "BK-MGF-R1-" + unique();
        String released2 = "BK-MGF-R2-" + unique();
        String pending = "BK-MGF-P-" + unique();
        createBatch(released1, List.of("t1"), 201);
        createBatch(released2, List.of("t1"), 201);
        createBatch(pending, List.of("t1"), 201);
        releaseBatch(released1, "insp-1");
        releaseBatch(released2, "insp-2");
        submitTest(pending, "t1", "PASS", "insp-p", 201);

        // 父批非 RELEASED → 整单失败
        merge("CK-MGF-1", "BK-MGF-C1-" + unique(), "L1", List.of(released1, pending), 409);
        // 父批请求内重复 → 409
        merge("CK-MGF-2", "BK-MGF-C2-" + unique(), "L2", List.of(released1, released1), 409);
        // 不同产品编码 → 422
        String otherProduct = "BK-MGF-OP-" + unique();
        String body = objectMapper.writeValueAsString(new CreateBatchCmd("CK-MGF-OP", otherProduct,
                "PROD-2", "LOT-OP", Instant.parse("2026-01-02T03:04:05Z"), List.of("t1")));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        releaseBatch(otherProduct, "insp-op");
        merge("CK-MGF-3", "BK-MGF-C3-" + unique(), "L3", List.of(released1, otherProduct), 422);
        // 父批不存在 → 404
        merge("CK-MGF-4", "BK-MGF-C4-" + unique(), "L4",
                List.of(released1, "NO-SUCH-PARENT-" + unique()), 404);

        // 全部失败均不落任何合批数据
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM merge_order", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM merge_parent", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_lineage", Integer.class));
        assertEquals("RELEASED", currentStatus(released1));

        // 仅 1 个父批 → 400
        String badMerge = "{\"commandKey\":\"CK-MGF-5\",\"childKey\":\"BK-MGF-C5-" + unique()
                + "\",\"childBatchNo\":\"L5\",\"parents\":[\"" + released1 + "\"]}";
        mockMvc.perform(post("/api/merges").contentType(MediaType.APPLICATION_JSON).content(badMerge))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mergeCommandKey_sameParamsReplays_changedParamsConflicts() throws Exception {
        String p1 = "BK-MGI-P1-" + unique();
        String p2 = "BK-MGI-P2-" + unique();
        createBatch(p1, List.of("t1"), 201);
        createBatch(p2, List.of("t1"), 201);
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");
        String child = "BK-MGI-C-" + unique();
        String body = mergeBody("CK-MGI", child, "LOT-I", List.of(p1, p2));

        MvcResult first = mockMvc.perform(post("/api/merges")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/merges")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM merge_order", Integer.class));

        String changed = mergeBody("CK-MGI", "BK-MGI-C2-" + unique(), "LOT-I", List.of(p1, p2));
        mockMvc.perform(post("/api/merges").contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());
    }

    // ---------- 并发：读数入库与第二批准竞争 ----------

    @Test
    void concurrentReadingAndFinalRelease_neverLeavesReleasedBatchWithOpenDeviation() throws Exception {
        String batchKey = "BK-RACE-RD-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        createSegment(batchKey, "SEG-RACE", "8.00", "2.00", "CK-SEG-RACE", 201);
        registerReading(batchKey, "SEG-RACE", "REQ-RACE-1",
                "2026-06-01T10:00:00Z", "7.00", 201);
        submitTest(batchKey, "t1", "PASS", "insp-r", 201);
        approve(batchKey, "qa-r", "QUALITY", "CK-RA1", 201);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey
                        + "/segments/SEG-RACE/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingBody("REQ-RACE-2", "2026-06-01T10:31:00Z", "7.00"))),
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "ops-r").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-RA2\"}"))
        );

        int readingCode = results.get(0).get(30, TimeUnit.SECONDS);
        int approvalCode = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, readingCode, "读数登记始终成功（越界/缺测是业务结果而非接口失败）");

        int openDeviations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM temperature_deviation WHERE batch_key = ? AND status = 'OPEN'",
                Integer.class, batchKey);
        int readings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM temperature_reading WHERE batch_key = ?",
                Integer.class, batchKey);
        int approvals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE batch_key = ?", Integer.class, batchKey);
        assertEquals(1, openDeviations, "恰好一条缺测偏差");
        assertEquals(2, readings);

        String finalStatus = currentStatus(batchKey);
        // 核心不变量：绝不能同时为 RELEASED 与存在未处置偏差
        assertFalse("RELEASED".equals(finalStatus) && openDeviations > 0,
                "不能留下带未处置偏差的已放行批次");
        if (approvalCode == 422) {
            // 读数先提交：第二步放行被门禁拦截，停在 RELEASE_REVIEW，仅一笔批准
            assertEquals("RELEASE_REVIEW", finalStatus);
            assertEquals(1, approvals);
        } else {
            // 放行先提交：批次 RELEASED 后读数再提交，立即转为 RELEASE_HOLD，两笔批准
            assertEquals(201, approvalCode);
            assertEquals("RELEASE_HOLD", finalStatus);
            assertEquals(2, approvals);
        }
    }

    // ---------- 参数与状态失败分支 ----------

    @Test
    void invalidSegmentsAndReadings_return400Or404Or422() throws Exception {
        String batchKey = "BK-BAD-" + unique();
        createBatch(batchKey, List.of("t1"), 201);

        // lower >= upper → 400
        mockMvc.perform(post("/api/batches/" + batchKey + "/segments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-BAD-1\",\"segmentKey\":\"SEG-BAD\","
                                + "\"upperLimit\":2.00,\"lowerLimit\":8.00}"))
                .andExpect(status().isBadRequest());
        // 缺字段 → 400
        mockMvc.perform(post("/api/batches/" + batchKey + "/segments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-BAD-2\",\"segmentKey\":\"SEG-BAD2\"}"))
                .andExpect(status().isBadRequest());
        // 批次不存在 → 404
        mockMvc.perform(post("/api/batches/NO-SUCH-BATCH/segments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-BAD-3\",\"segmentKey\":\"SEG-BAD3\","
                                + "\"upperLimit\":8.00,\"lowerLimit\":2.00}"))
                .andExpect(status().isNotFound());

        createSegment(batchKey, "SEG-OK", "8.00", "2.00", "CK-BAD-4", 201);
        // 仓储段重复 → 409
        createSegment(batchKey, "SEG-OK", "8.00", "2.00", "CK-BAD-5", 409);
        // 仓储段不存在 → 404
        registerReadingExpect(batchKey, "NO-SEG", "REQ-BAD",
                "2026-06-01T10:00:00Z", "7.00", 404);

        registerReading(batchKey, "SEG-OK", "REQ-OK-1",
                "2026-06-01T10:00:00Z", "7.00", 201);
        // 同时刻重复登记 → 422；更早时刻逆序 → 422
        registerReadingExpect(batchKey, "SEG-OK", "REQ-OK-2",
                "2026-06-01T10:00:00Z", "7.00", 422);
        registerReadingExpect(batchKey, "SEG-OK", "REQ-OK-3",
                "2026-06-01T09:59:00Z", "7.00", 422);
        // 读数请求体缺温度 → 400
        mockMvc.perform(post("/api/batches/" + batchKey + "/segments/SEG-OK/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-OK-4\",\"readingTime\":\"2026-06-01T11:00:00Z\"}"))
                .andExpect(status().isBadRequest());

        // 未知偏差确认 → 404；缺角色头 → 400
        mockMvc.perform(post("/api/deviations/NO-SUCH-DEV/confirmations")
                        .header("X-Actor-Id", "qa").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-BAD-6\",\"version\":1}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/deviations/DEV-X/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-BAD-7\",\"version\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void confirmVersionMismatch_andConfirmAfterDisposed_return409() throws Exception {
        String batchKey = "BK-VER-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        createSegment(batchKey, "SEG-V", "8.00", "2.00", "CK-SEG-V", 201);
        JsonNode r = registerReading(batchKey, "SEG-V", "REQ-V1",
                "2026-06-01T10:00:00Z", "9.00", 201);
        String deviationKey = r.path("deviations").get(0).path("deviationKey").asText();

        // 错误版本 → 409
        confirm(deviationKey, "qa-v", "QUALITY", 2, null, "CK-V1", 409);
        confirm(deviationKey, "qa-v", "QUALITY", 1, null, "CK-V2", 201);
        confirm(deviationKey, "ops-v", "OPERATIONS", 1, null, "CK-V3", 201);
        // 已处置再次确认 → 409
        confirm(deviationKey, "qa-v2", "QUALITY", 1, null, "CK-V4", 409);
        assertEquals("DISPOSED", timeline(batchKey, "SEG-V")
                .path("deviations").get(0).path("status").asText());
    }

    // ---------- helpers ----------

    private record CreateBatchCmd(String commandKey, String batchKey, String productCode,
                                  String batchNo, Instant producedAt, List<String> requiredTests) {
    }

    private record ReadingCmd(String requestId, Instant readingTime, java.math.BigDecimal temperature) {
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createBatch(String batchKey, List<String> items, int expected) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateBatchCmd("CK-C-" + unique(), batchKey,
                "PROD-1", "LOT-1", Instant.parse("2026-01-02T03:04:05Z"), items));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private void createSegment(String batchKey, String segmentKey, String upper, String lower,
                               String commandKey, int expected) throws Exception {
        String body = "{\"commandKey\":\"" + commandKey + "\",\"segmentKey\":\"" + segmentKey
                + "\",\"upperLimit\":" + upper + ",\"lowerLimit\":" + lower + "}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/segments")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private String readingBody(String requestId, String readingTime, String temperature) {
        return "{\"requestId\":\"" + requestId + "\",\"readingTime\":\"" + readingTime
                + "\",\"temperature\":" + temperature + "}";
    }

    private JsonNode registerReading(String batchKey, String segmentKey, String requestId,
                                     String readingTime, String temperature, int expected)
            throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/batches/" + batchKey + "/segments/" + segmentKey + "/readings")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(readingBody(requestId, readingTime, temperature)))
                .andExpect(status().is(expected)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void registerReadingExpect(String batchKey, String segmentKey, String requestId,
                                       String readingTime, String temperature, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/segments/" + segmentKey + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingBody(requestId, readingTime, temperature)))
                .andExpect(status().is(expected));
    }

    private void confirm(String deviationKey, String actor, String role, int version, String note,
                         String commandKey, int expected) throws Exception {
        String notePart = note == null ? "null" : ("\"" + note + "\"");
        String body = "{\"commandKey\":\"" + commandKey + "\",\"version\":" + version
                + ",\"note\":" + notePart + "}";
        mockMvc.perform(post("/api/deviations/" + deviationKey + "/confirmations")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private JsonNode timeline(String batchKey, String segmentKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey
                        + "/segments/" + segmentKey + "/timeline"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void submitTest(String batchKey, String item, String result, String inspector,
                            int expected) throws Exception {
        String body = objectMapper.writeValueAsString(new TestCmd("CK-T-" + unique(),
                "TK-" + unique(), item, result, inspector));
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private void approve(String batchKey, String actor, String role, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    private void releaseBatch(String batchKey, String inspector) throws Exception {
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        for (JsonNode item : node.path("batch").path("requiredTests")) {
            submitTest(batchKey, item.asText(), "PASS", inspector, 201);
        }
        approve(batchKey, "qa-" + unique(), "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", "CK-AO-" + unique(), 201);
    }

    private void split(String parentKey, String commandKey, List<String[]> children, int expected)
            throws Exception {
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

    private String mergeBody(String commandKey, String childKey, String childBatchNo,
                             List<String> parents) throws Exception {
        return objectMapper.writeValueAsString(new MergeCmd(commandKey, childKey, childBatchNo,
                parents));
    }

    private record MergeCmd(String commandKey, String childKey, String childBatchNo,
                            List<String> parents) {
    }

    private MvcResult merge(String commandKey, String childKey, String childBatchNo,
                            List<String> parents, int expected) throws Exception {
        return mockMvc.perform(post("/api/merges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(commandKey, childKey, childBatchNo, parents)))
                .andExpect(status().is(expected)).andReturn();
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private java.util.List<String> availableKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        java.util.List<String> keys = new java.util.ArrayList<>();
        objectMapper.readTree(result.getResponse().getContentAsString())
                .forEach(n -> keys.add(n.path("batchKey").asText()));
        return keys;
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
        List<Future<Integer>> futures = new java.util.ArrayList<>();
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

package com.example.starter.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
 * 批次有效期与复检延期测试：到期可用性、有效期查询、双人复检延期主流程、
 * 延期上限（分钟/次数）、复检人/确认人约束、召回交互、commandKey/extensionKey 幂等与并发裁决。
 * 全部流程基于真实 H2（MODE=MySQL）内存库，验证唯一约束、行锁与事务边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class ShelfLifeExtensionTest {

    private static final Instant PRODUCED_AT = Instant.parse("2026-01-02T03:04:05Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TestClockConfig.MutableClock clock;

    @BeforeEach
    void cleanTables() {
        clock.setInstant(TestClockConfig.BASE_INSTANT);
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM shelf_life_extension");
        jdbc.update("DELETE FROM extension_request");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 有效期与到期可用性 ----------

    @Test
    void createBatch_persistsShelfLifeAndValidUntil_queryReflectsRemainingMinutes() throws Exception {
        String key = "BK-SL-" + unique();
        // 保质 120 分钟：有效期 = 03:04:05 + 120 = 05:04:05；当前 04:00 → 剩余 64 分 5 秒，向下取整 64
        createBatch(key, List.of("t1"), 120);

        JsonNode shelf = shelfLife(key);
        assertEquals("2026-01-02T05:04:05Z", shelf.path("validUntil").asText());
        assertEquals(120, shelf.path("shelfLifeMinutes").asLong());
        assertFalse(shelf.path("expired").asBoolean());
        assertEquals(64, shelf.path("remainingMinutes").asLong());
        assertEquals(0, shelf.path("extensionCount").asInt());
        assertEquals(0, shelf.path("totalExtendedMinutes").asLong());
        assertEquals(0, shelf.path("extensions").size());

        JsonNode batch = historyBatch(key);
        assertEquals(120, batch.path("shelfLifeMinutes").asLong());
        assertFalse(batch.path("expired").asBoolean());
        assertTrue(availableKeys().contains(key));
    }

    @Test
    void expiredBatch_excludedFromAvailable_flaggedInQueries_statusNotRewritten() throws Exception {
        String key = "BK-EX-" + unique();
        // 保质 30 分钟：03:34:05 到期，基准时刻 04:00 已到期
        createBatch(key, List.of("t1"), 30);

        assertEquals("QUARANTINED", historyBatch(key).path("status").asText(), "到期不改写批次状态");
        assertTrue(historyBatch(key).path("expired").asBoolean());
        assertEquals(0, historyBatch(key).path("remainingMinutes").asLong());
        assertFalse(availableKeys().contains(key), "到期批次立即从可用查询排除");

        List<String> expired = expiredKeys();
        assertTrue(expired.contains(key));
    }

    @Test
    void expiredList_isStableSortedByIdAndIncludesRecalledAndSplit() throws Exception {
        String a = "BK-EXL-A-" + unique();
        String b = "BK-EXL-B-" + unique();
        String c = "BK-EXL-C-" + unique();
        String d = "BK-EXL-D-" + unique();
        createBatch(a, List.of("t1"), 30);
        createBatch(b, List.of("t1"), 120);
        createBatch(c, List.of("t1"), 43200);
        createBatch(d, List.of("t1"), 120);
        // b 到期前放行；d 到期前拆分（父批 SPLIT）
        releaseBatch(b, "insp-b");
        releaseBatch(d, "insp-d");
        split(d, "CK-EXL-S", twoChildren(), 201);

        // 拨到 120 分钟保质到期之后
        clock.setInstant(Instant.parse("2026-01-02T05:05:00Z"));
        // 到期不拦截召回：b 召回后仍计入到期清单
        recall(b, "u", "到期后召回", "CK-EXL-R", 201);

        List<String> expired = expiredKeys();
        int ia = expired.indexOf(a);
        int ib = expired.indexOf(b);
        int id = expired.indexOf(d);
        assertTrue(ia >= 0 && ib >= 0 && id >= 0, "到期清单含隔离/召回/拆分的到期批次");
        assertTrue(ia < ib && ib < id, "到期清单按 id 稳定升序");
        assertFalse(expired.contains(c), "未到期批次不在清单");
        assertEquals("RECALLED", historyBatch(b).path("status").asText());
        assertEquals("SPLIT", historyBatch(d).path("status").asText());
        // 到期批次在可用查询中一律缺席（无论状态）
        List<String> available = availableKeys();
        assertFalse(available.contains(a));
        assertFalse(available.contains(b));
        assertFalse(available.contains(d));
    }

    @Test
    void expiryBlocksApprove_andSplit_returns422() throws Exception {
        // 场景一：检验全部通过后到期，批准被 422
        String approveKey = "BK-EA-" + unique();
        createBatch(approveKey, List.of("t1"), 120);
        submitTest(approveKey, "t1", "PASS", "insp");
        assertEquals("PENDING_RELEASE", historyBatch(approveKey).path("status").asText());
        clock.setInstant(Instant.parse("2026-01-02T05:05:00Z"));
        approve(approveKey, "qa-1", "QUALITY", "CK-EA-Q", 422);
        assertEquals("PENDING_RELEASE", historyBatch(approveKey).path("status").asText(),
                "到期拦截不改变状态");
        assertFalse(availableKeys().contains(approveKey));

        // 场景二：放行后到期，拆分被 422
        clock.setInstant(TestClockConfig.BASE_INSTANT);
        String splitKey = "BK-ES-" + unique();
        createBatch(splitKey, List.of("t1"), 120);
        releaseBatch(splitKey, "insp-2");
        clock.setInstant(Instant.parse("2026-01-02T05:05:00Z"));
        split(splitKey, "CK-ES-S", twoChildren(), 422);
        assertEquals("RELEASED", historyBatch(splitKey).path("status").asText());
    }

    // ---------- 双人复检延期主流程 ----------

    @Test
    void extensionSubmitThenConfirm_extendsValidityRestoresAvailable_recordsImmutable() throws Exception {
        String key = "BK-XT-" + unique();
        createBatch(key, List.of("t1", "t2"), 120);
        releaseBatch(key, "insp-1");
        clock.setInstant(Instant.parse("2026-01-02T05:05:00Z"));
        // 已到期：不可用但状态仍 RELEASED
        assertTrue(historyBatch(key).path("expired").asBoolean());
        assertFalse(availableKeys().contains(key));

        // 复检人提交（与原批准人 qa-1/ops-1 都不同），尚不改有效期
        MvcResult submit = submitExtension(key, "reinspect-1", "EXT-1", "复检合格", 60,
                "CK-EXT-S1", 201);
        JsonNode submitBody = objectMapper.readTree(submit.getResponse().getContentAsString());
        assertEquals("PENDING", submitBody.path("status").asText());
        assertEquals(60, submitBody.path("extendMinutes").asInt());
        assertTrue(historyBatch(key).path("expired").asBoolean(), "待确认期间有效期不变");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM shelf_life_extension WHERE batch_key = ?", Integer.class, key));

        // 另一名不同于复检人的批准角色确认 → 同事务顺延并恢复可用
        MvcResult confirm = confirmExtension("EXT-1", "reconf-1", "QUALITY", "CK-EXT-C1", 201);
        JsonNode confirmBody = objectMapper.readTree(confirm.getResponse().getContentAsString());
        assertEquals("CONFIRMED", confirmBody.path("status").asText());
        assertEquals("2026-01-02T06:04:05Z", confirmBody.path("validUntil").asText());
        assertFalse(confirmBody.path("expired").asBoolean());
        assertEquals("RELEASED", historyBatch(key).path("status").asText(), "批次状态不被延期改写");
        assertTrue(availableKeys().contains(key), "延期生效后恢复可用");

        JsonNode shelf = shelfLife(key);
        assertEquals("2026-01-02T06:04:05Z", shelf.path("validUntil").asText());
        assertEquals(60, shelf.path("totalExtendedMinutes").asLong());
        assertEquals(1, shelf.path("extensionCount").asInt());
        JsonNode record = shelf.path("extensions").get(0);
        assertEquals("EXT-1", record.path("extensionKey").asText());
        assertEquals("reinspect-1", record.path("inspectorId").asText());
        assertEquals("reconf-1", record.path("confirmerId").asText());
        assertEquals("QUALITY", record.path("confirmerRole").asText());
        assertEquals("复检合格", record.path("reinspectionConclusion").asText());

        // 批次历史含延期记录，既有检验/批准记录不变
        MvcResult history = mockMvc.perform(get("/api/batches/" + key + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode historyNode = objectMapper.readTree(history.getResponse().getContentAsString());
        assertEquals(1, historyNode.path("extensions").size());
        assertEquals(2, historyNode.path("tests").size());
        assertEquals(2, historyNode.path("approvals").size());
        assertTrue(historyNode.path("recall").isNull());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM shelf_life_extension WHERE batch_key = ?", Integer.class, key));
    }

    // ---------- 延期上限 ----------

    @Test
    void cumulativeMinutes_cannotExceedTwiceShelfLife_failureChangesNothing() throws Exception {
        String key = "BK-CAP-" + unique();
        createBatch(key, List.of("t1"), 100); // 上限 200 分钟
        releaseBatch(key, "insp");

        // 120 通过
        submitExtension(key, "re-i", "E-CAP-1", "合格", 120, "CK-CAP-S1", 201);
        confirmExtension("E-CAP-1", "re-c", "QUALITY", "CK-CAP-C1", 201);
        // 再 100：累计 220 > 200 → 422，且不产生请求之外的任何生效变化
        submitExtension(key, "re-i", "E-CAP-2", "合格", 100, "CK-CAP-S2", 422);
        assertEquals(1, shelfLife(key).path("extensionCount").asInt());
        assertEquals(120, shelfLife(key).path("totalExtendedMinutes").asLong());
        assertEquals("2026-01-02T06:44:05Z", shelfLife(key).path("validUntil").asText());

        // 恰好补足到 200：通过
        submitExtension(key, "re-i", "E-CAP-3", "合格", 80, "CK-CAP-S3", 201);
        confirmExtension("E-CAP-3", "re-c", "OPERATIONS", "CK-CAP-C3", 201);
        assertEquals(2, shelfLife(key).path("extensionCount").asInt());
        assertEquals(200, shelfLife(key).path("totalExtendedMinutes").asLong());
        // 已达两倍上限：再延 1 分钟也不行
        submitExtension(key, "re-i", "E-CAP-4", "合格", 1, "CK-CAP-S4", 422);
        assertEquals(2, shelfLife(key).path("extensionCount").asInt());
    }

    @Test
    void atMostThreeExtensions_fourthReturns422() throws Exception {
        String key = "BK-CNT-" + unique();
        createBatch(key, List.of("t1"), 43200);
        releaseBatch(key, "insp");
        for (int i = 1; i <= 3; i++) {
            submitExtension(key, "re-i", "E-CNT-" + i, "合格", 1, "CK-CNT-S" + i, 201);
            confirmExtension("E-CNT-" + i, "re-c", "QUALITY", "CK-CNT-C" + i, 201);
        }
        assertEquals(3, shelfLife(key).path("extensionCount").asInt());
        submitExtension(key, "re-i", "E-CNT-4", "合格", 1, "CK-CNT-S4", 422);
        assertEquals(3, shelfLife(key).path("extensionCount").asInt());
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM shelf_life_extension WHERE batch_key = ?", Integer.class, key));
    }

    @Test
    void extendMinutesOutOfRange_returns400() throws Exception {
        String key = "BK-RANGE-" + unique();
        createBatch(key, List.of("t1"), 43200);
        releaseBatch(key, "insp");
        rawSubmitExtension(key, "re-i", "{\"commandKey\":\"CK-R1\",\"extensionKey\":\"E-R1\","
                + "\"reinspectionConclusion\":\"合格\",\"extendMinutes\":0}", 400);
        rawSubmitExtension(key, "re-i", "{\"commandKey\":\"CK-R2\",\"extensionKey\":\"E-R2\","
                + "\"reinspectionConclusion\":\"合格\",\"extendMinutes\":43201}", 400);
        rawSubmitExtension(key, "re-i", "{\"commandKey\":\"CK-R3\",\"extensionKey\":\"E-R3\","
                + "\"reinspectionConclusion\":\"\",\"extendMinutes\":10}", 400);
        assertEquals(0, shelfLife(key).path("extensionCount").asInt());
    }

    // ---------- 双人复检约束与前置条件 ----------

    @Test
    void inspectorMustDifferFromOriginalApprovers_confirmerMustDifferFromInspector() throws Exception {
        String key = "BK-WHO-" + unique();
        createBatch(key, List.of("t1"), 43200);
        releaseBatch(key, "insp");

        // 复检人是原批准人 qa-1 / ops-1 → 422
        submitExtension(key, "qa-1", "E-W1", "合格", 10, "CK-W-S1", 422);
        submitExtension(key, "ops-1", "E-W2", "合格", 10, "CK-W-S2", 422);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM extension_request", Integer.class));

        // 合法复检人提交
        submitExtension(key, "reinspect-x", "E-W3", "合格", 10, "CK-W-S3", 201);
        // 确认人就是复检人 → 422
        confirmExtension("E-W3", "reinspect-x", "QUALITY", "CK-W-C1", 422);
        // 非法角色头 → 400
        confirmExtensionRaw("E-W3", "reconf-x", "BOSS", "{\"commandKey\":\"CK-W-C2\"}", 400);
        // 待确认请求仍在、有效期不变
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM extension_request WHERE extension_key = 'E-W3'", String.class));
        assertEquals("2026-02-01T03:04:05Z", shelfLife(key).path("validUntil").asText());

        // 换人确认成功
        confirmExtension("E-W3", "reconf-x", "OPERATIONS", "CK-W-C3", 201);
        assertEquals(1, shelfLife(key).path("extensionCount").asInt());
    }

    @Test
    void nonPassConclusionOrUnfinishedChecks_returns422() throws Exception {
        // 检验未全部通过 / 未完成双人批准 → 422
        String early = "BK-NP-E-" + unique();
        createBatch(early, List.of("t1", "t2"), 43200);
        submitTest(early, "t1", "PASS", "insp");
        submitExtension(early, "re-i", "E-NP-1", "合格", 10, "CK-NP-S1", 422);
        submitTest(early, "t2", "PASS", "insp");
        // 全部检验通过但尚未双人批准
        submitExtension(early, "re-i", "E-NP-2", "合格", 10, "CK-NP-S2", 422);
        approve(early, "qa-1", "QUALITY", "CK-NP-A1", 201);
        // 仅一名批准人仍不满足
        submitExtension(early, "re-i", "E-NP-3", "合格", 10, "CK-NP-S3", 422);
        approve(early, "ops-1", "OPERATIONS", "CK-NP-A2", 201);

        // 已放行但复检结论不合格 → 422
        submitExtension(early, "re-i", "E-NP-4", "FAIL", 10, "CK-NP-S4", 422);
        submitExtension(early, "re-i", "E-NP-5", "微生物不合格", 10, "CK-NP-S5", 422);
        assertEquals(0, shelfLife(early).path("extensionCount").asInt());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM extension_request", Integer.class), "失败提交不残留请求");

        // “合格”结论通过
        submitExtension(early, "re-i", "E-NP-6", "复检结论：合格", 10, "CK-NP-S6", 201);
        confirmExtension("E-NP-6", "re-c", "QUALITY", "CK-NP-C6", 201);
        assertEquals(1, shelfLife(early).path("extensionCount").asInt());
    }

    @Test
    void recalledBatchAndRecalledAncestor_blockExtension_recordsKeptAfterwards() throws Exception {
        // 直接召回：延期提交 422
        String recalled = "BK-RC-D-" + unique();
        createBatch(recalled, List.of("t1"), 43200);
        releaseBatch(recalled, "insp");
        recall(recalled, "u", "质量问题", "CK-RC-D1", 201);
        submitExtension(recalled, "re-i", "E-RC-1", "合格", 10, "CK-RC-S1", 422);

        // 祖先召回场景：子批先完成一次延期，祖先随后召回
        String root = "BK-RC-R-" + unique();
        createBatch(root, List.of("t1"), 43200);
        releaseBatch(root, "insp-r");
        String child = "BK-RC-C-" + unique();
        split(root, "CK-RC-S", List.of(new String[]{child, "L1"},
                new String[]{"BK-RC-C2-" + unique(), "L2"}), 201);
        releaseBatch(child, "insp-c");
        submitExtension(child, "re-i", "E-RC-2", "合格", 10, "CK-RC-S2", 201);
        confirmExtension("E-RC-2", "re-c", "QUALITY", "CK-RC-C2", 201);
        assertEquals(1, shelfLife(child).path("extensionCount").asInt());
        // 召回前再留一个待确认请求，用于验证召回后确认也被拦截
        submitExtension(child, "re-i2", "E-RC-4", "合格", 10, "CK-RC-S4", 201);

        recall(root, "u", "原料污染", "CK-RC-R1", 201);
        // 后代不可用，但已生效延期记录保留
        assertFalse(availableKeys().contains(child));
        assertEquals(1, shelfLife(child).path("extensions").size());
        MvcResult history = mockMvc.perform(get("/api/batches/" + child + "/history"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(1, objectMapper.readTree(history.getResponse().getContentAsString())
                .path("extensions").size());
        // 祖先召回后：后代的新延期提交与待确认确认一律 422
        submitExtension(child, "re-i3", "E-RC-3", "合格", 10, "CK-RC-S3", 422);
        confirmExtension("E-RC-4", "re-c2", "QUALITY", "CK-RC-C4", 422);
        assertEquals(1, shelfLife(child).path("extensionCount").asInt(), "确认被拦截，不追加记录");
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM extension_request WHERE extension_key = 'E-RC-4'", String.class));
        // 已顺延后的有效期不被回滚
        assertEquals("2026-02-01T03:14:05Z", shelfLife(child).path("validUntil").asText());
    }

    // ---------- 拆分子批继承保质分钟 ----------

    @Test
    void splitChildren_inheritShelfLife_recomputeValidUntilFromOwnProducedAt() throws Exception {
        String parent = "BK-INH-P-" + unique();
        createBatch(parent, List.of("t1"), 240);
        releaseBatch(parent, "insp");
        String c1 = "BK-INH-C1-" + unique();
        String c2 = "BK-INH-C2-" + unique();
        MvcResult result = split(parent, "CK-INH-S",
                List.of(new String[]{c1, "L1"}, new String[]{c2, "L2"}), 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        for (JsonNode child : body.path("children")) {
            assertEquals(240, child.path("shelfLifeMinutes").asLong());
            // 子批生产时间与父批相同 → 初始有效期同口径重算，且不继承父批任何延期
            assertEquals("2026-01-02T07:04:05Z", child.path("validUntil").asText());
            assertFalse(child.path("expired").asBoolean());
        }
        assertEquals(240, shelfLife(c1).path("shelfLifeMinutes").asLong());
        assertEquals(240, shelfLife(c2).path("shelfLifeMinutes").asLong());
        assertEquals(0, shelfLife(c1).path("extensionCount").asInt());
    }

    // ---------- 幂等 ----------

    @Test
    void commandKeyAndExtensionKey_replayConflictAndFailureNotOccupying() throws Exception {
        String key = "BK-IDEM-X-" + unique();
        createBatch(key, List.of("t1"), 43200);
        releaseBatch(key, "insp");

        // 同 commandKey 同参重放：首次结果快照一致，仅一条请求
        MvcResult first = submitExtension(key, "re-i", "E-ID-1", "合格", 10, "CK-ID-S1", 201);
        MvcResult replay = submitExtension(key, "re-i", "E-ID-1", "合格", 10, "CK-ID-S1", 201);
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM extension_request WHERE extension_key = 'E-ID-1'", Integer.class));

        // 同 commandKey 改参（分钟不同）→ 409
        submitExtension(key, "re-i", "E-ID-1", "合格", 11, "CK-ID-S1", 409);
        // 同 extensionKey 改参（换 commandKey 也不行）→ 409
        submitExtension(key, "re-i", "E-ID-1", "结论合格", 10, "CK-ID-S2", 409);
        // 同 extensionKey 同参、不同 commandKey → 重放待确认结果
        submitExtension(key, "re-i", "E-ID-1", "合格", 10, "CK-ID-S3", 201);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM extension_request WHERE extension_key = 'E-ID-1'", Integer.class));

        // 失败不占键：CK-FAIL 先因结论不合格失败，修正为合格后同键成功
        submitExtension(key, "re-i", "E-ID-2", "FAIL", 10, "CK-FAIL", 422);
        submitExtension(key, "re-i", "E-ID-2", "合格", 10, "CK-FAIL", 201);

        // 确认幂等：同键同参重放生效快照；换确认人 → 409
        MvcResult confirmed = confirmExtension("E-ID-1", "re-c", "QUALITY", "CK-ID-C1", 201);
        MvcResult confirmReplay = confirmExtension("E-ID-1", "re-c", "QUALITY", "CK-ID-C1", 201);
        assertEquals(confirmed.getResponse().getContentAsString(),
                confirmReplay.getResponse().getContentAsString());
        confirmExtension("E-ID-1", "re-c2", "QUALITY", "CK-ID-C2", 409);
        // 不同 commandKey、同 extensionKey 同确认人 → 仍只生效一次，重放快照
        MvcResult replayOtherCmd = confirmExtension("E-ID-1", "re-c", "QUALITY", "CK-ID-C3", 201);
        assertEquals(confirmed.getResponse().getContentAsString(),
                replayOtherCmd.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM shelf_life_extension WHERE extension_key = 'E-ID-1'",
                Integer.class));
        // 已生效的 extensionKey 不能再次提交
        submitExtension(key, "re-i", "E-ID-1", "合格", 10, "CK-ID-S4", 201);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM extension_request WHERE extension_key = 'E-ID-1'", Integer.class));
    }

    // ---------- 并发 ----------

    @Test
    void concurrentConfirmSameExtensionKey_onlyOneEffective() throws Exception {
        String key = "BK-CC-E-" + unique();
        createBatch(key, List.of("t1"), 43200);
        releaseBatch(key, "insp");
        submitExtension(key, "re-i", "E-CC-1", "合格", 10, "CK-CC-S1", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(confirmRequest("E-CC-1", "re-c1", "QUALITY", "CK-CC-C1")),
                () -> callStatus(confirmRequest("E-CC-1", "re-c2", "OPERATIONS", "CK-CC-C2"))
        );
        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        // 按提交顺序只有一个确认人生效：恰好一个 201、另一个 409（谁先获锁不确定）
        assertEquals(1, (first == 201 ? 1 : 0) + (second == 201 ? 1 : 0),
                "同一 extensionKey 恰好生效一次");
        assertEquals(1, (first == 409 ? 1 : 0) + (second == 409 ? 1 : 0),
                "异确认人重复确认返回 409");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM shelf_life_extension WHERE extension_key = 'E-CC-1'",
                Integer.class));
        // 只顺延一次 10 分钟
        assertEquals("2026-02-01T03:14:05Z", shelfLife(key).path("validUntil").asText());
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT status FROM extension_request WHERE extension_key = 'E-CC-1'", String.class));
    }

    @Test
    void concurrentSubmitSameCommandKey_singlePendingRequest() throws Exception {
        String key = "BK-CC-S-" + unique();
        createBatch(key, List.of("t1"), 43200);
        releaseBatch(key, "insp");
        String body = submitBody("CK-CC-SAME", "E-CC-S1", "合格", 10);
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + key + "/extensions")
                        .header("X-Actor-Id", "re-i")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/" + key + "/extensions")
                        .header("X-Actor-Id", "re-i")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM extension_request WHERE extension_key = 'E-CC-S1'",
                Integer.class));
    }

    @Test
    void concurrentAncestorRecallAndExtensionConfirm_commitOrderDecides() throws Exception {
        String root = "BK-CC-R-" + unique();
        createBatch(root, List.of("t1"), 43200);
        releaseBatch(root, "insp-r");
        String child = "BK-CC-C-" + unique();
        split(root, "CK-CC-SPLIT", List.of(new String[]{child, "L1"},
                new String[]{"BK-CC-C2-" + unique(), "L2"}), 201);
        releaseBatch(child, "insp-c");
        submitExtension(child, "re-i", "E-CC-2", "合格", 10, "CK-CC-S2", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(confirmRequest("E-CC-2", "re-c", "QUALITY", "CK-CC-C3")),
                () -> callStatus(post("/api/batches/" + root + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CC-RC\",\"reason\":\"根批召回\"}"))
        );
        int confirm = results.get(0).get(30, TimeUnit.SECONDS);
        int recall = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, recall, "SPLIT 根批始终可召回");
        Integer recordCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shelf_life_extension WHERE extension_key = 'E-CC-2'",
                Integer.class);
        if (confirm == 201) {
            // 延期先生效、召回后提交：记录保留但不恢复可用
            assertEquals(1, recordCount);
            assertFalse(availableKeys().contains(child));
            assertEquals(1, shelfLife(child).path("extensions").size());
        } else {
            // 召回先提交：确认 422，有效期/记录不变，请求停留 PENDING
            assertEquals(422, confirm);
            assertEquals(0, recordCount);
            assertEquals("PENDING", jdbc.queryForObject(
                    "SELECT status FROM extension_request WHERE extension_key = 'E-CC-2'",
                    String.class));
            assertEquals("2026-02-01T03:04:05Z", shelfLife(child).path("validUntil").asText());
        }
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, Long shelfLifeMinutes, List<String> requiredTests) {
    }

    private record SubmitExtCmd(String commandKey, String extensionKey,
                                String reinspectionConclusion, Integer extendMinutes) {
    }

    private record ConfirmCmd(String commandKey) {
    }

    private void createBatch(String batchKey, List<String> items, long shelfLifeMinutes)
            throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(), batchKey,
                "PROD-1", "LOT-1", PRODUCED_AT, shelfLifeMinutes, items));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void submitTest(String batchKey, String item, String result, String inspector)
            throws Exception {
        String body = objectMapper.writeValueAsString(new TestCmd("CK-T-" + unique(),
                "TK-" + unique(), item, result, inspector));
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
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

    private void recall(String batchKey, String actor, String reason, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason
                                + "\"}"))
                .andExpect(status().is(expected));
    }

    /**
     * 让批次完成 全部检验 PASS + QUALITY/OPERATIONS 双角色批准（qa-1、ops-1）进入 RELEASED。
     */
    private void releaseBatch(String batchKey, String inspector) throws Exception {
        JsonNode node = historyBatch(batchKey);
        for (JsonNode item : node.path("requiredTests")) {
            submitTest(batchKey, item.asText(), "PASS", inspector);
        }
        approve(batchKey, "qa-1", "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AO-" + unique(), 201);
        assertEquals("RELEASED", historyBatch(batchKey).path("status").asText());
    }

    private String submitBody(String commandKey, String extensionKey, String conclusion,
                              int extendMinutes) throws Exception {
        return objectMapper.writeValueAsString(
                new SubmitExtCmd(commandKey, extensionKey, conclusion, extendMinutes));
    }

    private MvcResult submitExtension(String batchKey, String inspector, String extensionKey,
                                      String conclusion, int extendMinutes, String commandKey,
                                      int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/extensions")
                        .header("X-Actor-Id", inspector)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(commandKey, extensionKey, conclusion, extendMinutes)))
                .andExpect(status().is(expected)).andReturn();
    }

    private void rawSubmitExtension(String batchKey, String inspector, String rawBody, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/extensions")
                        .header("X-Actor-Id", inspector)
                        .contentType(MediaType.APPLICATION_JSON).content(rawBody))
                .andExpect(status().is(expected));
    }

    private MockHttpServletRequestBuilder confirmRequest(String extensionKey, String confirmer,
                                                         String role, String commandKey)
            throws Exception {
        return post("/api/batches/extensions/" + extensionKey + "/confirm")
                .header("X-Actor-Id", confirmer).header("X-Approval-Role", role)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ConfirmCmd(commandKey)));
    }

    private MvcResult confirmExtension(String extensionKey, String confirmer, String role,
                                       String commandKey, int expected) throws Exception {
        return mockMvc.perform(confirmRequest(extensionKey, confirmer, role, commandKey))
                .andExpect(status().is(expected)).andReturn();
    }

    private void confirmExtensionRaw(String extensionKey, String confirmer, String role,
                                     String rawBody, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/extensions/" + extensionKey + "/confirm")
                        .header("X-Actor-Id", confirmer).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON).content(rawBody))
                .andExpect(status().is(expected));
    }

    private List<String[]> twoChildren() {
        return List.of(new String[]{"BK-CH1-" + unique(), "L1"},
                new String[]{"BK-CH2-" + unique(), "L2"});
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

    private MvcResult split(String parentKey, String commandKey, List<String[]> children,
                            int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitBody(commandKey, children)))
                .andExpect(status().is(expected)).andReturn();
    }

    private JsonNode historyBatch(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("batch");
    }

    private JsonNode shelfLife(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/shelf-life"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<String> availableKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        List<String> keys = new ArrayList<>();
        objectMapper.readTree(result.getResponse().getContentAsString())
                .forEach(n -> keys.add(n.path("batchKey").asText()));
        return keys;
    }

    private List<String> expiredKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/expired"))
                .andExpect(status().isOk()).andReturn();
        List<String> keys = new ArrayList<>();
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

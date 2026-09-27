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
 * 批次托盘隔离 H2 端到端测试：类别声明、装载主流程与全部 422 分支、整批事务回滚、
 * 解除装载历史保留、装运快照冻结、隔离处置、血缘召回待复核联动、commandKey 幂等、
 * 并发装载唯一约束裁决与可控时钟时间戳。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class PalletSegregationFlowTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM pallet_review_flag");
        jdbc.update("DELETE FROM pallet_event");
        jdbc.update("DELETE FROM pallet_shipment");
        jdbc.update("DELETE FROM pallet_loading");
        jdbc.update("DELETE FROM pallet");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 隔离类别声明 ----------

    @Test
    void declareCategory_firstWins_replayAndChangeConflict() throws Exception {
        String batch = newBatch("CAT");
        MvcResult first = declareCategory(batch, "FOOD", "CK-CAT-1", 201);
        assertEquals("FOOD", objectMapper.readTree(first.getResponse().getContentAsString())
                .path("segregationCategory").asText());

        // 同 commandKey 同参重放首次响应
        MvcResult replay = declareCategory(batch, "FOOD", "CK-CAT-1", 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 同 commandKey 异参 → 409
        declareCategory(batch, "CHEM", "CK-CAT-1", 409);
        // 不同 commandKey 但类别一致 → 返回当前声明 200，不产生第二份声明
        declareCategory(batch, "FOOD", "CK-CAT-2", 200);
        // 不同 commandKey 换类别 → 409，类别不可更改
        declareCategory(batch, "CHEM", "CK-CAT-3", 409);
        assertEquals("FOOD", jdbc.queryForObject(
                "SELECT segregation_category FROM batch WHERE batch_key = ?", String.class, batch));
    }

    // ---------- 装载主流程与隔板 ----------

    @Test
    void sameCategoryLoadsWithoutDividers_multiCategoryNeedsDividerAndDistinctSlots() throws Exception {
        String b1 = newBatch("S");
        String b2 = newBatch("S");
        declareCategory(b1, "FOOD", "K1-" + uniq(), 201);
        declareCategory(b2, "FOOD", "K2-" + uniq(), 201);
        String pallet = newPallet("P-SAME", 0, "CK-P-" + uniq());

        // 同类别无需隔板
        MvcResult ok = load(pallet, List.of(entry(b1, 1), entry(b2, 2)), "CK-LOAD-" + uniq(), 201);
        JsonNode body = objectMapper.readTree(ok.getResponse().getContentAsString());
        assertEquals(0, body.path("dividerCount").asInt());
        assertEquals(0, body.path("requiredDividerCount").asInt());
        assertEquals(0, body.path("dividerGap").asInt());
        assertEquals("OPEN", body.path("palletStatus").asText());
        assertEquals(2, body.path("loadings").size());
        // 响应保持请求规范排序
        assertEquals(b1, body.path("loadings").get(0).path("batchKey").asText());
        assertEquals(1, body.path("loadings").get(0).path("seq").asInt());

        // 第三批不同类别：0 隔板 → 422，整批不写入（当前仍 2 条 ACTIVE）
        String b3 = newBatch("D");
        declareCategory(b3, "CHEM", "K3-" + uniq(), 201);
        MvcResult shortage = load(pallet, List.of(entry(b3, 3)), "CK-LOAD-FAIL-" + uniq(), 422);
        assertEquals("CATEGORY_CONFLICT", errorCode(shortage));
        assertActiveCount(pallet, 2);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_loading WHERE batch_key = ?", Integer.class, b3));

        // 配置 1 块隔板产生版本 1，再装载成功并固化版本快照
        MvcResult dividers = configureDividers(pallet, 1, "CK-DIV-" + uniq(), 200);
        JsonNode dividerBody = objectMapper.readTree(dividers.getResponse().getContentAsString());
        assertEquals(1, dividerBody.path("dividerVersion").asInt());
        assertEquals(1, dividerBody.path("delta").asInt());
        assertTrue(dividerBody.path("changed").asBoolean());

        // 相同数量再配：空操作，不产生新版本
        MvcResult noop = configureDividers(pallet, 1, "CK-DIV2-" + uniq(), 200);
        assertFalse(objectMapper.readTree(noop.getResponse().getContentAsString()).path("changed").asBoolean());

        MvcResult loaded = load(pallet, List.of(entry(b3, 3)), "CK-LOAD2-" + uniq(), 201);
        JsonNode loadedBody = objectMapper.readTree(loaded.getResponse().getContentAsString());
        assertEquals(1, loadedBody.path("requiredDividerCount").asInt());
        assertEquals(0, loadedBody.path("dividerGap").asInt());
        assertEquals(1, loadedBody.path("loadings").get(0).path("dividerVersion").asInt());
        assertActiveCount(pallet, 3);
    }

    @Test
    void missingOneDivider_reportsActualRequiredGap_andRejects422() throws Exception {
        String b1 = newBatch("A");
        String b2 = newBatch("B");
        String b3 = newBatch("C");
        declareCategory(b1, "FOOD", uniq(), 201);
        declareCategory(b2, "DRUG", uniq(), 201);
        declareCategory(b3, "CHEM", uniq(), 201);
        String pallet = newPallet("P-GAP", 1, uniq());

        // 3 个类别要求 2 块隔板，实际 1，差额 -1
        MvcResult result = load(pallet, List.of(entry(b1, 1), entry(b2, 2), entry(b3, 3)), uniq(), 422);
        assertEquals("DIVIDER_SHORTAGE", errorCode(result));
        String message = objectMapper.readTree(result.getResponse().getContentAsString()).path("message").asText();
        assertTrue(message.contains("实际 1"));
        assertTrue(message.contains("要求 2"));
        assertTrue(message.contains("差额 -1"));
        assertActiveCount(pallet, 0);
    }

    // ---------- 分隔位与请求重复 ----------

    @Test
    void duplicateSlots_inRequestAndAgainstExisting_reject422AndWriteNothing() throws Exception {
        String b1 = newBatch("X");
        String b2 = newBatch("Y");
        String b3 = newBatch("Z");
        declareCategory(b1, "FOOD", uniq(), 201);
        declareCategory(b2, "FOOD", uniq(), 201);
        declareCategory(b3, "FOOD", uniq(), 201);
        String pallet = newPallet("P-SLOT", 0, uniq());

        // 请求内分隔位重复
        MvcResult dupSlot = load(pallet, List.of(entry(b1, 1), entry(b2, 1)), uniq(), 422);
        assertEquals("SLOT_DUPLICATE", errorCode(dupSlot));
        assertActiveCount(pallet, 0);

        // 请求内批次重复
        MvcResult dupBatch = load(pallet, List.of(entry(b1, 1), entry(b1, 2)), uniq(), 422);
        assertEquals("REQUEST_BATCH_DUPLICATE", errorCode(dupBatch));

        // 正常装载 b1→1
        load(pallet, List.of(entry(b1, 1)), uniq(), 201);
        // b2、b3 请求内合法，但 b2 与已占用分隔位 1 冲突 → 整批回滚，b3 也不写入
        MvcResult conflict = load(pallet, List.of(entry(b2, 1), entry(b3, 2)), uniq(), 422);
        assertEquals("SLOT_DUPLICATE", errorCode(conflict));
        assertActiveCount(pallet, 1);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_loading WHERE batch_key IN (?,?)", Integer.class, b2, b3));
    }

    // ---------- 批次状态/占用/祖先召回 ----------

    @Test
    void invalidBatchStatus_andOccupiedByOtherPallet_wholeBatchRejects() throws Exception {
        String quarantined = newBatch("Q");
        String released = newBatch("R");
        String rejected = newBatch("J");
        declareCategory(quarantined, "FOOD", uniq(), 201);
        declareCategory(released, "FOOD", uniq(), 201);
        declareCategory(rejected, "FOOD", uniq(), 201);
        releaseBatch(released);
        rejectBatch(rejected);

        String pallet = newPallet("P-ST", 0, uniq());
        // 已放行不能装载
        load(pallet, List.of(entry(released, 1)), uniq(), 422);
        // 已拒收不能装载
        load(pallet, List.of(entry(rejected, 1)), uniq(), 422);
        // 未声明类别不能装载
        String noCategory = newBatch("N");
        MvcResult noCat = load(pallet, List.of(entry(noCategory, 1)), uniq(), 422);
        assertEquals("CATEGORY_NOT_DECLARED", errorCode(noCat));
        assertActiveCount(pallet, 0);

        // quarantined 先装到托盘 P1
        String p1 = newPallet("P-OCC1", 0, uniq());
        load(p1, List.of(entry(quarantined, 1)), uniq(), 201);
        // 另一托盘 P2 装同一批 → 422 占用；同请求附带合法批次也必须整体不写入
        String other = newBatch("O");
        declareCategory(other, "FOOD", uniq(), 201);
        MvcResult occupied = load(pallet, List.of(entry(quarantined, 1), entry(other, 2)), uniq(), 422);
        assertEquals("BATCH_OCCUPIED_OTHER_PALLET", errorCode(occupied));
        assertActiveCount(pallet, 0);
        // 同托盘重复装载已在托批次 → 409
        load(p1, List.of(entry(quarantined, 2)), uniq(), 409);
    }

    // ---------- 解除装载 ----------

    @Test
    void unload_onlyOpen_releasesSlotsAndKeepsHistory() throws Exception {
        String b1 = newBatch("U1");
        String b2 = newBatch("U2");
        declareCategory(b1, "FOOD", uniq(), 201);
        declareCategory(b2, "DRUG", uniq(), 201);
        String pallet = newPallet("P-UNL", 1, uniq());
        load(pallet, List.of(entry(b1, 1), entry(b2, 2)), uniq(), 201);

        // 请求含不在托盘的批次 → 422 整批不解除
        String outsider = newBatch("U3");
        declareCategory(outsider, "FOOD", uniq(), 201);
        unload(pallet, List.of(b1, outsider), uniq(), 422);
        assertActiveCount(pallet, 2);

        // 合法解除 b1：实际释放 1、要求 1、剩余 1
        MvcResult result = unload(pallet, List.of(b1), "CK-UNL-" + uniq(), 200);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(1, body.path("requestedCount").asInt());
        assertEquals(1, body.path("releasedSlotCount").asInt());
        assertEquals(1, body.path("remainingCount").asInt());
        assertActiveCount(pallet, 1);

        // 分隔位 1 已释放：新批次可重新使用 1 号位
        String b3 = newBatch("U4");
        declareCategory(b3, "FOOD", uniq(), 201);
        load(pallet, List.of(entry(b3, 1)), uniq(), 201);

        // 历史保留：含 b1 已解除行（unloadedAt 非 null）与全部装载行
        JsonNode detail = palletDetail(pallet);
        JsonNode history = detail.path("history");
        assertEquals(3, history.size());
        JsonNode unloadedRow = history.get(0);
        assertEquals(b1, unloadedRow.path("batchKey").asText());
        assertFalse(unloadedRow.path("unloadedAt").isNull());
        // 当前 ACTIVE 装载为 b2、b3
        assertEquals(2, detail.path("pallet").path("loadings").size());

        // 装运后不能解除
        ship(pallet, uniq(), 201);
        unload(pallet, List.of(b2), uniq(), 409);
        // 装载历史与记录仍在
        assertEquals(3, palletDetail(pallet).path("history").size());
    }

    // ---------- 装运快照冻结与隔离处置 ----------

    @Test
    void ship_freezesSnapshot_laterDividerChangeDoesNotRetroact_quarantineKeepsRecords() throws Exception {
        String b1 = newBatch("F1");
        String b2 = newBatch("F2");
        declareCategory(b1, "FOOD", uniq(), 201);
        declareCategory(b2, "DRUG", uniq(), 201);
        String pallet = newPallet("P-SHIP", 1, uniq());
        load(pallet, List.of(entry(b1, 1), entry(b2, 2)), uniq(), 201);

        // 空托盘不能装运
        String empty = newPallet("P-EMPTY", 0, uniq());
        ship(empty, uniq(), 422);

        MvcResult shipped = ship(pallet, "CK-SHIP-" + uniq(), 201);
        JsonNode shipBody = objectMapper.readTree(shipped.getResponse().getContentAsString());
        assertEquals("SHIPPED", shipBody.path("palletStatus").asText());
        // 托盘创建时即配置 1 块隔板，未再变更，故装运固化版本为 0
        assertEquals(0, shipBody.path("dividerVersion").asInt());
        assertEquals(2, shipBody.path("snapshot").size());
        assertEquals("DRUG", shipBody.path("snapshot").get(1).path("segregationCategory").asText());
        assertEquals(2, shipBody.path("snapshot").get(1).path("slotNo").asInt());

        // 已装运：改隔板/装载/解除全部拒绝
        configureDividers(pallet, 5, uniq(), 409);
        String b3 = newBatch("F3");
        declareCategory(b3, "CHEM", uniq(), 201);
        load(pallet, List.of(entry(b3, 3)), uniq(), 409);
        unload(pallet, List.of(b1), uniq(), 409);

        // 装运快照不随后续操作改写：仍为版本 0、2 条明细
        JsonNode detail = palletDetail(pallet);
        JsonNode snapshotView = detail.path("shipment");
        assertFalse(snapshotView.isMissingNode());
        assertEquals(0, snapshotView.path("dividerVersion").asInt());
        assertEquals(2, snapshotView.path("snapshot").size());
        assertEquals(Integer.valueOf(0), jdbc.queryForObject(
                "SELECT MIN(divider_version) FROM pallet_shipment WHERE pallet_key = ?",
                Integer.class, pallet));
        assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(DISTINCT divider_version) FROM pallet_shipment WHERE pallet_key = ?",
                Integer.class, pallet), "全部快照行固化同一隔板版本");
        // 托盘当前隔板仍为 1（配置被拒绝）
        assertEquals(1, detail.path("pallet").path("dividerCount").asInt());

        // OPEN 托盘不能直接隔离处置
        quarantine(empty, uniq(), "未装运不存在类别错误处置", 409);
        // 已装运发现类别错误 → 进入隔离处置，记录全部保留
        MvcResult quarantined = quarantine(pallet, "CK-Q-" + uniq(),
                "事后发现 DRUG 与 FOOD 隔板失效", 200);
        assertEquals("QUARANTINED", objectMapper.readTree(
                quarantined.getResponse().getContentAsString()).path("palletStatus").asText());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_shipment WHERE pallet_key = ?", Integer.class, pallet));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_loading WHERE pallet_key = ? AND active = 1",
                Integer.class, pallet));
        assertEquals(2, palletDetail(pallet).path("history").size());
        // 隔离处置后仍不能解除/再装运
        unload(pallet, List.of(b1), uniq(), 409);
        ship(pallet, uniq(), 409);
    }

    // ---------- 血缘召回联动待复核 ----------

    @Test
    void lineageRecall_flagsAllUnshippedBatchesOnPallet_blocksShipment_untilUnload() throws Exception {
        // 放行 X 后拆出两个子批；召回 X（SPLIT 可召回）命中血缘后代
        String x = newBatch("X");
        releaseBatch(x);
        String child = "BK-CHILD-" + uniq();
        String childB = "BK-CHILD-B-" + uniq();
        split(x, List.of(new SplitChildCmd(child, "LOT-C"), new SplitChildCmd(childB, "LOT-CB")),
                uniq(), 201);
        declareCategory(child, "FOOD", uniq(), 201);

        // 同托盘另装一个与血缘无关的未装运批次，也应一并标记
        String neighbor = newBatch("N");
        declareCategory(neighbor, "DRUG", uniq(), 201);

        String pallet = newPallet("P-REV", 1, uniq());
        load(pallet, List.of(entry(child, 1), entry(neighbor, 2)), uniq(), 201);
        assertActiveCount(pallet, 2);

        recall(x, "ancestor-recall", uniq(), 201);

        JsonNode detail = palletDetail(pallet);
        JsonNode flags = detail.path("reviewFlags");
        assertEquals(2, flags.size(), "命中托盘上所有未装运批次均标记待复核");
        for (JsonNode flag : flags) {
            assertEquals(x, flag.path("sourceRecalledKey").asText());
        }
        // 待复核期间装运被拒，且不写入快照
        MvcResult blocked = ship(pallet, uniq(), 422);
        assertEquals("PENDING_REVIEW", errorCode(blocked));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_shipment WHERE pallet_key = ?", Integer.class, pallet));

        // 解除全部批次：释放活标记但保留 REVIEW_FLAGGED 事件
        unload(pallet, List.of(child, neighbor), uniq(), 200);
        assertEquals(0, palletDetail(pallet).path("reviewFlags").size());
        long reviewEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_event WHERE pallet_key = ? AND event_type = 'REVIEW_FLAGGED'",
                Integer.class, pallet);
        assertEquals(2, reviewEvents, "待复核事件作为历史证据保留");
        // 批次自身状态不被联动改写
        assertEquals("QUARANTINED", currentStatus(child));
        assertEquals("QUARANTINED", currentStatus(neighbor));
    }

    @Test
    void lineageRecall_doesNotFlagAlreadyShippedPallet() throws Exception {
        String x = newBatch("X2");
        releaseBatch(x);
        String child = "BK-CHILD2-" + uniq();
        String childB2 = "BK-CHILD2-B-" + uniq();
        split(x, List.of(new SplitChildCmd(child, "LOT-C2"), new SplitChildCmd(childB2, "LOT-C2B")),
                uniq(), 201);
        declareCategory(child, "FOOD", uniq(), 201);

        String pallet = newPallet("P-SHIPPED-REV", 0, uniq());
        load(pallet, List.of(entry(child, 1)), uniq(), 201);
        ship(pallet, uniq(), 201);

        recall(x, "ancestor-recall-after-ship", uniq(), 201);

        JsonNode detail = palletDetail(pallet);
        assertEquals(0, detail.path("reviewFlags").size(), "已装运托盘冻结，不新增待复核标记");
        assertEquals("SHIPPED", detail.path("pallet").path("status").asText());
        assertEquals(1, detail.path("shipment").path("snapshot").size());
    }

    // ---------- 幂等：同参重放/异参 409/失败不占键 ----------

    @Test
    void commandKey_sameReplays_changedConflicts_failureDoesNotOccupy() throws Exception {
        String b1 = newBatch("I1");
        String b2 = newBatch("I2");
        declareCategory(b1, "FOOD", uniq(), 201);
        declareCategory(b2, "DRUG", uniq(), 201);
        String pallet = newPallet("P-IDEM", 0, uniq());

        // 首次因 0 隔板装载两类失败：失败不占键
        String key = "CK-IDEMPOTENT-LOAD";
        load(pallet, List.of(entry(b1, 1), entry(b2, 2)), key, 422);
        assertActiveCount(pallet, 0);
        // 同一 commandKey 在补齐隔板后以相同参数成功（失败未占键）
        configureDividers(pallet, 1, uniq(), 200);
        MvcResult first = load(pallet, List.of(entry(b1, 1), entry(b2, 2)), key, 201);
        // 同参重放首次响应
        MvcResult replay = load(pallet, List.of(entry(b1, 1), entry(b2, 2)), key, 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertActiveCount(pallet, 2);
        // 同键异参（分隔位不同）→ 409
        load(pallet, List.of(entry(b1, 3), entry(b2, 4)), key, 409);
        // 只写入一次装载关系（重放不重复写）
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_loading WHERE pallet_key = ? AND active = 1",
                Integer.class, pallet));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'LOAD_PALLET' AND command_key = ?",
                Integer.class, key));
    }

    // ---------- 并发：唯一约束裁决 ----------

    @Test
    void concurrentLoadSameBatchToTwoPallets_onlyOneSucceeds() throws Exception {
        String b1 = newBatch("C1");
        String other = newBatch("C2");
        declareCategory(b1, "FOOD", uniq(), 201);
        declareCategory(other, "FOOD", uniq(), 201);
        String p1 = newPallet("P-CON1", 0, uniq());
        String p2 = newPallet("P-CON2", 0, uniq());

        String body1 = loadBody(List.of(entry(b1, 1), entry(other, 2)), "CK-C1-" + uniq());
        String body2 = loadBody(List.of(entry(b1, 1)), "CK-C2-" + uniq());
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/pallets/" + p1 + "/loadings")
                        .contentType(MediaType.APPLICATION_JSON).content(body1)),
                () -> callStatus(post("/api/pallets/" + p2 + "/loadings")
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
        );

        int code1 = results.get(0).get(30, TimeUnit.SECONDS);
        int code2 = results.get(1).get(30, TimeUnit.SECONDS);
        // 按提交顺序恰有一个事务成功，另一个 422；绝无同一批次同时存在两个 ACTIVE 装载
        assertNotEquals(code1, code2);
        assertTrue(code1 == 201 || code1 == 422);
        assertTrue(code2 == 201 || code2 == 422);

        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_loading WHERE batch_key = ? AND active = 1",
                Integer.class, b1);
        assertEquals(1, active, "唯一约束保证同一批次并发只在一个托盘中成功");
        String winner = jdbc.queryForObject(
                "SELECT pallet_key FROM pallet_loading WHERE batch_key = ? AND active = 1",
                String.class, b1);
        if (winner.equals(p1)) {
            assertEquals(201, code1);
            assertActiveCount(p1, 2);
            assertActiveCount(p2, 0);
        } else {
            assertEquals(201, code2);
            assertActiveCount(p2, 1);
            // 失败事务整批回滚：other 也未被装入 p1
            assertActiveCount(p1, 0);
        }
    }

    @Test
    void concurrentSameCommandKeySameParams_bothReplayFirstResult() throws Exception {
        String b1 = newBatch("R1");
        String b2 = newBatch("R2");
        declareCategory(b1, "FOOD", uniq(), 201);
        declareCategory(b2, "FOOD", uniq(), 201);
        String pallet = newPallet("P-CMD", 0, uniq());
        String body = loadBody(List.of(entry(b1, 1), entry(b2, 2)), "CK-RACE-KEY");

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/pallets/" + pallet + "/loadings")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/pallets/" + pallet + "/loadings")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS));
        }
        assertActiveCount(pallet, 2);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_key = 'CK-RACE-KEY'",
                Integer.class));
    }

    // ---------- 可控时钟 ----------

    @Test
    void timestampsUseInjectedUtcClock() throws Exception {
        MvcResult clockResult = mockMvc.perform(get("/api/pallets")).andReturn();
        assertEquals(200, clockResult.getResponse().getStatus());

        String b1 = newBatch("T1");
        declareCategory(b1, "FOOD", uniq(), 201);
        String pallet = newPallet("P-TIME", 0, uniq());
        load(pallet, List.of(entry(b1, 1)), uniq(), 201);
        JsonNode detail = palletDetail(pallet);
        String loadedAt = detail.path("history").get(0).path("loadedAt").asText();
        // 装载时间取自注入的固定 UTC 时钟（见 FixedClockConfig）
        assertEquals(FixedClockTestConfig.FIXED_INSTANT, loadedAt);
        // UTC 精度保留到秒以下且可解析
        assertEquals(Instant.parse(FixedClockTestConfig.FIXED_INSTANT), Instant.parse(loadedAt));

        unload(pallet, List.of(b1), uniq(), 200);
        JsonNode history = palletDetail(pallet).path("history").get(0);
        assertEquals(FixedClockTestConfig.FIXED_INSTANT, history.path("unloadedAt").asText(),
                "解除时间同样使用 UTC 可控时钟");
    }

    // ---------- helpers ----------

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record BatchSeed(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests) {
    }

    private String newBatch(String prefix) throws Exception {
        String key = "BK-" + prefix + "-" + uniq();
        String body = objectMapper.writeValueAsString(new BatchSeed("CK-B-" + uniq(), key,
                "PROD-1", "LOT-" + key, Instant.parse("2026-02-03T04:05:06Z"), List.of("t1")));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        return key;
    }

    private void releaseBatch(String batchKey) throws Exception {
        submitPass(batchKey);
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "qa-" + uniq()).header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"" + uniq() + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "ops-" + uniq()).header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"" + uniq() + "\"}"))
                .andExpect(status().isCreated());
    }

    private void rejectBatch(String batchKey) throws Exception {
        String body = objectMapper.writeValueAsString(new TestCmd(uniq(), "TK-" + uniq(), "t1",
                "FAIL", "insp-" + uniq()));
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void submitPass(String batchKey) throws Exception {
        String body = objectMapper.writeValueAsString(new TestCmd(uniq(), "TK-" + uniq(), "t1",
                "PASS", "insp-" + uniq()));
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private MvcResult declareCategory(String batchKey, String category, String commandKey, int expected)
            throws Exception {
        String body = objectMapper.writeValueAsString(
                new DeclareCategoryCmd(commandKey, category));
        return mockMvc.perform(post("/api/batches/" + batchKey + "/segregation-category")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private record DeclareCategoryCmd(String commandKey, String segregationCategory) {
    }

    private String newPallet(String prefix, int dividerCount, String commandKey) throws Exception {
        String key = prefix + "-" + uniq();
        String body = objectMapper.writeValueAsString(new PalletCmd(commandKey, key, dividerCount));
        mockMvc.perform(post("/api/pallets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        return key;
    }

    private record PalletCmd(String commandKey, String palletKey, Integer dividerCount) {
    }

    private record LoadEntry(String batchKey, int slotNo) {
    }

    private LoadEntry entry(String batchKey, int slot) {
        return new LoadEntry(batchKey, slot);
    }

    private record LoadCmd(String commandKey, List<LoadEntry> entries) {
    }

    private String loadBody(List<LoadEntry> entries, String commandKey) throws Exception {
        return objectMapper.writeValueAsString(new LoadCmd(commandKey, entries));
    }

    private MvcResult load(String palletKey, List<LoadEntry> entries, String commandKey, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/pallets/" + palletKey + "/loadings")
                        .contentType(MediaType.APPLICATION_JSON).content(loadBody(entries, commandKey)))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult configureDividers(String palletKey, int count, String commandKey, int expected)
            throws Exception {
        String body = objectMapper.writeValueAsString(new DividerCmd(commandKey, count));
        return mockMvc.perform(post("/api/pallets/" + palletKey + "/dividers")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private record DividerCmd(String commandKey, int dividerCount) {
    }

    private MvcResult unload(String palletKey, List<String> batchKeys, String commandKey, int expected)
            throws Exception {
        String body = objectMapper.writeValueAsString(new UnloadCmd(commandKey, batchKeys));
        return mockMvc.perform(post("/api/pallets/" + palletKey + "/unload")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private record UnloadCmd(String commandKey, List<String> batchKeys) {
    }

    private MvcResult ship(String palletKey, String commandKey, int expected) throws Exception {
        return mockMvc.perform(post("/api/pallets/" + palletKey + "/shipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult quarantine(String palletKey, String commandKey, String reason, int expected)
            throws Exception {
        String body = objectMapper.writeValueAsString(new QuarantineCmd(commandKey, reason));
        return mockMvc.perform(post("/api/pallets/" + palletKey + "/quarantine")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private record QuarantineCmd(String commandKey, String reason) {
    }

    private MvcResult split(String parent, List<SplitChildCmd> children, String commandKey, int expected)
            throws Exception {
        String body = objectMapper.writeValueAsString(new SplitCmd(commandKey, children));
        return mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private record SplitCmd(String commandKey, List<SplitChildCmd> children) {
    }

    private record SplitChildCmd(String batchKey, String batchNo) {
    }

    private MvcResult recall(String batchKey, String reason, String commandKey, int expected)
            throws Exception {
        String body = objectMapper.writeValueAsString(new RecallCmd(commandKey, reason));
        return mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", "recaller-" + uniq())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private record RecallCmd(String commandKey, String reason) {
    }

    private JsonNode palletDetail(String palletKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/pallets/" + palletKey))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private void assertActiveCount(String palletKey, int expected) {
        assertEquals(expected, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_loading WHERE pallet_key = ? AND active = 1",
                Integer.class, palletKey));
    }

    private String errorCode(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asText();
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

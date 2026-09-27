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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批次托盘隔离端到端测试：装载/解除/装运/处置主流程、422 校验分支、
 * commandKey 幂等、血缘召回答复核标记、历史左闭右开区间与持久化核对。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PalletFlowTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM pallet_disposal");
        jdbc.update("DELETE FROM pallet_shipment");
        jdbc.update("DELETE FROM batch_occupancy");
        jdbc.update("DELETE FROM pallet_load");
        jdbc.update("DELETE FROM pallet");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程 ----------

    @Test
    void happyFlow_loadUnloadShipSnapshotAndDisposal() throws Exception {
        String pallet = "PL-HAPPY-" + unique();
        String pallet2 = "PL-HAPPY2-" + unique();
        String b1 = "BK-PH1-" + unique();
        String b2 = "BK-PH2-" + unique();
        String b3 = "BK-PH3-" + unique();
        String b4 = "BK-PH4-" + unique();
        createPallet(pallet, 2, 201);
        createBatch(b1, 201);
        createBatch(b2, 201);
        createBatch(b3, 201);
        createBatch(b4, 201);

        // 同类别可共享分隔位；不同类别须不同分隔位
        MvcResult loadResult = load(pallet, "CK-L-1", 201,
                item(b1, "A", 1), item(b2, "A", 1), item(b3, "B", 2));
        JsonNode loadBody = read(loadResult);
        assertEquals(3, loadBody.path("items").size());
        assertEquals(1, loadBody.path("dividerVersion").asInt());
        assertEquals(b1, loadBody.path("items").get(0).path("batchKey").asText());
        assertEquals("LOADED", loadBody.path("items").get(0).path("status").asText());

        JsonNode detail = getJson("/api/pallets/" + pallet);
        assertEquals(3, detail.path("currentLoads").size());
        assertTrue(detail.path("shipment").isNull());
        assertTrue(detail.path("disposal").isNull());

        JsonNode diag = getJson("/api/pallets/" + pallet + "/diagnostics");
        assertEquals(2, diag.path("actualDividers").asInt());
        assertEquals(1, diag.path("requiredDividers").asInt());
        assertEquals(0, diag.path("dividerDeficit").asInt());
        assertTrue(diag.path("dividerSufficient").asBoolean());
        assertEquals(List.of(1), toIntList(diag.path("categoryCompartments").path("A")));
        assertEquals(List.of(2), toIntList(diag.path("categoryCompartments").path("B")));
        assertEquals(3, diag.path("currentLoadCount").asInt());
        assertEquals(0, diag.path("pendingReviewCount").asInt());

        // 解除 b3：释放占用但保留历史
        unload(pallet, "CK-U-1", List.of(b3), 201);
        JsonNode history = getJson("/api/pallets/" + pallet + "/history");
        assertEquals(3, history.path("events").size());
        JsonNode b3Event = null;
        for (JsonNode e : history.path("events")) {
            if (e.path("batchKey").asText().equals(b3)) {
                b3Event = e;
            }
        }
        assertNotNull(b3Event);
        assertEquals("UNLOADED", b3Event.path("status").asText());
        assertFalse(b3Event.path("unloadedAt").isNull());

        // b3 占用已释放，可装入其他托盘
        createPallet(pallet2, 0, 201);
        load(pallet2, "CK-L-2", 201, item(b3, "B", 1));

        // 装运放行：固化快照
        MvcResult shipResult = ship(pallet, "CK-S-1", 201);
        JsonNode shipBody = read(shipResult);
        assertEquals(2, shipBody.path("itemCount").asInt());
        assertEquals(1, shipBody.path("categoryCount").asInt());
        assertEquals(2, shipBody.path("dividerCount").asInt());
        assertEquals(1, shipBody.path("dividerVersion").asInt());
        assertTrue(shipBody.path("openDurationMillis").asLong() >= 0);
        assertEquals("SHIPPED", shipBody.path("items").get(0).path("status").asText());

        // 已装运托盘禁止再装载、解除、重复装运
        load(pallet, "CK-L-3", 409, item(b4, "A", 1));
        unload(pallet, "CK-U-2", List.of(b1), 409);
        ship(pallet, "CK-S-2", 409);

        // 装运后修改隔板：允许，但快照不追溯
        updateDividers(pallet, "CK-D-1", 5, 201);
        JsonNode detail2 = getJson("/api/pallets/" + pallet);
        assertEquals(5, detail2.path("pallet").path("dividerCount").asInt());
        assertEquals(2, detail2.path("pallet").path("dividerVersion").asInt());
        assertEquals(2, detail2.path("shipment").path("dividerCount").asInt());
        assertEquals(1, detail2.path("shipment").path("dividerVersion").asInt());

        // 已装运托盘发现类别错误 → 隔离处置，装载记录保留
        dispose(pallet, "CK-X-1", "装运后发现类别标识错误", 201);
        JsonNode detail3 = getJson("/api/pallets/" + pallet);
        assertEquals("DISPOSAL", detail3.path("pallet").path("status").asText());
        assertEquals("装运后发现类别标识错误", detail3.path("disposal").path("reason").asText());
        dispose(pallet, "CK-X-2", "重复处置", 409);

        // 历史完整保留：b1/b2 SHIPPED、b3 UNLOADED，记录不删除
        JsonNode history2 = getJson("/api/pallets/" + pallet + "/history");
        assertEquals(3, history2.path("events").size());
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_load WHERE pallet_key = ?", Integer.class, pallet));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_load WHERE pallet_key = ? AND status = 'SHIPPED'",
                Integer.class, pallet));
    }

    // ---------- 装载校验失败分支：整批 422/404 且不写入 ----------

    @Test
    void loadValidationFailures_writeNothingAndFailedKeyReusable() throws Exception {
        String pallet = "PL-VAL-" + unique();
        String pallet2 = "PL-VAL2-" + unique();
        String b1 = "BK-PV1-" + unique();
        String b2 = "BK-PV2-" + unique();
        String b3 = "BK-PV3-" + unique();
        createPallet(pallet, 0, 201);
        createPallet(pallet2, 0, 201);
        createBatch(b1, 201);
        createBatch(b2, 201);
        createBatch(b3, 201);

        // 隔板数量不足：0 块隔板只有 1 个分隔位，两类两位需要 1 块
        MvcResult insufficient = load(pallet, "CK-LF-1", 422,
                item(b1, "A", 1), item(b2, "B", 2));
        String message = read(insufficient).path("message").asText();
        assertTrue(message.contains("隔板数量不足"), message);
        assertTrue(message.contains("实际 0") && message.contains("要求 1"), message);

        // 请求内同一分隔位被两个类别占用
        load(pallet, "CK-LF-2", 422, item(b1, "A", 1), item(b2, "B", 1));

        // 失败不写任何托盘关系
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_load WHERE pallet_key = ?", Integer.class, pallet));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_occupancy",
                Integer.class));

        // 失败不占键：同 commandKey 修正参数后成功
        load(pallet, "CK-LF-1", 201, item(b1, "A", 1), item(b2, "A", 1));

        // 批次已被其他托盘占用
        load(pallet2, "CK-LF-3", 422, item(b1, "A", 1));

        // 与在托装载类别冲突：分隔位 1 已被类别 A 占用
        load(pallet, "CK-LF-4", 422, item(b3, "B", 1));

        // 批次不存在 404；托盘不存在 404；请求内批次重复 422
        load(pallet, "CK-LF-5", 404, item("BK-NONE-" + unique(), "A", 1));
        load("PL-NONE-" + unique(), "CK-LF-6", 404, item(b3, "A", 1));
        load(pallet, "CK-LF-7", 422, item(b3, "A", 1), item(b3, "A", 1));

        // 最终只有 b1、b2 在托
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_load WHERE pallet_key = ? AND status = 'LOADED'",
                Integer.class, pallet));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM batch_occupancy",
                Integer.class));
    }

    @Test
    void loadBatchStateLegality_recalledReleasedRejectedRejected422() throws Exception {
        String pallet = "PL-ST-" + unique();
        String recalled = "BK-PS-RC-" + unique();
        String released = "BK-PS-RL-" + unique();
        String rejected = "BK-PS-RJ-" + unique();
        String ok = "BK-PS-OK-" + unique();
        createPallet(pallet, 2, 201);

        releaseBatch(recalled);
        recallBatch(recalled, "CK-PS-R1", 201);
        releaseBatch(released);
        rejectBatch(rejected);
        createBatch(ok, 201);

        load(pallet, "CK-LS-1", 422, item(recalled, "A", 1));
        load(pallet, "CK-LS-2", 422, item(released, "A", 1));
        load(pallet, "CK-LS-3", 422, item(rejected, "A", 1));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_load WHERE pallet_key = ?", Integer.class, pallet));
        load(pallet, "CK-LS-4", 201, item(ok, "A", 1));
    }

    // ---------- 幂等 ----------

    @Test
    void commandKey_sameParamsReplay_changedParamsConflict() throws Exception {
        String pallet = "PL-IDEM-" + unique();
        String b1 = "BK-PI1-" + unique();
        String b2 = "BK-PI2-" + unique();
        createBatch(b1, 201);
        createBatch(b2, 201);

        String createBody = objectMapper.writeValueAsString(
                new PalletCmd("CK-I-1", pallet, 1));
        MvcResult first = postRaw("/api/pallets", createBody, 201);
        MvcResult replay = postRaw("/api/pallets", createBody, 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 同键改参 → 409
        postRaw("/api/pallets", objectMapper.writeValueAsString(
                new PalletCmd("CK-I-1", pallet, 2)), 409);

        String loadBody = objectMapper.writeValueAsString(
                new LoadCmd("CK-I-2", List.of(item(b1, "A", 1))));
        MvcResult loadFirst = postRaw("/api/pallets/" + pallet + "/loads", loadBody, 201);
        MvcResult loadReplay = postRaw("/api/pallets/" + pallet + "/loads", loadBody, 201);
        assertEquals(loadFirst.getResponse().getContentAsString(),
                loadReplay.getResponse().getContentAsString());
        // 同键换批次 → 409
        postRaw("/api/pallets/" + pallet + "/loads", objectMapper.writeValueAsString(
                new LoadCmd("CK-I-2", List.of(item(b2, "A", 1)))), 409);
        // 重放不新增记录
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_load WHERE pallet_key = ?", Integer.class, pallet));

        String shipBody = "{\"commandKey\":\"CK-I-3\"}";
        MvcResult shipFirst = postRaw("/api/pallets/" + pallet + "/shipment", shipBody, 201);
        MvcResult shipReplay = postRaw("/api/pallets/" + pallet + "/shipment", shipBody, 201);
        assertEquals(shipFirst.getResponse().getContentAsString(),
                shipReplay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_shipment WHERE pallet_key = ?", Integer.class,
                pallet));
    }

    // ---------- 血缘召回 → 待复核 ----------

    @Test
    void recallMarksPalletBatchesPendingReview_andShipRevalidatesFinalState() throws Exception {
        String pallet = "PL-RC-" + unique();
        String b1 = "BK-PR1-" + unique();
        String b2 = "BK-PR2-" + unique();
        createPallet(pallet, 1, 201);
        createBatch(b1, 201);
        createBatch(b2, 201);
        load(pallet, "CK-LR-1", 201, item(b1, "A", 1), item(b2, "B", 2));

        // b1 在托期间完成放行再召回：血缘召回命中托盘批次
        completeRelease(b1);
        recallBatch(b1, "CK-PR-R1", 201);

        // 该托盘全部未装运批次标记待复核
        JsonNode diag = getJson("/api/pallets/" + pallet + "/diagnostics");
        assertEquals(2, diag.path("pendingReviewCount").asInt());
        JsonNode detail = getJson("/api/pallets/" + pallet);
        for (JsonNode load : detail.path("currentLoads")) {
            assertTrue(load.path("pendingReview").asBoolean());
            assertFalse(load.path("reviewMarkedAt").isNull());
        }

        // 装运前核对整批最终状态：b1 已召回 → 422，不固化任何快照
        ship(pallet, "CK-SR-1", 422);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_shipment WHERE pallet_key = ?", Integer.class,
                pallet));

        // 解除 b1 后装运成功；待复核标记随历史保留
        unload(pallet, "CK-UR-1", List.of(b1), 201);
        ship(pallet, "CK-SR-2", 201);
        JsonNode history = getJson("/api/pallets/" + pallet + "/history");
        JsonNode b1Event = null;
        for (JsonNode e : history.path("events")) {
            if (e.path("batchKey").asText().equals(b1)) {
                b1Event = e;
            }
        }
        assertNotNull(b1Event);
        assertEquals("UNLOADED", b1Event.path("status").asText());
        assertTrue(b1Event.path("pendingReview").asBoolean());
    }

    @Test
    void lineageRecall_marksDescendantPalletPendingReview_andBlocksNewLoad() throws Exception {
        String pallet = "PL-LIN-" + unique();
        String parent = "BK-PL-P-" + unique();
        String child1 = "BK-PL-C1-" + unique();
        String child2 = "BK-PL-C2-" + unique();
        createPallet(pallet, 0, 201);
        releaseBatch(parent);
        splitBatch(parent, child1, child2);

        // 子批装载后，召回父批 → 血缘命中 → 托盘待复核
        load(pallet, "CK-LL-1", 201, item(child1, "A", 1));
        recallBatch(parent, "CK-PL-R1", 201);
        JsonNode diag = getJson("/api/pallets/" + pallet + "/diagnostics");
        assertEquals(1, diag.path("pendingReviewCount").asInt());

        // 召回祖先的后代禁止新装载与装运
        load(pallet, "CK-LL-2", 422, item(child2, "A", 1));
        ship(pallet, "CK-SL-1", 422);
    }

    // ---------- 历史区间：左闭右开 ----------

    @Test
    void historyTimeRange_leftClosedRightOpen() throws Exception {
        String pallet = "PL-TR-" + unique();
        String b1 = "BK-PT1-" + unique();
        createPallet(pallet, 0, 201);
        createBatch(b1, 201);
        MvcResult loadResult = load(pallet, "CK-LT-1", 201, item(b1, "A", 1));
        String loadedAt = read(loadResult).path("items").get(0).path("loadedAt").asText();

        // [loadedAt, ∞) 包含事件
        JsonNode fromOnly = getJsonWithParams("/api/pallets/" + pallet + "/history",
                "from", loadedAt, null, null);
        assertEquals(1, fromOnly.path("events").size());
        // (∞, loadedAt) 右开：不包含端点事件
        JsonNode toOnly = getJsonWithParams("/api/pallets/" + pallet + "/history",
                null, null, "to", loadedAt);
        assertEquals(0, toOnly.path("events").size());
        // [t, t) 为空
        JsonNode empty = getJsonWithParams("/api/pallets/" + pallet + "/history",
                "from", loadedAt, "to", loadedAt);
        assertEquals(0, empty.path("events").size());
        // 无参数返回全部，from/to 为 null
        JsonNode all = getJson("/api/pallets/" + pallet + "/history");
        assertEquals(1, all.path("events").size());
        assertTrue(all.path("from").isNull());
        assertTrue(all.path("to").isNull());
        // 非法时间 → 400
        mockMvc.perform(get("/api/pallets/" + pallet + "/history").param("from", "not-a-time"))
                .andExpect(status().isBadRequest());
    }

    // ---------- 持久化 ----------

    @Test
    void dataIsPersistedInTables() throws Exception {
        String pallet = "PL-DB-" + unique();
        String b1 = "BK-PD1-" + unique();
        createPallet(pallet, 1, 201);
        createBatch(b1, 201);
        load(pallet, "CK-LD-1", 201, item(b1, "A", 1));
        ship(pallet, "CK-SD-1", 201);
        dispose(pallet, "CK-XD-1", "类别错误", 201);

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet WHERE pallet_key = ? AND status = 'DISPOSAL'",
                Integer.class, pallet));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_load WHERE pallet_key = ? AND status = 'SHIPPED'",
                Integer.class, pallet));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_occupancy WHERE batch_key = ?", Integer.class, b1));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_shipment WHERE pallet_key = ? AND divider_version = 1",
                Integer.class, pallet));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_disposal WHERE pallet_key = ?", Integer.class,
                pallet));
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_key IN"
                        + " ('CK-LD-1','CK-SD-1','CK-XD-1') OR command_type = 'CREATE_PALLET'",
                Integer.class));
    }

    // ---------- helpers ----------

    private record PalletCmd(String commandKey, String palletKey, int dividerCount) {
    }

    private record DividerCmd(String commandKey, int dividerCount) {
    }

    private record LoadCmd(String commandKey, List<Item> items) {
    }

    private record Item(String batchKey, String category, int compartmentNo) {
    }

    private record UnloadCmd(String commandKey, List<String> batchKeys) {
    }

    private record DisposalCmd(String commandKey, String reason) {
    }

    private record CreateBatchCmd(String commandKey, String batchKey, String productCode,
                                  String batchNo, Instant producedAt,
                                  List<String> requiredTests) {
    }

    private Item item(String batchKey, String category, int compartmentNo) {
        return new Item(batchKey, category, compartmentNo);
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult postRaw(String url, String body, int expected) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private JsonNode getJson(String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private JsonNode getJsonWithParams(String url, String name1, String value1,
                                       String name2, String value2) throws Exception {
        var request = get(url);
        if (name1 != null) {
            request = request.param(name1, value1);
        }
        if (name2 != null) {
            request = request.param(name2, value2);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private List<Integer> toIntList(JsonNode array) {
        List<Integer> values = new java.util.ArrayList<>();
        array.forEach(n -> values.add(n.asInt()));
        return values;
    }

    private void createPallet(String palletKey, int dividers, int expected) throws Exception {
        postRaw("/api/pallets", objectMapper.writeValueAsString(
                new PalletCmd("CK-P-" + unique(), palletKey, dividers)), expected);
    }

    private void updateDividers(String palletKey, String commandKey, int dividers, int expected)
            throws Exception {
        postRaw("/api/pallets/" + palletKey + "/dividers",
                objectMapper.writeValueAsString(new DividerCmd(commandKey, dividers)), expected);
    }

    private MvcResult load(String palletKey, String commandKey, int expected, Item... items)
            throws Exception {
        return postRaw("/api/pallets/" + palletKey + "/loads",
                objectMapper.writeValueAsString(new LoadCmd(commandKey, List.of(items))), expected);
    }

    private void unload(String palletKey, String commandKey, List<String> batchKeys, int expected)
            throws Exception {
        postRaw("/api/pallets/" + palletKey + "/unloads",
                objectMapper.writeValueAsString(new UnloadCmd(commandKey, batchKeys)), expected);
    }

    private MvcResult ship(String palletKey, String commandKey, int expected) throws Exception {
        return postRaw("/api/pallets/" + palletKey + "/shipment",
                "{\"commandKey\":\"" + commandKey + "\"}", expected);
    }

    private void dispose(String palletKey, String commandKey, String reason, int expected)
            throws Exception {
        postRaw("/api/pallets/" + palletKey + "/disposal",
                objectMapper.writeValueAsString(new DisposalCmd(commandKey, reason)), expected);
    }

    private void createBatch(String batchKey, int expected) throws Exception {
        postRaw("/api/batches", objectMapper.writeValueAsString(
                new CreateBatchCmd("CK-B-" + unique(), batchKey, "PROD-1", "LOT-1",
                        Instant.parse("2026-01-02T03:04:05Z"), List.of("t1"))), expected);
    }

    /**
     * 批次走完整检验与双批准进入 RELEASED。
     */
    private void releaseBatch(String batchKey) throws Exception {
        createBatch(batchKey, 201);
        completeRelease(batchKey);
    }

    /**
     * 对已创建批次提交全部检验并通过双批准，使其进入 RELEASED。
     */
    private void completeRelease(String batchKey) throws Exception {
        postRaw("/api/batches/" + batchKey + "/tests",
                "{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-1\","
                        + "\"testItem\":\"t1\",\"result\":\"PASS\",\"inspector\":\"insp-1\"}",
                201);
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-AQ-" + unique() + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-AO-" + unique() + "\"}"))
                .andExpect(status().isCreated());
    }

    private void rejectBatch(String batchKey) throws Exception {
        createBatch(batchKey, 201);
        postRaw("/api/batches/" + batchKey + "/tests",
                "{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-1\","
                        + "\"testItem\":\"t1\",\"result\":\"FAIL\",\"inspector\":\"insp-1\"}",
                201);
    }

    private void recallBatch(String batchKey, String commandKey, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"r\"}"))
                .andExpect(status().is(expected));
    }

    private void splitBatch(String parentKey, String child1, String child2) throws Exception {
        postRaw("/api/batches/" + parentKey + "/split",
                "{\"commandKey\":\"CK-SP-" + unique() + "\",\"children\":["
                        + "{\"batchKey\":\"" + child1 + "\",\"batchNo\":\"N1\"},"
                        + "{\"batchKey\":\"" + child2 + "\",\"batchNo\":\"N2\"}]}",
                201);
    }
}

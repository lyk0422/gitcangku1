package com.example.starter.pallet;

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
 * 批次托盘隔离端到端测试：主流程、422/409/404 失败分支、事务原子性、
 * commandKey 幂等、并发占用/装运裁决、召回联动待复核与装运快照不追溯。
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
        jdbc.update("DELETE FROM pallet_shipment");
        jdbc.update("DELETE FROM pallet_active_load");
        jdbc.update("DELETE FROM pallet_load");
        jdbc.update("DELETE FROM pallet_divider_change");
        jdbc.update("DELETE FROM pallet");
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
    void happyFlow_loadShipDisposalAndSnapshotFrozen() throws Exception {
        String pallet = "PL-HAPPY-" + unique();
        createPallet(pallet, 2, "CK-PC-1", 201);

        String b1 = "BK-H1-" + unique();
        String b2 = "BK-H2-" + unique();
        String b3 = "BK-H3-" + unique();
        createBatch(b1);
        createBatch(b2);
        createBatch(b3);

        // 3 个类别、3 个分隔位、2 个隔板：满足 类别数-1 <= 隔板数
        MvcResult loadResult = load(pallet, "CK-LD-1", 201,
                item(b3, "C", 3), item(b1, "A", 1), item(b2, "B", 2));
        JsonNode loadBody = readJson(loadResult);
        // 响应按 batchKey 规范排序
        assertEquals(b1, loadBody.path("items").get(0).path("batchKey").asText());
        assertEquals(1, loadBody.path("items").get(0).path("seq").asInt());
        assertEquals(b2, loadBody.path("items").get(1).path("batchKey").asText());
        assertEquals(b3, loadBody.path("items").get(2).path("batchKey").asText());
        assertEquals(1, loadBody.path("dividerVersion").asInt());

        JsonNode detail = detail(pallet);
        assertEquals("OPEN", detail.path("status").asText());
        assertEquals(3, detail.path("activeLoads").size());
        assertFalse(detail.path("activeLoads").get(0).path("reviewFlag").asBoolean());
        assertTrue(detail.path("activeLoads").get(0).path("reviewMarkedAt").isNull());

        // 诊断：要求值/实际值/差额齐全
        JsonNode diag = diagnostics(pallet);
        assertEquals(3, diag.path("distinctCategories").asInt());
        assertEquals(2, diag.path("requiredDividers").asInt());
        assertEquals(2, diag.path("actualDividers").asInt());
        assertEquals(0, diag.path("dividerDeficit").asInt());
        assertEquals(3, diag.path("availableCompartments").asInt());
        assertTrue(diag.path("segregationValid").asBoolean());

        // 装运放行：固化快照
        MvcResult shipResult = ship(pallet, "shipper-1", "CK-SH-1", 201);
        JsonNode shipBody = readJson(shipResult);
        assertEquals(2, shipBody.path("dividerCount").asInt());
        assertEquals(1, shipBody.path("dividerVersion").asInt());
        assertEquals(3, shipBody.path("items").size());
        assertEquals("SHIPPED", detail(pallet).path("status").asText());
        assertEquals(0, detail(pallet).path("activeLoads").size());
        assertEquals("shipper-1", detail(pallet).path("shippedBy").asText());

        // 装运后修改隔板：允许，版本 +1，但快照不追溯
        configureDivider(pallet, 5, "CK-DV-2", 200);
        JsonNode history = history(pallet);
        assertEquals(2, history.path("dividerVersion").asInt(), "当前配置已变更");
        assertEquals(5, history.path("dividerCount").asInt());
        assertEquals(1, history.path("shipment").get(0).path("dividerVersion").asInt(),
                "装运快照保留放行时点隔板版本");
        assertEquals(2, history.path("shipment").get(0).path("dividerCount").asInt());
        assertEquals(1, history.path("dividerChanges").size());
        assertEquals(3, history.path("loads").size());
        assertEquals("SHIPPED", history.path("loads").get(0).path("status").asText());

        // 已装运发现类别错误 → 隔离处置；装载记录保留不删除
        dispose(pallet, "qa-9", "CK-QD-1", "类别标识错误", 201);
        assertEquals("QUARANTINE_DISPOSAL", detail(pallet).path("status").asText());
        JsonNode historyAfter = history(pallet);
        assertEquals(3, historyAfter.path("loads").size(), "处置不得删除装载记录");
        assertEquals(3, historyAfter.path("shipment").size());
        assertEquals("类别标识错误", historyAfter.path("disposalReason").asText());
    }

    // ---------- 装载失败分支：422 且不写入任何托盘关系 ----------

    @Test
    void loadSegregationFailures_return422AndWriteNothing() throws Exception {
        String pallet = "PL-SEG-" + unique();
        createPallet(pallet, 1, "CK-PC", 201);
        String b1 = "BK-S1-" + unique();
        String b2 = "BK-S2-" + unique();
        String b3 = "BK-S3-" + unique();
        createBatch(b1);
        createBatch(b2);
        createBatch(b3);

        // 隔板数量不足：3 类需 2 个隔板，实际 1 个，差额 1
        MvcResult insufficient = load(pallet, "CK-L1", 422,
                item(b1, "A", 1), item(b2, "B", 2), item(b3, "C", 3));
        String insufficientBody = insufficient.getResponse().getContentAsString();
        assertTrue(insufficientBody.contains("INSUFFICIENT_DIVIDERS"), insufficientBody);
        assertTrue(insufficientBody.contains("需要 2 个隔板"), insufficientBody);
        assertTrue(insufficientBody.contains("实际 1 个"), insufficientBody);
        assertTrue(insufficientBody.contains("差额 1"), insufficientBody);

        // 分隔位重复
        MvcResult duplicate = load(pallet, "CK-L2", 422,
                item(b1, "A", 1), item(b2, "A", 1));
        assertTrue(duplicate.getResponse().getContentAsString().contains("COMPARTMENT_DUPLICATE"));

        // 分隔位超出范围：1 个隔板仅 2 个分隔位
        MvcResult outOfRange = load(pallet, "CK-L3", 422, item(b1, "A", 3));
        assertTrue(outOfRange.getResponse().getContentAsString().contains("COMPARTMENT_OUT_OF_RANGE"));

        // 类别冲突：未配置隔板的托盘不能共存两个类别
        String pallet0 = "PL-SEG0-" + unique();
        createPallet(pallet0, 0, "CK-PC0", 201);
        MvcResult conflict = load(pallet0, "CK-L4", 422,
                item(b1, "A", 1), item(b2, "B", 2));
        assertTrue(conflict.getResponse().getContentAsString().contains("CATEGORY_CONFLICT"));

        // 未知批次 → 404
        load(pallet, "CK-L5", 404, item("BK-NO-SUCH", "A", 1));

        // 全部失败：不写入任何托盘关系
        assertEquals(0, countRows("pallet_load"));
        assertEquals(0, countRows("pallet_active_load"));
        assertEquals(0, detail(pallet).path("activeLoads").size());
        assertEquals(0, detail(pallet0).path("activeLoads").size());
    }

    @Test
    void loadBatchStateChecks_recalledShippedOccupied() throws Exception {
        String pallet = "PL-ST-" + unique();
        createPallet(pallet, 2, "CK-PC", 201);

        // 已召回批次 → 422 BATCH_RECALLED
        String recalled = "BK-RC-" + unique();
        createBatch(recalled);
        releaseBatch(recalled);
        recallBatch(recalled, "CK-RC-1", 201);
        MvcResult recalledResult = load(pallet, "CK-L1", 422, item(recalled, "A", 1));
        assertTrue(recalledResult.getResponse().getContentAsString().contains("BATCH_RECALLED"));

        // 已被其他托盘占用 → 422 BATCH_OCCUPIED
        String occupied = "BK-OC-" + unique();
        createBatch(occupied);
        load(pallet, "CK-L2", 201, item(occupied, "A", 1));
        String pallet2 = "PL-ST2-" + unique();
        createPallet(pallet2, 2, "CK-PC2", 201);
        MvcResult occupiedResult = load(pallet2, "CK-L3", 422, item(occupied, "A", 1));
        assertTrue(occupiedResult.getResponse().getContentAsString().contains("BATCH_OCCUPIED"));

        // 已装运放行批次 → 422 BATCH_ALREADY_SHIPPED
        ship(pallet, "shipper", "CK-SH-1", 201);
        MvcResult shippedResult = load(pallet2, "CK-L4", 422, item(occupied, "A", 1));
        assertTrue(shippedResult.getResponse().getContentAsString().contains("BATCH_ALREADY_SHIPPED"));

        assertEquals(0, detail(pallet2).path("activeLoads").size());
    }

    @Test
    void multiItemLoad_unknownBatchRollsBackEverything() throws Exception {
        String pallet = "PL-ATOMIC-" + unique();
        createPallet(pallet, 2, "CK-PC", 201);
        String b1 = "BK-A1-" + unique();
        String b2 = "BK-A2-" + unique();
        createBatch(b1);
        createBatch(b2);

        // 第三项批次不存在 → 404，整批不写入
        load(pallet, "CK-L1", 404, item(b1, "A", 1), item(b2, "B", 2), item("BK-GHOST", "C", 3));
        assertEquals(0, countRows("pallet_load"));
        assertEquals(0, countRows("pallet_active_load"));

        // 第三项类别冲突 → 422，整批不写入
        String pallet0 = "PL-ATOMIC0-" + unique();
        createPallet(pallet0, 0, "CK-PC0", 201);
        load(pallet0, "CK-L2", 422, item(b1, "A", 1), item(b2, "B", 1));
        assertEquals(0, countRows("pallet_load"));
        assertEquals(0, countRows("pallet_active_load"));
    }

    // ---------- 请求形状 400 ----------

    @Test
    void invalidRequests_return400() throws Exception {
        String pallet = "PL-BAD-" + unique();
        createPallet(pallet, 1, "CK-PC", 201);

        // items 为空
        mockMvc.perform(post("/api/pallets/" + pallet + "/loads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"c\",\"items\":[]}"))
                .andExpect(status().isBadRequest());
        // compartment 为 0
        mockMvc.perform(post("/api/pallets/" + pallet + "/loads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"c\",\"items\":[{\"batchKey\":\"b\","
                                + "\"category\":\"A\",\"compartment\":0}]}"))
                .andExpect(status().isBadRequest());
        // category 为空
        mockMvc.perform(post("/api/pallets/" + pallet + "/loads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"c\",\"items\":[{\"batchKey\":\"b\","
                                + "\"category\":\"\",\"compartment\":1}]}"))
                .andExpect(status().isBadRequest());
        // dividerCount 超上限
        mockMvc.perform(post("/api/pallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"c\",\"palletKey\":\"PL-X\",\"dividerCount\":9}"))
                .andExpect(status().isBadRequest());
        // 请求内重复批次
        String b1 = "BK-DUP-" + unique();
        createBatch(b1);
        mockMvc.perform(post("/api/pallets/" + pallet + "/loads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"c\",\"items\":["
                                + "{\"batchKey\":\"" + b1 + "\",\"category\":\"A\",\"compartment\":1},"
                                + "{\"batchKey\":\"" + b1 + "\",\"category\":\"A\",\"compartment\":2}]}"))
                .andExpect(status().isBadRequest());
        // 装运放行缺 X-Actor-Id
        mockMvc.perform(post("/api/pallets/" + pallet + "/shipment-release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"c\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownPallet_returns404() throws Exception {
        mockMvc.perform(get("/api/pallets/NO-SUCH-PALLET")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/pallets/NO-SUCH-PALLET/history")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/pallets/NO-SUCH-PALLET/diagnostics")).andExpect(status().isNotFound());
        load("NO-SUCH-PALLET", "CK-L", 404, item("BK-X", "A", 1));
    }

    // ---------- 幂等 ----------

    @Test
    void commandKey_replayConflictAndFailureNotConsuming() throws Exception {
        String pallet = "PL-IDEM-" + unique();
        createPallet(pallet, 2, "CK-PC", 201);
        String b1 = "BK-I1-" + unique();
        String b2 = "BK-I2-" + unique();
        createBatch(b1);
        createBatch(b2);

        // 失败不占键：先 422，再用同键成功
        load(pallet, "CK-L1", 422, item(b1, "A", 1), item(b2, "B", 1));
        load(pallet, "CK-L1", 201, item(b1, "A", 1));

        // 同键同参重放首次响应
        MvcResult replay = load(pallet, "CK-L1", 201, item(b1, "A", 1));
        assertEquals(1, countRows("pallet_load"), "重放不得新增装载记录");
        assertEquals(b1, readJson(replay).path("items").get(0).path("batchKey").asText());

        // 同键异参 → 409
        load(pallet, "CK-L1", 409, item(b2, "A", 2));

        // 创建托盘：同键同参重放、异参 409、业务键重复 409
        String pallet2 = "PL-IDEM2-" + unique();
        String body = "{\"commandKey\":\"CK-PC-DUP\",\"palletKey\":\"" + pallet2
                + "\",\"dividerCount\":2}";
        MvcResult created = mockMvc.perform(post("/api/pallets")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replayed = mockMvc.perform(post("/api/pallets")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(created.getResponse().getContentAsString(),
                replayed.getResponse().getContentAsString());
        mockMvc.perform(post("/api/pallets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-PC-DUP\",\"palletKey\":\"" + pallet2
                                + "\",\"dividerCount\":3}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/pallets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-PC-NEW\",\"palletKey\":\"" + pallet2
                                + "\",\"dividerCount\":1}"))
                .andExpect(status().isConflict());
    }

    // ---------- 解除装载 ----------

    @Test
    void unload_releasesCompartmentKeepsHistoryAndAllowsReload() throws Exception {
        String pallet = "PL-UL-" + unique();
        createPallet(pallet, 2, "CK-PC", 201);
        String b1 = "BK-U1-" + unique();
        String b2 = "BK-U2-" + unique();
        createBatch(b1);
        createBatch(b2);
        load(pallet, "CK-L1", 201, item(b1, "A", 1), item(b2, "A", 2));

        // 解除 b1：释放分隔位 1，历史保留
        MvcResult unloadResult = unload(pallet, "CK-UL-1", 200, b1);
        JsonNode unloadBody = readJson(unloadResult);
        assertEquals(1, unloadBody.path("released").size());
        assertEquals(b1, unloadBody.path("released").get(0).path("batchKey").asText());
        assertEquals(1, unloadBody.path("released").get(0).path("compartment").asInt());

        JsonNode detail = detail(pallet);
        assertEquals(1, detail.path("activeLoads").size());
        assertEquals(b2, detail.path("activeLoads").get(0).path("batchKey").asText());

        JsonNode history = history(pallet);
        assertEquals(2, history.path("loads").size(), "解除装载保留历史");
        JsonNode unloaded = history.path("loads").get(0);
        assertEquals("UNLOADED", unloaded.path("status").asText());
        assertFalse(unloaded.path("unloadedAt").isNull());
        assertTrue(unloaded.path("shippedAt").isNull());

        // 解除后的批次可装载到其他托盘
        String pallet2 = "PL-UL2-" + unique();
        createPallet(pallet2, 1, "CK-PC2", 201);
        load(pallet2, "CK-L2", 201, item(b1, "A", 1));

        // 未在本托盘在托 → 422
        unload(pallet, "CK-UL-2", 422, b1);
        // 重复解除同一批（已解除）→ 422
        unload(pallet2, "CK-UL-3", 200, b1);
        unload(pallet2, "CK-UL-4", 422, b1);
    }

    @Test
    void unloadOnShippedPallet_returns409() throws Exception {
        String pallet = "PL-ULSH-" + unique();
        createPallet(pallet, 1, "CK-PC", 201);
        String b1 = "BK-US-" + unique();
        createBatch(b1);
        load(pallet, "CK-L1", 201, item(b1, "A", 1));
        ship(pallet, "shipper", "CK-SH-1", 201);
        unload(pallet, "CK-UL-1", 409, b1);
        // 历史未被改写
        assertEquals("SHIPPED", history(pallet).path("loads").get(0).path("status").asText());
    }

    // ---------- 装运放行 ----------

    @Test
    void shipmentRelease_emptyDoubleAndReplay() throws Exception {
        String pallet = "PL-SH-" + unique();
        createPallet(pallet, 1, "CK-PC", 201);

        // 空托盘 → 422
        ship(pallet, "shipper", "CK-SH-0", 422);

        String b1 = "BK-SH-" + unique();
        createBatch(b1);
        load(pallet, "CK-L1", 201, item(b1, "A", 1));
        MvcResult first = ship(pallet, "shipper", "CK-SH-1", 201);

        // 同键重放返回首次响应
        MvcResult replay = ship(pallet, "shipper", "CK-SH-1", 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, countRows("pallet_shipment"));

        // 已装运再次放行（不同键）→ 409；已装运再装载 → 409
        ship(pallet, "shipper", "CK-SH-2", 409);
        load(pallet, "CK-L2", 409, item(b1, "A", 1));
    }

    // ---------- 隔离处置 ----------

    @Test
    void quarantineDisposal_onlyForShippedPallet() throws Exception {
        String pallet = "PL-QD-" + unique();
        createPallet(pallet, 1, "CK-PC", 201);
        String b1 = "BK-QD-" + unique();
        createBatch(b1);
        load(pallet, "CK-L1", 201, item(b1, "A", 1));

        // 未装运不能处置
        dispose(pallet, "qa", "CK-QD-0", "过早处置", 409);

        ship(pallet, "shipper", "CK-SH-1", 201);
        dispose(pallet, "qa", "CK-QD-1", "类别错误", 201);
        assertEquals("QUARANTINE_DISPOSAL", detail(pallet).path("status").asText());

        // 处置后不能再装运、不能再处置、不能装载
        ship(pallet, "shipper", "CK-SH-2", 409);
        dispose(pallet, "qa", "CK-QD-2", "重复处置", 409);
        load(pallet, "CK-L2", 409, item(b1, "A", 1));

        // 装载与装运记录完整保留
        JsonNode history = history(pallet);
        assertEquals(1, history.path("loads").size());
        assertEquals(1, history.path("shipment").size());
    }

    // ---------- 隔板变更 ----------

    @Test
    void dividerReconfigure_blockedWithActiveLoads() throws Exception {
        String pallet = "PL-DV-" + unique();
        createPallet(pallet, 1, "CK-PC", 201);
        String b1 = "BK-DV-" + unique();
        createBatch(b1);
        load(pallet, "CK-L1", 201, item(b1, "A", 1));

        // 在托期间禁止变更隔板
        configureDivider(pallet, 3, "CK-DV-1", 409);
        assertEquals(1, detail(pallet).path("dividerVersion").asInt());

        // 解除后可变更，版本 +1 并记录历史
        unload(pallet, "CK-UL-1", 200, b1);
        configureDivider(pallet, 3, "CK-DV-2", 200);
        JsonNode detail = detail(pallet);
        assertEquals(3, detail.path("dividerCount").asInt());
        assertEquals(2, detail.path("dividerVersion").asInt());
        JsonNode changes = history(pallet).path("dividerChanges");
        assertEquals(1, changes.size());
        assertEquals(1, changes.get(0).path("oldDividerCount").asInt());
        assertEquals(3, changes.get(0).path("newDividerCount").asInt());
    }

    // ---------- 召回联动 ----------

    @Test
    void recall_marksAllUnshippedLoadsOnPallet() throws Exception {
        String pallet = "PL-RV-" + unique();
        createPallet(pallet, 2, "CK-PC", 201);
        String b1 = "BK-R1-" + unique();
        String b2 = "BK-R2-" + unique();
        String b3 = "BK-R3-" + unique();
        createBatch(b1);
        createBatch(b2);
        createBatch(b3);
        releaseBatch(b1);
        load(pallet, "CK-L1", 201, item(b1, "A", 1), item(b2, "A", 2), item(b3, "A", 3));

        recallBatch(b1, "CK-RC-1", 201);

        JsonNode detail = detail(pallet);
        assertEquals(3, detail.path("activeLoads").size());
        for (JsonNode load : detail.path("activeLoads")) {
            assertTrue(load.path("reviewFlag").asBoolean(),
                    "召回命中托盘后所有未装运批次均待复核: " + load.path("batchKey").asText());
            assertFalse(load.path("reviewMarkedAt").isNull());
        }
        // 诊断查询同样可见待复核标记
        JsonNode diag = diagnostics(pallet);
        assertTrue(diag.path("loads").get(0).path("reviewFlag").asBoolean());
    }

    @Test
    void lineageRecall_marksPalletOfDescendant() throws Exception {
        String parent = "BK-PP-" + unique();
        createBatch(parent);
        releaseBatch(parent);
        String child1 = "BK-PC1-" + unique();
        String child2 = "BK-PC2-" + unique();
        splitBatch(parent, child1, child2);

        String pallet = "PL-LRV-" + unique();
        createPallet(pallet, 1, "CK-PC", 201);
        load(pallet, "CK-L1", 201, item(child1, "A", 1));

        // 召回父批（SPLIT 可召回）：血缘命中子批所在托盘
        recallBatch(parent, "CK-RC-1", 201);

        JsonNode detail = detail(pallet);
        assertEquals(1, detail.path("activeLoads").size());
        assertTrue(detail.path("activeLoads").get(0).path("reviewFlag").asBoolean(),
                "血缘召回命中后子批所在托盘批次应待复核");
    }

    @Test
    void loadAfterRecallHit_marksWholePallet() throws Exception {
        // 先召回的批次后代再入托：装载时维持不变式，整托标记待复核
        String parent = "BK-PH-" + unique();
        createBatch(parent);
        releaseBatch(parent);
        String child1 = "BK-CH1-" + unique();
        String child2 = "BK-CH2-" + unique();
        splitBatch(parent, child1, child2);
        recallBatch(parent, "CK-RC-1", 201);

        String pallet = "PL-LAH-" + unique();
        createPallet(pallet, 1, "CK-PC", 201);
        String other = "BK-OTH-" + unique();
        createBatch(other);
        load(pallet, "CK-L1", 201, item(child1, "A", 1), item(other, "A", 2));

        JsonNode detail = detail(pallet);
        for (JsonNode load : detail.path("activeLoads")) {
            assertTrue(load.path("reviewFlag").asBoolean(),
                    "装载命中召回血缘的批次时整托待复核: " + load.path("batchKey").asText());
        }
    }

    // ---------- 并发 ----------

    @Test
    void concurrentLoad_sameBatchTwoPallets_onlyOneSucceeds() throws Exception {
        String pallet1 = "PL-C1-" + unique();
        String pallet2 = "PL-C2-" + unique();
        createPallet(pallet1, 1, "CK-PC1", 201);
        createPallet(pallet2, 1, "CK-PC2", 201);
        String batch = "BK-C-" + unique();
        createBatch(batch);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/pallets/" + pallet1 + "/loads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loadBody("CK-C1", item(batch, "A", 1)))),
                () -> callStatus(post("/api/pallets/" + pallet2 + "/loads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loadBody("CK-C2", item(batch, "A", 1))))
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
        assertEquals(1, created, "同一批次并发装载只在一个托盘成功");
        assertEquals(1, rejected);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_active_load WHERE batch_key = ?",
                Integer.class, batch));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_load WHERE batch_key = ? AND status = 'ACTIVE'",
                Integer.class, batch));
    }

    @Test
    void concurrentShipAndUnload_exactlyOneWins() throws Exception {
        String pallet = "PL-CS-" + unique();
        createPallet(pallet, 1, "CK-PC", 201);
        String batch = "BK-CS-" + unique();
        createBatch(batch);
        load(pallet, "CK-L1", 201, item(batch, "A", 1));

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/pallets/" + pallet + "/shipment-release")
                        .header("X-Actor-Id", "shipper")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-SH-1\"}")),
                () -> callStatus(post("/api/pallets/" + pallet + "/unload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-UL-1\",\"batchKeys\":[\"" + batch + "\"]}"))
        );

        int ship = results.get(0).get(30, TimeUnit.SECONDS);
        int unload = results.get(1).get(30, TimeUnit.SECONDS);
        if (ship == 201) {
            assertEquals(409, unload, "装运先提交则解除必须失败");
            assertEquals("SHIPPED", detail(pallet).path("status").asText());
            assertEquals(1, countRows("pallet_shipment"));
        } else {
            assertEquals(422, ship, "解除先提交则空托盘装运必须失败");
            assertEquals(200, unload);
            assertEquals("OPEN", detail(pallet).path("status").asText());
            assertEquals(0, countRows("pallet_shipment"));
        }
        // 终态一致：不存在半成品
        assertEquals(0, countRows("pallet_active_load"));
    }

    @Test
    void concurrentSameCommandKeyLoad_bothReplayFirstResult() throws Exception {
        String pallet = "PL-CK-" + unique();
        createPallet(pallet, 1, "CK-PC", 201);
        String batch = "BK-CK-" + unique();
        createBatch(batch);
        String body = loadBody("CK-LD-SAME", item(batch, "A", 1));

        List<Future<String>> results = runConcurrentForBody(
                () -> callBody(post("/api/pallets/" + pallet + "/loads")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callBody(post("/api/pallets/" + pallet + "/loads")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        String first = results.get(0).get(30, TimeUnit.SECONDS);
        String second = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(first, second, "同键同参并发重放均返回首次响应");
        assertEquals(1, countRows("pallet_load"));
        assertEquals(1, countRows("pallet_active_load"));
    }

    // ---------- 只读查询 ----------

    @Test
    void readsDoNotChangeState() throws Exception {
        String pallet = "PL-RO-" + unique();
        createPallet(pallet, 1, "CK-PC", 201);
        String batch = "BK-RO-" + unique();
        createBatch(batch);
        load(pallet, "CK-L1", 201, item(batch, "A", 1));
        int commandsBefore = countRows("command_log");

        String detail1 = getBody("/api/pallets/" + pallet);
        String detail2 = getBody("/api/pallets/" + pallet);
        assertEquals(detail1, detail2);
        String history1 = getBody("/api/pallets/" + pallet + "/history");
        String history2 = getBody("/api/pallets/" + pallet + "/history");
        assertEquals(history1, history2);
        String diag1 = getBody("/api/pallets/" + pallet + "/diagnostics");
        String diag2 = getBody("/api/pallets/" + pallet + "/diagnostics");
        assertEquals(diag1, diag2);

        assertEquals(commandsBefore, countRows("command_log"), "读取不得写入命令日志");
        assertEquals(1, countRows("pallet_load"));
        assertEquals(1, countRows("pallet_active_load"));
    }

    // ---------- helpers ----------

    private record ItemSpec(String batchKey, String category, int compartment) {
    }

    private ItemSpec item(String batchKey, String category, int compartment) {
        return new ItemSpec(batchKey, category, compartment);
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void createBatch(String batchKey) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-B-" + unique() + "\",\"batchKey\":\"" + batchKey
                                + "\",\"productCode\":\"PROD-1\",\"batchNo\":\"LOT-1\","
                                + "\"producedAt\":\"2026-01-02T03:04:05Z\",\"requiredTests\":[\"t1\"]}"))
                .andExpect(status().isCreated());
    }

    private void releaseBatch(String batchKey) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-1\","
                                + "\"testItem\":\"t1\",\"result\":\"PASS\",\"inspector\":\"insp-1\"}"))
                .andExpect(status().isCreated());
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

    private void recallBatch(String batchKey, String commandKey, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", "recall-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"质量异常\"}"))
                .andExpect(status().is(expected));
    }

    private void splitBatch(String parentKey, String child1, String child2) throws Exception {
        mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-SP-" + unique() + "\",\"children\":["
                                + "{\"batchKey\":\"" + child1 + "\",\"batchNo\":\"N1\"},"
                                + "{\"batchKey\":\"" + child2 + "\",\"batchNo\":\"N2\"}]}"))
                .andExpect(status().isCreated());
    }

    private void createPallet(String palletKey, int dividerCount, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/pallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"palletKey\":\"" + palletKey
                                + "\",\"dividerCount\":" + dividerCount + "}"))
                .andExpect(status().is(expected));
    }

    private void configureDivider(String palletKey, int dividerCount, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/pallets/" + palletKey + "/divider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"dividerCount\":"
                                + dividerCount + "}"))
                .andExpect(status().is(expected));
    }

    private String loadBody(String commandKey, ItemSpec... items) throws Exception {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"items\":[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"batchKey\":\"").append(items[i].batchKey())
                    .append("\",\"category\":\"").append(items[i].category())
                    .append("\",\"compartment\":").append(items[i].compartment()).append('}');
        }
        return sb.append("]}").toString();
    }

    private MvcResult load(String palletKey, String commandKey, int expected, ItemSpec... items)
            throws Exception {
        return mockMvc.perform(post("/api/pallets/" + palletKey + "/loads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loadBody(commandKey, items)))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult unload(String palletKey, String commandKey, int expected, String... batchKeys)
            throws Exception {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"batchKeys\":[");
        for (int i = 0; i < batchKeys.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('\"').append(batchKeys[i]).append('\"');
        }
        sb.append("]}");
        return mockMvc.perform(post("/api/pallets/" + palletKey + "/unload")
                        .contentType(MediaType.APPLICATION_JSON).content(sb.toString()))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult ship(String palletKey, String actor, String commandKey, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/pallets/" + palletKey + "/shipment-release")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private void dispose(String palletKey, String actor, String commandKey, String reason, int expected)
            throws Exception {
        mockMvc.perform(post("/api/pallets/" + palletKey + "/quarantine-disposal")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason
                                + "\"}"))
                .andExpect(status().is(expected));
    }

    private JsonNode detail(String palletKey) throws Exception {
        return objectMapper.readTree(getBody("/api/pallets/" + palletKey));
    }

    private JsonNode history(String palletKey) throws Exception {
        return objectMapper.readTree(getBody("/api/pallets/" + palletKey + "/history"));
    }

    private JsonNode diagnostics(String palletKey) throws Exception {
        return objectMapper.readTree(getBody("/api/pallets/" + palletKey + "/diagnostics"));
    }

    private String getBody(String url) throws Exception {
        return mockMvc.perform(get(url)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
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
            return mockMvc.perform(req).andReturn().getResponse().getContentAsString();
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
    private List<Future<String>> runConcurrentForBody(Callable<String>... tasks) throws Exception {
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

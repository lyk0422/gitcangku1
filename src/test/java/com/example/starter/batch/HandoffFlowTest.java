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
 * 跨厂隔离移交与召回屏障测试：完整接收、召回竞态、清单差异、取消、整体回滚与幂等边界。
 * 全部用例运行于真实 H2（MySQL 兼容模式）内存库，验证唯一约束、事务回滚与行锁并发。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HandoffFlowTest {

    private static final String PLANT_A = "PLANT-A";
    private static final String PLANT_B = "PLANT-B";
    private static final Instant ARRIVAL = Instant.parse("2026-02-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM handoff_item");
        jdbc.update("DELETE FROM handoff");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 完整主流程 ----------

    @Test
    void happyHandoff_createShipReceive_switchesPlantStatusAndVersion() throws Exception {
        String b1 = "BK-H1-" + unique();
        String b2 = "BK-H2-" + unique();
        createBatch(b1, PLANT_A, List.of("t1"));
        createBatch(b2, PLANT_A, List.of("t1"));
        submitTest(b1, "t1", "PASS", "insp-1", 201);
        String manifest = "MF-HAPPY-" + unique();

        // 创建：清单按 batchKey 字典序冻结（故意乱序提交）
        MvcResult created = createHandoff("RID-C1", manifest, PLANT_A, PLANT_B,
                List.of(item(b2, 0), item(b1, 0)), 201);
        JsonNode createBody = readBody(created);
        assertEquals("CREATED", createBody.path("status").asText());
        assertEquals(PLANT_A, createBody.path("sourcePlant").asText());
        assertEquals(PLANT_B, createBody.path("targetPlant").asText());
        List<String> sorted = b1.compareTo(b2) < 0 ? List.of(b1, b2) : List.of(b2, b1);
        assertEquals(sorted.get(0), createBody.path("items").get(0).path("batchKey").asText());
        assertEquals(sorted.get(1), createBody.path("items").get(1).path("batchKey").asText());
        assertEquals(1, createBody.path("items").get(0).path("seq").asInt());
        assertEquals(2, createBody.path("items").get(1).path("seq").asInt());

        // 发运：整单原子转 IN_TRANSIT，写入封签快照
        MvcResult shipped = ship(manifest, "RID-S1", PLANT_A,
                List.of(seal(b1, "SEAL-1"), seal(b2, "SEAL-2")), 200);
        assertEquals("SHIPPED", readBody(shipped).path("status").asText());
        assertEquals("IN_TRANSIT", currentStatus(b1));
        assertEquals("IN_TRANSIT", currentStatus(b2));
        assertFalse(availableKeys().contains(b1));
        assertFalse(availableKeys().contains(b2));
        // 在途批次禁止检验
        submitTest(b2, "t1", "PASS", "insp-2", 409);
        // 在途批次持有厂仍为源厂
        assertHolding(b1, PLANT_A, 0, "IN_TRANSIT");

        // 接收：整单切换持有厂、统一 QUARANTINED、版本各加一
        MvcResult received = receive(manifest, "RID-R1", PLANT_B, "receiver-1",
                List.of(seal(b2, "SEAL-2"), seal(b1, "SEAL-1")), 200);
        JsonNode receiveBody = readBody(received);
        assertEquals("RECEIVED", receiveBody.path("status").asText());
        assertEquals("receiver-1", receiveBody.path("receiver").asText());
        assertEquals("QUARANTINED", currentStatus(b1));
        assertEquals("QUARANTINED", currentStatus(b2));
        assertHolding(b1, PLANT_B, 1, "QUARANTINED");
        assertHolding(b2, PLANT_B, 1, "QUARANTINED");
        assertTrue(availableKeys().contains(b1));

        // 移交证据：不可变快照含源厂、目标厂、封签与接收版本
        JsonNode evidence = evidence(manifest);
        assertEquals("RECEIVED", evidence.path("status").asText());
        assertEquals(PLANT_A, evidence.path("sourcePlant").asText());
        assertEquals(PLANT_B, evidence.path("targetPlant").asText());
        for (JsonNode item : evidence.path("items")) {
            assertEquals(0, item.path("expectedVersion").asInt());
            assertEquals(1, item.path("receivedVersion").asInt());
            assertEquals("PENDING_RELEASE".equals(item.path("preStatus").asText())
                    || "QUARANTINED".equals(item.path("preStatus").asText()), true);
            assertTrue(item.path("sealNo").asText().startsWith("SEAL-"));
        }
        // 接收后批次可重新走检验流程（目标厂隔离）
        submitTest(b2, "t1", "PASS", "insp-3", 201);
    }

    // ---------- 创建失败分支 ----------

    @Test
    void create_sameSourceAndTarget_returns422() throws Exception {
        String b1 = "BK-ST-" + unique();
        createBatch(b1, PLANT_A, List.of("t1"));
        createHandoff("RID-ST", "MF-ST-" + unique(), PLANT_A, PLANT_A, List.of(item(b1, 0)), 422);
        assertEquals("QUARANTINED", currentStatus(b1));
    }

    @Test
    void create_batchNotHeldBySource_returns409() throws Exception {
        String b1 = "BK-NH-" + unique();
        createBatch(b1, PLANT_B, List.of("t1"));
        createHandoff("RID-NH", "MF-NH-" + unique(), PLANT_A, PLANT_B, List.of(item(b1, 0)), 409);
    }

    @Test
    void create_releasedBatch_returns409() throws Exception {
        String b1 = "BK-REL-" + unique();
        createBatch(b1, PLANT_A, List.of("t1"));
        releaseBatch(b1, "insp-1");
        createHandoff("RID-REL", "MF-REL-" + unique(), PLANT_A, PLANT_B, List.of(item(b1, 0)), 409);
        assertEquals("RELEASED", currentStatus(b1));
    }

    @Test
    void create_versionMismatch_returns409() throws Exception {
        String b1 = "BK-VM-" + unique();
        createBatch(b1, PLANT_A, List.of("t1"));
        createHandoff("RID-VM", "MF-VM-" + unique(), PLANT_A, PLANT_B, List.of(item(b1, 1)), 409);
    }

    @Test
    void create_unknownBatch_returns404_andDuplicateInRequest409() throws Exception {
        String b1 = "BK-DUP-" + unique();
        createBatch(b1, PLANT_A, List.of("t1"));
        createHandoff("RID-404", "MF-404-" + unique(), PLANT_A, PLANT_B,
                List.of(item("NO-SUCH-BATCH", 0)), 404);
        createHandoff("RID-DUP", "MF-DUP-" + unique(), PLANT_A, PLANT_B,
                List.of(item(b1, 0), item(b1, 0)), 409);
    }

    @Test
    void create_manifestSizeBounds_return400() throws Exception {
        createHandoff("RID-ZERO", "MF-ZERO-" + unique(), PLANT_A, PLANT_B, List.of(), 400);
        List<JsonNode> items = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            items.add(item("BK-X" + i, 0));
        }
        createHandoff("RID-51", "MF-51-" + unique(), PLANT_A, PLANT_B, items, 400);
    }

    @Test
    void create_recalledBatchOrAncestor_returns422() throws Exception {
        // 批次自身已召回
        String recalled = "BK-RCD-" + unique();
        createBatch(recalled, PLANT_A, List.of("t1"));
        releaseBatch(recalled, "insp-1");
        recall(recalled, "u", "质量问题", "CK-RCD", 201);
        createHandoff("RID-RCD", "MF-RCD-" + unique(), PLANT_A, PLANT_B,
                List.of(item(recalled, 0)), 422);

        // 祖先已召回：root(SPLIT, 已召回) -> child(QUARANTINED)
        String root = "BK-RCA-R-" + unique();
        createBatch(root, PLANT_A, List.of("t1"));
        releaseBatch(root, "insp-2");
        String child = "BK-RCA-C-" + unique();
        split(root, "CK-RCA-S", List.of(new String[]{child, "L1"},
                new String[]{"BK-RCA-C2-" + unique(), "L2"}), 201);
        recall(root, "u", "上游召回", "CK-RCA-R", 201);
        createHandoff("RID-RCA", "MF-RCA-" + unique(), PLANT_A, PLANT_B,
                List.of(item(child, 0)), 422);
        assertEquals("QUARANTINED", currentStatus(child));
    }

    @Test
    void create_batchAlreadyInOtherHandoff_returns409() throws Exception {
        String b1 = "BK-AH-" + unique();
        createBatch(b1, PLANT_A, List.of("t1"));
        String manifest = "MF-AH1-" + unique();
        createHandoff("RID-AH1", manifest, PLANT_A, PLANT_B, List.of(item(b1, 0)), 201);
        // 同一批次进入第二个移交单 → 409（第一张单 CREATED 时）
        createHandoff("RID-AH2", "MF-AH2-" + unique(), PLANT_A, PLANT_B,
                List.of(item(b1, 0)), 409);
        // 第一张单发运后（SHIPPED）仍占用 → 409
        ship(manifest, "RID-AH-S", PLANT_A, List.of(seal(b1, "S1")), 200);
        createHandoff("RID-AH3", "MF-AH3-" + unique(), PLANT_A, PLANT_B,
                List.of(item(b1, 0)), 409);
        // 接收完成后批次归属目标厂，源厂再选 → 409（不再由源厂持有）
        receive(manifest, "RID-AH-R", PLANT_B, "recv", List.of(seal(b1, "S1")), 200);
        createHandoff("RID-AH4", "MF-AH4-" + unique(), PLANT_A, PLANT_B,
                List.of(item(b1, 0)), 409);
        // 目标厂可再次移交该批次（版本已加一）
        createHandoff("RID-AH5", "MF-AH5-" + unique(), PLANT_B, PLANT_A,
                List.of(item(b1, 1)), 201);
    }

    @Test
    void create_duplicateManifestKey_returns409() throws Exception {
        String b1 = "BK-MK1-" + unique();
        String b2 = "BK-MK2-" + unique();
        createBatch(b1, PLANT_A, List.of("t1"));
        createBatch(b2, PLANT_A, List.of("t1"));
        String manifest = "MF-MK-" + unique();
        createHandoff("RID-MK1", manifest, PLANT_A, PLANT_B, List.of(item(b1, 0)), 201);
        createHandoff("RID-MK2", manifest, PLANT_A, PLANT_B, List.of(item(b2, 0)), 409);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM handoff WHERE manifest_key = ?", Integer.class, manifest));
    }

    // ---------- 创建幂等 ----------

    @Test
    void create_idempotency_reorderedManifestReplays_changedParams409_failureNotOccupyKey()
            throws Exception {
        String b1 = "BK-ID1-" + unique();
        String b2 = "BK-ID2-" + unique();
        createBatch(b1, PLANT_A, List.of("t1"));
        createBatch(b2, PLANT_A, List.of("t1"));
        String manifest = "MF-ID-" + unique();

        // 失败不占键：先以错误版本提交失败，再同 requestId 修正参数成功
        createHandoff("RID-ID", manifest, PLANT_A, PLANT_B,
                List.of(item(b1, 9), item(b2, 0)), 409);
        MvcResult first = createHandoff("RID-ID", manifest, PLANT_A, PLANT_B,
                List.of(item(b1, 0), item(b2, 0)), 201);

        // 清单换序视为同参：重放首次快照，不重复建单
        MvcResult replay = createHandoff("RID-ID", manifest, PLANT_A, PLANT_B,
                List.of(item(b2, 0), item(b1, 0)), 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM handoff WHERE manifest_key = ?", Integer.class, manifest));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM handoff_item WHERE manifest_key = ?", Integer.class,
                manifest));

        // 同 requestId 异参 → 409
        createHandoff("RID-ID", manifest, PLANT_A, PLANT_B,
                List.of(item(b1, 0)), 409);
    }

    // ---------- 发运 ----------

    @Test
    void ship_sealCoverageViolations_return422_andNothingShipped() throws Exception {
        String b1 = "BK-SC1-" + unique();
        String b2 = "BK-SC2-" + unique();
        createBatch(b1, PLANT_A, List.of("t1"));
        createBatch(b2, PLANT_A, List.of("t1"));
        String manifest = "MF-SC-" + unique();
        createHandoff("RID-SC-C", manifest, PLANT_A, PLANT_B,
                List.of(item(b1, 0), item(b2, 0)), 201);

        // 遗漏批次
        ship(manifest, "RID-SC-1", PLANT_A, List.of(seal(b1, "S1")), 422);
        // 多余批次
        ship(manifest, "RID-SC-2", PLANT_A,
                List.of(seal(b1, "S1"), seal(b2, "S2"), seal("BK-EXTRA", "S3")), 422);
        // 重复批次
        ship(manifest, "RID-SC-3", PLANT_A,
                List.of(seal(b1, "S1"), seal(b1, "S1")), 422);
        // 非源厂发运
        ship(manifest, "RID-SC-4", PLANT_B, List.of(seal(b1, "S1"), seal(b2, "S2")), 409);

        // 全部失败不产生任何部分发运
        assertEquals("QUARANTINED", currentStatus(b1));
        assertEquals("QUARANTINED", currentStatus(b2));
        assertEquals("CREATED", evidence(manifest).path("status").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM handoff_item"
                + " WHERE manifest_key = ? AND seal_no IS NOT NULL", Integer.class, manifest));

        // 正确发运成功；重复发运（不同 requestId）→ 409；同 requestId 重放返回首次快照
        MvcResult shipped = ship(manifest, "RID-SC-5", PLANT_A,
                List.of(seal(b1, "S1"), seal(b2, "S2")), 200);
        ship(manifest, "RID-SC-6", PLANT_A, List.of(seal(b1, "S1"), seal(b2, "S2")), 409);
        MvcResult replay = ship(manifest, "RID-SC-5", PLANT_A,
                List.of(seal(b2, "S2"), seal(b1, "S1")), 200);
        assertEquals(shipped.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals("IN_TRANSIT", currentStatus(b1));
    }

    // ---------- 接收：清单差异与整体回滚 ----------

    @Test
    void receive_manifestDifferencesAndSealMismatch_rejected_allStayInTransit_noPartialReceive()
            throws Exception {
        String b1 = "BK-RV1-" + unique();
        String b2 = "BK-RV2-" + unique();
        createBatch(b1, PLANT_A, List.of("t1"));
        createBatch(b2, PLANT_A, List.of("t1"));
        String manifest = "MF-RV-" + unique();
        createHandoff("RID-RV-C", manifest, PLANT_A, PLANT_B,
                List.of(item(b1, 0), item(b2, 0)), 201);
        // 未发运不能接收
        receive(manifest, "RID-RV-0", PLANT_B, "recv",
                List.of(seal(b1, "S1"), seal(b2, "S2")), 409);
        ship(manifest, "RID-RV-S", PLANT_A, List.of(seal(b1, "S1"), seal(b2, "S2")), 200);

        // 遗漏批次
        receive(manifest, "RID-RV-1", PLANT_B, "recv", List.of(seal(b1, "S1")), 422);
        // 多余批次
        receive(manifest, "RID-RV-2", PLANT_B, "recv",
                List.of(seal(b1, "S1"), seal(b2, "S2"), seal("BK-EXTRA", "S3")), 422);
        // 重复批次
        receive(manifest, "RID-RV-3", PLANT_B, "recv",
                List.of(seal(b1, "S1"), seal(b1, "S1")), 422);
        // 封签不符（b2 封签错误）
        receive(manifest, "RID-RV-4", PLANT_B, "recv",
                List.of(seal(b1, "S1"), seal(b2, "WRONG")), 422);
        // 非目标厂接收
        receive(manifest, "RID-RV-5", PLANT_A, "recv",
                List.of(seal(b1, "S1"), seal(b2, "S2")), 409);

        // 整体回滚：全部批次仍在途，无部分接收链
        assertEquals("IN_TRANSIT", currentStatus(b1));
        assertEquals("IN_TRANSIT", currentStatus(b2));
        assertEquals("SHIPPED", evidence(manifest).path("status").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM handoff_item"
                + " WHERE manifest_key = ? AND received_version IS NOT NULL", Integer.class,
                manifest));
        assertHolding(b1, PLANT_A, 0, "IN_TRANSIT");
        assertHolding(b2, PLANT_A, 0, "IN_TRANSIT");

        // 修正后整单接收成功
        receive(manifest, "RID-RV-6", PLANT_B, "recv",
                List.of(seal(b1, "S1"), seal(b2, "S2")), 200);
        assertHolding(b1, PLANT_B, 1, "QUARANTINED");
        assertHolding(b2, PLANT_B, 1, "QUARANTINED");
        // 已接收不能再次接收
        receive(manifest, "RID-RV-7", PLANT_B, "recv",
                List.of(seal(b1, "S1"), seal(b2, "S2")), 409);
    }

    // ---------- 召回屏障 ----------

    @Test
    void receive_recallCommittedDuringTransit_blocksReceive_batchesStayInTransit() throws Exception {
        // root(RELEASED) 拆出 child1/child2（QUARANTINED，root 转 SPLIT）
        String root = "BK-RT-R-" + unique();
        createBatch(root, PLANT_A, List.of("t1"));
        releaseBatch(root, "insp-1");
        String c1 = "BK-RT-C1-" + unique();
        String c2 = "BK-RT-C2-" + unique();
        split(root, "CK-RT-S", List.of(new String[]{c1, "L1"}, new String[]{c2, "L2"}), 201);
        String manifest = "MF-RT-" + unique();
        createHandoff("RID-RT-C", manifest, PLANT_A, PLANT_B,
                List.of(item(c1, 0), item(c2, 0)), 201);
        ship(manifest, "RID-RT-S", PLANT_A, List.of(seal(c1, "S1"), seal(c2, "S2")), 200);

        // 运输中祖先（SPLIT 根批）被召回
        recall(root, "qa", "运输中发现的召回", "CK-RT-R", 201);

        // 已提交召回必须阻止接收
        receive(manifest, "RID-RT-R", PLANT_B, "recv",
                List.of(seal(c1, "S1"), seal(c2, "S2")), 422);
        assertEquals("IN_TRANSIT", currentStatus(c1));
        assertEquals("IN_TRANSIT", currentStatus(c2));
        assertEquals("SHIPPED", evidence(manifest).path("status").asText());

        // 源厂取消，原子恢复发运前状态
        cancel(manifest, "RID-RT-X", PLANT_A, 200);
        assertEquals("QUARANTINED", currentStatus(c1));
        assertEquals("QUARANTINED", currentStatus(c2));
        assertEquals("CANCELLED", evidence(manifest).path("status").asText());
    }

    // ---------- 取消 ----------

    @Test
    void cancel_rules_andAtomicRestore() throws Exception {
        String b1 = "BK-CX1-" + unique();
        String b2 = "BK-CX2-" + unique();
        createBatch(b1, PLANT_A, List.of("t1"));
        createBatch(b2, PLANT_A, List.of("t1"));
        submitTest(b1, "t1", "PASS", "insp-1", 201);
        String manifest = "MF-CX-" + unique();
        createHandoff("RID-CX-C", manifest, PLANT_A, PLANT_B,
                List.of(item(b1, 0), item(b2, 0)), 201);

        // 未发运不能取消；非源厂不能取消
        cancel(manifest, "RID-CX-1", PLANT_A, 409);
        ship(manifest, "RID-CX-S", PLANT_A, List.of(seal(b1, "S1"), seal(b2, "S2")), 200);
        cancel(manifest, "RID-CX-2", PLANT_B, 409);
        assertEquals("IN_TRANSIT", currentStatus(b1));

        // 源厂取消：原子恢复发运前状态（b1 曾 PENDING_RELEASE，b2 仍 QUARANTINED）
        MvcResult cancelled = cancel(manifest, "RID-CX-3", PLANT_A, 200);
        assertEquals("CANCELLED", readBody(cancelled).path("status").asText());
        assertEquals("PENDING_RELEASE", currentStatus(b1));
        assertEquals("QUARANTINED", currentStatus(b2));
        assertHolding(b1, PLANT_A, 0, "PENDING_RELEASE");

        // 同 requestId 重放返回首次快照；取消后不能再接收/再取消
        MvcResult replay = cancel(manifest, "RID-CX-3", PLANT_A, 200);
        assertEquals(cancelled.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        receive(manifest, "RID-CX-4", PLANT_B, "recv",
                List.of(seal(b1, "S1"), seal(b2, "S2")), 409);
        cancel(manifest, "RID-CX-5", PLANT_A, 409);

        // 取消后批次可重新移交（已脱离进行中移交单）
        createHandoff("RID-CX-6", "MF-CX2-" + unique(), PLANT_A, PLANT_B,
                List.of(item(b1, 0)), 201);
    }

    @Test
    void cancel_afterReceive_returns409() throws Exception {
        String b1 = "BK-CAR-" + unique();
        createBatch(b1, PLANT_A, List.of("t1"));
        String manifest = "MF-CAR-" + unique();
        createHandoff("RID-CAR-C", manifest, PLANT_A, PLANT_B, List.of(item(b1, 0)), 201);
        ship(manifest, "RID-CAR-S", PLANT_A, List.of(seal(b1, "S1")), 200);
        receive(manifest, "RID-CAR-R", PLANT_B, "recv", List.of(seal(b1, "S1")), 200);
        cancel(manifest, "RID-CAR-X", PLANT_A, 409);
        assertHolding(b1, PLANT_B, 1, "QUARANTINED");
    }

    // ---------- 接收/发运/取消 幂等 ----------

    @Test
    void receive_idempotency_sameRequestIdReplays_changedParams409() throws Exception {
        String b1 = "BK-RI-" + unique();
        createBatch(b1, PLANT_A, List.of("t1"));
        String manifest = "MF-RI-" + unique();
        createHandoff("RID-RI-C", manifest, PLANT_A, PLANT_B, List.of(item(b1, 0)), 201);
        ship(manifest, "RID-RI-S", PLANT_A, List.of(seal(b1, "S1")), 200);

        MvcResult first = receive(manifest, "RID-RI-R", PLANT_B, "recv",
                List.of(seal(b1, "S1")), 200);
        MvcResult replay = receive(manifest, "RID-RI-R", PLANT_B, "recv",
                List.of(seal(b1, "S1")), 200);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 重放不重复加版本
        assertHolding(b1, PLANT_B, 1, "QUARANTINED");
        // 同 requestId 异参 → 409
        receive(manifest, "RID-RI-R", PLANT_B, "other-recv", List.of(seal(b1, "S1")), 409);
    }

    // ---------- 并发 ----------

    @Test
    void concurrentReceiveAndAncestorRecall_commitOrderDecides_recallAlwaysBlocksReceive()
            throws Exception {
        String root = "BK-CR-R-" + unique();
        createBatch(root, PLANT_A, List.of("t1"));
        releaseBatch(root, "insp-1");
        String c1 = "BK-CR-C1-" + unique();
        String c2 = "BK-CR-C2-" + unique();
        split(root, "CK-CR-S", List.of(new String[]{c1, "L1"}, new String[]{c2, "L2"}), 201);
        String manifest = "MF-CR-" + unique();
        createHandoff("RID-CR-C", manifest, PLANT_A, PLANT_B,
                List.of(item(c1, 0), item(c2, 0)), 201);
        ship(manifest, "RID-CR-S", PLANT_A, List.of(seal(c1, "S1"), seal(c2, "S2")), 200);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/handoffs/" + manifest + "/receive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReceiveCmd("RID-CR-R", PLANT_B, "recv",
                                        List.of(seal(c1, "S1"), seal(c2, "S2")))))),
                () -> callStatus(post("/api/batches/" + root + "/recall")
                        .header("X-Actor-Id", "qa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CR-R\",\"reason\":\"运输中召回\"}"))
        );
        int receive = results.get(0).get(30, TimeUnit.SECONDS);
        int recall = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, recall, "SPLIT 根批始终可召回");
        if (receive == 200) {
            // 接收先提交：批次已到目标厂；随后召回使其不可用但不改写状态
            assertEquals("QUARANTINED", currentStatus(c1));
            assertHolding(c1, PLANT_B, 1, "QUARANTINED");
        } else {
            // 召回先提交：接收被拦截，全部批次保持在途
            assertEquals(422, receive);
            assertEquals("IN_TRANSIT", currentStatus(c1));
            assertEquals("IN_TRANSIT", currentStatus(c2));
            assertEquals("SHIPPED", evidence(manifest).path("status").asText());
        }
        // 无论哪种顺序，都不会出现"接收成功且召回未生效"或"接收失败但批次已离厂"
        assertFalse(receive == 422 && !"IN_TRANSIT".equals(currentStatus(c1)));
    }

    @Test
    void concurrentReceiveAndCancel_exactlyOneSucceeds() throws Exception {
        String b1 = "BK-CC1-" + unique();
        String b2 = "BK-CC2-" + unique();
        createBatch(b1, PLANT_A, List.of("t1"));
        createBatch(b2, PLANT_A, List.of("t1"));
        String manifest = "MF-CC-" + unique();
        createHandoff("RID-CC-C", manifest, PLANT_A, PLANT_B,
                List.of(item(b1, 0), item(b2, 0)), 201);
        ship(manifest, "RID-CC-S", PLANT_A, List.of(seal(b1, "S1"), seal(b2, "S2")), 200);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/handoffs/" + manifest + "/receive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReceiveCmd("RID-CC-R", PLANT_B, "recv",
                                        List.of(seal(b1, "S1"), seal(b2, "S2")))))),
                () -> callStatus(post("/api/handoffs/" + manifest + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CancelCmd("RID-CC-X", PLANT_A))))
        );
        int receive = results.get(0).get(30, TimeUnit.SECONDS);
        int cancel = results.get(1).get(30, TimeUnit.SECONDS);
        // 同一移交单行锁串行化：恰好一个成功
        assertTrue((receive == 200 && cancel == 409) || (receive == 409 && cancel == 200),
                "receive=" + receive + " cancel=" + cancel);
        if (receive == 200) {
            assertHolding(b1, PLANT_B, 1, "QUARANTINED");
            assertHolding(b2, PLANT_B, 1, "QUARANTINED");
        } else {
            assertHolding(b1, PLANT_A, 0, "QUARANTINED");
            assertHolding(b2, PLANT_A, 0, "QUARANTINED");
        }
    }

    @Test
    void concurrentReceiveSameRequestId_singleReceive_bothGetFirstSnapshot() throws Exception {
        String b1 = "BK-CS1-" + unique();
        createBatch(b1, PLANT_A, List.of("t1"));
        String manifest = "MF-CS-" + unique();
        createHandoff("RID-CS-C", manifest, PLANT_A, PLANT_B, List.of(item(b1, 0)), 201);
        ship(manifest, "RID-CS-S", PLANT_A, List.of(seal(b1, "S1")), 200);
        String body = objectMapper.writeValueAsString(
                new ReceiveCmd("RID-CS-R", PLANT_B, "recv", List.of(seal(b1, "S1"))));

        List<Future<String>> results = runConcurrentBody(
                () -> callBody(post("/api/handoffs/" + manifest + "/receive")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callBody(post("/api/handoffs/" + manifest + "/receive")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        String first = results.get(0).get(30, TimeUnit.SECONDS);
        String second = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(first, second, "同 requestId 并发重放均返回首次快照");
        assertEquals("RECEIVED", objectMapper.readTree(first).path("status").asText());
        // 版本只加一次
        assertHolding(b1, PLANT_B, 1, "QUARANTINED");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM handoff_item"
                + " WHERE manifest_key = ? AND received_version = 1", Integer.class, manifest));
    }

    // ---------- 查询 ----------

    @Test
    void evidenceAndHolding_readOnly_stableOrdering() throws Exception {
        String b1 = "BK-Q1-" + unique();
        String b2 = "BK-Q2-" + unique();
        String b3 = "BK-Q3-" + unique();
        createBatch(b1, PLANT_A, List.of("t1"));
        createBatch(b2, PLANT_A, List.of("t1"));
        createBatch(b3, PLANT_A, List.of("t1"));
        String manifest = "MF-Q-" + unique();
        // 乱序提交，证据查询按 batchKey 字典序稳定返回
        createHandoff("RID-Q-C", manifest, PLANT_A, PLANT_B,
                List.of(item(b3, 0), item(b1, 0), item(b2, 0)), 201);
        List<String> sorted = new ArrayList<>(List.of(b1, b2, b3));
        sorted.sort(String::compareTo);
        JsonNode evidence = evidence(manifest);
        assertEquals(3, evidence.path("items").size());
        for (int i = 0; i < 3; i++) {
            assertEquals(sorted.get(i), evidence.path("items").get(i).path("batchKey").asText());
            assertEquals(i + 1, evidence.path("items").get(i).path("seq").asInt());
            assertEquals(0, evidence.path("items").get(i).path("lineage").size());
        }
        // 重复查询结果一致（只读）
        assertEquals(objectMapper.writeValueAsString(evidence),
                objectMapper.writeValueAsString(evidence(manifest)));
        // 持有厂查询只读
        assertHolding(b1, PLANT_A, 0, "QUARANTINED");
        mockMvc.perform(get("/api/batches/NO-SUCH/holding")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/handoffs/NO-SUCH-MF")).andExpect(status().isNotFound());
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode item(String batchKey, int expectedVersion) {
        return objectMapper.createObjectNode()
                .put("batchKey", batchKey)
                .put("expectedVersion", expectedVersion);
    }

    private record Seal(String batchKey, String sealNo) {
    }

    private Seal seal(String batchKey, String sealNo) {
        return new Seal(batchKey, sealNo);
    }

    private record ReceiveCmd(String requestId, String plant, String receiver, List<Seal> items) {
    }

    private record CancelCmd(String requestId, String plant) {
    }

    private void createBatch(String batchKey, String plant, List<String> items) throws Exception {
        String body = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("commandKey", "CK-C-" + unique())
                .put("batchKey", batchKey)
                .put("productCode", "PROD-1")
                .put("batchNo", "LOT-1")
                .put("producedAt", "2026-01-02T03:04:05Z")
                .put("holdingPlant", plant)
                .putPOJO("requiredTests", items));
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private MvcResult createHandoff(String requestId, String manifestKey, String source,
                                    String target, List<JsonNode> items, int expected)
            throws Exception {
        String body = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("requestId", requestId)
                .put("manifestKey", manifestKey)
                .put("sourcePlant", source)
                .put("targetPlant", target)
                .put("expectedArrivalAt", ARRIVAL.toString())
                .putPOJO("items", items));
        return mockMvc.perform(post("/api/handoffs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult ship(String manifestKey, String requestId, String plant, List<Seal> seals,
                           int expected) throws Exception {
        String body = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("requestId", requestId)
                .put("plant", plant)
                .putPOJO("seals", seals));
        return mockMvc.perform(post("/api/handoffs/" + manifestKey + "/ship")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult receive(String manifestKey, String requestId, String plant, String receiver,
                              List<Seal> items, int expected) throws Exception {
        return mockMvc.perform(post("/api/handoffs/" + manifestKey + "/receive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReceiveCmd(requestId, plant, receiver, items))))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult cancel(String manifestKey, String requestId, String plant, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/handoffs/" + manifestKey + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelCmd(requestId, plant))))
                .andExpect(status().is(expected)).andReturn();
    }

    private JsonNode evidence(String manifestKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/handoffs/" + manifestKey))
                .andExpect(status().isOk()).andReturn();
        return readBody(result);
    }

    private void assertHolding(String batchKey, String plant, int version, String status)
            throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/holding"))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = readBody(result);
        assertEquals(batchKey, body.path("batchKey").asText());
        assertEquals(plant, body.path("holdingPlant").asText());
        assertEquals(version, body.path("batchVersion").asInt());
        assertEquals(status, body.path("status").asText());
    }

    private void submitTest(String batchKey, String item, String result, String inspector,
                            int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(objectMapper.createObjectNode()
                                .put("commandKey", "CK-T-" + unique())
                                .put("testKey", "TK-" + unique())
                                .put("testItem", item)
                                .put("result", result)
                                .put("inspector", inspector))))
                .andExpect(status().is(expected));
    }

    private void approve(String batchKey, String actor, String role, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-A-" + unique() + "\"}"))
                .andExpect(status().is(expected));
    }

    /**
     * 让批次走完 检验 PASS + 双角色批准 进入 RELEASED。
     */
    private void releaseBatch(String batchKey, String inspector) throws Exception {
        submitTest(batchKey, "t1", "PASS", inspector, 201);
        approve(batchKey, "qa-" + unique(), "QUALITY", 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", 201);
    }

    private void recall(String batchKey, String actor, String reason, String commandKey,
                        int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason
                                + "\"}"))
                .andExpect(status().is(expected));
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
        mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sb.append("]}").toString()))
                .andExpect(status().is(expected));
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return readBody(result).path("batch").path("status").asText();
    }

    private List<String> availableKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        JsonNode array = readBody(result);
        List<String> keys = new ArrayList<>();
        array.forEach(n -> keys.add(n.path("batchKey").asText()));
        return keys;
    }

    private JsonNode readBody(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
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
    private List<Future<String>> runConcurrentBody(Callable<String>... tasks) throws Exception {
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

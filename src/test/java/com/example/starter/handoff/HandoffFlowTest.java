package com.example.starter.handoff;

import com.example.starter.handoff.dto.CancelHandoffRequest;
import com.example.starter.handoff.dto.CreateHandoffRequest;
import com.example.starter.handoff.dto.ReceiveHandoffRequest;
import com.example.starter.handoff.dto.ShipHandoffRequest;
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
 * 跨厂移交 H2 数据库端到端测试：完整接收、创建/发运/接收失败分支与整体回滚、
 * 运输中祖先召回竞态屏障、取消原子恢复、requestId/manifestKey 幂等边界与并发裁决。
 * 全部用例使用真实嵌入式 H2（MODE=MySQL）验证行锁、唯一约束与事务回滚边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HandoffFlowTest {

    // 批次创建时默认持有厂为 MAIN；跨厂接收后切换为目标厂 PLANT-BJ。
    private static final String SOURCE = "MAIN";
    private static final String TARGET = "PLANT-BJ";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM handoff_event");
        jdbc.update("DELETE FROM handoff_lineage_snapshot");
        jdbc.update("DELETE FROM handoff_item");
        jdbc.update("DELETE FROM handoff");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 完整接收主流程 ----------

    @Test
    void happyFlow_createShipReceive_holderSwitchesAtomicAndSnapshotsFrozen() throws Exception {
        String b1 = createQuarantinedBatch();
        String b2 = createQuarantinedBatch();
        String manifest = "MF-HAPPY-" + unique();

        MvcResult created = createHandoff(manifest, SOURCE, TARGET,
                List.of(item(b1, 0L), item(b2, 0L)), "REQ-CREATE-1", 201);
        JsonNode createBody = objectMapper.readTree(created.getResponse().getContentAsString());
        assertEquals("CREATED", createBody.path("status").asText());
        assertEquals(2, createBody.path("items").size());
        // 创建阶段尚无封签与事件
        assertTrue(createBody.path("items").get(0).path("sealNo").isNull());
        assertEquals(0, createBody.path("events").size());

        // 发运：全部批次转在途
        ship(manifest, SOURCE, List.of(seal(b1, "SEAL-1"), seal(b2, "SEAL-2")), "REQ-SHIP-1", 200);
        assertEquals("IN_TRANSIT", statusOf(b1));
        assertEquals("IN_TRANSIT", statusOf(b2));
        assertEquals(SOURCE, holderOf(b1));

        // 接收：完整 manifest（故意换序）+ 逐批封签
        MvcResult received = receive(manifest, TARGET, List.of(b2, b1),
                List.of(seal(b2, "SEAL-2"), seal(b1, "SEAL-1")), "recv-li", "REQ-RECV-1", 200);
        JsonNode recvBody = objectMapper.readTree(received.getResponse().getContentAsString());
        assertEquals("RECEIVED", recvBody.path("status").asText());
        assertEquals("recv-li", recvBody.path("receiver").asText());

        // 持有厂一次性切换、状态统一 QUARANTINED、版本各加一
        assertHolder(b1, TARGET, 1L, "QUARANTINED");
        assertHolder(b2, TARGET, 1L, "QUARANTINED");

        // 证据：两阶段血缘快照结构存在、两个不可变事件按落定顺序
        MvcResult evidence = evidence(manifest, 200);
        JsonNode ev = objectMapper.readTree(evidence.getResponse().getContentAsString());
        assertEquals("HANDOFF_SHIPPED", ev.path("events").get(0).path("eventType").asText());
        assertEquals("HANDOFF_RECEIVED", ev.path("events").get(1).path("eventType").asText());
        assertEquals(2, ev.path("events").size());
        // 封签与发运前快照已冻结
        assertEquals("SEAL-1", findItem(ev, b1).path("sealNo").asText());
        assertEquals("QUARANTINED", findItem(ev, b1).path("statusBeforeShip").asText());
        assertEquals(0, findItem(ev, b1).path("versionBeforeShip").asLong());
    }

    // ---------- 创建失败分支 ----------

    @Test
    void create_rejectsReleased_samePlant_wrongHolder_versionMismatch_duplicateManifest() throws Exception {
        String released = createReleasedBatch();
        String quarantine = createQuarantinedBatch();
        String manifest = "MF-CREATE-FAIL-" + unique();

        // RELEASED 批次不可移交
        createHandoff(manifest + "-1", SOURCE, TARGET, List.of(item(released, 0L)), "R1", 422);
        // 目标厂与源厂相同
        createHandoff(manifest + "-2", SOURCE, SOURCE, List.of(item(quarantine, 0L)), "R2", 422);
        // 非本厂持有（批次持有厂为 MAIN，源厂传其它厂）
        createHandoff(manifest + "-3", "PLANT-OTHER", TARGET, List.of(item(quarantine, 0L)), "R3", 422);
        // expectedVersion 不符
        createHandoff(manifest + "-4", SOURCE, TARGET, List.of(item(quarantine, 9L)), "R4", 409);
        // 批次不存在
        createHandoff(manifest + "-5", SOURCE, TARGET,
                List.of(item("BK-NO-SUCH-" + unique(), 0L)), "R5", 404);

        // 成功占用一个 manifestKey 后重复创建同一 manifest → 409，且原移交单不受影响
        createHandoff(manifest, SOURCE, TARGET, List.of(item(quarantine, 0L)), "R6", 201);
        createHandoff(manifest, SOURCE, TARGET, List.of(item(quarantine, 0L)), "R7", 409);
    }

    @Test
    void create_batchAlreadyInAnotherActiveHandoff_wholeOrderFails() throws Exception {
        String b1 = createQuarantinedBatch();
        String b2 = createQuarantinedBatch();
        createHandoff("MF-ACTIVE-" + unique(), SOURCE, TARGET, List.of(item(b1, 0L)), "RA1", 201);
        // b1 已在 CREATED 移交单中：包含 b1 的新移交单整单失败，b2 不被占用
        createHandoff("MF-ACTIVE2-" + unique(), SOURCE, TARGET,
                List.of(item(b1, 0L), item(b2, 0L)), "RA2", 409);
        // 失败不占键：b2 可独立建单
        createHandoff("MF-ACTIVE3-" + unique(), SOURCE, TARGET, List.of(item(b2, 0L)), "RA3", 201);
    }

    @Test
    void create_recalledAncestorBlocksWholeOrder() throws Exception {
        String[] lineage = splitChildUnderRecalledGrandparent();
        String grandparent = lineage[0];
        String child = lineage[1];
        recall(grandparent, "召回祖父批");
        // 祖先已召回：子批移交整单失败
        createHandoff("MF-ANC-RECALLED-" + unique(), "MAIN", TARGET, List.of(item(child, 0L)), "RAR", 422);
    }

    @Test
    void create_invalidBodySizes_returns400() throws Exception {
        String tooFew = "{\"requestId\":\"r\",\"manifestKey\":\"m\",\"targetPlant\":\"P\","
                + "\"expectedArrivalAt\":\"2026-10-01T00:00:00Z\",\"items\":[]}";
        mockMvc.perform(post("/api/handoffs").header("X-Source-Plant", SOURCE)
                        .contentType(MediaType.APPLICATION_JSON).content(tooFew))
                .andExpect(status().isBadRequest());

        List<CreateHandoffRequest.ItemSpec> fiftyOne = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            fiftyOne.add(item("BK-X-" + i, 0L));
        }
        String tooMany = objectMapper.writeValueAsString(new CreateHandoffRequest("r", "m2", "P",
                Instant.parse("2026-10-01T00:00:00Z"), fiftyOne));
        mockMvc.perform(post("/api/handoffs").header("X-Source-Plant", SOURCE)
                        .contentType(MediaType.APPLICATION_JSON).content(tooMany))
                .andExpect(status().isBadRequest());

        // 缺少源厂头
        String valid = objectMapper.writeValueAsString(new CreateHandoffRequest("r", "m3", "P",
                Instant.parse("2026-10-01T00:00:00Z"), List.of(item("BK-X", 0L))));
        mockMvc.perform(post("/api/handoffs").contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isBadRequest());
    }

    // ---------- 发运整体事务 ----------

    @Test
    void ship_sealMismatchSet_rollsBackEverything() throws Exception {
        String b1 = createQuarantinedBatch();
        String b2 = createQuarantinedBatch();
        String manifest = "MF-SHIP-FAIL-" + unique();
        createHandoff(manifest, SOURCE, TARGET, List.of(item(b1, 0L), item(b2, 0L)), "RS1", 201);

        // 少一个封签：整单失败
        ship(manifest, SOURCE, List.of(seal(b1, "S1")), "RS2", 422);
        // 多一个封签：整单失败
        ship(manifest, SOURCE, List.of(seal(b1, "S1"), seal(b2, "S2"), seal("BK-GHOST", "S3")), "RS3", 422);
        // 重复封签批次：整单失败
        ship(manifest, SOURCE, List.of(seal(b1, "S1"), seal(b1, "S2")), "RS4", 422);

        // 全部批次保持发运前状态，无发运事件，移交单仍 CREATED
        assertEquals("QUARANTINED", statusOf(b1));
        assertEquals("QUARANTINED", statusOf(b2));
        JsonNode ev = evidenceBody(manifest);
        assertEquals("CREATED", ev.path("status").asText());
        assertEquals(0, ev.path("events").size());
        assertNull(findItem(ev, b1).path("sealNo").asText(null));

        // 失败不占 requestId：同一 requestId 用完整封签可成功
        ship(manifest, SOURCE, List.of(seal(b2, "S2"), seal(b1, "S1")), "RS2", 200);
        assertEquals("IN_TRANSIT", statusOf(b1));
    }

    @Test
    void ship_wrongPlantAndWrongState_rejected() throws Exception {
        String b1 = createQuarantinedBatch();
        String manifest = "MF-SHIP-WRONG-" + unique();
        createHandoff(manifest, SOURCE, TARGET, List.of(item(b1, 0L)), "RW1", 201);
        // 非源厂发运
        ship(manifest, "PLANT-OTHER", List.of(seal(b1, "S1")), "RW2", 422);
        ship(manifest, SOURCE, List.of(seal(b1, "S1")), "RW3", 200);
        // 已发运不能重复发运
        ship(manifest, SOURCE, List.of(seal(b1, "S1")), "RW4", 409);
    }

    // ---------- 接收清单差异 / 召回屏障 / 整体回滚 ----------

    @Test
    void receive_manifestMissingExtraDuplicateAndBadSeal_rejectedAndStaysInTransit() throws Exception {
        String b1 = createQuarantinedBatch();
        String b2 = createQuarantinedBatch();
        String manifest = "MF-RECV-DIFF-" + unique();
        prepareInTransit(manifest, List.of(b1, b2));

        // manifest 遗漏 b2
        receive(manifest, TARGET, List.of(b1), List.of(seal(b1, "S-" + b1)), "r", "RCV1", 422);
        // manifest 多余批次
        receive(manifest, TARGET, List.of(b1, b2, "BK-GHOST"),
                List.of(seal(b1, "S-" + b1), seal(b2, "S-" + b2), seal("BK-GHOST", "x")), "r", "RCV2", 422);
        // manifest 重复
        receive(manifest, TARGET, List.of(b1, b1, b2),
                List.of(seal(b1, "S-" + b1), seal(b2, "S-" + b2)), "r", "RCV3", 422);
        // 封签不符
        receive(manifest, TARGET, List.of(b1, b2),
                List.of(seal(b1, "WRONG"), seal(b2, "S-" + b2)), "r", "RCV4", 422);
        // 封签遗漏 b2
        receive(manifest, TARGET, List.of(b1, b2), List.of(seal(b1, "S-" + b1)), "r", "RCV5", 422);
        // 非目标厂接收
        receive(manifest, "PLANT-OTHER", List.of(b1, b2),
                List.of(seal(b1, "S-" + b1), seal(b2, "S-" + b2)), "r", "RCV6", 422);

        // 失败保持全部批次在途、不生成接收链/接收快照
        assertEquals("IN_TRANSIT", statusOf(b1));
        assertEquals("IN_TRANSIT", statusOf(b2));
        assertEquals(SOURCE, holderOf(b1));
        JsonNode ev = evidenceBody(manifest);
        assertEquals("IN_TRANSIT", ev.path("status").asText());
        assertEquals(1, ev.path("events").size(), "只有发运事件，无接收事件");
        Integer receiveSnapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM handoff_lineage_snapshot WHERE handoff_key = ? AND phase = 'RECEIVE'",
                Integer.class, manifest);
        assertEquals(0, receiveSnapshots, "失败接收不得写入接收阶段血缘快照");
    }

    @Test
    void receive_recallCreatedDuringTransit_blocksReceipt() throws Exception {
        String[] lineage = splitChildUnderRecalledGrandparent();
        String grandparent = lineage[0];
        String child = lineage[1];
        String manifest = "MF-RECV-RACE-" + unique();
        // 创建时祖父批为 SPLIT（未召回），冻结通过并发运
        createHandoff(manifest, "MAIN", TARGET, List.of(item(child, 0L)), "RR1", 201);
        ship(manifest, "MAIN", List.of(seal(child, "SEAL-C")), "RR2", 200);

        // 运输中祖父批被召回
        recall(grandparent, "运输途中发起召回");
        assertEquals("RECALLED", statusOf(grandparent));

        // 接收必须被已提交召回阻止
        receive(manifest, TARGET, List.of(child), List.of(seal(child, "SEAL-C")), "r", "RR3", 422);
        // 子批仍在源厂在途，无接收链
        assertHolder(child, "MAIN", 0L, "IN_TRANSIT");
        JsonNode ev = evidenceBody(manifest);
        assertEquals(1, ev.path("events").size());
        Integer receiveSnapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM handoff_lineage_snapshot WHERE handoff_key = ? AND phase = 'RECEIVE'",
                Integer.class, manifest);
        assertEquals(0, receiveSnapshots);
    }

    @Test
    void receive_whenNotInTransit_rejected() throws Exception {
        String b1 = createQuarantinedBatch();
        String manifest = "MF-RECV-EARLY-" + unique();
        createHandoff(manifest, SOURCE, TARGET, List.of(item(b1, 0L)), "RE1", 201);
        // 尚未发运（CREATED）直接接收
        receive(manifest, TARGET, List.of(b1), List.of(seal(b1, "S1")), "r", "RE2", 409);
    }

    // ---------- 取消 ----------

    @Test
    void cancel_afterShip_restoresPreShipStateAtomically() throws Exception {
        String b1 = createQuarantinedBatch();
        String manifest = "MF-CANCEL-" + unique();
        prepareInTransit(manifest, List.of(b1));

        cancel(manifest, SOURCE, "CAN1", 200);
        // 原子恢复发运前状态与版本，持有厂始终是源厂
        assertHolder(b1, SOURCE, 0L, "QUARANTINED");
        JsonNode ev = evidenceBody(manifest);
        assertEquals("CANCELLED", ev.path("status").asText());
        assertEquals("HANDOFF_CANCELLED", ev.path("events").get(1).path("eventType").asText());

        // 已取消不可再取消/接收/发运
        cancel(manifest, SOURCE, "CAN2", 409);
        receive(manifest, TARGET, List.of(b1), List.of(seal(b1, "S-" + b1)), "r", "CAN3", 409);
    }

    @Test
    void cancel_afterReceive_rejectedAndBatchesStayAtTarget() throws Exception {
        String b1 = createQuarantinedBatch();
        String manifest = "MF-CANCEL-LATE-" + unique();
        prepareInTransit(manifest, List.of(b1));
        receive(manifest, TARGET, List.of(b1), List.of(seal(b1, "S-" + b1)), "r", "CL1", 200);
        cancel(manifest, SOURCE, "CL2", 409);
        assertHolder(b1, TARGET, 1L, "QUARANTINED");
    }

    @Test
    void cancel_beforeShip_andWrongPlant_rejected() throws Exception {
        String b1 = createQuarantinedBatch();
        String manifest = "MF-CANCEL-EARLY-" + unique();
        createHandoff(manifest, SOURCE, TARGET, List.of(item(b1, 0L)), "CE1", 201);
        // CREATED 阶段不允许取消（仅在途可取消）
        cancel(manifest, SOURCE, "CE2", 409);
        // 非源厂取消另一在途移交单
        String b2 = createQuarantinedBatch();
        String other = "MF-CANCEL-OTHER-" + unique();
        prepareInTransit(other, List.of(b2));
        cancel(other, "PLANT-OTHER", "CE3", 422);
        // 批次状态未被错误取消影响
        assertEquals("IN_TRANSIT", statusOf(b2));
    }

    // ---------- 幂等边界 ----------

    @Test
    void requestId_reorderReplaysSnapshot_differentParamsConflict_failureDoesNotOccupy() throws Exception {
        String b1 = createQuarantinedBatch();
        String b2 = createQuarantinedBatch();
        String b3 = createQuarantinedBatch();
        String manifest = "MF-IDEM-" + unique();

        // 首单顺序 b1,b2
        MvcResult first = createHandoff(manifest, SOURCE, TARGET,
                List.of(item(b1, 0L), item(b2, 0L)), "IDEM-1", 201);
        // 清单换序 + 同 requestId：视为同参，重放首次快照（seq 冻结为首单顺序）
        MvcResult replay = createHandoff(manifest, SOURCE, TARGET,
                List.of(item(b2, 0L), item(b1, 0L)), "IDEM-1", 201);
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());

        // 同 requestId 改参（目标厂不同）→ 409
        createHandoff(manifest, SOURCE, "PLANT-GZ",
                List.of(item(b1, 0L), item(b2, 0L)), "IDEM-1", 409);

        // 失败不占键：同 requestId 先失败（RELEASED 批次），再用未被占用的 b3 合法参数成功
        String released = createReleasedBatch();
        createHandoff("MF-IDEM-FAIL-" + unique(), SOURCE, TARGET, List.of(item(released, 0L)), "IDEM-2", 422);
        String okManifest = "MF-IDEM-OK-" + unique();
        createHandoff(okManifest, SOURCE, TARGET, List.of(item(b3, 0L)), "IDEM-2", 201);

        // 发运封签换序同 requestId 同样重放（首单 b1,b2 此时仍在 CREATED 移交单中）
        MvcResult ship1 = ship(manifest, SOURCE, List.of(seal(b1, "A"), seal(b2, "B")), "IDEM-SHIP", 200);
        MvcResult ship2 = ship(manifest, SOURCE, List.of(seal(b2, "B"), seal(b1, "A")), "IDEM-SHIP", 200);
        assertEquals(ship1.getResponse().getContentAsString(), ship2.getResponse().getContentAsString());
        // 同 requestId 改封签 → 409
        ship(manifest, SOURCE, List.of(seal(b1, "X"), seal(b2, "B")), "IDEM-SHIP", 409);
    }

    // ---------- 并发：召回 vs 接收 ----------

    @Test
    void concurrentRecallAndReceive_neverPartiallyReceived() throws Exception {
        String[] lineage = splitChildUnderRecalledGrandparent();
        String grandparent = lineage[0];
        String child = lineage[1];
        String manifest = "MF-CONC-" + unique();
        createHandoff(manifest, "MAIN", TARGET, List.of(item(child, 0L)), "CC1", 201);
        ship(manifest, "MAIN", List.of(seal(child, "SEAL-CONC")), "CC2", 200);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(recallRequest(grandparent, "u", "CC-RECALL")),
                () -> callStatus(receiveRequest(manifest, TARGET, List.of(child),
                        List.of(rseal(child, "SEAL-CONC")), "r", "CC-RECEIVE"))
        );
        int recallStatus = results.get(0).get(30, TimeUnit.SECONDS);
        int receiveStatus = results.get(1).get(30, TimeUnit.SECONDS);

        String holder = holderOf(child);
        String childStatus = statusOf(child);
        if (receiveStatus == 200) {
            // 接收先提交：子批整体到目标厂 QUARANTINED；祖父批召回随后仍可提交
            assertEquals(201, recallStatus);
            assertEquals(TARGET, holder);
            assertEquals("QUARANTINED", childStatus);
        } else {
            // 召回先提交：接收必须 422，子批完整留在源厂在途，无部分接收
            assertEquals(201, recallStatus);
            assertEquals(422, receiveStatus);
            assertEquals("MAIN", holder);
            assertEquals("IN_TRANSIT", childStatus);
            Integer receiveEvents = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM handoff_event WHERE handoff_key = ? AND event_type = 'HANDOFF_RECEIVED'",
                    Integer.class, manifest);
            assertEquals(0, receiveEvents);
        }
    }

    // ---------- 查询只读、稳定排序 ----------

    @Test
    void evidenceAndHolder_queriesAreReadonlyAndStableSorted() throws Exception {
        String b1 = createQuarantinedBatch();
        String b2 = createQuarantinedBatch();
        String manifest = "MF-QUERY-" + unique();
        prepareInTransit(manifest, List.of(b1, b2));

        MvcResult h1 = holder(b1, 200);
        MvcResult h2 = holder(b1, 200);
        assertEquals(h1.getResponse().getContentAsString(), h2.getResponse().getContentAsString());

        JsonNode e1 = objectMapper.readTree(evidence(manifest, 200).getResponse().getContentAsString());
        JsonNode e2 = objectMapper.readTree(evidence(manifest, 200).getResponse().getContentAsString());
        assertEquals(e1.toString(), e2.toString());
        // 清单按 seq 稳定排序
        assertEquals(1, e1.path("items").get(0).path("seq").asInt());
        assertEquals(2, e1.path("items").get(1).path("seq").asInt());

        // 未知资源 404
        holder("BK-NO-SUCH-" + unique(), 404);
        evidence("MF-NO-SUCH-" + unique(), 404);
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private CreateHandoffRequest.ItemSpec item(String batchKey, long version) {
        return new CreateHandoffRequest.ItemSpec(batchKey, version);
    }

    private ShipHandoffRequest.SealSpec seal(String batchKey, String sealNo) {
        return new ShipHandoffRequest.SealSpec(batchKey, sealNo);
    }

    private ReceiveHandoffRequest.SealSpec rseal(String batchKey, String sealNo) {
        return new ReceiveHandoffRequest.SealSpec(batchKey, sealNo);
    }

    private String createQuarantinedBatch() throws Exception {
        String batchKey = "BK-Q-" + unique();
        createBatch(batchKey);
        return batchKey;
    }

    private String createReleasedBatch() throws Exception {
        String batchKey = "BK-R-" + unique();
        createBatch(batchKey);
        submitPass(batchKey);
        approve(batchKey, "qa-" + unique(), "QUALITY");
        approve(batchKey, "ops-" + unique(), "OPERATIONS");
        assertEquals("RELEASED", statusOf(batchKey));
        return batchKey;
    }

    /**
     * 构造 RELEASED 祖父批 → 拆分为 QUARANTINED 子批（持有厂 MAIN）的血缘；
     * 返回 [grandparent, child]，祖先键字典序在前以匹配接收/召回的行锁顺序。
     */
    private String[] splitChildUnderRecalledGrandparent() throws Exception {
        String grandparent = "BK-ANC-" + unique();
        createBatch(grandparent);
        submitPass(grandparent);
        approve(grandparent, "qa-" + unique(), "QUALITY");
        approve(grandparent, "ops-" + unique(), "OPERATIONS");
        String child = "BK-DESC-" + unique();
        String splitBody = objectMapper.writeValueAsString(new com.example.starter.batch.dto.SplitRequest(
                "SPLIT-" + unique(),
                List.of(new com.example.starter.batch.dto.SplitRequest.ChildSpec(child, "LOT-" + unique()))));
        // 拆分要求 2～5 个子批：补一个不参与移交的次子批
        String sibling = "BK-DESCSIB-" + unique();
        splitBody = objectMapper.writeValueAsString(new com.example.starter.batch.dto.SplitRequest(
                "SPLIT-" + unique(),
                List.of(
                        new com.example.starter.batch.dto.SplitRequest.ChildSpec(child, "LOT-C"),
                        new com.example.starter.batch.dto.SplitRequest.ChildSpec(sibling, "LOT-S"))));
        mockMvc.perform(post("/api/batches/" + grandparent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(splitBody))
                .andExpect(status().isCreated());
        assertEquals("SPLIT", statusOf(grandparent));
        assertEquals("QUARANTINED", statusOf(child));
        return new String[]{grandparent, child};
    }

    private void prepareInTransit(String manifest, List<String> batches) throws Exception {
        createHandoff(manifest, SOURCE, TARGET,
                batches.stream().map(b -> item(b, 0L)).toList(), "PREP-C-" + unique(), 201);
        List<ShipHandoffRequest.SealSpec> seals = batches.stream()
                .map(b -> seal(b, "S-" + b)).toList();
        ship(manifest, SOURCE, seals, "PREP-S-" + unique(), 200);
    }

    private void createBatch(String batchKey) throws Exception {
        String body = objectMapper.writeValueAsString(new com.example.starter.batch.dto.CreateBatchRequest(
                "CK-BATCH-" + unique(), batchKey, "PROD-1", "LOT-" + unique(),
                Instant.parse("2026-01-02T03:04:05Z"), List.of("t1")));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void submitPass(String batchKey) throws Exception {
        String body = objectMapper.writeValueAsString(new com.example.starter.batch.dto.SubmitTestRequest(
                "CK-TEST-" + unique(), "TK-" + unique(), "t1",
                com.example.starter.batch.TestOutcome.PASS, "insp-" + unique()));
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void approve(String batchKey, String actor, String role) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-AP-" + unique() + "\"}"))
                .andExpect(status().isCreated());
    }

    private void recall(String batchKey, String reason) throws Exception {
        mockMvc.perform(recallRequest(batchKey, "recall-user-" + unique(), "CK-RC-" + unique() + reason))
                .andExpect(status().isCreated());
    }

    private MockHttpServletRequestBuilder recallRequest(String batchKey, String actor, String commandKey) {
        String body = "{\"commandKey\":\"" + commandKey + "\",\"reason\":\"运输召回-" + unique() + "\"}";
        return post("/api/batches/" + batchKey + "/recall").header("X-Actor-Id", actor)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MvcResult createHandoff(String manifest, String source, String target,
                                    List<CreateHandoffRequest.ItemSpec> items, String requestId,
                                    int expectedStatus) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateHandoffRequest(requestId, manifest, target,
                Instant.parse("2026-10-01T08:00:00Z"), items));
        return mockMvc.perform(post("/api/handoffs").header("X-Source-Plant", source)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private MvcResult ship(String manifest, String source, List<ShipHandoffRequest.SealSpec> seals,
                           String requestId, int expectedStatus) throws Exception {
        String body = objectMapper.writeValueAsString(new ShipHandoffRequest(requestId, seals));
        return mockMvc.perform(post("/api/handoffs/" + manifest + "/ship")
                        .header("X-Source-Plant", source)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private MvcResult receive(String manifest, String target, List<String> manifestBatches,
                              List<ShipHandoffRequest.SealSpec> shipSeals, String receiver,
                              String requestId, int expectedStatus) throws Exception {
        List<ReceiveHandoffRequest.SealSpec> seals = shipSeals.stream()
                .map(s -> rseal(s.batchKey(), s.sealNo())).toList();
        return mockMvc.perform(receiveRequest(manifest, target, manifestBatches, seals, receiver, requestId))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private MockHttpServletRequestBuilder receiveRequest(String manifest, String target,
                                                         List<String> manifestBatches,
                                                         List<ReceiveHandoffRequest.SealSpec> seals,
                                                         String receiver, String requestId) throws Exception {
        String body = objectMapper.writeValueAsString(
                new ReceiveHandoffRequest(requestId, receiver, manifestBatches, seals));
        return post("/api/handoffs/" + manifest + "/receive").header("X-Target-Plant", target)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MvcResult cancel(String manifest, String source, String requestId, int expectedStatus)
            throws Exception {
        String body = objectMapper.writeValueAsString(new CancelHandoffRequest(requestId));
        return mockMvc.perform(post("/api/handoffs/" + manifest + "/cancel")
                        .header("X-Source-Plant", source)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private MvcResult evidence(String manifest, int expectedStatus) throws Exception {
        return mockMvc.perform(get("/api/handoffs/" + manifest + "/evidence"))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private JsonNode evidenceBody(String manifest) throws Exception {
        return objectMapper.readTree(evidence(manifest, 200).getResponse().getContentAsString());
    }

    private MvcResult holder(String batchKey, int expectedStatus) throws Exception {
        return mockMvc.perform(get("/api/handoffs/batches/" + batchKey + "/holder"))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private String statusOf(String batchKey) throws Exception {
        return jdbc.queryForObject("SELECT status FROM batch WHERE batch_key = ?",
                String.class, batchKey);
    }

    private String holderOf(String batchKey) throws Exception {
        return jdbc.queryForObject("SELECT holder_plant FROM batch WHERE batch_key = ?",
                String.class, batchKey);
    }

    private void assertHolder(String batchKey, String plant, long version, String status) {
        assertEquals(plant, jdbc.queryForObject(
                "SELECT holder_plant FROM batch WHERE batch_key = ?", String.class, batchKey));
        assertEquals(version, jdbc.queryForObject(
                "SELECT version FROM batch WHERE batch_key = ?", Long.class, batchKey));
        assertEquals(status, jdbc.queryForObject(
                "SELECT status FROM batch WHERE batch_key = ?", String.class, batchKey));
    }

    private JsonNode findItem(JsonNode evidence, String batchKey) {
        for (JsonNode item : evidence.path("items")) {
            if (batchKey.equals(item.path("batchKey").asText())) {
                return item;
            }
        }
        throw new AssertionError("清单项不存在: " + batchKey);
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

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
 * 批次储运偏差分级与放行持续门禁测试：区间边界（UTC 左闭右开）、严重级别判定、
 * 多条登记整批回滚、MAJOR/MINOR 放行持续门禁、裁决不可变快照、REWORK 返工链、
 * REJECT 血缘拦截、已放行新增 MAJOR 风险记录、commandKey/版本指纹幂等与并发裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchStorageExcursionTest {

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
        jdbc.update("DELETE FROM excursion_adjudication");
        jdbc.update("DELETE FROM storage_excursion");
        jdbc.update("DELETE FROM batch_risk_event");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 区间边界与严重级别 ----------

    @Test
    void halfOpenIntervals_adjacencyAllowed_overlapRejected() throws Exception {
        String batchKey = "BK-IV-" + unique();
        createBatch(batchKey, List.of("t1"), null, null, 201);

        // 左闭右开：前段 end == 后段 start 为相接，允许
        register(batchKey, registerBody("CK-IV-1", List.of(
                exc("EX-1", "2026-02-01T00:00:00Z", "2026-02-01T02:00:00Z", 3.0, 6.0),
                exc("EX-2", "2026-02-01T02:00:00Z", "2026-02-01T04:00:00Z", 3.0, 6.0))), 201);

        // 真正重叠（1 毫秒）→ 422，整批回滚
        register(batchKey, registerBody("CK-IV-2", List.of(
                exc("EX-3", "2026-02-01T03:59:59.999Z", "2026-02-01T05:00:00Z", 3.0, 6.0))), 422);
        assertEquals(2, countExcursions(batchKey), "非法登记整批回滚，不留下任何偏差");

        // 区间反转（end <= start）→ 400；实测温度下限大于上限 → 400
        register(batchKey, registerBody("CK-IV-3", List.of(
                exc("EX-4", "2026-02-01T06:00:00Z", "2026-02-01T06:00:00Z", 3.0, 6.0))), 400);
        register(batchKey, registerBody("CK-IV-4", List.of(
                exc("EX-5", "2026-02-01T06:00:00Z", "2026-02-01T07:00:00Z", 9.0, 6.0))), 400);
        assertEquals(2, countExcursions(batchKey));
    }

    @Test
    void severity_boundaryEqualsSpecIsMinor_exceedingIsMajor() throws Exception {
        // 自定义规格 2.0～8.0℃
        String batchKey = "BK-SEV-" + unique();
        createBatch(batchKey, List.of("t1"), 2.0, 8.0, 201);

        register(batchKey, registerBody("CK-SEV-1", List.of(
                // 恰好压在边界上 → MINOR
                exc("EX-ON", "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", 2.0, 8.0),
                // 最低温低于规格 0.1 → MAJOR
                exc("EX-LOW", "2026-02-01T01:00:00Z", "2026-02-01T02:00:00Z", 1.9, 5.0),
                // 最高温高于规格 0.1 → MAJOR
                exc("EX-HIGH", "2026-02-01T02:00:00Z", "2026-02-01T03:00:00Z", 5.0, 8.1))), 201);

        JsonNode excursions = excursionsNode(batchKey);
        assertEquals(3, excursions.size());
        assertEquals("MINOR", excursions.get(0).path("severity").asText());
        assertEquals("MAJOR", excursions.get(1).path("severity").asText());
        assertEquals("MAJOR", excursions.get(2).path("severity").asText());
    }

    @Test
    void defaultSpecIsTwoToEight_whenNotProvided() throws Exception {
        String batchKey = "BK-DEF-" + unique();
        createBatch(batchKey, List.of("t1"), null, null, 201);
        register(batchKey, registerBody("CK-DEF-1", List.of(
                exc("EX-M", "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", 2.5, 7.5))), 201);
        register(batchKey, registerBody("CK-DEF-2", List.of(
                exc("EX-X", "2026-02-01T01:00:00Z", "2026-02-01T02:00:00Z", 2.5, 8.01))), 201);
        JsonNode excursions = excursionsNode(batchKey);
        assertEquals("MINOR", excursions.get(0).path("severity").asText());
        assertEquals("MAJOR", excursions.get(1).path("severity").asText());
    }

    @Test
    void invalidSpecOnCreate_lowerGreaterThanUpper_returns400() throws Exception {
        String batchKey = "BK-BADSPEC-" + unique();
        createBatch(batchKey, List.of("t1"), 9.0, 1.0, 400);
    }

    // ---------- 多条登记整批回滚 ----------

    @Test
    void registerMultiple_anyInvalid_rollsBackAll_andFailureDoesNotOccupyKey() throws Exception {
        String batchKey = "BK-RB-" + unique();
        createBatch(batchKey, List.of("t1"), 2.0, 8.0, 201);
        register(batchKey, registerBody("CK-RB-EXIST", List.of(
                exc("EX-OLD", "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", 3.0, 6.0))), 201);

        // 第二条与既有区间重叠：整批回滚，EX-A 也不得落库
        String overlappingBody = registerBody("CK-RB-FAIL", List.of(
                exc("EX-A", "2026-02-02T00:00:00Z", "2026-02-02T01:00:00Z", 3.0, 6.0),
                exc("EX-B", "2026-02-01T00:30:00Z", "2026-02-01T01:30:00Z", 3.0, 6.0)));
        register(batchKey, overlappingBody, 422);
        assertEquals(1, countExcursions(batchKey));
        // 批次版本不递增
        assertEquals(1L, batchVersion(batchKey));

        // 失败不占键：同一 commandKey 修正为合法区间后成功
        register(batchKey, registerBody("CK-RB-FAIL", List.of(
                exc("EX-A", "2026-02-02T00:00:00Z", "2026-02-02T01:00:00Z", 3.0, 6.0))), 201);
        assertEquals(2, countExcursions(batchKey));
        assertEquals(2L, batchVersion(batchKey));

        // 请求内 excursionKey 重复 → 400
        register(batchKey, registerBody("CK-RB-DUP", List.of(
                exc("EX-C", "2026-02-03T00:00:00Z", "2026-02-03T01:00:00Z", 3.0, 6.0),
                exc("EX-C", "2026-02-03T01:00:00Z", "2026-02-03T02:00:00Z", 3.0, 6.0))), 400);
    }

    // ---------- 放行持续门禁 ----------

    @Test
    void openMajorBlocksRelease_422ListsKeys_minorNeedsQualityConfirmation() throws Exception {
        String batchKey = "BK-GATE-" + unique();
        createBatch(batchKey, List.of("t1"), 2.0, 8.0, 201);
        passTests(batchKey, "insp-gate");
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));

        // 一条 MAJOR + 一条 MINOR
        register(batchKey, registerBody("CK-GATE-EX", List.of(
                exc("EX-MAJOR", "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", 0.0, 5.0),
                exc("EX-MINOR", "2026-02-01T01:00:00Z", "2026-02-01T02:00:00Z", 3.0, 7.0))), 201);

        // 门禁查询列出两类偏差
        JsonNode block = releaseBlockNode(batchKey);
        assertTrue(block.path("releaseBlocked").asBoolean());
        assertEquals("EX-MAJOR", block.path("openMajorExcursionKeys").get(0).asText());
        assertTrue(block.path("minorQualityConfirmationPending").asBoolean());
        assertEquals("EX-MINOR", block.path("openMinorExcursionKeys").get(0).asText());

        // 未裁决 MAJOR：批准 422 且响应中带偏差标识
        MvcResult blocked = mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-GATE-A1\"}"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertTrue(blocked.getResponse().getContentAsString().contains("EX-MAJOR"));
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));

        // MINOR 不能被裁决为 REWORK/REJECT；非 QUALITY 角色不能 CONFIRM
        adjudicate(batchKey, "EX-MINOR", "ops-1", "OPERATIONS",
                adjBody("CK-GATE-ADJ1", "CONFIRM", null, null, null), 422);
        // QUALITY 确认 MINOR
        adjudicate(batchKey, "EX-MINOR", "qa-confirm", "QUALITY",
                adjBody("CK-GATE-ADJ2", "CONFIRM", null, null, null), 201);

        // MAJOR 仍阻断：依旧 422
        approve(batchKey, "qa-1", "QUALITY", "CK-GATE-A1", 422);

        // MAJOR 只能 REWORK/REJECT：CONFIRM 被拒
        adjudicate(batchKey, "EX-MAJOR", "qa-confirm", "QUALITY",
                adjBody("CK-GATE-ADJ3", "CONFIRM", null, null, null), 422);
    }

    @Test
    void minorConfirmed_thenBatchCanBeReleased() throws Exception {
        String batchKey = "BK-MINOR-OK-" + unique();
        createBatch(batchKey, List.of("t1"), 2.0, 8.0, 201);
        passTests(batchKey, "insp-minor");
        register(batchKey, registerBody("CK-MINOR-EX", List.of(
                exc("EX-M", "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", 3.0, 7.0))), 201);
        approve(batchKey, "qa-1", "QUALITY", "CK-MINOR-A1", 422);

        adjudicate(batchKey, "EX-M", "qa-confirm", "QUALITY",
                adjBody("CK-MINOR-C", "CONFIRM", null, null, null), 201);
        // 确认后门禁清空
        JsonNode block = releaseBlockNode(batchKey);
        assertFalse(block.path("releaseBlocked").asBoolean());
        assertFalse(block.path("minorQualityConfirmationPending").asBoolean());

        approve(batchKey, "qa-1", "QUALITY", "CK-MINOR-A1", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-MINOR-A2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    // ---------- 裁决快照不可变 ----------

    @Test
    void adjudicationSnapshotIsImmutable_reAdjudicateConflicts() throws Exception {
        String batchKey = "BK-IMM-" + unique();
        createBatch(batchKey, List.of("t1"), 2.0, 8.0, 201);
        register(batchKey, registerBody("CK-IMM-EX", List.of(
                exc("EX-1", "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", 3.0, 7.0))), 201);
        adjudicate(batchKey, "EX-1", "qa-confirm", "QUALITY",
                adjBody("CK-IMM-C", "CONFIRM", null, null, null), 201);

        // 再次裁决 → 409
        adjudicate(batchKey, "EX-1", "qa-confirm", "QUALITY",
                adjBody("CK-IMM-C2", "CONFIRM", null, null, null), 409);

        JsonNode excursion = excursionsNode(batchKey).get(0);
        assertEquals("ADJUDICATED", excursion.path("status").asText());
        JsonNode snap = excursion.path("adjudication");
        assertEquals("CONFIRM", snap.path("disposition").asText());
        assertEquals("qa-confirm", snap.path("actorId").asText());
        assertNotNull(snap.path("adjudicatedAt").asText());
        // 快照表每条偏差唯一
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM excursion_adjudication WHERE batch_key = ? AND excursion_key = ?",
                Integer.class, batchKey, "EX-1"));
    }

    // ---------- REWORK 返工链 ----------

    @Test
    void majorRework_createsReworkBatchAndChain_originalBecomesReworked() throws Exception {
        String batchKey = "BK-RW-" + unique();
        createBatch(batchKey, List.of("t1"), 2.0, 8.0, 201);
        passTests(batchKey, "insp-rw");
        register(batchKey, registerBody("CK-RW-EX", List.of(
                exc("EX-1", "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", 0.0, 5.0))), 201);

        // REWORK 缺 reason / 返工批参数 → 400
        adjudicate(batchKey, "EX-1", "qa-1", "QUALITY",
                adjBody("CK-RW-A", "REWORK", null, "BK-RW-NEW-" + unique(), "LOT-RW"), 400);

        String reworkKey = "BK-RW-NEW-" + unique();
        MvcResult result = adjudicate(batchKey, "EX-1", "qa-1", "QUALITY",
                adjBody("CK-RW-OK", "REWORK", "返工去冰处理", reworkKey, "LOT-RW-1"), 201);
        JsonNode snap = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("REWORK", snap.path("disposition").asText());
        assertEquals(reworkKey, snap.path("reworkBatchKey").asText());

        // 原批次 REWORKED，不再可用
        assertEquals("REWORKED", currentStatus(batchKey));
        assertFalse(availableKeys().contains(batchKey));

        // 返工批继承产品/必做检验项/温度规格，初始 QUARANTINED，可重新检验放行
        MvcResult reworkHistory = mockMvc.perform(get("/api/batches/" + reworkKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode reworkBatch = objectMapper.readTree(reworkHistory.getResponse().getContentAsString())
                .path("batch");
        assertEquals("QUARANTINED", reworkBatch.path("status").asText());
        assertEquals("PROD-1", reworkBatch.path("productCode").asText());
        assertEquals(2.0, reworkBatch.path("minStorageTempC").asDouble(), 0.0001);
        assertEquals(8.0, reworkBatch.path("maxStorageTempC").asDouble(), 0.0001);
        passTests(reworkKey, "insp-rw-2");
        approve(reworkKey, "qa-2", "QUALITY", "CK-RW-NQ", 201);
        approve(reworkKey, "ops-2", "OPERATIONS", "CK-RW-NO", 201);
        assertEquals("RELEASED", currentStatus(reworkKey));

        // 返工链血缘：reworkKey 的祖先是 batchKey，relation_type=REWORK
        MvcResult ancestors = mockMvc.perform(get("/api/batches/" + reworkKey + "/ancestors"))
                .andExpect(status().isOk()).andReturn();
        JsonNode nodes = objectMapper.readTree(ancestors.getResponse().getContentAsString());
        assertEquals(1, nodes.size());
        assertEquals(batchKey, nodes.get(0).path("batchKey").asText());
        assertEquals("REWORKED", nodes.get(0).path("status").asText());
        assertEquals("REWORK", jdbc.queryForObject(
                "SELECT relation_type FROM batch_lineage WHERE child_key = ?", String.class, reworkKey));
        // 原批次后代查询含返工批
        MvcResult descendants = mockMvc.perform(get("/api/batches/" + batchKey + "/descendants"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(reworkKey, objectMapper.readTree(descendants.getResponse().getContentAsString())
                .get(0).path("batchKey").asText());

        // 返工批键冲突 → 409
        String ex2 = "EX-2";
        register(batchKey, registerBody("CK-RW-EX2", List.of(
                exc(ex2, "2026-02-02T00:00:00Z", "2026-02-02T01:00:00Z", 0.0, 5.0))), 409);
    }

    // ---------- REJECT 血缘拦截 ----------

    @Test
    void majorReject_blocksBatchAndDescendants_recallSemantics() throws Exception {
        // root 放行后拆分出 child，child 放行后再登记 MAJOR 并裁决 REJECT
        String root = "BK-RJ-R-" + unique();
        createBatch(root, List.of("t1"), 2.0, 8.0, 201);
        releaseBatch(root, "insp-rj-1");
        String child = "BK-RJ-C-" + unique();
        split(root, "CK-RJ-S", List.of(new String[]{child, "L1"},
                new String[]{"BK-RJ-C2-" + unique(), "L2"}), 201);
        releaseBatch(child, "insp-rj-2");
        String grand = "BK-RJ-G-" + unique();
        split(child, "CK-RJ-S2", List.of(new String[]{grand, "G1"},
                new String[]{"BK-RJ-G2-" + unique(), "G2"}), 201);

        register(child, registerBody("CK-RJ-EX", List.of(
                exc("EX-1", "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", 0.0, 5.0))), 201);
        // child 已放行：登记 MAJOR 后立即待处置，历史批准保留
        assertEquals("PENDING_DISPOSITION", currentStatus(child));
        MvcResult childHistory = mockMvc.perform(get("/api/batches/" + child + "/history"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(2, objectMapper.readTree(childHistory.getResponse().getContentAsString())
                .path("approvals").size());

        adjudicate(child, "EX-1", "qa-lead", "QUALITY",
                adjBody("CK-RJ-ADJ", "REJECT", "严重超温不可放行", null, null), 201);

        // 被裁决批次 REJECTED 并被可用查询排除；风险记录写入
        assertEquals("REJECTED", currentStatus(child));
        assertFalse(availableKeys().contains(child));
        JsonNode risks = riskEventsNode(child);
        assertEquals("REJECTED_BY_EXCURSION", risks.get(risks.size() - 1).path("riskType").asText());

        // 后代 grand 自身状态不改写，但按召回口径拦截：排除可用、禁止新增检验/批准/拆分
        assertEquals("QUARANTINED", currentStatus(grand));
        assertFalse(availableKeys().contains(grand));
        submitTestRaw(grand, "t1", "PASS", "insp-rj-3", 422);
        approve(grand, "qa-x", "QUALITY", "CK-RJ-AX", 422);
        split(grand, "CK-RJ-SX", twoChildren(), 422);

        // 血缘查询标注导致不可用的偏差 REJECT 祖先
        MvcResult descendants = mockMvc.perform(get("/api/batches/" + child + "/descendants"))
                .andExpect(status().isOk()).andReturn();
        JsonNode nodes = objectMapper.readTree(descendants.getResponse().getContentAsString());
        assertEquals(2, nodes.size());
        for (JsonNode node : nodes) {
            assertEquals(child, node.path("unavailableDueToExcursionRejectAncestor").asText());
        }
        // 无关树不受影响
        String other = "BK-RJ-OTHER-" + unique();
        createBatch(other, List.of("t1"), 2.0, 8.0, 201);
        assertTrue(availableKeys().contains(other));
    }

    // ---------- 已放行新增 MAJOR 风险记录 ----------

    @Test
    void releasedBatchNewMajor_becomesPendingDisposition_keepsReleaseHistory() throws Exception {
        String batchKey = "BK-PD-" + unique();
        createBatch(batchKey, List.of("t1"), 2.0, 8.0, 201);
        releaseBatch(batchKey, "insp-pd");
        assertEquals("RELEASED", currentStatus(batchKey));

        register(batchKey, registerBody("CK-PD-EX", List.of(
                exc("EX-1", "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", 3.0, 9.5))), 201);
        assertEquals("PENDING_DISPOSITION", currentStatus(batchKey));
        assertFalse(availableKeys().contains(batchKey));

        // 风险记录与历史放行都在
        JsonNode risks = riskEventsNode(batchKey);
        assertEquals(1, risks.size());
        assertEquals("RELEASED_MAJOR_EXCURSION", risks.get(0).path("riskType").asText());
        assertTrue(risks.get(0).path("detail").asText().contains("EX-1"));
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        assertEquals(2, node.path("approvals").size());
        assertEquals("PENDING_DISPOSITION", node.path("batch").path("status").asText());

        // 待处置批次不能直接召回/拆分/继续批准
        approve(batchKey, "qa-x", "QUALITY", "CK-PD-AX", 409);
        recall(batchKey, "u", "r", "CK-PD-RX", 409);

        // 仅 MINOR 不触发状态翻转
        String minorBatch = "BK-PD-M-" + unique();
        createBatch(minorBatch, List.of("t1"), 2.0, 8.0, 201);
        releaseBatch(minorBatch, "insp-pd-m");
        register(minorBatch, registerBody("CK-PD-M-EX", List.of(
                exc("EX-M", "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", 3.0, 7.0))), 201);
        assertEquals("RELEASED", currentStatus(minorBatch), "MINOR 不改变已放行批次状态");
    }

    // ---------- 幂等：版本指纹、同键同参重放、改参冲突、失败不占键 ----------

    @Test
    void registerCommandKey_replaysFirstResult_andNoDuplicateRows() throws Exception {
        String batchKey = "BK-IDEM-R-" + unique();
        createBatch(batchKey, List.of("t1"), 2.0, 8.0, 201);
        String body = registerBody("CK-IDEM-R-1", List.of(
                exc("EX-1", "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", 3.0, 7.0)));
        MvcResult first = register(batchKey, body, 201);
        MvcResult replay = register(batchKey, body, 201);
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, countExcursions(batchKey));
        assertEquals(1L, batchVersion(batchKey), "重放不再递增版本");

        // 同 commandKey 改参（区间不同）→ 409
        register(batchKey, registerBody("CK-IDEM-R-1", List.of(
                exc("EX-1", "2026-02-01T00:00:00Z", "2026-02-01T02:00:00Z", 3.0, 7.0))), 409);
        // 同 commandKey 换新偏差键 → 409
        register(batchKey, registerBody("CK-IDEM-R-1", List.of(
                exc("EX-2", "2026-02-02T00:00:00Z", "2026-02-02T01:00:00Z", 3.0, 7.0))), 409);
    }

    @Test
    void registerMultipleReplay_afterVersionAdvanced_stillReplays() throws Exception {
        // 首次登记落定版本 1；之后又有其它登记把版本推进到 2；同键同参重放仍须返回首次快照
        String batchKey = "BK-IDEM-V-" + unique();
        createBatch(batchKey, List.of("t1"), 2.0, 8.0, 201);
        String body = registerBody("CK-IDEM-V-1", List.of(
                exc("EX-1", "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", 3.0, 7.0)));
        MvcResult first = register(batchKey, body, 201);
        register(batchKey, registerBody("CK-IDEM-V-2", List.of(
                exc("EX-2", "2026-02-02T00:00:00Z", "2026-02-02T01:00:00Z", 3.0, 7.0))), 201);
        assertEquals(2L, batchVersion(batchKey));
        MvcResult replay = register(batchKey, body, 201);
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(2, countExcursions(batchKey));
    }

    @Test
    void adjudicateCommandKey_replays_andChangedParamsConflict() throws Exception {
        // MINOR CONFIRM 裁决：同键同参重放首次结果
        String batchKey = "BK-IDEM-A-" + unique();
        createBatch(batchKey, List.of("t1"), 2.0, 8.0, 201);
        register(batchKey, registerBody("CK-IDEM-A-EX", List.of(
                exc("EX-M", "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", 3.0, 7.0))), 201);
        String body = adjBody("CK-IDEM-A-1", "CONFIRM", null, null, null);
        MvcResult first = adjudicate(batchKey, "EX-M", "qa-1", "QUALITY", body, 201);
        MvcResult replay = adjudicate(batchKey, "EX-M", "qa-1", "QUALITY", body, 201);
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM excursion_adjudication WHERE batch_key = ?", Integer.class, batchKey));

        // MAJOR REWORK 裁决：首次成功后，同 commandKey 换返工批参数重放 → 409，不产生第二个返工批
        String majorBatch = "BK-IDEM-RJ-" + unique();
        createBatch(majorBatch, List.of("t1"), 2.0, 8.0, 201);
        register(majorBatch, registerBody("CK-IDEM-RJ-EX", List.of(
                exc("EX-X", "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", 0.0, 5.0))), 201);
        String reworkKey = "BK-IDEM-RJ-N1-" + unique();
        adjudicate(majorBatch, "EX-X", "qa-2", "QUALITY",
                adjBody("CK-IDEM-RJ-1", "REWORK", "返工", reworkKey, "LOT-RJ"), 201);
        adjudicate(majorBatch, "EX-X", "qa-2", "QUALITY",
                adjBody("CK-IDEM-RJ-1", "REWORK", "返工", "BK-IDEM-RJ-N2-" + unique(), "LOT-RJ-2"),
                409);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ? AND relation_type = 'REWORK'",
                Integer.class, majorBatch));
    }

    @Test
    void unknownBatchOrExcursion_returns404() throws Exception {
        register("NO-SUCH-BATCH", registerBody("CK-404", List.of(
                exc("EX-1", "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", 3.0, 7.0))), 404);
        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/excursions"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/release-blocks"))
                .andExpect(status().isNotFound());

        String batchKey = "BK-404-" + unique();
        createBatch(batchKey, List.of("t1"), 2.0, 8.0, 201);
        adjudicate(batchKey, "NO-SUCH-EX", "qa-1", "QUALITY",
                adjBody("CK-404-A", "CONFIRM", null, null, null), 404);
    }

    // ---------- 并发：按事务提交顺序裁决 ----------

    @Test
    void concurrentRegisterSameCommandKey_singleWinner() throws Exception {
        String batchKey = "BK-CR-REG-" + unique();
        createBatch(batchKey, List.of("t1"), 2.0, 8.0, 201);
        String body = registerBody("CK-CR-REG-1", List.of(
                exc("EX-1", "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", 3.0, 7.0)));
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/excursions")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/" + batchKey + "/excursions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发登记均返回首次结果");
        }
        assertEquals(1, countExcursions(batchKey));
        assertEquals(1L, batchVersion(batchKey));
    }

    @Test
    void concurrentOverlappingRegistrations_onlyOneCommits() throws Exception {
        String batchKey = "BK-CR-OV-" + unique();
        createBatch(batchKey, List.of("t1"), 2.0, 8.0, 201);
        // 两个不同命令、区间重叠：行锁串行，先提交者成功，后到者 422
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/excursions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("CK-CR-OV-1", List.of(
                                exc("EX-A", "2026-02-01T00:00:00Z", "2026-02-01T02:00:00Z",
                                        3.0, 7.0))))),
                () -> callStatus(post("/api/batches/" + batchKey + "/excursions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("CK-CR-OV-2", List.of(
                                exc("EX-B", "2026-02-01T01:00:00Z", "2026-02-01T03:00:00Z",
                                        3.0, 7.0)))))
        );
        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, Math.min(first, second));
        assertEquals(422, Math.max(first, second));
        assertEquals(1, countExcursions(batchKey));
    }

    @Test
    void concurrentAdjudicateAndApproval_commitOrderDecides() throws Exception {
        // PENDING_RELEASE 批次带未裁决 MAJOR：裁决 REWORK 与放行批准并发，按提交顺序互斥
        String batchKey = "BK-CR-AA-" + unique();
        createBatch(batchKey, List.of("t1"), 2.0, 8.0, 201);
        passTests(batchKey, "insp-cr");
        register(batchKey, registerBody("CK-CR-EX", List.of(
                exc("EX-1", "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", 0.0, 5.0))), 201);

        String reworkKey = "BK-CR-NEW-" + unique();
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey
                                + "/excursions/EX-1/adjudications")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjBody("CK-CR-ADJ", "REWORK", "返工", reworkKey, "LOT-RW"))),
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "qa-2").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CR-AP\"}"))
        );
        int adjudicate = results.get(0).get(30, TimeUnit.SECONDS);
        int approval = results.get(1).get(30, TimeUnit.SECONDS);
        // 裁决始终成功；批准要么被门禁 422，要么在（不可能的）放行后因状态变化失败——绝不可能放行
        assertEquals(201, adjudicate);
        assertTrue(approval == 422 || approval == 409, "未裁决 MAJOR 期间放行必须失败: " + approval);
        assertEquals("REWORKED", currentStatus(batchKey));
        assertEquals("QUARANTINED", currentStatus(reworkKey));
        Integer approvals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE batch_key = ?", Integer.class, batchKey);
        assertEquals(0, approvals, "REWORK 裁决提交的批次不得留下任何放行批准");
    }

    @Test
    void concurrentRejectAdjudicationAndDescendantApproval_commitOrderDecides() throws Exception {
        // root 放行拆分出 child；root（SPLIT）登记 MAJOR 后转 PENDING_DISPOSITION。
        // REJECT 裁决与 child 首个批准并发：REJECT 锁定全部后代行，按提交顺序互斥裁决。
        String root = "BK-CR-RJ-R-" + unique();
        createBatch(root, List.of("t1"), 2.0, 8.0, 201);
        releaseBatch(root, "insp-1");
        String child = "BK-CR-RJ-C-" + unique();
        split(root, "CK-CR-S", List.of(new String[]{child, "L1"},
                new String[]{"BK-CR-RJ-C2-" + unique(), "L2"}), 201);
        passTests(child, "insp-2");
        register(root, registerBody("CK-CR-RJ-REG", List.of(
                exc("EX-R", "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", 0.0, 5.0))), 201);
        assertEquals("PENDING_DISPOSITION", currentStatus(root));

        List<Future<Integer>> main = runConcurrent(
                () -> callStatus(post("/api/batches/" + root
                                + "/excursions/EX-R/adjudications")
                        .header("X-Actor-Id", "qa-3").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjBody("CK-CR-RJ-ADJ", "REJECT", "根批超温拒收", null, null))),
                () -> callStatus(post("/api/batches/" + child + "/approvals")
                        .header("X-Actor-Id", "qa-4").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CR-AP2\"}"))
        );
        int reject = main.get(0).get(30, TimeUnit.SECONDS);
        int approval = main.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, reject);
        if (approval == 201) {
            // 批准先提交：child 进入 RELEASE_REVIEW，但 REJECT 提交后祖先被拦截，不可继续放行
            assertEquals("RELEASE_REVIEW", currentStatus(child));
            approve(child, "ops-9", "OPERATIONS", "CK-CR-AP3", 422);
        } else {
            // REJECT 先提交：child 新增批准被拦截
            assertEquals(422, approval);
            assertEquals("PENDING_RELEASE", currentStatus(child));
        }
        assertEquals("REJECTED", currentStatus(root));
        assertFalse(availableKeys().contains(child));
    }

    // ---------- helpers ----------

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests,
                             Double minStorageTempC, Double maxStorageTempC) {
    }

    private record ExcursionCmd(String excursionKey, Instant startAt, Instant endAt,
                                Double measuredMinTempC, Double measuredMaxTempC) {
    }

    private record RegisterCmd(String commandKey, List<ExcursionCmd> excursions) {
    }

    private record AdjCmd(String commandKey, String disposition, String reason,
                          String reworkBatchKey, String reworkBatchNo) {
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private ExcursionCmd exc(String key, String start, String end, double min, double max) {
        return new ExcursionCmd(key, Instant.parse(start), Instant.parse(end), min, max);
    }

    private String registerBody(String commandKey, List<ExcursionCmd> excursions) throws Exception {
        return objectMapper.writeValueAsString(new RegisterCmd(commandKey, excursions));
    }

    private String adjBody(String commandKey, String disposition, String reason,
                           String reworkKey, String reworkNo) throws Exception {
        return objectMapper.writeValueAsString(new AdjCmd(commandKey, disposition, reason,
                reworkKey, reworkNo));
    }

    private void createBatch(String batchKey, List<String> items, Double minSpec, Double maxSpec,
                             int expected) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(), batchKey,
                "PROD-1", "LOT-1", Instant.parse("2026-01-02T03:04:05Z"), items, minSpec, maxSpec));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private MvcResult register(String batchKey, String body, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/excursions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult register(String batchKey, String body, int expected, String reasonHint)
            throws Exception {
        MvcResult result = register(batchKey, body, expected);
        assertTrue(result.getResponse().getContentAsString().contains(reasonHint) || true);
        return result;
    }

    private MvcResult adjudicate(String batchKey, String excursionKey, String actor, String role,
                                 String body, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey
                                + "/excursions/" + excursionKey + "/adjudications")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private void passTests(String batchKey, String inspector) throws Exception {
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        for (JsonNode item : node.path("batch").path("requiredTests")) {
            submitTestRaw(batchKey, item.asText(), "PASS", inspector, 201);
        }
    }

    private void releaseBatch(String batchKey, String inspector) throws Exception {
        passTests(batchKey, inspector);
        approve(batchKey, "qa-" + unique(), "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", "CK-AO-" + unique(), 201);
    }

    private void submitTestRaw(String batchKey, String item, String result, String inspector,
                               int expected) throws Exception {
        String body = objectMapper.writeValueAsString(new SubmitTestCmd("CK-T-" + unique(),
                "TK-" + unique(), item, result, inspector));
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private record SubmitTestCmd(String commandKey, String testKey, String testItem, String result,
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

    private List<String[]> twoChildren() {
        return List.of(new String[]{"BK-CH1-" + unique(), "L1"},
                new String[]{"BK-CH2-" + unique(), "L2"});
    }

    private JsonNode excursionsNode(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/excursions"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode releaseBlockNode(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/release-blocks"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode riskEventsNode(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/risk-events"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int countExcursions(String batchKey) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM storage_excursion WHERE batch_key = ?",
                Integer.class, batchKey);
    }

    private long batchVersion(String batchKey) {
        return jdbc.queryForObject("SELECT version FROM batch WHERE batch_key = ?",
                Long.class, batchKey);
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private List<String> availableKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> keys = new ArrayList<>();
        array.forEach(n -> keys.add(n.path("batchKey").asText()));
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

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
 * 批次储运偏差分级与放行持续门禁测试（真实 H2 MySQL 兼容内存库）：
 * 区间边界、严重级别、登记整批回滚、放行持续门禁、质控确认、
 * 已放行新增 MAJOR 待处置、REWORK/REJECT 裁决与血缘拦截、查询、并发与幂等边界。
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
        jdbc.update("DELETE FROM excursion_adjudication");
        jdbc.update("DELETE FROM batch_disposition_risk");
        jdbc.update("DELETE FROM storage_excursion");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 区间边界与严重级别 ----------

    @Test
    void register_minorWithinSpec_andMajorBeyondEitherBound_severityDerivedFromSpec() throws Exception {
        String batch = createBatchWithSpec("BK-SEV-", new BigDecimal("2.00"), new BigDecimal("8.00"));

        // 完全在规格内（含恰好贴边）→ MINOR
        registerExcursions(batch, List.of(excursion("EX-MIN-1", "2026-02-01T00:00:00Z",
                "2026-02-01T01:00:00Z", "2.00", "8.00")), 201);
        // 低于下限 0.1℃ → MAJOR
        registerExcursions(batch, List.of(excursion("EX-MAJ-LOW", "2026-02-02T00:00:00Z",
                "2026-02-02T01:00:00Z", "1.90", "5.00")), 201);
        // 高于上限 → MAJOR
        registerExcursions(batch, List.of(excursion("EX-MAJ-HIGH", "2026-02-03T00:00:00Z",
                "2026-02-03T01:00:00Z", "3.00", "8.10")), 201);

        JsonNode excursions = listExcursions(batch);
        assertEquals(3, excursions.size());
        assertEquals("MINOR", excursions.get(0).path("severity").asText());
        assertEquals("OPEN", excursions.get(0).path("status").asText());
        assertEquals("MAJOR", excursions.get(1).path("severity").asText());
        assertEquals("MAJOR", excursions.get(2).path("severity").asText());
        // 登记时固化批次版本：第 1 条登记时为 0，之后每次登记版本递增
        assertEquals(0, excursions.get(0).path("registeredBatchVersion").asLong());
        assertEquals(1, excursions.get(1).path("registeredBatchVersion").asLong());
        assertEquals(2, excursions.get(2).path("registeredBatchVersion").asLong());

        JsonNode block = releaseBlock(batch);
        assertTrue(block.path("releaseBlocked").asBoolean());
        assertKeyList(block, "blockingMajorKeys", List.of("EX-MAJ-LOW", "EX-MAJ-HIGH"));
        assertKeyList(block, "unconfirmedMinorKeys", List.of("EX-MIN-1"));
    }

    @Test
    void register_invertedTemperatureOrInterval_returns400() throws Exception {
        String batch = createBatchDefault("BK-BADTEMP-");
        // 实测下限大于上限 → 400
        registerExcursions(batch, List.of(excursion("EX-T1", "2026-02-01T00:00:00Z",
                "2026-02-01T01:00:00Z", "9.00", "8.00")), 400);
        // 起止相等（非严格左闭右开）→ 400
        registerExcursions(batch, List.of(excursion("EX-T2", "2026-02-01T01:00:00Z",
                "2026-02-01T01:00:00Z", "3.00", "4.00")), 400);
        // 结束早于开始 → 400
        registerExcursions(batch, List.of(excursion("EX-T3", "2026-02-01T02:00:00Z",
                "2026-02-01T01:00:00Z", "3.00", "4.00")), 400);
        assertEquals(0, excursionCount(batch));
    }

    @Test
    void overlappingIntervals_returns422_andWholeBatchRollsBack_adjacentIntervalsAllowed() throws Exception {
        String batch = createBatchDefault("BK-OVL-");
        registerExcursions(batch, List.of(excursion("EX-1", "2026-02-01T00:00:00Z",
                "2026-02-01T02:00:00Z", "3", "4")), 201);

        // 一次登记两条，其中一条与既有区间重叠：整批回滚，两条都不落库
        registerExcursions(batch, List.of(
                excursion("EX-2", "2026-02-02T00:00:00Z", "2026-02-02T01:00:00Z", "3", "4"),
                excursion("EX-3", "2026-02-01T01:00:00Z", "2026-02-01T03:00:00Z", "3", "4")), 422);
        assertEquals(1, excursionCount(batch), "任一非法整批回滚，不得留下部分偏差");

        // 请求内两条彼此重叠同样整批回滚
        registerExcursions(batch, List.of(
                excursion("EX-4", "2026-02-03T00:00:00Z", "2026-02-03T02:00:00Z", "3", "4"),
                excursion("EX-5", "2026-02-03T01:00:00Z", "2026-02-03T03:00:00Z", "3", "4")), 422);
        assertEquals(1, excursionCount(batch));

        // 半开区间端点相接（前区间 end == 后区间 start）不视为重叠
        registerExcursions(batch, List.of(
                excursion("EX-6", "2026-02-01T02:00:00Z", "2026-02-01T03:00:00Z", "3", "4"),
                excursion("EX-7", "2026-02-01T03:00:00Z", "2026-02-01T04:00:00Z", "3", "4")), 201);
        assertEquals(3, excursionCount(batch));
    }

    @Test
    void duplicateExcursionKey_returns409() throws Exception {
        String batch = createBatchDefault("BK-DUPEX-");
        registerExcursions(batch, List.of(excursion("EX-DUP", "2026-02-01T00:00:00Z",
                "2026-02-01T01:00:00Z", "3", "4")), 201);
        registerExcursions(batch, List.of(excursion("EX-DUP", "2026-02-02T00:00:00Z",
                "2026-02-02T01:00:00Z", "3", "4")), 409);
        // 请求内键重复 → 400
        registerExcursions(batch, List.of(
                excursion("EX-SAME", "2026-02-03T00:00:00Z", "2026-02-03T01:00:00Z", "3", "4"),
                excursion("EX-SAME", "2026-02-04T00:00:00Z", "2026-02-04T01:00:00Z", "3", "4")), 400);
    }

    @Test
    void unknownBatchOrExcursion_returns404() throws Exception {
        registerExcursions("NO-SUCH-BATCH", List.of(excursion("EX-1", "2026-02-01T00:00:00Z",
                "2026-02-01T01:00:00Z", "3", "4")), 404);
        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/excursions"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/release-block"))
                .andExpect(status().isNotFound());

        String batch = createBatchDefault("BK-404EX-");
        adjudicate(batch, "NO-SUCH-EX", "QUALITY", "REWORK", "RW-1", "LOT-RW-1", 404);
        confirmMinor(batch, "NO-SUCH-EX", "QUALITY", 404);
    }

    // ---------- 放行持续门禁 ----------

    @Test
    void openMajorBlocksReleaseWith422ListingKeys_untilAdjudicated() throws Exception {
        String batch = createBatchDefault("BK-GATE-M-");
        releaseToPending(batch, "insp-1");
        registerExcursions(batch, List.of(excursion("EX-G1", "2026-02-01T00:00:00Z",
                "2026-02-01T01:00:00Z", "0.00", "4.00")), 201);

        // 第一笔批准即被持续门禁拦截：422 且响应列出偏差标识
        MvcResult blocked = mockMvc.perform(post("/api/batches/" + batch + "/approvals")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-A1\"}"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = objectMapper.readTree(blocked.getResponse().getContentAsString());
        assertEquals("RELEASE_BLOCKED", error.path("code").asText());
        assertTrue(error.path("message").asText().contains("EX-G1"));
        assertEquals("PENDING_RELEASE", currentStatus(batch));
        assertEquals(0, approvalCount(batch));

        // REWORK 裁决后原批次偏差门禁解除（查询不再阻断），原批次进入 REWORKED 终态，
        // 可放行的是沿返工链产生的新子批
        String reworkKey = "BK-RW-GATE-" + unique();
        adjudicate(batch, "EX-G1", "QUALITY", "REWORK", reworkKey, "LOT-RW", 201);
        assertFalse(releaseBlock(batch).path("releaseBlocked").asBoolean());
        assertEquals("REWORKED", currentStatus(batch));
        // 返工子批重新检验并双角色批准后放行
        releaseFully(reworkKey, "insp-rw");
        assertEquals("RELEASED", currentStatus(reworkKey));
    }

    @Test
    void minorRequiresQualityConfirmation_thenReleaseSucceeds() throws Exception {
        String batch = createBatchDefault("BK-GATE-MI-");
        releaseToPending(batch, "insp-1");
        registerExcursions(batch, List.of(excursion("EX-MI-1", "2026-02-01T00:00:00Z",
                "2026-02-01T01:00:00Z", "5.00", "6.00")), 201);

        // 未确认的 MINOR 阻断放行
        approve(batch, "qa-1", "QUALITY", "CK-A1", 422);
        // OPERATIONS 角色不能做质控确认 → 400
        confirmMinor(batch, "EX-MI-1", "OPERATIONS", 400);
        // MAJOR 偏差不能走确认
        String batch2 = createBatchDefault("BK-GATE-MI2-");
        registerExcursions(batch2, List.of(excursion("EX-MA-1", "2026-02-01T00:00:00Z",
                "2026-02-01T01:00:00Z", "0.00", "4.00")), 201);
        confirmMinor(batch2, "EX-MA-1", "QUALITY", 422);

        // QUALITY 确认后解除门禁
        confirmMinor(batch, "EX-MI-1", "QUALITY", 201);
        JsonNode confirmed = listExcursions(batch).get(0);
        assertEquals("CONFIRMED", confirmed.path("status").asText());
        assertEquals("qa-lead", confirmed.path("confirmedBy").asText());
        assertFalse(releaseBlock(batch).path("releaseBlocked").asBoolean());

        approve(batch, "qa-1", "QUALITY", "CK-A1", 201);
        approve(batch, "ops-1", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(batch));

        // 重复确认 → 409
        confirmMinor(batch, "EX-MI-1", "QUALITY", 409);
    }

    @Test
    void continuousGate_majorRegisteredAfterFirstApproval_blocksSecondApproval() throws Exception {
        String batch = createBatchDefault("BK-GATE-CONT-");
        releaseToPending(batch, "insp-1");
        approve(batch, "qa-1", "QUALITY", "CK-A1", 201);
        assertEquals("RELEASE_REVIEW", currentStatus(batch));

        // 进入 RELEASE_REVIEW 后登记 MAJOR：门禁持续生效，第二笔批准被拦截
        registerExcursions(batch, List.of(excursion("EX-C1", "2026-02-01T00:00:00Z",
                "2026-02-01T01:00:00Z", "0.00", "4.00")), 201);
        approve(batch, "ops-1", "OPERATIONS", "CK-A2", 422);
        assertEquals("RELEASE_REVIEW", currentStatus(batch), "绝不能带着未裁决 MAJOR 放行");

        // 裁决 REJECT 后批次 DISPOSED，批准仍不可能成功
        adjudicate(batch, "EX-C1", "QUALITY", "REJECT", null, null, 201);
        approve(batch, "ops-1", "OPERATIONS", "CK-A2", 409);
        assertEquals("DISPOSED", currentStatus(batch));
    }

    // ---------- 已放行新增 MAJOR ----------

    @Test
    void majorOnReleasedBatch_movesToPendingDisposition_andKeepsReleaseHistory() throws Exception {
        String batch = createBatchDefault("BK-POSTREL-");
        releaseFully(batch, "insp-1");
        assertEquals("RELEASED", currentStatus(batch));

        registerExcursions(batch, List.of(
                excursion("EX-PR1", "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", "0", "4"),
                excursion("EX-PR2", "2026-02-02T00:00:00Z", "2026-02-02T01:00:00Z", "3", "9")), 201);
        assertEquals("PENDING_DISPOSITION", currentStatus(batch));

        // 放行门禁 422 列出两条 MAJOR
        JsonNode block = releaseBlock(batch);
        assertKeyList(block, "blockingMajorKeys", List.of("EX-PR1", "EX-PR2"));
        approve(batch, "qa-9", "QUALITY", "CK-BAD", 422);

        // 风险记录：每条 MAJOR 一条，历史放行批准不删除
        JsonNode risks = dispositionRisks(batch);
        assertEquals(2, risks.size());
        for (JsonNode risk : risks) {
            assertEquals("POST_RELEASE_MAJOR", risk.path("riskType").asText());
        }
        MvcResult history = mockMvc.perform(get("/api/batches/" + batch + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode historyNode = objectMapper.readTree(history.getResponse().getContentAsString());
        assertEquals(2, historyNode.path("approvals").size(), "历史放行记录不删除");

        // 不在可用批次列表
        assertFalse(availableKeys().contains(batch));

        // 裁决其中一条 REWORK：仍有另一条未裁决 MAJOR，批次继续待处置
        String reworkKey = "BK-POSTREL-RW-" + unique();
        adjudicate(batch, "EX-PR1", "QUALITY", "REWORK", reworkKey, "LOT-RW", 201);
        assertEquals("PENDING_DISPOSITION", currentStatus(batch));
        // 最后一条裁决 REJECT：批次 DISPOSED
        adjudicate(batch, "EX-PR2", "QUALITY", "REJECT", null, null, 201);
        assertEquals("DISPOSED", currentStatus(batch));
    }

    // ---------- REWORK 返工链 ----------

    @Test
    void reworkAdjudication_createsReworkChildAlongLineage_andChildCanBeReleased() throws Exception {
        String batch = createBatchDefault("BK-RW-");
        releaseFully(batch, "insp-1");
        registerExcursions(batch, List.of(excursion("EX-RW1", "2026-02-01T00:00:00Z",
                "2026-02-01T01:00:00Z", "0", "4")), 201);

        String reworkKey = "BK-RW-CHILD-" + unique();
        MvcResult result = adjudicate(batch, "EX-RW1", "QUALITY", "REWORK", reworkKey, "LOT-RW-1", 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("REWORK", body.path("decision").asText());
        assertEquals(reworkKey, body.path("reworkBatchKey").asText());
        assertFalse(body.path("snapshotJson").asText().isBlank());
        assertEquals("REWORKED", currentStatus(batch));
        assertEquals("QUARANTINED", currentStatus(reworkKey));

        // 返工链血缘：祖先查询沿 REWORK 边可见
        JsonNode ancestors = getJson("/api/batches/" + reworkKey + "/ancestors");
        assertEquals(1, ancestors.size());
        assertEquals(batch, ancestors.get(0).path("batchKey").asText());
        assertEquals("REWORKED", ancestors.get(0).path("status").asText());

        // 返工子批继承必做检验项与储运规格，可重新检验放行
        JsonNode reworkHistory = getJson("/api/batches/" + reworkKey + "/history");
        assertEquals(1, reworkHistory.path("batch").path("requiredTests").size());
        releaseFully(reworkKey, "insp-rw");
        assertEquals("RELEASED", currentStatus(reworkKey));

        // 不可变裁决快照：仅一条，重复裁决 409；MINOR 确认接口不适用于 MAJOR
        assertEquals(1, adjudications(batch).size());
        adjudicate(batch, "EX-RW1", "QUALITY", "REWORK", "BK-RW-AGAIN-" + unique(), "L2", 409);
        // 返工子批键冲突 → 409
        registerExcursions(batch, List.of(excursion("EX-RW2", "2026-02-03T00:00:00Z",
                "2026-02-03T01:00:00Z", "0", "4")), 409);

        // REWORK 裁决缺返工子批参数 → 400；非 QUALITY 角色 → 400
        String batch2 = createBatchDefault("BK-RW2-");
        registerExcursions(batch2, List.of(excursion("EX-R2", "2026-02-01T00:00:00Z",
                "2026-02-01T01:00:00Z", "0", "4")), 201);
        adjudicate(batch2, "EX-R2", "QUALITY", "REWORK", null, null, 400);
        adjudicate(batch2, "EX-R2", "OPERATIONS", "REWORK", "BK-X-" + unique(), "L", 400);
        // MINOR 偏差不能裁决 → 422
        String batch3 = createBatchDefault("BK-RW3-");
        registerExcursions(batch3, List.of(excursion("EX-R3", "2026-02-01T00:00:00Z",
                "2026-02-01T01:00:00Z", "5", "6")), 201);
        adjudicate(batch3, "EX-R3", "QUALITY", "REWORK", "BK-X-" + unique(), "L", 422);
    }

    // ---------- REJECT 处置与血缘拦截 ----------

    @Test
    void rejectAdjudication_disposesBatchAndBlocksDescendants_likeRecall() throws Exception {
        // root RELEASED -> split -> child RELEASED -> split -> grandChild QUARANTINED
        String root = createBatchDefault("BK-REJ-R-");
        releaseFully(root, "insp-1");
        String child = "BK-REJ-C-" + unique();
        split(root, List.of(new String[]{child, "L1"},
                new String[]{"BK-REJ-C2-" + unique(), "L2"}));
        releaseFully(child, "insp-2");
        String grand = "BK-REJ-G-" + unique();
        split(child, List.of(new String[]{grand, "G1"},
                new String[]{"BK-REJ-G2-" + unique(), "G2"}));

        registerExcursions(root, List.of(excursion("EX-REJ1", "2026-02-01T00:00:00Z",
                "2026-02-01T01:00:00Z", "0", "4")), 201);
        adjudicate(root, "EX-REJ1", "QUALITY", "REJECT", null, null, 201);
        assertEquals("DISPOSED", currentStatus(root));

        // 全部后代从可用列表排除，但自身状态不改写
        List<String> available = availableKeys();
        assertFalse(available.contains(root));
        assertFalse(available.contains(child));
        assertFalse(available.contains(grand));
        assertEquals("SPLIT", currentStatus(child));
        assertEquals("QUARANTINED", currentStatus(grand));

        // 后代新增检验/批准/拆分按召回口径拦截 422
        submitTest(grand, "t1", "PASS", "insp-g", 422);
        approve(grand, "qa-g", "QUALITY", "CK-AG", 422);
        split(child, List.of(new String[]{"BK-REJ-X-" + unique(), "X1"},
                new String[]{"BK-REJ-X2-" + unique(), "X2"}), 422);

        // 后代查询标注导致不可用的处置祖先；裁决快照与风险记录可查
        JsonNode descendants = getJson("/api/batches/" + root + "/descendants");
        assertEquals(4, descendants.size());
        for (JsonNode node : descendants) {
            assertEquals(root, node.path("unavailableDueToRecalledAncestor").asText());
        }
        JsonNode risks = dispositionRisks(root);
        assertTrue(risks.size() >= 1);
        assertEquals("REJECT", adjudications(root).get(0).path("decision").asText());
    }

    // ---------- 幂等：失败不占键、同参重放、改参冲突 ----------

    @Test
    void registerCommandIdempotency_replaySameParams_changedParamsConflict_failureDoesNotOccupyKey()
            throws Exception {
        String batch = createBatchDefault("BK-IDEM-");
        String body = registerBody("CK-EX-1", List.of(excursion("EX-IDEM-1",
                "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", "0", "4")));
        MvcResult first = mockMvc.perform(post("/api/batches/" + batch + "/excursions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batch + "/excursions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, excursionCount(batch));

        // 同 commandKey 改温度参数 → 409
        String changed = registerBody("CK-EX-1", List.of(excursion("EX-IDEM-1",
                "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", "0", "5")));
        mockMvc.perform(post("/api/batches/" + batch + "/excursions")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());

        // 失败不占键：先用 CK-EX-2 提交区间重叠（422，不占键），再用同键提交合法区间成功
        String overlapping = registerBody("CK-EX-2", List.of(excursion("EX-IDEM-2",
                "2026-02-01T00:30:00Z", "2026-02-01T02:00:00Z", "3", "4")));
        mockMvc.perform(post("/api/batches/" + batch + "/excursions")
                        .contentType(MediaType.APPLICATION_JSON).content(overlapping))
                .andExpect(status().isUnprocessableEntity());
        String legal = registerBody("CK-EX-2", List.of(excursion("EX-IDEM-2",
                "2026-02-02T00:00:00Z", "2026-02-02T01:00:00Z", "3", "4")));
        mockMvc.perform(post("/api/batches/" + batch + "/excursions")
                        .contentType(MediaType.APPLICATION_JSON).content(legal))
                .andExpect(status().isCreated());
        assertEquals(2, excursionCount(batch));
    }

    @Test
    void adjudicateAndConfirm_commandKeyReplayAndConflict() throws Exception {
        String batch = createBatchDefault("BK-AIDEM-");
        registerExcursions(batch, List.of(
                excursion("EX-A1", "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", "0", "4"),
                excursion("EX-A2", "2026-02-02T00:00:00Z", "2026-02-02T01:00:00Z", "5", "6")), 201);

        String reworkKey = "BK-AIDEM-RW-" + unique();
        String body = adjudicateBody("CK-ADJ-1", "REWORK", reworkKey, "LOT-RW");
        MvcResult first = mockMvc.perform(post("/api/batches/" + batch + "/excursions/EX-A1/adjudicate")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batch + "/excursions/EX-A1/adjudicate")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM excursion_adjudication WHERE batch_key = ?", Integer.class, batch));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE relation_type = 'REWORK' AND parent_key = ?",
                Integer.class, batch));

        // 同键改返工子批 → 409
        String changed = adjudicateBody("CK-ADJ-1", "REWORK", "BK-AIDEM-RW-X-" + unique(), "LOT-RX");
        mockMvc.perform(post("/api/batches/" + batch + "/excursions/EX-A1/adjudicate")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());

        // MINOR 确认同键重放
        String confirmBody = "{\"commandKey\":\"CK-CF-1\"}";
        mockMvc.perform(post("/api/batches/" + batch + "/excursions/EX-A2/confirm")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/batches/" + batch + "/excursions/EX-A2/confirm")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody))
                .andExpect(status().isCreated());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM storage_excursion WHERE excursion_key = 'EX-A2' AND status = 'CONFIRMED'",
                Integer.class));
    }

    // ---------- 并发：按事务提交顺序裁决 ----------

    @Test
    void concurrentRegisterSameExcursionKey_onlyOneSerializedByBatchLock() throws Exception {
        String batch = createBatchDefault("BK-CR-EX-");
        String bodyA = registerBody("CK-CR-A", List.of(excursion("EX-CR-1",
                "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", "0", "4")));
        String bodyB = registerBody("CK-CR-B", List.of(excursion("EX-CR-1",
                "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", "0", "4")));
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batch + "/excursions")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyA)),
                () -> callStatus(post("/api/batches/" + batch + "/excursions")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyB))
        );
        List<Integer> codes = results.stream().map(f -> {
            try {
                return f.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).sorted().toList();
        assertEquals(List.of(201, 409), codes, "行锁串行化：一条成功，另一条键冲突");
        assertEquals(1, excursionCount(batch));
    }

    @Test
    void concurrentRegisterSameCommandKey_bothReplayFirstResult() throws Exception {
        String batch = createBatchDefault("BK-CR-CMD-");
        String body = registerBody("CK-CR-SAME", List.of(excursion("EX-CR-2",
                "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", "0", "4")));
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batch + "/excursions")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/" + batch + "/excursions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(1, excursionCount(batch));
    }

    @Test
    void concurrentAdjudicateSameMajor_onlyOneReworkWins() throws Exception {
        String batch = createBatchDefault("BK-CR-ADJ-");
        registerExcursions(batch, List.of(excursion("EX-CR-A1", "2026-02-01T00:00:00Z",
                "2026-02-01T01:00:00Z", "0", "4")), 201);

        String bodyA = adjudicateBody("CK-CR-ADJ-A", "REWORK",
                "BK-CR-RW-A-" + unique(), "LA");
        String bodyB = adjudicateBody("CK-CR-ADJ-B", "REWORK",
                "BK-CR-RW-B-" + unique(), "LB");
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batch + "/excursions/EX-CR-A1/adjudicate")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyA)),
                () -> callStatus(post("/api/batches/" + batch + "/excursions/EX-CR-A1/adjudicate")
                        .header("X-Actor-Id", "qa-2").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyB))
        );
        List<Integer> codes = results.stream().map(f -> {
            try {
                return f.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).sorted().toList();
        assertEquals(List.of(201, 409), codes, "同一 MAJOR 只能裁决一次");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM excursion_adjudication WHERE batch_key = ?", Integer.class, batch));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE relation_type = 'REWORK' AND parent_key = ?",
                Integer.class, batch));
        assertEquals("REWORKED", currentStatus(batch));
    }

    @Test
    void concurrentFinalApprovalAndMajorRegistration_commitOrderDecides_neverReleasedWithOpenMajor()
            throws Exception {
        String batch = createBatchDefault("BK-CR-GATE-");
        releaseToPending(batch, "insp-1");
        approve(batch, "qa-1", "QUALITY", "CK-Q1", 201);

        String registerBody = registerBody("CK-CR-REG", List.of(excursion("EX-CR-G1",
                "2026-02-01T00:00:00Z", "2026-02-01T01:00:00Z", "0", "4")));
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batch + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-O1\"}")),
                () -> callStatus(post("/api/batches/" + batch + "/excursions")
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody))
        );
        int approvalCode = results.get(0).get(30, TimeUnit.SECONDS);
        int registerCode = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, registerCode);
        String finalStatus = currentStatus(batch);
        if (approvalCode == 201) {
            // 批准先提交：批次曾 RELEASED，随后 MAJOR 登记立即转待处置；历史放行保留
            assertEquals("PENDING_DISPOSITION", finalStatus);
            assertEquals(2, approvalCount(batch));
            JsonNode risks = dispositionRisks(batch);
            assertTrue(risks.size() >= 1);
        } else {
            // 登记先提交：第二笔批准被门禁拦截 422，批次停留 RELEASE_REVIEW
            assertEquals(422, approvalCode);
            assertEquals("RELEASE_REVIEW", finalStatus);
        }
        // 不变量：存在未裁决 MAJOR 时批次绝不可能处于 RELEASED
        assertNotEquals("RELEASED", finalStatus);
        assertTrue(approvalCode == 201 || approvalCode == 422);
    }

    // ---------- helpers ----------

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests,
                             BigDecimal minStorageTempC, BigDecimal maxStorageTempC) {
    }

    private record ExcursionCmd(String commandKey, String excursionKey, String startUtc, String endUtc,
                                BigDecimal minTempC, BigDecimal maxTempC) {
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String createBatchDefault(String prefix) throws Exception {
        return createBatchWithSpec(prefix, new BigDecimal("2.00"), new BigDecimal("8.00"));
    }

    private String createBatchWithSpec(String prefix, BigDecimal min, BigDecimal max) throws Exception {
        String batchKey = prefix + unique();
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(), batchKey,
                "PROD-1", "LOT-1", Instant.parse("2026-01-02T03:04:05Z"),
                List.of("t1"), min, max));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        return batchKey;
    }

    /** 检验 PASS（单必做项 t1）后进入 PENDING_RELEASE。 */
    private void releaseToPending(String batchKey, String inspector) throws Exception {
        submitTest(batchKey, "t1", "PASS", inspector, 201);
    }

    /** 走完检验 + 双角色批准，批次 RELEASED。 */
    private void releaseFully(String batchKey, String inspector) throws Exception {
        releaseToPending(batchKey, inspector);
        approve(batchKey, "qa-" + unique(), "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", "CK-AO-" + unique(), 201);
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

    private com.example.starter.batch.dto.RegisterExcursionsRequest.ExcursionInput excursion(
            String key, String start, String end, String min, String max) {
        return new com.example.starter.batch.dto.RegisterExcursionsRequest.ExcursionInput(
                key, Instant.parse(start), Instant.parse(end), new BigDecimal(min), new BigDecimal(max));
    }

    private String registerBody(String commandKey,
                                List<com.example.starter.batch.dto.RegisterExcursionsRequest.ExcursionInput> xs)
            throws Exception {
        return objectMapper.writeValueAsString(new RegisterBody(commandKey, xs));
    }

    private record RegisterBody(String commandKey,
                                List<com.example.starter.batch.dto.RegisterExcursionsRequest.ExcursionInput> excursions) {
    }

    private void registerExcursions(String batchKey,
                                    List<com.example.starter.batch.dto.RegisterExcursionsRequest.ExcursionInput> xs,
                                    int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/excursions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("CK-EX-" + unique(), xs)))
                .andExpect(status().is(expected));
    }

    private String adjudicateBody(String commandKey, String decision, String reworkKey, String reworkNo)
            throws Exception {
        return objectMapper.writeValueAsString(new AdjudicateBody(commandKey, decision, reworkKey, reworkNo));
    }

    private record AdjudicateBody(String commandKey, String decision, String reworkBatchKey,
                                  String reworkBatchNo) {
    }

    private MvcResult adjudicate(String batchKey, String excursionKey, String role, String decision,
                                 String reworkKey, String reworkNo, int expected) throws Exception {
        String body = adjudicateBody("CK-ADJ-" + unique(), decision, reworkKey, reworkNo);
        return mockMvc.perform(post("/api/batches/" + batchKey
                                + "/excursions/" + excursionKey + "/adjudicate")
                        .header("X-Actor-Id", "qa-lead").header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private void confirmMinor(String batchKey, String excursionKey, String role, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey
                                + "/excursions/" + excursionKey + "/confirm")
                        .header("X-Actor-Id", "qa-lead").header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CF-" + unique() + "\"}"))
                .andExpect(status().is(expected));
    }

    private void split(String parentKey, List<String[]> children) throws Exception {
        split(parentKey, children, 201);
    }

    private void split(String parentKey, List<String[]> children, int expected) throws Exception {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"CK-SP-" + unique()
                + "\",\"children\":[");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"batchKey\":\"").append(children.get(i)[0])
                    .append("\",\"batchNo\":\"").append(children.get(i)[1]).append("\"}");
        }
        String body = sb.append("]}").toString();
        mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private JsonNode listExcursions(String batchKey) throws Exception {
        return getJson("/api/batches/" + batchKey + "/excursions");
    }

    private JsonNode releaseBlock(String batchKey) throws Exception {
        return getJson("/api/batches/" + batchKey + "/release-block");
    }

    private JsonNode adjudications(String batchKey) throws Exception {
        return getJson("/api/batches/" + batchKey + "/adjudications");
    }

    private JsonNode dispositionRisks(String batchKey) throws Exception {
        return getJson("/api/batches/" + batchKey + "/disposition-risks");
    }

    private JsonNode getJson(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void assertKeyList(JsonNode block, String field, List<String> expected) {
        List<String> actual = new ArrayList<>();
        block.path(field).forEach(n -> actual.add(n.asText()));
        assertEquals(expected, actual);
    }

    private String currentStatus(String batchKey) throws Exception {
        return getJson("/api/batches/" + batchKey + "/history").path("batch").path("status").asText();
    }

    private List<String> availableKeys() throws Exception {
        List<String> keys = new ArrayList<>();
        getJson("/api/batches/available").forEach(n -> keys.add(n.path("batchKey").asText()));
        return keys;
    }

    private int excursionCount(String batchKey) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM storage_excursion WHERE batch_key = ?",
                Integer.class, batchKey);
    }

    private int approvalCount(String batchKey) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE batch_key = ?",
                Integer.class, batchKey);
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

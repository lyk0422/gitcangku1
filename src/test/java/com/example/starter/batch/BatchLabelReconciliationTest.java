package com.example.starter.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * 批次标签核销端到端测试（真实 H2 内存库 MODE=MySQL，非 mock）：
 * 计划登记、批量封箱完整集合校验与整笔回滚、作废释放与版本、放行核销闸门与快照固化、
 * 快照不可改写、差异诊断、UTC 左闭右开时间窗（可控时钟）、并发同标签唯一成功与裁决、commandKey 幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchLabelReconciliationTest {

    /**
     * 可控 UTC 时钟：测试主动推进时间，不依赖墙钟与睡眠。
     */
    static class MutableClock extends Clock {
        private volatile Instant instant = Instant.parse("2026-03-10T08:00:00Z");

        void setInstant(Instant t) {
            this.instant = t;
        }

        void advance(Duration d) {
            this.instant = instant.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        public MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM release_snapshot_seal");
        jdbc.update("DELETE FROM release_snapshot");
        jdbc.update("DELETE FROM label_binding");
        jdbc.update("DELETE FROM box_seal_active");
        jdbc.update("DELETE FROM box_seal_event");
        jdbc.update("DELETE FROM packing_plan");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
        clock.setInstant(Instant.parse("2026-03-10T08:00:00Z"));
    }

    // ---------- 主流程 ----------

    @Test
    void happyFlow_registerSealRelease_freezesSnapshot() throws Exception {
        String batchKey = "BK-LBL-H-" + unique();
        createReleasedBatch(batchKey, 100, 1001, 1003,
                List.of(new long[]{1001, 30}, new long[]{1002, 30}, new long[]{1003, 40}),
                List.of("S1", "S2", "S3"));

        MvcResult packingResult = mockMvc.perform(get("/api/batches/" + batchKey + "/packing"))
                .andExpect(status().isOk()).andReturn();
        JsonNode packing = objectMapper.readTree(packingResult.getResponse().getContentAsString());
        assertEquals(100, packing.path("plan").path("plannedQuantity").asLong());
        assertEquals(100, packing.path("plan").path("sealedQuantity").asLong());
        assertEquals(0, packing.path("plan").path("remainingQuantity").asLong());
        assertEquals(3, packing.path("plan").path("usedLabelCount").asLong());
        assertEquals(3, packing.path("activeSeals").size());

        JsonNode snapshot = packing.path("snapshot");
        assertFalse(snapshot.isMissingNode());
        assertEquals(100, snapshot.path("plannedQuantity").asLong());
        assertEquals(100, snapshot.path("sealedQuantity").asLong());
        assertEquals(0, snapshot.path("quantityDifference").asLong());
        assertEquals(3, snapshot.path("usedLabelCount").asLong());
        assertEquals(3, snapshot.path("sealCount").asLong());
        // 规范排序摘要：数值升序
        assertEquals("[1001,1002,1003]", snapshot.path("usedLabels").toString());
        // 每个封箱版本固化，按标签号升序
        assertEquals("S1", snapshot.path("seals").get(0).path("sealKey").asText());
        assertEquals(1, snapshot.path("seals").get(0).path("version").asInt());
        assertEquals(30, snapshot.path("seals").get(0).path("quantity").asLong());
        assertEquals("S3", snapshot.path("seals").get(2).path("sealKey").asText());
        assertEquals(40, snapshot.path("seals").get(2).path("quantity").asLong());
        assertTrue(packing.path("diagnostic").path("reconciled").asBoolean());
        assertTrue(packing.path("diagnostic").path("snapshotConsistent").asBoolean());
    }

    @Test
    void partialSealing_diagnosticReportsActualRequiredAndDifference() throws Exception {
        String batchKey = "BK-LBL-DIAG-" + unique();
        createBatch(batchKey);
        registerPlan(batchKey, 100, 1001, 1003, "CK-RP", 201);
        seal(batchKey, List.of(new Box("S1", 1001, 30), new Box("S2", 1002, 30)), "CK-S1", 201);

        JsonNode diag = diagnostic(batchKey);
        assertFalse(diag.path("reconciled").asBoolean());
        assertEquals(60, diag.path("sealedQuantity").asLong());
        assertEquals(100, diag.path("plannedQuantity").asLong());
        assertEquals(-40, diag.path("quantityDifference").asLong());
        assertEquals(2, diag.path("activeSealCount").asLong());
        assertEquals(2, diag.path("usedLabelCount").asLong());
        assertEquals(0, diag.path("labelSealDifference").asLong());
        assertTrue(diag.path("snapshotConsistent").isNull());
    }

    // ---------- 失败分支与整笔回滚 ----------

    @Test
    void outOfRangeLabel_entireBatchRollsBack_noPartialOccupation() throws Exception {
        String batchKey = "BK-LBL-OOR-" + unique();
        createBatch(batchKey);
        registerPlan(batchKey, 100, 1001, 1003, "CK-RP", 201);

        // 一条合法、一条越界：先校验完整最终集合，整次回滚
        MvcResult result = seal(batchKey, List.of(new Box("S1", 1001, 30), new Box("S2", 9999, 30)),
                "CK-SBAD", 422);
        assertMessageContains(result, "标签号越界");

        assertNoSealData(batchKey, new long[]{1001, 9999});
        JsonNode plan = packing(batchKey).path("plan");
        assertEquals(0, plan.path("sealedQuantity").asLong());
        assertEquals(100, plan.path("remainingQuantity").asLong());
    }

    @Test
    void duplicateLabelInRequest_andAcrossRequests_rejectedAndRolledBack() throws Exception {
        String batchKey = "BK-LBL-DUP-" + unique();
        createBatch(batchKey);
        registerPlan(batchKey, 100, 1001, 1003, "CK-RP", 201);

        seal(batchKey, List.of(new Box("S1", 1001, 10)), "CK-OK", 201);

        // 同请求内标签重复 →422，整次回滚
        seal(batchKey, List.of(new Box("S2", 1002, 10), new Box("S3", 1002, 10)),
                "CK-DUP1", 422);
        // 跨请求重复占用标签 →422
        seal(batchKey, List.of(new Box("S4", 1001, 10)), "CK-DUP2", 422);
        // 重复 sealKey →409
        seal(batchKey, List.of(new Box("S1", 1002, 10)), "CK-DUP3", 409);

        assertEquals(1L, count("SELECT COUNT(*) FROM box_seal_active WHERE batch_key = ?", batchKey));
        assertEquals(1L, count("SELECT COUNT(*) FROM box_seal_event WHERE batch_key = ?", batchKey));
    }

    @Test
    void quantityExceedsPlanned_reportsActualRequiredAndDifference_andRollsBack() throws Exception {
        String batchKey = "BK-LBL-OVER-" + unique();
        createBatch(batchKey);
        registerPlan(batchKey, 100, 1001, 1003, "CK-RP", 201);
        seal(batchKey, List.of(new Box("S1", 1001, 60)), "CK-S1", 201);

        MvcResult result = seal(batchKey,
                List.of(new Box("S2", 1002, 30), new Box("S3", 1003, 20)), "CK-SOVER", 422);
        assertMessageContains(result, "超过计划数量");
        assertMessageContains(result, "110");
        assertMessageContains(result, "超出 10");

        // 仅第一次封箱保留，整次失败未留部分占用
        assertEquals(60L, count("SELECT COALESCE(SUM(quantity),0) FROM box_seal_active WHERE batch_key = ?",
                batchKey));
        assertEquals(1L, count("SELECT COUNT(*) FROM box_seal_event WHERE batch_key = ?", batchKey));
    }

    @Test
    void invertedRange_returns400_andRepeatedRegisterConflicts() throws Exception {
        String batchKey = "BK-LBL-CAP-" + unique();
        createBatch(batchKey);
        // 号段终点小于起点 →400
        String inverted = "{\"commandKey\":\"CK-RP0\",\"plannedQuantity\":100,"
                + "\"labelRangeStart\":1002,\"labelRangeEnd\":1001}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/packing-plan")
                        .contentType(MediaType.APPLICATION_JSON).content(inverted))
                .andExpect(status().isBadRequest());
        // 号段容量可小于计划数量（一标签一封箱、每箱数量为正整数）：2 个标签各 50 件即可完成 100 件
        registerPlan(batchKey, 100, 1001, 1002, "CK-RP", 201);
        // 重复登记 →409
        registerPlan(batchKey, 100, 1001, 1002, "CK-RP2", 409);
        assertEquals(1L, count("SELECT COUNT(*) FROM packing_plan WHERE batch_key = ?", batchKey));
    }

    @Test
    void sealWithoutPlan_recalledOrReleasedBatch_rejected() throws Exception {
        String batchKey = "BK-LBL-STATE-" + unique();
        createBatch(batchKey);
        // 未登记计划封箱 →422
        seal(batchKey, List.of(new Box("S1", 1001, 1)), "CK-S0", 422);

        registerPlan(batchKey, 100, 1001, 1003, "CK-RP", 201);
        seal(batchKey, List.of(new Box("S1", 1001, 100)), "CK-S1", 201);
        releaseBatch(batchKey);

        // 已放行封箱 →409；已放行登记计划 →409
        seal(batchKey, List.of(new Box("S2", 1002, 1)), "CK-S2", 409);
        registerPlan(batchKey, 100, 2001, 2003, "CK-RP2", 409);
    }

    @Test
    void sameLabelAcrossBatches_secondBatchRejected() throws Exception {
        String a = "BK-LBL-A-" + unique();
        String b = "BK-LBL-B-" + unique();
        createBatch(a);
        createBatch(b);
        registerPlan(a, 100, 5001, 5005, "CK-RA", 201);
        registerPlan(b, 100, 5001, 5005, "CK-RB", 201);
        seal(a, List.of(new Box("SA", 5001, 10)), "CK-SA", 201);
        MvcResult result = seal(b, List.of(new Box("SB", 5001, 10)), "CK-SB", 409);
        assertMessageContains(result, "已关联其他批次");
        assertEquals(a, jdbc.queryForObject(
                "SELECT batch_key FROM label_binding WHERE label_no = 5001", String.class));
    }

    @Test
    void invalidBodies_return400_andUnknownResources404() throws Exception {
        String batchKey = "BK-LBL-400-" + unique();
        createBatch(batchKey);
        // quantity 非正
        String badQty = "{\"commandKey\":\"c\",\"boxes\":[{\"sealKey\":\"s\",\"labelNo\":1,\"quantity\":0}]}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/seals")
                        .contentType(MediaType.APPLICATION_JSON).content(badQty))
                .andExpect(status().isBadRequest());
        // 空 boxes
        String emptyBoxes = "{\"commandKey\":\"c\",\"boxes\":[]}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/seals")
                        .contentType(MediaType.APPLICATION_JSON).content(emptyBoxes))
                .andExpect(status().isBadRequest());
        // 计划数量非正
        String badPlan = "{\"commandKey\":\"c\",\"plannedQuantity\":0,\"labelRangeStart\":1,\"labelRangeEnd\":2}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/packing-plan")
                        .contentType(MediaType.APPLICATION_JSON).content(badPlan))
                .andExpect(status().isBadRequest());
        // 作废原因为空
        mockMvc.perform(post("/api/batches/" + batchKey + "/seals/S1/void")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"c\",\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());

        // 未知批次
        mockMvc.perform(get("/api/batches/NO-SUCH/packing")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/batches/NO-SUCH/packing-plan")
                        .contentType(MediaType.APPLICATION_JSON).content(badPlan.replace("0", "1")))
                .andExpect(status().isNotFound());
    }

    // ---------- 放行闸门与快照 ----------

    @Test
    void releaseGate_unfinishedPacking_blocksSecondApproval_andNoSnapshot() throws Exception {
        String batchKey = "BK-LBL-GATE-" + unique();
        createBatch(batchKey);
        registerPlan(batchKey, 100, 1001, 1003, "CK-RP", 201);
        seal(batchKey, List.of(new Box("S1", 1001, 60)), "CK-S1", 201);
        passSingleTest(batchKey);
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 201);

        // 数量未完成：第二批准 422，状态停留 RELEASE_REVIEW，无快照、无第二批准
        MvcResult blocked = approve(batchKey, "ops-1", "OPERATIONS", "CK-A2", 422);
        assertMessageContains(blocked, "完成全部计划数量");
        assertMessageContains(blocked, "差额 40");
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
        assertEquals(0L, count("SELECT COUNT(*) FROM release_snapshot WHERE batch_key = ?", batchKey));
        assertEquals(1L, count("SELECT COUNT(*) FROM approval WHERE batch_key = ?", batchKey));

        // 补齐后放行成功并固化快照
        seal(batchKey, List.of(new Box("S2", 1002, 40)), "CK-S2", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
        assertEquals(1L, count("SELECT COUNT(*) FROM release_snapshot WHERE batch_key = ?", batchKey));
        assertEquals(2L, count("SELECT COUNT(*) FROM release_snapshot_seal WHERE batch_key = ?", batchKey));
    }

    @Test
    void snapshot_immutableAfterRelease_voidAndSealRejected_historyKept() throws Exception {
        String batchKey = "BK-LBL-IMMUT-" + unique();
        createReleasedBatch(batchKey, 100, 1001, 1002,
                List.of(new long[]{1001, 40}, new long[]{1002, 60}), List.of("S1", "S2"));

        JsonNode before = packing(batchKey).path("snapshot");

        mockMvc.perform(post("/api/batches/" + batchKey + "/seals/S1/void")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-V1\",\"reason\":\"发现破损\"}"))
                .andExpect(status().isConflict());
        seal(batchKey, List.of(new Box("S3", 1003, 1)), "CK-S3", 409);

        JsonNode after = packing(batchKey).path("snapshot");
        assertEquals(before.toString(), after.toString(), "放行快照不得被后续作废申请改写");
        // 活跃占用与封箱历史也未变
        assertEquals(2L, count("SELECT COUNT(*) FROM box_seal_active WHERE batch_key = ?", batchKey));
        assertEquals(2L, count("SELECT COUNT(*) FROM box_seal_event WHERE batch_key = ?", batchKey));
        // 历史证据完整可查
        JsonNode history = history(batchKey);
        assertEquals(2, history.path("packing").path("sealHistory").size());
        assertEquals(2, history.path("approvals").size());
    }

    @Test
    void releaseSnapshotDiff_detectedByDiagnostic_forRecallFlow() throws Exception {
        String batchKey = "BK-LBL-DIFF-" + unique();
        createReleasedBatch(batchKey, 100, 1001, 1003,
                List.of(new long[]{1001, 30}, new long[]{1002, 30}, new long[]{1003, 40}),
                List.of("S1", "S2", "S3"));

        // 模拟放行后发现标签差异（只读诊断基于快照对比；业务接口不允许制造差异），
        // 直接在库层面构造活跃标签集合变化，验证诊断能定位缺失/多余标签作为召回依据。
        jdbc.update("DELETE FROM box_seal_active WHERE batch_key = ? AND label_no = 1002", batchKey);
        jdbc.update("INSERT INTO box_seal_active (batch_key, label_no, seal_key, version, quantity, sealed_at)"
                + " VALUES (?, 1004, 'SX', 1, 30, ?)", batchKey, clock.instant().toString());

        JsonNode diag = diagnostic(batchKey);
        assertFalse(diag.path("reconciled").asBoolean());
        assertFalse(diag.path("snapshotConsistent").asBoolean());
        assertEquals("[1002]", diag.path("missingAgainstSnapshot").toString());
        assertEquals("[1004]", diag.path("extraAgainstSnapshot").toString());
        // 快照本身保持原值
        JsonNode snapshot = packing(batchKey).path("snapshot");
        assertEquals("[1001,1002,1003]", snapshot.path("usedLabels").toString());
        assertEquals(100, snapshot.path("sealedQuantity").asLong());
    }

    // ---------- 作废释放与版本 ----------

    @Test
    void voidSeal_releasesQuantityAndLabel_resealBumpsVersion_historyKept() throws Exception {
        String batchKey = "BK-LBL-VOID-" + unique();
        createBatch(batchKey);
        registerPlan(batchKey, 100, 1001, 1003, "CK-RP", 201);
        seal(batchKey, List.of(new Box("S1", 1001, 60)), "CK-S1", 201);

        Instant voidAt = clock.instant().plus(Duration.ofMinutes(30));
        clock.setInstant(voidAt);
        MvcResult voidResult = mockMvc.perform(post("/api/batches/" + batchKey + "/seals/S1/void")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-V1\",\"reason\":\"纸箱破损\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode voidBody = objectMapper.readTree(voidResult.getResponse().getContentAsString());
        assertEquals(0, voidBody.path("sealedQuantity").asLong());
        assertEquals(0, voidBody.path("usedLabelCount").asLong());
        assertEquals(1, voidBody.path("version").asInt());
        assertEquals("2026-03-10T08:30:00Z", voidBody.path("voidedAt").asText());

        // 未重封时重复作废（新 commandKey）→409；不存在的封箱作废 →404
        mockMvc.perform(post("/api/batches/" + batchKey + "/seals/S1/void")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-V2\",\"reason\":\"未重封重复作废\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/batches/" + batchKey + "/seals/NO-SUCH/void")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-VX\",\"reason\":\"r\"}"))
                .andExpect(status().isNotFound());

        // 作废后标签与数量释放：可重新封箱，同 sealKey 版本递增为 2，标签 1001 可再次使用
        clock.advance(Duration.ofMinutes(30));
        seal(batchKey, List.of(new Box("S1", 1001, 40)), "CK-S2", 201);
        JsonNode detail = packing(batchKey);
        assertEquals(40, detail.path("plan").path("sealedQuantity").asLong());
        assertEquals(60, detail.path("plan").path("remainingQuantity").asLong());
        JsonNode historyEvents = detail.path("sealHistory");
        assertEquals(2, historyEvents.size());
        assertEquals(1, historyEvents.get(0).path("version").asInt());
        assertEquals("VOIDED", historyEvents.get(0).path("status").asText());
        assertEquals("纸箱破损", historyEvents.get(0).path("voidReason").asText());
        assertFalse(historyEvents.get(0).path("sealedAt").isNull());
        assertEquals(2, historyEvents.get(1).path("version").asInt());
        assertEquals("SEALED", historyEvents.get(1).path("status").asText());
        assertTrue(historyEvents.get(1).path("voidedAt").isNull());
        // 未作废封箱 voidReason/voidedAt 为 null（null 含义稳定）
        assertTrue(historyEvents.get(1).path("voidReason").isNull());

        // 重封后版本 2 为活跃版本，可用新 commandKey 再次作废；作废只更新 v2 行不新增行，
        // 历史仍为 2 个版本（v1、v2），最终均 VOIDED，原因分别保留
        MvcResult secondVoid = mockMvc.perform(post("/api/batches/" + batchKey + "/seals/S1/void")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-V3\",\"reason\":\"复检异常\"}"))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(0, objectMapper.readTree(secondVoid.getResponse().getContentAsString())
                .path("sealedQuantity").asLong());
        JsonNode detail2 = packing(batchKey);
        assertEquals(2, detail2.path("sealHistory").size());
        assertEquals(1, detail2.path("sealHistory").get(0).path("version").asInt());
        assertEquals("VOIDED", detail2.path("sealHistory").get(0).path("status").asText());
        assertEquals("纸箱破损", detail2.path("sealHistory").get(0).path("voidReason").asText());
        assertEquals(2, detail2.path("sealHistory").get(1).path("version").asInt());
        assertEquals("VOIDED", detail2.path("sealHistory").get(1).path("status").asText());
        assertEquals("复检异常", detail2.path("sealHistory").get(1).path("voidReason").asText());
        assertEquals(0, detail2.path("activeSeals").size());
        assertEquals(0, detail2.path("plan").path("sealedQuantity").asLong());
    }

    // ---------- 幂等 ----------

    @Test
    void commandIdempotency_replaySameParams_changedParamsConflict_failureDoesNotOccupyKey()
            throws Exception {
        String batchKey = "BK-LBL-IDEM-" + unique();
        createBatch(batchKey);

        String planBody = "{\"commandKey\":\"CK-RP\",\"plannedQuantity\":100,"
                + "\"labelRangeStart\":1001,\"labelRangeEnd\":1003}";
        MvcResult p1 = mockMvc.perform(post("/api/batches/" + batchKey + "/packing-plan")
                        .contentType(MediaType.APPLICATION_JSON).content(planBody))
                .andExpect(status().isCreated()).andReturn();
        MvcResult p2 = mockMvc.perform(post("/api/batches/" + batchKey + "/packing-plan")
                        .contentType(MediaType.APPLICATION_JSON).content(planBody))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(p1.getResponse().getContentAsString(), p2.getResponse().getContentAsString());
        // 同键改参 →409
        mockMvc.perform(post("/api/batches/" + batchKey + "/packing-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody.replace("1003", "1009")))
                .andExpect(status().isConflict());
        assertEquals(1L, count("SELECT COUNT(*) FROM packing_plan WHERE batch_key = ?", batchKey));

        // 失败不占键：CK-S 先用于越界失败，再以同键合法提交成功
        seal(batchKey, List.of(new Box("S1", 9999, 10)), "CK-S", 422);
        seal(batchKey, List.of(new Box("S1", 1001, 10)), "CK-S", 201);
        // 同参重放返回首次结果且不新增数据
        seal(batchKey, List.of(new Box("S1", 1001, 10)), "CK-S", 201);
        assertEquals(1L, count("SELECT COUNT(*) FROM box_seal_event WHERE batch_key = ?", batchKey));
        // 同键异参 →409
        seal(batchKey, List.of(new Box("S2", 1002, 20)), "CK-S", 409);
    }

    @Test
    void voidCommand_replayReturnsFirstResult_changedReasonConflicts() throws Exception {
        String batchKey = "BK-LBL-VIDEM-" + unique();
        createBatch(batchKey);
        registerPlan(batchKey, 100, 1001, 1003, "CK-RP", 201);
        seal(batchKey, List.of(new Box("S1", 1001, 60)), "CK-S1", 201);

        String body = "{\"commandKey\":\"CK-V1\",\"reason\":\"原因一\"}";
        MvcResult first = mockMvc.perform(post("/api/batches/" + batchKey + "/seals/S1/void")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batchKey + "/seals/S1/void")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        mockMvc.perform(post("/api/batches/" + batchKey + "/seals/S1/void")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-V1\",\"reason\":\"原因二\"}"))
                .andExpect(status().isConflict());
        assertEquals(1L, count("SELECT COUNT(*) FROM box_seal_event WHERE status = 'VOIDED'"
                + " AND batch_key = ?", batchKey));
    }

    // ---------- 并发 ----------

    @Test
    void concurrentSameLabel_sameBatch_onlyOneSucceeds() throws Exception {
        String batchKey = "BK-LBL-RACE1-" + unique();
        createBatch(batchKey);
        registerPlan(batchKey, 100, 3001, 3005, "CK-RP", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(sealRequest(batchKey,
                        List.of(new Box("SA", 3001, 10)), "CK-RA")),
                () -> callStatus(sealRequest(batchKey,
                        List.of(new Box("SB", 3001, 10)), "CK-RB"))
        );
        int ok = 0;
        int rejected = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                ok++;
            } else if (code == 409 || code == 422) {
                rejected++;
            } else {
                fail("意外状态码: " + code);
            }
        }
        assertEquals(1, ok);
        assertEquals(1, rejected);
        assertEquals(1L, count("SELECT COUNT(*) FROM box_seal_active WHERE batch_key = ? AND label_no = 3001",
                batchKey));
        assertEquals(1L, count("SELECT COUNT(*) FROM label_binding WHERE label_no = 3001"));
    }

    @Test
    void concurrentSameLabel_acrossBatches_databaseUniqueBindingAllowsOne() throws Exception {
        String a = "BK-LBL-XA-" + unique();
        String b = "BK-LBL-XB-" + unique();
        createBatch(a);
        createBatch(b);
        registerPlan(a, 100, 4001, 4005, "CK-RA", 201);
        registerPlan(b, 100, 4001, 4005, "CK-RB", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(sealRequest(a, List.of(new Box("SA", 4001, 10)), "CK-SA")),
                () -> callStatus(sealRequest(b, List.of(new Box("SB", 4001, 10)), "CK-SB"))
        );
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                ok++;
            } else if (code == 409) {
                conflict++;
            } else {
                fail("意外状态码: " + code);
            }
        }
        assertEquals(1, ok, "数据库唯一约束保证同一标签最多一次成功");
        assertEquals(1, conflict);
        // 负者整笔回滚：只有赢者批次留下事件/占用/绑定
        assertEquals(1L, count("SELECT COUNT(*) FROM label_binding WHERE label_no = 4001"));
        assertEquals(1L, count("SELECT COUNT(*) FROM box_seal_active WHERE label_no = 4001"));
        assertEquals(1L, count("SELECT COUNT(*) FROM box_seal_event WHERE label_no = 4001"));
    }

    @Test
    void concurrentSealCompletionAndFinalApproval_adjucatedByCommitOrder() throws Exception {
        String batchKey = "BK-LBL-RACE2-" + unique();
        createBatch(batchKey);
        registerPlan(batchKey, 100, 1001, 1003, "CK-RP", 201);
        seal(batchKey, List.of(new Box("S1", 1001, 60)), "CK-S1", 201);
        passSingleTest(batchKey);
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(sealRequest(batchKey,
                        List.of(new Box("S2", 1002, 40)), "CK-S2")),
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-A2\"}"))
        );

        int sealCode = results.get(0).get(30, TimeUnit.SECONDS);
        int approvalCode = results.get(1).get(30, TimeUnit.SECONDS);
        String finalStatus = currentStatus(batchKey);
        long snapshotCount = count("SELECT COUNT(*) FROM release_snapshot WHERE batch_key = ?", batchKey);
        if ("RELEASED".equals(finalStatus)) {
            // 补齐封箱先提交，第二批准随后通过
            assertEquals(201, sealCode);
            assertEquals(201, approvalCode);
            assertEquals(1L, snapshotCount);
        } else {
            // 第二批准先提交时数量未完成被拒；封箱随后成功，停留 RELEASE_REVIEW
            assertEquals("RELEASE_REVIEW", finalStatus);
            assertEquals(422, approvalCode);
            assertEquals(201, sealCode);
            assertEquals(0L, snapshotCount);
        }
        // 无论裁决顺序如何，封箱最终数据完整且无半成品
        assertEquals(100L, count("SELECT COALESCE(SUM(quantity),0) FROM box_seal_active WHERE batch_key = ?",
                batchKey));
    }

    // ---------- UTC 左闭右开时间窗与只读语义 ----------

    @Test
    void packingWindow_leftClosedRightOpen_controlledClock_reportsDurationAndActuals() throws Exception {
        String batchKey = "BK-LBL-WIN-" + unique();
        createBatch(batchKey);
        registerPlan(batchKey, 100, 1001, 1003, "CK-RP", 201);

        Instant t1 = clock.instant();
        seal(batchKey, List.of(new Box("S1", 1001, 30)), "CK-S1", 201);
        clock.advance(Duration.ofHours(1));
        Instant t2 = clock.instant();
        seal(batchKey, List.of(new Box("S2", 1002, 30)), "CK-S2", 201);
        clock.advance(Duration.ofHours(1));
        Instant t3 = clock.instant();

        // [t1, t2)：左闭含 t1 的封箱，右开不含 t2 的封箱，时长 3600 秒（实际值）
        JsonNode packing = packing(batchKey, t1, t2);
        JsonNode window = packing.path("window");
        assertEquals(1, window.path("sealEventCount").asInt());
        assertEquals(30, window.path("sealedQuantity").asLong());
        assertEquals(3600, window.path("durationSeconds").asLong());

        // [t2, t3) 只含第二批
        JsonNode window2 = packing(batchKey, t2, t3).path("window");
        assertEquals(1, window2.path("sealEventCount").asInt());
        assertEquals(30, window2.path("sealedQuantity").asLong());

        // 全区间 [t1, t3) 两批都含
        JsonNode windowAll = packing(batchKey, t1, t3).path("window");
        assertEquals(2, windowAll.path("sealEventCount").asInt());
        assertEquals(60, windowAll.path("sealedQuantity").asLong());
        assertEquals(7200, windowAll.path("durationSeconds").asLong());

        // 非法窗口 →400
        mockMvc.perform(get("/api/batches/" + batchKey + "/packing")
                        .param("from", t3.toString()).param("to", t1.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readsDoNotChangeState_andHistoryNullsStable() throws Exception {
        String batchKey = "BK-LBL-READ-" + unique();
        createBatch(batchKey);
        registerPlan(batchKey, 100, 1001, 1003, "CK-RP", 201);
        seal(batchKey, List.of(new Box("S1", 1001, 30)), "CK-S1", 201);

        long eventsBefore = count("SELECT COUNT(*) FROM box_seal_event WHERE batch_key = ?", batchKey);
        JsonNode h1 = history(batchKey);
        JsonNode h2 = history(batchKey);
        JsonNode d1 = packing(batchKey);
        JsonNode d2 = packing(batchKey);
        assertEquals(h1.toString(), h2.toString());
        assertEquals(d1.toString(), d2.toString());
        assertEquals(eventsBefore,
                count("SELECT COUNT(*) FROM box_seal_event WHERE batch_key = ?", batchKey));

        // 未放行：snapshot 为 null；未作废封箱 voidReason/voidedAt 为 null
        assertTrue(d1.path("snapshot").isNull());
        assertTrue(d1.path("activeSeals").get(0).path("voidReason").isNull());
        assertTrue(d1.path("activeSeals").get(0).path("voidedAt").isNull());
        // 未登记计划的批次历史 packing 为 null
        String other = "BK-LBL-NOPLAN-" + unique();
        createBatch(other);
        assertTrue(history(other).path("packing").isNull());
    }

    // ---------- helpers ----------

    private record Box(String sealKey, long labelNo, long quantity) {
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createBatch(String batchKey) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "commandKey", "CK-C-" + unique(),
                "batchKey", batchKey,
                "productCode", "PROD-1",
                "batchNo", "LOT-" + batchKey.substring(batchKey.length() - 4),
                "producedAt", "2026-01-02T03:04:05Z",
                "requiredTests", List.of("无菌")));
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void passSingleTest(String batchKey) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "commandKey", "CK-T-" + unique(),
                "testKey", "TK-1",
                "testItem", "无菌",
                "result", "PASS",
                "inspector", "insp-1"));
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void registerPlan(String batchKey, long planned, long start, long end, String commandKey,
                              int expected) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "commandKey", commandKey,
                "plannedQuantity", planned,
                "labelRangeStart", start,
                "labelRangeEnd", end));
        mockMvc.perform(post("/api/batches/" + batchKey + "/packing-plan")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private MvcResult seal(String batchKey, List<Box> boxes, String commandKey, int expected)
            throws Exception {
        return mockMvc.perform(sealRequest(batchKey, boxes, commandKey))
                .andExpect(status().is(expected)).andReturn();
    }

    private MockHttpServletRequestBuilder sealRequest(String batchKey, List<Box> boxes,
                                                      String commandKey) throws Exception {
        List<java.util.Map<String, Object>> rawBoxes = new ArrayList<>();
        for (Box b : boxes) {
            rawBoxes.add(java.util.Map.of("sealKey", b.sealKey(), "labelNo", b.labelNo(),
                    "quantity", b.quantity()));
        }
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "commandKey", commandKey, "boxes", rawBoxes));
        return post("/api/batches/" + batchKey + "/seals")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MvcResult approve(String batchKey, String actor, String role, String commandKey,
                              int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    /**
     * 建批→登记计划→分批封箱（每批一个键）→检验通过→双角色放行；要求总数量恰好等于计划数量。
     */
    private void createReleasedBatch(String batchKey, long planned, long labelStart, long labelEnd,
                                     List<long[]> sealSpecs, List<String> sealKeys) throws Exception {
        createBatch(batchKey);
        registerPlan(batchKey, planned, labelStart, labelEnd, "CK-RP", 201);
        for (int i = 0; i < sealSpecs.size(); i++) {
            long[] spec = sealSpecs.get(i);
            seal(batchKey, List.of(new Box(sealKeys.get(i), spec[0], spec[1])),
                    "CK-S" + i, 201);
        }
        passSingleTest(batchKey);
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    private JsonNode packing(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/packing"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode packing(String batchKey, Instant from, Instant to) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/packing")
                        .param("from", from.toString()).param("to", to.toString()))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode diagnostic(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/labels/diagnostic"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode history(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String currentStatus(String batchKey) throws Exception {
        return history(batchKey).path("batch").path("status").asText();
    }

    private void releaseBatch(String batchKey) throws Exception {
        passSingleTest(batchKey);
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    private void assertNoSealData(String batchKey, long[] labels) {
        assertEquals(0L, count("SELECT COUNT(*) FROM box_seal_event WHERE batch_key = ?", batchKey));
        assertEquals(0L, count("SELECT COUNT(*) FROM box_seal_active WHERE batch_key = ?", batchKey));
        for (long label : labels) {
            assertEquals(0L, count("SELECT COUNT(*) FROM label_binding WHERE label_no = ?", label));
        }
    }

    private void assertMessageContains(MvcResult result, String fragment) {
        try {
            JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
            assertTrue(node.path("message").asText().contains(fragment),
                    "错误信息应包含 [" + fragment + "]，实际: " + node.path("message").asText());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long count(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0L : v;
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

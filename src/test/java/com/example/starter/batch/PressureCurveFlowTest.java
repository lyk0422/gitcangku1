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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 密封批次压力曲线 H2 事务集成测试：题给合成数据、门禁失败持久化、requestId 全局幂等、
 * 期望版本、拆批不继承、普通批次兼容，以及行锁串行下的真实并发裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PressureCurveFlowTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests, BatchType batchType) {
    }

    private record CurveCmd(Long expectedVersion, List<Point> points) {
    }

    private record Point(Long second, String pressureKpa) {
    }

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM pressure_curve_segment");
        jdbc.update("DELETE FROM pressure_curve_node");
        jdbc.update("DELETE FROM pressure_curve");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ---------- 主流程与题给合成数据 ----------

    @Test
    void syntheticCurve_firstLastDrop06_cumulative11_failsAndRejectsAndPersists() throws Exception {
        String batchKey = "BK-CURVE-FAIL-" + unique();
        createSealed(batchKey, List.of("t1"), 201);

        String requestId = "RID-FAIL-" + unique();
        String body = curveBody(0L, List.of(
                new Point(0L, "10"), new Point(5L, "9.5"), new Point(10L, "10"),
                new Point(15L, "9.4")));
        MvcResult result = mockMvc.perform(post("/api/batches/" + batchKey + "/pressure-curve")
                        .header("X-Request-Id", requestId).header("X-Actor-Id", "tech-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("FAIL", json.path("result").asText());
        assertEquals("REJECTED", json.path("batchStatus").asText());
        assertEquals(1.1, json.path("totalDeclineKpa").asDouble(), 1e-9);
        assertEquals(0.6, json.path("segments").get(2).path("declineKpa").asDouble(), 1e-9);
        assertEquals(0.12, json.path("segments").get(2).path("rateKpaPerSecond").asDouble(), 1e-9);
        assertEquals(1, json.path("batchVersion").asInt());
        // 节点顺序即提交身份，不重排
        assertEquals(0, json.path("points").get(0).path("second").asInt());
        assertEquals(15, json.path("points").get(3).path("second").asInt());
        assertEquals("9.4", json.path("points").get(3).path("pressureKpa").asText());

        assertEquals("REJECTED", currentStatus(batchKey));
        assertEquals(1, count("SELECT COUNT(*) FROM pressure_curve WHERE batch_key = ? AND outcome = 'FAIL'",
                batchKey));
        assertEquals(4, count("SELECT COUNT(*) FROM pressure_curve_node n JOIN pressure_curve c"
                + " ON n.curve_id = c.id WHERE c.batch_key = ?", batchKey));
        assertEquals(1, count("SELECT COUNT(*) FROM request_log WHERE request_id = ?", requestId));

        // 合法 FAIL 曲线保留，不能改成 PASS
        mockMvc.perform(post("/api/batches/" + batchKey + "/pressure-curve")
                        .header("X-Request-Id", "RID-RETRY-" + unique()).header("X-Actor-Id", "tech-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(curveBody(1L, List.of(new Point(0L, "10"), new Point(10L, "10")))))
                .andExpect(status().isConflict());
        JsonNode history = history(batchKey);
        assertEquals("FAIL", history.path("pressureCurve").path("result").asText());
        assertEquals("REJECTED", history.path("pressureCurve").path("batchStatus").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM pressure_curve WHERE batch_key = ?", batchKey));
    }

    @Test
    void curvePassAfterTests_qualityAndOperationsApprovalsRelease() throws Exception {
        String batchKey = "BK-CURVE-OK-" + unique();
        createSealed(batchKey, List.of("t1", "t2"), 201);

        // 先提交检验：密封批次缺曲线时停留 QUARANTINED
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp-a", 201);
        assertEquals("QUARANTINED", currentStatus(batchKey));
        approve(batchKey, "qa", "QUALITY", 422);

        // 去掉最后下降的合成曲线可 PASS：(0,10)(5,9.5)(10,10) 累计仅 0.5
        String requestId = "RID-OK-" + unique();
        MvcResult curve = mockMvc.perform(post("/api/batches/" + batchKey + "/pressure-curve")
                        .header("X-Request-Id", requestId).header("X-Actor-Id", "tech-9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(curveBody(1L, List.of(new Point(0L, "10"), new Point(5L, "9.5"),
                                new Point(10L, "10")))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode curveJson = objectMapper.readTree(curve.getResponse().getContentAsString());
        assertEquals("PASS", curveJson.path("result").asText());
        assertEquals("QUARANTINED", curveJson.path("batchStatus").asText(), "原检验未齐，仍隔离");

        submitTest(batchKey, "TK-2", "t2", "PASS", "insp-b", 201);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
        // 检验人与曲线提交人都不能担任批准人：用独立批准人完成两角色
        approve(batchKey, "qa-1", "QUALITY", 201);
        approve(batchKey, "ops-1", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 批准冻结：曲线与逐段速率原样可查
        JsonNode history = history(batchKey);
        assertEquals(0.5, history.path("pressureCurve").path("totalDeclineKpa").asDouble(), 1e-9);
        assertEquals(0.1, history.path("pressureCurve").path("segments").get(0)
                .path("rateKpaPerSecond").asDouble(), 1e-9);
        assertEquals(2, history.path("approvals").size());
    }

    // ---------- 曲线 PASS 在先、检验补齐在后 ----------

    @Test
    void curvePassFirst_thenLastTestMovesToPendingRelease() throws Exception {
        String batchKey = "BK-CURVE-FIRST-" + unique();
        createSealed(batchKey, List.of("t1"), 201);
        submitCurve(batchKey, "RID-" + unique(), "tech", 0L,
                List.of(new Point(0L, "10"), new Point(10L, "10")), "PASS", "QUARANTINED", 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp", 201);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
    }

    // ---------- 非法输入：400 且不留检验、不占 requestId ----------

    @Test
    void invalidCurve_returns400_leavesNoRecordAndRequestIdReusable() throws Exception {
        String batchKey = "BK-CURVE-BAD-" + unique();
        createSealed(batchKey, List.of("t1"), 201);
        String requestId = "RID-BAD-" + unique();

        // 逆序时刻：节点顺序属于身份，不能排序掩盖
        String reversed = curveBody(0L, List.of(new Point(0L, "10"), new Point(6L, "9"),
                new Point(5L, "9")));
        mockMvc.perform(post("/api/batches/" + batchKey + "/pressure-curve")
                        .header("X-Request-Id", requestId).header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON).content(reversed))
                .andExpect(status().isBadRequest());
        // 压力 4 位小数
        mockMvc.perform(post("/api/batches/" + batchKey + "/pressure-curve")
                        .header("X-Request-Id", requestId).header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(curveBody(0L, List.of(new Point(0L, "10"), new Point(10L, "9.1234")))))
                .andExpect(status().isBadRequest());
        // 非数字压力文本也必须是 400（不能因数值解析异常变成 500）
        mockMvc.perform(post("/api/batches/" + batchKey + "/pressure-curve")
                        .header("X-Request-Id", requestId).header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(curveBody(0L, List.of(new Point(0L, "10"), new Point(10L, "abc")))))
                .andExpect(status().isBadRequest());
        // 缺请求头
        mockMvc.perform(post("/api/batches/" + batchKey + "/pressure-curve")
                        .header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(curveBody(0L, List.of(new Point(0L, "10"), new Point(10L, "10")))))
                .andExpect(status().isBadRequest());

        assertEquals(0, count("SELECT COUNT(*) FROM pressure_curve WHERE batch_key = ?", batchKey));
        assertEquals(0, count("SELECT COUNT(*) FROM request_log WHERE request_id = ?", requestId),
                "失败不占键");
        // requestId 可被随后的合法提交首次占用
        submitCurve(batchKey, requestId, "tech", 0L,
                List.of(new Point(0L, "10"), new Point(10L, "10")), "PASS", "QUARANTINED", 201);
        assertEquals(1, count("SELECT COUNT(*) FROM request_log WHERE request_id = ?", requestId));
    }

    // ---------- requestId 全局幂等 ----------

    @Test
    void requestId_sameParamsReplaysFullResponse_changedParamsConflicts() throws Exception {
        String b1 = "BK-IDEM-1-" + unique();
        String b2 = "BK-IDEM-2-" + unique();
        createSealed(b1, List.of("t1"), 201);
        createSealed(b2, List.of("t1"), 201);
        String requestId = "RID-GLOBAL-" + unique();
        String body = curveBody(0L, List.of(new Point(0L, "10"), new Point(10L, "10")));

        MvcResult first = mockMvc.perform(post("/api/batches/" + b1 + "/pressure-curve")
                        .header("X-Request-Id", requestId).header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        // 同参完整重放（含相同头），逐字节返回首次响应
        MvcResult replay = mockMvc.perform(post("/api/batches/" + b1 + "/pressure-curve")
                        .header("X-Request-Id", requestId).header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, count("SELECT COUNT(*) FROM pressure_curve WHERE batch_key = ?", b1));

        // 同 requestId 改资源（换批次）→ 409
        mockMvc.perform(post("/api/batches/" + b2 + "/pressure-curve")
                        .header("X-Request-Id", requestId).header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        // 同 requestId 改参数（压力值变化）→ 409
        mockMvc.perform(post("/api/batches/" + b1 + "/pressure-curve")
                        .header("X-Request-Id", requestId).header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(curveBody(0L, List.of(new Point(0L, "10"), new Point(10L, "9.9")))))
                .andExpect(status().isConflict());
        // 同 requestId 改期望版本 → 409
        mockMvc.perform(post("/api/batches/" + b1 + "/pressure-curve")
                        .header("X-Request-Id", requestId).header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(curveBody(1L, List.of(new Point(0L, "10"), new Point(10L, "10")))))
                .andExpect(status().isConflict());
        // 同 requestId 改操作者 → 409
        mockMvc.perform(post("/api/batches/" + b1 + "/pressure-curve")
                        .header("X-Request-Id", requestId).header("X-Actor-Id", "tech-2")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        assertEquals(0, count("SELECT COUNT(*) FROM pressure_curve WHERE batch_key = ?", b2));
    }

    // ---------- 期望版本 ----------

    @Test
    void decimalByValueFingerprint_trailingZerosReplay_rawTextStillDistinguishable() throws Exception {
        String batchKey = "BK-FP-DEC-" + unique();
        createSealed(batchKey, List.of("t1"), 201);
        String requestId = "RID-FP-" + unique();
        // 首次用 9.500（3 位小数原文）
        String firstBody = curveBody(0L, List.of(new Point(0L, "10.000"), new Point(10L, "9.500")));
        MvcResult first = mockMvc.perform(post("/api/batches/" + batchKey + "/pressure-curve")
                        .header("X-Request-Id", requestId).header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isCreated()).andReturn();
        // 同 requestId，压力改为等值文本 9.5（十进制按值相等）→ 视为同参，返回首次完整响应
        String valueEqualBody = curveBody(0L, List.of(new Point(0L, "10.000"), new Point(10L, "9.5")));
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batchKey + "/pressure-curve")
                        .header("X-Request-Id", requestId).header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON).content(valueEqualBody))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        JsonNode json = objectMapper.readTree(first.getResponse().getContentAsString());
        assertEquals("9.500", json.path("points").get(1).path("pressureKpa").asText(),
                "响应与存储保留首次提交原文");
        assertEquals(1, count("SELECT COUNT(*) FROM pressure_curve WHERE batch_key = ?", batchKey));
        // 文本原样：批压力真正改值（9.6）→ 409
        mockMvc.perform(post("/api/batches/" + batchKey + "/pressure-curve")
                        .header("X-Request-Id", requestId).header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(curveBody(0L, List.of(new Point(0L, "10.000"), new Point(10L, "9.6")))))
                .andExpect(status().isConflict());
    }

    // ---------- 逐段冻结存储 ----------

    @Test
    void segmentRows_frozenExactDeclineAndRate_afterSubmission() throws Exception {
        String batchKey = "BK-SEG-" + unique();
        createSealed(batchKey, List.of("t1"), 201);
        // 题给 FAIL 曲线：三段（下降/上升/下降）
        submitCurve(batchKey, "RID-SEG-" + unique(), "tech", 0L,
                List.of(new Point(0L, "10"), new Point(5L, "9.5"),
                        new Point(10L, "10"), new Point(15L, "9.4")),
                "FAIL", "REJECTED", 201);
        assertEquals(3, count("SELECT COUNT(*) FROM pressure_curve_segment s"
                + " JOIN pressure_curve c ON s.curve_id = c.id WHERE c.batch_key = ?", batchKey));
        // 逐段精确值与门禁标记落库
        assertEquals("0.5", jdbc.queryForObject(
                "SELECT s.decline_kpa FROM pressure_curve_segment s JOIN pressure_curve c"
                        + " ON s.curve_id = c.id WHERE c.batch_key = ? AND s.seq = 1",
                String.class, batchKey));
        assertEquals("0.6", jdbc.queryForObject(
                "SELECT s.decline_kpa FROM pressure_curve_segment s JOIN pressure_curve c"
                        + " ON s.curve_id = c.id WHERE c.batch_key = ? AND s.seq = 3",
                String.class, batchKey));
        assertEquals("0", jdbc.queryForObject(
                "SELECT s.decline_kpa FROM pressure_curve_segment s JOIN pressure_curve c"
                        + " ON s.curve_id = c.id WHERE c.batch_key = ? AND s.seq = 2",
                String.class, batchKey));
        assertEquals("0.12", jdbc.queryForObject(
                "SELECT s.rate_kpa_per_second FROM pressure_curve_segment s JOIN pressure_curve c"
                        + " ON s.curve_id = c.id WHERE c.batch_key = ? AND s.seq = 3",
                String.class, batchKey));
        assertEquals(0, jdbc.queryForObject(
                "SELECT s.rate_exceeded FROM pressure_curve_segment s JOIN pressure_curve c"
                        + " ON s.curve_id = c.id WHERE c.batch_key = ? AND s.seq = 3",
                Integer.class, batchKey));
        // 历史响应逐段数据来自冻结段表
        JsonNode history = history(batchKey);
        assertEquals(3, history.path("pressureCurve").path("segments").size());
        assertEquals(0.5, history.path("pressureCurve").path("segments").get(0)
                .path("declineKpa").asDouble(), 1e-9);
        assertEquals(0.12, history.path("pressureCurve").path("segments").get(2)
                .path("rateKpaPerSecond").asDouble(), 1e-9);
    }

    // ---------- 期望版本（下一组） ----------
    @Test
    void staleExpectedVersion_conflictsAndDoesNotWrite() throws Exception {
        String batchKey = "BK-VER-" + unique();
        createSealed(batchKey, List.of("t1"), 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp", 201); // 每次成功提交推进版本 -> 1
        assertEquals("QUARANTINED", currentStatus(batchKey), "密封批次缺曲线停留隔离");
        // 仍携带 0：版本冲突 409，不写曲线、不占 requestId
        String requestId = "RID-VER-" + unique();
        mockMvc.perform(post("/api/batches/" + batchKey + "/pressure-curve")
                        .header("X-Request-Id", requestId).header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(curveBody(0L, List.of(new Point(0L, "10"), new Point(10L, "10")))))
                .andExpect(status().isConflict());
        assertEquals(0, count("SELECT COUNT(*) FROM pressure_curve WHERE batch_key = ?", batchKey));
        assertEquals(0, count("SELECT COUNT(*) FROM request_log WHERE request_id = ?", requestId));
        // 用最新版本 1 提交成功，并因原检验已齐直接进入 PENDING_RELEASE
        submitCurve(batchKey, requestId, "tech", 1L,
                List.of(new Point(0L, "10"), new Point(10L, "10")),
                "PASS", "PENDING_RELEASE", 201);
        assertEquals(2, versionOf(batchKey));
    }

    @Test
    void sealedBatchWithoutCurve_cannotReachPendingReleaseOrApproval() throws Exception {
        String batchKey = "BK-NOCURVE-" + unique();
        createSealed(batchKey, List.of("t1"), 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp", 201);
        assertEquals("QUARANTINED", currentStatus(batchKey));
        approve(batchKey, "qa", "QUALITY", 422);
    }

    // ---------- 拆批不继承曲线结论，必须重新测 ----------

    @Test
    void splitChild_isSealedButDoesNotInheritCurve_mustMeasureAgain() throws Exception {
        String parent = "BK-SP-P-" + unique();
        createSealed(parent, List.of("t1"), 201);
        submitTest(parent, "TK-1", "t1", "PASS", "insp", 201);
        submitCurve(parent, "RID-P-" + unique(), "tech", 1L,
                List.of(new Point(0L, "10"), new Point(10L, "10")),
                "PASS", "PENDING_RELEASE", 201);
        approve(parent, "qa-1", "QUALITY", 201);
        approve(parent, "ops-1", "OPERATIONS", 201);

        String child = "BK-SP-C-" + unique();
        String child2 = "BK-SP-C2-" + unique();
        split(parent, List.of(child, child2), 201);
        JsonNode childHistory = history(child);
        assertEquals("SEALED", childHistory.path("batch").path("batchType").asText());
        assertEquals(0, childHistory.path("version").asInt());
        assertTrue(childHistory.path("pressureCurve").isNull(), "新子批不继承曲线结论");

        // 子批必须重新测曲线；缺曲线不能放行
        submitTest(child, "TK-C", "t1", "PASS", "insp2", 201);
        assertEquals("QUARANTINED", currentStatus(child));
        approve(child, "qa-2", "QUALITY", 422);
        submitCurve(child, "RID-C-" + unique(), "tech2", 1L,
                List.of(new Point(0L, "10"), new Point(10L, "10")),
                "PASS", "PENDING_RELEASE", 201);
        approve(child, "qa-2", "QUALITY", 201);
        approve(child, "ops-2", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(child));
    }

    // ---------- 普通批次兼容 ----------

    @Test
    void standardBatch_curveEndpoint422_andFlowUnchanged() throws Exception {
        String batchKey = "BK-STD-" + unique();
        // 不传 batchType，旧客户端创建普通批次
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCmd("CK-1", batchKey,
                                "PROD-1", "LOT-1", Instant.parse("2026-01-02T03:04:05Z"),
                                List.of("t1"), null))))
                .andExpect(status().isCreated());
        assertEquals("STANDARD", history(batchKey).path("batch").path("batchType").asText());
        mockMvc.perform(post("/api/batches/" + batchKey + "/pressure-curve")
                        .header("X-Request-Id", "RID-STD-" + unique()).header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(curveBody(0L, List.of(new Point(0L, "10"), new Point(10L, "10")))))
                .andExpect(status().isUnprocessableEntity());
        // 原普通流程不受版本/曲线影响
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp", 201);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
        approve(batchKey, "qa", "QUALITY", 201);
        approve(batchKey, "ops", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    @Test
    void unknownBatchForCurve_returns404() throws Exception {
        mockMvc.perform(post("/api/batches/NO-SUCH/pressure-curve")
                        .header("X-Request-Id", "RID-404").header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(curveBody(0L, List.of(new Point(0L, "10"), new Point(10L, "10")))))
                .andExpect(status().isNotFound());
        assertEquals(0, count("SELECT COUNT(*) FROM request_log WHERE request_id = 'RID-404'"));
    }

    // ---------- 并发：曲线提交与最终批准/检验串行裁决 ----------

    @Test
    void concurrentFailingCurveAndFirstApproval_serialized_neverReleases() throws Exception {
        String batchKey = "BK-RACE-CURVE-FAIL-" + unique();
        createSealed(batchKey, List.of("t1"), 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp", 201); // v1，缺曲线仍 QUARANTINED

        // 同时：题给 FAIL 曲线（累计下降 1.1）与 QUALITY 批准，行锁按提交顺序串行
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/pressure-curve")
                        .header("X-Request-Id", "RID-RACE-F-" + unique()).header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(curveBody(1L, List.of(new Point(0L, "10"), new Point(5L, "9.5"),
                                new Point(10L, "10"), new Point(15L, "9.4"))))),
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CA-Q\"}"))
        );

        int curveCode = results.get(0).get(30, TimeUnit.SECONDS);
        int approvalCode = results.get(1).get(30, TimeUnit.SECONDS);
        // 批准先获锁：曲线未 PASS → 422，随后 FAIL 曲线 201；曲线先获锁：FAIL 201，随后批准 409
        assertEquals(201, curveCode);
        assertTrue(approvalCode == 409 || approvalCode == 422);
        assertEquals("REJECTED", currentStatus(batchKey));
        assertEquals(0, count("SELECT COUNT(*) FROM approval WHERE batch_key = ?", batchKey),
                "FAIL 曲线批次不得留下任何批准");
        assertEquals(1, count("SELECT COUNT(*) FROM pressure_curve WHERE batch_key = ? AND outcome = 'FAIL'",
                batchKey));
    }

    @Test
    void concurrentPassingCurveAndFirstApproval_serialized_neverSkipsGate() throws Exception {
        String batchKey = "BK-RACE-CURVE-OK-" + unique();
        createSealed(batchKey, List.of("t1"), 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp", 201); // v1

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/pressure-curve")
                        .header("X-Request-Id", "RID-RACE-P-" + unique()).header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(curveBody(1L, List.of(new Point(0L, "10"), new Point(10L, "10"))))),
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CA-Q2\"}"))
        );

        int curveCode = results.get(0).get(30, TimeUnit.SECONDS);
        int approvalCode = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, curveCode);
        // 批准先获锁则 422（曲线缺失），曲线先获锁则 201（曲线已 PASS，进入 RELEASE_REVIEW）
        assertTrue(approvalCode == 201 || approvalCode == 422);
        // 无论裁决顺序如何，绝不可能跳过门禁直接 RELEASED；至多一笔批准
        String status = currentStatus(batchKey);
        int approvals = count("SELECT COUNT(*) FROM approval WHERE batch_key = ?", batchKey);
        if (approvals == 1) {
            assertEquals(201, approvalCode);
            assertEquals("RELEASE_REVIEW", status);
        } else {
            assertEquals(422, approvalCode);
            assertEquals("PENDING_RELEASE", status);
        }
        assertEquals(1, count("SELECT COUNT(*) FROM pressure_curve WHERE batch_key = ? AND outcome = 'PASS'",
                batchKey));
    }

    @Test
    void concurrentSplitAndCurveSubmission_serializedByParentRowLock() throws Exception {
        String parent = "BK-RACE-SP-" + unique();
        createSealed(parent, List.of("t1"), 201);
        submitTest(parent, "TK-1", "t1", "PASS", "insp", 201);
        submitCurve(parent, "RID-SP-P", "tech", 1L,
                List.of(new Point(0L, "10"), new Point(10L, "10")),
                "PASS", "PENDING_RELEASE", 201);
        approve(parent, "qa-1", "QUALITY", 201);
        approve(parent, "ops-1", "OPERATIONS", 201); // v4 RELEASED

        String child = "BK-RACE-SP-C-" + unique();
        String child2 = "BK-RACE-SP-C2-" + unique();
        List<java.util.Map<String, String>> children = List.of(
                java.util.Map.of("batchKey", child, "batchNo", "LOT-C"),
                java.util.Map.of("batchKey", child2, "batchNo", "LOT-C2"));
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "commandKey", "CK-S-RACE", "children", children)))),
                () -> callStatus(post("/api/batches/" + parent + "/pressure-curve")
                        .header("X-Request-Id", "RID-RACE-S").header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(curveBody(4L, List.of(new Point(0L, "10"), new Point(10L, "10")))))
        );
        int splitCode = results.get(0).get(30, TimeUnit.SECONDS);
        int curveCode = results.get(1).get(30, TimeUnit.SECONDS);
        // 拆批先获锁：父批 SPLIT，曲线 409；曲线先获锁：RELEASED 不允许曲线 409，随后拆批 201
        assertEquals(409, curveCode);
        assertEquals(201, splitCode);
        assertEquals("SPLIT", currentStatus(parent));
        assertEquals(1, count("SELECT COUNT(*) FROM pressure_curve WHERE batch_key = ?", parent));
        assertEquals(2, count("SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", parent));
    }

    @Test
    void concurrentSameRequestId_sameWinnerForBoth_noDuplicateKeyLeak() throws Exception {
        String batchKey = "BK-RACE-RID-" + unique();
        createSealed(batchKey, List.of("t1"), 201);
        String body = curveBody(0L, List.of(new Point(0L, "10"), new Point(10L, "10")));
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/pressure-curve")
                        .header("X-Request-Id", "RID-DUP").header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/" + batchKey + "/pressure-curve")
                        .header("X-Request-Id", "RID-DUP").header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同 requestId 同参并发均返回首次响应");
        }
        assertEquals(1, count("SELECT COUNT(*) FROM pressure_curve WHERE batch_key = ?", batchKey));
        assertEquals(2, count("SELECT COUNT(*) FROM pressure_curve_node n JOIN pressure_curve c"
                + " ON n.curve_id = c.id WHERE c.batch_key = ?", batchKey));
        assertEquals(1, count("SELECT COUNT(*) FROM request_log WHERE request_id = 'RID-DUP'"));
    }

    // ---------- 祖先召回：与曲线提交串行，后代禁止新增曲线 ----------

    @Test
    void recalledAncestor_blocksCurveSubmissionOnDescendant() throws Exception {
        String parent = "BK-ANC-P-" + unique();
        createSealed(parent, List.of("t1"), 201);
        submitTest(parent, "TK-1", "t1", "PASS", "insp", 201);
        submitCurve(parent, "RID-ANC-P", "tech", 1L,
                List.of(new Point(0L, "10"), new Point(10L, "10")),
                "PASS", "PENDING_RELEASE", 201);
        approve(parent, "qa-1", "QUALITY", 201);
        approve(parent, "ops-1", "OPERATIONS", 201);

        String child = "BK-ANC-C-" + unique();
        String child2 = "BK-ANC-C2-" + unique();
        split(parent, List.of(child, child2), 201);
        // 召回父批（SPLIT 可召回）：后代立即不可新增检验/批准/拆分，也不可新增曲线
        mockMvc.perform(post("/api/batches/" + parent + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-RC-ANC\",\"reason\":\"上游密封异常\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/batches/" + child + "/pressure-curve")
                        .header("X-Request-Id", "RID-ANC-C").header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(curveBody(0L, List.of(new Point(0L, "10"), new Point(10L, "10")))))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, count("SELECT COUNT(*) FROM pressure_curve WHERE batch_key = ?", child));
        assertEquals(0, count("SELECT COUNT(*) FROM request_log WHERE request_id = 'RID-ANC-C'"));
    }

    // ---------- helpers ----------

    @Test
    void concurrentDescendantCurveAndAncestorRecall_serializedByRowLocks() throws Exception {
        // 祖先召回会逐行锁后代；后代曲线先锁自身行。两者按行锁提交顺序裁决，结果必须自洽。
        String parent = "BK-RACE-ANC-P-" + unique();
        createSealed(parent, List.of("t1"), 201);
        submitTest(parent, "TK-P", "t1", "PASS", "insp", 201);
        submitCurve(parent, "RID-RACE-ANC-P", "tech", 1L,
                List.of(new Point(0L, "10"), new Point(10L, "10")),
                "PASS", "PENDING_RELEASE", 201);
        approve(parent, "qa-1", "QUALITY", 201);
        approve(parent, "ops-1", "OPERATIONS", 201);

        String child = "BK-RACE-ANC-C-" + unique();
        String child2 = "BK-RACE-ANC-C2-" + unique();
        split(parent, List.of(child, child2), 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + parent + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-RC-RACE\",\"reason\":\"并发召回\"}")),
                () -> callStatus(post("/api/batches/" + child + "/pressure-curve")
                        .header("X-Request-Id", "RID-RACE-ANC-C").header("X-Actor-Id", "tech")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(curveBody(0L, List.of(new Point(0L, "10"), new Point(10L, "10")))))
        );
        int recallCode = results.get(0).get(30, TimeUnit.SECONDS);
        int curveCode = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, recallCode);
        int curveRows = count("SELECT COUNT(*) FROM pressure_curve WHERE batch_key = ?", child);
        if (curveRows == 1) {
            // 曲线先提交（201），召回随后：曲线历史保留，但祖先召回后子批不可用
            assertEquals(201, curveCode);
            assertEquals(1, count("SELECT COUNT(*) FROM request_log WHERE request_id = 'RID-RACE-ANC-C'"));
        } else {
            // 召回先锁定后代：曲线看到召回祖先返回 422，不写曲线、不占 requestId
            assertEquals(422, curveCode);
            assertEquals(0, count("SELECT COUNT(*) FROM request_log WHERE request_id = 'RID-RACE-ANC-C'"));
        }
        assertEquals("RECALLED", currentStatus(parent));
    }

    private void createSealed(String batchKey, List<String> items, int expected) throws Exception {
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCmd("CK-" + unique(),
                                batchKey, "PROD-1", "LOT-1",
                                Instant.parse("2026-01-02T03:04:05Z"), items, BatchType.SEALED))))
                .andExpect(status().is(expected));
    }

    private String curveBody(long expectedVersion, List<Point> points) throws Exception {
        return objectMapper.writeValueAsString(new CurveCmd(expectedVersion, points));
    }

    private void submitTest(String batchKey, String testKey, String item, String result,
                            String inspector, int expected) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "commandKey", "CK-T-" + unique(), "testKey", testKey,
                "testItem", item, "result", result, "inspector", inspector));
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private void approve(String batchKey, String actor, String role, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-A-" + unique() + "\"}"))
                .andExpect(status().is(expected));
    }

    private void split(String parent, List<String> childKeys, int expected) throws Exception {
        List<java.util.Map<String, String>> children = childKeys.stream()
                .map(k -> java.util.Map.of("batchKey", k, "batchNo", "LOT-" + k.substring(k.length() - 4)))
                .toList();
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "commandKey", "CK-S-" + unique(), "children", children))))
                .andExpect(status().is(expected));
    }

    private void submitCurve(String batchKey, String requestId, String actor, long expectedVersion,
                             List<Point> points, String expectedResult, String expectedStatus,
                             int expectedHttp) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/batches/" + batchKey + "/pressure-curve")
                        .header("X-Request-Id", requestId).header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(curveBody(expectedVersion, points)))
                .andExpect(status().is(expectedHttp)).andReturn();
        if (expectedHttp == 201) {
            JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
            assertEquals(expectedResult, json.path("result").asText());
            assertEquals(expectedStatus, json.path("batchStatus").asText());
        }
    }

    private String currentStatus(String batchKey) throws Exception {
        return history(batchKey).path("batch").path("status").asText();
    }

    private long versionOf(String batchKey) throws Exception {
        return history(batchKey).path("batch").path("version").asLong();
    }

    private JsonNode history(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int count(String sql, String... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, (Object[]) args);
        return value == null ? 0 : value;
    }

    private int callStatus(MockHttpServletRequestBuilder req) {
        try {
            return mockMvc.perform(req).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
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

    @Test
    void curveFailureBlocksLaterTestsAndApprovals() throws Exception {        String batchKey = "BK-CURVE-LOCK-" + unique();
        createSealed(batchKey, List.of("t1", "t2"), 201);
        submitCurve(batchKey, "RID-LOCK-" + unique(), "tech", 0L,
                List.of(new Point(0L, "10"), new Point(5L, "9.5"),
                        new Point(10L, "10"), new Point(15L, "9.4")),
                "FAIL", "REJECTED", 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp", 409);
        approve(batchKey, "qa", "QUALITY", 409);
        assertFalse(history(batchKey).path("tests").isArray() && history(batchKey).path("tests").size() > 0);
    }
}


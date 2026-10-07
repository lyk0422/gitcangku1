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
 * 压力曲线检验端到端测试（真实 H2 事务）：合成样例 FAIL/PASS、400 不留痕不占键、
 * requestId 幂等（十进制按值）、期望版本、失败曲线保留不可改 PASS、原流程兼容。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PressureProfileTest {

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
        jdbc.update("DELETE FROM pressure_profile");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 题干合成样例 ----------

    @Test
    void syntheticCurve_cumulativeDescent1_1_failsAndIsRetainedUnchangeable() throws Exception {
        String batchKey = "BK-PP-FAIL-" + unique();
        createBatch(batchKey, List.of("t1"), 201);

        String body = curveBody("RQ-SYN-1", "seal-op",
                pts(pt(0, "10"), pt(5, "9.5"), pt(10, "10"), pt(15, "9.4")));
        MvcResult first = submitCurve(batchKey, body, 201);
        JsonNode result = objectMapper.readTree(first.getResponse().getContentAsString());
        // 首末仅降 0.6，累计下降 0.5 + 0.6 = 1.1 > 1，必须 FAIL 并进入原 REJECTED
        assertEquals("FAIL", result.path("outcome").asText());
        assertEquals("1.1", result.path("cumulativeDescentKpa").asText());
        assertEquals("0.12", result.path("maxDescentRateKpaPerSec").asText());
        assertEquals("REJECTED", result.path("batchStatus").asText());
        assertEquals(4, result.path("pointCount").asInt());
        assertEquals("0:10,5:9.5,10:10,15:9.4", result.path("points").asText());
        assertEquals("REJECTED", currentStatus(batchKey));

        // 合法失败曲线落库保留：pressure_profile + test_result（保留检验项名）
        assertEquals(1, countRows("pressure_profile", batchKey));
        assertEquals("FAIL", jdbc.queryForObject(
                "SELECT outcome FROM pressure_profile WHERE batch_key = ?", String.class, batchKey));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM test_result WHERE batch_key = ? AND test_item = 'PRESSURE_PROFILE'"
                        + " AND outcome = 'FAIL'", Integer.class, batchKey));

        // 历史完整保留：曲线结论与批次状态快照
        JsonNode history = history(batchKey);
        assertEquals("FAIL", history.path("pressureProfile").path("outcome").asText());
        assertEquals("1.1", history.path("pressureProfile").path("cumulativeDescentKpa").asText());

        // 不能改成 PASS：同 requestId 异参 409；同参重放仍返回首次 FAIL 响应
        submitCurve(batchKey, curveBody("RQ-SYN-1", "seal-op",
                pts(pt(0, "10"), pt(5, "9.5"), pt(10, "10"))), 409);
        MvcResult replay = submitCurve(batchKey, body, 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals("FAIL", objectMapper.readTree(replay.getResponse().getContentAsString())
                .path("outcome").asText());
        // 换 requestId 也不允许：REJECTED 终态拒绝新检验
        submitCurve(batchKey, curveBody("RQ-SYN-2", "seal-op",
                pts(pt(0, "10"), pt(10, "10"))), 409);
        assertEquals(1, countRows("pressure_profile", batchKey));
        assertEquals("REJECTED", currentStatus(batchKey));
    }

    @Test
    void syntheticCurveWithoutLastDrop_passesButStillNeedsItemsAndTwoRoleApproval()
            throws Exception {
        String batchKey = "BK-PP-PASS-" + unique();
        createBatch(batchKey, List.of("t1"), 201);

        MvcResult curveResult = submitCurve(batchKey, curveBody("RQ-P1", "curve-op",
                pts(pt(0, "10"), pt(5, "9.5"), pt(10, "10"))), 201);
        JsonNode curve = objectMapper.readTree(curveResult.getResponse().getContentAsString());
        assertEquals("PASS", curve.path("outcome").asText());
        assertEquals("0.5", curve.path("cumulativeDescentKpa").asText());
        // 曲线 PASS 不替代原必做检验项：批次仍在隔离
        assertEquals("QUARANTINED", curve.path("batchStatus").asText());

        // 必做项未完成不能批准；曲线操作者作为检验人不得批准
        approve(batchKey, "qa-1", "QUALITY", "CK-EARLY", 422);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp-1", 201);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
        approve(batchKey, "curve-op", "QUALITY", "CK-OP", 422);

        // 仍需两角色批准才放行
        approve(batchKey, "qa-1", "QUALITY", "CK-Q", 201);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
        approve(batchKey, "ops-1", "OPERATIONS", "CK-O", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 曲线 PASS 不改变状态，版本号仅随 3 次状态变更加 1
        JsonNode history = history(batchKey);
        assertEquals("PASS", history.path("pressureProfile").path("outcome").asText());
        assertEquals(3, history.path("batch").path("version").asInt());
    }

    // ---------- 400：非法曲线不留检验、不占键 ----------

    @Test
    void invalidCurves_return400_leaveNoRecordAndDoNotConsumeRequestId() throws Exception {
        String batchKey = "BK-PP-400-" + unique();
        createBatch(batchKey, List.of("t1"), 201);

        String[] invalidBodies = {
                curveBody("RQ-BAD-1", "op", pts(pt(0, "10"))),                              // 仅 1 点
                curveBody("RQ-BAD-1", "op", pts(pt(0, "10"), pt(1, "10"), pt(2, "10"),
                        pt(3, "10"), pt(4, "10"), pt(5, "10"), pt(6, "10"), pt(7, "10"),
                        pt(8, "10"), pt(9, "10"), pt(10, "10"))),                           // 11 点
                curveBody("RQ-BAD-1", "op", pts(pt(1, "10"), pt(10, "10"))),                // 首点非 0
                curveBody("RQ-BAD-1", "op", pts(pt(0, "10"), pt(9, "10"))),                 // 末点不足 10
                curveBody("RQ-BAD-1", "op", pts(pt(0, "10"), pt(0, "9"), pt(10, "10"))),    // 时刻重复
                curveBody("RQ-BAD-1", "op", pts(pt(0, "10"), pt(9, "10"), pt(5, "10"),
                        pt(10, "10"))),                                                     // 逆序
                curveBody("RQ-BAD-1", "op", pts(pt(0, "-0.1"), pt(10, "10"))),              // 负压力
                curveBody("RQ-BAD-1", "op", pts(pt(0, "10.0001"), pt(10, "10"))),           // 超 3 位小数
                curveBody("RQ-BAD-1", "", pts(pt(0, "10"), pt(10, "10"))),                  // 操作者空
                "{\"operator\":\"op\",\"points\":" + pts(pt(0, "10"), pt(10, "10")) + "}",  // 缺 requestId
                curveBody("RQ-BAD-1", "op", "[]"),                                          // 空曲线
                curveBody("RQ-BAD-1", "op", pts(pt(0, "10"), pt(10, null))),                // 压力缺失
        };
        for (String invalid : invalidBodies) {
            submitCurve(batchKey, invalid, 400);
        }

        // 不留检验、不占键、状态不变
        assertEquals(0, countRows("pressure_profile", batchKey));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM test_result WHERE batch_key = ?", Integer.class, batchKey));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'SUBMIT_PRESSURE_PROFILE'",
                Integer.class));
        assertEquals("QUARANTINED", currentStatus(batchKey));

        // 失败不占键：同一 requestId 修正参数后可正常提交
        submitCurve(batchKey, curveBody("RQ-BAD-1", "op", pts(pt(0, "10"), pt(10, "10"))), 201);
        assertEquals(1, countRows("pressure_profile", batchKey));
    }

    // ---------- 幂等：同参重放、异参 409、十进制按值 ----------

    @Test
    void requestId_replaySameParams_changedParamsConflict_decimalsByValue() throws Exception {
        String batchKey = "BK-PP-IDEM-" + unique();
        createBatch(batchKey, List.of("t1"), 201);

        String body = curveBody("RQ-IDEM-1", "op-a", pts(pt(0, "10"), pt(5, "9.5"), pt(10, "10")));
        MvcResult first = submitCurve(batchKey, body, 201);
        MvcResult replay = submitCurve(batchKey, body, 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());

        // 十进制按值：9.50 与 9.5 同值，视为同参，重放首次完整响应
        MvcResult valueReplay = submitCurve(batchKey, curveBody("RQ-IDEM-1", "op-a",
                pts(pt(0, "10.0"), pt(5, "9.50"), pt(10, "10"))), 201);
        assertEquals(first.getResponse().getContentAsString(),
                valueReplay.getResponse().getContentAsString());

        // 异参 409：曲线不同、操作者不同、补传期望版本均视为不同参数
        submitCurve(batchKey, curveBody("RQ-IDEM-1", "op-a",
                pts(pt(0, "10"), pt(5, "9.4"), pt(10, "10"))), 409);
        submitCurve(batchKey, curveBody("RQ-IDEM-1", "op-b",
                pts(pt(0, "10"), pt(5, "9.5"), pt(10, "10"))), 409);
        submitCurve(batchKey, curveBodyWithVersion("RQ-IDEM-1", "op-a", 0,
                pts(pt(0, "10"), pt(5, "9.5"), pt(10, "10"))), 409);

        // 换 requestId：批次已有曲线记录，不可改写
        submitCurve(batchKey, curveBody("RQ-IDEM-2", "op-a",
                pts(pt(0, "10"), pt(5, "9.5"), pt(10, "10"))), 409);
        assertEquals(1, countRows("pressure_profile", batchKey));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'SUBMIT_PRESSURE_PROFILE'",
                Integer.class));
    }

    // ---------- 期望版本 ----------

    @Test
    void expectedVersion_mustMatchCurrentBatchVersion() throws Exception {
        String batchKey = "BK-PP-VER-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        assertEquals(0, history(batchKey).path("batch").path("version").asInt());

        // 版本不匹配 409 且不留痕；不匹配失败同样不占键
        submitCurve(batchKey, curveBodyWithVersion("RQ-V1", "op", 3,
                pts(pt(0, "10"), pt(10, "10"))), 409);
        assertEquals(0, countRows("pressure_profile", batchKey));

        // 累计下降 1.001 > 1，FAIL 使批次 REJECTED，状态变更一次，版本号加 1
        submitCurve(batchKey, curveBodyWithVersion("RQ-V1", "op", 0,
                pts(pt(0, "10"), pt(10, "8.999"))), 201);
        assertEquals(1, history(batchKey).path("batch").path("version").asInt());
        assertEquals("REJECTED", currentStatus(batchKey));
    }

    // ---------- 与原流程兼容 ----------

    @Test
    void curveOnTerminalOrUnknownBatch_rejected() throws Exception {
        submitCurve("NO-SUCH-BATCH", curveBody("RQ-NB", "op", pts(pt(0, "10"), pt(10, "10"))), 404);

        String rejected = "BK-PP-REJ-" + unique();
        createBatch(rejected, List.of("t1"), 201);
        submitTest(rejected, "TK-F", "t1", "FAIL", "insp", 201);
        submitCurve(rejected, curveBody("RQ-T1", "op", pts(pt(0, "10"), pt(10, "10"))), 409);

        String released = "BK-PP-REL-" + unique();
        createBatch(released, List.of("t1"), 201);
        submitTest(released, "TK-1", "t1", "PASS", "insp", 201);
        approve(released, "qa", "QUALITY", "CK-Q", 201);
        approve(released, "ops", "OPERATIONS", "CK-O", 201);
        submitCurve(released, curveBody("RQ-T2", "op", pts(pt(0, "10"), pt(10, "10"))), 409);
        assertEquals(0, countRows("pressure_profile", released));
    }

    @Test
    void ordinaryBatchFlowWithoutCurve_stillWorks() throws Exception {
        String batchKey = "BK-PP-COMPAT-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp", 201);
        approve(batchKey, "qa", "QUALITY", "CK-Q", 201);
        approve(batchKey, "ops", "OPERATIONS", "CK-O", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
        assertTrue(history(batchKey).path("pressureProfile").isNull());
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String pt(long seconds, String kpa) {
        return "{\"offsetSeconds\":" + seconds + ",\"pressureKpa\":" + kpa + "}";
    }

    private static String pts(String... points) {
        return "[" + String.join(",", points) + "]";
    }

    private static String curveBody(String requestId, String operator, String pointsJson) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                + "\",\"points\":" + pointsJson + "}";
    }

    private static String curveBodyWithVersion(String requestId, String operator, long version,
                                               String pointsJson) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                + "\",\"expectedVersion\":" + version + ",\"points\":" + pointsJson + "}";
    }

    private MvcResult submitCurve(String batchKey, String body, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/pressure-profiles")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private void createBatch(String batchKey, List<String> items, int expected) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-" + batchKey, batchKey,
                "PROD-1", "LOT-1", Instant.parse("2026-01-02T03:04:05Z"), items));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests) {
    }

    private void submitTest(String batchKey, String testKey, String item, String result,
                            String inspector, int expected) throws Exception {
        String body = "{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"" + testKey
                + "\",\"testItem\":\"" + item + "\",\"result\":\"" + result
                + "\",\"inspector\":\"" + inspector + "\"}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private void approve(String batchKey, String actor, String role, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    private String currentStatus(String batchKey) throws Exception {
        return history(batchKey).path("batch").path("status").asText();
    }

    private JsonNode history(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int countRows(String table, String batchKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE batch_key = ?", Integer.class, batchKey);
        return count == null ? 0 : count;
    }
}

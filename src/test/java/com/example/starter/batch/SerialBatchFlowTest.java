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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 序号区间根批登记测试：区间规范化（排序、相邻合并）、越界/重叠/长度不符 422、
 * 根域按产品号唯一（含已拆分、已召回根批）、requestId 幂等与十进制同值同参。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SerialBatchFlowTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM serial_interval");
        jdbc.update("DELETE FROM serial_batch");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 登记主流程 ----------

    @Test
    void createRoot_happy_mergesAdjacentAndSortsIntervals() throws Exception {
        String key = "SB-ROOT-" + unique();
        // 区间乱序且相邻：[5,10] 与 [1,4] 应合并为 [1,10]
        MvcResult result = createSerial(
                createBody("RQ-C1-" + unique(), key, "PROD-A", new BigDecimal("10"),
                        new long[][]{{5, 10}, {1, 4}}), 201);

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(key, body.path("batchKey").asText());
        assertEquals("QUARANTINED", body.path("status").asText());
        assertEquals(10, body.path("quantity").asLong());
        assertEquals(1, body.path("version").asInt());
        assertEquals(1, body.path("serialIntervals").size());
        assertEquals(1, body.path("serialIntervals").get(0).path("start").asLong());
        assertEquals(10, body.path("serialIntervals").get(0).path("end").asLong());

        // 落库：根批标记 + 合并后的单条区间，不展开单件序号
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "SELECT is_root FROM serial_batch WHERE batch_key = ?", Boolean.class, key));
        assertEquals(10, jdbc.queryForObject(
                "SELECT quantity FROM serial_batch WHERE batch_key = ?", Long.class, key));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM serial_interval WHERE batch_key = ?", Integer.class, key));
        assertEquals("QUARANTINED", jdbc.queryForObject(
                "SELECT status FROM batch WHERE batch_key = ?", String.class, key));
    }

    @Test
    void createRoot_millionRange_storedAsIntervalsNotExpanded() throws Exception {
        String key = "SB-MILL-" + unique();
        createSerial(createBody("RQ-M1-" + unique(), key, "PROD-M",
                new BigDecimal("1000000"), new long[][]{{1, 1000000}}), 201);
        // 百万序号只存 1 条区间记录
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM serial_interval WHERE batch_key = ?", Integer.class, key));
        assertEquals(1000000L, jdbc.queryForObject(
                "SELECT quantity FROM serial_batch WHERE batch_key = ?", Long.class, key));
    }

    // ---------- 登记失败分支 ----------

    @Test
    void createRoot_invalidIntervals_return422_andNothingPersisted() throws Exception {
        // 重叠
        createSerial(createBody("RQ-B1-" + unique(), "SB-B1-" + unique(), "PROD-B",
                new BigDecimal("8"), new long[][]{{1, 5}, {3, 7}}), 422);
        // 越界：起点 0
        createSerial(createBody("RQ-B2-" + unique(), "SB-B2-" + unique(), "PROD-B",
                new BigDecimal("6"), new long[][]{{0, 5}}), 422);
        // 越界：终点超 1000000
        createSerial(createBody("RQ-B3-" + unique(), "SB-B3-" + unique(), "PROD-B",
                new BigDecimal("6"), new long[][]{{999995, 1000001}}), 422);
        // 起点大于终点
        createSerial(createBody("RQ-B4-" + unique(), "SB-B4-" + unique(), "PROD-B",
                new BigDecimal("1"), new long[][]{{5, 3}}), 422);
        // 区间总长度与数量不一致
        createSerial(createBody("RQ-B5-" + unique(), "SB-B5-" + unique(), "PROD-B",
                new BigDecimal("6"), new long[][]{{1, 5}}), 422);
        // 数量非整数
        createSerial(createBody("RQ-B6-" + unique(), "SB-B6-" + unique(), "PROD-B",
                new BigDecimal("2.5"), new long[][]{{1, 2}}), 422);
        // 规范化后超过 10 个区间
        long[][] eleven = new long[11][2];
        for (int i = 0; i < 11; i++) {
            eleven[i] = new long[]{i * 2 + 1, i * 2 + 1};
        }
        createSerial(createBody("RQ-B7-" + unique(), "SB-B7-" + unique(), "PROD-B",
                new BigDecimal("11"), eleven), 422);

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM serial_batch", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM serial_interval", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch", Integer.class));
        // 失败不占键
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class));
    }

    @Test
    void createRoot_missingOperatorOrRequestId_return400() throws Exception {
        String key = "SB-400-" + unique();
        mockMvc.perform(post("/api/serial-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RQ-400-1\",\"batchKey\":\"" + key
                                + "\",\"productCode\":\"PROD-A\",\"batchNo\":\"L1\","
                                + "\"producedAt\":\"2026-01-02T03:04:05Z\","
                                + "\"requiredTests\":[\"t1\"],\"quantity\":2,"
                                + "\"serialIntervals\":[{\"start\":1,\"end\":2}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/serial-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"op-1\",\"batchKey\":\"" + key
                                + "\",\"productCode\":\"PROD-A\",\"batchNo\":\"L1\","
                                + "\"producedAt\":\"2026-01-02T03:04:05Z\","
                                + "\"requiredTests\":[\"t1\"],\"quantity\":2,"
                                + "\"serialIntervals\":[{\"start\":1,\"end\":2}]}"))
                .andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch", Integer.class));
    }

    // ---------- 根域唯一性 ----------

    @Test
    void rootDomain_uniquePerProduct_includesSplitAndRecalledRoots() throws Exception {
        String rootA = "SB-D-A-" + unique();
        createSerial(createBody("RQ-D1-" + unique(), rootA, "PROD-D", new BigDecimal("10"),
                new long[][]{{1, 10}}), 201);
        // 同产品相交 → 409（[5,15] 与 [1,10] 相交）
        createSerial(createBody("RQ-D2-" + unique(), "SB-D-B-" + unique(), "PROD-D",
                new BigDecimal("11"), new long[][]{{5, 15}}), 409);
        // 同产品不相交 → 201
        createSerial(createBody("RQ-D3-" + unique(), "SB-D-C-" + unique(), "PROD-D",
                new BigDecimal("10"), new long[][]{{11, 20}}), 201);
        // 不同产品同区间 → 201
        createSerial(createBody("RQ-D4-" + unique(), "SB-D-Q-" + unique(), "PROD-Q",
                new BigDecimal("10"), new long[][]{{1, 10}}), 201);

        // 已拆分的根批仍持有根域：rootA 放行并受控拆分后，[1,10] 仍不可再登记
        releaseBatch(rootA, "insp-1");
        splitSerial(rootA, "RQ-DS-" + unique(), 1,
                List.of(child("SB-D-A1-" + unique(), new long[][]{{1, 4}}),
                        child("SB-D-A2-" + unique(), new long[][]{{5, 10}})), 201);
        assertEquals("SPLIT", jdbc.queryForObject(
                "SELECT status FROM batch WHERE batch_key = ?", String.class, rootA));
        createSerial(createBody("RQ-D5-" + unique(), "SB-D-E-" + unique(), "PROD-D",
                new BigDecimal("10"), new long[][]{{1, 10}}), 409);

        // 已召回的根批仍持有根域
        String rootR = "SB-D-R-" + unique();
        createSerial(createBody("RQ-D6-" + unique(), rootR, "PROD-D", new BigDecimal("10"),
                new long[][]{{21, 30}}), 201);
        releaseBatch(rootR, "insp-2");
        recall(rootR, "qa", "质量召回", "CK-DR-" + unique(), 201);
        createSerial(createBody("RQ-D7-" + unique(), "SB-D-F-" + unique(), "PROD-D",
                new BigDecimal("11"), new long[][]{{25, 35}}), 409);
        // 不相交的新根仍可登记
        createSerial(createBody("RQ-D8-" + unique(), "SB-D-G-" + unique(), "PROD-D",
                new BigDecimal("10"), new long[][]{{31, 40}}), 201);
    }

    // ---------- 登记幂等 ----------

    @Test
    void createRoot_idempotent_reorderAndDecimalSameValueReplay_changedParams409() throws Exception {
        String key = "SB-ID-" + unique();
        String requestId = "RQ-ID-" + unique();
        // 首次：区间 [1,4],[5,10]，数量 10
        MvcResult first = createSerial(
                createBody(requestId, key, "PROD-I", new BigDecimal("10"),
                        new long[][]{{1, 4}, {5, 10}}), 201);
        // 重放：区间换序 + 数量写成 10.0，属同参，返回首次完整响应
        MvcResult replay = createSerial(
                createBody(requestId, key, "PROD-I", new BigDecimal("10.0"),
                        new long[][]{{5, 10}, {1, 4}}), 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, key));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class));

        // 同 requestId 改参（批号不同）→ 409
        String changed = objectMapper.writeValueAsString(new CreateCmd(requestId, "op-1", key,
                "PROD-I", "LOT-OTHER", Instant.parse("2026-01-02T03:04:05Z"), List.of("t1"),
                new BigDecimal("10"), List.of(new IntervalSpec(1, 10))));
        mockMvc.perform(post("/api/serial-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());
    }

    @Test
    void createRoot_failureDoesNotOccupyRequestId() throws Exception {
        String requestId = "RQ-F-" + unique();
        String key = "SB-F-" + unique();
        // 先以非法区间失败（422）
        createSerial(createBody(requestId, key, "PROD-F", new BigDecimal("5"),
                new long[][]{{1, 3}, {2, 5}}), 422);
        // 同 requestId 修正参数后成功
        createSerial(createBody(requestId, key, "PROD-F", new BigDecimal("5"),
                new long[][]{{1, 5}}), 201);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, key));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record IntervalSpec(long start, long end) {
    }

    private record CreateCmd(String requestId, String operator, String batchKey,
                             String productCode, String batchNo, Instant producedAt,
                             List<String> requiredTests, BigDecimal quantity,
                             List<IntervalSpec> serialIntervals) {
    }

    private record ChildSpec(String batchKey, String batchNo, List<IntervalSpec> serialIntervals) {
    }

    private record SplitCmd(String requestId, String operator, int expectedVersion,
                            List<ChildSpec> children) {
    }

    private ChildSpec child(String batchKey, long[][] intervals) {
        return new ChildSpec(batchKey, "LOT-" + unique(), toSpecs(intervals));
    }

    private List<IntervalSpec> toSpecs(long[][] intervals) {
        return Arrays.stream(intervals)
                .map(iv -> new IntervalSpec(iv[0], iv[1]))
                .toList();
    }

    private String createBody(String requestId, String batchKey, String productCode,
                              BigDecimal quantity, long[][] intervals) throws Exception {
        return objectMapper.writeValueAsString(new CreateCmd(requestId, "op-1", batchKey,
                productCode, "LOT-1", Instant.parse("2026-01-02T03:04:05Z"), List.of("t1"),
                quantity, toSpecs(intervals)));
    }

    private MvcResult createSerial(String body, int expected) throws Exception {
        return mockMvc.perform(post("/api/serial-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private void splitSerial(String parentKey, String requestId, int expectedVersion,
                             List<ChildSpec> children, int expected) throws Exception {
        mockMvc.perform(post("/api/serial-batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SplitCmd(requestId, "op-1", expectedVersion, children))))
                .andExpect(status().is(expected));
    }

    /**
     * 让批次走完 检验 PASS + 双角色批准 进入 RELEASED（沿用原放行入口）。
     */
    private void releaseBatch(String batchKey, String inspector) throws Exception {
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        for (JsonNode item : node.path("batch").path("requiredTests")) {
            mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-"
                                    + unique() + "\",\"testItem\":\"" + item.asText()
                                    + "\",\"result\":\"PASS\",\"inspector\":\"" + inspector
                                    + "\"}"))
                    .andExpect(status().isCreated());
        }
        approve(batchKey, "qa-" + unique(), "QUALITY");
        approve(batchKey, "ops-" + unique(), "OPERATIONS");
    }

    private void approve(String batchKey, String actor, String role) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-A-" + unique() + "\"}"))
                .andExpect(status().isCreated());
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
}
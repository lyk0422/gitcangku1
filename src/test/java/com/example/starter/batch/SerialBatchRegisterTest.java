package com.example.starter.batch;

import com.example.starter.batch.dto.IntervalSpec;
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
 * 序号根批登记测试（真实 H2）：主流程、区间规范化、422/409 失败分支、
 * 根域按产品唯一（含已拆/召回根）、requestId 幂等与并发登记。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SerialBatchRegisterTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM batch_serial_interval");
        jdbc.update("DELETE FROM serial_domain_lock");
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
    void registerHappy_adjacentIntervalsMergedAndPersisted() throws Exception {
        String batchKey = "SB-REG-" + unique();
        MvcResult result = register(registerBody("RID-" + unique(), "op-1", batchKey,
                "PROD-S-" + unique(), 10, List.of(iv(1, 4), iv(5, 10))), 201);

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(batchKey, body.path("batchKey").asText());
        assertEquals("QUARANTINED", body.path("status").asText());
        assertEquals(10, body.path("quantity").asInt());
        // 相邻区间 [1,4] 与 [5,10] 合并为 [1,10]
        assertEquals(1, body.path("intervals").size());
        assertEquals(1, body.path("intervals").get(0).path("start").asInt());
        assertEquals(10, body.path("intervals").get(0).path("end").asInt());
        assertEquals(10, body.path("intervals").get(0).path("length").asInt());

        // 落库：SERIAL 类型、数量、合并后的单条区间（不展开逐件记录）
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?"
                + " AND batch_kind = 'SERIAL' AND quantity = 10", Integer.class, batchKey));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_serial_interval WHERE batch_key = ? AND kind = 'ROOT'",
                Integer.class, batchKey));

        // 序号域查询：当前持有，区间冻结
        MvcResult intervals = mockMvc.perform(get("/api/serial-batches/" + batchKey + "/intervals"))
                .andExpect(status().isOk()).andReturn();
        JsonNode domain = objectMapper.readTree(intervals.getResponse().getContentAsString());
        assertTrue(domain.path("current").asBoolean());
        assertEquals("SERIAL", domain.path("batchKind").asText());
        assertEquals(1, domain.path("intervals").size());
    }

    @Test
    void registerMillionSerials_storedAsSingleInterval() throws Exception {
        String batchKey = "SB-MILLION-" + unique();
        register(registerBody("RID-" + unique(), "op-1", batchKey, "PROD-M-" + unique(),
                1_000_000, List.of(iv(1, 1_000_000))), 201);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_serial_interval WHERE batch_key = ?",
                Integer.class, batchKey), "百万序号只存区间端点，不展开记录");
        MvcResult lookup = mockMvc.perform(get("/api/serial-batches/lookup")
                        .param("productCode", productOf(batchKey)).param("serial", "999999"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(batchKey, objectMapper.readTree(lookup.getResponse().getContentAsString())
                .path("batchKey").asText());
    }

    // ---------- 失败分支 ----------

    @Test
    void registerInvalidIntervals_return422() throws Exception {
        String product = "PROD-V-" + unique();
        // 区间内重叠
        register(registerBody("RID-" + unique(), "op", "SB-V1-" + unique(), product, 10,
                List.of(iv(1, 5), iv(5, 10))), 422);
        // 区间外：下界
        register(registerBody("RID-" + unique(), "op", "SB-V2-" + unique(), product, 5,
                List.of(iv(0, 4))), 422);
        // 区间外：上界
        register(registerBody("RID-" + unique(), "op", "SB-V3-" + unique(), product, 5,
                List.of(iv(999_997, 1_000_001))), 422);
        // 起点大于终点
        register(registerBody("RID-" + unique(), "op", "SB-V4-" + unique(), product, 5,
                List.of(iv(6, 2))), 422);
        // 区间总长度与数量不一致
        register(registerBody("RID-" + unique(), "op", "SB-V5-" + unique(), product, 6,
                List.of(iv(1, 5))), 422);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE product_code = ?",
                Integer.class, product), "失败请求不得留下任何批次");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class),
                "失败请求不占幂等键");
    }

    @Test
    void registerInvalidRequestShape_return400() throws Exception {
        // 缺 operator
        String noOperator = "{\"requestId\":\"r1\",\"expectedVersion\":0,\"batchKey\":\"b1\","
                + "\"productCode\":\"p\",\"batchNo\":\"n\",\"producedAt\":\"2026-01-01T00:00:00Z\","
                + "\"requiredTests\":[\"t1\"],\"quantity\":1,\"intervals\":[{\"start\":1,\"end\":1}]}";
        mockMvc.perform(post("/api/serial-batches").contentType(MediaType.APPLICATION_JSON)
                        .content(noOperator)).andExpect(status().isBadRequest());
        // 缺 requestId
        String noRequestId = "{\"operator\":\"op\",\"expectedVersion\":0,\"batchKey\":\"b2\","
                + "\"productCode\":\"p\",\"batchNo\":\"n\",\"producedAt\":\"2026-01-01T00:00:00Z\","
                + "\"requiredTests\":[\"t1\"],\"quantity\":1,\"intervals\":[{\"start\":1,\"end\":1}]}";
        mockMvc.perform(post("/api/serial-batches").contentType(MediaType.APPLICATION_JSON)
                        .content(noRequestId)).andExpect(status().isBadRequest());
        // 11 个区间
        StringBuilder intervals = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            intervals.append(i == 0 ? "" : ",").append("{\"start\":").append(i * 2 + 1)
                    .append(",\"end\":").append(i * 2 + 1).append("}");
        }
        String eleven = "{\"requestId\":\"r3\",\"operator\":\"op\",\"expectedVersion\":0,"
                + "\"batchKey\":\"b3\",\"productCode\":\"p\",\"batchNo\":\"n\","
                + "\"producedAt\":\"2026-01-01T00:00:00Z\",\"requiredTests\":[\"t1\"],"
                + "\"quantity\":11,\"intervals\":[" + intervals + "]}";
        mockMvc.perform(post("/api/serial-batches").contentType(MediaType.APPLICATION_JSON)
                        .content(eleven)).andExpect(status().isBadRequest());
    }

    @Test
    void duplicateBatchKey_returns409() throws Exception {
        String batchKey = "SB-DUP-" + unique();
        String product = "PROD-D-" + unique();
        register(registerBody("RID-" + unique(), "op", batchKey, product, 5, List.of(iv(1, 5))), 201);
        register(registerBody("RID-" + unique(), "op", batchKey, product, 5, List.of(iv(6, 10))), 409);
    }

    // ---------- 根域按产品唯一 ----------

    @Test
    void rootDomainUniquePerProduct_includesSplitAndRecalledRoots() throws Exception {
        String product = "PROD-U-" + unique();
        String rootA = "SB-UA-" + unique();
        register(registerBody("RID-" + unique(), "op", rootA, product, 10, List.of(iv(1, 10))), 201);
        // 与现有根域相交 → 409
        register(registerBody("RID-" + unique(), "op", "SB-UB-" + unique(), product, 5,
                List.of(iv(5, 9))), 409);
        register(registerBody("RID-" + unique(), "op", "SB-UC-" + unique(), product, 5,
                List.of(iv(8, 12))), 409);
        // 不同产品同一区间 → 允许
        register(registerBody("RID-" + unique(), "op", "SB-UD-" + unique(),
                "PROD-U2-" + unique(), 10, List.of(iv(1, 10))), 201);
        // 同产品不相交 → 允许
        register(registerBody("RID-" + unique(), "op", "SB-UE-" + unique(), product, 5,
                List.of(iv(11, 15))), 201);

        // 根批拆分后域仍占用（父域只作历史，仍阻断新根批）
        releaseBatch(rootA);
        serialSplit(rootA, "RID-" + unique(), List.of(
                child("SB-UAC1-" + unique(), List.of(iv(1, 5))),
                child("SB-UAC2-" + unique(), List.of(iv(6, 10)))), 201);
        register(registerBody("RID-" + unique(), "op", "SB-UF-" + unique(), product, 2,
                List.of(iv(9, 10))), 409);

        // 根批召回后域仍占用
        String rootG = "SB-UG-" + unique();
        register(registerBody("RID-" + unique(), "op", rootG, product, 3,
                List.of(iv(16, 18))), 201);
        releaseBatch(rootG);
        recall(rootG, "CK-RC-" + unique(), 201);
        register(registerBody("RID-" + unique(), "op", "SB-UH-" + unique(), product, 2,
                List.of(iv(17, 18))), 409);
    }

    // ---------- requestId 幂等 ----------

    @Test
    void requestId_replayReturnsFirstResponse_changedParamsConflict_failureNotOccupied()
            throws Exception {
        String batchKey = "SB-IDEM-" + unique();
        String product = "PROD-I-" + unique();
        String requestId = "RID-" + unique();
        String body = registerBody(requestId, "op-1", batchKey, product, 10, List.of(iv(1, 10)));
        MvcResult first = register(body, 201);
        MvcResult replay = register(body, 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "同键同参重放首次完整响应");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, batchKey));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM request_log WHERE request_id = ?",
                Integer.class, requestId));

        // 同 requestId 改参（数量不同）→ 409
        register(registerBody(requestId, "op-1", batchKey, product, 9, List.of(iv(1, 9))), 409);
        // 同 requestId 改操作者 → 409
        register(registerBody(requestId, "op-2", batchKey, product, 10, List.of(iv(1, 10))), 409);
        // 同 requestId 改期望版本 → 409
        String changedVersion = objectMapper.writeValueAsString(new RegisterCmd(requestId, "op-1",
                1L, batchKey, product, "LOT-1", Instant.parse("2026-01-02T03:04:05Z"),
                List.of("t1"), 10, List.of(iv(1, 10))));
        mockMvc.perform(post("/api/serial-batches").contentType(MediaType.APPLICATION_JSON)
                .content(changedVersion)).andExpect(status().isConflict());

        // 失败不占键：先 422，修正参数后同 requestId 成功
        String failKey = "RID-" + unique();
        String failBatch = "SB-IDEM2-" + unique();
        register(registerBody(failKey, "op", failBatch, product, 6, List.of(iv(20, 24))), 422);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, failKey));
        register(registerBody(failKey, "op", failBatch, product, 5, List.of(iv(20, 24))), 201);

        // 全局 requestId：同一 requestId 用于另一操作类型 → 409
        releaseBatch(failBatch);
        serialSplitBody(failBatch, failKey, List.of(
                child("SB-IDEM3-" + unique(), List.of(iv(20, 22))),
                child("SB-IDEM4-" + unique(), List.of(iv(23, 24)))), 409);
    }

    // ---------- 并发 ----------

    @Test
    void concurrentRegister_overlappingDomain_exactlyOneWins() throws Exception {
        String product = "PROD-CR-" + unique();
        String keyA = "SB-CRA-" + unique();
        String keyB = "SB-CRB-" + unique();
        String bodyA = registerBody("RID-" + unique(), "op", keyA, product, 10, List.of(iv(1, 10)));
        String bodyB = registerBody("RID-" + unique(), "op", keyB, product, 10, List.of(iv(5, 14)));
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/serial-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyA)),
                () -> callStatus(post("/api/serial-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyB)));
        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        assertTrue((first == 201 && second == 409) || (first == 409 && second == 201),
                "域相交的并发登记必须恰好一个成功，实际: " + first + "," + second);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE product_code = ?",
                Integer.class, product));
    }

    @Test
    void concurrentSameRequestId_register_bothReplayFirstResponse() throws Exception {
        String batchKey = "SB-CID-" + unique();
        String body = registerBody("RID-" + unique(), "op", batchKey,
                "PROD-CI-" + unique(), 10, List.of(iv(1, 10)));
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/serial-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/serial-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body)));
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发不得暴露唯一键错误");
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, batchKey));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class));
    }

    // ---------- helpers ----------

    private record RegisterCmd(String requestId, String operator, long expectedVersion,
                               String batchKey, String productCode, String batchNo,
                               Instant producedAt, List<String> requiredTests, int quantity,
                               List<IntervalSpec> intervals) {
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private IntervalSpec iv(int start, int end) {
        return new IntervalSpec(start, end);
    }

    private String registerBody(String requestId, String operator, String batchKey,
                                String productCode, int quantity, List<IntervalSpec> intervals)
            throws Exception {
        return objectMapper.writeValueAsString(new RegisterCmd(requestId, operator, 0L, batchKey,
                productCode, "LOT-1", Instant.parse("2026-01-02T03:04:05Z"), List.of("t1"),
                quantity, intervals));
    }

    private MvcResult register(String body, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/serial-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private String productOf(String batchKey) {
        return jdbc.queryForObject("SELECT product_code FROM batch WHERE batch_key = ?",
                String.class, batchKey);
    }

    private record SplitChildCmd(String batchKey, String batchNo, List<IntervalSpec> intervals) {
    }

    private record SplitCmd(String requestId, String operator, long expectedVersion,
                            List<SplitChildCmd> children) {
    }

    private SplitChildCmd child(String batchKey, List<IntervalSpec> intervals) {
        return new SplitChildCmd(batchKey, "LOT-" + batchKey.substring(batchKey.length() - 4),
                intervals);
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
                        .content("{\"commandKey\":\"CK-A1-" + unique() + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-A2-" + unique() + "\"}"))
                .andExpect(status().isCreated());
    }

    private void recall(String batchKey, String commandKey, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"r\"}"))
                .andExpect(status().is(expected));
    }

    private void serialSplit(String parentKey, String requestId, List<SplitChildCmd> children,
                             int expected) throws Exception {
        serialSplitBody(parentKey, requestId, children, expected);
    }

    private MvcResult serialSplitBody(String parentKey, String requestId,
                                      List<SplitChildCmd> children, int expected) throws Exception {
        String body = objectMapper.writeValueAsString(
                new SplitCmd(requestId, "op-1", 0L, children));
        return mockMvc.perform(post("/api/serial-batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private int callStatus(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
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

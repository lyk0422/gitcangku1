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
 * 压力曲线检验并发与血缘交互测试（真实 H2 事务、真实线程）：
 * 与最终批准串行裁决、并发曲线唯一落库、同 requestId 并发重放、
 * 拆分子批不继承曲线结论、祖先召回禁止子批提交曲线。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PressureProfileConcurrencyTest {

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

    @Test
    void concurrentCurveFailVsFinalApproval_serializedExactlyOneWins() throws Exception {
        String batchKey = "BK-PC-RACE-" + unique();
        createBatch(batchKey, List.of("t1"));
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp");
        approve(batchKey, "qa", "QUALITY", "CK-Q", 201);

        String failCurve = curveBody("RQ-RACE-1", "curve-op",
                pts(pt(0, "10"), pt(5, "9.5"), pt(10, "10"), pt(15, "9.4")));
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/pressure-profiles")
                        .contentType(MediaType.APPLICATION_JSON).content(failCurve)),
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "ops").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-O\"}"))
        );

        int curve = results.get(0).get(30, TimeUnit.SECONDS);
        int approval = results.get(1).get(30, TimeUnit.SECONDS);
        String finalStatus = currentStatus(batchKey);
        Integer profiles = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pressure_profile WHERE batch_key = ?", Integer.class, batchKey);
        Integer approvals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE batch_key = ?", Integer.class, batchKey);
        if (curve == 201) {
            // 曲线 FAIL 先落定：批次 REJECTED，最终批准必然 409，曲线记录保留
            assertEquals(409, approval);
            assertEquals("REJECTED", finalStatus);
            assertEquals(1, profiles);
            assertEquals(1, approvals);
        } else {
            // 最终批准先落定：批次 RELEASED，曲线提交 409，不留曲线记录
            assertEquals(409, curve);
            assertEquals(201, approval);
            assertEquals("RELEASED", finalStatus);
            assertEquals(0, profiles);
            assertEquals(2, approvals);
        }
    }

    @Test
    void concurrentTwoCurves_exactlyOnePersisted() throws Exception {
        String batchKey = "BK-PC-TWO-" + unique();
        createBatch(batchKey, List.of("t1"));

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/pressure-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(curveBody("RQ-A", "op-a", pts(pt(0, "10"), pt(10, "10"))))),
                () -> callStatus(post("/api/batches/" + batchKey + "/pressure-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(curveBody("RQ-B", "op-b", pts(pt(0, "10"), pt(10, "9")))))
        );

        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        // 每批次仅一条曲线记录：恰一个 201，另一个 409；不产生唯一键异常泄漏
        assertTrue((first == 201 && second == 409) || (first == 409 && second == 201),
                "实际状态码: " + first + ", " + second);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pressure_profile WHERE batch_key = ?", Integer.class, batchKey));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM test_result WHERE batch_key = ? AND test_item = 'PRESSURE_PROFILE'",
                Integer.class, batchKey));
    }

    @Test
    void concurrentSameRequestId_bothGetFirstResponse() throws Exception {
        String batchKey = "BK-PC-IDEM-" + unique();
        createBatch(batchKey, List.of("t1"));

        String body = curveBody("RQ-SAME", "op", pts(pt(0, "10"), pt(10, "10")));
        List<Future<String>> results = runConcurrent(
                () -> callBody(post("/api/batches/" + batchKey + "/pressure-profiles")
                        .contentType(MediaType.APPLICATION_JSON).content(body), 201),
                () -> callBody(post("/api/batches/" + batchKey + "/pressure-profiles")
                        .contentType(MediaType.APPLICATION_JSON).content(body), 201)
        );
        String first = results.get(0).get(30, TimeUnit.SECONDS);
        String second = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(first, second, "同键同参并发重放均返回首次完整响应");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pressure_profile WHERE batch_key = ?", Integer.class, batchKey));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'SUBMIT_PRESSURE_PROFILE'",
                Integer.class));
    }

    @Test
    void splitChildren_doNotInheritCurveAndMustRemeasure() throws Exception {
        String parentKey = "BK-PC-PARENT-" + unique();
        createBatch(parentKey, List.of("t1"));
        submitCurve(parentKey, curveBody("RQ-PARENT", "op", pts(pt(0, "10"), pt(10, "10"))), 201);
        submitTest(parentKey, "TK-1", "t1", "PASS", "insp");
        approve(parentKey, "qa", "QUALITY", "CK-Q", 201);
        approve(parentKey, "ops", "OPERATIONS", "CK-O", 201);

        String child1 = "BK-PC-C1-" + unique();
        String child2 = "BK-PC-C2-" + unique();
        String splitBody = "{\"commandKey\":\"CK-SPLIT\",\"children\":["
                + "{\"batchKey\":\"" + child1 + "\",\"batchNo\":\"LOT-C1\"},"
                + "{\"batchKey\":\"" + child2 + "\",\"batchNo\":\"LOT-C2\"}]}";
        mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(splitBody))
                .andExpect(status().isCreated());

        // 子批不继承父批曲线结论：无曲线记录，历史为 null，必须重新测
        for (String child : List.of(child1, child2)) {
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pressure_profile WHERE batch_key = ?", Integer.class, child));
            assertTrue(history(child).path("pressureProfile").isNull());
            assertEquals("QUARANTINED", currentStatus(child));
            submitCurve(child, curveBody("RQ-" + child, "op", pts(pt(0, "10"), pt(10, "10"))), 201);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pressure_profile WHERE batch_key = ?", Integer.class, child));
        }
        // 父批曲线记录保留；SPLIT 终态不允许再提交曲线
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pressure_profile WHERE batch_key = ?", Integer.class, parentKey));
        submitCurve(parentKey, curveBody("RQ-PARENT-2", "op", pts(pt(0, "10"), pt(10, "10"))), 409);
    }

    @Test
    void ancestorRecall_blocksChildCurveSubmission() throws Exception {
        String parentKey = "BK-PC-RCP-" + unique();
        createBatch(parentKey, List.of("t1"));
        submitTest(parentKey, "TK-1", "t1", "PASS", "insp");
        approve(parentKey, "qa", "QUALITY", "CK-Q", 201);
        approve(parentKey, "ops", "OPERATIONS", "CK-O", 201);

        String child = "BK-PC-RCC-" + unique();
        String splitBody = "{\"commandKey\":\"CK-SPLIT\",\"children\":["
                + "{\"batchKey\":\"" + child + "\",\"batchNo\":\"LOT-C1\"},"
                + "{\"batchKey\":\"" + child + "-B\",\"batchNo\":\"LOT-C2\"}]}";
        mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(splitBody))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/batches/" + parentKey + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-R\",\"reason\":\"祖先召回\"}"))
                .andExpect(status().isCreated());

        // 祖先召回后子批禁止提交曲线（422），且不留痕
        submitCurve(child, curveBody("RQ-RCC", "op", pts(pt(0, "10"), pt(10, "10"))), 422);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pressure_profile WHERE batch_key = ?", Integer.class, child));
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

    private void submitCurve(String batchKey, String body, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/pressure-profiles")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private void createBatch(String batchKey, List<String> items) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-" + batchKey, batchKey,
                "PROD-1", "LOT-1", Instant.parse("2026-01-02T03:04:05Z"), items));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests) {
    }

    private void submitTest(String batchKey, String testKey, String item, String result,
                            String inspector) throws Exception {
        String body = "{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"" + testKey
                + "\",\"testItem\":\"" + item + "\",\"result\":\"" + result
                + "\",\"inspector\":\"" + inspector + "\"}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
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

    private int callStatus(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
        try {
            return mockMvc.perform(req).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String callBody(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                            int expectedStatus) {
        try {
            MvcResult result = mockMvc.perform(req).andExpect(status().is(expectedStatus)).andReturn();
            return result.getResponse().getContentAsString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SafeVarargs
    private <T> List<Future<T>> runConcurrent(Callable<T>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CyclicBarrier barrier = new CyclicBarrier(tasks.length);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
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

package com.example.starter.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 保养工单并发与幂等边界测试：真实 H2 数据库上的并发写协调，断言响应与最终数据一致性。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkOrderConcurrencyTests {

    private static final String WINDOW_START = "2026-01-01T12:00:00Z";
    private static final String WINDOW_END = "2026-01-01T14:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM work_order");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM equipment");
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private void register(String equipmentId, long periodMinutes) throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":%d}
                                """.formatted(equipmentId, equipmentId, periodMinutes)))
                .andExpect(status().isCreated());
    }

    private void addReading(String equipmentId, String requestId, long expectedVersion,
                            String readingId, String sampledAt, long cumulativeMinutes) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                cumulativeMinutes)))
                .andExpect(status().isCreated());
    }

    private String createBody(String workOrderKey, long expectedVersion) {
        return """
                {"workOrderKey":"%s","expectedVersion":%d,"windowStart":"%s","windowEnd":"%s"}
                """.formatted(workOrderKey, expectedVersion, WINDOW_START, WINDOW_END);
    }

    /** 并发同 workOrderKey 同参建单：全部得到同一成功结果，工单只建一次。 */
    @Test
    void concurrentSameKeyCreate_replaysSingleOrder() throws Exception {
        register("eq-woc", 1000);
        addReading("eq-woc", "wc-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = createBody("wo-cc", 2);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-woc/work-orders", body);
            }));
        }
        gate.countDown();

        Set<String> bodies = new HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(), "并发同键同参建单应全部重放成功结果");
            bodies.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, bodies.size(), "所有并发响应应完全相同（重放同一成功结果）");

        mockMvc.perform(get("/api/equipment/eq-woc/work-orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        Integer orderCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_order WHERE work_order_key = 'wo-cc'", Integer.class);
        assertEquals(1, orderCount, "同一 workOrderKey 只应建单一次");
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'WO|wo-cc|CREATE|v1'",
                Integer.class);
        assertEquals(1, idemCount, "同一建单键只应占一个幂等键");
    }

    /** 并发开始与取消（同一期望工单版本）：恰有一个成功，最终状态一致。 */
    @Test
    void concurrentStartVsCancel_exactlyOneWins() throws Exception {
        register("eq-wsc", 1000);
        addReading("eq-wsc", "wsc-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        postJson("/api/equipment/eq-wsc/work-orders", createBody("wo-sc", 2));

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> startFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-wsc/work-orders/wo-sc/start", """
                    {"expectedWorkOrderVersion":1}
                    """);
        });
        Future<MvcResult> cancelFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-wsc/work-orders/wo-sc/cancel", """
                    {"expectedWorkOrderVersion":1}
                    """);
        });
        gate.countDown();

        int startStatus = startFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int cancelStatus = cancelFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        assertTrue((startStatus == 200 && cancelStatus == 409)
                        || (startStatus == 409 && cancelStatus == 200),
                "开始与取消并发应恰有一个成功，实际：start=" + startStatus + ", cancel=" + cancelStatus);

        // 工单版本只推进一次，最终状态与胜者一致
        MvcResult state = mockMvc.perform(get("/api/equipment/eq-wsc/work-orders/wo-sc")).andReturn();
        String body = state.getResponse().getContentAsString();
        assertTrue(body.contains("\"workOrderVersion\":2"), "工单版本应只推进一次：" + body);
        if (startStatus == 200) {
            assertTrue(body.contains("\"status\":\"STARTED\""), body);
            // 已开始工单不可取消
            mockMvc.perform(post("/api/equipment/eq-wsc/work-orders/wo-sc/cancel")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"expectedWorkOrderVersion":2}
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("WORK_ORDER_ALREADY_STARTED"));
        } else {
            assertTrue(body.contains("\"status\":\"CANCELLED\""), body);
        }
    }

    /** 并发批量登记与关闭（同一期望工单版本）：恰有一个先生效；最终快照与读数一致。 */
    @Test
    void concurrentBatchVsClose_consistentFinalState() throws Exception {
        register("eq-wbc", 1000);
        addReading("eq-wbc", "wbc-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        postJson("/api/equipment/eq-wbc/work-orders", createBody("wo-bc", 2));
        postJson("/api/equipment/eq-wbc/work-orders/wo-bc/start", """
                {"expectedWorkOrderVersion":1}
                """);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> batchFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-wbc/work-orders/wo-bc/readings", """
                    {"expectedVersion":2,"expectedWorkOrderVersion":2,"readings":[
                     {"readingId":"r2","sampledAt":"2026-01-01T12:30:00Z","cumulativeMinutes":150}]}
                    """);
        });
        Future<MvcResult> closeFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-wbc/work-orders/wo-bc/close", """
                    {"expectedWorkOrderVersion":2}
                    """);
        });
        gate.countDown();

        int batchStatus = batchFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int closeStatus = closeFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        assertTrue((batchStatus == 201 && closeStatus == 409)
                        || (batchStatus == 409 && closeStatus == 200),
                "批量登记与关闭并发应恰有一个先生效，实际：batch=" + batchStatus + ", close=" + closeStatus);

        if (batchStatus == 201) {
            // 批量先生效：关闭因工单版本冲突失败；按新版本重试关闭后快照须包含批内读数
            mockMvc.perform(post("/api/equipment/eq-wbc/work-orders/wo-bc/close")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"expectedWorkOrderVersion":3}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CLOSED"))
                    .andExpect(jsonPath("$.snapshotLastReadingId").value("r2"))
                    .andExpect(jsonPath("$.snapshotLastCumulativeMinutes").value(150));
            mockMvc.perform(get("/api/equipment/eq-wbc/readings"))
                    .andExpect(jsonPath("$.length()").value(2));
        } else {
            // 关闭先生效：批量登记因工单已完结失败，不得留下任何读数
            mockMvc.perform(get("/api/equipment/eq-wbc/readings"))
                    .andExpect(jsonPath("$.length()").value(1));
            mockMvc.perform(get("/api/equipment/eq-wbc/work-orders/wo-bc"))
                    .andExpect(jsonPath("$.status").value("CLOSED"))
                    .andExpect(jsonPath("$.snapshotLastReadingId").value("r1"))
                    .andExpect(jsonPath("$.snapshotLastCumulativeMinutes").value(100));
        }
        // 无论哪种时序：工单版本单调推进，无半成品状态
        Integer readingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading WHERE equipment_id = 'eq-wbc'", Integer.class);
        Integer revisionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading_revision WHERE equipment_id = 'eq-wbc'", Integer.class);
        assertEquals(readingCount, revisionCount, "每条读数应对应一条初始修订，不得有半成品状态");
    }

    /** 并发同键同参批量登记：全部重放同一成功结果，读数只落一次。 */
    @Test
    void concurrentSameBatch_replaysSingleEffect() throws Exception {
        register("eq-wbr", 1000);
        addReading("eq-wbr", "wbr-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        postJson("/api/equipment/eq-wbr/work-orders", createBody("wo-br", 2));
        postJson("/api/equipment/eq-wbr/work-orders/wo-br/start", """
                {"expectedWorkOrderVersion":1}
                """);

        int threads = 6;
        CountDownLatch gate = new CountDownLatch(1);
        String batch = """
                {"expectedVersion":2,"expectedWorkOrderVersion":2,"readings":[
                 {"readingId":"r2","sampledAt":"2026-01-01T12:30:00Z","cumulativeMinutes":150},
                 {"readingId":"r3","sampledAt":"2026-01-01T13:00:00Z","cumulativeMinutes":180}]}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-wbr/work-orders/wo-br/readings", batch);
            }));
        }
        gate.countDown();

        Set<String> bodies = new HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(), "并发同键同参批量登记应全部重放成功");
            bodies.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, bodies.size(), "所有并发响应应完全相同");

        // 业务效果只发生一次：两条新读数、工单版本仅推进一次
        mockMvc.perform(get("/api/equipment/eq-wbr/readings"))
                .andExpect(jsonPath("$.length()").value(3));
        mockMvc.perform(get("/api/equipment/eq-wbr/work-orders/wo-br"))
                .andExpect(jsonPath("$.workOrderVersion").value(3))
                .andExpect(jsonPath("$.readingsInWindow").value(2));
        mockMvc.perform(get("/api/equipment/eq-wbr/status"))
                .andExpect(jsonPath("$.version").value(3));
    }
}

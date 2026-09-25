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

import com.example.starter.maintenance.domain.WorkOrder;

/**
 * 保养工单并发与幂等边界测试：真实 H2 数据库上的并发状态机裁决与 workOrderKey 重放，
 * 断言响应与最终数据一致性，不靠打印或休眠充当验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkOrderConcurrencyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM work_order_idempotency");
        jdbc.update("DELETE FROM maintenance_snapshot");
        jdbc.update("DELETE FROM work_order");
        jdbc.update("DELETE FROM idempotency_request");
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

    /** 登记设备 + 基线 r1=100 + 认证 + 建单 CREATED（窗口 12:00~18:00）；设备版本停在 4。 */
    private void setupCreatedWorkOrder(String equipmentId, String workOrderId) throws Exception {
        postJson("/api/equipment", """
                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":10000}
                """.formatted(equipmentId, equipmentId)).getResponse().getStatus();
        postJson("/api/equipment/%s/readings".formatted(equipmentId), """
                {"requestId":"add-%s","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                """.formatted(equipmentId)).getResponse().getStatus();
        postJson("/api/equipment/%s/readings/r1/certification".formatted(equipmentId), """
                {"requestId":"cert-%s","expectedVersion":2}
                """.formatted(equipmentId)).getResponse().getStatus();
        mockMvc.perform(post("/api/equipment/%s/work-orders".formatted(equipmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"create-%s","expectedVersion":3,"workOrderId":"%s",
                                 "baselineReadingId":"r1",
                                 "windowStart":"2026-01-01T12:00:00Z",
                                 "windowEnd":"2026-01-01T18:00:00Z"}
                                """.formatted(workOrderId, workOrderId)))
                .andExpect(status().isCreated());
    }

    /** 并发同 workOrderKey 同参数开始工单：全部得到同一成功结果，工单只开始一次。 */
    @Test
    void concurrentSameKeyStart_replaysSingleEffect() throws Exception {
        setupCreatedWorkOrder("eq-cstart", "wo-1");
        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"workOrderKey":"same-start","expectedVersion":4,"workOrderVersion":1}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-cstart/work-orders/wo-1/start", body);
            }));
        }
        gate.countDown();

        Set<String> responseBodies = new HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(),
                    "并发同键同参开始工单应全部重放成功结果");
            responseBodies.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, responseBodies.size(), "所有并发响应应完全相同（重放同一成功结果）");

        mockMvc.perform(get("/api/equipment/eq-cstart/work-orders/wo-1"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(get("/api/equipment/eq-cstart/status"))
                .andExpect(jsonPath("$.version").value(5));
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_order_idempotency WHERE work_order_key = 'same-start'",
                Integer.class);
        assertEquals(1, idemCount, "同一 workOrderKey 只应占一个幂等键");
    }

    /** 并发开始与取消（同一工单版本）：按提交顺序恰有一个胜出，状态机不产生半成品。 */
    @Test
    void concurrentStartVsCancel_exactlyOneWins() throws Exception {
        setupCreatedWorkOrder("eq-race", "wo-1");
        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> startFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-race/work-orders/wo-1/start", """
                    {"workOrderKey":"race-start","expectedVersion":4,"workOrderVersion":1}
                    """);
        });
        Future<MvcResult> cancelFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-race/work-orders/wo-1/cancel", """
                    {"workOrderKey":"race-cancel","expectedVersion":4,"workOrderVersion":1}
                    """);
        });
        gate.countDown();

        int startStatus = startFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int cancelStatus = cancelFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        assertTrue(
                (startStatus == 201 && cancelStatus == 409)
                        || (startStatus == 409 && cancelStatus == 201),
                "开始与取消并发应恰有一个成功，实际：start=" + startStatus
                        + ", cancel=" + cancelStatus);

        // 工单版本恰好推进一次；终态唯一确定
        String finalStatus = jdbc.queryForObject(
                "SELECT status FROM work_order WHERE equipment_id = 'eq-race' AND work_order_id = 'wo-1'",
                String.class);
        Long woVersion = jdbc.queryForObject(
                "SELECT version FROM work_order WHERE equipment_id = 'eq-race' AND work_order_id = 'wo-1'",
                Long.class);
        Long equipmentVersion = jdbc.queryForObject(
                "SELECT version FROM equipment WHERE equipment_id = 'eq-race'", Long.class);
        assertEquals(2L, woVersion, "工单版本应只推进一次");
        assertEquals(5L, equipmentVersion, "设备版本应只推进一次");
        assertTrue(
                WorkOrder.IN_PROGRESS.equals(finalStatus)
                        || WorkOrder.CANCELLED.equals(finalStatus),
                "终态必须为 IN_PROGRESS 或 CANCELLED，实际：" + finalStatus);

        if (WorkOrder.CANCELLED.equals(finalStatus)) {
            // 取消胜出方可重新建单
            mockMvc.perform(post("/api/equipment/eq-race/work-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"workOrderKey":"after-cancel","expectedVersion":5,"workOrderId":"wo-2",
                                     "baselineReadingId":"r1",
                                     "windowStart":"2026-01-02T12:00:00Z",
                                     "windowEnd":"2026-01-02T18:00:00Z"}
                                    """))
                    .andExpect(status().isCreated());
        }
    }

    /** 并发两个不同批次（相同期望版本）：按提交顺序一批成功，另一批版本失配 422 且全部回滚。 */
    @Test
    void concurrentBatches_oneCommitsOtherRollsBack() throws Exception {
        setupCreatedWorkOrder("eq-batch", "wo-1");
        postJson("/api/equipment/eq-batch/work-orders/wo-1/start", """
                {"workOrderKey":"b-start","expectedVersion":4,"workOrderVersion":1}
                """);

        CountDownLatch gate = new CountDownLatch(1);
        String batchA = """
                {"workOrderKey":"b-a","expectedVersion":5,"workOrderVersion":2,
                 "readings":[
                   {"readingId":"a1","sampledAt":"2026-01-01T13:00:00Z","cumulativeMinutes":200}
                 ]}
                """;
        String batchB = """
                {"workOrderKey":"b-b","expectedVersion":5,"workOrderVersion":2,
                 "readings":[
                   {"readingId":"b1","sampledAt":"2026-01-01T14:00:00Z","cumulativeMinutes":250}
                 ]}
                """;
        Future<MvcResult> futureA = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-batch/work-orders/wo-1/readings", batchA);
        });
        Future<MvcResult> futureB = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-batch/work-orders/wo-1/readings", batchB);
        });
        gate.countDown();

        int statusA = futureA.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int statusB = futureB.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int success = (statusA == 201 ? 1 : 0) + (statusB == 201 ? 1 : 0);
        int rejected = (statusA == 422 ? 1 : 0) + (statusB == 422 ? 1 : 0);
        assertEquals(1, success, "并发批次应恰有一批成功");
        assertEquals(1, rejected, "失败批次必须整体回滚并返回 422");

        // 仅成功批次的读数入库；设备版本只加一（版本失配批次回滚）
        mockMvc.perform(get("/api/equipment/eq-batch/readings"))
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/equipment/eq-batch/status"))
                .andExpect(jsonPath("$.version").value(6));

        // 失败批次修正期望版本后可重新提交（失败不占键）
        MvcResult retry = postJson("/api/equipment/eq-batch/work-orders/wo-1/readings", """
                {"workOrderKey":"b-b","expectedVersion":6,"workOrderVersion":2,
                 "readings":[
                   {"readingId":"b1","sampledAt":"2026-01-01T15:00:00Z","cumulativeMinutes":300}
                 ]}
                """);
        assertEquals(201, retry.getResponse().getStatus());
        mockMvc.perform(get("/api/equipment/eq-batch/readings"))
                .andExpect(jsonPath("$.length()").value(3));
    }
}

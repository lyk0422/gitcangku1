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
 * 能源隔离并发与幂等边界测试：真实 H2 上的并发写协调，断言响应与最终数据一致性。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IsolationConcurrencyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM rejected_reading");
        jdbc.update("DELETE FROM lock_event");
        jdbc.update("DELETE FROM lock_point");
        jdbc.update("DELETE FROM isolation_order");
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

    private void register(String equipmentId, long periodMinutes) throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":%d}
                                """.formatted(equipmentId, equipmentId, periodMinutes)))
                .andExpect(status().isCreated());
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    /** 并发同 requestId 创建隔离令：全部得到同一成功快照，业务效果只发生一次。 */
    @Test
    void concurrentCreateSameRequestId_singleEffect() throws Exception {
        register("eq-cc", 1000);
        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"cc-1","orderId":"ord-cc","operator":"op",
                 "lockPointIds":["LP-A","LP-B"]}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-cc/isolations", body);
            }));
        }
        gate.countDown();

        Set<String> bodies = new HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(), "并发同键同参应全部重放成功结果");
            bodies.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, bodies.size(), "所有并发响应应完全相同");

        mockMvc.perform(get("/api/equipment/eq-cc/isolations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].lockPoints.length()").value(2));
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'cc-1'", Integer.class);
        assertEquals(1, idemCount, "同一 requestId 只应占一个幂等键");
    }

    /** 并发双角色确认同一隔离令：均成功，事件各一条，令恰好生效一次。 */
    @Test
    void concurrentConfirmTwoRoles_effectiveExactlyOnce() throws Exception {
        register("eq-cf", 1000);
        postJson("/api/equipment/eq-cf/isolations", """
                {"requestId":"iso-cf","orderId":"ord-cf","operator":"op",
                 "lockPointIds":["LP-A","LP-B"]}
                """);
        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> elecFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cf/isolations/ord-cf/confirmations", """
                    {"requestId":"cf-e","role":"ELECTRICIAN","operator":"e1",
                     "pointIds":["LP-A","LP-B"]}
                    """);
        });
        Future<MvcResult> mechFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cf/isolations/ord-cf/confirmations", """
                    {"requestId":"cf-m","role":"MECHANIC","operator":"m1",
                     "pointIds":["LP-A","LP-B"]}
                    """);
        });
        gate.countDown();

        assertEquals(201, elecFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus());
        assertEquals(201, mechFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus());

        mockMvc.perform(get("/api/equipment/eq-cf/isolations/ord-cf"))
                .andExpect(jsonPath("$.state").value("ISOLATED"))
                .andExpect(jsonPath("$.confirmationCount").value(4));
        Integer confirmEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lock_event WHERE order_id = 'ord-cf' AND event_type = 'CONFIRM'",
                Integer.class);
        assertEquals(4, confirmEvents, "每个角色对每个锁定点恰好一条确认事件");
    }

    /** 隔离生效与新增读数并发：按提交顺序裁决——读数要么在生效前写入，要么被 409 拒绝并固化证据。 */
    @Test
    void concurrentIsolationEffectiveVsReading_submissionOrderWins() throws Exception {
        register("eq-race2", 1000);
        postJson("/api/equipment/eq-race2/isolations", """
                {"requestId":"iso-r2","orderId":"ord-r2","operator":"op","lockPointIds":["LP-A"]}
                """);
        postJson("/api/equipment/eq-race2/isolations/ord-r2/confirmations", """
                {"requestId":"cf-r2e","role":"ELECTRICIAN","operator":"e1","pointIds":["LP-A"]}
                """);

        CountDownLatch gate = new CountDownLatch(1);
        // 第二角色确认（使隔离生效）与新增读数并发竞速
        Future<MvcResult> confirmFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-race2/isolations/ord-r2/confirmations", """
                    {"requestId":"cf-r2m","role":"MECHANIC","operator":"m1","pointIds":["LP-A"]}
                    """);
        });
        Future<MvcResult> readingFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-race2/readings", """
                    {"requestId":"rd-race","expectedVersion":1,"readingId":"r1",
                     "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100,"operator":"reader"}
                    """);
        });
        gate.countDown();

        MvcResult confirmResult = confirmFuture.get(15, TimeUnit.SECONDS);
        MvcResult readingResult = readingFuture.get(15, TimeUnit.SECONDS);
        assertEquals(201, confirmResult.getResponse().getStatus(), "确认应成功使隔离生效");
        int readingStatus = readingResult.getResponse().getStatus();
        assertTrue(readingStatus == 201 || readingStatus == 409,
                "读数只能成功（先生效前提交）或被拒（隔离先生效），实际：" + readingStatus);

        mockMvc.perform(get("/api/equipment/eq-race2/isolations/ord-r2"))
                .andExpect(jsonPath("$.state").value("ISOLATED"));
        Integer readingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading WHERE equipment_id = 'eq-race2'", Integer.class);
        Integer rejectedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rejected_reading WHERE equipment_id = 'eq-race2'", Integer.class);
        if (readingStatus == 201) {
            assertEquals(1, readingCount, "读数先提交则应落库");
            assertEquals(0, rejectedCount, "读数成功则不应有被拒证据");
        } else {
            assertEquals(0, readingCount, "隔离先生效则读数不得落库");
            assertEquals(1, rejectedCount, "被拒读数应固化证据");
        }
    }

    /** 并发批量隔离与单设备隔离（同设备）：恰有一个成功，设备至多一道未结束隔离令。 */
    @Test
    void concurrentBatchAndSingleCreate_exactlyOneWins() throws Exception {
        register("eq-bx", 1000);
        register("eq-by", 1000);
        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> batchFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/isolations/batch", """
                    {"requestId":"batch-x","operator":"op",
                     "orders":[{"equipmentId":"eq-bx","orderId":"bo-x","lockPointIds":["LP-1"]},
                               {"equipmentId":"eq-by","orderId":"bo-y","lockPointIds":["LP-2"]}]}
                    """);
        });
        Future<MvcResult> singleFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-bx/isolations", """
                    {"requestId":"iso-x","orderId":"ord-x","operator":"op","lockPointIds":["LP-9"]}
                    """);
        });
        gate.countDown();

        int batchStatus = batchFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int singleStatus = singleFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        assertTrue(
                (batchStatus == 201 && singleStatus == 422)
                        || (batchStatus == 422 && singleStatus == 201),
                "同设备并发建令应恰有一个成功，实际：batch=" + batchStatus + ", single=" + singleStatus);

        // eq-bx 上至多一道未结束隔离令；批量若失败则整批不生效（eq-by 无令）
        Integer openOrders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM isolation_order WHERE equipment_id = 'eq-bx'"
                        + " AND state IN ('PENDING','ISOLATED')", Integer.class);
        assertEquals(1, openOrders);
        if (batchStatus == 422) {
            mockMvc.perform(get("/api/equipment/eq-by/isolations"))
                    .andExpect(jsonPath("$.length()").value(0));
        } else {
            mockMvc.perform(get("/api/equipment/eq-by/isolations"))
                    .andExpect(jsonPath("$.length()").value(1));
        }
    }
}

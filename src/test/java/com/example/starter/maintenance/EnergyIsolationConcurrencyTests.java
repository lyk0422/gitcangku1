package com.example.starter.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 能源隔离并发裁决测试：真实 H2 数据库，设备行锁串行化同设备写操作，
 * 按提交顺序裁决；核对最终业务数据而非打印或休眠。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClockConfig.class)
class EnergyIsolationConcurrencyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rejected_reading");
        jdbc.update("DELETE FROM isolation_event");
        jdbc.update("DELETE FROM isolation_lock_point");
        jdbc.update("DELETE FROM energy_isolation_order");
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

    private void register(String equipmentId) throws Exception {
        postJson("/api/equipment", """
                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":1000}
                """.formatted(equipmentId, equipmentId));
    }

    /** 建一道双点令，并把确认推进到“差最后一个 VERIFIER 确认即生效”，此时版本为 2。 */
    private long setupOneConfirmationFromEffective(String equipmentId) throws Exception {
        postJson("/api/equipment/" + equipmentId + "/isolation/orders", """
                {"requestId":"c-create","expectedVersion":1,"operatorId":"alice",
                 "lockPoints":[{"lockPointId":"L1","description":"电"},
                               {"lockPointId":"L2","description":"气"}]}
                """);
        long orderId = jdbc.queryForObject(
                "SELECT order_id FROM energy_isolation_order WHERE equipment_id = '" + equipmentId + "'",
                Long.class);
        confirm(equipmentId, "L1", "c-i1", "alice", "ISOLATOR");
        confirm(equipmentId, "L1", "c-v1", "bob", "VERIFIER");
        confirm(equipmentId, "L2", "c-i2", "alice", "ISOLATOR");
        return orderId;
    }

    private MvcResult confirm(String equipmentId, String lockPointId, String requestId,
                              String operator, String role) throws Exception {
        return postJson("/api/equipment/" + equipmentId + "/isolation/lock-points/"
                        + lockPointId + "/confirmations",
                """
                        {"requestId":"%s","expectedVersion":2,"operatorId":"%s","lockRole":"%s"}
                        """.formatted(requestId, operator, role));
    }

    /**
     * 隔离生效（最后一次确认）与新增读数并发，同期望版本：按行锁提交顺序裁决，二者恰一胜一负。
     * 隔离先提交则令为 EFFECTIVE、读数不得写入；读数先提交则令停留 PENDING、读数落库。
     * 说明：等待行锁的事务在 READ_COMMITTED 下读到对方已提交的新版本，故负方为版本冲突 409；
     * 版本匹配下的语义拒绝与证据固化由 {@link #concurrentReadingsUnderIsolation_allRejectedWithEvidence()}
     * 覆盖。
     */
    @Test
    void concurrentEffectiveConfirmVsReading_orderingAdjudication() throws Exception {
        register("eq-race-iso");
        long orderId = setupOneConfirmationFromEffective("eq-race-iso");

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> confirmFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return confirm("eq-race-iso", "L2", "c-v2", "bob", "VERIFIER");
        });
        Future<MvcResult> readingFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-race-iso/readings", """
                    {"requestId":"race-add","expectedVersion":2,"readingId":"r1",
                     "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100,"operatorId":"op-r"}
                    """);
        });
        gate.countDown();

        int confirmStatus = confirmFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int readingStatus = readingFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();

        boolean isolationWon = confirmStatus == 201 && readingStatus == 409;
        boolean readingWon = confirmStatus == 409 && readingStatus == 201;
        assertTrue(isolationWon || readingWon,
                "并发裁决须恰一胜一负：confirm=" + confirmStatus + ", reading=" + readingStatus);

        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM energy_isolation_order WHERE order_id = ?", String.class, orderId);
        Integer readingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading WHERE equipment_id = 'eq-race-iso'", Integer.class);
        Long version = jdbc.queryForObject(
                "SELECT version FROM equipment WHERE equipment_id = 'eq-race-iso'", Long.class);

        if (isolationWon) {
            // 隔离先生效：读数不得写入，版本只随生效前进一步（→3）
            assertEquals("EFFECTIVE", orderStatus);
            assertEquals(0, readingCount);
            assertEquals(3L, version);
            mockMvc.perform(get("/api/equipment/eq-race-iso/status"))
                    .andExpect(jsonPath("$.currentIsolationStatus").value("EFFECTIVE"));
        } else {
            // 读数先写入：确认版本冲突失败，令停留 PENDING，读数 1 条，版本 →3
            assertEquals("PENDING", orderStatus);
            assertEquals(1, readingCount);
            assertEquals(3L, version);
            mockMvc.perform(get("/api/equipment/eq-race-iso/status"))
                    .andExpect(jsonPath("$.currentIsolationStatus").value("PENDING"));
        }
        // 负方事务均回滚，无部分结果：活动令始终只有一条
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM energy_isolation_order WHERE equipment_id = 'eq-race-iso'",
                Integer.class));
    }

    /** 并发同 requestId 同参数确认同一锁定点：全部返回首次快照，确认效果只发生一次。 */
    @Test
    void concurrentSameConfirmRequestId_singleConfirmation() throws Exception {
        register("eq-cf");
        postJson("/api/equipment/eq-cf/isolation/orders", """
                {"requestId":"cf-create","expectedVersion":1,"operatorId":"alice",
                 "lockPoints":[{"lockPointId":"L1","description":"电"},
                               {"lockPointId":"L2","description":"气"}]}
                """);
        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"cf-same","expectedVersion":2,"operatorId":"alice","lockRole":"ISOLATOR"}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-cf/isolation/lock-points/L1/confirmations", body);
            }));
        }
        gate.countDown();

        Set<String> bodies = new HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(), "并发同键同参应全部重放成功结果");
            bodies.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, bodies.size(), "所有并发响应应为同一首次快照");
        // 确认效果只一次：ISOLATOR 确认人只有一个，CONFIRMED 事件仅一条，幂等键仅一条
        assertEquals("alice", jdbc.queryForObject(
                "SELECT confirmed_isolator_by FROM isolation_lock_point lp"
                        + " JOIN energy_isolation_order o ON lp.order_id = o.order_id"
                        + " WHERE o.equipment_id = 'eq-cf' AND lp.lock_point_id = 'L1'",
                String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM isolation_event e JOIN energy_isolation_order o ON e.order_id = o.order_id"
                        + " WHERE o.equipment_id = 'eq-cf' AND e.event_type = 'CONFIRMED'"
                        + " AND e.lock_point_id = 'L1' AND e.lock_role = 'ISOLATOR'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'cf-same'",
                Integer.class));
    }

    /** 生效期间并发多个不同 requestId 读数：全部 409，证据逐条固化，读数 0、版本不动。 */
    @Test
    void concurrentReadingsUnderIsolation_allRejectedWithEvidence() throws Exception {
        register("eq-rej");
        long orderId = setupOneConfirmationFromEffective("eq-rej");
        confirm("eq-rej", "L2", "rej-v2", "bob", "VERIFIER");
        assertEquals("EFFECTIVE", jdbc.queryForObject(
                "SELECT status FROM energy_isolation_order WHERE order_id = ?", String.class, orderId));

        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-rej/readings", """
                        {"requestId":"read-rej-%d","expectedVersion":3,"readingId":"r%d",
                         "sampledAt":"2026-01-01T1%d:00:00Z","cumulativeMinutes":%d,"operatorId":"op%d"}
                        """.formatted(index, index, index, 100L * (index + 1), index))
                        .getResponse().getStatus();
            }));
        }
        gate.countDown();
        for (Future<Integer> future : futures) {
            assertEquals(409, future.get(15, TimeUnit.SECONDS), "隔离生效期间读数应全部 409");
        }
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading WHERE equipment_id = 'eq-rej'", Integer.class));
        assertEquals(threads, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rejected_reading WHERE equipment_id = 'eq-rej'", Integer.class));
        assertEquals(threads, jdbc.queryForObject(
                "SELECT COUNT(*) FROM isolation_event e JOIN energy_isolation_order o ON e.order_id = o.order_id"
                        + " WHERE o.equipment_id = 'eq-rej' AND e.event_type = 'REJECTED_READING'",
                Integer.class));
        assertEquals(3L, jdbc.queryForObject(
                "SELECT version FROM equipment WHERE equipment_id = 'eq-rej'", Long.class),
                "被拒不推进版本");
        // 失败不占幂等键：被拒读数 requestId 未在幂等表中
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id LIKE 'read-rej-%'",
                Integer.class));
    }

    /** 批量隔离并发同 requestId 同参数：整批只生效一次，所有线程得到同一首次快照。 */
    @Test
    void concurrentSameBatchRequestId_replaysSingleBatch() throws Exception {
        register("eq-bx1");
        register("eq-bx2");
        int threads = 6;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"batch-same","operatorId":"alice","items":[
                  {"equipmentId":"eq-bx1","expectedVersion":1,
                   "lockPoints":[{"lockPointId":"P1","description":"a"}]},
                  {"equipmentId":"eq-bx2","expectedVersion":1,
                   "lockPoints":[{"lockPointId":"P2","description":"b"}]}]}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/isolation/batch", body);
            }));
        }
        gate.countDown();

        Set<String> responses = new HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(),
                    "并发同键同参批量请求应全部重放首次成功结果");
            responses.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, responses.size(), "所有并发批量响应应为同一首次快照");
        // 整批只生效一次：两设备各恰好一令，共 2 条
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM energy_isolation_order WHERE equipment_id IN ('eq-bx1','eq-bx2')",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM energy_isolation_order WHERE equipment_id = 'eq-bx1'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM energy_isolation_order WHERE equipment_id = 'eq-bx2'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'batch-same'",
                Integer.class));
    }

    /** 并发创建两道隔离令（同期望版本、不同 requestId）：恰一条成功，无第二条令。 */
    @Test
    void concurrentCreateOrders_onlyOneSucceeds() throws Exception {
        register("eq-two");
        int threads = 2;
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-two/isolation/orders", """
                        {"requestId":"two-%d","expectedVersion":1,"operatorId":"alice",
                         "lockPoints":[{"lockPointId":"L%d","description":"x"}]}
                        """.formatted(index, index)).getResponse().getStatus();
            }));
        }
        gate.countDown();
        Set<Integer> statuses = new HashSet<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(15, TimeUnit.SECONDS));
        }
        assertEquals(2, statuses.size(), "应一成功一失败");
        assertTrue(statuses.contains(201));
        assertTrue(statuses.contains(409) || statuses.contains(422));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM energy_isolation_order WHERE equipment_id = 'eq-two'",
                Integer.class));
    }
}

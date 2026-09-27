package com.example.starter.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * 能源隔离并发与提交顺序裁决测试：真实 H2 数据库上协调并发，断言响应与最终数据，
 * 不靠打印或长超时休眠。
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
        jdbc.update("DELETE FROM isolated_reading_rejection");
        jdbc.update("DELETE FROM isolation_lock_release");
        jdbc.update("DELETE FROM isolation_confirmation");
        jdbc.update("DELETE FROM isolation_lock_point");
        jdbc.update("DELETE FROM isolation_order");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM equipment");
        executor = Executors.newFixedThreadPool(10);
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
        postJson("/api/equipment", """
                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":%d}
                """.formatted(equipmentId, equipmentId, periodMinutes));
    }

    private long currentVersion(String equipmentId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/equipment/" + equipmentId + "/status")).andReturn();
        String body = result.getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"version\":(\\d+).*", "$1"));
    }

    /** 并发同一确认请求（同 requestId 同参）：业务效果只发生一次，全部返回同一成功快照。 */
    @Test
    void concurrentSameConfirmRequestId_singleEffect() throws Exception {
        register("eq-cc", 1000);
        postJson("/api/equipment/eq-cc/isolation-orders", """
                {"requestId":"iso-cc","orderId":"order-cc","createdBy":"op-1","expectedVersion":1,
                 "lockPoints":[{"lockPointId":"lp-1"}]}
                """);

        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"cf-cc","lockPointId":"lp-1","operatorId":"op-a","roleId":"ELECTRICIAN"}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/isolation-orders/order-cc/confirmations", body);
            }));
        }
        gate.countDown();

        Set<String> responses = new HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(), "并发同键同参应全部重放成功结果");
            responses.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, responses.size(), "所有响应应为同一成功快照");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM isolation_confirmation WHERE order_id = 'order-cc'",
                Integer.class);
        assertEquals(1, count, "确认记录只能有一条");
    }

    /** 两名不同角色并发确认同一锁定点：两条确认都生效，隔离恰好转为 ACTIVE 一次。 */
    @Test
    void concurrentTwoRoles_activatesExactlyOnce() throws Exception {
        register("eq-cr", 1000);
        postJson("/api/equipment/eq-cr/isolation-orders", """
                {"requestId":"iso-cr","orderId":"order-cr","createdBy":"op-1","expectedVersion":1,
                 "lockPoints":[{"lockPointId":"lp-1"}]}
                """);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> f1 = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/isolation-orders/order-cr/confirmations", """
                    {"requestId":"cf-cr-1","lockPointId":"lp-1","operatorId":"op-a",
                     "roleId":"ELECTRICIAN"}
                    """);
        });
        Future<MvcResult> f2 = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/isolation-orders/order-cr/confirmations", """
                    {"requestId":"cf-cr-2","lockPointId":"lp-1","operatorId":"op-b",
                     "roleId":"SUPERVISOR"}
                    """);
        });
        gate.countDown();

        assertEquals(201, f1.get(15, TimeUnit.SECONDS).getResponse().getStatus());
        assertEquals(201, f2.get(15, TimeUnit.SECONDS).getResponse().getStatus());

        mockMvc.perform(get("/api/isolation-orders/order-cr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.confirmedLockPointCount").value(1))
                .andExpect(jsonPath("$.lockPoints[0].confirmations.length()").value(2));
        // 版本：登记 1 → 建令 2 → 生效 3（仅推进一次）
        mockMvc.perform(get("/api/equipment/eq-cr/status"))
                .andExpect(jsonPath("$.version").value(3));
    }

    /** 隔离生效后并发提交读数：全部 409，每个都有独立拒绝证据，无一条读数落库。 */
    @Test
    void concurrentReadingsWhileIsolated_allRejectedAndEvidenced() throws Exception {
        register("eq-cj", 1000);
        postJson("/api/equipment/eq-cj/isolation-orders", """
                {"requestId":"iso-cj","orderId":"order-cj","createdBy":"op-1","expectedVersion":1,
                 "lockPoints":[{"lockPointId":"lp-1"},{"lockPointId":"lp-2"}]}
                """);
        postJson("/api/isolation-orders/order-cj/confirmations", """
                {"requestId":"cf-j1","lockPointId":"lp-1","operatorId":"op-a","roleId":"ELECTRICIAN"}
                """);
        postJson("/api/isolation-orders/order-cj/confirmations", """
                {"requestId":"cf-j2","lockPointId":"lp-1","operatorId":"op-b","roleId":"SUPERVISOR"}
                """);
        postJson("/api/isolation-orders/order-cj/confirmations", """
                {"requestId":"cf-j3","lockPointId":"lp-2","operatorId":"op-a","roleId":"ELECTRICIAN"}
                """);
        postJson("/api/isolation-orders/order-cj/confirmations", """
                {"requestId":"cf-j4","lockPointId":"lp-2","operatorId":"op-b","roleId":"SUPERVISOR"}
                """);

        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                // 隔离生效后版本停在建令后的值；expectedVersion 用 2，门禁优先于版本校验。
                MvcResult result = postJson("/api/equipment/eq-cj/readings", """
                        {"requestId":"cj-r-%d","expectedVersion":3,"readingId":"rr-%d",
                         "sampledAt":"2026-01-01T1%d:00:00Z","cumulativeMinutes":%d,"operatorId":"op-m-%d"}
                        """.formatted(index, index, index, 100L * (index + 1), index));
                return result.getResponse().getStatus();
            }));
        }
        gate.countDown();

        for (Future<Integer> future : futures) {
            assertEquals(409, future.get(15, TimeUnit.SECONDS),
                    "隔离生效期并发读数必须全部 409");
        }
        Integer rejections = jdbc.queryForObject(
                "SELECT COUNT(*) FROM isolated_reading_rejection WHERE order_id = 'order-cj'",
                Integer.class);
        assertEquals(threads, rejections, "每次被拒都应在独立事务固化证据");
        Integer readings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading WHERE equipment_id = 'eq-cj'", Integer.class);
        assertEquals(0, readings, "被拒读数不得落库");
        // 设备版本未因拒绝而推进
        mockMvc.perform(get("/api/equipment/eq-cj/status"))
                .andExpect(jsonPath("$.version").value(3));
    }

    /**
     * 提交顺序裁决：隔离生效与读数并发，读数要么在生效前成功（版本推进、读数落库），
     * 要么在生效后被拒（409 + 证据），二者必居其一，不存在半成品。
     */
    @Test
    void concurrentIsolationActivationVsReadings_commitOrderAdjudicated() throws Exception {
        register("eq-co", 10000);
        int readers = 6;
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger isolated = new AtomicInteger();

        // 隔离线程：建令（1 个锁定点）后两名角色确认即生效；建令遇版本冲突则重读版本重试。
        Future<?> isolator = executor.submit(() -> {
            try {
                gate.await(5, TimeUnit.SECONDS);
                for (int attempt = 0; attempt < 30; attempt++) {
                    long version = currentVersion("eq-co");
                    MvcResult created = postJson("/api/equipment/eq-co/isolation-orders", """
                            {"requestId":"iso-co","orderId":"order-co","createdBy":"op-iso",
                             "expectedVersion":%d,"lockPoints":[{"lockPointId":"lp-1"}]}
                            """.formatted(version));
                    if (created.getResponse().getStatus() == 201) {
                        break;
                    }
                    assertEquals(409, created.getResponse().getStatus());
                }
                postJson("/api/isolation-orders/order-co/confirmations", """
                        {"requestId":"cf-co-1","lockPointId":"lp-1","operatorId":"op-a",
                         "roleId":"ELECTRICIAN"}
                        """);
                postJson("/api/isolation-orders/order-co/confirmations", """
                        {"requestId":"cf-co-2","lockPointId":"lp-1","operatorId":"op-b",
                         "roleId":"SUPERVISOR"}
                        """);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        List<Future<?>> readerFutures = new ArrayList<>();
        for (int i = 0; i < readers; i++) {
            int index = i;
            readerFutures.add(executor.submit(() -> {
                try {
                    gate.await(5, TimeUnit.SECONDS);
                    // 乐观重试版本冲突；终态只可能是 201（生效前）或 EQUIPMENT_ISOLATED（生效后）
                    for (int attempt = 0; attempt < 30; attempt++) {
                        long version = currentVersion("eq-co");
                        MvcResult result = postJson("/api/equipment/eq-co/readings", """
                                {"requestId":"co-r-%d","expectedVersion":%d,"readingId":"rr-%d",
                                 "sampledAt":"2026-01-01T0%d:00:00Z","cumulativeMinutes":100,
                                 "operatorId":"op-read-%d"}
                                """.formatted(index, version, index, index, index));
                        int status = result.getResponse().getStatus();
                        if (status == 201) {
                            accepted.incrementAndGet();
                            return;
                        }
                        if (status == 409
                                && result.getResponse().getContentAsString().contains("EQUIPMENT_ISOLATED")) {
                            isolated.incrementAndGet();
                            return;
                        }
                        // VERSION_CONFLICT 等可重试冲突：短暂退让后重读版本
                        assertEquals(409, status, "只允许版本冲突重试，实际 " + status);
                    }
                    throw new IllegalStateException("reader-" + index + " 重试耗尽");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        gate.countDown();

        isolator.get(20, TimeUnit.SECONDS);
        for (Future<?> future : readerFutures) {
            future.get(30, TimeUnit.SECONDS);
        }

        assertEquals(readers, accepted.get() + isolated.get(),
                "每个读数必须有明确终态：生效前成功或生效后被拒");
        Integer dbReadings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading WHERE equipment_id = 'eq-co'", Integer.class);
        assertEquals(accepted.get(), dbReadings, "成功读数必须全部落库，被拒读数不得落库");
        Integer dbRejections = jdbc.queryForObject(
                "SELECT COUNT(*) FROM isolated_reading_rejection WHERE order_id = 'order-co'",
                Integer.class);
        assertEquals(isolated.get(), dbRejections, "被拒读数必须全部有证据");
        mockMvc.perform(get("/api/isolation-orders/order-co"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        // 被拒读数 id 绝不出现在读数表
        List<String> readingIds = jdbc.queryForList(
                "SELECT reading_id FROM reading WHERE equipment_id = 'eq-co'", String.class);
        List<String> rejectionIds = jdbc.queryForList(
                "SELECT reading_id FROM isolated_reading_rejection WHERE order_id = 'order-co'",
                String.class);
        assertTrue(Collections.disjoint(readingIds, rejectionIds),
                "同一读数不得既成功落库又被拒固化");
    }
}

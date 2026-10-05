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
 * 部件装拆账并发与幂等边界测试：真实 H2 数据库上的并发写协调，断言响应与最终数据一致性。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ComponentConcurrencyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM component_current");
        jdbc.update("DELETE FROM component_install");
        jdbc.update("DELETE FROM component");
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

    private void registerEquipmentWithReading(String equipmentId, String readingId,
                                              long cumulativeMinutes) throws Exception {
        postJson("/api/equipment", """
                {"requestId":"reg-eq-%s","equipmentId":"%s","maintenancePeriodMinutes":100}
                """.formatted(equipmentId, equipmentId));
        postJson("/api/equipment/" + equipmentId + "/readings", """
                {"requestId":"rd-%s","expectedVersion":1,"readingId":"%s",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":%d}
                """.formatted(equipmentId, readingId, cumulativeMinutes));
    }

    private void registerComponent(String componentId, long lifetimeMinutes) throws Exception {
        mockMvc.perform(post("/api/components")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-cp-%s","operator":"tester","componentId":"%s",
                                 "lifetimeMinutes":%d}
                                """.formatted(componentId, componentId, lifetimeMinutes)))
                .andExpect(status().isCreated());
    }

    /** 并发安装同部件至两台设备：恰有一台成功（201），另一个 409；归属与版本只推进一次。 */
    @Test
    void concurrentInstallSameComponent_onlyOneSucceeds() throws Exception {
        registerEquipmentWithReading("E1", "r1", 100);
        registerEquipmentWithReading("E2", "r2", 500);
        registerComponent("cmp-race", 1000);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> toE1 = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/components/cmp-race/installs", """
                    {"requestId":"race-ins-e1","operator":"tester","expectedVersion":1,
                     "equipmentId":"E1","readingId":"r1"}
                    """);
        });
        Future<MvcResult> toE2 = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/components/cmp-race/installs", """
                    {"requestId":"race-ins-e2","operator":"tester","expectedVersion":1,
                     "equipmentId":"E2","readingId":"r2"}
                    """);
        });
        gate.countDown();

        int statusE1 = toE1.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int statusE2 = toE2.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        assertTrue(
                (statusE1 == 201 && statusE2 == 409) || (statusE1 == 409 && statusE2 == 201),
                "并发安装同部件应恰有一个成功，实际：E1=" + statusE1 + ", E2=" + statusE2);

        String winner = statusE1 == 201 ? "E1" : "E2";
        mockMvc.perform(get("/api/components/cmp-race"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.currentEquipmentId").value(winner))
                .andExpect(jsonPath("$.installs.length()").value(1));
        Integer currentRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM component_current WHERE component_id = 'cmp-race'",
                Integer.class);
        assertEquals(1, currentRows, "同部件同一时刻只能装一台");
    }

    /** 并发同 requestId 同参数安装：全部重放首次成功结果，归属只登记一次。 */
    @Test
    void concurrentSameRequestIdInstall_replaysSingleEffect() throws Exception {
        registerEquipmentWithReading("E1", "r1", 100);
        registerComponent("cmp-cid", 1000);
        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"cid-ins-1","operator":"tester","expectedVersion":1,
                 "equipmentId":"E1","readingId":"r1"}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/components/cmp-cid/installs", body);
            }));
        }
        gate.countDown();

        Set<String> bodies = new HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(), "并发同键同参应全部重放成功结果");
            bodies.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, bodies.size(), "所有并发响应应完全相同（重放同一成功结果）");

        mockMvc.perform(get("/api/components/cmp-cid"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.installs.length()").value(1));
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'cid-ins-1'",
                Integer.class);
        assertEquals(1, idemCount, "同一 requestId 只应占一个幂等键");
    }

    /** 并发同 requestId 拆卸：全部重放首次结果，区间时长只计入一次。 */
    @Test
    void concurrentSameRequestIdUninstall_accumulatesOnce() throws Exception {
        registerEquipmentWithReading("E1", "r1", 100);
        postJson("/api/equipment/E1/readings", """
                {"requestId":"rd-e1-2","expectedVersion":2,"readingId":"r2",
                 "sampledAt":"2026-01-01T11:00:00Z","cumulativeMinutes":160}
                """);
        registerComponent("cmp-uni", 1000);
        postJson("/api/components/cmp-uni/installs", """
                {"requestId":"uni-ins-1","operator":"tester","expectedVersion":1,
                 "equipmentId":"E1","readingId":"r1"}
                """);

        int threads = 6;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"uni-unins-1","operator":"tester","expectedVersion":2,"readingId":"r2"}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/components/cmp-uni/uninstall", body);
            }));
        }
        gate.countDown();

        Set<String> bodies = new HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(), "并发同键拆卸应全部重放成功结果");
            bodies.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, bodies.size(), "所有并发拆卸响应应完全相同（时长 60）");
        assertTrue(bodies.iterator().next().contains("\"durationMinutes\":60"),
                "重放响应应携带首次区间时长 60");

        // 寿命只计入一次：累计 60，版本只推进一次
        mockMvc.perform(get("/api/components/cmp-uni"))
                .andExpect(jsonPath("$.accumulatedMinutes").value(60))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.installs.length()").value(1));
    }
}

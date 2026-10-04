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

    private void registerEquipment(String equipmentId) throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-eq-%s","equipmentId":"%s","maintenancePeriodMinutes":100000}
                                """.formatted(equipmentId, equipmentId)))
                .andExpect(status().isCreated());
    }

    private void registerComponent(String componentId, long lifespanMinutes) throws Exception {
        mockMvc.perform(post("/api/components")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-cp-%s","operator":"tester","componentId":"%s",
                                 "lifespanMinutes":%d}
                                """.formatted(componentId, componentId, lifespanMinutes)))
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

    /** 并发安装同一部件至两台设备：最多一台成功（201），另一台 409，在装记录唯一。 */
    @Test
    void concurrentInstallSameComponent_onlyOneSucceeds() throws Exception {
        registerEquipment("E1");
        registerEquipment("E2");
        registerComponent("C1", 1000);
        addReading("E1", "e1-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("E2", "e2-r1", 1, "r1", "2026-01-01T10:00:00Z", 500);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> installE1 = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/components/C1/installs", """
                    {"requestId":"cins-1","operator":"tester","expectedVersion":1,
                     "equipmentId":"E1","readingId":"r1"}
                    """);
        });
        Future<MvcResult> installE2 = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/components/C1/installs", """
                    {"requestId":"cins-2","operator":"tester","expectedVersion":1,
                     "equipmentId":"E2","readingId":"r1"}
                    """);
        });
        gate.countDown();

        int statusE1 = installE1.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int statusE2 = installE2.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        assertTrue((statusE1 == 201 && statusE2 == 409) || (statusE1 == 409 && statusE2 == 201),
                "并发安装同部件应恰有一台成功，实际：E1=" + statusE1 + ", E2=" + statusE2);

        // 在装记录唯一，部件版本只前进一次
        Integer openCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM component_install WHERE component_id = 'C1'"
                        + " AND open_component_id IS NOT NULL", Integer.class);
        assertEquals(1, openCount, "同部件同一时刻只能装一台");
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM component_install WHERE component_id = 'C1'", Integer.class);
        assertEquals(1, total);
        mockMvc.perform(get("/api/components/C1"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.accumulatedMinutes").value(0));
    }
    /** 拆卸与读数更正并发：联合判定，拆卸快照与修订结果必居其一且一致，不能先记归属再漏记寿命。 */
    @Test
    void concurrentRemoveVsRevise_jointDecision() throws Exception {
        registerEquipment("E1");
        registerComponent("C1", 1000);
        addReading("E1", "e1-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("E1", "e1-r2", 2, "r2", "2026-01-01T11:00:00Z", 160);
        postJson("/api/components/C1/installs", """
                {"requestId":"j-ins","operator":"tester","expectedVersion":1,
                 "equipmentId":"E1","readingId":"r1"}
                """);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> reviseFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/E1/readings/r2/revisions", """
                    {"requestId":"j-revise","expectedVersion":3,"cumulativeMinutes":170}
                    """);
        });
        Future<MvcResult> removeFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/components/C1/removals", """
                    {"requestId":"j-remove","operator":"tester","expectedVersion":2,
                     "equipmentId":"E1","readingId":"r2"}
                    """);
        });
        gate.countDown();

        MvcResult revise = reviseFuture.get(15, TimeUnit.SECONDS);
        MvcResult removal = removeFuture.get(15, TimeUnit.SECONDS);
        int reviseStatus = revise.getResponse().getStatus();
        int removeStatus = removal.getResponse().getStatus();
        assertEquals(201, removeStatus, "拆卸引用已提交读数，始终应成功");

        Long accumulated = jdbc.queryForObject(
                "SELECT accumulated_minutes FROM component WHERE component_id = 'C1'", Long.class);
        Long removeCumulative = jdbc.queryForObject(
                "SELECT remove_cumulative_minutes FROM component_install WHERE component_id = 'C1'",
                Long.class);
        if (reviseStatus == 201) {
            // 修订先提交：拆卸快照为修订后值 170，区间 70
            assertEquals(170L, removeCumulative, "修订先提交时拆卸须快照新值");
            assertEquals(70L, accumulated);
        } else {
            // 拆卸先提交：读数已被装拆账引用，修订必须 409，区间按原值 60
            assertEquals(409, reviseStatus, "拆卸引用后修订必须拒绝");
            assertTrue(revise.getResponse().getContentAsString().contains("READING_ANCHORED"));
            assertEquals(160L, removeCumulative);
            assertEquals(60L, accumulated);
        }
        // 部件累计与装拆账区间一致：归属与寿命同时结算
        assertEquals(removeCumulative - 100L, accumulated);
    }

    /** 并发同 requestId 同参数拆卸：全部重放首次成功响应，区间只结算一次。 */
    @Test
    void concurrentSameRequestIdRemove_replaysSingleEffect() throws Exception {
        registerEquipment("E1");
        registerComponent("C1", 1000);
        addReading("E1", "e1-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("E1", "e1-r2", 2, "r2", "2026-01-01T11:00:00Z", 160);
        postJson("/api/components/C1/installs", """
                {"requestId":"r-ins","operator":"tester","expectedVersion":1,
                 "equipmentId":"E1","readingId":"r1"}
                """);

        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"r-rem","operator":"tester","expectedVersion":2,
                 "equipmentId":"E1","readingId":"r2"}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/components/C1/removals", body);
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
        assertTrue(bodies.iterator().next().contains("\"segmentMinutes\":60"));

        // 区间只结算一次：累计 60（而非 60×8），版本只前进一次
        mockMvc.perform(get("/api/components/C1"))
                .andExpect(jsonPath("$.accumulatedMinutes").value(60))
                .andExpect(jsonPath("$.version").value(3));
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'r-rem'", Integer.class);
        assertEquals(1, idemCount, "同一 requestId 只应占一个幂等键");
    }
}
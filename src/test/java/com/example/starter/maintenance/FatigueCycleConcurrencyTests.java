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
 * 应力循环并发与幂等边界测试：真实 H2 上的并发写协调，断言响应与最终数据一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FatigueCycleConcurrencyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM fatigue_cycle");
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

    private void registerWithFatigue(String equipmentId) throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":10000,
                                 "fatigueThreshold":125,"baselineStressMpa":10}
                                """.formatted(equipmentId, equipmentId)))
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

    /** 并发同 requestId 同参数循环写入：全部得到原成功结果，损伤只写一次。 */
    @Test
    void concurrentSameRequestId_singleDamageEffect() throws Exception {
        registerWithFatigue("eq-cc");
        addReading("eq-cc", "cc-r1", 1, "r1", "2026-01-01T10:00:00Z", 0);
        addReading("eq-cc", "cc-r2", 2, "r2", "2026-01-01T12:00:00Z", 100);

        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"cc-c1","expectedVersion":3,"cycleId":"c1",
                 "fromReadingId":"r1","fromRevisionNo":1,"toReadingId":"r2","toRevisionNo":1,
                 "peakAt":"2026-01-01T11:00:00Z","returnAt":"2026-01-01T11:30:00Z",
                 "peakStressMpa":15,"returnStressMpa":10,"operator":"tester"}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-cc/cycles", body);
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

        // 损伤只写一次：一条循环、版本仅加一、幂等键唯一
        mockMvc.perform(get("/api/equipment/eq-cc/cycles"))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/equipment/eq-cc/status"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.currentRoundDamage").value(125))
                .andExpect(jsonPath("$.status").value("DUE"));
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'cc-c1'", Integer.class);
        assertEquals(1, idemCount, "同一 requestId 只应占一个幂等键");
        Integer cycleCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fatigue_cycle WHERE equipment_id = 'eq-cc'", Integer.class);
        assertEquals(1, cycleCount, "并发下同键循环只应落库一次");
    }

    /** 并发循环写入与引用读数修订（同一期望版本）：恰有一个成功，最终数据一致。 */
    @Test
    void concurrentCycleVsRevise_exactlyOneWins() throws Exception {
        registerWithFatigue("eq-cr");
        addReading("eq-cr", "cr-r1", 1, "r1", "2026-01-01T10:00:00Z", 0);
        addReading("eq-cr", "cr-r2", 2, "r2", "2026-01-01T12:00:00Z", 100);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> cycleFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cr/cycles", """
                    {"requestId":"cr-cycle","expectedVersion":3,"cycleId":"c1",
                     "fromReadingId":"r1","fromRevisionNo":1,"toReadingId":"r2","toRevisionNo":1,
                     "peakAt":"2026-01-01T11:00:00Z","returnAt":"2026-01-01T11:30:00Z",
                     "peakStressMpa":15,"returnStressMpa":10,"operator":"tester"}
                    """);
        });
        Future<MvcResult> reviseFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cr/readings/r1/revisions", """
                    {"requestId":"cr-revise","expectedVersion":3,"cumulativeMinutes":50}
                    """);
        });
        gate.countDown();

        int cycleStatus = cycleFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int reviseStatus = reviseFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();

        // 版本校验使两者恰有一个成功
        assertTrue((cycleStatus == 201 && reviseStatus == 409)
                        || (cycleStatus == 409 && reviseStatus == 201),
                "循环写入与修订并发应恰有一个成功，实际：cycle=" + cycleStatus
                        + ", revise=" + reviseStatus);
        mockMvc.perform(get("/api/equipment/eq-cr/status"))
                .andExpect(jsonPath("$.version").value(4));

        if (cycleStatus == 201) {
            // 循环胜出：损伤入账，被引用读数不可再修订
            mockMvc.perform(get("/api/equipment/eq-cr/cycles"))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].damage").value(125));
            Integer revisionNo = jdbc.queryForObject(
                    "SELECT revision_no FROM reading WHERE equipment_id = 'eq-cr' AND reading_id = 'r1'",
                    Integer.class);
            assertEquals(1, revisionNo, "循环引用后读数修订号不得变化");
        } else {
            // 修订胜出：无循环入账，读数修订号为 2
            mockMvc.perform(get("/api/equipment/eq-cr/cycles"))
                    .andExpect(jsonPath("$.length()").value(0));
            mockMvc.perform(get("/api/equipment/eq-cr/readings/r1/revisions"))
                    .andExpect(jsonPath("$.length()").value(2));
            mockMvc.perform(get("/api/equipment/eq-cr/status"))
                    .andExpect(jsonPath("$.currentRoundDamage").value(0));
        }
    }
}

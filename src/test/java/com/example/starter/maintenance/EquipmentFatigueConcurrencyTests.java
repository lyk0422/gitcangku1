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
 * 应力循环并发边界测试：真实 H2 上的并发写协调，断言响应与最终数据一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EquipmentFatigueConcurrencyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM stress_cycle");
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

    private void registerWithReadings(String equipmentId) throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":100000,
                                 "fatigueThreshold":125,"baselineStressMpa":10}
                                """.formatted(equipmentId, equipmentId)))
                .andExpect(status().isCreated());
        postJson("/api/equipment/" + equipmentId + "/readings", """
                {"requestId":"%s-r1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":0}
                """.formatted(equipmentId));
        postJson("/api/equipment/" + equipmentId + "/readings", """
                {"requestId":"%s-r2","expectedVersion":2,"readingId":"r2",
                 "sampledAt":"2026-01-01T11:00:00Z","cumulativeMinutes":30}
                """.formatted(equipmentId));
    }

    private String cycleBody(String requestId, long expectedVersion, String cycleId,
                             String riseAt, String fallAt) {
        return """
                {"requestId":"%s","operator":"op-1","expectedVersion":%d,"cycleId":"%s",
                 "rise":{"stressMpa":15,"sampledAt":"%s","readingId":"r1","readingRevisionNo":1},
                 "fall":{"stressMpa":10,"sampledAt":"%s","readingId":"r2","readingRevisionNo":1}}
                """.formatted(requestId, expectedVersion, cycleId, riseAt, fallAt);
    }

    /** 并发同 requestId 同参数循环写入：全部重放同一成功结果，效果只发生一次。 */
    @Test
    void concurrentSameRequestIdCycle_singleEffect() throws Exception {
        registerWithReadings("eq-cc");
        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = cycleBody("cc-1", 3, "c1", "2026-01-01T10:10:00Z", "2026-01-01T10:40:00Z");
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
        assertEquals(1, bodies.size(), "所有并发响应应完全相同");

        mockMvc.perform(get("/api/equipment/eq-cc/cycles"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].damage").value(125));
        mockMvc.perform(get("/api/equipment/eq-cc/status"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.currentRoundDamage").value(125))
                .andExpect(jsonPath("$.status").value("DUE"));
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'cc-1'", Integer.class);
        assertEquals(1, idemCount, "同一 requestId 只应占一个幂等键");
    }

    /** 并发新循环与完成保养（同一期望版本）：恰有一个成功，版本只前进一次。 */
    @Test
    void concurrentCycleVsMaintenance_exactlyOneWins() throws Exception {
        registerWithReadings("eq-cm");
        postJson("/api/equipment/eq-cm/cycles",
                cycleBody("cm-c1", 3, "c1", "2026-01-01T10:10:00Z", "2026-01-01T10:40:00Z"));

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> cycleFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cm/cycles",
                    cycleBody("cm-c2", 4, "c2", "2026-01-01T10:50:00Z", "2026-01-01T10:55:00Z"));
        });
        Future<MvcResult> maintenanceFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cm/maintenances", """
                    {"requestId":"cm-m1","expectedVersion":4,"readingId":"r2","anchorRevisionNo":1}
                    """);
        });
        gate.countDown();

        int cycleStatus = cycleFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int maintenanceStatus = maintenanceFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        assertTrue((cycleStatus == 201 && maintenanceStatus == 409)
                        || (cycleStatus == 409 && maintenanceStatus == 201),
                "循环写入与保养并发应恰有一个成功，实际：cycle=" + cycleStatus
                        + ", maintenance=" + maintenanceStatus);

        mockMvc.perform(get("/api/equipment/eq-cm/status"))
                .andExpect(jsonPath("$.version").value(5));
        if (maintenanceStatus == 201) {
            // 保养胜出：c1 冻结为历史轮，c2 未写入
            mockMvc.perform(get("/api/equipment/eq-cm/cycles"))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].currentRound").value(false));
            mockMvc.perform(get("/api/equipment/eq-cm/status"))
                    .andExpect(jsonPath("$.currentRoundDamage").value(0));
        } else {
            // 循环胜出：无保养记录，c2 计入当前轮
            mockMvc.perform(get("/api/equipment/eq-cm/maintenances"))
                    .andExpect(jsonPath("$.length()").value(0));
            mockMvc.perform(get("/api/equipment/eq-cm/cycles"))
                    .andExpect(jsonPath("$.length()").value(2));
            mockMvc.perform(get("/api/equipment/eq-cm/status"))
                    .andExpect(jsonPath("$.currentRoundDamage").value(250));
        }
    }

    /** 并发重叠区间循环（不同 requestId）：恰有一个提交，另一个最终 422，账本只有一条。 */
    @Test
    void concurrentOverlappingCycles_onlyOneCommitted() throws Exception {
        registerWithReadings("eq-co");
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        String[] ranges = {"2026-01-01T10:10:00Z|2026-01-01T10:40:00Z",
                "2026-01-01T10:20:00Z|2026-01-01T10:50:00Z"};
        for (int i = 0; i < 2; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                String[] range = ranges[index].split("\\|");
                for (int attempt = 0; attempt < 20; attempt++) {
                    MvcResult statusResult = mockMvc.perform(get("/api/equipment/eq-co/status"))
                            .andReturn();
                    String body = statusResult.getResponse().getContentAsString();
                    long version = Long.parseLong(body.replaceAll(".*\"version\":(\\d+).*", "$1"));
                    MvcResult write = postJson("/api/equipment/eq-co/cycles",
                            cycleBody("co-" + index, version, "c" + index, range[0], range[1]));
                    int writeStatus = write.getResponse().getStatus();
                    if (writeStatus == 201 || writeStatus == 422) {
                        return writeStatus;
                    }
                    assertEquals(409, writeStatus, "版本冲突时按最新版本重试");
                }
                throw new IllegalStateException("乐观并发重试次数耗尽");
            }));
        }
        gate.countDown();

        List<Integer> results = new ArrayList<>();
        for (Future<Integer> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        assertTrue(results.contains(201) && results.contains(422),
                "重叠区间并发应恰一个 201、一个 422，实际：" + results);
        mockMvc.perform(get("/api/equipment/eq-co/cycles"))
                .andExpect(jsonPath("$.length()").value(1));
        Integer damage = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stress_cycle WHERE equipment_id = 'eq-co'", Integer.class);
        assertEquals(1, damage, "账本中只能有一个重叠区间的循环");
    }
}

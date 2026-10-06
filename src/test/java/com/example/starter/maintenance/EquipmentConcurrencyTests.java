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
 * 并发与幂等边界测试：真实 H2 数据库上的并发写协调，断言响应与最终数据一致性。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EquipmentConcurrencyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
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

    /** 并发同 requestId 同参数：全部得到原成功结果，业务效果只发生一次。 */
    @Test
    void concurrentSameRequestId_replaysSingleEffect() throws Exception {
        register("eq-cid", 1000);
        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"cid-1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-cid/readings", body);
            }));
        }
        gate.countDown();

        Set<String> bodies = new HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(),
                    "并发同键同参应全部重放成功结果");
            bodies.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, bodies.size(), "所有并发响应应完全相同（重放同一成功结果）");

        // 业务效果只发生一次：一条读数、一条修订、版本仅加一
        mockMvc.perform(get("/api/equipment/eq-cid/readings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/equipment/eq-cid/status"))
                .andExpect(jsonPath("$.version").value(2));
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'cid-1'", Integer.class);
        assertEquals(1, idemCount, "同一 requestId 只应占一个幂等键");
    }

    /** 并发修订与完成保养（同一期望版本）：恰有一个成功，已完成保养的锚点不发生变化。 */
    @Test
    void concurrentReviseVsMaintenance_anchorStable() throws Exception {
        register("eq-race", 1000);
        postJson("/api/equipment/eq-race/readings", """
                {"requestId":"race-r1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                """);
        postJson("/api/equipment/eq-race/readings", """
                {"requestId":"race-r2","expectedVersion":2,"readingId":"r2",
                 "sampledAt":"2026-01-01T11:00:00Z","cumulativeMinutes":200}
                """);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> reviseFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-race/readings/r1/revisions", """
                    {"requestId":"race-revise","expectedVersion":3,"cumulativeMinutes":150}
                    """);
        });
        Future<MvcResult> maintenanceFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-race/maintenances", """
                    {"requestId":"race-mnt","expectedVersion":3,"readingId":"r1","anchorRevisionNo":1}
                    """);
        });
        gate.countDown();

        MvcResult revise = reviseFuture.get(15, TimeUnit.SECONDS);
        MvcResult maintenance = maintenanceFuture.get(15, TimeUnit.SECONDS);
        int reviseStatus = revise.getResponse().getStatus();
        int maintenanceStatus = maintenance.getResponse().getStatus();

        // 恰有一个成功（201），另一个冲突（409）：版本校验使两者不能同时生效
        assertTrue(
                (reviseStatus == 201 && maintenanceStatus == 409)
                        || (reviseStatus == 409 && maintenanceStatus == 201),
                "修订与保养并发应恰有一个成功，实际：revise=" + reviseStatus
                        + ", maintenance=" + maintenanceStatus);

        // 版本只前进一次
        mockMvc.perform(get("/api/equipment/eq-race/status"))
                .andExpect(jsonPath("$.version").value(4));

        if (maintenanceStatus == 201) {
            // 保养胜出：锚点快照为修订号 1、工时 100，且之后不可再修订该读数
            mockMvc.perform(get("/api/equipment/eq-race/maintenances"))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].anchorRevisionNo").value(1))
                    .andExpect(jsonPath("$[0].anchorCumulativeMinutes").value(100));
            mockMvc.perform(get("/api/equipment/eq-race/readings/r1/revisions"))
                    .andExpect(jsonPath("$.length()").value(1));
            Integer revisionNo = jdbc.queryForObject(
                    "SELECT revision_no FROM reading WHERE equipment_id = 'eq-race' AND reading_id = 'r1'",
                    Integer.class);
            assertEquals(1, revisionNo, "保养锚定后读数修订号不得变化");
        } else {
            // 修订胜出：无保养记录，读数修订号为 2、工时 150
            mockMvc.perform(get("/api/equipment/eq-race/maintenances"))
                    .andExpect(jsonPath("$.length()").value(0));
            mockMvc.perform(get("/api/equipment/eq-race/readings/r1/revisions"))
                    .andExpect(jsonPath("$.length()").value(2));
            Integer cumulative = jdbc.queryForObject(
                    "SELECT cumulative_minutes FROM reading WHERE equipment_id = 'eq-race' AND reading_id = 'r1'",
                    Integer.class);
            assertEquals(150, cumulative);
        }
    }

    /** 并发不同 requestId 新增不同读数：串行化后均按版本推进，无丢失更新。 */
    @Test
    void concurrentDistinctWrites_noLostUpdate() throws Exception {
        register("eq-par", 10000);
        int threads = 6;
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                // 每个线程先查版本再写，冲突时按最新版本重试，模拟客户端乐观并发
                for (int attempt = 0; attempt < 20; attempt++) {
                    MvcResult statusResult = mockMvc.perform(get("/api/equipment/eq-par/status"))
                            .andReturn();
                    String body = statusResult.getResponse().getContentAsString();
                    long version = Long.parseLong(body.replaceAll(".*\"version\":(\\d+).*", "$1"));
                    MvcResult write = postJson("/api/equipment/eq-par/readings", """
                            {"requestId":"par-%d","expectedVersion":%d,"readingId":"r-%d",
                             "sampledAt":"2026-01-01T1%d:00:00Z","cumulativeMinutes":%d}
                            """.formatted(index, version, index, index, 100L * (index + 1)));
                    if (write.getResponse().getStatus() == 201) {
                        return write;
                    }
                    assertEquals(409, write.getResponse().getStatus());
                }
                throw new IllegalStateException("乐观并发重试次数耗尽");
            }));
        }
        gate.countDown();
        for (Future<MvcResult> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }

        // 全部写成功：读数齐全、版本 = 1 + 写次数
        mockMvc.perform(get("/api/equipment/eq-par/readings"))
                .andExpect(jsonPath("$.length()").value(threads));
        mockMvc.perform(get("/api/equipment/eq-par/status"))
                .andExpect(jsonPath("$.version").value(1 + threads));
    }

    private void registerRange(String equipmentId, String requestId, long expectedVersion, long rangeM)
            throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"rangeM":%d}
                                """.formatted(requestId, expectedVersion, rangeM)))
                .andExpect(status().isCreated());
    }

    /** 并发同 requestId 原始读数：全部得到原成功结果，归一与回绕证据只落库一次。 */
    @Test
    void concurrentRawAdd_sameRequestId_singleEffect() throws Exception {
        register("eq-craw", 10000);
        registerRange("eq-craw", "craw-range", 1, 1000);
        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"craw-1","expectedVersion":2,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","raw":990,"wrapCount":0}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-craw/raw-readings", body);
            }));
        }
        gate.countDown();

        Set<String> bodies = new HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(),
                    "并发同键同参应全部重放成功结果");
            bodies.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, bodies.size(), "所有并发响应应完全相同（重放同一成功结果）");

        mockMvc.perform(get("/api/equipment/eq-craw/readings"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].raw").value(990))
                .andExpect(jsonPath("$[0].wrapCount").value(0))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(990));
        mockMvc.perform(get("/api/equipment/eq-craw/status"))
                .andExpect(jsonPath("$.version").value(3));
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'craw-1'", Integer.class);
        assertEquals(1, idemCount, "同一 requestId 只应占一个幂等键");
    }

    /** 并发原始修订与完成保养：恰有一个成功；保养胜出时锚点冻结 raw/wrap/M 快照。 */
    @Test
    void concurrentRawReviseVsMaintenance_anchorFrozen() throws Exception {
        register("eq-rrace", 10000);
        registerRange("eq-rrace", "rrace-range", 1, 1000);
        postJson("/api/equipment/eq-rrace/raw-readings", """
                {"requestId":"rrace-r1","expectedVersion":2,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","raw":100,"wrapCount":0}
                """);
        postJson("/api/equipment/eq-rrace/raw-readings", """
                {"requestId":"rrace-r2","expectedVersion":3,"readingId":"r2",
                 "sampledAt":"2026-01-01T11:00:00Z","raw":200,"wrapCount":0}
                """);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> reviseFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-rrace/readings/r1/raw-revisions", """
                    {"requestId":"rrace-revise","expectedVersion":4,"readingRevision":1,
                     "raw":150,"wrapCount":0}
                    """);
        });
        Future<MvcResult> maintenanceFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-rrace/maintenances", """
                    {"requestId":"rrace-mnt","expectedVersion":4,"readingId":"r1","anchorRevisionNo":1}
                    """);
        });
        gate.countDown();

        MvcResult revise = reviseFuture.get(15, TimeUnit.SECONDS);
        MvcResult maintenance = maintenanceFuture.get(15, TimeUnit.SECONDS);
        int reviseStatus = revise.getResponse().getStatus();
        int maintenanceStatus = maintenance.getResponse().getStatus();

        assertTrue(
                (reviseStatus == 201 && maintenanceStatus == 409)
                        || (reviseStatus == 409 && maintenanceStatus == 201),
                "原始修订与保养并发应恰有一个成功，实际：revise=" + reviseStatus
                        + ", maintenance=" + maintenanceStatus);

        // 版本只前进一次（登记 1 + 量程 1 + 两条读数 2 + 本次 1 = 5）
        mockMvc.perform(get("/api/equipment/eq-rrace/status"))
                .andExpect(jsonPath("$.version").value(5));

        if (maintenanceStatus == 201) {
            // 保养胜出：锚点冻结 raw=100、wrap=0、M=1000、归一 100，读数修订号不变
            mockMvc.perform(get("/api/equipment/eq-rrace/maintenances"))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].anchorRevisionNo").value(1))
                    .andExpect(jsonPath("$[0].anchorCumulativeMinutes").value(100))
                    .andExpect(jsonPath("$[0].anchorRaw").value(100))
                    .andExpect(jsonPath("$[0].anchorWrapCount").value(0))
                    .andExpect(jsonPath("$[0].anchorRangeM").value(1000));
            Integer revisionNo = jdbc.queryForObject(
                    "SELECT revision_no FROM reading WHERE equipment_id = 'eq-rrace' AND reading_id = 'r1'",
                    Integer.class);
            assertEquals(1, revisionNo, "保养锚定后读数修订号不得变化");
        } else {
            // 修订胜出：无保养记录，读数修订号为 2、归一 150
            mockMvc.perform(get("/api/equipment/eq-rrace/maintenances"))
                    .andExpect(jsonPath("$.length()").value(0));
            mockMvc.perform(get("/api/equipment/eq-rrace/readings/r1/revisions"))
                    .andExpect(jsonPath("$.length()").value(2));
            Integer cumulative = jdbc.queryForObject(
                    "SELECT cumulative_minutes FROM reading WHERE equipment_id = 'eq-rrace' AND reading_id = 'r1'",
                    Integer.class);
            assertEquals(150, cumulative);
        }
    }
}

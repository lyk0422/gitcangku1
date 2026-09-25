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
 * 延期审批并发与幂等边界测试：真实 H2 数据库上的并发写协调，
 * 验证事务提交顺序裁决（保养完成 vs 批准、读数提交 vs 批准）与同键重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeferralConcurrencyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM deferral");
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
                            String readingId, String sampledAt, long cumulativeMinutes)
            throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                cumulativeMinutes)))
                .andExpect(status().isCreated());
    }

    /** 并发同 requestId 同参数申请延期：全部重放首次结果，业务效果只发生一次。 */
    @Test
    void concurrentSameRequestIdApply_replaysSingleEffect() throws Exception {
        register("eq-capply", 100);
        addReading("eq-capply", "r-1", 1, "r1", "2026-01-01T10:00:00Z", 100);

        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"ca-1","deferKey":"dk1","deferMinutes":20,
                 "reason":"等待备件","applicant":"alice"}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-capply/deferrals", body);
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

        // 业务效果只发生一次：一条延期记录、一个幂等键
        mockMvc.perform(get("/api/equipment/eq-capply/deferrals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'ca-1'", Integer.class);
        assertEquals(1, idemCount, "同一 requestId 只应占一个幂等键");
    }

    /** 批准与保养完成并发：按事务提交顺序裁决，最终状态与响应一致。 */
    @Test
    void concurrentApproveVsMaintenance_commitOrderArbitrates() throws Exception {
        register("eq-race", 100);
        addReading("eq-race", "r-1", 1, "r1", "2026-01-01T10:00:00Z", 110);
        postJson("/api/equipment/eq-race/deferrals", """
                {"requestId":"rc-apply","deferKey":"dk1","deferMinutes":20,
                 "reason":"等待备件","applicant":"alice"}
                """);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> approveFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-race/deferrals/dk1/approve", """
                    {"requestId":"rc-approve","approver":"bob"}
                    """);
        });
        Future<MvcResult> maintenanceFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-race/maintenances", """
                    {"requestId":"rc-mnt","expectedVersion":2,"readingId":"r1","anchorRevisionNo":1}
                    """);
        });
        gate.countDown();

        MvcResult approve = approveFuture.get(15, TimeUnit.SECONDS);
        MvcResult maintenance = maintenanceFuture.get(15, TimeUnit.SECONDS);
        int approveStatus = approve.getResponse().getStatus();
        int maintenanceStatus = maintenance.getResponse().getStatus();

        // 保养完成必然成功；批准是否成功取决于提交顺序
        assertEquals(201, maintenanceStatus, "保养完成应成功");
        assertTrue(approveStatus == 200 || approveStatus == 409,
                "批准应为 200（先提交）或 409（保养先提交，延期已失效），实际：" + approveStatus);

        String deferralStatus = jdbc.queryForObject(
                "SELECT status FROM deferral WHERE equipment_id = 'eq-race' AND defer_key = 'dk1'",
                String.class);
        if (approveStatus == 200) {
            // 批准先提交：记录为 APPROVED 且阈值快照固化
            assertEquals("APPROVED", deferralStatus);
            Long newThreshold = jdbc.queryForObject(
                    "SELECT new_threshold_minutes FROM deferral"
                            + " WHERE equipment_id = 'eq-race' AND defer_key = 'dk1'",
                    Long.class);
            assertEquals(120L, newThreshold);
        } else {
            // 保养完成先提交：待审批延期 409 且已失效
            assertEquals("EXPIRED", deferralStatus);
        }

        // 无论哪种顺序：新周期延期额度归零、按原始阈值计算
        mockMvc.perform(get("/api/equipment/eq-race/status"))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.approvedDeferralMinutes").value(0))
                .andExpect(jsonPath("$.effectiveThresholdMinutes").value(100));
    }

    /** 读数提交与批准并发：读数先提交则 409（待审批封锁），批准先提交则读数按新阈值判定。 */
    @Test
    void concurrentReadingVsApprove_commitOrderArbitrates() throws Exception {
        register("eq-rv", 100);
        addReading("eq-rv", "r-1", 1, "r1", "2026-01-01T10:00:00Z", 110);
        postJson("/api/equipment/eq-rv/deferrals", """
                {"requestId":"rv-apply","deferKey":"dk1","deferMinutes":20,
                 "reason":"等待备件","applicant":"alice"}
                """);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> readingFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-rv/readings", """
                    {"requestId":"rv-read","expectedVersion":2,"readingId":"r2",
                     "sampledAt":"2026-01-01T11:00:00Z","cumulativeMinutes":115}
                    """);
        });
        Future<MvcResult> approveFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-rv/deferrals/dk1/approve", """
                    {"requestId":"rv-approve","approver":"bob"}
                    """);
        });
        gate.countDown();

        MvcResult reading = readingFuture.get(15, TimeUnit.SECONDS);
        MvcResult approve = approveFuture.get(15, TimeUnit.SECONDS);
        int readingStatus = reading.getResponse().getStatus();

        // 批准必然成功（与读数无冲突，仅串行化）
        assertEquals(200, approve.getResponse().getStatus(), "批准应成功");
        // 读数：先提交则 409 DEFERRAL_PENDING；批准先提交则按新阈值 120 判定，115 < 120 → 201
        assertTrue(readingStatus == 201 || readingStatus == 409,
                "读数应为 201（批准后按新阈值）或 409（待审批封锁），实际：" + readingStatus);

        if (readingStatus == 201) {
            mockMvc.perform(get("/api/equipment/eq-rv/status"))
                    .andExpect(jsonPath("$.runMinutes").value(115))
                    .andExpect(jsonPath("$.effectiveThresholdMinutes").value(120))
                    .andExpect(jsonPath("$.status").value("DUE"));
        } else {
            // 读数被封锁且失败不占键：最终仍只有一条读数
            mockMvc.perform(get("/api/equipment/eq-rv/readings"))
                    .andExpect(jsonPath("$.length()").value(1));
            mockMvc.perform(get("/api/equipment/eq-rv/status"))
                    .andExpect(jsonPath("$.runMinutes").value(110));
        }
    }

    /** 并发不同 deferKey 申请：设备行锁串行化，恰有一条成为待审批，其余 409。 */
    @Test
    void concurrentDistinctApply_exactlyOnePending() throws Exception {
        register("eq-multi", 100);
        addReading("eq-multi", "r-1", 1, "r1", "2026-01-01T10:00:00Z", 100);

        int threads = 4;
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-multi/deferrals", """
                        {"requestId":"ma-%d","deferKey":"dk-%d","deferMinutes":10,
                         "reason":"等待备件","applicant":"alice"}
                        """.formatted(index, index));
            }));
        }
        gate.countDown();

        int created = 0;
        int conflict = 0;
        for (Future<MvcResult> future : futures) {
            int statusCode = future.get(15, TimeUnit.SECONDS).getResponse().getStatus();
            if (statusCode == 201) {
                created++;
            } else if (statusCode == 409) {
                conflict++;
            }
        }
        assertEquals(1, created, "同一设备同时只允许一条待审批延期");
        assertEquals(threads - 1, conflict, "其余并发申请应 409");
        mockMvc.perform(get("/api/equipment/eq-multi/deferrals"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }
}

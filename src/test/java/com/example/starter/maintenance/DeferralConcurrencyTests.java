package com.example.starter.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
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
 * 保养延期并发与幂等边界测试：真实 H2 上的并发写协调，
 * 按事务提交顺序裁决（行锁串行化），断言响应与最终数据一致。
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

    /** 并发批准与完成保养：保养先提交则批准 409（延期失效）；批准先提交则保养正常完成。 */
    @Test
    void concurrentApproveVsMaintenance_commitOrderDecides() throws Exception {
        register("eq-c1", 100);
        addReading("eq-c1", "c1-r1", 1, "r1", "2026-01-01T10:00:00Z", 110);
        postJson("/api/equipment/eq-c1/deferrals", """
                {"requestId":"c1-apply","deferKey":"dk1","minutes":20,
                 "reason":"备件待到货","applicant":"zhang"}
                """);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> approveFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-c1/deferrals/dk1/approval", """
                    {"requestId":"c1-approve","approver":"wang"}
                    """);
        });
        Future<MvcResult> maintenanceFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-c1/maintenances", """
                    {"requestId":"c1-mnt","expectedVersion":2,"readingId":"r1","anchorRevisionNo":1}
                    """);
        });
        gate.countDown();

        int approveStatus = approveFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int maintenanceStatus = maintenanceFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();

        // 保养完成一定成功（版本无冲突）；批准结果由提交顺序裁决
        assertEquals(201, maintenanceStatus, "保养完成应成功");
        assertTrue(approveStatus == 200 || approveStatus == 409,
                "批准应成功或因延期失效而 409，实际：" + approveStatus);

        String deferralStatus = jdbc.queryForObject(
                "SELECT status FROM deferral WHERE equipment_id = 'eq-c1' AND defer_key = 'dk1'",
                String.class);
        if (approveStatus == 200) {
            // 批准先提交：记录为 APPROVED 且阈值快照完整
            assertEquals("APPROVED", deferralStatus);
            Long newThreshold = jdbc.queryForObject(
                    "SELECT new_threshold_minutes FROM deferral"
                            + " WHERE equipment_id = 'eq-c1' AND defer_key = 'dk1'",
                    Long.class);
            assertEquals(120L, newThreshold);
        } else {
            // 保养先提交：待审批延期被置为 EXPIRED，批准 409
            assertEquals("EXPIRED", deferralStatus);
        }
        // 无论顺序，保养记录恰一条，设备版本只前进一次
        mockMvc.perform(get("/api/equipment/eq-c1/maintenances"))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-c1/status"))
                .andExpect(jsonPath("$.version").value(3));
    }

    /** 并发批准与新增读数：批准先提交则读数按新阈值判定（120 内允许），否则读数因待审批封锁 409。 */
    @Test
    void concurrentApproveVsReading_commitOrderDecides() throws Exception {
        register("eq-c2", 100);
        addReading("eq-c2", "c2-r1", 1, "r1", "2026-01-01T10:00:00Z", 110);
        postJson("/api/equipment/eq-c2/deferrals", """
                {"requestId":"c2-apply","deferKey":"dk1","minutes":20,
                 "reason":"备件待到货","applicant":"zhang"}
                """);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> approveFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-c2/deferrals/dk1/approval", """
                    {"requestId":"c2-approve","approver":"wang"}
                    """);
        });
        Future<MvcResult> readingFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-c2/readings", """
                    {"requestId":"c2-r2","expectedVersion":2,"readingId":"r2",
                     "sampledAt":"2026-01-01T11:00:00Z","cumulativeMinutes":115}
                    """);
        });
        gate.countDown();

        int approveStatus = approveFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int readingStatus = readingFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();

        assertEquals(200, approveStatus, "批准应成功");
        // 读数在待审批封锁期内提交则 409；批准先提交则按新阈值 120 判定，115 允许
        assertTrue(readingStatus == 201 || readingStatus == 409,
                "读数应按提交顺序判定，实际：" + readingStatus);

        Integer readingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading WHERE equipment_id = 'eq-c2'", Integer.class);
        String deferralStatus = jdbc.queryForObject(
                "SELECT status FROM deferral WHERE equipment_id = 'eq-c2' AND defer_key = 'dk1'",
                String.class);
        assertEquals("APPROVED", deferralStatus);
        if (readingStatus == 201) {
            assertEquals(2, readingCount, "读数成功则应落库");
            mockMvc.perform(get("/api/equipment/eq-c2/status"))
                    .andExpect(jsonPath("$.latestCumulativeMinutes").value(115))
                    .andExpect(jsonPath("$.currentThresholdMinutes").value(120));
        } else {
            assertEquals(1, readingCount, "读数被封锁则不应落库");
        }
    }

    /** 并发申请同一设备延期：恰有一条成为待审批，其余 409（唯一待审批约束）。 */
    @Test
    void concurrentApply_onlyOnePending() throws Exception {
        register("eq-c3", 100);
        addReading("eq-c3", "c3-r1", 1, "r1", "2026-01-01T10:00:00Z", 110);

        int threads = 6;
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-c3/deferrals", """
                        {"requestId":"c3-apply-%d","deferKey":"dk-%d","minutes":20,
                         "reason":"备件待到货","applicant":"zhang"}
                        """.formatted(index, index));
            }));
        }
        gate.countDown();

        int created = 0;
        int conflict = 0;
        for (Future<MvcResult> future : futures) {
            int code = future.get(15, TimeUnit.SECONDS).getResponse().getStatus();
            if (code == 201) {
                created++;
            } else if (code == 409) {
                conflict++;
            }
        }
        assertEquals(1, created, "并发申请应恰有一条成功");
        assertEquals(threads - 1, conflict, "其余申请应 409");

        Integer pendingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM deferral WHERE equipment_id = 'eq-c3' AND status = 'PENDING'",
                Integer.class);
        assertEquals(1, pendingCount, "同时只允许一条待审批延期");
    }

    /** 并发同 requestId 同参申请：全部重放首次结果，延期记录仅一条。 */
    @Test
    void concurrentSameRequestId_replaysSingleDeferral() throws Exception {
        register("eq-c4", 100);
        addReading("eq-c4", "c4-r1", 1, "r1", "2026-01-01T10:00:00Z", 110);

        int threads = 6;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"c4-apply","deferKey":"dk1","minutes":20,
                 "reason":"备件待到货","applicant":"zhang"}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-c4/deferrals", body);
            }));
        }
        gate.countDown();

        java.util.Set<String> bodies = new java.util.HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(), "并发同键同参应全部重放成功");
            bodies.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, bodies.size(), "所有并发响应应完全相同");

        Integer deferralCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM deferral WHERE equipment_id = 'eq-c4'", Integer.class);
        assertEquals(1, deferralCount, "延期记录应仅一条");
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'c4-apply'",
                Integer.class);
        assertEquals(1, idemCount, "同一 requestId 只应占一个幂等键");
    }
}

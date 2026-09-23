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
 * 漂移修正并发边界测试（真实 H2）：与另一漂移修正、保养完成并发时按设备行锁串行提交，
 * 不能出现读数已修正而保养状态仍基于旧值；同键并发重放只生效一次。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DriftCorrectionConcurrencyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM maintenance_item_snapshot");
        jdbc.update("DELETE FROM maintenance_snapshot");
        jdbc.update("DELETE FROM drift_correction_reading");
        jdbc.update("DELETE FROM drift_correction_anchor");
        jdbc.update("DELETE FROM drift_correction");
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

    private void seed() throws Exception {
        postJson("/api/equipment", """
                {"requestId":"reg-eq","equipmentId":"eq-c","maintenancePeriodMinutes":100}
                """);
        String[][] readings = {
                {"add-r1", "1", "r1", "2026-01-01T10:00:00Z", "100"},
                {"add-r2", "2", "r2", "2026-01-01T10:30:00Z", "200"},
                {"add-r3", "3", "r3", "2026-01-01T11:00:00Z", "300"},
                {"add-r4", "4", "r4", "2026-01-01T11:30:00Z", "400"},
                {"add-r5", "5", "r5", "2026-01-01T12:00:00Z", "500"},
        };
        for (String[] r : readings) {
            postJson("/api/equipment/eq-c/readings", """
                    {"requestId":"%s","expectedVersion":%s,"readingId":"%s",
                     "sampledAt":"%s","cumulativeMinutes":%s}
                    """.formatted(r[0], r[1], r[2], r[3], r[4]));
        }
    }

    private String driftBody(String requestId, String correctionKey, long expectedVersion,
                             String firstHours, String lastHours) {
        return """
                {"requestId":"%s","correctionKey":"%s","expectedVersion":%d,"anchors":[
                  {"readingId":"r1","expectedVersion":1,"calibratedHours":%s},
                  {"readingId":"r5","expectedVersion":1,"calibratedHours":%s}
                ]}
                """.formatted(requestId, correctionKey, expectedVersion, firstHours, lastHours);
    }

    /** 两单漂移修正并发（同一期望版本）：恰有一单成功，另一单版本冲突；快照与读数终态一致。 */
    @Test
    void concurrentTwoCorrections_onlyOneCommits() throws Exception {
        seed();
        CountDownLatch gate = new CountDownLatch(1);
        String bodyA = driftBody("dc-a", "ck-a", 6, "2.000", "6.000");
        String bodyB = driftBody("dc-b", "ck-b", 6, "3.000", "7.000");
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (String body : List.of(bodyA, bodyB)) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-c/drift-corrections", body);
            }));
        }
        gate.countDown();

        int success = 0;
        int conflict = 0;
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            int status = result.getResponse().getStatus();
            assertTrue(status == 201 || status == 409, "应为 201 或 409，实际 " + status);
            if (status == 201) {
                success++;
            } else {
                conflict++;
            }
        }
        assertEquals(1, success, "并发两单漂移修正应恰有一单成功");
        assertEquals(1, conflict);

        // 版本只前进一次，快照恰好一个
        mockMvc.perform(get("/api/equipment/eq-c/status"))
                .andExpect(jsonPath("$.version").value(7));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_snapshot WHERE equipment_id='eq-c'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT maintenance_snapshot_version FROM equipment WHERE equipment_id='eq-c'",
                Long.class));
        // 每条区间读数恰好一个漂移新版本（修订号 2），不存在部分修正
        Integer partial = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading WHERE equipment_id='eq-c' AND revision_no <> 2",
                Integer.class);
        assertEquals(0, partial, "不能只修正部分读数");
        // 保养状态与修正后读数一致：2.0/6.0 或 3.0/7.0 方案下最新工时均 >= 周期
        mockMvc.perform(get("/api/equipment/eq-c/status"))
                .andExpect(jsonPath("$.status").value("DUE"));
    }

    /** 漂移修正与完成保养并发（锚点 r3 在修正区间内）：互斥，终态一致。 */
    @Test
    void concurrentCorrectionVsMaintenance_mutuallyConsistent() throws Exception {
        seed();
        CountDownLatch gate = new CountDownLatch(1);
        String driftBody = driftBody("dc-race", "ck-race", 6, "2.000", "6.000");
        String maintenanceBody = """
                {"requestId":"mnt-race","expectedVersion":6,"readingId":"r3","anchorRevisionNo":1}
                """;
        Future<MvcResult> driftFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-c/drift-corrections", driftBody);
        });
        Future<MvcResult> maintenanceFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-c/maintenances", maintenanceBody);
        });
        gate.countDown();

        MvcResult drift = driftFuture.get(15, TimeUnit.SECONDS);
        MvcResult maintenance = maintenanceFuture.get(15, TimeUnit.SECONDS);
        int driftStatus = drift.getResponse().getStatus();
        int maintenanceStatus = maintenance.getResponse().getStatus();

        // 同一期望版本并发：恰有一方提交（201），另一方版本冲突（409），不存在部分生效。
        assertTrue(
                (driftStatus == 201 && maintenanceStatus == 409)
                        || (driftStatus == 409 && maintenanceStatus == 201),
                "漂移修正与保养并发应恰有一方成功，实际：drift=" + driftStatus
                        + ", maintenance=" + maintenanceStatus);
        mockMvc.perform(get("/api/equipment/eq-c/status"))
                .andExpect(jsonPath("$.version").value(7));

        if (maintenanceStatus == 201) {
            // 保养先提交：漂移修正未生效（版本冲突整单回滚），读数未被改动、无快照；
            // 此后客户端以新版本重试修正含冻结点 r3 的区间，必须整单 422 且不能只修其余读数。
            Integer revisions = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM reading WHERE equipment_id='eq-c' AND revision_no <> 1",
                    Integer.class);
            assertEquals(0, revisions, "冲突回滚时不得改动任何读数");
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM maintenance_snapshot WHERE equipment_id='eq-c'",
                    Integer.class));
            MvcResult retry = postJson("/api/equipment/eq-c/drift-corrections",
                    driftBody("dc-retry", "ck-retry", 7, "2.000", "6.000"));
            assertEquals(422, retry.getResponse().getStatus(),
                    "保养冻结点落在修正集合内时重试必须整单 422");
            assertEquals("CORRECTION_FROZEN_READING",
                    com.jayway.jsonpath.JsonPath.read(retry.getResponse().getContentAsString(), "$.code"));
            mockMvc.perform(get("/api/equipment/eq-c/status"))
                    .andExpect(jsonPath("$.version").value(7));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM drift_correction", Integer.class),
                    "422 回滚不占 correctionKey");
        } else {
            // 漂移修正先提交：保养以过期修订号锚定 r3 被拒；快照与读数已原子生效
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM maintenance_snapshot WHERE equipment_id='eq-c'",
                    Integer.class));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM maintenance WHERE equipment_id='eq-c'", Integer.class));
            mockMvc.perform(get("/api/equipment/eq-c/status"))
                    .andExpect(jsonPath("$.latestCumulativeMinutes").value(360))
                    .andExpect(jsonPath("$.status").value("DUE"));
        }
    }

    /** 同一漂移修正单同 requestId 同参并发：全部重放首次快照，业务效果只发生一次。 */
    @Test
    void concurrentSameRequestId_replaysSingleCorrection() throws Exception {
        seed();
        int threads = 6;
        CountDownLatch gate = new CountDownLatch(1);
        String body = driftBody("dc-idem", "ck-idem", 6, "2.000", "6.000");
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-c/drift-corrections", body);
            }));
        }
        gate.countDown();

        java.util.Set<String> responses = new java.util.HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(20, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
            responses.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, responses.size(), "并发同键同参应全部重放同一成功快照");
        mockMvc.perform(get("/api/equipment/eq-c/status"))
                .andExpect(jsonPath("$.version").value(7));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM drift_correction WHERE correction_id='ck-idem'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_snapshot WHERE equipment_id='eq-c'", Integer.class));
        Integer driftRevisions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading_revision WHERE equipment_id='eq-c'"
                        + " AND change_type='DRIFT_CORRECTION'", Integer.class);
        assertEquals(5, driftRevisions, "每条区间读数只生成一个漂移新版本");
    }
}

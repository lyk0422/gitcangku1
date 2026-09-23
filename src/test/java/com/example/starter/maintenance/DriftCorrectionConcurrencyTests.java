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
 * 漂移修正并发边界测试：真实 H2 数据库上协调并发写，断言响应与最终数据一致性。
 * 漂移修正与新读数上报、普通修订、保养完成及另一漂移修正并发时按提交顺序串行化，
 * 不得出现读数已修正而保养状态仍基于旧值。
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
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM drift_correction_item");
        jdbc.update("DELETE FROM drift_correction_anchor");
        jdbc.update("DELETE FROM drift_correction");
        jdbc.update("DELETE FROM maintenance_snapshot");
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

    private void addReading(String equipmentId, String readingId, long expectedVersion,
                            String sampledAt, long cumulativeMinutes) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"add-%s-%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d}
                                """.formatted(equipmentId, readingId, expectedVersion, readingId,
                                sampledAt, cumulativeMinutes)))
                .andExpect(status().isCreated());
    }

    private static String correctionBody(String requestId, String correctionKey,
                                         long expectedVersion, String firstHours,
                                         String lastHours) {
        return """
                {"requestId":"%s","correctionKey":"%s","expectedVersion":%d,
                 "anchors":[{"readingId":"r1","expectedVersion":1,"cumulativeHours":%s},
                            {"readingId":"r3","expectedVersion":1,"cumulativeHours":%s}]}
                """.formatted(requestId, correctionKey, expectedVersion, firstHours, lastHours);
    }

    /** 并发同 requestId 同参数激活：全部重放首次快照，修正与快照只发生一次。 */
    @Test
    void concurrentSameRequestId_singleCorrectionAndSnapshot() throws Exception {
        register("eq-cc1", 1000);
        addReading("eq-cc1", "r1", 1, "2026-01-01T10:00:00Z", 60);
        addReading("eq-cc1", "r2", 2, "2026-01-01T11:00:00Z", 120);
        addReading("eq-cc1", "r3", 3, "2026-01-01T12:00:00Z", 180);

        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = correctionBody("cc-1", "ck-cc1", 4, "1.000", "3.000");
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-cc1/drift-corrections", body);
            }));
        }
        gate.countDown();

        Set<String> bodies = new HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(), "并发同键同参应全部重放首次快照");
            bodies.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, bodies.size(), "所有并发响应应完全相同（重放首次快照）");

        // 业务效果只发生一次：一个修正单、一个快照版本、每条读数仅前进一个修订号
        mockMvc.perform(get("/api/equipment/eq-cc1/drift-corrections"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].maintenanceSnapshotVersion").value(1));
        mockMvc.perform(get("/api/equipment/eq-cc1/maintenance-snapshots"))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/equipment/eq-cc1/status"))
                .andExpect(jsonPath("$.version").value(5));
        Integer r2Revision = jdbc.queryForObject(
                "SELECT revision_no FROM reading WHERE equipment_id = 'eq-cc1' AND reading_id = 'r2'",
                Integer.class);
        assertEquals(2, r2Revision, "区间内读数应恰好被修正一次");
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'cc-1'", Integer.class);
        assertEquals(1, idemCount, "同一 requestId 只应占一个幂等键");
    }

    /** 漂移修正与普通修订并发（同一期望版本）：恰有一个成功，最终状态自洽。 */
    @Test
    void concurrentCorrectionVsRevise_exactlyOneWins() throws Exception {
        register("eq-cc2", 1000);
        addReading("eq-cc2", "r1", 1, "2026-01-01T10:00:00Z", 60);
        addReading("eq-cc2", "r2", 2, "2026-01-01T11:00:00Z", 120);
        addReading("eq-cc2", "r3", 3, "2026-01-01T12:00:00Z", 180);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> correctionFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cc2/drift-corrections",
                    correctionBody("cc-c2", "ck-cc2", 4, "1.000", "3.300"));
        });
        Future<MvcResult> reviseFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cc2/readings/r2/revisions", """
                    {"requestId":"cc-r2","expectedVersion":4,"cumulativeMinutes":150}
                    """);
        });
        gate.countDown();

        MvcResult correction = correctionFuture.get(15, TimeUnit.SECONDS);
        MvcResult revise = reviseFuture.get(15, TimeUnit.SECONDS);
        int correctionStatus = correction.getResponse().getStatus();
        int reviseStatus = revise.getResponse().getStatus();
        assertTrue(
                (correctionStatus == 201 && reviseStatus == 409)
                        || (correctionStatus == 409 && reviseStatus == 201),
                "漂移修正与修订并发应恰有一个成功，实际：correction=" + correctionStatus
                        + ", revise=" + reviseStatus);

        // 版本只前进一次
        mockMvc.perform(get("/api/equipment/eq-cc2/status"))
                .andExpect(jsonPath("$.version").value(5));

        if (correctionStatus == 201) {
            // 修正胜出：r2 被插值为 2.150h（129min），快照基于修正后读数
            Long r2Millis = jdbc.queryForObject(
                    "SELECT cumulative_millis FROM reading WHERE equipment_id = 'eq-cc2'"
                            + " AND reading_id = 'r2'", Long.class);
            assertEquals(7740000L, r2Millis, "r2 应被插值修正为 2.150 小时");
            mockMvc.perform(get("/api/equipment/eq-cc2/maintenance-snapshots"))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].latestCumulativeMillis").value(11880000))
                    .andExpect(jsonPath("$[0].runMillis").value(11880000));
            // 保养状态不得基于旧值：最新读数 r3 已修正为 3.300h = 198min
            mockMvc.perform(get("/api/equipment/eq-cc2/status"))
                    .andExpect(jsonPath("$.runMinutes").value(198));
        } else {
            // 修订胜出：无修正单、无快照，r2 为修订值 150min
            mockMvc.perform(get("/api/equipment/eq-cc2/drift-corrections"))
                    .andExpect(jsonPath("$.length()").value(0));
            mockMvc.perform(get("/api/equipment/eq-cc2/maintenance-snapshots"))
                    .andExpect(jsonPath("$.length()").value(0));
            Integer r2Minutes = jdbc.queryForObject(
                    "SELECT cumulative_minutes FROM reading WHERE equipment_id = 'eq-cc2'"
                            + " AND reading_id = 'r2'", Integer.class);
            assertEquals(150, r2Minutes);
        }
    }

    /** 漂移修正与新读数上报并发：串行化后恰有一个成功，数据与状态一致。 */
    @Test
    void concurrentCorrectionVsAddReading_serialized() throws Exception {
        register("eq-cc3", 1000);
        addReading("eq-cc3", "r1", 1, "2026-01-01T10:00:00Z", 60);
        addReading("eq-cc3", "r3", 2, "2026-01-01T12:00:00Z", 180);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> correctionFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cc3/drift-corrections",
                    correctionBody("cc-c3", "ck-cc3", 3, "1.000", "3.000"));
        });
        Future<MvcResult> addFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cc3/readings", """
                    {"requestId":"cc-a3","expectedVersion":3,"readingId":"r2",
                     "sampledAt":"2026-01-01T11:00:00Z","cumulativeMinutes":120}
                    """);
        });
        gate.countDown();

        MvcResult correction = correctionFuture.get(15, TimeUnit.SECONDS);
        MvcResult add = addFuture.get(15, TimeUnit.SECONDS);
        int correctionStatus = correction.getResponse().getStatus();
        int addStatus = add.getResponse().getStatus();
        assertTrue(
                (correctionStatus == 201 && addStatus == 409)
                        || (correctionStatus == 409 && addStatus == 201),
                "漂移修正与新增读数并发应恰有一个成功，实际：correction=" + correctionStatus
                        + ", add=" + addStatus);

        mockMvc.perform(get("/api/equipment/eq-cc3/status"))
                .andExpect(jsonPath("$.version").value(4));
        if (correctionStatus == 201) {
            // 修正先提交：r2 未落库，区间 [r1, r3] 内仅 r1、r3 被修正
            mockMvc.perform(get("/api/equipment/eq-cc3/readings"))
                    .andExpect(jsonPath("$.length()").value(2));
            mockMvc.perform(get("/api/equipment/eq-cc3/drift-corrections"))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].affectedCount").value(2));
            mockMvc.perform(get("/api/equipment/eq-cc3/maintenance-snapshots"))
                    .andExpect(jsonPath("$.length()").value(1));
        } else {
            // 新增先提交：修正因版本冲突失败，r2 落库且无快照
            mockMvc.perform(get("/api/equipment/eq-cc3/readings"))
                    .andExpect(jsonPath("$.length()").value(3));
            mockMvc.perform(get("/api/equipment/eq-cc3/drift-corrections"))
                    .andExpect(jsonPath("$.length()").value(0));
            mockMvc.perform(get("/api/equipment/eq-cc3/maintenance-snapshots"))
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }

    /** 两个漂移修正并发（不同 correctionKey、同一期望版本）：恰有一个成功，快照版本唯一。 */
    @Test
    void concurrentTwoCorrections_exactlyOneSnapshot() throws Exception {
        register("eq-cc4", 1000);
        addReading("eq-cc4", "r1", 1, "2026-01-01T10:00:00Z", 60);
        addReading("eq-cc4", "r2", 2, "2026-01-01T11:00:00Z", 120);
        addReading("eq-cc4", "r3", 3, "2026-01-01T12:00:00Z", 180);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> first = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cc4/drift-corrections",
                    correctionBody("cc-d1", "ck-d1", 4, "1.000", "3.000"));
        });
        Future<MvcResult> second = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cc4/drift-corrections",
                    correctionBody("cc-d2", "ck-d2", 4, "1.100", "3.100"));
        });
        gate.countDown();

        int firstStatus = first.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int secondStatus = second.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        assertTrue((firstStatus == 201) != (secondStatus == 201),
                "两个漂移修正并发应恰有一个成功，实际：" + firstStatus + "/" + secondStatus);

        // 只生成一个快照版本；设备版本只前进一次
        mockMvc.perform(get("/api/equipment/eq-cc4/maintenance-snapshots"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].snapshotVersion").value(1));
        mockMvc.perform(get("/api/equipment/eq-cc4/drift-corrections"))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/equipment/eq-cc4/status"))
                .andExpect(jsonPath("$.version").value(5));
    }

    /** 漂移修正与保养完成并发：恰有一个成功；保养先完成则区间内冻结点使整单 422。 */
    @Test
    void concurrentCorrectionVsMaintenance_frozenOrVersionConflict() throws Exception {
        register("eq-cc5", 1000);
        addReading("eq-cc5", "r1", 1, "2026-01-01T10:00:00Z", 60);
        addReading("eq-cc5", "r2", 2, "2026-01-01T11:00:00Z", 120);
        addReading("eq-cc5", "r3", 3, "2026-01-01T12:00:00Z", 180);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> correctionFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cc5/drift-corrections",
                    correctionBody("cc-c5", "ck-cc5", 4, "1.000", "3.000"));
        });
        Future<MvcResult> maintenanceFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cc5/maintenances", """
                    {"requestId":"cc-m5","expectedVersion":4,"readingId":"r2","anchorRevisionNo":1}
                    """);
        });
        gate.countDown();

        MvcResult correction = correctionFuture.get(15, TimeUnit.SECONDS);
        MvcResult maintenance = maintenanceFuture.get(15, TimeUnit.SECONDS);
        int correctionStatus = correction.getResponse().getStatus();
        int maintenanceStatus = maintenance.getResponse().getStatus();

        // 恰有一个成功；若保养先提交，修正整单 422（冻结点落入修正集合）或 409（版本冲突）
        assertTrue(
                (correctionStatus == 201 && maintenanceStatus == 409)
                        || (maintenanceStatus == 201
                                && (correctionStatus == 422 || correctionStatus == 409)),
                "实际：correction=" + correctionStatus + ", maintenance=" + maintenanceStatus);

        mockMvc.perform(get("/api/equipment/eq-cc5/status"))
                .andExpect(jsonPath("$.version").value(5));
        if (maintenanceStatus == 201) {
            // 保养胜出：锚点快照保留原值，区间内读数未被修正，无修正单
            mockMvc.perform(get("/api/equipment/eq-cc5/maintenances"))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].anchorCumulativeMinutes").value(120));
            mockMvc.perform(get("/api/equipment/eq-cc5/drift-corrections"))
                    .andExpect(jsonPath("$.length()").value(0));
            Integer r2Revision = jdbc.queryForObject(
                    "SELECT revision_no FROM reading WHERE equipment_id = 'eq-cc5'"
                            + " AND reading_id = 'r2'", Integer.class);
            assertEquals(1, r2Revision, "冻结读数不得被漂移修正");
        } else {
            // 修正胜出：无保养记录，读数已修正且快照基于修正后读数
            mockMvc.perform(get("/api/equipment/eq-cc5/maintenances"))
                    .andExpect(jsonPath("$.length()").value(0));
            mockMvc.perform(get("/api/equipment/eq-cc5/maintenance-snapshots"))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].latestCumulativeMillis").value(10800000));
        }
    }
}

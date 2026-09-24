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
 * 停机区间扣减与保养判定重算测试（真实 H2 内存库，MySQL 兼容模式）。
 * 覆盖：区间校验、扣减量计算、读数修订重算、锚点保护、撤销、保养结算固化、
 * downtimeKey 全局唯一、requestId 幂等以及并发重叠裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DowntimeTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM downtime");
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

    private MvcResult downtime(String equipmentId, String requestId, long expectedVersion,
                               String downtimeKey, String startAt, String endAt, String reason)
            throws Exception {
        return mockMvc.perform(post("/api/equipment/" + equipmentId + "/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"downtimeKey":"%s",
                                 "startAt":"%s","endAt":"%s","reason":"%s"}
                                """.formatted(requestId, expectedVersion, downtimeKey,
                                startAt, endAt, reason)))
                .andReturn();
    }

    private MvcResult cancel(String equipmentId, String downtimeKey, String requestId,
                             long expectedVersion) throws Exception {
        return mockMvc.perform(post("/api/equipment/" + equipmentId + "/downtimes/"
                                + downtimeKey + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d}
                                """.formatted(requestId, expectedVersion)))
                .andReturn();
    }

    /** 准备标准读数序列：10:00=0、11:00=100、12:00=160（设备初始版本 1，最终版本 4）。 */
    private void seedReadings(String equipmentId) throws Exception {
        addReading(equipmentId, equipmentId + "-r1", 1, "r1", "2026-01-01T10:00:00Z", 0);
        addReading(equipmentId, equipmentId + "-r2", 2, "r2", "2026-01-01T11:00:00Z", 100);
        addReading(equipmentId, equipmentId + "-r3", 3, "r3", "2026-01-01T12:00:00Z", 160);
    }

    // ---------- 区间校验 ----------

    @Test
    void downtime_validationFailures_return422() throws Exception {
        register("eq-val", 1000);

        // 无读数：起止无法落在读数范围内
        MvcResult noReading = downtime("eq-val", "dt-noread", 1, "k-noread",
                "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "no reading yet");
        assertEquals(422, noReading.getResponse().getStatus());
        assertTrue(noReading.getResponse().getContentAsString().contains("DOWNTIME_OUTSIDE_READINGS"));

        seedReadings("eq-val"); // 版本推进到 4

        // 结束不晚于开始
        assertEquals(422, downtime("eq-val", "dt-badrange", 4, "k-badrange",
                "2026-01-01T11:00:00Z", "2026-01-01T11:00:00Z", "zero length")
                .getResponse().getStatus());
        assertEquals(422, downtime("eq-val", "dt-badrange2", 4, "k-badrange2",
                "2026-01-01T11:30:00Z", "2026-01-01T11:00:00Z", "reversed")
                .getResponse().getStatus());

        // 早于最早读数
        assertEquals(422, downtime("eq-val", "dt-before", 4, "k-before",
                "2026-01-01T09:00:00Z", "2026-01-01T10:00:00Z", "before earliest")
                .getResponse().getStatus());
        // 晚于最晚读数
        assertEquals(422, downtime("eq-val", "dt-after", 4, "k-after",
                "2026-01-01T12:00:00Z", "2026-01-01T13:00:00Z", "after latest")
                .getResponse().getStatus());

        // 原因非空（Bean 校验 400）
        MvcResult blankReason = mockMvc.perform(post("/api/equipment/eq-val/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dt-blank","expectedVersion":4,"downtimeKey":"k-blank",
                                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:00:00Z","reason":""}
                                """))
                .andReturn();
        assertEquals(400, blankReason.getResponse().getStatus());

        // 失败不占键、不加版本
        mockMvc.perform(get("/api/equipment/eq-val/status"))
                .andExpect(jsonPath("$.version").value(4));
        mockMvc.perform(get("/api/equipment/eq-val/downtimes"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void downtime_overlapRejected_butEndpointTouchingAllowed() throws Exception {
        register("eq-ovl", 1000);
        seedReadings("eq-ovl"); // 版本 4

        assertEquals(201, downtime("eq-ovl", "dt-a", 4, "k-a",
                "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "first")
                .getResponse().getStatus());

        // 严格重叠 → 422（版本现为 5）
        MvcResult overlap = downtime("eq-ovl", "dt-b", 5, "k-b",
                "2026-01-01T10:30:00Z", "2026-01-01T11:30:00Z", "overlap");
        assertEquals(422, overlap.getResponse().getStatus());
        assertTrue(overlap.getResponse().getContentAsString().contains("DOWNTIME_OVERLAP"));

        // 端点相接合法：[11:00,12:00) 与 [10:00,11:00)
        assertEquals(201, downtime("eq-ovl", "dt-c", 5, "k-c",
                "2026-01-01T11:00:00Z", "2026-01-01T12:00:00Z", "touching")
                .getResponse().getStatus());
        // 被包含的区间同样算重叠（版本现为 6）
        assertEquals(422, downtime("eq-ovl", "dt-d", 6, "k-d",
                "2026-01-01T10:15:00Z", "2026-01-01T10:45:00Z", "inside")
                .getResponse().getStatus());

        mockMvc.perform(get("/api/equipment/eq-ovl/downtimes"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].downtimeKey").value("k-a"))
                .andExpect(jsonPath("$[1].downtimeKey").value("k-c"));
    }

    @Test
    void downtime_crossingAnchorRejected_anchorAtEndpointAllowed() throws Exception {
        register("eq-anc", 1000);
        seedReadings("eq-anc"); // 版本 4

        // 在 11:00（r2）完成保养
        mockMvc.perform(post("/api/equipment/eq-anc/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-1","expectedVersion":4,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated()); // 版本 5

        // 区间跨越锚点 11:00 → 422
        MvcResult crossing = downtime("eq-anc", "dt-cross", 5, "k-cross",
                "2026-01-01T10:30:00Z", "2026-01-01T11:30:00Z", "cross");
        assertEquals(422, crossing.getResponse().getStatus());
        assertTrue(crossing.getResponse().getContentAsString().contains("DOWNTIME_CROSSES_ANCHOR"));

        // 锚点恰为区间终点（区间在保养之前）合法
        assertEquals(201, downtime("eq-anc", "dt-before", 5, "k-before-anchor",
                "2026-01-01T10:30:00Z", "2026-01-01T11:00:00Z", "ends at anchor")
                .getResponse().getStatus());
        // 锚点恰为区间起点（区间在保养之后）合法，且与上一区间端点相接（版本 6）
        assertEquals(201, downtime("eq-anc", "dt-after", 6, "k-after-anchor",
                "2026-01-01T11:00:00Z", "2026-01-01T12:00:00Z", "starts at anchor")
                .getResponse().getStatus());
    }

    // ---------- 扣减量计算 ----------

    @Test
    void deduction_excludedFromRunMinutes() throws Exception {
        register("eq-ded", 100);
        seedReadings("eq-ded"); // 版本 4；最新累计 160

        // 无停机：运行 160，已达周期 100 → DUE
        mockMvc.perform(get("/api/equipment/eq-ded/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(0))
                .andExpect(jsonPath("$.runMinutes").value(160))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 停机 [10:00,11:00)：计量器在停机期间仍计数，扣减 = 读数(≤11:00)=100 − 读数(≤10:00)=0 = 100
        MvcResult created = downtime("eq-ded", "dt-1", 4, "k-1",
                "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "lunch break");
        assertEquals(201, created.getResponse().getStatus());
        mockMvc.perform(get("/api/equipment/eq-ded/downtimes/k-1"))
                .andExpect(jsonPath("$.deductionMinutes").value(100))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.includedInCurrentRun").value(true))
                .andExpect(jsonPath("$.equipmentVersion").value(5));

        // 运行分钟 = 160 - 0(锚点) - 100 = 60 → OK
        mockMvc.perform(get("/api/equipment/eq-ded/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(100))
                .andExpect(jsonPath("$.runMinutes").value(60))
                .andExpect(jsonPath("$.status").value("OK"));

        // 第二个停机 [11:00,12:00)：扣减 = 160 − 100 = 60；合计 160 → 运行 0（版本 5）
        assertEquals(201, downtime("eq-ded", "dt-2", 5, "k-2",
                "2026-01-01T11:00:00Z", "2026-01-01T12:00:00Z", "shift end")
                .getResponse().getStatus());
        mockMvc.perform(get("/api/equipment/eq-ded/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(160))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"));

        // 清单按开始时刻升序并带扣减明细
        mockMvc.perform(get("/api/equipment/eq-ded/downtimes"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].downtimeKey").value("k-1"))
                .andExpect(jsonPath("$[0].deductionMinutes").value(100))
                .andExpect(jsonPath("$[1].downtimeKey").value("k-2"))
                .andExpect(jsonPath("$[1].deductionMinutes").value(60));
    }

    @Test
    void deduction_boundaryWithoutReading_isZero() throws Exception {
        register("eq-bound", 1000);
        seedReadings("eq-bound"); // 版本 4

        // 区间 [10:15,10:45)：两端点都取不到读数，各自回退到 10:00=0，扣减 0
        assertEquals(201, downtime("eq-bound", "dt-z", 4, "k-z",
                "2026-01-01T10:15:00Z", "2026-01-01T10:45:00Z", "no boundary reading")
                .getResponse().getStatus());
        mockMvc.perform(get("/api/equipment/eq-bound/downtimes/k-z"))
                .andExpect(jsonPath("$.deductionMinutes").value(0));
        mockMvc.perform(get("/api/equipment/eq-bound/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(0))
                .andExpect(jsonPath("$.runMinutes").value(160));
    }

    // ---------- 读数修订重算 ----------

    @Test
    void reviseBoundaryReading_recalculatesDeductionAndStatus() throws Exception {
        register("eq-recalc", 1000);
        seedReadings("eq-recalc"); // 版本 4
        assertEquals(201, downtime("eq-recalc", "dt-1", 4, "k-1",
                "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "break")
                .getResponse().getStatus()); // 扣减 100，版本 5

        // 修订被停机区间用作结束边界的读数 r2：100 → 120（满足前后相邻：0 ≤ 120 ≤ 160）
        mockMvc.perform(post("/api/equipment/eq-recalc/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-1","expectedVersion":5,"cumulativeMinutes":120}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2)); // 版本 6

        // 扣减明细实时重算为 120；运行分钟 = 160 - 120 = 40
        mockMvc.perform(get("/api/equipment/eq-recalc/downtimes/k-1"))
                .andExpect(jsonPath("$.deductionMinutes").value(120));
        mockMvc.perform(get("/api/equipment/eq-recalc/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(120))
                .andExpect(jsonPath("$.runMinutes").value(40));

        // 修订后违反相邻约束（超过后邻 160 不允许；低于前邻也不允许）
        mockMvc.perform(post("/api/equipment/eq-recalc/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-2","expectedVersion":6,"cumulativeMinutes":200}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- 撤销 ----------

    @Test
    void cancelDowntime_excludesDeductionAndKeepsRecord() throws Exception {
        register("eq-cancel", 1000);
        seedReadings("eq-cancel"); // 版本 4
        assertEquals(201, downtime("eq-cancel", "dt-1", 4, "k-cancel",
                "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "break")
                .getResponse().getStatus()); // 版本 5

        // 版本错误 → 409
        assertEquals(409, cancel("eq-cancel", "k-cancel", "cancel-bad-ver", 4)
                .getResponse().getStatus());

        MvcResult cancelled = cancel("eq-cancel", "k-cancel", "cancel-1", 5);
        assertEquals(201, cancelled.getResponse().getStatus());
        assertTrue(cancelled.getResponse().getContentAsString().contains("CANCELLED"));

        // 撤销后扣减不再参与计算
        mockMvc.perform(get("/api/equipment/eq-cancel/status"))
                .andExpect(jsonPath("$.version").value(6))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(0))
                .andExpect(jsonPath("$.runMinutes").value(160));

        // 记录保留、不可改写：明细仍可查，状态 CANCELLED、扣减 0
        mockMvc.perform(get("/api/equipment/eq-cancel/downtimes/k-cancel"))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.deductionMinutes").value(0))
                .andExpect(jsonPath("$.includedInCurrentRun").value(false))
                .andExpect(jsonPath("$.revokedAt").exists());

        // 重复撤销 → 409
        assertEquals(409, cancel("eq-cancel", "k-cancel", "cancel-2", 6)
                .getResponse().getStatus());
        // 撤销后可在同一时刻登记新的生效区间（原区间不再参与重叠判定）
        assertEquals(201, downtime("eq-cancel", "dt-2", 6, "k-replacement",
                "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "new break")
                .getResponse().getStatus());

        // 不存在的区间 → 404
        assertEquals(404, cancel("eq-cancel", "k-missing", "cancel-3", 7)
                .getResponse().getStatus());
    }

    // ---------- 完成保养结算 ----------

    @Test
    void completeMaintenance_settlesDeductionAndRestartsCycle() throws Exception {
        register("eq-set", 100);
        seedReadings("eq-set"); // 版本 4
        // d1：保养前停机，扣减 100
        assertEquals(201, downtime("eq-set", "dt-1", 4, "k-set-1",
                "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "break")
                .getResponse().getStatus()); // 版本 5

        // 在 12:00（r3=160）完成保养：固化扣减合计 100、运行分钟 60
        MvcResult maintenance = mockMvc.perform(post("/api/equipment/eq-set/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-set","expectedVersion":5,"readingId":"r3","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anchorCumulativeMinutes").value(160))
                .andExpect(jsonPath("$.settledDeductionMinutes").value(100))
                .andExpect(jsonPath("$.settledRunMinutes").value(60))
                .andReturn();
        assertTrue(maintenance.getResponse().getStatus() == 201);

        // 保养历史同样带结算快照
        mockMvc.perform(get("/api/equipment/eq-set/maintenances"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].settledDeductionMinutes").value(100))
                .andExpect(jsonPath("$[0].settledRunMinutes").value(60));

        // 保养后：d1 开始于锚点之前，不再计入当前周期扣减；状态运行分钟归零
        mockMvc.perform(get("/api/equipment/eq-set/downtimes/k-set-1"))
                .andExpect(jsonPath("$.includedInCurrentRun").value(false));
        mockMvc.perform(get("/api/equipment/eq-set/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(0))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"));

        // 新增读数 r4=200 与锚点后停机 d2 [12:00,13:00)，扣减 40 → 运行 0
        addReading("eq-set", "r4-add", 6, "r4", "2026-01-01T13:00:00Z", 200);
        assertEquals(201, downtime("eq-set", "dt-2", 7, "k-set-2",
                "2026-01-01T12:00:00Z", "2026-01-01T13:00:00Z", "after anchor")
                .getResponse().getStatus());
        mockMvc.perform(get("/api/equipment/eq-set/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(40))
                .andExpect(jsonPath("$.runMinutes").value(0));

        // 撤销 d2 后运行分钟恢复为 40
        assertEquals(201, cancel("eq-set", "k-set-2", "cancel-set", 8)
                .getResponse().getStatus());
        mockMvc.perform(get("/api/equipment/eq-set/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(0))
                .andExpect(jsonPath("$.runMinutes").value(40));
    }

    // ---------- 全局唯一键与幂等 ----------

    @Test
    void downtimeKey_globallyUniqueAndRequestIdIdempotent() throws Exception {
        register("eq-a", 1000);
        register("eq-b", 1000);
        seedReadings("eq-a");
        seedReadings("eq-b");
        // eq-a 再补一条 13:00 读数，版本 4 → 5，供后续端点相接区间使用
        addReading("eq-a", "eq-a-r4", 4, "r4", "2026-01-01T13:00:00Z", 200);

        assertEquals(201, downtime("eq-a", "dt-g1", 5, "global-key",
                "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "on A")
                .getResponse().getStatus());

        // 同 downtimeKey 用于另一设备 → 409（全局唯一）
        MvcResult reused = downtime("eq-b", "dt-g2", 4, "global-key",
                "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "on B");
        assertEquals(409, reused.getResponse().getStatus());
        assertTrue(reused.getResponse().getContentAsString().contains("DOWNTIME_KEY_EXISTS"));

        // 同键同参重放：返回首次结果，不重复落库、版本不重复增加
        MvcResult first = downtime("eq-a", "dt-rep", 6, "replay-key",
                "2026-01-01T11:00:00Z", "2026-01-01T12:00:00Z", "replay me");
        MvcResult replay = downtime("eq-a", "dt-rep", 6, "replay-key",
                "2026-01-01T11:00:00Z", "2026-01-01T12:00:00Z", "replay me");
        assertEquals(201, first.getResponse().getStatus());
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-a/status"))
                .andExpect(jsonPath("$.version").value(7));

        // 同键异参 → 409
        MvcResult mismatch = downtime("eq-a", "dt-rep", 7, "replay-key",
                "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "different");
        assertEquals(409, mismatch.getResponse().getStatus());
        assertTrue(mismatch.getResponse().getContentAsString().contains("REQUEST_ID_CONFLICT"));

        // 失败不占键：重叠 422 后，同一 requestId 改用合法区间（与 replay-key 端点相接）可成功
        MvcResult failed = downtime("eq-a", "dt-retry", 7, "retry-key",
                "2026-01-01T10:30:00Z", "2026-01-01T11:30:00Z", "overlap");
        assertEquals(422, failed.getResponse().getStatus());
        assertEquals(201, downtime("eq-a", "dt-retry", 7, "retry-key",
                "2026-01-01T12:00:00Z", "2026-01-01T13:00:00Z", "fixed")
                .getResponse().getStatus());
    }

    // ---------- 并发重叠裁决 ----------

    @Test
    void concurrentOverlappingDowntimes_onlyOneActive() throws Exception {
        register("eq-conc", 1000);
        seedReadings("eq-conc"); // 版本 4

        CountDownLatch gate = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        // 两个相互重叠的区间并发提交，均按版本 4 提交：行锁串行化后版本裁决，恰一个成功
        futures.add(executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return downtime("eq-conc", "dt-c1", 4, "k-c1",
                    "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "one");
        }));
        futures.add(executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return downtime("eq-conc", "dt-c2", 4, "k-c2",
                    "2026-01-01T10:30:00Z", "2026-01-01T11:30:00Z", "two");
        }));
        gate.countDown();

        int success = 0;
        int conflict = 0;
        for (Future<MvcResult> future : futures) {
            int status = future.get(15, TimeUnit.SECONDS).getResponse().getStatus();
            assertTrue(status == 201 || status == 409, "只允许 201 或 409，实际：" + status);
            if (status == 201) {
                success++;
            } else {
                conflict++;
            }
        }
        assertEquals(1, success, "重叠区间并发应恰有一个登记成功");
        assertEquals(1, conflict, "另一个应因版本冲突失败");

        // 最终只有一个生效区间，版本只前进一次
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM downtime WHERE equipment_id = 'eq-conc' AND status = 'ACTIVE'",
                Integer.class);
        assertEquals(1, activeCount, "同一设备不得出现重叠生效区间");
        mockMvc.perform(get("/api/equipment/eq-conc/status"))
                .andExpect(jsonPath("$.version").value(5));
    }
}

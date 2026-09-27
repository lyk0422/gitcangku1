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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 能源隔离并发边界测试：真实 H2 数据库上的并发确认、解除、读数裁决与幂等重放，
 * 使用锁与线程协调真实操作并设置超时，断言业务结果与最终数据，不依赖休眠或打印判断。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClock.Config.class)
class IsolationConcurrencyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM rejected_reading");
        jdbc.update("DELETE FROM isolation_confirmation");
        jdbc.update("DELETE FROM isolation_lock_point");
        jdbc.update("DELETE FROM isolation_order");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM equipment");
        clock.setInstant(MutableClock.Config.INITIAL);
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private void register(String equipmentId) throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":1000}
                                """.formatted(equipmentId, equipmentId)))
                .andExpect(status().isCreated());
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private long createIsolationWithPoints(String equipmentId, String requestId, long expectedVersion,
                                           int pointCount) throws Exception {
        StringBuilder points = new StringBuilder("[");
        for (int i = 1; i <= pointCount; i++) {
            if (i > 1) {
                points.append(",");
            }
            points.append("{\"lockPointId\":\"LP-%d\"}".formatted(i));
        }
        points.append("]");
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/isolations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"operatorId":"chief","lockPoints":%s}
                                """.formatted(requestId, expectedVersion, points)))
                .andExpect(status().isCreated());
        Long id = jdbc.queryForObject(
                "SELECT isolation_id FROM isolation_order WHERE equipment_id = ?", Long.class, equipmentId);
        return id == null ? -1L : id;
    }

    /** 两名不同角色并发确认：行锁串行化后乐观版本冲突的一方取最新版本重试，最终恰好两条确认且生效。 */
    @Test
    void concurrentDistinctRoles_activatesExactlyOnce() throws Exception {
        register("eq-cfm");
        long isolationId = createIsolationWithPoints("eq-cfm", "iso-cfm", 1, 2);

        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        futures.add(submitConfirmWithRetry(gate, "eq-cfm", isolationId, "cc-1", "alice", "R1"));
        futures.add(submitConfirmWithRetry(gate, "eq-cfm", isolationId, "cc-2", "bob", "R2"));
        gate.countDown();

        Set<Integer> finalStatuses = new HashSet<>();
        for (Future<Integer> future : futures) {
            finalStatuses.add(future.get(15, TimeUnit.SECONDS));
        }
        assertEquals(Set.of(201), finalStatuses, "两名不同角色经乐观重试后都应确认成功");

        mockMvc.perform(get("/api/equipment/eq-cfm/isolations/" + isolationId))
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.confirmedRoleCount").value(2));
        Integer confirmationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM isolation_confirmation WHERE isolation_id = ?",
                Integer.class, isolationId);
        assertEquals(2, confirmationCount);
        // 版本因两次确认各加一（创建后为 2，两次成功确认后为 4）
        mockMvc.perform(get("/api/equipment/eq-cfm/status"))
                .andExpect(jsonPath("$.version").value(4));
    }

    /** 并发确认：版本冲突（409）时按最新版本重试，模拟客户端乐观并发；返回最终 HTTP 状态。 */
    private Future<Integer> submitConfirmWithRetry(CountDownLatch gate, String equipmentId,
                                                   long isolationId, String requestId, String operator,
                                                   String role) {
        return executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            for (int attempt = 0; attempt < 20; attempt++) {
                Long version = jdbc.queryForObject(
                        "SELECT version FROM equipment WHERE equipment_id = ?", Long.class, equipmentId);
                MvcResult result = postJson(
                        "/api/equipment/" + equipmentId + "/isolations/" + isolationId + "/confirmations",
                        """
                                {"requestId":"%s","expectedVersion":%d,"operatorId":"%s","role":"%s"}
                                """.formatted(requestId, version, operator, role));
                int status = result.getResponse().getStatus();
                if (status == 201) {
                    return 201;
                }
                if (status != 409) {
                    return status;
                }
            }
            throw new IllegalStateException("确认乐观重试次数耗尽：" + requestId);
        });
    }

    /** 同 requestId 同参数并发确认：全部重放首次成功快照，只产生一条确认。 */
    @Test
    void concurrentSameConfirmRequest_replaysSingleEffect() throws Exception {
        register("eq-rep");
        long isolationId = createIsolationWithPoints("eq-rep", "iso-rep", 1, 2);

        int threads = 6;
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-rep/isolations/" + isolationId + "/confirmations",
                        """
                                {"requestId":"same-cfm","expectedVersion":2,"operatorId":"alice","role":"R1"}
                                """);
            }));
        }
        gate.countDown();

        Set<String> bodies = new HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus());
            bodies.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, bodies.size(), "并发同键同参应全部返回同一成功快照");
        Integer confirmationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM isolation_confirmation WHERE isolation_id = ?",
                Integer.class, isolationId);
        assertEquals(1, confirmationCount, "确认效果只发生一次");
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'same-cfm'", Integer.class);
        assertEquals(1, idemCount);
    }

    /** 隔离生效期间并发新增读数：全部 409 且各自固化证据，读数一条不落库、版本不推进。 */
    @Test
    void concurrentReadingsWhileActive_allRejectedAndEvidenced() throws Exception {
        register("eq-blk");
        long isolationId = createIsolationWithPoints("eq-blk", "iso-blk", 1, 1);
        activate(isolationId, "eq-blk", 2);
        // 当前版本 4（登记 1 → 创建 2 → 两次确认 3、4）

        int threads = 6;
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-blk/readings", """
                        {"requestId":"blk-%d","expectedVersion":4,"readingId":"rb-%d",
                         "sampledAt":"2026-01-01T1%d:00:00Z","cumulativeMinutes":%d,"operatorId":"reader-%d"}
                        """.formatted(index, index, index, 100L * (index + 1), index));
            }));
        }
        gate.countDown();

        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            assertEquals(409, result.getResponse().getStatus());
            assertTrue(result.getResponse().getContentAsString().contains("EQUIPMENT_ISOLATED"));
        }
        Integer readingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading WHERE equipment_id = 'eq-blk'", Integer.class);
        assertEquals(0, readingCount, "被拒读数不得落库");
        Integer evidenceCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rejected_reading WHERE equipment_id = 'eq-blk'", Integer.class);
        assertEquals(threads, evidenceCount, "每次被拒提交都应固化一条证据");
        // 证据互不覆盖：操作人与读数标识齐全且去重
        Integer distinctOps = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT operator_id) FROM rejected_reading WHERE equipment_id = 'eq-blk'",
                Integer.class);
        assertEquals(threads, distinctOps);
        mockMvc.perform(get("/api/equipment/eq-blk/status"))
                .andExpect(jsonPath("$.version").value(4));
    }

    /** 并发解除两个不同锁定点：都成功后隔离令恰好 RELEASED，计数与最终数据一致。 */
    @Test
    void concurrentReleaseDistinctPoints_completesIsolation() throws Exception {
        register("eq-rel");
        long isolationId = createIsolationWithPoints("eq-rel", "iso-rel", 1, 2);
        activate(isolationId, "eq-rel", 2);
        // 当前版本 4

        CountDownLatch gate = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        futures.add(submitRelease(gate, "eq-rel", isolationId, "LP-1", "r-1", 4, "原因一"));
        futures.add(submitRelease(gate, "eq-rel", isolationId, "LP-2", "r-2", 4, "原因二"));
        gate.countDown();

        // 行锁串行化：一个期望版本 4 成功，另一个因版本已变为 5 而 409（乐观版本保护）。
        int success = 0;
        int conflict = 0;
        for (Future<MvcResult> future : futures) {
            int status = future.get(15, TimeUnit.SECONDS).getResponse().getStatus();
            if (status == 201) {
                success++;
            } else if (status == 409) {
                conflict++;
            } else {
                throw new IllegalStateException("解除并发仅允许 201/409，实际 " + status);
            }
        }
        assertEquals(1, success);
        assertEquals(1, conflict);

        // 用最新版本重试冲突的解除，最终全部解除、隔离 RELEASED
        long version = jdbc.queryForObject("SELECT version FROM equipment WHERE equipment_id = 'eq-rel'",
                Long.class);
        String remainingPoint = jdbc.queryForObject(
                "SELECT lock_point_id FROM isolation_lock_point WHERE isolation_id = ? AND released_at IS NULL",
                String.class, isolationId);
        mockMvc.perform(post("/api/equipment/eq-rel/isolations/" + isolationId
                        + "/lock-points/" + remainingPoint + "/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"r-retry","expectedVersion":%d,"operatorId":"chief","reason":"补齐解除"}
                                """.formatted(version)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RELEASED"))
                .andExpect(jsonPath("$.releasedLockPointCount").value(2))
                .andExpect(jsonPath("$.remainingLockPointCount").value(0));
    }

    private Future<MvcResult> submitRelease(CountDownLatch gate, String equipmentId, long isolationId,
                                            String lockPointId, String requestId, long expectedVersion,
                                            String reason) {
        return executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/" + equipmentId + "/isolations/" + isolationId
                            + "/lock-points/" + lockPointId + "/release",
                    """
                            {"requestId":"%s","expectedVersion":%d,"operatorId":"chief","reason":"%s"}
                            """.formatted(requestId, expectedVersion, reason));
        });
    }

    /**
     * 提交顺序裁决：第二名角色确认（使隔离生效）与新增读数以相同期望版本并发。
     * 先拿设备行锁者生效：确认先提交则读数不得写入（409）；读数先提交则确认失败（409），两者互斥，最终状态自洽。
     */
    @Test
    void concurrentConfirmVsReading_orderingIsAdjudicated() throws Exception {
        register("eq-ord");
        long isolationId = createIsolationWithPoints("eq-ord", "iso-ord", 1, 1);
        // 先完成第一名角色确认：版本 1→登记 2→创建 3→确认一 4；当前版本 4？实际：登记1 创建2 确认一3
        mockMvc.perform(post("/api/equipment/eq-ord/isolations/" + isolationId + "/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ord-c1","expectedVersion":2,"operatorId":"a","role":"R1"}
                                """))
                .andExpect(status().isCreated());
        // 当前版本 3

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> confirmFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-ord/isolations/" + isolationId + "/confirmations",
                    """
                            {"requestId":"ord-c2","expectedVersion":3,"operatorId":"b","role":"R2"}
                            """);
        });
        Future<MvcResult> readingFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-ord/readings",
                    """
                            {"requestId":"ord-r","expectedVersion":3,"readingId":"r1",
                             "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100,"operatorId":"reader"}
                            """);
        });
        gate.countDown();

        int confirmStatus = confirmFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int readingStatus = readingFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();

        Integer readingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading WHERE equipment_id = 'eq-ord'", Integer.class);
        String isolationState = jdbc.queryForObject(
                "SELECT state FROM isolation_order WHERE isolation_id = ?", String.class, isolationId);
        long version = jdbc.queryForObject("SELECT version FROM equipment WHERE equipment_id = 'eq-ord'",
                Long.class);

        if (confirmStatus == 201) {
            // 隔离先生效：读数必须被版本或隔离规则拒绝，读数不存在，隔离 ACTIVE
            assertEquals(409, readingStatus, "隔离先提交时读数不得写入");
            assertEquals(0, readingCount);
            assertEquals("ACTIVE", isolationState);
        } else {
            // 读数先写入：确认版本冲突，读数存在，隔离仍 PENDING；随后可按新版本完成确认并拒绝后续读数
            assertEquals(409, confirmStatus);
            assertEquals(201, readingStatus);
            assertEquals(1, readingCount);
            assertEquals("PENDING", isolationState);
            mockMvc.perform(post("/api/equipment/eq-ord/isolations/" + isolationId + "/confirmations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"requestId":"ord-c2b","expectedVersion":%d,"operatorId":"b","role":"R2"}
                                    """.formatted(version)))
                    .andExpect(status().isCreated());
            mockMvc.perform(post("/api/equipment/eq-ord/readings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"requestId":"ord-r2","expectedVersion":%d,"readingId":"r2",
                                     "sampledAt":"2026-01-01T11:00:00Z","cumulativeMinutes":200}
                                    """.formatted(version + 1)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("EQUIPMENT_ISOLATED"));
        }
    }

    private void activate(long isolationId, String equipmentId, long versionAfterCreate) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/isolations/" + isolationId
                        + "/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"act-1","expectedVersion":%d,"operatorId":"a","role":"R1"}
                                """.formatted(versionAfterCreate)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/isolations/" + isolationId
                        + "/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"act-2","expectedVersion":%d,"operatorId":"b","role":"R2"}
                                """.formatted(versionAfterCreate + 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("ACTIVE"));
    }
}

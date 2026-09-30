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
        jdbc.update("DELETE FROM reading_witness");
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
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":%d,
                                 "witnessThresholdMinutes":1000000}
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
                    {"requestId":"race-revise","expectedVersion":3,"cumulativeMinutes":150,
                     "submittedBy":"alice"}
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

    /**
     * 新修订与见证并发：设备行锁串行化，见证只能确认申请中的指定版本；
     * 新修订无论先后都不得让旧见证批准新读数。最终读数恰有一个版本生效，数据一致。
     */
    @Test
    void concurrentReviseVsWitness_witnessOnlyConfirmsSpecifiedVersion() throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-eq-rw","equipmentId":"eq-rw",
                                 "maintenancePeriodMinutes":100000,"witnessThresholdMinutes":100}
                                """))
                .andExpect(status().isCreated());
        postJson("/api/equipment/eq-rw/readings", """
                {"requestId":"rw-add","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":1000}
                """);
        postJson("/api/equipment/eq-rw/readings/r1/revisions", """
                {"requestId":"rw-rev-1","expectedVersion":2,"cumulativeMinutes":1200,
                 "submittedBy":"alice"}
                """);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> witnessFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-rw/readings/r1/witness", """
                    {"requestId":"rw-wit","expectedVersion":3,"expectedRevisionNo":2,
                     "cumulativeMinutes":1200,"witnessedBy":"bob"}
                    """);
        });
        // 并发新修订（阈值内 1210）：有待确认时被 422 拦截；若见证先生效，则因期望版本过旧 409
        Future<MvcResult> reviseFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-rw/readings/r1/revisions", """
                    {"requestId":"rw-rev-2","expectedVersion":3,"cumulativeMinutes":1210,
                     "submittedBy":"alice"}
                    """);
        });
        gate.countDown();

        int witnessStatus = witnessFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int reviseStatus = reviseFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();

        // 见证必成功（指定版本 2/值 1200 始终与申请一致）；新修订必失败（422 待确认占用 或 409 版本冲突）
        assertEquals(201, witnessStatus, "见证应确认其指定的待确认版本");
        assertTrue(reviseStatus == 422 || reviseStatus == 409,
                "并发新修订不得干扰见证指定版本，实际：" + reviseStatus);

        // 最终读数确定为见证生效值 1200、修订号 2，待确认行清空、版本只到 4
        mockMvc.perform(get("/api/equipment/eq-rw/status"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(1200));
        mockMvc.perform(get("/api/equipment/eq-rw/witnesses"))
                .andExpect(jsonPath("$.length()").value(0));
        Integer revisionNo = jdbc.queryForObject(
                "SELECT revision_no FROM reading WHERE equipment_id = 'eq-rw' AND reading_id = 'r1'",
                Integer.class);
        assertEquals(2, revisionNo, "旧见证不得批准新读数，新修订也不得越过待确认直接生效");
    }

    /** 并发不同 requestId 新增不同读数：串行化后均按版本推进，无丢失更新。 */
    @Test
    void concurrentDistinctWrites_noLostUpdate() throws Exception {        register("eq-par", 10000);
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

    /**
     * 并发两个见证（同一待确认版本、不同 requestId/见证人）：设备行锁串行化，
     * 恰有一个见证生效（201），另一个因版本已前进返回 409；读数只生效一次、修订历史只追加一行。
     */
    @Test
    void concurrentWitnesses_onlyOneConfirmsSpecifiedVersion() throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-eq-wrace","equipmentId":"eq-wrace",
                                 "maintenancePeriodMinutes":100000,"witnessThresholdMinutes":100}
                                """))
                .andExpect(status().isCreated());
        postJson("/api/equipment/eq-wrace/readings", """
                {"requestId":"wr-add","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":1000}
                """);
        // 大幅修订 1200 进入待确认（版本 2 → 3，待生效修订号 2）
        postJson("/api/equipment/eq-wrace/readings/r1/revisions", """
                {"requestId":"wr-rev","expectedVersion":2,"cumulativeMinutes":1200,
                 "submittedBy":"alice"}
                """);

        CountDownLatch gate = new CountDownLatch(1);
        String witnessBody = """
                {"requestId":"%s","expectedVersion":3,"expectedRevisionNo":2,
                 "cumulativeMinutes":1200,"witnessedBy":"%s"}
                """;
        Future<MvcResult> bob = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-wrace/readings/r1/witness",
                    witnessBody.formatted("wr-wit-bob", "bob"));
        });
        Future<MvcResult> carol = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-wrace/readings/r1/witness",
                    witnessBody.formatted("wr-wit-carol", "carol"));
        });
        gate.countDown();

        int bobStatus = bob.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int carolStatus = carol.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        assertTrue(
                (bobStatus == 201 && carolStatus == 409)
                        || (bobStatus == 409 && carolStatus == 201),
                "并发见证应恰有一个生效，实际：bob=" + bobStatus + ", carol=" + carolStatus);

        // 版本只前进一次（3 → 4），待确认申请已删除，修订历史恰为 2 行
        mockMvc.perform(get("/api/equipment/eq-wrace/status"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(1200));
        mockMvc.perform(get("/api/equipment/eq-wrace/witnesses"))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/equipment/eq-wrace/readings/r1/revisions"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].revisionNo").value(2))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(1200));
        Integer pendingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading_witness WHERE equipment_id = 'eq-wrace'", Integer.class);
        assertEquals(0, pendingCount, "见证生效后待确认行必须删除");
    }

    /**
     * 见证与完成保养并发（同一期望版本）：待确认读数不得放行，故保养必失败（422），
     * 见证不受影响地生效（201）；最终无保养记录、读数按待确认值生效。
     */
    @Test
    void concurrentWitnessVsMaintenance_witnessWinsAndMaintenanceRejected() throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-eq-wm","equipmentId":"eq-wm",
                                 "maintenancePeriodMinutes":100000,"witnessThresholdMinutes":100}
                                """))
                .andExpect(status().isCreated());
        postJson("/api/equipment/eq-wm/readings", """
                {"requestId":"wm-add","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":1000}
                """);
        postJson("/api/equipment/eq-wm/readings/r1/revisions", """
                {"requestId":"wm-rev","expectedVersion":2,"cumulativeMinutes":1200,
                 "submittedBy":"alice"}
                """);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> witnessFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-wm/readings/r1/witness", """
                    {"requestId":"wm-wit","expectedVersion":3,"expectedRevisionNo":2,
                     "cumulativeMinutes":1200,"witnessedBy":"bob"}
                    """);
        });
        Future<MvcResult> maintenanceFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-wm/maintenances", """
                    {"requestId":"wm-mnt","expectedVersion":3,"readingId":"r1","anchorRevisionNo":1}
                    """);
        });
        gate.countDown();

        int witnessStatus = witnessFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int maintenanceStatus = maintenanceFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        assertEquals(201, witnessStatus, "待确认读数见证应正常生效");
        assertTrue(maintenanceStatus == 422 || maintenanceStatus == 409,
                "待确认读数不得触发保养放行（先到 422；见证先到则锚点修订号不符 409），实际："
                        + maintenanceStatus);

        mockMvc.perform(get("/api/equipment/eq-wm/status"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(1200));
        mockMvc.perform(get("/api/equipment/eq-wm/maintenances"))
                .andExpect(jsonPath("$.length()").value(0));
    }
}

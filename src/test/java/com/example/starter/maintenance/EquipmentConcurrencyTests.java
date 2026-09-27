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
        jdbc.update("DELETE FROM maintenance_correction");
        jdbc.update("DELETE FROM maintenance_confirmation");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM maintenance_cycle");
        jdbc.update("DELETE FROM evidence_material_summary");
        jdbc.update("DELETE FROM evidence_check_result");
        jdbc.update("DELETE FROM evidence_package");
        jdbc.update("DELETE FROM maintenance_template_item");
        jdbc.update("DELETE FROM maintenance_template");
        jdbc.update("DELETE FROM material");
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

    /** 并发修订锚点读数与双人完工确认（同一期望版本）：恰有一个成功，已完工锚点不发生变化。 */
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
        // 模板 + 材料 + 合规证据包（提交人 bob）
        postJson("/api/equipment/eq-race/template/revisions", """
                {"requestId":"race-tpl","expectedVersion":3,"items":[
                  {"itemCode":"c1","itemName":"检查1","required":true}]}
                """);
        postJson("/api/materials", """
                {"requestId":"race-mat","materialId":"race-oil","materialName":"润滑油","unit":"L"}
                """);
        postJson("/api/equipment/eq-race/evidence", """
                {"requestId":"race-ev","expectedVersion":4,"submittedBy":"bob",
                 "checks":[{"itemCode":"c1","result":"PASS","note":null}],
                 "materials":[{"materialId":"race-oil","quantity":1}]}
                """);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> reviseFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-race/readings/r1/revisions", """
                    {"requestId":"race-revise","expectedVersion":5,"cumulativeMinutes":150}
                    """);
        });
        Future<MvcResult> maintenanceFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-race/maintenances", """
                    {"requestId":"race-mnt","expectedVersion":5,"readingId":"r1","anchorRevisionNo":1,
                     "expectedPackageVersion":1,"confirmedBy":"alice"}
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
                "修订与完工并发应恰有一个成功，实际：revise=" + reviseStatus
                        + ", maintenance=" + maintenanceStatus);

        // 版本只前进一次
        mockMvc.perform(get("/api/equipment/eq-race/status"))
                .andExpect(jsonPath("$.version").value(6));

        if (maintenanceStatus == 201) {
            // 完工胜出：锚点快照为修订号 1、工时 100，周期推进且读数不可再修订
            mockMvc.perform(get("/api/equipment/eq-race/maintenances"))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].anchorRevisionNo").value(1))
                    .andExpect(jsonPath("$[0].anchorCumulativeMinutes").value(100))
                    .andExpect(jsonPath("$[0].evidencePackageVersion").value(1))
                    .andExpect(jsonPath("$[0].confirmedBy").value("alice"));
            mockMvc.perform(get("/api/equipment/eq-race/cycles/current"))
                    .andExpect(jsonPath("$.cycleNo").value(2));
            mockMvc.perform(get("/api/equipment/eq-race/readings/r1/revisions"))
                    .andExpect(jsonPath("$.length()").value(1));
            Integer revisionNo = jdbc.queryForObject(
                    "SELECT revision_no FROM reading WHERE equipment_id = 'eq-race' AND reading_id = 'r1'",
                    Integer.class);
            assertEquals(1, revisionNo, "保养锚定后读数修订号不得变化");
            // 双人确认已落库且固化两个操作人
            Integer confirmationCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM maintenance_confirmation WHERE equipment_id = 'eq-race'"
                            + " AND submitted_by = 'bob' AND confirmed_by = 'alice'", Integer.class);
            assertEquals(1, confirmationCount, "应恰有一条双人完工确认");
        } else {
            // 修订胜出：无保养记录、周期仍为 1，读数修订号为 2、工时 150
            mockMvc.perform(get("/api/equipment/eq-race/maintenances"))
                    .andExpect(jsonPath("$.length()").value(0));
            mockMvc.perform(get("/api/equipment/eq-race/cycles/current"))
                    .andExpect(jsonPath("$.cycleNo").value(1))
                    .andExpect(jsonPath("$.status").value("OPEN"));
            mockMvc.perform(get("/api/equipment/eq-race/readings/r1/revisions"))
                    .andExpect(jsonPath("$.length()").value(2));
            Integer cumulative = jdbc.queryForObject(
                    "SELECT cumulative_minutes FROM reading WHERE equipment_id = 'eq-race' AND reading_id = 'r1'",
                    Integer.class);
            assertEquals(150, cumulative);
            Integer confirmationCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM maintenance_confirmation WHERE equipment_id = 'eq-race'",
                    Integer.class);
            assertEquals(0, confirmationCount, "失败的完工不得留下确认记录");
        }
    }

    /** 两个确认人并发确认同一证据包版本：恰有一个完工，周期不被完成两次。 */
    @Test
    void concurrentDoubleConfirmation_singleCompletion() throws Exception {
        register("eq-conf", 1000);
        postJson("/api/equipment/eq-conf/readings", """
                {"requestId":"conf-r1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                """);
        postJson("/api/equipment/eq-conf/template/revisions", """
                {"requestId":"conf-tpl","expectedVersion":2,"items":[
                  {"itemCode":"c1","itemName":"检查1","required":true}]}
                """);
        postJson("/api/materials", """
                {"requestId":"conf-mat","materialId":"conf-oil","materialName":"润滑油","unit":"L"}
                """);
        postJson("/api/equipment/eq-conf/evidence", """
                {"requestId":"conf-ev","expectedVersion":3,"submittedBy":"bob",
                 "checks":[{"itemCode":"c1","result":"PASS","note":null}],
                 "materials":[{"materialId":"conf-oil","quantity":1}]}
                """);

        int threads = 4;
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-conf/maintenances", """
                        {"requestId":"conf-mnt-%d","expectedVersion":4,"readingId":"r1",
                         "anchorRevisionNo":1,"expectedPackageVersion":1,"confirmedBy":"alice-%d"}
                        """.formatted(index, index));
            }));
        }
        gate.countDown();

        int success = 0;
        int conflict = 0;
        for (Future<MvcResult> future : futures) {
            int status = future.get(15, TimeUnit.SECONDS).getResponse().getStatus();
            if (status == 201) {
                success++;
            } else {
                assertEquals(409, status, "未成功者必须为版本冲突 409");
                conflict++;
            }
        }
        assertEquals(1, success, "同一周期只能完工一次");
        assertEquals(threads - 1, conflict);

        // 最终只有一条保养、一条确认、两个周期（1 COMPLETED + 2 OPEN）
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance WHERE equipment_id = 'eq-conf'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_confirmation WHERE equipment_id = 'eq-conf'",
                Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_cycle WHERE equipment_id = 'eq-conf'",
                Integer.class));
        mockMvc.perform(get("/api/equipment/eq-conf/cycles/current"))
                .andExpect(jsonPath("$.cycleNo").value(2));
    }

    /** 证据提交与材料撤销并发：按提交顺序裁决，要么引用成功要么 422，无脏状态。 */
    @Test
    void concurrentEvidenceVsMaterialRevocation_ordered() throws Exception {
        register("eq-mr", 1000);
        postJson("/api/equipment/eq-mr/readings", """
                {"requestId":"mr-r1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                """);
        postJson("/api/equipment/eq-mr/template/revisions", """
                {"requestId":"mr-tpl","expectedVersion":2,"items":[
                  {"itemCode":"c1","itemName":"检查1","required":true}]}
                """);
        postJson("/api/materials", """
                {"requestId":"mr-mat","materialId":"mr-oil","materialName":"润滑油","unit":"L"}
                """);

        int rounds = 8;
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            int index = i;
            // 一半线程尝试提交引用 mr-oil 的证据包（每轮版本不同，靠重试拿到最新版本）
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                for (int attempt = 0; attempt < 30; attempt++) {
                    MvcResult equipmentStatus = mockMvc.perform(get("/api/equipment/eq-mr/status"))
                            .andReturn();
                    long version = Long.parseLong(equipmentStatus.getResponse().getContentAsString()
                            .replaceAll(".*\"version\":(\\d+).*", "$1"));
                    MvcResult evidence = postJson("/api/equipment/eq-mr/evidence", """
                            {"requestId":"mr-ev-%d-%d","expectedVersion":%d,"submittedBy":"bob",
                             "checks":[{"itemCode":"c1","result":"PASS","note":null}],
                             "materials":[{"materialId":"mr-oil","quantity":1}]}
                            """.formatted(index, attempt, version));
                    int status = evidence.getResponse().getStatus();
                    if (status == 201 || status == 422) {
                        return evidence;
                    }
                    // 409 版本竞争或 5xx 锁等待牺牲者均重试
                    assertTrue(status == 409 || status >= 500,
                            "非预期中间状态：" + status);
                }
                throw new IllegalStateException("证据提交重试耗尽");
            }));
        }
        // 一个线程在闸门后立即撤销材料
        Future<MvcResult> revokeFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/materials/mr-oil/revocation", """
                    {"requestId":"mr-revoke","reason":"并发裁决撤销"}
                    """);
        });
        gate.countDown();

        MvcResult revoke = revokeFuture.get(15, TimeUnit.SECONDS);
        // 撤销本身与证据线程无设备版本竞争：材料行锁串行，撤销恰好一次成功
        assertEquals(200, revoke.getResponse().getStatus(),
                "材料撤销响应：" + revoke.getResponse().getContentAsString());

        int accepted = 0;
        int rejected = 0;
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(30, TimeUnit.SECONDS);
            if (result == null) {
                continue;
            }
            int status = result.getResponse().getStatus();
            if (status == 201) {
                accepted++;
            } else {
                assertEquals(422, status, "撤销后引用必须为 422："
                        + result.getResponse().getContentAsString());
                rejected++;
            }
        }
        // 同周期材料最多被一个成功证据包引用；其余在撤销前因重复或撤销后因失效而 422
        Integer summaries = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence_material_summary"
                        + " WHERE equipment_id = 'eq-mr' AND material_id = 'mr-oil'", Integer.class);
        assertEquals(accepted, summaries, "成功数必须与实际材料摘要数一致");
        assertTrue(accepted <= 1, "同周期同一材料至多被引用一次，实际成功 " + accepted + " 次");
        assertTrue(accepted + rejected >= 1);
    }
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
}

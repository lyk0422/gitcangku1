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
 * 部件装拆账并发与幂等边界测试：真实 H2 数据库上的并发写协调，断言响应与最终数据一致性。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ComponentConcurrencyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM component_installation");
        jdbc.update("DELETE FROM component");
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

    private void registerEquipment(String equipmentId) throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":1000}
                                """.formatted(equipmentId, equipmentId)))
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

    private void registerComponent(String componentId, long lifetimeMinutes) throws Exception {
        mockMvc.perform(post("/api/components")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"regc-%s","operator":"tester","componentId":"%s",
                                 "lifetimeMinutes":%d}
                                """.formatted(componentId, componentId, lifetimeMinutes)))
                .andExpect(status().isCreated());
    }

    /** 并发安装同一部件至两台设备：恰有一台成功（201），另一个 409，最终只有一条进行中安装。 */
    @Test
    void concurrentInstallSameComponent_onlyOneSucceeds() throws Exception {
        registerEquipment("E1");
        registerEquipment("E2");
        registerComponent("comp-race", 1000);
        addReading("E1", "r1", 1, "2026-02-01T10:00:00Z", 100);
        addReading("E2", "s1", 1, "2026-02-01T10:00:00Z", 500);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> toE1 = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/components/comp-race/installations", """
                    {"requestId":"ci-e1","operator":"tester","equipmentId":"E1","readingId":"r1",
                     "expectedVersion":1}
                    """);
        });
        Future<MvcResult> toE2 = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/components/comp-race/installations", """
                    {"requestId":"ci-e2","operator":"tester","equipmentId":"E2","readingId":"s1",
                     "expectedVersion":1}
                    """);
        });
        gate.countDown();

        int statusE1 = toE1.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int statusE2 = toE2.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        assertTrue((statusE1 == 201 && statusE2 == 409) || (statusE1 == 409 && statusE2 == 201),
                "并发安装同一部件应恰有一台成功，实际：E1=" + statusE1 + ", E2=" + statusE2);

        // 最终数据：仅一条安装记录且为进行中，当前设备与胜出一方一致
        mockMvc.perform(get("/api/components/comp-race"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installations.length()").value(1))
                .andExpect(jsonPath("$.installations[0].active").value(true))
                .andExpect(jsonPath("$.usedMinutes").value(0))
                .andExpect(jsonPath("$.version").value(2));
        String winner = statusE1 == 201 ? "E1" : "E2";
        mockMvc.perform(get("/api/components/comp-race"))
                .andExpect(jsonPath("$.currentEquipmentId").value(winner));
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM component_installation"
                        + " WHERE component_id = 'comp-race' AND remove_reading_id IS NULL",
                Integer.class);
        assertEquals(1, activeCount, "同一部件同一时刻只能装一台");
    }

    /** 并发安装与读数修订（同一读数）：联合判定，装拆端点取值与读数最终值一致，不允许先记归属再漏改。 */
    @Test
    void concurrentInstallVsRevise_jointDecision() throws Exception {
        registerEquipment("E1");
        registerComponent("comp-joint", 1000);
        addReading("E1", "r1", 1, "2026-02-01T10:00:00Z", 100);
        addReading("E1", "r2", 2, "2026-02-01T11:00:00Z", 200);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> installFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/components/comp-joint/installations", """
                    {"requestId":"cj-inst","operator":"tester","equipmentId":"E1","readingId":"r1",
                     "expectedVersion":1}
                    """);
        });
        Future<MvcResult> reviseFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/E1/readings/r1/revisions", """
                    {"requestId":"cj-revise","expectedVersion":3,"cumulativeMinutes":150}
                    """);
        });
        gate.countDown();

        int installStatus = installFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int reviseStatus = reviseFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();

        // 安装必成功；修订要么先完成（201），要么因读数被装拆账引用而拒绝（409）
        assertEquals(201, installStatus, "安装与修订无版本冲突，安装应成功");
        assertTrue(reviseStatus == 201 || reviseStatus == 409,
                "修订应成功或被装拆账引用拒绝，实际：" + reviseStatus);

        // 联合一致性：装拆账记录的安装累计必须等于读数最终生效值
        Long readingValue = jdbc.queryForObject(
                "SELECT cumulative_minutes FROM reading WHERE equipment_id = 'E1' AND reading_id = 'r1'",
                Long.class);
        Long installValue = jdbc.queryForObject(
                "SELECT install_cumulative_minutes FROM component_installation"
                        + " WHERE component_id = 'comp-joint'",
                Long.class);
        assertEquals(readingValue, installValue,
                "装拆端点快照与读数最终值必须一致（reviseStatus=" + reviseStatus + "）");
        if (reviseStatus == 201) {
            assertEquals(150L, installValue, "修订先生效时安装应记录修订后的值");
        } else {
            assertEquals(100L, installValue, "安装先生效时修订被拒绝，快照保持原值");
        }
    }

    /** 并发同 requestId 同参数拆卸：全部重放首次成功响应，寿命只累计一次。 */
    @Test
    void concurrentSameRequestIdRemove_replaysSingleEffect() throws Exception {
        registerEquipment("E1");
        registerComponent("comp-cid", 1000);
        addReading("E1", "r1", 1, "2026-02-01T10:00:00Z", 100);
        addReading("E1", "r2", 2, "2026-02-01T11:00:00Z", 160);
        postJson("/api/components/comp-cid/installations", """
                {"requestId":"cid-inst","operator":"tester","equipmentId":"E1","readingId":"r1",
                 "expectedVersion":1}
                """);

        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"cid-rm","operator":"tester","readingId":"r2","expectedVersion":2}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/components/comp-cid/removals", body);
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
        assertTrue(bodies.iterator().next().contains("\"deltaMinutes\":60"),
                "重放响应应保留首次拆卸的区间差值 60");

        // 业务效果只发生一次：寿命 60、一条装拆记录、版本仅加一
        mockMvc.perform(get("/api/components/comp-cid"))
                .andExpect(jsonPath("$.usedMinutes").value(60))
                .andExpect(jsonPath("$.installations.length()").value(1))
                .andExpect(jsonPath("$.version").value(3));
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'cid-rm'",
                Integer.class);
        assertEquals(1, idemCount, "同一 requestId 只应占一个幂等键");
    }
}

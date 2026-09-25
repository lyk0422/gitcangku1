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
 * 读数认证并发与幂等边界测试：真实 H2 数据库上的并发认证协调，
 * 并发按事务提交顺序裁决，断言响应与最终数据一致性。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CertificationConcurrencyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM reading_certification");
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
                                 "sampledAt":"%s","cumulativeMinutes":%d,"recordedBy":"alice"}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                cumulativeMinutes)))
                .andExpect(status().isCreated());
    }

    /** 并发同 certKey 同参数：全部得到完整重算结果，认证效果只发生一次。 */
    @Test
    void concurrentSameCertKey_replaysSingleEffect() throws Exception {
        register("eq-k1", 1000);
        addReading("eq-k1", "k1-add", 1, "r1", "2026-01-01T10:00:00Z", 100);

        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"ck-conc","certifier":"bob","items":[
                  {"equipmentId":"eq-k1","readingId":"r1","expectedRevisionNo":1}]}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/certifications", body);
            }));
        }
        gate.countDown();

        Set<String> bodies = new HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(),
                    "并发同 certKey 应全部重放成功结果");
            bodies.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, bodies.size(), "所有并发响应应完全相同（重放同一重算结果）");

        // 认证效果只发生一次：一条快照、版本仅加一、一个幂等键
        mockMvc.perform(get("/api/equipment/eq-k1/certifications"))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/equipment/eq-k1/status"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.runMinutes").value(100));
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'ck-conc'",
                Integer.class);
        assertEquals(1, idemCount, "同一 certKey 只应占一个幂等键");
    }

    /** 并发不同 certKey 认证同一读数：恰有一个成功，其余 409。 */
    @Test
    void concurrentCertifySameReading_exactlyOneSucceeds() throws Exception {
        register("eq-k2", 1000);
        addReading("eq-k2", "k2-add", 1, "r1", "2026-01-01T10:00:00Z", 100);

        CountDownLatch gate = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/certifications", """
                        {"requestId":"ck-x-%d","certifier":"certifier-%d","items":[
                          {"equipmentId":"eq-k2","readingId":"r1","expectedRevisionNo":1}]}
                        """.formatted(index, index));
            }));
        }
        gate.countDown();

        int created = 0;
        int conflict = 0;
        for (Future<MvcResult> future : futures) {
            int status = future.get(15, TimeUnit.SECONDS).getResponse().getStatus();
            if (status == 201) {
                created++;
            } else if (status == 409) {
                conflict++;
            }
        }
        assertEquals(1, created, "同一读数并发认证恰有一个成功");
        assertEquals(3, conflict, "其余并发认证应返回 409");

        // 最终数据一致：一条快照、读数已认证、版本仅前进一次
        mockMvc.perform(get("/api/equipment/eq-k2/certifications"))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/equipment/eq-k2/readings"))
                .andExpect(jsonPath("$[0].status").value("CERTIFIED"));
        mockMvc.perform(get("/api/equipment/eq-k2/status"))
                .andExpect(jsonPath("$.version").value(3));
    }

    /** 并发认证与设备退役：按事务提交顺序裁决，恰有一个生效，终态与提交顺序一致。 */
    @Test
    void concurrentCertifyVsRetire_commitOrderDecides() throws Exception {
        register("eq-k3", 1000);
        addReading("eq-k3", "k3-add", 1, "r1", "2026-01-01T10:00:00Z", 100);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> certifyFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/certifications", """
                    {"requestId":"ck-race","certifier":"bob","items":[
                      {"equipmentId":"eq-k3","readingId":"r1","expectedRevisionNo":1}]}
                    """);
        });
        Future<MvcResult> retireFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-k3/retirement", """
                    {"requestId":"rt-race","expectedVersion":2}
                    """);
        });
        gate.countDown();

        MvcResult certify = certifyFuture.get(15, TimeUnit.SECONDS);
        MvcResult retire = retireFuture.get(15, TimeUnit.SECONDS);
        int certifyStatus = certify.getResponse().getStatus();
        int retireStatus = retire.getResponse().getStatus();

        // 行锁串行化后按提交顺序裁决，恰有一个生效：
        // 退役先提交 → 认证 422（设备已退役）；认证先提交 → 退役 409（版本已前进）
        assertTrue(
                (certifyStatus == 422 && retireStatus == 201)
                        || (certifyStatus == 201 && retireStatus == 409),
                "认证与退役并发应恰有一个生效，实际：certify=" + certifyStatus
                        + ", retire=" + retireStatus);

        if (retireStatus == 201) {
            // 退役先提交：认证被拒绝，读数保持 PENDING、无快照、累计工时不变、设备已退役
            mockMvc.perform(get("/api/equipment/eq-k3/readings"))
                    .andExpect(jsonPath("$[0].status").value("PENDING"));
            mockMvc.perform(get("/api/equipment/eq-k3/certifications"))
                    .andExpect(jsonPath("$.length()").value(0));
            mockMvc.perform(get("/api/equipment/eq-k3/status"))
                    .andExpect(jsonPath("$.version").value(3))
                    .andExpect(jsonPath("$.runMinutes").value(0));
            assertTrue(jdbc.queryForObject(
                    "SELECT retired FROM equipment WHERE equipment_id = 'eq-k3'", Boolean.class),
                    "设备最终应已退役");
        } else {
            // 认证先提交：读数已认证、快照已写入，退役因版本冲突被拒、设备未退役
            mockMvc.perform(get("/api/equipment/eq-k3/readings"))
                    .andExpect(jsonPath("$[0].status").value("CERTIFIED"));
            mockMvc.perform(get("/api/equipment/eq-k3/certifications"))
                    .andExpect(jsonPath("$.length()").value(1));
            mockMvc.perform(get("/api/equipment/eq-k3/status"))
                    .andExpect(jsonPath("$.version").value(3))
                    .andExpect(jsonPath("$.runMinutes").value(100));
            assertEquals(Boolean.FALSE, jdbc.queryForObject(
                    "SELECT retired FROM equipment WHERE equipment_id = 'eq-k3'", Boolean.class),
                    "设备最终应未退役");
        }
    }

    /** 并发认证与修订：按事务提交顺序裁决，恰有一个生效，修订链与认证状态一致。 */
    @Test
    void concurrentCertifyVsRevise_commitOrderDecides() throws Exception {
        register("eq-k4", 1000);
        addReading("eq-k4", "k4-add", 1, "r1", "2026-01-01T10:00:00Z", 100);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> certifyFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/certifications", """
                    {"requestId":"ck-cr","certifier":"bob","items":[
                      {"equipmentId":"eq-k4","readingId":"r1","expectedRevisionNo":1}]}
                    """);
        });
        Future<MvcResult> reviseFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-k4/readings/r1/revisions", """
                    {"requestId":"rv-cr","expectedVersion":2,"cumulativeMinutes":150,
                     "recordedBy":"carol"}
                    """);
        });
        gate.countDown();

        MvcResult certify = certifyFuture.get(15, TimeUnit.SECONDS);
        MvcResult revise = reviseFuture.get(15, TimeUnit.SECONDS);
        int certifyStatus = certify.getResponse().getStatus();
        int reviseStatus = revise.getResponse().getStatus();

        // 恰有一个生效：修订先提交 → 认证 422（读数版本已前进）；认证先提交 → 修订 409（设备版本已前进）
        assertTrue(
                (certifyStatus == 422 && reviseStatus == 201)
                        || (certifyStatus == 201 && reviseStatus == 409),
                "认证与修订并发应恰有一个生效，实际：certify=" + certifyStatus
                        + ", revise=" + reviseStatus);

        // 版本只前进一次
        mockMvc.perform(get("/api/equipment/eq-k4/status"))
                .andExpect(jsonPath("$.version").value(3));

        if (reviseStatus == 201) {
            // 修订先生效：读数 rev2 保持 PENDING，无认证快照
            mockMvc.perform(get("/api/equipment/eq-k4/readings"))
                    .andExpect(jsonPath("$[0].revisionNo").value(2))
                    .andExpect(jsonPath("$[0].status").value("PENDING"));
            mockMvc.perform(get("/api/equipment/eq-k4/certifications"))
                    .andExpect(jsonPath("$.length()").value(0));
        } else {
            // 认证先生效：读数 rev1 已认证、快照已写入，修订链未前进
            mockMvc.perform(get("/api/equipment/eq-k4/readings"))
                    .andExpect(jsonPath("$[0].revisionNo").value(1))
                    .andExpect(jsonPath("$[0].status").value("CERTIFIED"));
            mockMvc.perform(get("/api/equipment/eq-k4/certifications"))
                    .andExpect(jsonPath("$.length()").value(1));
        }
    }
}

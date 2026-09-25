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
 * 认证并发与幂等边界测试：真实 H2 数据库上的并发写协调，
 * 录入/认证/修订/退役按事务提交顺序裁决，断言响应与最终数据一致性。
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
        jdbc.update("DELETE FROM certification_snapshot");
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
                                 "sampledAt":"%s","cumulativeMinutes":%d,"recordedBy":"rec"}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                cumulativeMinutes)))
                .andExpect(status().isCreated());
    }

    /** 并发同 certKey 同参数：全部得到原成功结果，认证效果只发生一次。 */
    @Test
    void concurrentSameCertKey_replaysSingleEffect() throws Exception {
        register("eq-cc", 1000);
        addReading("eq-cc", "cc-a1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"certKey":"cc-key","certifiedBy":"cert",
                 "items":[{"equipmentId":"eq-cc","readingId":"r1","revisionNo":1}]}
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
                    "并发同键同参应全部重放成功结果");
            bodies.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, bodies.size(), "所有并发响应应完全相同（重放同一重算结果）");

        // 认证效果只发生一次：一条快照、版本仅加一、幂等键唯一
        mockMvc.perform(get("/api/equipment/eq-cc/certifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/equipment/eq-cc/status"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(100));
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'cc-key'", Integer.class);
        assertEquals(1, keyCount, "同一 certKey 只应占一个幂等键");
    }

    /** 并发认证与修订（同一读数）：按事务提交顺序裁决，恰有一个生效，最终状态一致。 */
    @Test
    void concurrentCertifyVsRevise_exactlyOneEffective() throws Exception {
        register("eq-cr", 1000);
        addReading("eq-cr", "cr-a1", 1, "r1", "2026-01-01T10:00:00Z", 100);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> certifyFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/certifications", """
                    {"certKey":"cr-cert","certifiedBy":"cert",
                     "items":[{"equipmentId":"eq-cr","readingId":"r1","revisionNo":1}]}
                    """);
        });
        Future<MvcResult> reviseFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cr/readings/r1/revisions", """
                    {"requestId":"cr-revise","expectedVersion":2,"cumulativeMinutes":150,"recordedBy":"rec"}
                    """);
        });
        gate.countDown();

        int certifyStatus = certifyFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int reviseStatus = reviseFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();

        // 认证先提交：读数转 CERTIFIED、版本到 3，修订因版本冲突 409；
        // 修订先提交：读数修订号为 2，认证因读数版本不一致 422
        assertTrue(
                (certifyStatus == 201 && reviseStatus == 409)
                        || (certifyStatus == 422 && reviseStatus == 201),
                "认证与修订并发应恰有一个生效，实际：certify=" + certifyStatus
                        + ", revise=" + reviseStatus);

        if (certifyStatus == 201) {
            mockMvc.perform(get("/api/equipment/eq-cr/readings"))
                    .andExpect(jsonPath("$[0].certStatus").value("CERTIFIED"))
                    .andExpect(jsonPath("$[0].revisionNo").value(1));
            mockMvc.perform(get("/api/equipment/eq-cr/certifications"))
                    .andExpect(jsonPath("$.length()").value(1));
            mockMvc.perform(get("/api/equipment/eq-cr/status"))
                    .andExpect(jsonPath("$.version").value(3))
                    .andExpect(jsonPath("$.latestCumulativeMinutes").value(100));
        } else {
            mockMvc.perform(get("/api/equipment/eq-cr/readings"))
                    .andExpect(jsonPath("$[0].certStatus").value("PENDING"))
                    .andExpect(jsonPath("$[0].revisionNo").value(2))
                    .andExpect(jsonPath("$[0].cumulativeMinutes").value(150));
            mockMvc.perform(get("/api/equipment/eq-cr/certifications"))
                    .andExpect(jsonPath("$.length()").value(0));
            mockMvc.perform(get("/api/equipment/eq-cr/status"))
                    .andExpect(jsonPath("$.version").value(3))
                    .andExpect(jsonPath("$.latestCumulativeMinutes").value(0));
        }
    }

    /** 并发认证与设备退役：退役先提交则认证 422；认证先提交则两者均生效，最终状态与提交顺序一致。 */
    @Test
    void concurrentCertifyVsRetire_commitOrderDecides() throws Exception {
        register("eq-ct", 1000);
        addReading("eq-ct", "ct-a1", 1, "r1", "2026-01-01T10:00:00Z", 100);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> certifyFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/certifications", """
                    {"certKey":"ct-cert","certifiedBy":"cert",
                     "items":[{"equipmentId":"eq-ct","readingId":"r1","revisionNo":1}]}
                    """);
        });
        Future<MvcResult> retireFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-ct/retire", """
                    {"requestId":"ct-retire","expectedVersion":2}
                    """);
        });
        gate.countDown();

        int certifyStatus = certifyFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int retireStatus = retireFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();

        // 退役总能成功（先提交时版本匹配；后提交时认证不校验设备版本，退役版本期望仍匹配：
        // 认证使版本到 3，此时退役期望版本 2 会 409）——两种提交顺序分别断言
        if (certifyStatus == 422) {
            // 退役先提交：认证因设备已退役 422，退役成功
            assertEquals(200, retireStatus, "退役先提交应成功");
            MvcResult certifyAgain = postJson("/api/certifications", """
                    {"certKey":"ct-cert","certifiedBy":"cert",
                     "items":[{"equipmentId":"eq-ct","readingId":"r1","revisionNo":1}]}
                    """);
            assertEquals(422, certifyAgain.getResponse().getStatus(),
                    "失败不占键：重试仍因退役 422");
            mockMvc.perform(get("/api/equipment/eq-ct/readings"))
                    .andExpect(jsonPath("$[0].certStatus").value("PENDING"));
            mockMvc.perform(get("/api/equipment/eq-ct/certifications"))
                    .andExpect(jsonPath("$.length()").value(0));
        } else {
            // 认证先提交：认证成功；退役因设备版本已前进（期望 2，当前 3）→ 409
            assertEquals(201, certifyStatus);
            assertEquals(409, retireStatus, "认证先提交使版本前进，退役应版本冲突");
            mockMvc.perform(get("/api/equipment/eq-ct/readings"))
                    .andExpect(jsonPath("$[0].certStatus").value("CERTIFIED"));
            mockMvc.perform(get("/api/equipment/eq-ct/certifications"))
                    .andExpect(jsonPath("$.length()").value(1));
        }
        mockMvc.perform(get("/api/equipment/eq-ct/status"))
                .andExpect(jsonPath("$.version").value(3));
    }
}

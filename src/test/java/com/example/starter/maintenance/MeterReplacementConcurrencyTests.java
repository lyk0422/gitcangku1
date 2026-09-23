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
 * 工时表更换并发边界测试：更换与新读数/旧表修订/再次更换并发按设备行锁串行提交，
 * 不允许半重算；同 replacementKey 并发重放同一完整链快照（真实 H2 内存库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MeterReplacementConcurrencyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM meter_chain_recompute");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM meter");
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

    private void seedOneReadingEquipment(String equipmentId) throws Exception {
        MvcResult registered = postJson("/api/equipment", """
                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":60000}
                """.formatted(equipmentId, equipmentId));
        assertEquals(201, registered.getResponse().getStatus());
        postJson("/api/equipment/%s/readings".formatted(equipmentId), """
                {"requestId":"seed-%s","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","rawHours":100}
                """.formatted(equipmentId));
    }

    /** 更换与旧表最后读数修订并发（同一期望版本）：恰一个成功，链条状态与胜出者一致，无半重算。 */
    @Test
    void concurrentReplacementVsClosedMeterRevision_serializedNoHalfRecompute() throws Exception {
        seedOneReadingEquipment("eq-rc");
        // 第二条读数 r2=200，使旧表最后有效读数为 r2（版本 3）
        postJson("/api/equipment/eq-rc/readings", """
                {"requestId":"seed-r2","expectedVersion":2,"readingId":"r2",
                 "sampledAt":"2026-01-01T11:00:00Z","rawHours":200}
                """);

        CountDownLatch gate = new CountDownLatch(1);
        String replaceBody = """
                {"replacementKey":"rep-race","expectedVersion":3,"newMeterKey":"m2",
                 "finalRawHours":210,"initialRawHours":1000,"lastReadingRevisionNo":1}
                """;
        String reviseBody = """
                {"requestId":"rev-race","expectedVersion":3,"rawHours":190}
                """;
        Future<MvcResult> replaceFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-rc/meters/replacements", replaceBody);
        });
        Future<MvcResult> reviseFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-rc/readings/r2/revisions", reviseBody);
        });
        gate.countDown();

        MvcResult replace = replaceFuture.get(15, TimeUnit.SECONDS);
        MvcResult revise = reviseFuture.get(15, TimeUnit.SECONDS);
        int replaceStatus = replace.getResponse().getStatus();
        int reviseStatus = revise.getResponse().getStatus();

        assertTrue(
                (replaceStatus == 201 && reviseStatus == 409)
                        || (replaceStatus == 409 && reviseStatus == 201),
                "更换与旧表修订并发应恰有一个成功，实际：replace=" + replaceStatus
                        + ", revise=" + reviseStatus);

        // 版本只前进一次
        mockMvc.perform(get("/api/equipment/eq-rc/status"))
                .andExpect(jsonPath("$.version").value(4));

        if (replaceStatus == 201) {
            // 更换先提交：新表按最后读数 200 冻结偏移；修订因版本过期失败，r2 仍为 200/修订号 1
            mockMvc.perform(get("/api/equipment/eq-rc/meters"))
                    .andExpect(jsonPath("$.activeMeterKey").value("m2"))
                    .andExpect(jsonPath("$.meters[1].offsetHours").value(200))
                    .andExpect(jsonPath("$.meters[1].recalcVersion").value(1))
                    .andExpect(jsonPath("$.recomputes.length()").value(0));
            java.math.BigDecimal raw = jdbc.queryForObject(
                    "SELECT raw_hours FROM reading WHERE equipment_id = 'eq-rc' AND reading_id = 'r2'",
                    java.math.BigDecimal.class);
            assertEquals(0, raw.compareTo(new java.math.BigDecimal("200.000000")));
            Integer revisionNo = jdbc.queryForObject(
                    "SELECT revision_no FROM reading WHERE equipment_id = 'eq-rc' AND reading_id = 'r2'",
                    Integer.class);
            assertEquals(1, revisionNo);
        } else {
            // 修订先提交：更换因版本过期失败，仍只有初始 ACTIVE 表，r2=190
            mockMvc.perform(get("/api/equipment/eq-rc/meters"))
                    .andExpect(jsonPath("$.activeMeterKey").value("eq-rc"))
                    .andExpect(jsonPath("$.meters.length()").value(1))
                    .andExpect(jsonPath("$.recomputes.length()").value(0));
            java.math.BigDecimal raw = jdbc.queryForObject(
                    "SELECT raw_hours FROM reading WHERE equipment_id = 'eq-rc' AND reading_id = 'r2'",
                    java.math.BigDecimal.class);
            assertEquals(0, raw.compareTo(new java.math.BigDecimal("190.000000")));
        }
    }

    /** 同 replacementKey 同参并发：全部重放首次完整链快照，更换只发生一次。 */
    @Test
    void concurrentSameReplacementKey_singleChainAndIdenticalSnapshot() throws Exception {
        seedOneReadingEquipment("eq-crp");
        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"replacementKey":"rep-same","expectedVersion":2,"newMeterKey":"mc2",
                 "finalRawHours":120,"initialRawHours":500,"lastReadingRevisionNo":1}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-crp/meters/replacements", body);
            }));
        }
        gate.countDown();

        Set<String> snapshots = new HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(), "并发同键同参应全部返回首次快照");
            snapshots.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, snapshots.size(), "所有并发更换响应应为同一完整链快照");

        mockMvc.perform(get("/api/equipment/eq-crp/meters"))
                .andExpect(jsonPath("$.meters.length()").value(2))
                .andExpect(jsonPath("$.activeMeterKey").value("mc2"));
        mockMvc.perform(get("/api/equipment/eq-crp/status"))
                .andExpect(jsonPath("$.version").value(3));
        Integer meterCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM meter WHERE equipment_id = 'eq-crp'", Integer.class);
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'rep-same'", Integer.class);
        assertEquals(2, meterCount, "更换只能落一条新表");
        assertEquals(1, idemCount, "同一 replacementKey 只占一个幂等键");
    }

    /** 两次不同 replacementKey 的更换并发（同期望版本）：恰一个成功，另一个 409，始终只有一张 ACTIVE。 */
    @Test
    void concurrentDistinctReplacements_singleActiveMeter() throws Exception {
        seedOneReadingEquipment("eq-crp2");
        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> f1 = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-crp2/meters/replacements", """
                    {"replacementKey":"rep-x1","expectedVersion":2,"newMeterKey":"mx1",
                     "finalRawHours":110,"initialRawHours":0}
                    """);
        });
        Future<MvcResult> f2 = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-crp2/meters/replacements", """
                    {"replacementKey":"rep-x2","expectedVersion":2,"newMeterKey":"mx2",
                     "finalRawHours":110,"initialRawHours":0}
                    """);
        });
        gate.countDown();

        int s1 = f1.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int s2 = f2.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        assertTrue(
                (s1 == 201 && s2 == 409) || (s1 == 409 && s2 == 201),
                "两次更换并发应恰有一个成功，实际 s1=" + s1 + ", s2=" + s2);

        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM meter WHERE equipment_id = 'eq-crp2' AND status = 'ACTIVE'",
                Integer.class);
        Integer closedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM meter WHERE equipment_id = 'eq-crp2' AND status = 'CLOSED'",
                Integer.class);
        assertEquals(1, activeCount, "同设备任意时刻只能有一张 ACTIVE 表");
        assertEquals(1, closedCount, "只能关闭旧表一次");
        mockMvc.perform(get("/api/equipment/eq-crp2/status"))
                .andExpect(jsonPath("$.version").value(3));
    }

    /** 更换与新读数并发（同期望版本）：恰一个成功；新读数不会落到 CLOSED 表或造成半重算。 */
    @Test
    void concurrentReplacementVsNewReading() throws Exception {
        seedOneReadingEquipment("eq-crn");
        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> replaceFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-crn/meters/replacements", """
                    {"replacementKey":"rep-n","expectedVersion":2,"newMeterKey":"mn2",
                     "finalRawHours":110,"initialRawHours":1000,"lastReadingRevisionNo":1}
                    """);
        });
        Future<MvcResult> readingFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-crn/readings", """
                    {"requestId":"add-n","expectedVersion":2,"readingId":"r2",
                     "sampledAt":"2026-01-01T12:00:00Z","rawHours":105}
                    """);
        });
        gate.countDown();

        int replaceStatus = replaceFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int readingStatus = readingFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        assertTrue(
                (replaceStatus == 201 && readingStatus == 409)
                        || (replaceStatus == 409 && readingStatus == 201),
                "更换与新读数并发应恰有一个成功，实际 replace=" + replaceStatus
                        + ", reading=" + readingStatus);

        mockMvc.perform(get("/api/equipment/eq-crn/status"))
                .andExpect(jsonPath("$.version").value(3));
        // 新读数绝不会写在 CLOSED 表上
        Integer closedReadings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading r JOIN meter m"
                        + " ON r.equipment_id = m.equipment_id AND r.meter_key = m.meter_key"
                        + " WHERE r.equipment_id = 'eq-crn' AND m.status = 'CLOSED'"
                        + " AND r.reading_id = 'r2'", Integer.class);
        assertEquals(0, closedReadings, "新读数不得登记到已关闭工时表");
    }
}

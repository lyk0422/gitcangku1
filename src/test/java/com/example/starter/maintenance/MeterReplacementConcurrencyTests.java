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

import com.jayway.jsonpath.JsonPath;

/**
 * 工时表更换链并发边界测试：更换与新读数、保养登记、旧表修订并发按提交顺序串行，
 * 不出现半重算；同 requestId 并发重放首次链快照。真实 H2 数据库协调真实并发。
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
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM meter_replacement");
        jdbc.update("DELETE FROM meter");
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

    private long currentVersion(String equipmentId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/equipment/" + equipmentId + "/status"))
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.version"))
                .longValue();
    }

    /** 并发更换与新读数（同一期望版本）：恰有一个先成功，另一个按最新版本重试后落到正确表。 */
    @Test
    void concurrentReplaceVsReading_serializedConsistentChain() throws Exception {
        register("eq-cr", 10000);
        postJson("/api/equipment/eq-cr/readings", """
                {"requestId":"cr-a1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                """);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> replaceFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cr/meter-replacements", """
                    {"requestId":"cr-rep","expectedVersion":2,"replacementKey":"rk-c1",
                     "newMeterKey":"m-B","oldLastReadingVersion":1,"finalRawHours":100,
                     "initialRawHours":0}
                    """);
        });
        Future<MvcResult> readingFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cr/readings", """
                    {"requestId":"cr-a2","expectedVersion":2,"readingId":"r2",
                     "sampledAt":"2026-01-01T11:00:00Z","cumulativeMinutes":200}
                    """);
        });
        gate.countDown();

        MvcResult replace = replaceFuture.get(15, TimeUnit.SECONDS);
        MvcResult reading = readingFuture.get(15, TimeUnit.SECONDS);
        int replaceStatus = replace.getResponse().getStatus();
        int readingStatus = reading.getResponse().getStatus();
        assertTrue((replaceStatus == 201 && readingStatus == 409)
                        || (replaceStatus == 409 && readingStatus == 201),
                "更换与读数并发应恰有一个先成功，实际：replace=" + replaceStatus
                        + ", reading=" + readingStatus);

        // 落败方按最新版本重试：读数重试落到新表；更换重试按当前最后有效读数重新申报
        long version = currentVersion("eq-cr");
        if (replaceStatus == 409) {
            // 读数 r2=200 已落到旧表，重新申报 final=200
            MvcResult retry = postJson("/api/equipment/eq-cr/meter-replacements", """
                    {"requestId":"cr-rep","expectedVersion":%d,"replacementKey":"rk-c1",
                     "newMeterKey":"m-B","oldLastReadingVersion":1,"finalRawHours":200,
                     "initialRawHours":0}
                    """.formatted(version));
            assertEquals(201, retry.getResponse().getStatus(), "版本冲突失败不占键，修正后应成功");
        } else {
            MvcResult retry = postJson("/api/equipment/eq-cr/readings", """
                    {"requestId":"cr-a2","expectedVersion":%d,"readingId":"r2",
                     "sampledAt":"2026-01-01T11:00:00Z","cumulativeMinutes":200}
                    """.formatted(version));
            assertEquals(201, retry.getResponse().getStatus());
        }

        // 最终状态一致：两张表、一条更换记录、两条读数、版本 4、仅一张 ACTIVE
        mockMvc.perform(get("/api/equipment/eq-cr/meter-chain"))
                .andExpect(jsonPath("$.equipmentVersion").value(4))
                .andExpect(jsonPath("$.meters.length()").value(2))
                .andExpect(jsonPath("$.replacements.length()").value(1))
                .andExpect(jsonPath("$.meters[0].status").value("CLOSED"))
                .andExpect(jsonPath("$.meters[1].status").value("ACTIVE"));
        mockMvc.perform(get("/api/equipment/eq-cr/readings"))
                .andExpect(jsonPath("$.length()").value(2));

        // 无半更换：offset 与旧表最后有效读数满足链式连续公式
        MvcResult chain = mockMvc.perform(get("/api/equipment/eq-cr/meter-chain")).andReturn();
        String body = chain.getResponse().getContentAsString();
        long lastVirtual = ((Number) JsonPath.read(body, "$.meters[0].lastVirtualHours")).longValue();
        long offset = ((Number) JsonPath.read(body, "$.meters[1].offsetHours")).longValue();
        long initialRaw = ((Number) JsonPath.read(body, "$.meters[1].initialRawHours")).longValue();
        assertEquals(lastVirtual - initialRaw, offset, "冻结 offset 必须等于前驱最后有效虚拟工时减去新表起始读数");
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM meter WHERE equipment_id = 'eq-cr' AND status = 'ACTIVE'",
                Integer.class);
        assertEquals(1, activeCount);
    }

    /** 并发旧表修订（触发全链重算）与保养登记：恰有一个先成功，重试后锚点虚拟工时与重算结果一致。 */
    @Test
    void concurrentRevisionRecomputeVsMaintenance_noHalfRecalc() throws Exception {
        register("eq-cm", 1000);
        postJson("/api/equipment/eq-cm/readings", """
                {"requestId":"cm-a1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                """);
        postJson("/api/equipment/eq-cm/readings", """
                {"requestId":"cm-a2","expectedVersion":2,"readingId":"r2",
                 "sampledAt":"2026-01-01T11:00:00Z","cumulativeMinutes":200}
                """);
        // 更换到 m-B：offset = 200 - 50 = 150
        postJson("/api/equipment/eq-cm/meter-replacements", """
                {"requestId":"cm-rep","expectedVersion":3,"replacementKey":"rk-c1",
                 "newMeterKey":"m-B","oldLastReadingVersion":1,"finalRawHours":200,
                 "initialRawHours":50}
                """);
        postJson("/api/equipment/eq-cm/readings", """
                {"requestId":"cm-a3","expectedVersion":4,"readingId":"r3",
                 "sampledAt":"2026-01-01T12:00:00Z","cumulativeMinutes":80}
                """);

        CountDownLatch gate = new CountDownLatch(1);
        // 修订旧表最后有效读数 200 → 150，触发重算：offset(m-B) 150 → 100
        Future<MvcResult> reviseFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cm/readings/r2/revisions", """
                    {"requestId":"cm-rv","expectedVersion":5,"cumulativeMinutes":150}
                    """);
        });
        // 保养锚定 r3（m-B，raw=80）
        Future<MvcResult> maintenanceFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cm/maintenances", """
                    {"requestId":"cm-mn","expectedVersion":5,"readingId":"r3","anchorRevisionNo":1}
                    """);
        });
        gate.countDown();

        MvcResult revise = reviseFuture.get(15, TimeUnit.SECONDS);
        MvcResult maintenance = maintenanceFuture.get(15, TimeUnit.SECONDS);
        int reviseStatus = revise.getResponse().getStatus();
        int maintenanceStatus = maintenance.getResponse().getStatus();
        assertTrue((reviseStatus == 201 && maintenanceStatus == 409)
                        || (reviseStatus == 409 && maintenanceStatus == 201),
                "修订与保养并发应恰有一个先成功，实际：revise=" + reviseStatus
                        + ", maintenance=" + maintenanceStatus);

        // 落败方按最新版本重试，两操作最终都生效
        long version = currentVersion("eq-cm");
        if (reviseStatus == 409) {
            MvcResult retry = postJson("/api/equipment/eq-cm/readings/r2/revisions", """
                    {"requestId":"cm-rv","expectedVersion":%d,"cumulativeMinutes":150}
                    """.formatted(version));
            assertEquals(201, retry.getResponse().getStatus());
        } else {
            MvcResult retry = postJson("/api/equipment/eq-cm/maintenances", """
                    {"requestId":"cm-mn","expectedVersion":%d,"readingId":"r3","anchorRevisionNo":1}
                    """.formatted(version));
            assertEquals(201, retry.getResponse().getStatus());
        }

        // 无半重算：无论提交顺序如何，最终锚点虚拟工时 = 原始快照 + 重算后 offset = 80 + 100
        mockMvc.perform(get("/api/equipment/eq-cm/meter-chain"))
                .andExpect(jsonPath("$.equipmentVersion").value(7))
                .andExpect(jsonPath("$.recalcVersion").value(1))
                .andExpect(jsonPath("$.meters[1].offsetHours").value(100))
                .andExpect(jsonPath("$.meters[1].lastVirtualHours").value(180));
        mockMvc.perform(get("/api/equipment/eq-cm/maintenances"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].anchorCumulativeMinutes").value(80))
                .andExpect(jsonPath("$[0].anchorVirtualHours").value(180));

        Long offset = jdbc.queryForObject(
                "SELECT offset_hours FROM meter WHERE equipment_id = 'eq-cm' AND meter_key = 'm-B'",
                Long.class);
        Long anchorVirtual = jdbc.queryForObject(
                "SELECT anchor_virtual_hours FROM maintenance WHERE equipment_id = 'eq-cm'",
                Long.class);
        assertEquals(100L, offset);
        assertEquals(180L, anchorVirtual, "锚点虚拟工时必须与重算后 offset 一致，不允许半重算");
    }

    /** 并发同 requestId 同参数更换：全部重放首次链快照，业务效果只发生一次。 */
    @Test
    void concurrentSameRequestIdReplacement_replaysSingleSnapshot() throws Exception {
        register("eq-ci", 1000);
        postJson("/api/equipment/eq-ci/readings", """
                {"requestId":"ci-a1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                """);

        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"ci-rep","expectedVersion":2,"replacementKey":"rk-ci",
                 "newMeterKey":"m-B","oldLastReadingVersion":1,"finalRawHours":100,
                 "initialRawHours":0}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-ci/meter-replacements", body);
            }));
        }
        gate.countDown();

        Set<String> bodies = new HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(),
                    "并发同键同参应全部重放首次链快照");
            bodies.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, bodies.size(), "所有并发响应应完全相同（重放同一链快照）");

        // 业务效果只发生一次：两张表、一条更换记录、版本仅加一、幂等键仅一条
        mockMvc.perform(get("/api/equipment/eq-ci/meter-chain"))
                .andExpect(jsonPath("$.equipmentVersion").value(3))
                .andExpect(jsonPath("$.meters.length()").value(2))
                .andExpect(jsonPath("$.replacements.length()").value(1));
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'ci-rep'", Integer.class);
        assertEquals(1, idemCount);
        Integer replacementCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM meter_replacement WHERE replacement_key = 'rk-ci'", Integer.class);
        assertEquals(1, replacementCount);
    }
}

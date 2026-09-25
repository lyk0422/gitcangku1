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
 * 停机区间并发与幂等边界测试：并发按提交顺序裁决，同设备不得出现重叠生效区间。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DowntimeConcurrencyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM downtime");
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
                            String readingId, String sampledAt, long cumulativeMinutes)
            throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                cumulativeMinutes)))
                .andExpect(status().isCreated());
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private long currentVersion(String equipmentId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/equipment/" + equipmentId + "/status"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"version\":(\\d+).*", "$1"));
    }

    /** 并发登记重叠停机区间：恰有一个生效，重试后另一个必为 422 重叠冲突。 */
    @Test
    void concurrentOverlappingDowntime_exactlyOneActive() throws Exception {
        register("eq-dtc1", 10000);
        addReading("eq-dtc1", "c1-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("eq-dtc1", "c1-r2", 2, "r2", "2026-01-01T11:00:00Z", 200);
        addReading("eq-dtc1", "c1-r3", 3, "r3", "2026-01-01T12:00:00Z", 300);

        CountDownLatch gate = new CountDownLatch(1);
        // 两个重叠区间：[10:00,11:00] 与 [10:30,11:30]，版本冲突时按最新版本重试
        Future<Integer> first = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            for (int attempt = 0; attempt < 20; attempt++) {
                long version = currentVersion("eq-dtc1");
                MvcResult result = postJson("/api/equipment/eq-dtc1/downtimes", """
                        {"requestId":"c1-dtx","expectedVersion":%d,"downtimeKey":"dt-x",
                         "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:00:00Z",
                         "reason":"区间X"}
                        """.formatted(version));
                int code = result.getResponse().getStatus();
                if (code != 409) {
                    return code;
                }
            }
            throw new IllegalStateException("重试次数耗尽");
        });
        Future<Integer> second = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            for (int attempt = 0; attempt < 20; attempt++) {
                long version = currentVersion("eq-dtc1");
                MvcResult result = postJson("/api/equipment/eq-dtc1/downtimes", """
                        {"requestId":"c1-dty","expectedVersion":%d,"downtimeKey":"dt-y",
                         "startAt":"2026-01-01T10:30:00Z","endAt":"2026-01-01T11:30:00Z",
                         "reason":"区间Y"}
                        """.formatted(version));
                int code = result.getResponse().getStatus();
                if (code != 409) {
                    return code;
                }
            }
            throw new IllegalStateException("重试次数耗尽");
        });
        gate.countDown();

        int firstCode = first.get(30, TimeUnit.SECONDS);
        int secondCode = second.get(30, TimeUnit.SECONDS);

        // 恰有一个登记成功（201），另一个最终因重叠被拒（422）
        assertTrue((firstCode == 201 && secondCode == 422)
                        || (firstCode == 422 && secondCode == 201),
                "并发重叠区间应恰有一个生效，实际：first=" + firstCode + ", second=" + secondCode);

        // 最终数据：仅一条生效区间，版本只前进一次
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM downtime WHERE equipment_id = 'eq-dtc1' AND status = 'ACTIVE'",
                Integer.class);
        assertEquals(1, activeCount, "同一设备不得出现重叠生效区间");
        mockMvc.perform(get("/api/equipment/eq-dtc1/status"))
                .andExpect(jsonPath("$.version").value(5));
    }

    /** 并发同 requestId 同参数登记停机：全部重放首次结果，业务效果只发生一次。 */
    @Test
    void concurrentSameRequestId_replaysSingleEffect() throws Exception {
        register("eq-dtc2", 10000);
        addReading("eq-dtc2", "c2-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("eq-dtc2", "c2-r2", 2, "r2", "2026-01-01T11:00:00Z", 200);

        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"c2-idem","expectedVersion":3,"downtimeKey":"dt-same",
                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:00:00Z",
                 "reason":"并发同键"}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-dtc2/downtimes", body);
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

        // 业务效果只发生一次：一条停机区间、版本仅加一、幂等键唯一
        mockMvc.perform(get("/api/equipment/eq-dtc2/downtimes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].deductionMinutes").value(100));
        mockMvc.perform(get("/api/equipment/eq-dtc2/status"))
                .andExpect(jsonPath("$.version").value(4));
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'c2-idem'",
                Integer.class);
        assertEquals(1, idemCount, "同一 requestId 只应占一个幂等键");
    }

    /** 并发登记与撤销同一区间：恰有一个成功，最终状态一致（生效或已撤销）。 */
    @Test
    void concurrentRegisterAndRevoke_consistentFinalState() throws Exception {
        register("eq-dtc3", 10000);
        addReading("eq-dtc3", "c3-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("eq-dtc3", "c3-r2", 2, "r2", "2026-01-01T11:00:00Z", 200);
        addReading("eq-dtc3", "c3-r3", 3, "r3", "2026-01-01T12:00:00Z", 300);
        postJson("/api/equipment/eq-dtc3/downtimes", """
                {"requestId":"c3-dt","expectedVersion":4,"downtimeKey":"dt-1",
                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:00:00Z",
                 "reason":"待撤销"}
                """);

        CountDownLatch gate = new CountDownLatch(1);
        // 撤销与新增读数并发（同一期望版本 5）：恰有一个成功
        Future<Integer> revoke = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-dtc3/downtimes/dt-1/revoke", """
                    {"requestId":"c3-revoke","expectedVersion":5}
                    """).getResponse().getStatus();
        });
        Future<Integer> reading = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-dtc3/readings", """
                    {"requestId":"c3-r4","expectedVersion":5,"readingId":"r4",
                     "sampledAt":"2026-01-01T10:30:00Z","cumulativeMinutes":150}
                    """).getResponse().getStatus();
        });
        gate.countDown();

        int revokeCode = revoke.get(15, TimeUnit.SECONDS);
        int readingCode = reading.get(15, TimeUnit.SECONDS);
        assertTrue((revokeCode == 200 && readingCode == 409)
                        || (revokeCode == 409 && readingCode == 201),
                "撤销与新增读数并发应恰有一个成功，实际：revoke=" + revokeCode
                        + ", reading=" + readingCode);

        // 版本只前进一次；若撤销成功则扣减退出计算
        mockMvc.perform(get("/api/equipment/eq-dtc3/status"))
                .andExpect(jsonPath("$.version").value(6));
        if (revokeCode == 200) {
            mockMvc.perform(get("/api/equipment/eq-dtc3/status"))
                    .andExpect(jsonPath("$.downtimeDeductionMinutes").value(0));
            mockMvc.perform(get("/api/equipment/eq-dtc3/downtimes"))
                    .andExpect(jsonPath("$[0].status").value("REVOKED"));
        } else {
            mockMvc.perform(get("/api/equipment/eq-dtc3/downtimes"))
                    .andExpect(jsonPath("$[0].status").value("ACTIVE"));
        }
    }
}

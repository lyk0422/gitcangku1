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
 * 停机区间并发与幂等边界测试：真实 H2 数据库上的并发写协调，断言响应与最终数据一致性。
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

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
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

    /** 并发同 requestId 同参数登记停机：全部重放首次结果，业务效果只发生一次。 */
    @Test
    void concurrentSameRequestId_replaysSingleEffect() throws Exception {
        register("eq-cdt", 1000);
        addReading("eq-cdt", "cdt-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("eq-cdt", "cdt-r2", 2, "r2", "2026-01-01T12:00:00Z", 300);

        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"cdt-1","expectedVersion":3,"downtimeKey":"dk-c1",
                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T12:00:00Z",
                 "reason":"检修"}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-cdt/downtimes", body);
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

        // 业务效果只发生一次：一条停机记录、版本仅加一
        mockMvc.perform(get("/api/equipment/eq-cdt/downtimes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].deductionMinutes").value(200));
        mockMvc.perform(get("/api/equipment/eq-cdt/status"))
                .andExpect(jsonPath("$.version").value(4));
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'cdt-1'", Integer.class);
        assertEquals(1, idemCount, "同一 requestId 只应占一个幂等键");
    }

    /** 并发登记相互重叠的停机区间：按提交顺序裁决，恰有一个生效，重试另一请求得到 422 重叠。 */
    @Test
    void concurrentOverlappingDowntimes_exactlyOneActive() throws Exception {
        register("eq-cov", 1000);
        addReading("eq-cov", "cov-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("eq-cov", "cov-r2", 2, "r2", "2026-01-01T12:00:00Z", 300);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> first = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cov/downtimes", """
                    {"requestId":"cov-1","expectedVersion":3,"downtimeKey":"dk-x",
                     "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T12:00:00Z",
                     "reason":"检修"}
                    """);
        });
        Future<MvcResult> second = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cov/downtimes", """
                    {"requestId":"cov-2","expectedVersion":3,"downtimeKey":"dk-y",
                     "startAt":"2026-01-01T10:30:00Z","endAt":"2026-01-01T12:00:00Z",
                     "reason":"检修"}
                    """);
        });
        gate.countDown();

        int firstStatus = first.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int secondStatus = second.get(15, TimeUnit.SECONDS).getResponse().getStatus();

        // 同一期望版本并发：恰有一个成功（201），另一个版本冲突（409）
        assertTrue(
                (firstStatus == 201 && secondStatus == 409)
                        || (firstStatus == 409 && secondStatus == 201),
                "并发重叠登记应恰有一个成功，实际：first=" + firstStatus + ", second=" + secondStatus);

        // 仅一条生效区间，版本只前进一次
        mockMvc.perform(get("/api/equipment/eq-cov/downtimes"))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/equipment/eq-cov/status"))
                .andExpect(jsonPath("$.version").value(4));

        // 落败请求按最新版本重试：与已生效区间重叠 → 422，不得出现重叠生效区间
        String loserKey = firstStatus == 201 ? "dk-y" : "dk-x";
        String loserRequest = firstStatus == 201 ? "cov-2" : "cov-1";
        String loserBody = """
                {"requestId":"%s","expectedVersion":4,"downtimeKey":"%s",
                 "startAt":"2026-01-01T10:30:00Z","endAt":"2026-01-01T12:00:00Z",
                 "reason":"检修"}
                """.formatted(loserRequest, loserKey);
        // dk-x 区间为 [10:00,12:00)，dk-y 区间为 [10:30,12:00)，二者均与胜者重叠
        String body = loserKey.equals("dk-y") ? loserBody : """
                {"requestId":"%s","expectedVersion":4,"downtimeKey":"%s",
                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T12:00:00Z",
                 "reason":"检修"}
                """.formatted(loserRequest, loserKey);
        mockMvc.perform(post("/api/equipment/eq-cov/downtimes")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DOWNTIME_OVERLAP"));
        mockMvc.perform(get("/api/equipment/eq-cov/downtimes"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    /** 并发不同 downtimeKey 同键冲突：全局唯一约束下恰有一个成功，最终仅一条记录。 */
    @Test
    void concurrentSameDowntimeKey_singleRecord() throws Exception {
        register("eq-ckey", 1000);
        addReading("eq-ckey", "ck-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("eq-ckey", "ck-r2", 2, "r2", "2026-01-01T12:00:00Z", 300);

        int threads = 4;
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-ckey/downtimes", """
                        {"requestId":"ck-%d","expectedVersion":3,"downtimeKey":"dk-same",
                         "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T12:00:00Z",
                         "reason":"检修"}
                        """.formatted(index));
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
        assertEquals(1, created, "同一 downtimeKey 并发登记应恰有一个成功");
        assertEquals(threads - 1, conflict, "其余并发请求应全部冲突");

        mockMvc.perform(get("/api/equipment/eq-ckey/downtimes"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].downtimeKey").value("dk-same"));
        mockMvc.perform(get("/api/equipment/eq-ckey/status"))
                .andExpect(jsonPath("$.version").value(4));
    }
}

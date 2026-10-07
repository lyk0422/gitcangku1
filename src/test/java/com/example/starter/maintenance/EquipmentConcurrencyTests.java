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
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM equipment_calendar");
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
                    {"requestId":"race-revise","expectedVersion":3,"cumulativeMinutes":150}
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

    /** 并发不同 requestId 新增不同读数：串行化后均按版本推进，无丢失更新。 */
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

    /** 并发启用日历（同一期望版本、不同 requestId）：恰有一个成功，档案仅一条，版本仅推进一次。 */
    @Test
    void concurrentEnableCalendar_singleSuccess() throws Exception {
        register("eq-ccal", 1000);
        int threads = 4;
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-ccal/calendar", """
                        {"requestId":"ccal-%d","expectedVersion":1,"operator":"op-%d",
                         "commissionedAt":"2026-01-01T00:00:00Z","periodDays":30}
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
            } else {
                assertEquals(409, status, "并发启用日历失败者应返回 409");
                conflict++;
            }
        }
        assertEquals(1, created, "并发启用日历应恰有一个成功");
        assertEquals(threads - 1, conflict);

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM equipment_calendar WHERE equipment_id = 'eq-ccal'",
                Integer.class);
        assertEquals(1, rows, "日历档案只应启用一次");
        mockMvc.perform(get("/api/equipment/eq-ccal/status"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.calendarEnabled").value(true))
                .andExpect(jsonPath("$.calendarPeriodDays").value(30));
    }

    /** 日历启用与新增读数共享设备版本：并发同版本写恰有一个成功，版本只前进一次。 */
    @Test
    void concurrentEnableCalendarVsAddReading_sharedVersion() throws Exception {
        register("eq-cmix", 1000);
        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> calendarFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cmix/calendar", """
                    {"requestId":"cmix-cal","expectedVersion":1,"operator":"op",
                     "commissionedAt":"2026-01-01T00:00:00Z","periodDays":30}
                    """);
        });
        Future<MvcResult> readingFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cmix/readings", """
                    {"requestId":"cmix-r1","expectedVersion":1,"readingId":"r1",
                     "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                    """);
        });
        gate.countDown();

        int calendarStatus = calendarFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int readingStatus = readingFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        assertTrue(
                (calendarStatus == 201 && readingStatus == 409)
                        || (calendarStatus == 409 && readingStatus == 201),
                "日历启用与读数写入并发应恰有一个成功，实际：calendar=" + calendarStatus
                        + ", reading=" + readingStatus);
        mockMvc.perform(get("/api/equipment/eq-cmix/status"))
                .andExpect(jsonPath("$.version").value(2));
    }
}

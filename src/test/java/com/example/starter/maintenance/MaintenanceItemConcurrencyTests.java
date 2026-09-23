package com.example.starter.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
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
 * 多保养项目并发边界测试（真实 H2 内存库）：
 * 项目新增、读数新增与任一项目保养竞争同一个设备版本，只允许匹配 expectedVersion 者成功。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MaintenanceItemConcurrencyTests {

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
        jdbc.update("DELETE FROM maintenance_item");
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

    /** 两个不同项目以同一期望版本并发完成保养锚定同一读数：恰有一个成功，版本只加一。 */
    @Test
    void concurrentMaintenanceOnDifferentItems_singleWinner() throws Exception {
        postJson("/api/equipment", """
                {"requestId":"reg-cmi","equipmentId":"eq-cmi","maintenancePeriodMinutes":1000}
                """);
        postJson("/api/equipment/eq-cmi/readings", """
                {"requestId":"cmi-r1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                """);
        postJson("/api/equipment/eq-cmi/items", """
                {"requestId":"cmi-oil","expectedVersion":2,"itemCode":"OIL","maintenancePeriodMinutes":500}
                """);
        // 当前版本 3

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> defaultFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cmi/maintenances", """
                    {"requestId":"cmi-m-def","expectedVersion":3,
                     "readingId":"r1","anchorRevisionNo":1}
                    """);
        });
        Future<MvcResult> oilFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cmi/maintenances", """
                    {"requestId":"cmi-m-oil","expectedVersion":3,"itemCode":"OIL",
                     "readingId":"r1","anchorRevisionNo":1}
                    """);
        });
        gate.countDown();

        MvcResult defaultResult = defaultFuture.get(15, TimeUnit.SECONDS);
        MvcResult oilResult = oilFuture.get(15, TimeUnit.SECONDS);
        int defaultStatus = defaultResult.getResponse().getStatus();
        int oilStatus = oilResult.getResponse().getStatus();

        assertTrue(
                (defaultStatus == 201 && oilStatus == 409)
                        || (defaultStatus == 409 && oilStatus == 201),
                "不同项目并发同期望版本应恰有一个成功，实际：DEFAULT=" + defaultStatus
                        + ", OIL=" + oilStatus);

        // 版本只前进一次
        mockMvc.perform(get("/api/equipment/eq-cmi/status"))
                .andExpect(jsonPath("$.version").value(4));

        // 只有一个项目产生了保养记录，另一项目为空；读数控件锚定状态不变
        Integer maintenanceCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance WHERE equipment_id = 'eq-cmi'", Integer.class);
        assertEquals(1, maintenanceCount, "竞争失败者不得落库保养记录");
    }

    /** 新增项目与新增读数并发竞争同一版本：恰有一个成功，失败方按新版本重试后两者均生效。 */
    @Test
    void concurrentAddItemVsAddReading_retryBothSucceed() throws Exception {
        postJson("/api/equipment", """
                {"requestId":"reg-cir","equipmentId":"eq-cir","maintenancePeriodMinutes":1000}
                """);

        CountDownLatch gate = new CountDownLatch(1);
        List<Future<RaceResult>> futures = new ArrayList<>();
        futures.add(executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return new RaceResult("item", postJson("/api/equipment/eq-cir/items", """
                    {"requestId":"cir-item","expectedVersion":1,"itemCode":"OIL",
                     "maintenancePeriodMinutes":500}
                    """).getResponse().getStatus());
        }));
        futures.add(executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return new RaceResult("reading", postJson("/api/equipment/eq-cir/readings", """
                    {"requestId":"cir-reading","expectedVersion":1,"readingId":"r1",
                     "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                    """).getResponse().getStatus());
        }));
        gate.countDown();

        List<RaceResult> results = new ArrayList<>();
        for (Future<RaceResult> future : futures) {
            results.add(future.get(15, TimeUnit.SECONDS));
        }
        long success = results.stream().filter(r -> r.status() == 201).count();
        long conflict = results.stream().filter(r -> r.status() == 409).count();
        assertEquals(1, success, "同期望版本并发只应有一个操作成功，实际：" + results);
        assertEquals(1, conflict, "另一个操作应为版本冲突，实际：" + results);

        // 仅失败方以新版本、同 requestId 重试：失败不占键，重试成功，最终两个变更都生效
        String loser = results.stream().filter(r -> r.status() == 409).findFirst().orElseThrow().name();
        MvcResult retry = "item".equals(loser)
                ? postJson("/api/equipment/eq-cir/items", """
                        {"requestId":"cir-item","expectedVersion":2,"itemCode":"OIL",
                         "maintenancePeriodMinutes":500}
                        """)
                : postJson("/api/equipment/eq-cir/readings", """
                        {"requestId":"cir-reading","expectedVersion":2,"readingId":"r1",
                         "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                        """);
        assertEquals(201, retry.getResponse().getStatus(), "版本冲突失败不占键，按新版本重试应成功");

        mockMvc.perform(get("/api/equipment/eq-cir/items/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.items.length()").value(2));
        mockMvc.perform(get("/api/equipment/eq-cir/readings"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    private record RaceResult(String name, int status) {
    }
}

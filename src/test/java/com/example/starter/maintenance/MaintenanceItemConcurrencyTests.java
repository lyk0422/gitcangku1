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
 * 多保养项目并发边界测试（真实 H2 数据库）：
 * 新增项目/项目保养与既有写操作竞争同一设备版本，同 requestId 并发重放只生效一次。
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
        jdbc.update("DELETE FROM maintenance_item");
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

    private void register(String equipmentId, long periodMinutes) throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":%d}
                                """.formatted(equipmentId, equipmentId, periodMinutes)))
                .andExpect(status().isCreated());
    }

    /** 并发新增两个不同项目（同一期望版本）：恰一个成功，版本只加一，无重复项目。 */
    @Test
    void concurrentAddItemSameVersion_onlyOneSucceeds() throws Exception {
        register("eq-ci", 1000);
        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> f1 = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-ci/items", """
                    {"requestId":"ci-belt","expectedVersion":1,"itemCode":"BELT",
                     "maintenancePeriodMinutes":500}
                    """);
        });
        Future<MvcResult> f2 = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-ci/items", """
                    {"requestId":"ci-filter","expectedVersion":1,"itemCode":"FILTER",
                     "maintenancePeriodMinutes":300}
                    """);
        });
        gate.countDown();

        int s1 = f1.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int s2 = f2.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        assertTrue(
                (s1 == 201 && s2 == 409) || (s1 == 409 && s2 == 201),
                "并发新增项目应恰有一个成功，实际：" + s1 + "/" + s2);

        mockMvc.perform(get("/api/equipment/eq-ci/items"))
                .andExpect(jsonPath("$.length()").value(2)); // DEFAULT + 一个新项目
        mockMvc.perform(get("/api/equipment/eq-ci/status"))
                .andExpect(jsonPath("$.version").value(2));
    }

    /** 并发同 requestId 同参新增项目：全部重放同一成功结果，项目只建一次。 */
    @Test
    void concurrentAddItemSameRequestId_replaysSingleEffect() throws Exception {
        register("eq-cirep", 1000);
        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"cirep-1","expectedVersion":1,"itemCode":"BELT",
                 "maintenancePeriodMinutes":500}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-cirep/items", body);
            }));
        }
        gate.countDown();

        Set<String> bodies = new HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(), "并发同键同参应全部重放成功结果");
            bodies.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, bodies.size(), "所有并发响应应完全相同");

        mockMvc.perform(get("/api/equipment/eq-cirep/items"))
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/equipment/eq-cirep/status"))
                .andExpect(jsonPath("$.version").value(2));
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'cirep-1'",
                Integer.class);
        assertEquals(1, idemCount, "同一 requestId 只应占一个幂等键");
    }

    /**
     * 两个项目并发以同一读数为锚点（同一期望版本）：第一轮恰一个成功；
     * 失败方按新版本重试后也成功。最终两个项目共享同一读数锚点，该读数被双方引用而不可修订。
     */
    @Test
    void concurrentItemMaintenance_sharedAnchorAfterRetry() throws Exception {
        register("eq-cm", 1000);
        postJson("/api/equipment/eq-cm/items", """
                {"requestId":"cm-item","expectedVersion":1,"itemCode":"BELT",
                 "maintenancePeriodMinutes":500}
                """);
        postJson("/api/equipment/eq-cm/readings", """
                {"requestId":"cm-r1","expectedVersion":2,"readingId":"r1",
                 "sampledAt":"2026-06-01T10:00:00Z","cumulativeMinutes":100}
                """);
        postJson("/api/equipment/eq-cm/readings", """
                {"requestId":"cm-r2","expectedVersion":3,"readingId":"r2",
                 "sampledAt":"2026-06-01T11:00:00Z","cumulativeMinutes":200}
                """);

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> defaultFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cm/maintenances", """
                    {"requestId":"cm-mn-d","expectedVersion":4,"readingId":"r1",
                     "anchorRevisionNo":1}
                    """);
        });
        Future<MvcResult> beltFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cm/maintenances", """
                    {"requestId":"cm-mn-b","expectedVersion":4,"readingId":"r1",
                     "anchorRevisionNo":1,"itemCode":"BELT"}
                    """);
        });
        gate.countDown();

        int defaultStatus = defaultFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        int beltStatus = beltFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        assertTrue(
                (defaultStatus == 201 && beltStatus == 409)
                        || (defaultStatus == 409 && beltStatus == 201),
                "并发项目保养应恰有一个首轮成功，实际：DEFAULT=" + defaultStatus + ", BELT=" + beltStatus);

        // 失败方用新版本重试：同一读数作为另一项目锚点，允许成功
        String failedRequestId = defaultStatus == 409 ? "cm-mn-d2" : "cm-mn-b2";
        String itemCode = defaultStatus == 409 ? null : "BELT";
        String retryBody = """
                {"requestId":"%s","expectedVersion":5,"readingId":"r1",
                 "anchorRevisionNo":1%s}
                """.formatted(failedRequestId, itemCode == null ? "" : ",\"itemCode\":\"" + itemCode + "\"");
        MvcResult retry = postJson("/api/equipment/eq-cm/maintenances", retryBody);
        assertEquals(201, retry.getResponse().getStatus(),
                "失败方按新版本重试应成功并让两项目共享同一锚点读数");

        // 版本 = 初始1 + 新增项目1 + 两条读数2 + 两次保养2 = 6
        mockMvc.perform(get("/api/equipment/eq-cm/status"))
                .andExpect(jsonPath("$.version").value(6));
        mockMvc.perform(get("/api/equipment/eq-cm/maintenances"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].readingId").value("r1"));
        mockMvc.perform(get("/api/equipment/eq-cm/items/BELT/maintenances"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].readingId").value("r1"));

        // 该读数被两个项目引用：修订被 409 拦截，错误信息列出全部 itemCode
        List<String> codes = jdbc.queryForList(
                "SELECT DISTINCT item_code FROM maintenance"
                        + " WHERE equipment_id = 'eq-cm' AND reading_id = 'r1' ORDER BY item_code",
                String.class);
        assertEquals(List.of("BELT", "DEFAULT"), codes);
        mockMvc.perform(post("/api/equipment/eq-cm/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"cm-rev","expectedVersion":6,"cumulativeMinutes":120}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_ANCHORED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("BELT")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("DEFAULT")));
    }

    /** 新增项目与新增读数并发混合：乐观重试后全部成功，版本严格 = 1 + 写次数，无丢失更新。 */
    @Test
    void concurrentMixedItemAndReadingWrites_noLostUpdate() throws Exception {
        register("eq-mix", 10000);
        int writers = 6;
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                for (int attempt = 0; attempt < 20; attempt++) {
                    MvcResult statusResult = mockMvc.perform(get("/api/equipment/eq-mix/status"))
                            .andReturn();
                    long version = Long.parseLong(statusResult.getResponse().getContentAsString()
                            .replaceAll(".*\"version\":(\\d+).*", "$1"));
                    MvcResult write;
                    if (index % 2 == 0) {
                        write = postJson("/api/equipment/eq-mix/items", """
                                {"requestId":"mix-item-%d","expectedVersion":%d,"itemCode":"I%d",
                                 "maintenancePeriodMinutes":%d}
                                """.formatted(index, version, index, 200 + index));
                    } else {
                        write = postJson("/api/equipment/eq-mix/readings", """
                                {"requestId":"mix-r-%d","expectedVersion":%d,"readingId":"r%d",
                                 "sampledAt":"2026-07-01T1%d:00:00Z","cumulativeMinutes":%d}
                                """.formatted(index, version, index, index, 100L * (index + 1)));
                    }
                    if (write.getResponse().getStatus() == 201) {
                        return true;
                    }
                    assertEquals(409, write.getResponse().getStatus());
                }
                throw new IllegalStateException("乐观并发重试次数耗尽");
            }));
        }
        gate.countDown();
        for (Future<Boolean> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }

        // 3 个自定义项目 + DEFAULT；3 条读数；版本 = 1 + 6 次写
        mockMvc.perform(get("/api/equipment/eq-mix/items"))
                .andExpect(jsonPath("$.length()").value(4));
        mockMvc.perform(get("/api/equipment/eq-mix/readings"))
                .andExpect(jsonPath("$.length()").value(3));
        mockMvc.perform(get("/api/equipment/eq-mix/status"))
                .andExpect(jsonPath("$.version").value(1 + writers));
    }
}

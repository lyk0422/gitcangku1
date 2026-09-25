package com.example.starter.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 多单位换算的并发与幂等边界测试：真实 H2 数据库上的并发写协调，
 * 断言响应、最终数据与换算留痕的一致性。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EquipmentMultiUnitConcurrencyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM reading_conversion");
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

    /** 并发同 requestId 跨单位提交：全部重放首次结果，读数与换算留痕均只产生一次。 */
    @Test
    void concurrentCrossUnitSameRequestId_singleEffectSingleTrace() throws Exception {
        postJson("/api/equipment", """
                {"requestId":"reg-cc","equipmentId":"eq-cc","maintenancePeriodMinutes":1000}
                """);
        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"cc-1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","unit":"HOURS","cumulativeValue":1.68}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-cc/readings", body);
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

        // 业务效果与换算留痕均只发生一次
        mockMvc.perform(get("/api/equipment/eq-cc/readings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(101));
        mockMvc.perform(get("/api/equipment/eq-cc/status"))
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(get("/api/equipment/eq-cc/conversions"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].convertedMinutes").value(101));
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'cc-1'", Integer.class);
        assertEquals(1, idemCount, "同一 requestId 只应占一个幂等键");
    }

    /** 并发跨单位与登记单位写不同读数：串行化后均按版本推进，判定口径一致。 */
    @Test
    void concurrentMixedUnitWrites_noLostUpdate() throws Exception {
        postJson("/api/equipment", """
                {"requestId":"reg-mx","equipmentId":"eq-mx","unit":"HOURS","maintenancePeriod":100.0}
                """);
        int threads = 6;
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                for (int attempt = 0; attempt < 20; attempt++) {
                    MvcResult statusResult = mockMvc.perform(get("/api/equipment/eq-mx/status"))
                            .andReturn();
                    String statusBody = statusResult.getResponse().getContentAsString();
                    long version = Long.parseLong(statusBody.replaceAll(".*\"version\":(\\d+).*", "$1"));
                    // 偶数线程按小时提交，奇数线程跨单位按分钟提交（值相同：60*(index+1) 分钟）
                    String writeBody = index % 2 == 0
                            ? """
                              {"requestId":"mx-%d","expectedVersion":%d,"readingId":"r-%d",
                               "sampledAt":"2026-01-01T1%d:00:00Z","cumulativeValue":%s}
                              """.formatted(index, version, index, index, index + 1)
                            : """
                              {"requestId":"mx-%d","expectedVersion":%d,"readingId":"r-%d",
                               "sampledAt":"2026-01-01T1%d:00:00Z","unit":"MINUTES",
                               "cumulativeValue":%d}
                              """.formatted(index, version, index, index, 60L * (index + 1));
                    MvcResult write = postJson("/api/equipment/eq-mx/readings", writeBody);
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

        // 全部写成功：读数齐全、版本 = 1 + 写次数；跨单位提交的留痕仅奇数线程 3 条
        mockMvc.perform(get("/api/equipment/eq-mx/readings"))
                .andExpect(jsonPath("$.length()").value(threads));
        mockMvc.perform(get("/api/equipment/eq-mx/status"))
                .andExpect(jsonPath("$.version").value(1 + threads))
                // 最新读数 r-5：360 分钟 = 6.0h，统一按分钟口径判定
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(360))
                .andExpect(jsonPath("$.runMinutes").value(360));
        mockMvc.perform(get("/api/equipment/eq-mx/conversions"))
                .andExpect(jsonPath("$.length()").value(3));
    }
}

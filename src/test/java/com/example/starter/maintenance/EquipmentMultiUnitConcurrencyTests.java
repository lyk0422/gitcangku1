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
 * 多单位换算并发与幂等边界测试：真实 H2 数据库上协调跨单位并发写，
 * 断言换算留痕次数、版本推进与重放响应一致性。
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

    /** 并发同 requestId 跨单位同参：全部重放首次结果，读数与换算留痕各只产生一次。 */
    @Test
    void concurrentSameRequestId_crossUnit_singleTrace() throws Exception {
        // HOURS 设备：并发请求以 MINUTES 提交
        postJson("/api/equipment", """
                {"requestId":"reg-cxu","equipmentId":"eq-cxu","measurementUnit":"HOURS",
                 "maintenancePeriodValue":100}
                """);
        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"cxu-1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","unit":"MINUTES","cumulativeValue":90}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-cxu/readings", body);
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

        mockMvc.perform(get("/api/equipment/eq-cxu/readings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].cumulativeValue").value(1.5))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(90));
        // 换算留痕仅一条：重放不重复换算、不额外产生读数条目
        mockMvc.perform(get("/api/equipment/eq-cxu/conversions"))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/equipment/eq-cxu/status"))
                .andExpect(jsonPath("$.version").value(2));
    }

    /** 并发不同 requestId 跨单位新增：版本乐观并发 + 客户端重试后全部成功，留痕与读数一一对应。 */
    @Test
    void concurrentDistinctCrossUnitWrites_allSucceedWithTraces() throws Exception {
        postJson("/api/equipment", """
                {"requestId":"reg-cxp","equipmentId":"eq-cxp","measurementUnit":"HOURS",
                 "maintenancePeriodValue":1000}
                """);
        int threads = 6;
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                // 按采样时刻递增提交分钟数，保证换算分钟口径单调；冲突时按最新版本重试
                for (int attempt = 0; attempt < 20; attempt++) {
                    MvcResult statusResult = mockMvc.perform(get("/api/equipment/eq-cxp/status"))
                            .andReturn();
                    long version = Long.parseLong(statusResult.getResponse().getContentAsString()
                            .replaceAll(".*\"version\":(\\d+).*", "$1"));
                    long minutes = 60L * (index + 1);
                    MvcResult write = postJson("/api/equipment/eq-cxp/readings", """
                            {"requestId":"cxp-%d","expectedVersion":%d,"readingId":"r-%d",
                             "sampledAt":"2026-01-01T1%d:00:00Z","unit":"MINUTES","cumulativeValue":%d}
                            """.formatted(index, version, index, index, minutes));
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

        mockMvc.perform(get("/api/equipment/eq-cxp/readings"))
                .andExpect(jsonPath("$.length()").value(threads));
        // 每条跨单位读数恰好一条换算留痕
        mockMvc.perform(get("/api/equipment/eq-cxp/conversions"))
                .andExpect(jsonPath("$.length()").value(threads))
                .andExpect(jsonPath("$[0].sourceUnit").value("MINUTES"));
        mockMvc.perform(get("/api/equipment/eq-cxp/status"))
                .andExpect(jsonPath("$.version").value(1 + threads))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(60L * threads));
    }
}

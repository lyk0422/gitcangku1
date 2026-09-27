package com.example.starter.maintenance;

import static org.hamcrest.Matchers.hasSize;
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
 * 证据提交与双人完工的真实 H2 并发测试：设备行锁串行化 + 版本校验 + 幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvidenceConcurrencyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM evidence_appendix");
        jdbc.update("DELETE FROM maintenance_confirmation");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM evidence_material");
        jdbc.update("DELETE FROM evidence_check_result");
        jdbc.update("DELETE FROM evidence_package");
        jdbc.update("DELETE FROM maintenance_template_item");
        jdbc.update("DELETE FROM maintenance_template");
        jdbc.update("DELETE FROM material");
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

    private void setup(String equipmentId, String batchCode) throws Exception {
        postJson("/api/equipment", """
                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":100}
                """.formatted(equipmentId, equipmentId));
        postJson("/api/equipment/" + equipmentId + "/template-versions", """
                {"requestId":"tpl-%s","expectedVersion":1,"overdueThresholdMinutes":200,
                 "items":[{"itemCode":"I1","itemTitle":"润滑"},{"itemCode":"I2","itemTitle":"紧固"}]}
                """.formatted(equipmentId));
        postJson("/api/equipment/" + equipmentId + "/readings", """
                {"requestId":"r-%s","expectedVersion":2,"readingId":"r1",
                 "sampledAt":"2026-02-01T10:00:00Z","cumulativeMinutes":130}
                """.formatted(equipmentId));
        postJson("/api/materials", """
                {"requestId":"mat-%s","batchCode":"%s","description":"x"}
                """.formatted(equipmentId, batchCode));
    }

    /** 并发同 requestId 同参提交证据：全部重放同一成功结果，只生成一个版本、版本号只加一。 */
    @Test
    void concurrentSameRequestIdEvidence_singleVersion() throws Exception {
        setup("eq-ce1", "MAT-CE0001");
        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"ce1-ev","expectedVersion":3,"submittedBy":"alice",
                 "checkResults":[{"itemCode":"I1","result":"PASS"}],
                 "materials":[{"batchCode":"MAT-CE0001","quantity":1,"unit":"PCS"}]}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-ce1/evidence", body);
            }));
        }
        gate.countDown();

        Set<String> responses = new HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(), "并发同键同参证据提交应全部成功重放");
            responses.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, responses.size());
        mockMvc.perform(get("/api/equipment/eq-ce1/evidence"))
                .andExpect(jsonPath("$.packageVersion").value(1));
        mockMvc.perform(get("/api/equipment/eq-ce1/status"))
                .andExpect(jsonPath("$.version").value(4));
        Integer packageRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence_package WHERE equipment_id = 'eq-ce1'", Integer.class);
        assertEquals(1, packageRows);
    }

    /** 并发不同 requestId 证据更新：串行裁决 + 乐观重试后全部生效，证据版本递增无丢失。 */
    @Test
    void concurrentDistinctEvidenceUpdates_allVersionsPersisted() throws Exception {
        setup("eq-ce2", "MAT-CE0002");
        int threads = 4;
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                // 线程 0 起反复提交 I1，其余提交 I2；均按最新版本重试直到成功
                String item = index == 0 ? "I1" : "I2";
                for (int attempt = 0; attempt < 30; attempt++) {
                    MvcResult statusResult = mockMvc.perform(get("/api/equipment/eq-ce2/status"))
                            .andReturn();
                    long version = Long.parseLong(statusResult.getResponse().getContentAsString()
                            .replaceAll(".*\"version\":(\\d+).*", "$1"));
                    String body = """
                            {"requestId":"ce2-%d-%d","expectedVersion":%d,"submittedBy":"alice",
                             "checkResults":[{"itemCode":"%s","result":"PASS"}]}
                            """.formatted(index, attempt, version, item);
                    MvcResult write = postJson("/api/equipment/eq-ce2/evidence", body);
                    if (write.getResponse().getStatus() == 201) {
                        return write.getResponse().getStatus();
                    }
                    assertEquals(409, write.getResponse().getStatus());
                }
                throw new IllegalStateException("证据并发重试次数耗尽");
            }));
        }
        gate.countDown();
        for (Future<Integer> future : futures) {
            assertEquals(201, future.get(30, TimeUnit.SECONDS));
        }

        // 4 次成功提交：证据包版本 1..4，设备版本 3 + 4 = 7；
        // 该场景未提交材料摘要，故最终包检查项齐全但 complete=false（缺材料）
        mockMvc.perform(get("/api/equipment/eq-ce2/evidence"))
                .andExpect(jsonPath("$.packageVersion").value(4))
                .andExpect(jsonPath("$.checkResults.length()").value(2))
                .andExpect(jsonPath("$.failedItems", hasSize(0)))
                .andExpect(jsonPath("$.complete").value(false));
        mockMvc.perform(get("/api/equipment/eq-ce2/status"))
                .andExpect(jsonPath("$.version").value(7));
        Integer packageRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence_package WHERE equipment_id = 'eq-ce2'", Integer.class);
        assertEquals(4, packageRows);
    }

    /** 并发双人完工确认：恰一个成功；保养记录与双人确认各仅一条。 */
    @Test
    void concurrentCompletions_exactlyOneConfirmed() throws Exception {
        setup("eq-ce3", "MAT-CE0003");
        postJson("/api/equipment/eq-ce3/evidence", """
                {"requestId":"ce3-ev","expectedVersion":3,"submittedBy":"alice",
                 "checkResults":[{"itemCode":"I1","result":"PASS"},{"itemCode":"I2","result":"PASS"}],
                 "materials":[{"batchCode":"MAT-CE0003","quantity":1,"unit":"PCS"}]}
                """);

        int threads = 4;
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-ce3/maintenances", """
                        {"requestId":"ce3-mnt-%d","expectedVersion":4,"readingId":"r1","anchorRevisionNo":1,
                         "expectedPackageVersion":1,"expectedTemplateVersion":1,"confirmerId":"bob%d"}
                        """.formatted(index, index));
            }));
        }
        gate.countDown();

        int success = 0;
        int conflict = 0;
        for (Future<MvcResult> future : futures) {
            int status = future.get(15, TimeUnit.SECONDS).getResponse().getStatus();
            assertTrue(status == 201 || status == 409, "完工并发只允许 201/409，实际 " + status);
            if (status == 201) {
                success++;
            } else {
                conflict++;
            }
        }
        assertEquals(1, success, "仅一个完工确认可成功");
        assertEquals(threads - 1, conflict);

        Integer maintenanceCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance WHERE equipment_id = 'eq-ce3'", Integer.class);
        Integer confirmationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_confirmation WHERE equipment_id = 'eq-ce3'", Integer.class);
        assertEquals(1, maintenanceCount);
        assertEquals(1, confirmationCount);
        mockMvc.perform(get("/api/equipment/eq-ce3/status"))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.currentCycleNo").value(2));
    }
}

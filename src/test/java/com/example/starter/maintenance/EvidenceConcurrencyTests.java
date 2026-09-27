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
 * 证据包并发与幂等边界测试：真实 H2 数据库上协调真实并发操作，断言响应与最终数据。
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
        jdbc.update("DELETE FROM maintenance_appendix");
        jdbc.update("DELETE FROM completion_confirmation");
        jdbc.update("DELETE FROM evidence_material");
        jdbc.update("DELETE FROM evidence_item_result");
        jdbc.update("DELETE FROM evidence_package");
        jdbc.update("DELETE FROM material_batch");
        jdbc.update("DELETE FROM maintenance_template_item");
        jdbc.update("DELETE FROM maintenance_template_head");
        jdbc.update("DELETE FROM idempotency_request");
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

    private void prepareTemplateAndMaterial() throws Exception {
        postJson("/api/maintenance-templates", """
                {"requestId":"ct-cc","templateId":"tpl-cc","items":[
                  {"itemCode":"oil","itemName":"机油","required":true,"sortOrder":1}]}
                """);
        for (int i = 1; i <= 10; i++) {
            postJson("/api/material-batches", """
                    {"requestId":"rm-%d","batchCode":"BATCH-C%d","materialName":"材料%d"}
                    """.formatted(i, i, i));
        }
        postJson("/api/equipment", """
                {"requestId":"reg-cc","equipmentId":"eq-cc","maintenancePeriodMinutes":1000,
                 "templateId":"tpl-cc"}
                """);
        postJson("/api/equipment/eq-cc/readings", """
                {"requestId":"rd-cc","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                """);
    }

    /** 并发提交不同证据包版本：按提交顺序裁决，版本连续、无丢失更新，失败事务不留半成品。 */
    @Test
    void concurrentEvidenceSubmission_serializedVersions() throws Exception {
        prepareTemplateAndMaterial();
        int threads = 6;
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                // 客户端乐观并发：读取当前版本后提交，409 则按最新版本重试（证据包版本也递增）
                for (int attempt = 0; attempt < 20; attempt++) {
                    MvcResult statusResult = mockMvc.perform(get("/api/equipment/eq-cc/status"))
                            .andReturn();
                    long version = Long.parseLong(statusResult.getResponse().getContentAsString()
                            .replaceAll(".*\"version\":(\\d+).*", "$1"));
                    MvcResult result = postJson("/api/equipment/eq-cc/evidence-packages", """
                            {"requestId":"se-%d","expectedVersion":%d,"submittedBy":"tech-a",
                             "itemResults":[{"itemCode":"oil","result":"PASS"}],
                             "materials":[{"batchCode":"BATCH-C%d","summary":"第%d次提交"}]}
                            """.formatted(index, version, index + 1, index + 1));
                    int statusCode = result.getResponse().getStatus();
                    if (statusCode == 201) {
                        return 201;
                    }
                    assertEquals(409, statusCode, "并发提交除成功外只应出现版本冲突");
                }
                throw new IllegalStateException("乐观并发重试次数耗尽");
            }));
        }
        gate.countDown();
        for (Future<Integer> future : futures) {
            assertEquals(201, future.get(30, TimeUnit.SECONDS));
        }

        // 最终状态：版本连续 2..7，证据包版本 1..6，每个材料恰好使用一次
        mockMvc.perform(get("/api/equipment/eq-cc/status"))
                .andExpect(jsonPath("$.version").value(2 + threads));
        mockMvc.perform(get("/api/equipment/eq-cc/evidence-packages"))
                .andExpect(jsonPath("$.length()").value(threads))
                .andExpect(jsonPath("$[0].packageVersion").value(1))
                .andExpect(jsonPath("$[5].packageVersion").value(6))
                .andExpect(jsonPath("$[5].materialCount").value(1));
        Integer materialRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence_material WHERE equipment_id = 'eq-cc' AND cycle_no = 1",
                Integer.class);
        assertEquals(threads, materialRows, "每次成功提交恰有一条材料摘要，无半成品或重复");
    }

    /** 并发同 requestId 同参提交：全部重放首次结果，只产生一个证据包版本。 */
    @Test
    void concurrentSameRequestIdSubmit_singlePackage() throws Exception {
        prepareTemplateAndMaterial();
        int threads = 6;
        CountDownLatch gate = new CountDownLatch(1);
        String body = """
                {"requestId":"se-same","expectedVersion":2,"submittedBy":"tech-a",
                 "itemResults":[{"itemCode":"oil","result":"PASS"}],
                 "materials":[{"batchCode":"BATCH-C1","summary":"同键提交"}]}
                """;
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-cc/evidence-packages", body);
            }));
        }
        gate.countDown();

        java.util.Set<String> responses = new java.util.HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(15, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus());
            responses.add(result.getResponse().getContentAsString());
        }
        assertEquals(1, responses.size());
        mockMvc.perform(get("/api/equipment/eq-cc/evidence-packages"))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/equipment/eq-cc/status"))
                .andExpect(jsonPath("$.version").value(3));
    }

    /** 证据提交与材料撤销并发：按提交顺序裁决，撤销后新引用必 422，无半成品。 */
    @Test
    void concurrentSubmitVersusMaterialRevoke() throws Exception {
        prepareTemplateAndMaterial();
        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> revokeFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/material-batches/BATCH-C1/revocations", """
                    {"requestId":"rvk-cc","reason":"并发撤销"}
                    """);
        });
        Future<MvcResult> submitFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-cc/evidence-packages", """
                    {"requestId":"se-race","expectedVersion":2,"submittedBy":"tech-a",
                     "itemResults":[{"itemCode":"oil","result":"PASS"}],
                     "materials":[{"batchCode":"BATCH-C1","summary":"撤销竞态"}]}
                    """);
        });
        gate.countDown();

        MvcResult revoke = revokeFuture.get(15, TimeUnit.SECONDS);
        MvcResult submit = submitFuture.get(15, TimeUnit.SECONDS);
        int revokeStatus = revoke.getResponse().getStatus();
        int submitStatus = submit.getResponse().getStatus();
        assertEquals(201, revokeStatus, "撤销必须成功");
        assertTrue(submitStatus == 201 || submitStatus == 422,
                "提交按顺序裁决：撤销前成功（201）或撤销后拒绝（422），实际 " + submitStatus);

        if (submitStatus == 422) {
            // 撤销先提交：证据包不存在，且撤销后补提交同批次仍 422
            mockMvc.perform(get("/api/equipment/eq-cc/evidence-packages"))
                    .andExpect(jsonPath("$.length()").value(0));
            mockMvc.perform(post("/api/equipment/eq-cc/evidence-packages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"requestId":"se-after","expectedVersion":2,"submittedBy":"tech-a",
                                     "itemResults":[{"itemCode":"oil","result":"PASS"}],
                                     "materials":[{"batchCode":"BATCH-C1","summary":"撤销后引用"}]}
                                    """))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("MATERIAL_REVOKED"));
            mockMvc.perform(get("/api/equipment/eq-cc/status"))
                    .andExpect(jsonPath("$.version").value(2));
        } else {
            // 提交先于撤销：证据包诊断将该材料标为已撤销，完工门禁 422 列出 REVOKED_MATERIAL
            mockMvc.perform(get("/api/equipment/eq-cc/evidence-diagnostics"))
                    .andExpect(jsonPath("$.revokedMaterials", org.hamcrest.Matchers.hasSize(1)))
                    .andExpect(jsonPath("$.revokedMaterials[0]").value("BATCH-C1"))
                    .andExpect(jsonPath("$.readyForCompletion").value(false));
            mockMvc.perform(post("/api/equipment/eq-cc/maintenances")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"requestId":"mnt-blocked","expectedVersion":3,"readingId":"r1",
                                     "anchorRevisionNo":1}
                                    """))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].type").value("REVOKED_MATERIAL"));
        }
    }

    /** 并发双人确认（同期望版本、同证据包版本）：恰有一个成功，周期只推进一次。 */
    @Test
    void concurrentConfirmations_onlyOneCompletes() throws Exception {
        prepareTemplateAndMaterial();
        postJson("/api/equipment/eq-cc/evidence-packages", """
                {"requestId":"se-pre","expectedVersion":2,"submittedBy":"tech-a",
                 "itemResults":[{"itemCode":"oil","result":"PASS"}],
                 "materials":[{"batchCode":"BATCH-C1","summary":"完工前证据"}]}
                """);
        int threads = 4;
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return postJson("/api/equipment/eq-cc/completion-confirmations", """
                        {"requestId":"cf-%d","expectedVersion":3,"confirmedBy":"tech-b%d",
                         "packageVersion":1,"templateVersion":1,"readingId":"r1","anchorRevisionNo":1}
                        """.formatted(index, index));
            }));
        }
        gate.countDown();

        int success = 0;
        int conflict = 0;
        for (Future<MvcResult> future : futures) {
            int code = future.get(15, TimeUnit.SECONDS).getResponse().getStatus();
            assertTrue(code == 201 || code == 409, "确认只应成功或版本冲突，实际 " + code);
            if (code == 201) {
                success++;
            } else {
                conflict++;
            }
        }
        assertEquals(1, success, "恰有一个确认成功");
        assertEquals(threads - 1, conflict);

        mockMvc.perform(get("/api/equipment/eq-cc/status"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.runMinutes").value(0));
        Integer confirmations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM completion_confirmation WHERE equipment_id = 'eq-cc'",
                Integer.class);
        assertEquals(1, confirmations, "每周期至多一条完工确认");
        Integer maintenances = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance WHERE equipment_id = 'eq-cc'", Integer.class);
        assertEquals(1, maintenances, "保养记录只生成一次");
    }
}

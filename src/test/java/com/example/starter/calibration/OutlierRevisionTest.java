package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 测量修订版本测试：已放行批次新增读数只能创建后续修订版本，旧放行快照不改写；
 * 新修订的离群继续阻断放行；引用旧修订版本的复核返回 410。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutlierRevisionTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outlier_audit");
        jdbc.update("DELETE FROM batch_stats_record");
        jdbc.update("DELETE FROM batch_release_snapshot");
        jdbc.update("DELETE FROM outlier_review");
        jdbc.update("DELETE FROM outlier_record");
        jdbc.update("DELETE FROM batch_reading");
        jdbc.update("DELETE FROM calibration_batch");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private void createCertAndBatch() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-B","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-C-1","batchKey":"B-1","instrumentId":"INS-B",
                                 "targetValue":"10","allowedDeviation":"0.5"}"""))
                .andExpect(status().isCreated());
    }

    private String submitReading(String key, String value, String by) throws Exception {
        return mvc.perform(post("/api/batches/{batch}/readings", "B-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-R-%s","readingKey":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","value":"%s","submittedBy":"%s"}
                                """.formatted(key, key, value, by)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private void release(String requestId, String actor) throws Exception {
        mvc.perform(post("/api/batches/{key}/release", "B-1")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void releasedBatchNewReadingCreatesFollowUpRevisionAndKeepsOldSnapshot() throws Exception {
        createCertAndBatch();
        submitReading("RD-1", "10.1", "alice");
        release("REQ-L-1", "carol");

        // 已放行批次新增读数：创建后续修订版本（revision=2，状态回到 OPEN）
        String body = submitReading("RD-2", "10.2", "alice");
        Object revision = com.jayway.jsonpath.JsonPath.read(body, "$.batchRevision");
        org.junit.jupiter.api.Assertions.assertEquals(2, ((Number) revision).intValue());

        // 旧放行快照不改写：统计与放行人保持封存值
        mvc.perform(get("/api/batches/{key}", "B-1"))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.releaseSnapshots.length()").value(1))
                .andExpect(jsonPath("$.releaseSnapshots[0].batchRevision").value(1))
                .andExpect(jsonPath("$.releaseSnapshots[0].includedCount").value(1))
                .andExpect(jsonPath("$.releaseSnapshots[0].meanValue").value("10.1"))
                .andExpect(jsonPath("$.releaseSnapshots[0].releasedBy").value("carol"));

        // 新修订统计只覆盖本修订的读数
        mvc.perform(get("/api/batches/{key}", "B-1"))
                .andExpect(jsonPath("$.latestStats.batchRevision").value(2))
                .andExpect(jsonPath("$.latestStats.includedCount").value(1))
                .andExpect(jsonPath("$.latestStats.meanValue").value("10.2"));

        // 新修订可独立放行，生成第二条快照，旧快照保持不变
        release("REQ-L-2", "dave");
        mvc.perform(get("/api/batches/{key}", "B-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releaseSnapshots.length()").value(2))
                .andExpect(jsonPath("$.releaseSnapshots[0].batchRevision").value(1))
                .andExpect(jsonPath("$.releaseSnapshots[0].meanValue").value("10.1"))
                .andExpect(jsonPath("$.releaseSnapshots[1].batchRevision").value(2))
                .andExpect(jsonPath("$.releaseSnapshots[1].meanValue").value("10.2"))
                .andExpect(jsonPath("$.releaseSnapshots[1].releasedBy").value("dave"));
    }

    @Test
    void newOutlierInFollowUpRevisionBlocksReleaseUntilVerdict() throws Exception {
        createCertAndBatch();
        submitReading("RD-1", "10.1", "alice");
        release("REQ-L-1", "carol");

        // 后续修订版本中出现新离群：继续阻断放行
        String body = submitReading("RD-2", "10.9", "alice");
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId")).longValue();
        mvc.perform(post("/api/batches/{key}/release", "B-1")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-L-2\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.actual").value(1));

        // 复核须引用当前修订版本（2）；引用旧版本（1）返回 410 并落库 STALE
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-RV-1","reviewer":"bob","decision":"ACCEPT",
                                 "reason":"旧修订","batchRevision":1,"certificateVersion":1}"""))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("STALE_REVIEW"));
        String stale = jdbc.queryForObject(
                "SELECT status FROM outlier_review WHERE reviewer = 'bob'", String.class);
        org.junit.jupiter.api.Assertions.assertEquals("STALE", stale);

        // 引用当前修订版本的有效复核形成裁决后可放行
        for (String reviewer : new String[]{"bob", "carol"}) {
            mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"requestId":"REQ-RV-OK-%s","reviewer":"%s","decision":"ACCEPT",
                                     "reason":"可接受","batchRevision":2,"certificateVersion":1}
                                    """.formatted(reviewer, reviewer)))
                    .andExpect(status().isCreated());
        }
        release("REQ-L-3", "dave");
        mvc.perform(get("/api/batches/{key}", "B-1"))
                .andExpect(jsonPath("$.releaseSnapshots.length()").value(2))
                .andExpect(jsonPath("$.releaseSnapshots[1].batchRevision").value(2));
    }

    @Test
    void evidenceHistoryIsAppendOnlyAcrossRevisions() throws Exception {
        createCertAndBatch();
        submitReading("RD-1", "10.6", "alice");
        // 第一修订的离群裁决后放行
        String body = mvc.perform(get("/api/batches/{key}", "B-1"))
                .andReturn().getResponse().getContentAsString();
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outliers[0].id")).longValue();
        for (String reviewer : new String[]{"bob", "carol"}) {
            mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"requestId":"REQ-RV-%s","reviewer":"%s","decision":"ACCEPT",
                                     "reason":"可接受","batchRevision":1,"certificateVersion":1}
                                    """.formatted(reviewer, reviewer)))
                    .andExpect(status().isCreated());
        }
        release("REQ-L-1", "dave");
        int auditsAfterFirst = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlier_audit", Integer.class);

        // 后续修订新增离群事实：历史审计记录不被改写，只追加
        submitReading("RD-2", "10.9", "alice");
        int auditsAfterSecond = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlier_audit", Integer.class);
        org.junit.jupiter.api.Assertions.assertTrue(auditsAfterSecond > auditsAfterFirst);

        // 历史证据字段保持稳定：第一修订的检测记录封存值未被后续操作覆盖
        mvc.perform(get("/api/batches/{key}/audits", "B-1"))
                .andExpect(jsonPath("$[0].eventType").value("OUTLIER_DETECTED"))
                .andExpect(jsonPath("$[0].batchRevision").value(1))
                .andExpect(jsonPath("$[0].readingValue").value("10.6"))
                .andExpect(jsonPath("$[0].targetValue").value("10"))
                .andExpect(jsonPath("$[0].allowedDeviation").value("0.5"));
    }
}
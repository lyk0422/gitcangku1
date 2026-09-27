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
 * 离群复核主流程测试：离群检测阻断放行、法定人数裁决（ACCEPT/REJECT）、
 * REJECT 同事务排除读数并重算统计、放行快照不可变、修订开启新版本。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutlierReviewFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM batch_release");
        jdbc.update("DELETE FROM batch_stats_record");
        jdbc.update("DELETE FROM outlier_verdict");
        jdbc.update("DELETE FROM outlier_review");
        jdbc.update("DELETE FROM outlier_record");
        jdbc.update("DELETE FROM batch_reading");
        jdbc.update("DELETE FROM measurement_batch");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long createCert(String instrument) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private void createBatch(String batchKey, String instrument, String target, String deviation)
            throws Exception {
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchKey":"%s","instrumentId":"%s",
                                 "targetValue":"%s","allowedDeviation":"%s"}
                                """.formatted(batchKey, instrument, target, deviation)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    private String submitReading(String batchKey, String requestId, String reading, String by)
            throws Exception {
        return mvc.perform(post("/api/batches/{batchKey}/readings", batchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","measuredAt":"2026-06-01T00:00:00Z",
                                 "reading":"%s","submittedBy":"%s"}
                                """.formatted(requestId, reading, by)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private String review(long outlierId, String requestId, String reviewer, String decision,
                          int revision, long certId, int expectedStatus) throws Exception {
        return mvc.perform(post("/api/outliers/{outlierId}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","reviewer":"%s","decision":"%s",
                                 "reason":"%s 复核理由","batchRevision":%d,"certificateId":%d}
                                """.formatted(requestId, reviewer, decision, reviewer, revision, certId)))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void outlierBlocksReleaseUntilQuorumVerdictsThenReleaseSucceeds() throws Exception {
        long certId = createCert("INS-O1");
        createBatch("B-1", "INS-O1", "10", "1");

        // 左闭右开边界：9 在区间内（左闭），11 在区间外（右开），12 明显离群
        String inRange = submitReading("B-1", "rd-1", "9", "alice");
        org.junit.jupiter.api.Assertions.assertEquals(
                Boolean.FALSE, com.jayway.jsonpath.JsonPath.read(inRange, "$.outlier"));
        String boundary = submitReading("B-1", "rd-2", "11", "alice");
        org.junit.jupiter.api.Assertions.assertEquals(
                Boolean.TRUE, com.jayway.jsonpath.JsonPath.read(boundary, "$.outlier"));
        long outlierBoundary = ((Number) com.jayway.jsonpath.JsonPath.read(boundary, "$.outlierId")).longValue();
        String far = submitReading("B-1", "rd-3", "12", "alice");
        long outlierFar = ((Number) com.jayway.jsonpath.JsonPath.read(far, "$.outlierId")).longValue();

        // 批次明细：2 条待裁决离群；统计计入全部 3 条读数
        mvc.perform(get("/api/batches/{batchKey}", "B-1"))
                .andExpect(jsonPath("$.pendingOutliers").value(2))
                .andExpect(jsonPath("$.stats.includedCount").value(3))
                .andExpect(jsonPath("$.stats.excludedCount").value(0));

        // 未裁决离群阻断放行：422 且返回各离群记录的实际/允许偏差
        mvc.perform(post("/api/batches/{batchKey}/release", "B-1").header("X-Actor-Id", "carol"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OUTLIERS_UNRESOLVED"))
                .andExpect(jsonPath("$.failures.length()").value(2))
                .andExpect(jsonPath("$.failures[?(@.key=='" + outlierBoundary + "')].reasons[0]")
                        .value("OUTLIER_UNRESOLVED"))
                .andExpect(jsonPath("$.failures[?(@.key=='" + outlierFar + "')].reasons[1]")
                        .value("actualDeviation=2"))
                .andExpect(jsonPath("$.failures[?(@.key=='" + outlierFar + "')].reasons[2]")
                        .value("allowedDeviation=1"));

        // 复核人不能与读数提交人相同
        review(outlierBoundary, "rv-x", "alice", "ACCEPT", 1, certId, 403);

        // 离群记录 outlierBoundary（读数 11）：ACCEPT 法定人数裁决
        String r1 = review(outlierBoundary, "rv-1", "rev1", "ACCEPT", 1, certId, 201);
        org.junit.jupiter.api.Assertions.assertEquals(
                "PENDING", com.jayway.jsonpath.JsonPath.read(r1, "$.outlierStatus"));
        // 同一复核人重复复核 → 409
        review(outlierBoundary, "rv-2", "rev1", "ACCEPT", 1, certId, 409);
        // 相反结论 → 保持待仲裁
        String r2 = review(outlierBoundary, "rv-3", "rev2", "REJECT", 1, certId, 201);
        org.junit.jupiter.api.Assertions.assertEquals(
                "PENDING", com.jayway.jsonpath.JsonPath.read(r2, "$.outlierStatus"));
        // 放行仍被阻断
        mvc.perform(post("/api/batches/{batchKey}/release", "B-1").header("X-Actor-Id", "carol"))
                .andExpect(status().isUnprocessableEntity());
        // 第三名复核人 ACCEPT → 达到法定人数，裁决 ACCEPTED
        String r3 = review(outlierBoundary, "rv-4", "rev3", "ACCEPT", 1, certId, 201);
        org.junit.jupiter.api.Assertions.assertEquals(
                "ACCEPTED", com.jayway.jsonpath.JsonPath.read(r3, "$.outlierStatus"));

        // 离群明细：封存证据与裁决、全部复核记录
        mvc.perform(get("/api/outliers/{outlierId}", outlierBoundary))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.targetValue").value("10"))
                .andExpect(jsonPath("$.allowedDeviation").value("1"))
                .andExpect(jsonPath("$.readingValue").value("11"))
                .andExpect(jsonPath("$.actualDeviation").value("1"))
                .andExpect(jsonPath("$.excessDeviation").value("0"))
                .andExpect(jsonPath("$.verdict.decision").value("ACCEPT"))
                .andExpect(jsonPath("$.reviews.length()").value(3));

        // 已有最终裁决的离群记录不可再复核
        review(outlierBoundary, "rv-5", "rev4", "REJECT", 1, certId, 409);

        // 离群记录 outlierFar（读数 12）：REJECT 法定人数裁决，同事务排除读数并重算统计
        review(outlierFar, "rv-6", "rev1", "REJECT", 1, certId, 201);
        String r7 = review(outlierFar, "rv-7", "rev2", "REJECT", 1, certId, 201);
        org.junit.jupiter.api.Assertions.assertEquals(
                "REJECTED", com.jayway.jsonpath.JsonPath.read(r7, "$.outlierStatus"));

        // 重算后统计：读数 12 已排除，计入 9 与 11
        mvc.perform(get("/api/batches/{batchKey}", "B-1"))
                .andExpect(jsonPath("$.pendingOutliers").value(0))
                .andExpect(jsonPath("$.stats.includedCount").value(2))
                .andExpect(jsonPath("$.stats.excludedCount").value(1))
                .andExpect(jsonPath("$.stats.meanValue").value("10"))
                .andExpect(jsonPath("$.stats.minValue").value("9"))
                .andExpect(jsonPath("$.stats.maxValue").value("11"));

        // 全部离群裁决完成 → 放行成功，快照封存统计
        mvc.perform(post("/api/batches/{batchKey}/release", "B-1").header("X-Actor-Id", "carol"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchRevision").value(1))
                .andExpect(jsonPath("$.releasedBy").value("carol"))
                .andExpect(jsonPath("$.includedCount").value(2))
                .andExpect(jsonPath("$.excludedCount").value(1))
                .andExpect(jsonPath("$.meanValue").value("10"));

        // 重复放行 → 409
        mvc.perform(post("/api/batches/{batchKey}/release", "B-1").header("X-Actor-Id", "carol"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_RELEASED"));

        // 已放行批次拒绝新读数：新增离群事实只能进入后续修订版本
        mvc.perform(post("/api/batches/{batchKey}/readings", "B-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rd-4","measuredAt":"2026-06-01T00:00:00Z",
                                 "reading":"10","submittedBy":"alice"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_RELEASED"));

        // 测量修订：版本 +1，状态回到 OPEN；旧放行快照不改写
        mvc.perform(post("/api/batches/{batchKey}/revisions", "B-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetValue\":\"10\",\"allowedDeviation\":\"2\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.status").value("OPEN"));

        // 历史与诊断：旧修订放行快照保留、离群证据与统计重算轨迹完整
        mvc.perform(get("/api/batches/{batchKey}/history", "B-1"))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.releases.length()").value(1))
                .andExpect(jsonPath("$.releases[0].batchRevision").value(1))
                .andExpect(jsonPath("$.releases[0].includedCount").value(2))
                .andExpect(jsonPath("$.outliers.length()").value(2))
                .andExpect(jsonPath("$.statsRecords.length()").value(4))
                .andExpect(jsonPath("$.statsRecords[3].triggerType").value("READING_EXCLUDED"))
                .andExpect(jsonPath("$.statsRecords[3].includedCount").value(2))
                .andExpect(jsonPath("$.statsRecords[3].meanValue").value("10"));

        // 新修订版本可提交读数并放行
        String newReading = submitReading("B-1", "rd-5", "11.5", "alice");
        org.junit.jupiter.api.Assertions.assertEquals(
                Boolean.FALSE, com.jayway.jsonpath.JsonPath.read(newReading, "$.outlier"));
        org.junit.jupiter.api.Assertions.assertEquals(
                2, (int) com.jayway.jsonpath.JsonPath.read(newReading, "$.batchRevision"));
        mvc.perform(post("/api/batches/{batchKey}/release", "B-1").header("X-Actor-Id", "carol"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchRevision").value(2))
                .andExpect(jsonPath("$.includedCount").value(1));
    }

    @Test
    void batchValidationAndNotFoundBranches() throws Exception {
        createCert("INS-V");
        // 负偏差 → 400
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchKey":"B-NEG","instrumentId":"INS-V",
                                 "targetValue":"10","allowedDeviation":"-1"}
                                """))
                .andExpect(status().isBadRequest());
        // 批次键重复 → 409
        createBatch("B-DUP", "INS-V", "10", "1");
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchKey":"B-DUP","instrumentId":"INS-V",
                                 "targetValue":"10","allowedDeviation":"1"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BATCH_KEY"));
        // 批次不存在 → 404
        mvc.perform(get("/api/batches/{batchKey}", "B-NONE")).andExpect(status().isNotFound());
        mvc.perform(get("/api/outliers/{outlierId}", 99999)).andExpect(status().isNotFound());
        // 无匹配有效证书 → 422
        mvc.perform(post("/api/batches/{batchKey}/readings", "B-DUP")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rd-nocert","measuredAt":"2030-06-01T00:00:00Z",
                                 "reading":"10","submittedBy":"alice"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_MATCHING_CERTIFICATE"));
        // 空批次（无计入读数）放行 → 422
        mvc.perform(post("/api/batches/{batchKey}/release", "B-DUP").header("X-Actor-Id", "carol"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_INCLUDED_READINGS"));
        // 非法复核结论 → 400
        mvc.perform(post("/api/outliers/{outlierId}/reviews", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv-bad","reviewer":"rev1","decision":"MAYBE",
                                 "reason":"x","batchRevision":1,"certificateId":1}
                                """))
                .andExpect(status().isBadRequest());
    }
}

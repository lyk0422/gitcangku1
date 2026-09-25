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
 * 同行复核测试：复核独立性（复核人≠提交人）、PASS/RETURN 结论门禁、
 * 版本变化 410 与 STALE、修订后旧复核失效、requestId 幂等、
 * 复核历史 / 待复核清单 / 放行门禁状态查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM review_request");
        jdbc.update("DELETE FROM measurement_review");
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

    private void submit(String key, String instrument, String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"%s"}
                                """.formatted(key, instrument, by)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(1));
    }

    private String reviewJson(String reviewKey, String requestId, int revision,
                              String conclusion, String comment) {
        return """
                {"reviewKey":"%s","requestId":"%s","revision":%d,
                 "conclusion":"%s","comment":"%s"}
                """.formatted(reviewKey, requestId, revision, conclusion, comment);
    }

    private void review(String key, String reviewer, String reviewKey,
                        String requestId, int revision, String conclusion) throws Exception {
        mvc.perform(post("/api/measurements/{key}/reviews", key)
                        .header("X-Actor-Id", reviewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson(reviewKey, requestId, revision, conclusion, "说明")))
                .andExpect(status().isCreated());
    }

    private void revise(String key, String by) throws Exception {
        mvc.perform(post("/api/measurements/{key}/revisions", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measuredAt":"2026-06-01T00:00:00Z","reading":"2",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"%s"}
                                """.formatted(by)))
                .andExpect(status().isOk());
    }

    @Test
    void passReviewSatisfiesGateAndReleaseSucceeds() throws Exception {
        createCert("INS-1");
        submit("M-1", "INS-1", "alice");

        // 门禁：无复核时不可放行
        mvc.perform(get("/api/measurements/{key}/release-gate", "M-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releasable").value(false))
                .andExpect(jsonPath("$.effectivePassReview").doesNotExist())
                .andExpect(jsonPath("$.reasons[0]").value("REVIEW_MISSING"));

        mvc.perform(post("/api/measurements/{key}/reviews", "M-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("RV-1", "REQ-1", 1, "PASS", "数据一致")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewKey").value("RV-1"))
                .andExpect(jsonPath("$.measurementKey").value("M-1"))
                .andExpect(jsonPath("$.measurementRevision").value(1))
                .andExpect(jsonPath("$.reviewer").value("bob"))
                .andExpect(jsonPath("$.conclusion").value("PASS"))
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.effective").value(true));

        mvc.perform(get("/api/measurements/{key}/release-gate", "M-1"))
                .andExpect(jsonPath("$.releasable").value(true))
                .andExpect(jsonPath("$.effectivePassReview").value("RV-1"))
                .andExpect(jsonPath("$.reasons.length()").value(0));

        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-1\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    void releaseWithoutPassReviewRejectedWith422() throws Exception {
        createCert("INS-1");
        submit("M-1", "INS-1", "alice");

        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-1\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_REJECTED"))
                .andExpect(jsonPath("$.failures[0].key").value("M-1"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("REVIEW_MISSING"));

        mvc.perform(get("/api/measurements/{key}", "M-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void reviewerMustDifferFromSubmitter() throws Exception {
        createCert("INS-1");
        submit("M-1", "INS-1", "alice");

        mvc.perform(post("/api/measurements/{key}/reviews", "M-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("RV-1", "REQ-1", 1, "PASS", "自审")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVIEWER_IS_SUBMITTER"));

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM measurement_review", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, count);
    }

    @Test
    void returnReviewBlocksReleaseAndMarksNeedsRevision() throws Exception {
        createCert("INS-1");
        submit("M-1", "INS-1", "alice");
        review("M-1", "bob", "RV-1", "REQ-1", 1, "RETURN");

        mvc.perform(get("/api/measurements/{key}", "M-1"))
                .andExpect(jsonPath("$.status").value("NEEDS_REVISION"));

        // RETURN 后禁止放行（NOT_PENDING）
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-1\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("NOT_PENDING"));

        // 同一版本允许一条 PASS 与一条 RETURN 共存
        review("M-1", "carol", "RV-2", "REQ-2", 1, "PASS");
        // 重复同类有效复核 → 409
        mvc.perform(post("/api/measurements/{key}/reviews", "M-1")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("RV-3", "REQ-3", 1, "PASS", "再次通过")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REVIEW"));
        mvc.perform(post("/api/measurements/{key}/reviews", "M-1")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("RV-4", "REQ-4", 1, "RETURN", "再次退回")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REVIEW"));
    }

    @Test
    void staleReviewOnRevisionMismatchReturns410AndCannotRelease() throws Exception {
        createCert("INS-1");
        submit("M-1", "INS-1", "alice");
        revise("M-1", "alice"); // 版本变为 2

        // 针对旧版本 1 的复核 → 410，记录为 STALE
        mvc.perform(post("/api/measurements/{key}/reviews", "M-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("RV-OLD", "REQ-OLD", 1, "PASS", "基于旧版本")))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.reviewKey").value("RV-OLD"))
                .andExpect(jsonPath("$.measurementRevision").value(1))
                .andExpect(jsonPath("$.status").value("STALE"))
                .andExpect(jsonPath("$.effective").value(false));

        // STALE 复核不得用于放行
        mvc.perform(get("/api/measurements/{key}/release-gate", "M-1"))
                .andExpect(jsonPath("$.releasable").value(false))
                .andExpect(jsonPath("$.reasons[0]").value("REVIEW_MISSING"));
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-1\"]}"))
                .andExpect(status().isUnprocessableEntity());

        // 410 失败不占 requestId：同键修正版本后可成功（换用新复核键）
        mvc.perform(post("/api/measurements/{key}/reviews", "M-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("RV-NEW", "REQ-OLD", 2, "PASS", "基于旧版本")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.measurementRevision").value(2))
                .andExpect(jsonPath("$.effective").value(true));
    }

    @Test
    void revisionInvalidatesOldReviewsAndRequiresNewPass() throws Exception {
        createCert("INS-1");
        submit("M-1", "INS-1", "alice");
        review("M-1", "bob", "RV-1", "REQ-1", 1, "PASS");

        revise("M-1", "alice");
        mvc.perform(get("/api/measurements/{key}", "M-1"))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reading").value("2"));

        // 旧版本 PASS 仅保留历史，不迁移：门禁失效
        mvc.perform(get("/api/measurements/{key}/release-gate", "M-1"))
                .andExpect(jsonPath("$.releasable").value(false))
                .andExpect(jsonPath("$.reasons[0]").value("REVIEW_STALE"));
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-1\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("REVIEW_STALE"));

        // 复核历史保留旧记录且标记为无效
        mvc.perform(get("/api/measurements/{key}/reviews", "M-1"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reviewKey").value("RV-1"))
                .andExpect(jsonPath("$[0].status").value("VALID"))
                .andExpect(jsonPath("$[0].effective").value(false));

        // 新版本重新复核后可放行
        review("M-1", "bob", "RV-2", "REQ-2", 2, "PASS");
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-1\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    void requestIdReplayConflictAndFailureNotOccupying() throws Exception {
        createCert("INS-1");
        submit("M-1", "INS-1", "alice");

        // 失败（复核人=提交人）不占键
        mvc.perform(post("/api/measurements/{key}/reviews", "M-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("RV-1", "REQ-X", 1, "PASS", "自审")))
                .andExpect(status().isUnprocessableEntity());

        // 同 requestId 修正参数后成功
        mvc.perform(post("/api/measurements/{key}/reviews", "M-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("RV-1", "REQ-X", 1, "PASS", "自审")))
                .andExpect(status().isCreated());

        // 同键同参重放首次结果
        mvc.perform(post("/api/measurements/{key}/reviews", "M-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("RV-1", "REQ-X", 1, "PASS", "自审")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewKey").value("RV-1"));

        // 同键异参 → 409
        mvc.perform(post("/api/measurements/{key}/reviews", "M-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("RV-1", "REQ-X", 1, "PASS", "不同的说明")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 全程只产生一条复核记录
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM measurement_review", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, count);
    }

    @Test
    void reviewRejectedWhenCertificateRevokedOrMeasurementReleased() throws Exception {
        long certId = createCert("INS-1");
        submit("M-1", "INS-1", "alice");

        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
        mvc.perform(post("/api/measurements/{key}/reviews", "M-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("RV-1", "REQ-1", 1, "PASS", "证书已撤销")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_REVOKED"));

        // 已放行测量不能再复核
        createCert("INS-2");
        submit("M-2", "INS-2", "alice");
        review("M-2", "bob", "RV-2", "REQ-2", 1, "PASS");
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-2\"]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/measurements/{key}/reviews", "M-2")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("RV-3", "REQ-3", 1, "RETURN", "放行后退回")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_RELEASED"));
    }

    @Test
    void pendingListAndHistoryAndGateQueries() throws Exception {
        createCert("INS-1");
        submit("M-1", "INS-1", "alice");
        submit("M-2", "INS-1", "bob");

        // 两条均待复核
        mvc.perform(get("/api/reviews/pending"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].measurementKey").value("M-1"))
                .andExpect(jsonPath("$[0].revision").value(1))
                .andExpect(jsonPath("$[0].submittedBy").value("alice"));

        review("M-1", "carol", "RV-1", "REQ-1", 1, "PASS");

        // M-1 已有有效 PASS，移出待复核清单
        mvc.perform(get("/api/reviews/pending"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].measurementKey").value("M-2"));

        // 复核历史
        mvc.perform(get("/api/measurements/{key}/reviews", "M-1"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reviewKey").value("RV-1"))
                .andExpect(jsonPath("$[0].effective").value(true));
        mvc.perform(get("/api/measurements/{key}/reviews", "NO-SUCH"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/measurements/{key}/release-gate", "NO-SUCH"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reviseValidationAndNotFoundAndReleased() throws Exception {
        createCert("INS-1");
        submit("M-1", "INS-1", "alice");

        mvc.perform(post("/api/measurements/{key}/revisions", "NO-SUCH")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"measuredAt\":\"2026-06-01T00:00:00Z\",\"reading\":\"2\","
                                + "\"lowerLimit\":\"0\",\"upperLimit\":\"9\",\"submittedBy\":\"alice\"}"))
                .andExpect(status().isNotFound());

        // 测量时刻无匹配证书 → 422
        mvc.perform(post("/api/measurements/{key}/revisions", "M-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"measuredAt\":\"2030-06-01T00:00:00Z\",\"reading\":\"2\","
                                + "\"lowerLimit\":\"0\",\"upperLimit\":\"9\",\"submittedBy\":\"alice\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 已放行 → 409
        review("M-1", "bob", "RV-1", "REQ-1", 1, "PASS");
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-1\"]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/measurements/{key}/revisions", "M-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"measuredAt\":\"2026-06-01T00:00:00Z\",\"reading\":\"2\","
                                + "\"lowerLimit\":\"0\",\"upperLimit\":\"9\",\"submittedBy\":\"alice\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_RELEASED"));
    }
}

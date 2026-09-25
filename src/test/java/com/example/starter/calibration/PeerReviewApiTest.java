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
 * 同行复核与放行门禁主流程与失败分支测试：复核独立性、PASS/RETURN 门禁、重复复核 409、
 * 版本失效 410 STALE、修订不迁移旧复核、证书撤销、幂等同参重放/异参 409/失败不占键、
 * 待复核清单、复核历史与门禁状态查询、单条放行 422、修订前置状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PeerReviewApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM review_request");
        jdbc.update("DELETE FROM peer_review");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM measurement_head");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private void createCert(String instrument) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}""".formatted(instrument)))
                .andExpect(status().isCreated());
    }

    private void submit(String key, String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"INS-1",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"%s"}
                                """.formatted(key, by)))
                .andExpect(status().isCreated());
    }

    private String reviewJson(String key, String reviewKey, int version, String conclusion) {
        return """
                {"measurementKey":"%s","reviewKey":"%s","version":%d,
                 "conclusion":"%s","comment":"checked"}
                """.formatted(key, reviewKey, version, conclusion);
    }

    private void review(String reqId, String reviewer, String body, int expected) throws Exception {
        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", reviewer)
                        .header("X-Request-Id", reqId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    @Test
    void passReviewByDifferentReviewerOpensGateAndEnablesRelease() throws Exception {
        createCert("INS-1");
        submit("M-1", "alice");

        // 放行前门禁缺少有效 PASS
        mvc.perform(get("/api/measurements/{key}/release-gate", "M-1"))
                .andExpect(jsonPath("$.releasable").value(false))
                .andExpect(jsonPath("$.validPass").value(false))
                .andExpect(jsonPath("$.reasons[0]").value("REVIEW_GATE_FAILED"));

        // 无复核单条放行 → 422 且说明缺少/无效复核
        mvc.perform(post("/api/measurements/{key}/release", "M-1").header("X-Actor-Id", "carol"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GATE_NOT_SATISFIED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("PASS")));

        // 同行（非提交人）PASS 复核
        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "bob").header("X-Request-Id", "REQ-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("M-1", "RV-1", 1, "PASS")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("VALID"))
                .andExpect(jsonPath("$.conclusion").value("PASS"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.reviewer").value("bob"))
                .andExpect(jsonPath("$.certificateId").isNumber());

        mvc.perform(get("/api/measurements/{key}/release-gate", "M-1"))
                .andExpect(jsonPath("$.releasable").value(true))
                .andExpect(jsonPath("$.validPass").value(true))
                .andExpect(jsonPath("$.reasons.length()").value(0));

        // 具备门禁后由第三名放行人放行成功
        mvc.perform(post("/api/measurements/{key}/release", "M-1").header("X-Actor-Id", "carol"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released[0]").value("M-1"));
    }

    @Test
    void reviewerMustDifferFromSubmitterAndFailureDoesNotOccupyRequestId() throws Exception {
        createCert("INS-1");
        submit("M-2", "alice");

        // 复核人即提交人 → 422
        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "alice").header("X-Request-Id", "REQ-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("M-2", "RV-2", 1, "PASS")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVIEWER_IS_SUBMITTER"));

        // 失败不占键：同一 requestId 改正复核人后成功
        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "bob").header("X-Request-Id", "REQ-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("M-2", "RV-2", 1, "PASS")))
                .andExpect(status().isCreated());
        assertEqualsInt(1, "SELECT COUNT(*) FROM peer_review WHERE measurement_key='M-2'");
        assertEqualsInt(1, "SELECT COUNT(*) FROM review_request WHERE request_id='REQ-2'");
    }

    @Test
    void returnReviewMovesToRevisionAndBlocksRelease() throws Exception {
        createCert("INS-1");
        submit("M-3", "alice");

        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "bob").header("X-Request-Id", "REQ-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("M-3", "RV-3", 1, "RETURN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("VALID"));

        mvc.perform(get("/api/measurements/{key}", "M-3"))
                .andExpect(jsonPath("$.status").value("RETURNED"));
        mvc.perform(get("/api/measurements/{key}/release-gate", "M-3"))
                .andExpect(jsonPath("$.validReturn").value(true))
                .andExpect(jsonPath("$.releasable").value(false))
                .andExpect(jsonPath("$.reasons").value(org.hamcrest.Matchers.hasItem("RETURNED_FOR_REVISION")));

        // 单条放行 → 422
        mvc.perform(post("/api/measurements/{key}/release", "M-3").header("X-Actor-Id", "carol"))
                .andExpect(status().isUnprocessableEntity());
        // 批量放行 → 409，原因含 NOT_PENDING（待修订）与 REVIEW_GATE_FAILED（无有效 PASS）
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-3\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons").value(
                        org.hamcrest.Matchers.hasItems("NOT_PENDING", "REVIEW_GATE_FAILED")));
    }

    @Test
    void duplicateValidSameConclusionIs409ButPassAndReturnCanCoexist() throws Exception {
        createCert("INS-1");
        submit("M-4", "alice");

        review("REQ-4A", "bob", reviewJson("M-4", "RV-4A", 1, "PASS"), 201);
        // 重复有效 PASS（不同 reviewKey/requestId）→ 409
        review("REQ-4B", "carol", reviewJson("M-4", "RV-4B", 1, "PASS"), 409);

        // 一条有效 RETURN 可与有效 PASS 共存；RETURN 使测量转回待修订，门禁仍不满足
        review("REQ-4C", "carol", reviewJson("M-4", "RV-4C", 1, "RETURN"), 201);
        mvc.perform(get("/api/measurements/{key}/release-gate", "M-4"))
                .andExpect(jsonPath("$.validPass").value(true))
                .andExpect(jsonPath("$.validReturn").value(true))
                .andExpect(jsonPath("$.releasable").value(false));
        // 重复 PASS 被 409 拒绝且随事务回滚，故仅保留一条 PASS 与一条 RETURN
        assertEqualsInt(2, "SELECT COUNT(*) FROM peer_review WHERE measurement_key='M-4'");
        assertEqualsInt(1, "SELECT COUNT(*) FROM peer_review WHERE measurement_key='M-4' AND state='VALID' "
                + "AND conclusion='PASS'");
    }

    @Test
    void reviewAgainstStaleVersionReturns410AndPersistsStaleRecordNotUsableForRelease() throws Exception {
        createCert("INS-1");
        submit("M-5", "alice");
        // 先退回，再修订产生 v2
        review("REQ-5R", "bob", reviewJson("M-5", "RV-5R", 1, "RETURN"), 201);
        mvc.perform(post("/api/measurements/{key}/revise", "M-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reading":"2","lowerLimit":"0","upperLimit":"9"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 针对旧版本 v1 的复核 → 410，并固化 STALE
        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "REQ-5S")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("M-5", "RV-5S", 1, "PASS")))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("REVIEW_STALE"));
        // STALE 不得用于放行：当前 v2 无有效 PASS
        mvc.perform(get("/api/measurements/{key}/release-gate", "M-5"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.validPass").value(false))
                .andExpect(jsonPath("$.releasable").value(false));
        // 历史保留 STALE 记录
        mvc.perform(get("/api/measurements/{key}/reviews", "M-5"))
                .andExpect(jsonPath("$[?(@.state=='STALE')].conclusion").value(
                        org.hamcrest.Matchers.hasItem("PASS")));
    }

    @Test
    void revisionDoesNotMigratePriorReviews() throws Exception {
        createCert("INS-1");
        submit("M-6", "alice");
        review("REQ-6P", "bob", reviewJson("M-6", "RV-6P", 1, "PASS"), 201);
        review("REQ-6R", "carol", reviewJson("M-6", "RV-6R", 1, "RETURN"), 201);

        mvc.perform(post("/api/measurements/{key}/revise", "M-6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reading":"3","lowerLimit":"0","upperLimit":"9"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 新版本无任何复核，门禁不满足；旧 v1 复核仅保留历史
        mvc.perform(get("/api/measurements/{key}/release-gate", "M-6"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.validPass").value(false))
                .andExpect(jsonPath("$.validReturn").value(false));
        mvc.perform(post("/api/measurements/{key}/release", "M-6").header("X-Actor-Id", "carol"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/measurements/{key}/reviews", "M-6"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].version").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is(1))));
    }

    @Test
    void cannotRevisePendingOrReleasedMeasurement() throws Exception {
        createCert("INS-1");
        submit("M-7", "alice");
        // PENDING 不可直接修订（须先被 RETURN）
        mvc.perform(post("/api/measurements/{key}/revise", "M-7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reading":"3","lowerLimit":"0","upperLimit":"9"}"""))
                .andExpect(status().isConflict());

        review("REQ-7P", "bob", reviewJson("M-7", "RV-7P", 1, "PASS"), 201);
        mvc.perform(post("/api/measurements/{key}/release", "M-7").header("X-Actor-Id", "carol"))
                .andExpect(status().isOk());
        // RELEASED 不可修订
        mvc.perform(post("/api/measurements/{key}/revise", "M-7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reading":"3","lowerLimit":"0","upperLimit":"9"}"""))
                .andExpect(status().isConflict());
    }

    @Test
    void idempotentReplaySameParamsAndConflictOnDifferentParams() throws Exception {
        createCert("INS-1");
        submit("M-8", "alice");
        String body = reviewJson("M-8", "RV-8", 1, "PASS");

        mvc.perform(post("/api/reviews").header("X-Actor-Id", "bob").header("X-Request-Id", "REQ-8")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber());
        // 同键同参重放首次结果
        mvc.perform(post("/api/reviews").header("X-Actor-Id", "bob").header("X-Request-Id", "REQ-8")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewKey").value("RV-8"));
        // 同键异参 → 409
        mvc.perform(post("/api/reviews").header("X-Actor-Id", "bob").header("X-Request-Id", "REQ-8")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("M-8", "RV-OTHER", 1, "PASS")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PARAM_MISMATCH"));
        assertEqualsInt(1, "SELECT COUNT(*) FROM peer_review WHERE measurement_key='M-8'");
    }

    @Test
    void reviewOnRevokedCertificateRejectedAndUnknownMeasurement404AndHeadersRequired() throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-1","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long certId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
        submit("M-9", "alice");
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());

        review("REQ-9", "bob", reviewJson("M-9", "RV-9", 1, "PASS"), 422);

        // 未知测量 → 404
        mvc.perform(post("/api/reviews").header("X-Actor-Id", "bob").header("X-Request-Id", "REQ-9X")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("NOPE", "RV-X", 1, "PASS")))
                .andExpect(status().isNotFound());

        // 缺少 X-Request-Id → 400
        mvc.perform(post("/api/reviews").header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("M-9", "RV-Y", 1, "PASS")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pendingListAndHistoryReflectReviewState() throws Exception {
        createCert("INS-1");
        submit("M-A", "alice");
        submit("M-B", "alice");

        mvc.perform(get("/api/reviews/pending"))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/reviews/pending").param("instrumentId", "INS-1"))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/reviews/pending").param("instrumentId", "INS-OTHER"))
                .andExpect(jsonPath("$.length()").value(0));

        // M-A 通过后移出待复核清单
        review("REQ-A", "bob", reviewJson("M-A", "RV-A", 1, "PASS"), 201);
        mvc.perform(get("/api/reviews/pending"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].measurementKey").value("M-B"));

        // M-B 被退回后也不在待复核队列（等待提交人修订）
        review("REQ-B", "bob", reviewJson("M-B", "RV-B", 1, "RETURN"), 201);
        mvc.perform(get("/api/reviews/pending")).andExpect(jsonPath("$.length()").value(0));

        mvc.perform(get("/api/measurements/{key}/reviews", "M-B"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].conclusion").value("RETURN"));
    }

    @Test
    void reviewAfterReleaseRejectedAndSingleReleaseTwiceConflicts() throws Exception {
        createCert("INS-1");
        submit("M-R", "alice");
        review("REQ-RP", "bob", reviewJson("M-R", "RV-RP", 1, "PASS"), 201);
        mvc.perform(post("/api/measurements/{key}/release", "M-R").header("X-Actor-Id", "carol"))
                .andExpect(status().isOk());

        // 已放行后不再受理复核 → 409
        review("REQ-R2", "carol", reviewJson("M-R", "RV-R2", 1, "PASS"), 409);
        // 再次单条放行 → 409
        mvc.perform(post("/api/measurements/{key}/release", "M-R").header("X-Actor-Id", "dave"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_RELEASED"));
    }

    private void assertEqualsInt(int expected, String sql) {
        Integer actual = jdbc.queryForObject(sql, Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}

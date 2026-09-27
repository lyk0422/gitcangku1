package com.example.starter.calibration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 测量修订 H2 集成测试：410 版本过期、422 无匹配证书、版本自增、修订证据只增不回写、
 * 修订后回到待放行需重新裁决、旧放行快照保留、幂等同参重放/异参 409/失败不占键。
 */
class MeasurementRevisionApiTest extends AbstractCalibrationApiTest {

    private String reviseJson(String at, String reading, String lower, String upper, Long expected) {
        return """
                {"measuredAt":"%s","reading":"%s","lowerLimit":"%s","upperLimit":"%s","expectedVersion":%d}
                """.formatted(at, reading, lower, upper, expected);
    }

    @Test
    void reviseBumpsVersionRecomputesAndAppendsHistory() throws Exception {
        createCertificate("INS-1", "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z", "2", "1");
        submitMeasurement("V-1", "INS-1", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");

        // 计算值 2×1+1=3
        mvc.perform(get("/api/measurements/{key}", "V-1"))
                .andExpect(jsonPath("$.computedValue").value("3"))
                .andExpect(jsonPath("$.version").value(1));

        // 修订读数为 2 → 计算值 5
        mvc.perform(post("/api/measurements/{key}/revisions", "V-1")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("2026-06-02T00:00:00Z", "2", "0", "9", 1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.computedValue").value("5"))
                .andExpect(jsonPath("$.measuredAt").value("2026-06-02T00:00:00Z"))
                .andExpect(jsonPath("$.revisions.length()").value(1))
                .andExpect(jsonPath("$.revisions[0].fromVersion").value(1))
                .andExpect(jsonPath("$.revisions[0].toVersion").value(2))
                .andExpect(jsonPath("$.revisions[0].oldReading").value("1"))
                .andExpect(jsonPath("$.revisions[0].newReading").value("2"))
                .andExpect(jsonPath("$.revisions[0].oldComputedValue").value("3"))
                .andExpect(jsonPath("$.revisions[0].newComputedValue").value("5"))
                .andExpect(jsonPath("$.revisions[0].revisedBy").value("dave"));
    }

    @Test
    void staleExpectedVersionReturns410() throws Exception {
        createCertificate("INS-1", "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z", "1", "0");
        submitMeasurement("V-2", "INS-1", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
        mvc.perform(post("/api/measurements/{key}/revisions", "V-2")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("2026-06-02T00:00:00Z", "2", "0", "9", 7L)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("MEASUREMENT_VERSION_STALE"));
        mvc.perform(get("/api/measurements/{key}", "V-2"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.revisions.length()").value(0));
    }

    @Test
    void reviseOutsideCertificateValidityIs422() throws Exception {
        createCertificate("INS-1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "1", "0");
        submitMeasurement("V-3", "INS-1", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
        // 修订到右开终点之后 → 无匹配证书 422，事务回滚不留修订
        mvc.perform(post("/api/measurements/{key}/revisions", "V-3")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("2027-06-01T00:00:00Z", "1", "0", "9", 1L)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_MATCHING_CERTIFICATE"));
        mvc.perform(get("/api/measurements/{key}", "V-3"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.revisions.length()").value(0));
    }

    @Test
    void revisionAfterReleaseReturnsToPendingAndKeepsOldReleaseSnapshot() throws Exception {
        createCertificate("INS-1", "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z", "1", "0");
        createOpenThreshold("INS-1", "2026-01-01T00:00:00Z", "1");
        submitMeasurement("V-4", "INS-1", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
        registerBudget("V-4", "alice", budgetJson("u", "0.1"), null).andExpect(status().isOk());
        release("carol", null, "{\"keys\":[\"V-4\"]}").andExpect(status().isOk());
        mvc.perform(get("/api/measurements/{key}", "V-4"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(true));

        // 修订：回到待放行，旧放行历史保留，当前不可用
        mvc.perform(post("/api/measurements/{key}/revisions", "V-4")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("2026-06-02T00:00:00Z", "2", "0", "9", 1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.releases.length()").value(1))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("carol"))
                .andExpect(jsonPath("$.releases[0].combinedUncertainty").value("0.1000"));

        mvc.perform(get("/api/measurements/usable")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void revisionIdempotencyReplayConflictAndFailureDoesNotOccupyKey() throws Exception {
        createCertificate("INS-1", "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z", "1", "0");
        submitMeasurement("V-5", "INS-1", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
        String body = reviseJson("2026-06-02T00:00:00Z", "2", "0", "9", 1L);
        // 首次成功
        mvc.perform(post("/api/measurements/{key}/revisions", "V-5")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "REQ-R1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        // 同参重放：仍是首次快照（version=2），不再自增
        mvc.perform(post("/api/measurements/{key}/revisions", "V-5")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "REQ-R1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        // 异参（读数不同）→ 409
        mvc.perform(post("/api/measurements/{key}/revisions", "V-5")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "REQ-R1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("2026-06-02T00:00:00Z", "3", "0", "9", 1L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PARAM_CONFLICT"));
        // 失败不占键：REQ-R2 先版本过期（410），再以正确版本成功
        mvc.perform(post("/api/measurements/{key}/revisions", "V-5")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "REQ-R2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("2026-06-03T00:00:00Z", "4", "0", "9", 1L)))
                .andExpect(status().isGone());
        mvc.perform(post("/api/measurements/{key}/revisions", "V-5")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "REQ-R2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("2026-06-03T00:00:00Z", "4", "0", "9", 2L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3));
    }
}

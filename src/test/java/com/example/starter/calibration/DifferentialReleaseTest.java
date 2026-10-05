package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 差分放行门禁测试：放行人不得为任一原测量提交人、不合格差分不能放行、
 * 证书撤销整次 409、批量放行不得绕过差分门禁、期望版本校验、撤销后证据保留。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DifferentialReleaseTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM differential_occupation");
        jdbc.update("DELETE FROM differential_pair");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long createCert(String instrument, String a, String b) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"%s","b":"%s"}
                                """.formatted(instrument, a, b)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private void submit(String key, String instrument, String reading,
                        String lower, String upper, String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"%s",
                                 "lowerLimit":"%s","upperLimit":"%s","submittedBy":"%s"}
                                """.formatted(key, instrument, reading, lower, upper, by)))
                .andExpect(status().isCreated());
    }

    private long createDiff(String requestId, String refKey, String dutKey,
                            String lower, String upper) throws Exception {
        String body = mvc.perform(post("/api/differentials")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"referenceKey":"%s","dutKey":"%s","lowerLimit":"%s","upperLimit":"%s"}
                                """.formatted(refKey, dutKey, lower, upper)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    /** 合成一对合格测量并创建差值 0.3 的待审差分。 */
    private long createSyntheticPair(String requestId, String lower, String upper) throws Exception {
        createCert("INS-1", "1", "0");
        submit("REF-1", "INS-1", "9.8", "9", "10", "alice");
        submit("DUT-1", "INS-1", "10.1", "10", "11", "bob");
        return createDiff(requestId, "REF-1", "DUT-1", lower, upper);
    }

    @Test
    void releaseRejectsSameActorAsEitherSubmitter() throws Exception {
        long id = createSyntheticPair("req-sa-create", "0", "0.3");
        mvc.perform(post("/api/differentials/{id}/release", id)
                        .header("X-Actor-Id", "alice").header("X-Request-Id", "req-sa-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAME_ACTOR"));
        mvc.perform(post("/api/differentials/{id}/release", id)
                        .header("X-Actor-Id", "bob").header("X-Request-Id", "req-sa-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAME_ACTOR"));
        mvc.perform(get("/api/differentials/{id}", id))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void notPassedDifferentialSavedButCannotBeReleased() throws Exception {
        // 差值 0.3 超出上限 0.2：不合格差分仍保存为待审
        long id = createSyntheticPair("req-np-create", "0", "0.2");
        mvc.perform(get("/api/differentials/{id}", id))
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.status").value("PENDING"));

        mvc.perform(post("/api/differentials/{id}/release", id)
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "req-np-rel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DIFF_NOT_PASSED"));
        mvc.perform(get("/api/differentials/{id}", id))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/api/measurements/{key}", "REF-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    /** 合成场景反向：参考证书先撤销，则两条测量均不得被差分放行。 */
    @Test
    void revokedReferenceCertificateBlocksRelease() throws Exception {
        long id = createSyntheticPair("req-rv-create", "0", "0.3");
        long refCertId = ((Number) com.jayway.jsonpath.JsonPath
                .read(mvc.perform(get("/api/differentials/{id}", id)).andReturn()
                        .getResponse().getContentAsString(), "$.referenceCertificateId")).longValue();
        mvc.perform(post("/api/certificates/{id}/revoke", refCertId))
                .andExpect(status().isOk());

        mvc.perform(post("/api/differentials/{id}/release", id)
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "req-rv-rel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_REVOKED"));

        // 整次拒绝：两条原测量都保持待放行
        mvc.perform(get("/api/measurements/{key}", "REF-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/api/measurements/{key}", "DUT-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/api/differentials/{id}", id))
                .andExpect(jsonPath("$.status").value("PENDING"));
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(0, releaseCount);
    }

    @Test
    void releasedDifferentialUnusableAfterRevokeButEvidenceKept() throws Exception {
        long id = createSyntheticPair("req-eu-create", "0", "0.3");
        mvc.perform(post("/api/differentials/{id}/release", id)
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "req-eu-rel"))
                .andExpect(status().isOk());
        long dutCertId = ((Number) com.jayway.jsonpath.JsonPath
                .read(mvc.perform(get("/api/differentials/{id}", id)).andReturn()
                        .getResponse().getContentAsString(), "$.dutCertificateId")).longValue();

        mvc.perform(post("/api/certificates/{id}/revoke", dutCertId))
                .andExpect(status().isOk());

        // 差分当前不可用，但旧配对与放行证据仍可查
        mvc.perform(get("/api/differentials/{id}", id))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.releasedBy").value("carol"))
                .andExpect(jsonPath("$.diffValue").value("0.3"));
        mvc.perform(get("/api/measurements/{key}", "REF-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("carol"));
    }

    @Test
    void batchReleaseRejectsPairedMeasurement() throws Exception {
        createSyntheticPair("req-bg-create", "0", "0.3");
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"REF-1\",\"DUT-1\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[?(@.key=='REF-1')].reasons[0]")
                        .value("PAIRED_IN_DIFFERENTIAL"))
                .andExpect(jsonPath("$.failures[?(@.key=='DUT-1')].reasons[0]")
                        .value("PAIRED_IN_DIFFERENTIAL"));

        // 整批拒绝：两条都保持待放行
        mvc.perform(get("/api/measurements/{key}", "REF-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/api/measurements/{key}", "DUT-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void expectedVersionMismatchRejects() throws Exception {
        long id = createSyntheticPair("req-vc-create", "0", "0.3");
        mvc.perform(post("/api/differentials/{id}/release", id)
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "req-vc-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        mvc.perform(post("/api/differentials/{id}/release", id)
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "req-vc-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }
}

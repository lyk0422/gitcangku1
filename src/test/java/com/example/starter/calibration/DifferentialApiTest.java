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
 * 差分创建与取消测试：合成场景主流程（参考 9.8、待测 10.1、差 0.3 在 [0,0.3] 内）、
 * 配对约束（同仪器、采样间隔不超过 10 秒、原测量自身合格、未占用）、取消释放占用。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DifferentialApiTest {

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

    private void submit(String key, String instrument, String measuredAt, String reading,
                        String lower, String upper, String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s","measuredAt":"%s",
                                 "reading":"%s","lowerLimit":"%s","upperLimit":"%s","submittedBy":"%s"}
                                """.formatted(key, instrument, measuredAt, reading, lower, upper, by)))
                .andExpect(status().isCreated());
    }

    private String createDiffBody(String refKey, String dutKey, String lower, String upper) {
        return """
                {"referenceKey":"%s","dutKey":"%s","lowerLimit":"%s","upperLimit":"%s"}
                """.formatted(refKey, dutKey, lower, upper);
    }

    private long createDiff(String requestId, String refKey, String dutKey,
                            String lower, String upper) throws Exception {
        String body = mvc.perform(post("/api/differentials")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createDiffBody(refKey, dutKey, lower, upper)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    /** 合成场景：参考 9.8、待测 10.1，差 0.3 在 [0,0.3] 内，可创建并放行。 */
    @Test
    void syntheticPairCreatedAndReleased() throws Exception {
        createCert("INS-1", "1", "0");
        submit("REF-1", "INS-1", "2026-06-01T00:00:00Z", "9.8", "9", "10", "alice");
        submit("DUT-1", "INS-1", "2026-06-01T00:00:05Z", "10.1", "10", "11", "bob");

        long id = createDiff("req-create-1", "REF-1", "DUT-1", "0", "0.3");
        mvc.perform(get("/api/differentials/{id}", id))
                .andExpect(jsonPath("$.instrumentId").value("INS-1"))
                .andExpect(jsonPath("$.referenceKey").value("REF-1"))
                .andExpect(jsonPath("$.dutKey").value("DUT-1"))
                .andExpect(jsonPath("$.referenceValue").value("9.8"))
                .andExpect(jsonPath("$.dutValue").value("10.1"))
                .andExpect(jsonPath("$.diffValue").value("0.3"))
                .andExpect(jsonPath("$.displayDiff").value("0.3000"))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.version").value(1));

        // 配对不改变原测量放行状态
        mvc.perform(get("/api/measurements/{key}", "REF-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        mvc.perform(post("/api/differentials/{id}/release", id)
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "req-release-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(true))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.releasedBy").value("carol"));

        // 差分放行同时放行两条原测量
        mvc.perform(get("/api/measurements/{key}", "REF-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(true))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("carol"));
        mvc.perform(get("/api/measurements/{key}", "DUT-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"));
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(2, releaseCount);
    }

    @Test
    void createRejectsInstrumentMismatch() throws Exception {
        createCert("INS-1", "1", "0");
        createCert("INS-2", "1", "0");
        submit("REF-1", "INS-1", "2026-06-01T00:00:00Z", "9.8", "9", "10", "alice");
        submit("DUT-1", "INS-2", "2026-06-01T00:00:05Z", "10.1", "10", "11", "bob");

        mvc.perform(post("/api/differentials")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "req-mismatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createDiffBody("REF-1", "DUT-1", "0", "0.3")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSTRUMENT_MISMATCH"));
    }

    @Test
    void createRejectsSamplingIntervalOverTenSecondsAndAllowsBoundary() throws Exception {
        createCert("INS-1", "1", "0");
        // 边界：恰好 10 秒允许
        submit("REF-10", "INS-1", "2026-06-01T00:00:00Z", "9.8", "9", "10", "alice");
        submit("DUT-10", "INS-1", "2026-06-01T00:00:10Z", "10.1", "10", "11", "bob");
        createDiff("req-boundary", "REF-10", "DUT-10", "0", "0.3");

        // 11 秒拒绝
        submit("REF-11", "INS-1", "2026-06-01T00:00:00Z", "9.8", "9", "10", "alice");
        submit("DUT-11", "INS-1", "2026-06-01T00:00:11Z", "10.1", "10", "11", "bob");
        mvc.perform(post("/api/differentials")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "req-interval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createDiffBody("REF-11", "DUT-11", "0", "0.3")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAMPLING_INTERVAL_EXCEEDED"));
    }

    @Test
    void createRejectsUnpassedOrMissingMeasurement() throws Exception {
        createCert("INS-1", "1", "0");
        submit("REF-1", "INS-1", "2026-06-01T00:00:00Z", "9.8", "9", "10", "alice");
        submit("DUT-BAD", "INS-1", "2026-06-01T00:00:05Z", "100", "10", "11", "bob");

        mvc.perform(post("/api/differentials")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "req-notpassed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createDiffBody("REF-1", "DUT-BAD", "0", "100")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MEASUREMENT_NOT_PASSED"));

        mvc.perform(post("/api/differentials")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "req-missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createDiffBody("REF-1", "NO-SUCH", "0", "0.3")))
                .andExpect(status().isNotFound());
    }

    @Test
    void occupationBlocksReuseAndCancelReleasesIt() throws Exception {
        createCert("INS-1", "1", "0");
        submit("REF-1", "INS-1", "2026-06-01T00:00:00Z", "9.8", "9", "10", "alice");
        submit("DUT-1", "INS-1", "2026-06-01T00:00:05Z", "10.1", "10", "11", "bob");
        submit("DUT-2", "INS-1", "2026-06-01T00:00:06Z", "10.1", "10", "11", "bob");

        long id = createDiff("req-occ-1", "REF-1", "DUT-1", "0", "0.3");
        // REF-1 已被占用，绑定其他差分 409
        mvc.perform(post("/api/differentials")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "req-occ-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createDiffBody("REF-1", "DUT-2", "0", "0.3")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEASUREMENT_OCCUPIED"));

        // 取消后占用释放，记录保留；已取消不能再次取消或放行
        mvc.perform(post("/api/differentials/{id}/cancel", id)
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "req-cancel-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/differentials/{id}", id))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/differentials/{id}/cancel", id)
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "req-cancel-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CANCELLED"));
        mvc.perform(post("/api/differentials/{id}/release", id)
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "req-rel-cancelled"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CANCELLED"));

        createDiff("req-occ-3", "REF-1", "DUT-2", "0", "0.3");
    }

    @Test
    void releasedDifferentialCannotBeCancelled() throws Exception {
        createCert("INS-1", "1", "0");
        submit("REF-1", "INS-1", "2026-06-01T00:00:00Z", "9.8", "9", "10", "alice");
        submit("DUT-1", "INS-1", "2026-06-01T00:00:05Z", "10.1", "10", "11", "bob");
        long id = createDiff("req-rc-1", "REF-1", "DUT-1", "0", "0.3");
        mvc.perform(post("/api/differentials/{id}/release", id)
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "req-rc-rel"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/differentials/{id}/cancel", id)
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "req-rc-cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_RELEASED"));
    }

    @Test
    void detailUnknownDifferentialReturns404() throws Exception {
        mvc.perform(get("/api/differentials/{id}", 999999)).andExpect(status().isNotFound());
    }
}

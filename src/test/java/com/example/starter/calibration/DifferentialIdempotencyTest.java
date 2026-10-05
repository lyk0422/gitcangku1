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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 差分写操作幂等测试：同 requestId 同参数重放首次完整响应、异参数 409、
 * 失败不占键、放行重放不重复产生放行历史。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DifferentialIdempotencyTest {

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

    private void createCert(String instrument) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated());
    }

    private void submit(String key, String reading, String lower, String upper, String by)
            throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"INS-1",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"%s",
                                 "lowerLimit":"%s","upperLimit":"%s","submittedBy":"%s"}
                                """.formatted(key, reading, lower, upper, by)))
                .andExpect(status().isCreated());
    }

    private void preparePair() throws Exception {
        createCert("INS-1");
        submit("REF-1", "9.8", "9", "10", "alice");
        submit("DUT-1", "10.1", "10", "11", "bob");
    }

    private String createBody(String lower, String upper) {
        return """
                {"referenceKey":"REF-1","dutKey":"DUT-1","lowerLimit":"%s","upperLimit":"%s"}
                """.formatted(lower, upper);
    }

    @Test
    void sameRequestIdSameParamsReplaysFirstResponse() throws Exception {
        preparePair();
        String first = mvc.perform(post("/api/differentials")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("0", "0.3")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/differentials")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("0", "0.3")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertEquals(first, second, "同键同参重放必须返回首次完整响应");
        Integer diffCount = jdbc.queryForObject("SELECT COUNT(*) FROM differential_pair", Integer.class);
        Integer idemCount = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class);
        assertEquals(1, diffCount);
        assertEquals(1, idemCount);
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() throws Exception {
        preparePair();
        mvc.perform(post("/api/differentials")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "idem-2")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("0", "0.3")))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/differentials")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "idem-2")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("0", "0.5")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        Integer diffCount = jdbc.queryForObject("SELECT COUNT(*) FROM differential_pair", Integer.class);
        assertEquals(1, diffCount);
    }

    @Test
    void failedRequestDoesNotOccupyKey() throws Exception {
        preparePair();
        // 第一次失败：待测测量不存在（404）
        mvc.perform(post("/api/differentials")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "idem-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"referenceKey":"REF-1","dutKey":"NO-SUCH",
                                 "lowerLimit":"0","upperLimit":"0.3"}
                                """))
                .andExpect(status().isNotFound());
        Integer idemCount = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class);
        assertEquals(0, idemCount, "失败请求不得占用幂等键");

        // 同一 requestId 修正参数后可成功
        mvc.perform(post("/api/differentials")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "idem-3")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("0", "0.3")))
                .andExpect(status().isCreated());
    }

    @Test
    void releaseReplayReturnsSameResponseWithoutDuplicateHistory() throws Exception {
        preparePair();
        String created = mvc.perform(post("/api/differentials")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "idem-4")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("0", "0.3")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(created, "$.id")).longValue();

        String first = mvc.perform(post("/api/differentials/{id}/release", id)
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "idem-5"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/differentials/{id}/release", id)
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "idem-5"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(first, second, "放行重放必须返回首次完整响应");

        // 重放不重复放行：放行历史仍只有两条
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(2, releaseCount);
    }
}

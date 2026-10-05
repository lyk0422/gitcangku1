package com.example.starter.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 幂等去重测试：同键同参重放首次完整响应、异参 409、失败不占键、
 * 并发同键不泄露唯一键异常且只落一条业务数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM certificate_node");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private String certJson(String requestId, String instrument, String from, String to,
                            String a, String b) {
        return """
                {"instrumentId":"%s","validFrom":"%s","validTo":"%s","a":"%s","b":"%s","requestId":"%s"}
                """.formatted(instrument, from, to, a, b, requestId);
    }

    private long createLinearCert(String instrument) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}""".formatted(instrument)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    @Test
    void sameKeySameParamsReplaysFirstResponse() throws Exception {
        String json = certJson("IDEM-1", "INS-I1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "1.5", "0");
        String first = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Object firstId = com.jayway.jsonpath.JsonPath.read(first, "$.id");
        Object secondId = com.jayway.jsonpath.JsonPath.read(second, "$.id");
        assertEquals(firstId, secondId, "重放必须返回首次完整响应");
        Integer certs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_certificate WHERE instrument_id = 'INS-I1'", Integer.class);
        assertEquals(1, certs, "重放不得产生第二张证书");
        Integer idem = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class);
        assertEquals(1, idem);
    }

    @Test
    void sameKeyDifferentParamsReturns409() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("IDEM-2", "INS-I2", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "1", "0")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("IDEM-2", "INS-I2", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "2", "0")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void failedRequestDoesNotOccupyKey() throws Exception {
        createLinearCert("INS-I3");
        // 与现有证书区间重叠 → 业务失败 409，不占键
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("IDEM-3", "INS-I3", "2026-06-01T00:00:00Z", "2027-06-01T00:00:00Z", "1", "0")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_OVERLAP"));
        Integer idem = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class);
        assertEquals(0, idem, "失败请求不得占用幂等键");
        // 同键换合法参数可成功
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("IDEM-3", "INS-I3", "2028-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "1", "0")))
                .andExpect(status().isCreated());
    }

    @Test
    void measurementSubmitReplayReturnsFirstResponse() throws Exception {
        createLinearCert("INS-I4");
        String json = """
                {"measurementKey":"IM-1","instrumentId":"INS-I4","measuredAt":"2026-06-01T00:00:00Z",
                 "reading":"1","lowerLimit":"0","upperLimit":"9","submittedBy":"alice","requestId":"IDEM-4"}""";
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.measurementKey").value("IM-1"));
        // 重放返回首次响应，而不是测量键冲突 409
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.measurementKey").value("IM-1"));
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void releaseAndRevokeReplayReturnFirstResponse() throws Exception {
        long certId = createLinearCert("INS-I5");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"IM-2","instrumentId":"INS-I5",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}"""))
                .andExpect(status().isCreated());

        String releaseJson = "{\"keys\":[\"IM-2\"],\"requestId\":\"IDEM-5\"}";
        String first = mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(releaseJson))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // 重放返回首次批次，而不是 ALREADY_RELEASED 409
        String second = mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(releaseJson))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Object firstBatch = com.jayway.jsonpath.JsonPath.read(first, "$.batchId");
        Object secondBatch = com.jayway.jsonpath.JsonPath.read(second, "$.batchId");
        assertEquals(firstBatch, secondBatch);
        Integer releases = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(1, releases);

        // 撤销重放：首次 200，重放仍 200 而不是 ALREADY_REVOKED 409
        String revokeJson = "{\"requestId\":\"IDEM-6\"}";
        mvc.perform(post("/api/certificates/{id}/revoke", certId)
                        .contentType(MediaType.APPLICATION_JSON).content(revokeJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
        mvc.perform(post("/api/certificates/{id}/revoke", certId)
                        .contentType(MediaType.APPLICATION_JSON).content(revokeJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
    }

    @Test
    void concurrentSameRequestIdOnlyOneBusinessRow() throws Exception {
        int threads = 8;
        String json = certJson("IDEM-7", "INS-I7", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "1", "0");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                var response = mvc.perform(post("/api/certificates")
                                .contentType(MediaType.APPLICATION_JSON).content(json))
                        .andReturn().getResponse();
                return response.getStatus() + "|" + response.getContentAsString();
            }));
        }
        start.countDown();
        List<String> bodies = new ArrayList<>();
        for (Future<String> future : results) {
            String[] parts = future.get(30, TimeUnit.SECONDS).split("\\|", 2);
            assertEquals("201", parts[0], "并发同键不得出现唯一键异常等 500");
            bodies.add(parts[1]);
        }
        pool.shutdown();
        Object firstId = com.jayway.jsonpath.JsonPath.read(bodies.get(0), "$.id");
        for (String body : bodies) {
            assertEquals(firstId, com.jayway.jsonpath.JsonPath.read(body, "$.id"),
                    "并发同键必须全部重放首次响应");
            assertTrue(body.contains("INS-I7"));
        }
        Integer certs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_certificate WHERE instrument_id = 'INS-I7'", Integer.class);
        assertEquals(1, certs, "并发同键只能落一张证书");
        Integer idem = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class);
        assertEquals(1, idem, "并发同键只能落一条幂等记录");
    }
}

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
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 幂等与并发重试测试：
 * 1. 并发相同 requestId 的二次证书创建与反解提交，恰好一次业务生效，其余重放首次完整响应；
 * 2. 竞争不暴露唯一键错误（只允许 201 重放或 409 处理中，重试后必为重放）；
 * 3. 改参 409、十进制同值同参、文本原样。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyConcurrencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private String quadCertBody(String requestId, String a) {
        return """
                {"requestId":"%s","operator":"metrology","instrumentId":"INS-C",
                 "validFrom":"2026-01-01T00:00:00Z","validTo":"2028-01-01T00:00:00Z",
                 "a":"%s","b":"0","c":"0","domainLo":"1","domainHi":"3"}
                """.formatted(requestId, a);
    }

    private String inverseBody(String requestId, String key) {
        return """
                {"requestId":"%s","measurementKey":"%s","instrumentId":"INS-C",
                 "measuredAt":"2026-06-01T00:00:00Z","signal":"4",
                 "lowerLimit":"2","upperLimit":"2","submittedBy":"alice"}
                """.formatted(requestId, key);
    }

    /** 执行请求；若遇 409 处理中则顺序重试（竞态窗口），最终必须拿到确定结果。 */
    private MvcResult postWithRetry(String url, String body) throws Exception {
        for (int i = 0; i < 20; i++) {
            MvcResult result = mvc.perform(post(url)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn();
            int status = result.getResponse().getStatus();
            if (status == 409 && result.getResponse().getContentAsString()
                    .contains("IDEMPOTENCY_IN_PROGRESS")) {
                Thread.sleep(50);
                continue;
            }
            return result;
        }
        throw new AssertionError("重试后仍未获得确定响应");
    }

    @Test
    void concurrentSameRequestIdCertificateCreateExactlyOneWins() throws Exception {
        int threads = 6;
        String body = quadCertBody("REQ-CERT-1", "1");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return postWithRetry("/api/certificates/quadratic", body);
            }));
        }
        start.countDown();
        String firstBody = null;
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(60, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(),
                    "并发同键请求必须全部获得 201（首次或重放），不得暴露唯一键错误");
            if (firstBody == null) {
                firstBody = result.getResponse().getContentAsString();
            } else {
                assertEquals(firstBody, result.getResponse().getContentAsString(),
                        "重放必须返回首次完整响应");
            }
        }
        pool.shutdown();
        Integer certCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_certificate WHERE instrument_id = 'INS-C'", Integer.class);
        assertEquals(1, certCount, "并发同键创建必须恰好一张证书生效");
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'REQ-CERT-1'", Integer.class);
        assertEquals(1, idemCount, "幂等记录必须恰好一条");
    }

    @Test
    void concurrentSameRequestIdInverseSubmitExactlyOneWins() throws Exception {
        mvc.perform(post("/api/certificates/quadratic").contentType(MediaType.APPLICATION_JSON)
                        .content(quadCertBody("REQ-CERT-SETUP", "1")))
                .andExpect(status().isCreated());

        int threads = 6;
        String body = inverseBody("REQ-INV-1", "M-C1");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return postWithRetry("/api/measurements/inverse", body);
            }));
        }
        start.countDown();
        String firstBody = null;
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(60, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(),
                    "并发同键请求必须全部获得 201（首次或重放），不得暴露唯一键错误");
            if (firstBody == null) {
                firstBody = result.getResponse().getContentAsString();
            } else {
                assertEquals(firstBody, result.getResponse().getContentAsString(),
                        "重放必须返回首次完整响应");
            }
        }
        pool.shutdown();
        Integer measurementCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE measurement_key = 'M-C1'", Integer.class);
        assertEquals(1, measurementCount, "并发同键提交必须恰好一条测量生效");
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'REQ-INV-1'", Integer.class);
        assertEquals(1, idemCount);
    }

    @Test
    void decimalSameValueSameParamAndTextAsIs() throws Exception {
        // 十进制同值同参：a=1.0 与 a=1.00 视为同参 → 重放首次响应
        String first = mvc.perform(post("/api/certificates/quadratic")
                        .contentType(MediaType.APPLICATION_JSON).content(quadCertBody("REQ-DEC", "1.0")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/certificates/quadratic")
                        .contentType(MediaType.APPLICATION_JSON).content(quadCertBody("REQ-DEC", "1.00")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, replay, "十进制同值必须视为同参并重放");

        // 文本原样：operator 多一个空格即不同请求 → 409
        String differentOperator = quadCertBody("REQ-DEC", "1.0").replace(
                "\"operator\":\"metrology\"", "\"operator\":\"metrology \"");
        mvc.perform(post("/api/certificates/quadratic")
                        .contentType(MediaType.APPLICATION_JSON).content(differentOperator))
                .andExpect(status().isConflict());
    }

    @Test
    void changedParamsWithSameRequestIdReturns409() throws Exception {
        mvc.perform(post("/api/certificates/quadratic")
                        .contentType(MediaType.APPLICATION_JSON).content(quadCertBody("REQ-CHG", "1")))
                .andExpect(status().isCreated());
        // 改参（a 由 1 改为 2）→ 409
        mvc.perform(post("/api/certificates/quadratic")
                        .contentType(MediaType.APPLICATION_JSON).content(quadCertBody("REQ-CHG", "2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PARAMS_MISMATCH"));
        // 期望版本也是指纹的一部分：仅 expectedVersion 不同 → 409
        String withVersion = quadCertBody("REQ-CHG", "1").replace(
                "\"domainHi\":\"3\"", "\"domainHi\":\"3\",\"expectedVersion\":\"7\"");
        mvc.perform(post("/api/certificates/quadratic")
                        .contentType(MediaType.APPLICATION_JSON).content(withVersion))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PARAMS_MISMATCH"));
        Integer certCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_certificate WHERE instrument_id = 'INS-C'", Integer.class);
        assertEquals(1, certCount, "改参请求不得产生新业务数据");
    }

    @Test
    void missingRequestIdOrOperatorReturns400() throws Exception {
        String noRequestId = quadCertBody("REQ-X", "1").replace(
                "\"requestId\":\"REQ-X\",", "");
        mvc.perform(post("/api/certificates/quadratic")
                        .contentType(MediaType.APPLICATION_JSON).content(noRequestId))
                .andExpect(status().isBadRequest());
        String noOperator = quadCertBody("REQ-X", "1").replace(
                "\"operator\":\"metrology\"", "\"operator\":\"\"");
        mvc.perform(post("/api/certificates/quadratic")
                        .contentType(MediaType.APPLICATION_JSON).content(noOperator))
                .andExpect(status().isBadRequest());
        String noSubmitter = inverseBody("REQ-Y", "M-X").replace(
                "\"submittedBy\":\"alice\"", "\"submittedBy\":\"\"");
        mvc.perform(post("/api/measurements/inverse")
                        .contentType(MediaType.APPLICATION_JSON).content(noSubmitter))
                .andExpect(status().isBadRequest());
        Integer idemCount = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class);
        assertEquals(0, idemCount, "参数非法不得占用幂等键");
    }

    @Test
    void replayAfterFailureDoesNotOccupyKey() throws Exception {
        // 首次失败（非单调 400）→ 不占键
        String bad = """
                {"requestId":"REQ-F","operator":"metrology","instrumentId":"INS-C",
                 "validFrom":"2026-01-01T00:00:00Z","validTo":"2028-01-01T00:00:00Z",
                 "a":"1","b":"0","c":"0","domainLo":"-1","domainHi":"3"}""";
        mvc.perform(post("/api/certificates/quadratic")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'REQ-F'", Integer.class);
        assertEquals(0, idemCount, "失败请求不得占用幂等键");

        // 同一 requestId 修正参数后成功
        mvc.perform(post("/api/certificates/quadratic")
                        .contentType(MediaType.APPLICATION_JSON).content(quadCertBody("REQ-F", "1")))
                .andExpect(status().isCreated());
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'REQ-F'",
                Integer.class) == 1);
    }
}

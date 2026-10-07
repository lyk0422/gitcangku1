package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 二次校准证书接口测试：创建主流程、a 非零、输入闭区间、端点导数严格同号（非单调 400）、
 * 线性模式拒绝二次字段、与线性证书的区间重叠沿用原门禁。
 */
@SpringBootTest
@AutoConfigureMockMvc
class QuadraticCertificateApiTest {

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

    private String quadJson(String instrument, String a, String b, String c, String lo, String hi) {
        return """
                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z","validTo":"2027-01-01T00:00:00Z",
                 "mode":"QUADRATIC","a":"%s","b":"%s","c":"%s","domainLo":"%s","domainHi":"%s"}
                """.formatted(instrument, a, b, c, lo, hi);
    }

    @Test
    void createQuadraticCertificateReturns201WithModeAndDomain() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(quadJson("INS-Q", "1", "0", "0", "1", "3")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("QUADRATIC"))
                .andExpect(jsonPath("$.a").value("1"))
                .andExpect(jsonPath("$.b").value("0"))
                .andExpect(jsonPath("$.c").value("0"))
                .andExpect(jsonPath("$.domainLo").value("1"))
                .andExpect(jsonPath("$.domainHi").value("3"))
                .andExpect(jsonPath("$.revoked").value(false));
    }

    @Test
    void quadraticRejectsZeroAAndMissingFieldsAndInvalidDomain() throws Exception {
        // a = 0 → 400
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(quadJson("INS-Q", "0", "1", "0", "1", "3")))
                .andExpect(status().isBadRequest());
        // 缺 c → 400
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-Q","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","mode":"QUADRATIC",
                                 "a":"1","b":"0","domainLo":"1","domainHi":"3"}"""))
                .andExpect(status().isBadRequest());
        // 缺输入区间 → 400
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-Q","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","mode":"QUADRATIC",
                                 "a":"1","b":"0","c":"0"}"""))
                .andExpect(status().isBadRequest());
        // domainLo >= domainHi → 400
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(quadJson("INS-Q", "1", "0", "0", "3", "3")))
                .andExpect(status().isBadRequest());
        // 非法 mode → 400
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-Q","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","mode":"CUBIC",
                                 "a":"1","b":"0"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonMonotonicQuadraticRejectedWith400() throws Exception {
        // y=x² 在 [-1,3]：导数端点 -2 与 6 异号 → 400
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(quadJson("INS-Q", "1", "0", "0", "-1", "3")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        // y=x² 在 [0,3]：端点导数为 0，非严格 → 400
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(quadJson("INS-Q", "1", "0", "0", "0", "3")))
                .andExpect(status().isBadRequest());
        // 递减分支合法：y=-x²+10 在 [1,3]，导数端点 -2 与 -6 同号 → 201
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(quadJson("INS-Q", "-1", "0", "10", "1", "3")))
                .andExpect(status().isCreated());
    }

    @Test
    void linearCertificateRejectsQuadraticFields() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-L","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0","c":"0"}"""))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-L","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","mode":"LINEAR",
                                 "a":"1","b":"0","domainLo":"1","domainHi":"3"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void quadraticOverlapsLinearCertificateConflict409() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-M","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-M","validFrom":"2026-06-01T00:00:00Z",
                                 "validTo":"2027-06-01T00:00:00Z","mode":"QUADRATIC",
                                 "a":"1","b":"0","c":"0","domainLo":"1","domainHi":"3"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_OVERLAP"));
    }
}

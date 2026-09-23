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
 * 校准证书接口测试：创建主流程、参数校验、区间重叠、相邻合法、撤销语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CertificateApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement_revision_request");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM measurement_head");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private String certJson(String instrument, String from, String to, String a, String b) {
        return """
                {"instrumentId":"%s","validFrom":"%s","validTo":"%s","a":"%s","b":"%s"}
                """.formatted(instrument, from, to, a, b);
    }

    @Test
    void createCertificateReturns201WithGeneratedId() throws Exception {
        mvc.perform(post("/api/certificates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("INS-1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "1.5", "0.25")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.instrumentId").value("INS-1"))
                .andExpect(jsonPath("$.a").value("1.5"))
                .andExpect(jsonPath("$.b").value("0.25"))
                .andExpect(jsonPath("$.revoked").value(false))
                .andExpect(jsonPath("$.revokedAt").doesNotExist());
    }

    @Test
    void createCertificateRejectsInvalidDecimal() throws Exception {
        mvc.perform(post("/api/certificates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("INS-1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "1.1234567", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void createCertificateRejectsInvalidRangeAndBlankInstrument() throws Exception {
        mvc.perform(post("/api/certificates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("INS-1", "2027-01-01T00:00:00Z", "2026-01-01T00:00:00Z", "1", "0")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/certificates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "1", "0")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/certificates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("INS-1", "not-a-time", "2027-01-01T00:00:00Z", "1", "0")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void overlappingCertificateRejectedButAdjacentAllowed() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("INS-1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "1", "0")))
                .andExpect(status().isCreated());

        // 重叠：左闭右开区间相交 → 409
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("INS-1", "2026-06-01T00:00:00Z", "2027-06-01T00:00:00Z", "1", "0")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_OVERLAP"));

        // 相邻：端点相接合法 → 201
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("INS-1", "2027-01-01T00:00:00Z", "2028-01-01T00:00:00Z", "1", "0")))
                .andExpect(status().isCreated());

        // 不同仪器互不影响 → 201
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("INS-2", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "1", "0")))
                .andExpect(status().isCreated());
    }

    @Test
    void revokedCertificateDoesNotBlockOverlap() throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("INS-1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "1", "0")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();

        mvc.perform(post("/api/certificates/{id}/revoke", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.revokedAt").isString());

        // 撤销后同区间可重新创建
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("INS-1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "2", "0")))
                .andExpect(status().isCreated());
    }

    @Test
    void revokeUnknownReturns404AndDoubleRevokeReturns409() throws Exception {
        mvc.perform(post("/api/certificates/{id}/revoke", 999999))
                .andExpect(status().isNotFound());

        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("INS-1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "1", "0")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();

        mvc.perform(post("/api/certificates/{id}/revoke", id)).andExpect(status().isOk());
        mvc.perform(post("/api/certificates/{id}/revoke", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REVOKED"));
    }

    @Test
    void getUnknownCertificateReturns404() throws Exception {
        mvc.perform(get("/api/certificates/{id}", 999999))
                .andExpect(status().isNotFound());
    }
}

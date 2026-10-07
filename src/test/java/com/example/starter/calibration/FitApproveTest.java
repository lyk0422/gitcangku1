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
 * 拟合批准测试：另一名放行人显式批准、批准时复核不重叠、一次创建不可变证书、
 * 已批准只重放首次证书、拟合历史不被撤证改写、原测量与批量放行使用拟合证书。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FitApproveTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM reference_fit_point");
        jdbc.update("DELETE FROM reference_fit");
        jdbc.update("DELETE FROM fit_request");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long submitFit(String requestId, String instrument) throws Exception {
        String body = mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","points":[{"x":"0","y":"1"},
                                 {"x":"1","y":"3"},{"x":"2","y":"5"}],"residualLimit":"0",
                                 "expectedVersion":1,"submittedBy":"alice"}""".formatted(requestId, instrument)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @Test
    void approveCreatesImmutableCertificateUsedByMeasurementAndRelease() throws Exception {
        long fitId = submitFit("REQ-A1", "INS-A1");
        String approved = mvc.perform(post("/api/fits/{id}/approve", fitId)
                        .header("X-Actor-Id", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value("bob"))
                .andExpect(jsonPath("$.certificateId").isNumber())
                .andReturn().getResponse().getContentAsString();
        long certId = ((Number) com.jayway.jsonpath.JsonPath.read(approved, "$.certificateId")).longValue();

        // 证书使用拟合系数 a=2、b=1，不可变且未撤销
        mvc.perform(get("/api/certificates/{id}", certId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instrumentId").value("INS-A1"))
                .andExpect(jsonPath("$.a").value("2"))
                .andExpect(jsonPath("$.b").value("1"))
                .andExpect(jsonPath("$.revoked").value(false));

        // 原测量匹配该证书：2×4+1=9 合格
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-A1","instrumentId":"INS-A1",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"4",
                                 "lowerLimit":"0","upperLimit":"10","submittedBy":"alice"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.certificateId").value(certId))
                .andExpect(jsonPath("$.computedValue").value("9"))
                .andExpect(jsonPath("$.passed").value(true));

        // 批量放行使用该证书（放行人与提交人不同）
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"M-A1\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/measurements/usable").param("instrumentId", "INS-A1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].computedValue").value("9"));
    }

    @Test
    void approveBySubmitterRejected409() throws Exception {
        long fitId = submitFit("REQ-A2", "INS-A2");
        mvc.perform(post("/api/fits/{id}/approve", fitId)
                        .header("X-Actor-Id", "alice"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAME_ACTOR"));
        assertEquals(0, count("calibration_certificate"));
        mvc.perform(get("/api/fits/{id}", fitId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void approveOrGetUnknownFitReturns404() throws Exception {
        mvc.perform(post("/api/fits/{id}/approve", 999999)
                        .header("X-Actor-Id", "bob"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/fits/{id}", 999999))
                .andExpect(status().isNotFound());
    }

    @Test
    void approvedFitReplaysFirstCertificateOnly() throws Exception {
        long fitId = submitFit("REQ-A3", "INS-A3");
        String first = mvc.perform(post("/api/fits/{id}/approve", fitId)
                        .header("X-Actor-Id", "bob"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long certId = ((Number) com.jayway.jsonpath.JsonPath.read(first, "$.certificateId")).longValue();

        // 再次批准（含另一名放行人）：只重放首次证书，不再签发
        mvc.perform(post("/api/fits/{id}/approve", fitId)
                        .header("X-Actor-Id", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificateId").value(certId))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(post("/api/fits/{id}/approve", fitId)
                        .header("X-Actor-Id", "dave"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificateId").value(certId));
        assertEquals(1, count("calibration_certificate"));
    }

    @Test
    void approveRechecksOverlapAndConflictCreatesNothing() throws Exception {
        long fitId = submitFit("REQ-A4", "INS-A4");
        // 拟合提交后、批准前，同仪器被直接登记了重叠证书
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-A4","validFrom":"2026-06-01T00:00:00Z",
                                 "validTo":"2027-06-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());

        // 批准时仍核验不重叠：冲突整次不建证书，拟合保持待批准
        mvc.perform(post("/api/fits/{id}/approve", fitId)
                        .header("X-Actor-Id", "bob"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_OVERLAP"));
        assertEquals(1, count("calibration_certificate"));
        mvc.perform(get("/api/fits/{id}", fitId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.certificateId").doesNotExist());

        // 撤销冲突证书后批准成功
        long blockingId = ((Number) jdbc.queryForObject(
                "SELECT id FROM calibration_certificate WHERE instrument_id = 'INS-A4'", Long.class)).longValue();
        mvc.perform(post("/api/certificates/{id}/revoke", blockingId))
                .andExpect(status().isOk());
        mvc.perform(post("/api/fits/{id}/approve", fitId)
                        .header("X-Actor-Id", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        assertEquals(2, count("calibration_certificate"));
    }

    @Test
    void fitHistorySurvivesCertificateRevocation() throws Exception {
        long fitId = submitFit("REQ-A5", "INS-A5");
        String approved = mvc.perform(post("/api/fits/{id}/approve", fitId)
                        .header("X-Actor-Id", "bob"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long certId = ((Number) com.jayway.jsonpath.JsonPath.read(approved, "$.certificateId")).longValue();

        mvc.perform(post("/api/certificates/{id}/revoke", certId))
                .andExpect(status().isOk());

        // 拟合历史不被撤证改写：状态、证书引用、冻结参考点与评分保持
        mvc.perform(get("/api/fits/{id}", fitId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.certificateId").value(certId))
                .andExpect(jsonPath("$.approvedBy").value("bob"))
                .andExpect(jsonPath("$.a").value("2"))
                .andExpect(jsonPath("$.b").value("1"))
                .andExpect(jsonPath("$.points.length()").value(3))
                .andExpect(jsonPath("$.points[1].residual").value("0"));
    }

    @Test
    void approveWithRequestIdReplaysAndConflicts() throws Exception {
        long fitId = submitFit("REQ-A6", "INS-A6");
        String first = mvc.perform(post("/api/fits/{id}/approve", fitId)
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"AP-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long certId = ((Number) com.jayway.jsonpath.JsonPath.read(first, "$.certificateId")).longValue();

        // 同 requestId 同参（同拟合同批准人）→ 重放首次批准响应
        mvc.perform(post("/api/fits/{id}/approve", fitId)
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"AP-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificateId").value(certId));
        assertEquals(1, count("calibration_certificate"));

        // 同 requestId 用于另一拟合的批准 → 异参 409
        long otherFit = submitFit("REQ-A7", "INS-A7");
        mvc.perform(post("/api/fits/{id}/approve", otherFit)
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"AP-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }
}

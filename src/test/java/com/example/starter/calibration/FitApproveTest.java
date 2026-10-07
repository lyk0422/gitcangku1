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
 * 拟合批准接口测试：批准创建不可变证书、权限（批准人≠提交人）、区间不重叠复核、
 * 重复批准只重放首次证书、撤证不改写拟合历史、批准后测量与批量放行使用该证书。
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
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM reference_fit");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long submitFit(String requestId, String instrument, String submittedBy) throws Exception {
        String body = mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(FitApiTest.fitJson(requestId, instrument, "0",
                                "{\"x\":\"0\",\"y\":\"1\"},{\"x\":\"1\",\"y\":\"3\"},{\"x\":\"2\",\"y\":\"5\"}",
                                submittedBy)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private String approve(long fitId, String requestId, String actor) throws Exception {
        return mvc.perform(post("/api/fits/{id}/approve", fitId)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void approveCreatesImmutableCertificateAndReplayDoesNotIssueAgain() throws Exception {
        long fitId = submitFit("REQ-A1", "INS-A1", "alice");

        // 提交人不能批准自己的拟合 → 409，且失败不占键
        mvc.perform(post("/api/fits/{id}/approve", fitId)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-A1-AP\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAME_ACTOR"));

        // 失败不占键：同一 requestId 换放行人可继续批准
        String approved = mvc.perform(post("/api/fits/{id}/approve", fitId)
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-A1-AP\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value("bob"))
                .andReturn().getResponse().getContentAsString();
        long certId = ((Number) com.jayway.jsonpath.JsonPath.read(approved, "$.certificateId")).longValue();

        // 证书已登记且系数来自拟合（a=2、b=1），不是调用方给的
        mvc.perform(get("/api/certificates/{id}", certId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.a").value("2"))
                .andExpect(jsonPath("$.b").value("1"))
                .andExpect(jsonPath("$.revoked").value(false));

        // 已成功批准键再次批准（新 requestId）：只重放首次证书，不再签一张
        String replayed = approve(fitId, "REQ-A1-AP2", "carol");
        long replayedCertId = ((Number) com.jayway.jsonpath.JsonPath.read(replayed, "$.certificateId")).longValue();
        assertEquals(certId, replayedCertId);
        Integer certCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_certificate WHERE instrument_id = 'INS-A1'", Integer.class);
        assertEquals(1, certCount, "重复批准不得再次签发证书");

        // 撤证后拟合历史不被改写：状态与证书登记保持
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
        mvc.perform(get("/api/fits/{id}", fitId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.certificateId").value((int) certId))
                .andExpect(jsonPath("$.a").value("2"))
                .andExpect(jsonPath("$.b").value("1"));
    }

    @Test
    void approveRechecksOverlapAndRejectsWholeApproval() throws Exception {
        // 先直接创建占区间的证书
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-A2","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());
        long fitId = submitFit("REQ-A2", "INS-A2", "alice");

        // 批准时复核不重叠规则：冲突 → 409，整次不建证书
        mvc.perform(post("/api/fits/{id}/approve", fitId)
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-A2-AP\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_OVERLAP"));

        mvc.perform(get("/api/fits/{id}", fitId))
                .andExpect(jsonPath("$.status").value("PENDING"));
        Integer certCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_certificate WHERE instrument_id = 'INS-A2'", Integer.class);
        assertEquals(1, certCount, "冲突时不得创建新证书");
    }

    @Test
    void measurementAndReleaseUseFittedCertificate() throws Exception {
        long fitId = submitFit("REQ-A3", "INS-A3", "alice");
        String approved = approve(fitId, "REQ-A3-AP", "bob");
        long certId = ((Number) com.jayway.jsonpath.JsonPath.read(approved, "$.certificateId")).longValue();

        // 原测量使用拟合证书：computed = 2×2+1 = 5
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-A3","instrumentId":"INS-A3",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"2",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.certificateId").value((int) certId))
                .andExpect(jsonPath("$.computedValue").value("5"))
                .andExpect(jsonPath("$.passed").value(true));

        // 批量放行沿用原约定（放行人≠提交人）
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"M-A3\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/measurements/usable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].measurementKey").value("M-A3"));
    }

    @Test
    void approveUnknownFitReturns404() throws Exception {
        mvc.perform(post("/api/fits/{id}/approve", 999999)
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-A9\"}"))
                .andExpect(status().isNotFound());
    }
}

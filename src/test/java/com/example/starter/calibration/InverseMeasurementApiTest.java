package com.example.starter.calibration;

import java.math.BigDecimal;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 二次反解测量测试：合成样例 y=x² 分支 [1,3] 信号 4 反解 2；
 * 合格判定用端点函数值精确比较（显示 2.0000 也不得放行不合格根）；
 * 信号越界 422、非二次证书 400、期望区间越出输入域 400、放行门禁与混批原子性。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InverseMeasurementApiTest {

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

    private void createQuadCert(String requestId, String instrument, String a, String b, String c,
                                String lo, String hi) throws Exception {
        mvc.perform(post("/api/certificates/quadratic").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","operator":"metrology","instrumentId":"%s",
                                 "validFrom":"2026-01-01T00:00:00Z","validTo":"2028-01-01T00:00:00Z",
                                 "a":"%s","b":"%s","c":"%s","domainLo":"%s","domainHi":"%s"}
                                """.formatted(requestId, instrument, a, b, c, lo, hi)))
                .andExpect(status().isCreated());
    }

    private void createLinearCert(String instrument) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated());
    }

    private String inverseJson(String requestId, String key, String instrument, String signal,
                               String lo, String hi, String by) {
        return """
                {"requestId":"%s","measurementKey":"%s","instrumentId":"%s",
                 "measuredAt":"2026-06-01T00:00:00Z","signal":"%s",
                 "lowerLimit":"%s","upperLimit":"%s","submittedBy":"%s"}
                """.formatted(requestId, key, instrument, signal, lo, hi, by);
    }

    @Test
    void syntheticCaseSignalFourInvertsToTwoAndPasses() throws Exception {
        // 合成样例：y=x²，分支 [1,3]，信号 4 → 反解 2；合格域 [2,2] 允许
        createQuadCert("QC-1", "INS-Q", "1", "0", "0", "1", "3");
        String body = mvc.perform(post("/api/measurements/inverse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inverseJson("RQ-1", "M-Q1", "INS-Q", "4", "2", "2", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.certificateType").value("QUADRATIC"))
                .andExpect(jsonPath("$.reading").value("4"))
                .andExpect(jsonPath("$.computedValue").value("2"))
                .andExpect(jsonPath("$.displayValue").value("2.0000"))
                .andExpect(jsonPath("$.evalLo").value("4"))
                .andExpect(jsonPath("$.evalHi").value("4"))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        // 夹逼区间包含根 2 且宽度不大于 0.000001
        BigDecimal rootLower = new BigDecimal(
                (String) com.jayway.jsonpath.JsonPath.read(body, "$.rootLower"));
        BigDecimal rootUpper = new BigDecimal(
                (String) com.jayway.jsonpath.JsonPath.read(body, "$.rootUpper"));
        assertTrue(rootLower.compareTo(new BigDecimal("2")) <= 0
                && rootUpper.compareTo(new BigDecimal("2")) >= 0, "夹逼区间必须包含根");
        assertTrue(rootUpper.subtract(rootLower).compareTo(new BigDecimal("0.000001")) <= 0,
                "夹逼区间宽度必须不大于 0.000001");
    }

    @Test
    void passJudgmentUsesExactEndpointValuesNotApproximateRoot() throws Exception {
        // 信号 4.000001：根 ≈ 2.00000025，显示 2.0000，但精确判定根 ∉ [2,2] → 不合格，不得放行
        createQuadCert("QC-2", "INS-Q", "1", "0", "0", "1", "3");
        mvc.perform(post("/api/measurements/inverse").contentType(MediaType.APPLICATION_JSON)
                        .content(inverseJson("RQ-2", "M-Q2", "INS-Q", "4.000001", "2", "2", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayValue").value("2.0000"))
                .andExpect(jsonPath("$.evalLo").value("4"))
                .andExpect(jsonPath("$.evalHi").value("4"))
                .andExpect(jsonPath("$.passed").value(false));

        // 放行人不同于提交人，但因不合格整批拒绝
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"M-Q2\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("NOT_PASSED"));
        mvc.perform(get("/api/measurements/{key}", "M-Q2"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void signalOutsideEndpointValuesReturns422() throws Exception {
        createQuadCert("QC-3", "INS-Q", "1", "0", "0", "1", "3");
        // f(1)=1，信号 0.5 低于下端点函数值 → 422
        mvc.perform(post("/api/measurements/inverse").contentType(MediaType.APPLICATION_JSON)
                        .content(inverseJson("RQ-3", "M-Q3", "INS-Q", "0.5", "1", "3", "alice")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SIGNAL_OUT_OF_RANGE"));
        // f(3)=9，信号 10 高于上端点函数值 → 422
        mvc.perform(post("/api/measurements/inverse").contentType(MediaType.APPLICATION_JSON)
                        .content(inverseJson("RQ-4", "M-Q4", "INS-Q", "10", "1", "3", "alice")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SIGNAL_OUT_OF_RANGE"));
        // 失败不占键：同一 requestId 修正参数后成功
        mvc.perform(post("/api/measurements/inverse").contentType(MediaType.APPLICATION_JSON)
                        .content(inverseJson("RQ-3", "M-Q3", "INS-Q", "4", "2", "2", "alice")))
                .andExpect(status().isCreated());
    }

    @Test
    void expectedIntervalMustBeInsideCertificateDomain() throws Exception {
        createQuadCert("QC-4", "INS-Q", "1", "0", "0", "1", "3");
        mvc.perform(post("/api/measurements/inverse").contentType(MediaType.APPLICATION_JSON)
                        .content(inverseJson("RQ-5", "M-Q5", "INS-Q", "4", "0.5", "2", "alice")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/measurements/inverse").contentType(MediaType.APPLICATION_JSON)
                        .content(inverseJson("RQ-6", "M-Q6", "INS-Q", "4", "2", "3.5", "alice")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void noCertificateOrLinearCertificateRejected() throws Exception {
        createLinearCert("INS-L");
        createQuadCert("QC-5", "INS-Q", "1", "0", "0", "1", "3");
        // 无匹配证书 → 422
        mvc.perform(post("/api/measurements/inverse").contentType(MediaType.APPLICATION_JSON)
                        .content(inverseJson("RQ-7", "M-Q7", "INS-X", "4", "2", "2", "alice")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_MATCHING_CERTIFICATE"));
        // 匹配到线性证书 → 400
        mvc.perform(post("/api/measurements/inverse").contentType(MediaType.APPLICATION_JSON)
                        .content(inverseJson("RQ-8", "M-Q8", "INS-L", "4", "2", "2", "alice")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CERT_TYPE_MISMATCH"));
    }

    @Test
    void decreasingBranchAndEndpointRoot() throws Exception {
        // 严格递减分支：y = -x²，[1,3]，f(1)=-1，f(3)=-9；信号 -4 → 根 2
        createQuadCert("QC-6", "INS-D", "-1", "0", "0", "1", "3");
        mvc.perform(post("/api/measurements/inverse").contentType(MediaType.APPLICATION_JSON)
                        .content(inverseJson("RQ-9", "M-Q9", "INS-D", "-4", "2", "2", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayValue").value("2.0000"))
                .andExpect(jsonPath("$.evalLo").value("-4"))
                .andExpect(jsonPath("$.passed").value(true));
        // 端点根：信号 -9 → 根 3，合格域 [3,3] 允许
        mvc.perform(post("/api/measurements/inverse").contentType(MediaType.APPLICATION_JSON)
                        .content(inverseJson("RQ-10", "M-Q10", "INS-D", "-9", "3", "3", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayValue").value("3.0000"))
                .andExpect(jsonPath("$.passed").value(true));
        // 信号恰在端点函数值之外一点点 → 422
        mvc.perform(post("/api/measurements/inverse").contentType(MediaType.APPLICATION_JSON)
                        .content(inverseJson("RQ-11", "M-Q11", "INS-D", "-9.000001", "1", "3", "alice")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void duplicateMeasurementKeyReturns409() throws Exception {
        createQuadCert("QC-7", "INS-Q", "1", "0", "0", "1", "3");
        mvc.perform(post("/api/measurements/inverse").contentType(MediaType.APPLICATION_JSON)
                        .content(inverseJson("RQ-12", "M-Q12", "INS-Q", "4", "2", "2", "alice")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/measurements/inverse").contentType(MediaType.APPLICATION_JSON)
                        .content(inverseJson("RQ-13", "M-Q12", "INS-Q", "4", "2", "2", "alice")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_MEASUREMENT_KEY"));
    }

    @Test
    void mixedLinearQuadraticBatchIsAtomic() throws Exception {
        createLinearCert("INS-L");
        createQuadCert("QC-8", "INS-Q", "1", "0", "0", "1", "3");
        // 线性合格测量
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-L1","instrumentId":"INS-L",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}"""))
                .andExpect(status().isCreated());
        // 二次不合格测量（根在合格域外）
        mvc.perform(post("/api/measurements/inverse").contentType(MediaType.APPLICATION_JSON)
                        .content(inverseJson("RQ-14", "M-Q14", "INS-Q", "4.000001", "2", "2", "bob")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passed").value(false));

        // 混批：任一失败全批回滚，线性合格测量也不得部分放行
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"M-L1\",\"M-Q14\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_REJECTED"));
        mvc.perform(get("/api/measurements/{key}", "M-L1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(0, releaseCount, "混批失败不得产生任何放行记录");

        // 单独放行线性合格测量 → 成功
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"M-L1\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    void releasedInverseMeasurementKeepsFrozenValuesAfterRevoke() throws Exception {
        createQuadCert("QC-9", "INS-Q", "1", "0", "0", "1", "3");
        mvc.perform(post("/api/measurements/inverse").contentType(MediaType.APPLICATION_JSON)
                        .content(inverseJson("RQ-15", "M-Q15", "INS-Q", "4", "2", "2", "alice")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"M-Q15\"]}"))
                .andExpect(status().isOk());

        Long certId = jdbc.queryForObject(
                "SELECT certificate_id FROM measurement WHERE measurement_key = 'M-Q15'", Long.class);
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());

        // 历史展示不随撤证重算：冻结的根、合格依据与放行历史保留
        mvc.perform(get("/api/measurements/{key}", "M-Q15"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.computedValue").value("2"))
                .andExpect(jsonPath("$.evalLo").value("4"))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("carol"));
    }
}

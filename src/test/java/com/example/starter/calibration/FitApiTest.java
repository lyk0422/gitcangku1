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
 * 拟合提交接口测试：精确系数主流程、残差上限、系数可表示性、参数校验与失败不占键。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FitApiTest {

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

    static String fitJson(String requestId, String instrument, String maxResidual,
                          String points, String submittedBy) {
        return """
                {"requestId":"%s","instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                 "validTo":"2027-01-01T00:00:00Z","maxResidual":"%s","points":[%s],
                 "submittedBy":"%s"}
                """.formatted(requestId, instrument, maxResidual, points, submittedBy);
    }

    @Test
    void submitSyntheticLineReturnsExactCoefficients() throws Exception {
        // 题干合成数据：(0,1)、(1,3)、(2,5) → a=2、b=1，残差 0，可签发
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-1", "INS-F1", "0",
                                "{\"x\":\"0\",\"y\":\"1\"},{\"x\":\"1\",\"y\":\"3\"},{\"x\":\"2\",\"y\":\"5\"}",
                                "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.a").value("2"))
                .andExpect(jsonPath("$.b").value("1"))
                .andExpect(jsonPath("$.maxAbsResidual").value("0"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.points.length()").value(3))
                .andExpect(jsonPath("$.points[2].residual").value("0"))
                .andExpect(jsonPath("$.submittedBy").value("alice"));
    }

    @Test
    void residualAboveLimitRejectedAndNothingPersisted() throws Exception {
        // 题干用例：末点改 5.1 且上限 0 → 拒绝，不生成证书
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-2", "INS-F2", "0",
                                "{\"x\":\"0\",\"y\":\"1\"},{\"x\":\"1\",\"y\":\"3\"},{\"x\":\"2\",\"y\":\"5.1\"}",
                                "alice")))
                .andExpect(status().isUnprocessableEntity());
        // 残差明确超限分支：最大绝对残差 0.2 > 上限 0.1 → 422 RESIDUAL_LIMIT_EXCEEDED
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-3", "INS-F2", "0.1",
                                "{\"x\":\"0\",\"y\":\"0\"},{\"x\":\"1\",\"y\":\"1\"},"
                                        + "{\"x\":\"2\",\"y\":\"2\"},{\"x\":\"3\",\"y\":\"3.5\"}",
                                "alice")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RESIDUAL_LIMIT_EXCEEDED"));

        assertEquals(0, count("reference_fit"), "失败请求不得写入拟合记录");
        assertEquals(0, count("calibration_certificate"), "失败请求不得生成证书");
        assertEquals(0, count("idempotency_record"), "失败请求不得占用幂等键");
    }

    @Test
    void residualEqualToLimitAccepted() throws Exception {
        // 最大绝对残差 0.2 恰好等于上限 0.2 → 边界可签发
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-4", "INS-F3", "0.2",
                                "{\"x\":\"0\",\"y\":\"0\"},{\"x\":\"1\",\"y\":\"1\"},"
                                        + "{\"x\":\"2\",\"y\":\"2\"},{\"x\":\"3\",\"y\":\"3.5\"}",
                                "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.a").value("1.15"))
                .andExpect(jsonPath("$.b").value("-0.1"))
                .andExpect(jsonPath("$.maxAbsResidual").value("0.2"));
    }

    @Test
    void nonRepresentableCoefficientsRejected422() throws Exception {
        // a = 5/14 不可精确表示 → 422
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-5", "INS-F4", "1",
                                "{\"x\":\"0\",\"y\":\"0\"},{\"x\":\"1\",\"y\":\"0\"},{\"x\":\"3\",\"y\":\"1\"}",
                                "alice")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COEFFICIENT_NOT_REPRESENTABLE"));
        // b = -1/6 不可精确表示 → 422
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-6", "INS-F4", "1",
                                "{\"x\":\"0\",\"y\":\"0\"},{\"x\":\"1\",\"y\":\"1\"},{\"x\":\"2\",\"y\":\"3\"}",
                                "alice")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COEFFICIENT_NOT_REPRESENTABLE"));
        assertEquals(0, count("reference_fit"));
    }

    @Test
    void invalidInputRejected400() throws Exception {
        String three = "{\"x\":\"0\",\"y\":\"1\"},{\"x\":\"1\",\"y\":\"3\"},{\"x\":\"2\",\"y\":\"5\"}";
        // 点数不足 3
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("R-1", "INS-V", "0", "{\"x\":\"0\",\"y\":\"1\"},{\"x\":\"1\",\"y\":\"3\"}", "alice")))
                .andExpect(status().isBadRequest());
        // 点数超过 8
        String nine = "{\"x\":\"0\",\"y\":\"0\"},".repeat(8) + "{\"x\":\"8\",\"y\":\"0\"}";
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("R-2", "INS-V", "1", nine, "alice")))
                .andExpect(status().isBadRequest());
        // 重复 x（0 与 0.0 按值重复）
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("R-3", "INS-V", "0",
                                "{\"x\":\"0\",\"y\":\"1\"},{\"x\":\"0.0\",\"y\":\"2\"},{\"x\":\"1\",\"y\":\"3\"}", "alice")))
                .andExpect(status().isBadRequest());
        // 负残差上限
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("R-4", "INS-V", "-0.1", three, "alice")))
                .andExpect(status().isBadRequest());
        // 空提交人
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("R-5", "INS-V", "0", three, "")))
                .andExpect(status().isBadRequest());
        // 空 requestId
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("", "INS-V", "0", three, "alice")))
                .andExpect(status().isBadRequest());
        // 参考点超过 6 位小数
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("R-6", "INS-V", "0",
                                "{\"x\":\"0\",\"y\":\"1\"},{\"x\":\"1\",\"y\":\"3.0000001\"},{\"x\":\"2\",\"y\":\"5\"}",
                                "alice")))
                .andExpect(status().isBadRequest());
        assertEquals(0, count("reference_fit"));
        assertEquals(0, count("idempotency_record"));
    }

    @Test
    void getUnknownFitReturns404() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/fits/{id}", 999999))
                .andExpect(status().isNotFound());
    }

    private int count(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}

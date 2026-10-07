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
 * 参考点线性拟合提交接口测试：精确有理数拟合主流程、参数与系数校验（400/422）、
 * 残差上限拒绝、幂等占键（同参重放、异参 409、失败不占键）。
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
        jdbc.update("DELETE FROM reference_fit_point");
        jdbc.update("DELETE FROM reference_fit");
        jdbc.update("DELETE FROM fit_request");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private String fitJson(String requestId, String instrument, String limit,
                           String submittedBy, String... pairs) {
        StringBuilder pts = new StringBuilder();
        for (int i = 0; i < pairs.length; i++) {
            String[] xy = pairs[i].split(",");
            if (i > 0) {
                pts.append(',');
            }
            pts.append("{\"x\":\"").append(xy[0]).append("\",\"y\":\"").append(xy[1]).append("\"}");
        }
        return """
                {"requestId":"%s","instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                 "validTo":"2027-01-01T00:00:00Z","points":[%s],"residualLimit":"%s",
                 "expectedVersion":1,"submittedBy":"%s"}
                """.formatted(requestId, instrument, pts, limit, submittedBy);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @Test
    void syntheticLineFitsExactlyAndIsIssuable() throws Exception {
        // (0,1),(1,3),(2,5) → a=2, b=1，残差全 0，可签发
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-1", "INS-F1", "0", "alice", "0,1", "1,3", "2,5")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.requestId").value("REQ-1"))
                .andExpect(jsonPath("$.instrumentId").value("INS-F1"))
                .andExpect(jsonPath("$.a").value("2"))
                .andExpect(jsonPath("$.b").value("1"))
                .andExpect(jsonPath("$.maxAbsResidual").value("0"))
                .andExpect(jsonPath("$.residualLimit").value("0"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.submittedBy").value("alice"))
                .andExpect(jsonPath("$.expectedVersion").value(1))
                .andExpect(jsonPath("$.certificateId").doesNotExist())
                .andExpect(jsonPath("$.points.length()").value(3))
                .andExpect(jsonPath("$.points[0].x").value("0"))
                .andExpect(jsonPath("$.points[0].y").value("1"))
                .andExpect(jsonPath("$.points[0].residual").value("0"))
                .andExpect(jsonPath("$.points[2].residual").value("0"));
        assertEquals(1, count("reference_fit"));
        assertEquals(3, count("reference_fit_point"));
        assertEquals(1, count("fit_request"));
    }

    @Test
    void nonZeroResidualsWithinLimitAreAccepted() throws Exception {
        // (0,0),(1,1),(2,2),(3,2) → a=0.7, b=0.2，最大绝对残差 0.4 ≤ 上限 0.5
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-2", "INS-F2", "0.5", "alice", "0,0", "1,1", "2,2", "3,2")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.a").value("0.7"))
                .andExpect(jsonPath("$.b").value("0.2"))
                .andExpect(jsonPath("$.maxAbsResidual").value("0.4"))
                .andExpect(jsonPath("$.points[0].residual").value("-0.2"))
                .andExpect(jsonPath("$.points[2].residual").value("0.4"));
    }

    @Test
    void residualOverLimitRejectedAndNothingPersisted() throws Exception {
        // 同上数据但上限 0.3 < 0.4 → 422，不生成拟合/证书/幂等键
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-3", "INS-F3", "0.3", "alice", "0,0", "1,1", "2,2", "3,2")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RESIDUAL_LIMIT_EXCEEDED"));
        assertEquals(0, count("reference_fit"));
        assertEquals(0, count("reference_fit_point"));
        assertEquals(0, count("calibration_certificate"));
        assertEquals(0, count("fit_request"));
    }

    @Test
    void syntheticLastPointChangedTo51WithZeroLimitRejected() throws Exception {
        // 题干合成用例：末点改 5.1 且上限 0 → 拒绝（422），不生成证书
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-4", "INS-F4", "0", "alice", "0,1", "1,3", "2,5.1")))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, count("reference_fit"));
        assertEquals(0, count("calibration_certificate"));
        assertEquals(0, count("fit_request"));
    }

    @Test
    void nonTerminatingCoefficientRejected422() throws Exception {
        // (0,0),(1,0),(2,1) → a=0.5，b=-1/6 无限循环 → 422，不强行舍入
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-5", "INS-F5", "10", "alice", "0,0", "1,0", "2,1")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COEFFICIENT_NOT_REPRESENTABLE"));
        assertEquals(0, count("reference_fit"));
        assertEquals(0, count("fit_request"));
    }

    @Test
    void invalidInputRejected400() throws Exception {
        // 重复 x
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-6", "INS-F6", "1", "alice", "0,1", "0,2", "1,3")))
                .andExpect(status().isBadRequest());
        // 点数不足 3
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-7", "INS-F6", "1", "alice", "0,1", "1,3")))
                .andExpect(status().isBadRequest());
        // 点数超过 8
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-8", "INS-F6", "1", "alice",
                                "0,0", "1,1", "2,2", "3,3", "4,4", "5,5", "6,6", "7,7", "8,8")))
                .andExpect(status().isBadRequest());
        // 残差上限为负
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-9", "INS-F6", "-0.1", "alice", "0,1", "1,3", "2,5")))
                .andExpect(status().isBadRequest());
        // 超过 6 位小数
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-10", "INS-F6", "1", "alice", "0,1", "1.0000001,3", "2,5")))
                .andExpect(status().isBadRequest());
        // 缺少 requestId / submittedBy
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-F6","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","points":[{"x":"0","y":"1"},
                                 {"x":"1","y":"3"},{"x":"2","y":"5"}],"residualLimit":"0","submittedBy":"alice"}"""))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-11", "INS-F6", "1", " ", "0,1", "1,3", "2,5")))
                .andExpect(status().isBadRequest());
        assertEquals(0, count("reference_fit"));
        assertEquals(0, count("fit_request"));
    }

    @Test
    void sameRequestIdSameParamsReplaysFirstResponse() throws Exception {
        String body = fitJson("REQ-R1", "INS-F7", "0", "alice", "0,1", "1,3", "2,5");
        String first = mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, second, "同参重放必须返回首次完整响应");
        assertEquals(1, count("reference_fit"));
        assertEquals(1, count("fit_request"));
    }

    @Test
    void replayComparesDecimalsByValueAndTextVerbatim() throws Exception {
        // 首次：小数写法 1.0 / 上限 0.0 / 时间 +01:00 偏移
        String first = mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-R2","instrumentId":"INS-F8",
                                 "validFrom":"2026-01-01T01:00:00+01:00","validTo":"2027-01-01T00:00:00Z",
                                 "points":[{"x":"0.0","y":"1.0"},{"x":"1.0","y":"3.0"},{"x":"2.0","y":"5.0"}],
                                 "residualLimit":"0.0","expectedVersion":1,"submittedBy":"alice"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        // 重放：同值不同写法（1.00、0、UTC Z），应按值判定为同参并重放
        String second = mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-R2","instrumentId":"INS-F8",
                                 "validFrom":"2026-01-01T00:00:00Z","validTo":"2027-01-01T00:00:00Z",
                                 "points":[{"x":"0","y":"1.00"},{"x":"1","y":"3"},{"x":"2.00","y":"5"}],
                                 "residualLimit":"0","expectedVersion":1,"submittedBy":"alice"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
        assertEquals(1, count("reference_fit"));
        // 文本原样比较：提交人不同（文本不同）→ 409
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-R2","instrumentId":"INS-F8",
                                 "validFrom":"2026-01-01T00:00:00Z","validTo":"2027-01-01T00:00:00Z",
                                 "points":[{"x":"0","y":"1"},{"x":"1","y":"3"},{"x":"2","y":"5"}],
                                 "residualLimit":"0","expectedVersion":1,"submittedBy":"bob"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void sameRequestIdDifferentParamsConflict409() throws Exception {
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-R3", "INS-F9", "0", "alice", "0,1", "1,3", "2,5")))
                .andExpect(status().isCreated());
        // 期望版本不同也算异参
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-R3","instrumentId":"INS-F9",
                                 "validFrom":"2026-01-01T00:00:00Z","validTo":"2027-01-01T00:00:00Z",
                                 "points":[{"x":"0","y":"1"},{"x":"1","y":"3"},{"x":"2","y":"5"}],
                                 "residualLimit":"0","expectedVersion":2,"submittedBy":"alice"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        // 点位不同（仍为可签发的合法参数）→ 409
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-R3", "INS-F9", "0", "alice", "0,1", "1,3", "2,5", "3,7")))
                .andExpect(status().isConflict());
        assertEquals(1, count("reference_fit"));
    }

    @Test
    void failedSubmitDoesNotOccupyRequestId() throws Exception {
        // 首次残差超限 422 → 不占键
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-R4", "INS-F10", "0.3", "alice", "0,0", "1,1", "2,2", "3,2")))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, count("fit_request"));
        // 修正上限后同 requestId 可成功
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-R4", "INS-F10", "0.5", "alice", "0,0", "1,1", "2,2", "3,2")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.a").value("0.7"));
        assertEquals(1, count("reference_fit"));
        assertEquals(1, count("fit_request"));
    }
}
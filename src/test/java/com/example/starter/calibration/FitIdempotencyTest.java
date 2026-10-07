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
 * 拟合幂等去重测试：同参重放首次完整响应、异参 409、失败不占键、
 * 十进制按值比较、期望版本参与结构化比较。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FitIdempotencyTest {

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

    private String submit(String body) throws Exception {
        return mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void sameParamsReplayFirstFullResponse() throws Exception {
        String body = FitApiTest.fitJson("REQ-I1", "INS-I1", "0",
                "{\"x\":\"0\",\"y\":\"1\"},{\"x\":\"1\",\"y\":\"3\"},{\"x\":\"2\",\"y\":\"5\"}", "alice");
        String first = submit(body);
        String second = submit(body);
        assertEquals(first, second, "同参重放必须返回首次完整响应");
        assertEquals(1, count("reference_fit"), "重放不得重复写入拟合记录");
        assertEquals(1, count("idempotency_record"));

        // 十进制按值比较：1 与 1.0、0 与 0.00 视为同参 → 仍重放首次响应
        String equivalent = FitApiTest.fitJson("REQ-I1", "INS-I1", "0.00",
                "{\"x\":\"0.0\",\"y\":\"1.0\"},{\"x\":\"1\",\"y\":\"3\"},{\"x\":\"2\",\"y\":\"5\"}", "alice");
        assertEquals(first, submit(equivalent));
        assertEquals(1, count("reference_fit"));
    }

    @Test
    void differentParamsSameRequestIdConflict409() throws Exception {
        String body = FitApiTest.fitJson("REQ-I2", "INS-I2", "0",
                "{\"x\":\"0\",\"y\":\"1\"},{\"x\":\"1\",\"y\":\"3\"},{\"x\":\"2\",\"y\":\"5\"}", "alice");
        submit(body);

        // 文本原样比较：仪器不同 → 409
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(FitApiTest.fitJson("REQ-I2", "INS-I2X", "0",
                                "{\"x\":\"0\",\"y\":\"1\"},{\"x\":\"1\",\"y\":\"3\"},{\"x\":\"2\",\"y\":\"5\"}", "alice")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        // 参数值不同（y 3 → 3.5）→ 409
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(FitApiTest.fitJson("REQ-I2", "INS-I2", "0",
                                "{\"x\":\"0\",\"y\":\"1\"},{\"x\":\"1\",\"y\":\"3.5\"},{\"x\":\"2\",\"y\":\"5\"}", "alice")))
                .andExpect(status().isConflict());
        // 期望版本不同 → 409
        String withVersion = """
                {"requestId":"REQ-I2","instrumentId":"INS-I2","validFrom":"2026-01-01T00:00:00Z",
                 "validTo":"2027-01-01T00:00:00Z","maxResidual":"0",
                 "points":[{"x":"0","y":"1"},{"x":"1","y":"3"},{"x":"2","y":"5"}],
                 "submittedBy":"alice","expectedVersion":2}
                """;
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON).content(withVersion))
                .andExpect(status().isConflict());
        assertEquals(1, count("reference_fit"), "异参冲突不得产生新记录");
    }

    @Test
    void failedRequestDoesNotOccupyKey() throws Exception {
        // 首次以残差超限失败（422）→ 不占键
        String failing = FitApiTest.fitJson("REQ-I3", "INS-I3", "0",
                "{\"x\":\"0\",\"y\":\"1\"},{\"x\":\"1\",\"y\":\"3\"},{\"x\":\"2\",\"y\":\"5.1\"}", "alice");
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON).content(failing))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, count("idempotency_record"));

        // 同一 requestId 换合法参数可正常创建
        mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(FitApiTest.fitJson("REQ-I3", "INS-I3", "0",
                                "{\"x\":\"0\",\"y\":\"1\"},{\"x\":\"1\",\"y\":\"3\"},{\"x\":\"2\",\"y\":\"5\"}", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.a").value("2"));
        assertEquals(1, count("reference_fit"));
        assertEquals(1, count("idempotency_record"));
    }

    @Test
    void approveSameRequestIdReplaysFirstApproval() throws Exception {
        String fitBody = mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(FitApiTest.fitJson("REQ-I4", "INS-I4", "0",
                                "{\"x\":\"0\",\"y\":\"1\"},{\"x\":\"1\",\"y\":\"3\"},{\"x\":\"2\",\"y\":\"5\"}", "alice")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long fitId = ((Number) com.jayway.jsonpath.JsonPath.read(fitBody, "$.id")).longValue();

        String approveBody = "{\"requestId\":\"REQ-I4-AP\"}";
        String first = mvc.perform(post("/api/fits/{id}/approve", fitId)
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON).content(approveBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        // 同 requestId 同参重放首次完整响应（含首次证书 ID）
        String second = mvc.perform(post("/api/fits/{id}/approve", fitId)
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON).content(approveBody))
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
        // 同 requestId 换放行人（异参）→ 409
        mvc.perform(post("/api/fits/{id}/approve", fitId)
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(approveBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        Integer certCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_certificate WHERE instrument_id = 'INS-I4'", Integer.class);
        assertEquals(1, certCount, "批准重放不得再次签发证书");
    }

    private int count(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}

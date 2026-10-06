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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分辨率格点配置接口测试：新建（版本从 1 起）、expectedVersion 乐观更新、
 * 参数校验、requestId 幂等（同参重放、异参 409、失败不占键）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResolutionApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM instrument_resolution");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private String resolutionJson(String requestId, String origin, String step, Integer expectedVersion) {
        return """
                {"requestId":"%s","origin":"%s","step":"%s","expectedVersion":%s}
                """.formatted(requestId, origin, step, expectedVersion == null ? null : expectedVersion);
    }

    @Test
    void createStartsAtVersionOneAndUpdateBumpsVersion() throws Exception {
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-Q")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolutionJson("REQ-1", "0", "0.2", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.instrumentId").value("INS-Q"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.origin").value("0"))
                .andExpect(jsonPath("$.step").value("0.2"))
                .andExpect(jsonPath("$.updatedBy").value("dave"));

        // 负原点合法；携带 expectedVersion 更新到版本 2
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-Q")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolutionJson("REQ-2", "-0.5", "0.25", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.origin").value("-0.5"))
                .andExpect(jsonPath("$.step").value("0.25"));

        mvc.perform(get("/api/instruments/{id}/resolution", "INS-Q"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void validationRejectsBadStepAndPrecisionAndVersion() throws Exception {
        // 步长必须为正
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-Q")
                        .header("X-Actor-Id", "dave").contentType(MediaType.APPLICATION_JSON)
                        .content(resolutionJson("REQ-1", "0", "0", null)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-Q")
                        .header("X-Actor-Id", "dave").contentType(MediaType.APPLICATION_JSON)
                        .content(resolutionJson("REQ-2", "0", "-0.2", null)))
                .andExpect(status().isBadRequest());
        // 超过 6 位小数
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-Q")
                        .header("X-Actor-Id", "dave").contentType(MediaType.APPLICATION_JSON)
                        .content(resolutionJson("REQ-3", "0", "0.0000001", null)))
                .andExpect(status().isBadRequest());
        // expectedVersion 必须 >= 1
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-Q")
                        .header("X-Actor-Id", "dave").contentType(MediaType.APPLICATION_JSON)
                        .content(resolutionJson("REQ-4", "0", "0.2", 0)))
                .andExpect(status().isBadRequest());
        // 缺少 X-Actor-Id
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-Q")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolutionJson("REQ-5", "0", "0.2", null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createOnExistingAndVersionMismatchAndUpdateMissing() throws Exception {
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-Q")
                        .header("X-Actor-Id", "dave").contentType(MediaType.APPLICATION_JSON)
                        .content(resolutionJson("REQ-1", "0", "0.2", null)))
                .andExpect(status().isCreated());

        // 已存在配置时再次新建 → 409
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-Q")
                        .header("X-Actor-Id", "dave").contentType(MediaType.APPLICATION_JSON)
                        .content(resolutionJson("REQ-2", "0", "0.5", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOLUTION_ALREADY_EXISTS"));
        // 版本不匹配 → 409
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-Q")
                        .header("X-Actor-Id", "dave").contentType(MediaType.APPLICATION_JSON)
                        .content(resolutionJson("REQ-3", "0", "0.5", 7)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        // 更新未配置仪器 → 404
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-OTHER")
                        .header("X-Actor-Id", "dave").contentType(MediaType.APPLICATION_JSON)
                        .content(resolutionJson("REQ-4", "0", "0.5", 1)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/instruments/{id}/resolution", "INS-OTHER"))
                .andExpect(status().isNotFound());
    }

    @Test
    void sameRequestIdSameParamsReplaysFirstResponse() throws Exception {
        String body = resolutionJson("REQ-R", "0", "0.2", null);
        String first = mvc.perform(put("/api/instruments/{id}/resolution", "INS-Q")
                        .header("X-Actor-Id", "dave").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // 同键同参重放：返回首次完整响应，版本不变
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-Q")
                        .header("X-Actor-Id", "dave").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().json(first));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM instrument_resolution WHERE instrument_id = 'INS-Q'",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, count);
        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbc.queryForObject("SELECT version FROM instrument_resolution WHERE instrument_id = 'INS-Q'",
                        Integer.class));
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() throws Exception {
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-Q")
                        .header("X-Actor-Id", "dave").contentType(MediaType.APPLICATION_JSON)
                        .content(resolutionJson("REQ-C", "0", "0.2", null)))
                .andExpect(status().isCreated());
        // 异参 → 409
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-Q")
                        .header("X-Actor-Id", "dave").contentType(MediaType.APPLICATION_JSON)
                        .content(resolutionJson("REQ-C", "0", "0.5", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        // 异操作者 → 409
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-Q")
                        .header("X-Actor-Id", "erin").contentType(MediaType.APPLICATION_JSON)
                        .content(resolutionJson("REQ-C", "0", "0.2", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void failedRequestDoesNotConsumeRequestId() throws Exception {
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-Q")
                        .header("X-Actor-Id", "dave").contentType(MediaType.APPLICATION_JSON)
                        .content(resolutionJson("REQ-F", "0", "0.2", null)))
                .andExpect(status().isCreated());
        // 业务失败（重复新建）→ 409，且不占键
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-Q")
                        .header("X-Actor-Id", "dave").contentType(MediaType.APPLICATION_JSON)
                        .content(resolutionJson("REQ-F2", "0", "0.5", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOLUTION_ALREADY_EXISTS"));
        Integer records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'REQ-F2'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, records, "失败请求不得占用幂等键");
        // 同一 requestId 之后可用于其他资源的成功写入
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-Z")
                        .header("X-Actor-Id", "dave").contentType(MediaType.APPLICATION_JSON)
                        .content(resolutionJson("REQ-F2", "0", "0.5", null)))
                .andExpect(status().isCreated());
    }
}

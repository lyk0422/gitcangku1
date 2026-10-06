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
 * 分辨率格点配置接口测试：创建/更新版本语义、参数校验、
 * requestId 幂等（同参重放、异参 409、失败不占键）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResolutionConfigApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM resolution_config");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private String configJson(String requestId, String step, String origin, int expectedVersion) {
        return """
                {"requestId":"%s","step":"%s","origin":"%s","expectedVersion":%d}
                """.formatted(requestId, step, origin, expectedVersion);
    }

    private org.springframework.test.web.servlet.ResultActions putConfig(
            String instrument, String requestId, String step, String origin, int expectedVersion)
            throws Exception {
        return mvc.perform(put("/api/instruments/{id}/resolution", instrument)
                .header("X-Actor-Id", "dave")
                .contentType(MediaType.APPLICATION_JSON)
                .content(configJson(requestId, step, origin, expectedVersion)));
    }

    @Test
    void createReturns201AndVersion1ThenGetReturnsCurrent() throws Exception {
        putConfig("INS-C1", "REQ-1", "0.2", "0", 0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.instrumentId").value("INS-C1"))
                .andExpect(jsonPath("$.step").value("0.2"))
                .andExpect(jsonPath("$.origin").value("0"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.updatedBy").value("dave"));

        mvc.perform(get("/api/instruments/{id}/resolution", "INS-C1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("0.2"))
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(get("/api/instruments/{id}/resolution", "INS-UNKNOWN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateRequiresMatchingExpectedVersion() throws Exception {
        putConfig("INS-C2", "REQ-1", "0.2", "0", 0).andExpect(status().isCreated());
        // 已存在配置时再传 0 → 409
        putConfig("INS-C2", "REQ-2", "0.5", "0", 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        // 版本不匹配 → 409
        putConfig("INS-C2", "REQ-3", "0.5", "0", 3)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        // 版本匹配 → 200，版本 +1
        putConfig("INS-C2", "REQ-4", "0.5", "-0.1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("0.5"))
                .andExpect(jsonPath("$.origin").value("-0.1"))
                .andExpect(jsonPath("$.version").value(2));
        // 旧版本再次更新 → 409
        putConfig("INS-C2", "REQ-5", "0.7", "0", 1)
                .andExpect(status().isConflict());
        mvc.perform(get("/api/instruments/{id}/resolution", "INS-C2"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void invalidParamsReturn400() throws Exception {
        // 步长为 0 / 负数
        putConfig("INS-C3", "REQ-1", "0", "0", 0).andExpect(status().isBadRequest());
        putConfig("INS-C3", "REQ-2", "-0.2", "0", 0).andExpect(status().isBadRequest());
        // 小数位超过 6 位
        putConfig("INS-C3", "REQ-3", "0.1234567", "0", 0).andExpect(status().isBadRequest());
        // 缺少 requestId
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-C3")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"step\":\"0.2\",\"origin\":\"0\",\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());
        // 缺少 X-Actor-Id
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-C3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson("REQ-4", "0.2", "0", 0)))
                .andExpect(status().isBadRequest());
        // 负期望版本
        putConfig("INS-C3", "REQ-5", "0.2", "0", -1).andExpect(status().isBadRequest());
        // 全部失败不占键：同 requestId 修正参数后可成功
        putConfig("INS-C3", "REQ-1", "0.2", "0", 0).andExpect(status().isCreated());
    }

    @Test
    void sameRequestIdSameParamsReplaysFirstResponse() throws Exception {
        putConfig("INS-C4", "REQ-R", "0.2", "0", 0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        // 同键同参重放：返回首次完整响应，不产生新版本
        putConfig("INS-C4", "REQ-R", "0.2", "0", 0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.step").value("0.2"));
        mvc.perform(get("/api/instruments/{id}/resolution", "INS-C4"))
                .andExpect(jsonPath("$.version").value(1));
        Integer records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'REQ-R'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, records);
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() throws Exception {
        putConfig("INS-C5", "REQ-X", "0.2", "0", 0).andExpect(status().isCreated());
        // 同键异参 → 409
        putConfig("INS-C5", "REQ-X", "0.5", "0", 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        // 同键但操作者不同（身份含操作者）→ 409
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-C5")
                        .header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson("REQ-X", "0.2", "0", 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void failedRequestDoesNotOccupyKey() throws Exception {
        // 版本冲突失败不占键
        putConfig("INS-C6", "REQ-F", "0.2", "0", 5)
                .andExpect(status().isConflict());
        putConfig("INS-C6", "REQ-F", "0.2", "0", 0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        // 更新重放：同键同参返回首响（200，版本 2），不再次递增
        putConfig("INS-C6", "REQ-U", "0.5", "0", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        putConfig("INS-C6", "REQ-U", "0.5", "0", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/instruments/{id}/resolution", "INS-C6"))
                .andExpect(jsonPath("$.version").value(2));
    }
}

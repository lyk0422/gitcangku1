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
 * 仪器不确定度阈值接口测试：版本化登记、当前/历史查询、
 * 阈值缺失或非法 422、幂等重放与异参冲突、失败不占键。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ThresholdApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM budget_revision_component");
        jdbc.update("DELETE FROM budget_revision");
        jdbc.update("DELETE FROM uncertainty_component");
        jdbc.update("DELETE FROM instrument_threshold");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    @Test
    void registerIncrementsVersionAndHistoryIsOrdered() throws Exception {
        mvc.perform(put("/api/instruments/{id}/threshold", "INS-T")
                        .header("X-Actor-Id", "op1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"t-1\",\"threshold\":\"0.5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instrumentId").value("INS-T"))
                .andExpect(jsonPath("$.threshold").value("0.5"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.createdBy").value("op1"))
                .andExpect(jsonPath("$.requestId").value("t-1"));

        mvc.perform(put("/api/instruments/{id}/threshold", "INS-T")
                        .header("X-Actor-Id", "op2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"t-2\",\"threshold\":\"0.25\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(get("/api/instruments/{id}/threshold", "INS-T"))
                .andExpect(jsonPath("$.threshold").value("0.25"))
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(get("/api/instruments/{id}/thresholds", "INS-T"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].threshold").value("0.5"))
                .andExpect(jsonPath("$[1].version").value(2))
                .andExpect(jsonPath("$[1].threshold").value("0.25"));
    }

    @Test
    void missingOrInvalidThresholdReturns422() throws Exception {
        // 阈值缺失
        mvc.perform(put("/api/instruments/{id}/threshold", "INS-T")
                        .header("X-Actor-Id", "op")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"t-m\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("THRESHOLD_MISSING"));
        // 负数
        mvc.perform(put("/api/instruments/{id}/threshold", "INS-T")
                        .header("X-Actor-Id", "op")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"t-n\",\"threshold\":\"-0.1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_THRESHOLD"));
        // 小数位超过 6 位
        mvc.perform(put("/api/instruments/{id}/threshold", "INS-T")
                        .header("X-Actor-Id", "op")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"t-p\",\"threshold\":\"0.1234567\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_THRESHOLD"));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM instrument_threshold", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, count);
    }

    @Test
    void currentThresholdMissingReturns404() throws Exception {
        mvc.perform(get("/api/instruments/{id}/threshold", "INS-NONE"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/instruments/{id}/thresholds", "INS-NONE"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void idempotentReplayAndConflict() throws Exception {
        String body = "{\"requestId\":\"t-r\",\"threshold\":\"0.5\"}";
        String first = mvc.perform(put("/api/instruments/{id}/threshold", "INS-T")
                        .header("X-Actor-Id", "op")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // 同参重放：返回首次快照，不生成新版本
        mvc.perform(put("/api/instruments/{id}/threshold", "INS-T")
                        .header("X-Actor-Id", "op")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().json(first));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM instrument_threshold WHERE instrument_id = 'INS-T'",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, count);
        // 异参 → 409
        mvc.perform(put("/api/instruments/{id}/threshold", "INS-T")
                        .header("X-Actor-Id", "op")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"t-r\",\"threshold\":\"0.6\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void failedRequestDoesNotConsumeRequestId() throws Exception {
        mvc.perform(put("/api/instruments/{id}/threshold", "INS-T")
                        .header("X-Actor-Id", "op")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"t-f\",\"threshold\":\"-1\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/instruments/{id}/threshold", "INS-T")
                        .header("X-Actor-Id", "op")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"t-f\",\"threshold\":\"1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }
}

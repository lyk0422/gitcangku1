package com.example.starter.blackstart;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 接口测试：从合成计划（data.sql 初始化的 1 号计划）出发走完黑启动全流程，
 * 覆盖成功、读数拒绝、幂等重放、异参 409、校验失败与计划不存在。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BlackStartApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSyntheticPlan() {
        jdbcTemplate.update("DELETE FROM step_attempt");
        jdbcTemplate.update("DELETE FROM black_start_plan");
        jdbcTemplate.update("INSERT INTO black_start_plan (id, name, status, current_step, created_at)"
                + " VALUES (1, '合成黑启动计划-01', 'IN_PROGRESS', 1, TIMESTAMP '2026-01-01 00:00:00')");
    }

    private static String confirmBody(String requestId, String voltage, String frequency, String powerKw) {
        String power = powerKw == null ? "null" : powerKw;
        return "{\"requestId\":\"" + requestId + "\",\"voltage\":" + voltage
                + ",\"frequency\":" + frequency + ",\"powerKw\":" + power
                + ",\"deviceStatus\":\"NORMAL\"}";
    }

    @Test
    void fullBlackStartFlowOverHttp() throws Exception {
        mockMvc.perform(get("/api/black-start/plans/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.currentStep").value(1))
                .andExpect(jsonPath("$.steps", hasSize(0)));

        mockMvc.perform(post("/api/black-start/plans/1/steps/1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("http-1", "400", "50.0", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.currentStep").value(2))
                .andExpect(jsonPath("$.recordedAt", notNullValue()));

        mockMvc.perform(post("/api/black-start/plans/1/steps/2/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("http-2", "400", "50.6", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason", notNullValue()))
                .andExpect(jsonPath("$.currentStep").value(2));

        mockMvc.perform(post("/api/black-start/plans/1/steps/2/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("http-2", "400", "50.6", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.outcome").value("REJECTED"));

        mockMvc.perform(post("/api/black-start/plans/1/steps/2/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("http-2", "400", "50.1", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        mockMvc.perform(post("/api/black-start/plans/1/steps/2/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("http-3", "400", "50.1", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStep").value(3));

        mockMvc.perform(post("/api/black-start/plans/1/steps/3/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("http-4", "400", "50.0", "180")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planStatus").value("STARTED"));

        mockMvc.perform(get("/api/black-start/plans/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STARTED"))
                .andExpect(jsonPath("$.completedAt", notNullValue()))
                .andExpect(jsonPath("$.terminatedAt", nullValue()))
                .andExpect(jsonPath("$.steps", hasSize(4)))
                .andExpect(jsonPath("$.steps[1].outcome").value("REJECTED"))
                .andExpect(jsonPath("$.steps[1].rejectReason", notNullValue()));
    }

    @Test
    void terminateOverHttpMakesPlanReadOnly() throws Exception {
        mockMvc.perform(post("/api/black-start/plans/1/termination")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"演练中止\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"))
                .andExpect(jsonPath("$.terminatedAt", notNullValue()))
                .andExpect(jsonPath("$.terminationReason").value("演练中止"));

        mockMvc.perform(post("/api/black-start/plans/1/steps/1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("http-t1", "400", "50.0", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PLAN_TERMINATED"));
    }

    @Test
    void unknownPlanAndInvalidBodyReturnErrors() throws Exception {
        mockMvc.perform(get("/api/black-start/plans/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAN_NOT_FOUND"));

        mockMvc.perform(post("/api/black-start/plans/1/steps/1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voltage\":400,\"frequency\":50.0,\"deviceStatus\":\"NORMAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }
}
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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP 接口测试：状态码、查询视图内容与合成场景（第二步 50.6 Hz 停步后重试继续）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
class BlackStartApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM black_start_step_success");
        jdbc.update("DELETE FROM black_start_step_attempt");
        jdbc.update("DELETE FROM black_start_plan");
    }

    private long createPlan() throws Exception {
        MvcResult result = mvc.perform(post("/api/blackstart/plans"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.currentStep").value(1))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"planId\":(\\d+).*", "$1"));
    }

    private static String confirmBody(String requestId, double voltage, double frequency,
            String powerFragment) {
        return "{\"requestId\":\"" + requestId + "\",\"deviceStatus\":\"NORMAL\","
                + "\"voltageV\":" + voltage + ",\"frequencyHz\":" + frequency + powerFragment + "}";
    }

    @Test
    void httpFlowCoversSuccessRejectionReplayAndTerminate() throws Exception {
        long planId = createPlan();

        mvc.perform(post("/api/blackstart/plans/{id}/steps/1/confirm", planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("api-1", 400.0, 50.0, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.currentStep").value(2));

        String badFreq = confirmBody("api-2", 400.0, 50.6, "");
        mvc.perform(post("/api/blackstart/plans/{id}/steps/2/confirm", planId)
                        .contentType(MediaType.APPLICATION_JSON).content(badFreq))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason", notNullValue()))
                .andExpect(jsonPath("$.currentStep").value(2));

        // 同 requestId 同参重放返回首个结果；异参返回 409。
        mvc.perform(post("/api/blackstart/plans/{id}/steps/2/confirm", planId)
                        .contentType(MediaType.APPLICATION_JSON).content(badFreq))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.outcome").value("REJECTED"));
        mvc.perform(post("/api/blackstart/plans/{id}/steps/2/confirm", planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("api-2", 400.0, 50.0, "")))
                .andExpect(status().isConflict());

        // 重试合格读数后继续，第三步完成后计划已启动。
        mvc.perform(post("/api/blackstart/plans/{id}/steps/2/confirm", planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("api-3", 400.0, 50.0, "")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/blackstart/plans/{id}/steps/3/confirm", planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("api-4", 400.0, 50.0, ",\"powerKw\":180.0")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planStatus").value("STARTED"));

        mvc.perform(get("/api/blackstart/plans/{id}", planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STARTED"))
                .andExpect(jsonPath("$.completedAt", notNullValue()))
                .andExpect(jsonPath("$.terminatedAt", nullValue()))
                .andExpect(jsonPath("$.steps", hasSize(3)))
                .andExpect(jsonPath("$.steps[1].attempts", hasSize(2)))
                .andExpect(jsonPath("$.steps[1].attempts[0].rejectReason", notNullValue()))
                .andExpect(jsonPath("$.steps[2].attempts[0].powerKw").value(180.0));

        mvc.perform(post("/api/blackstart/plans/{id}/terminate", planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"已完成后再终止\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void terminateThenConfirmIsRejectedAndQueryShowsTermination() throws Exception {
        long planId = createPlan();
        mvc.perform(post("/api/blackstart/plans/{id}/terminate", planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"调度取消\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"))
                .andExpect(jsonPath("$.terminatedAt", notNullValue()))
                .andExpect(jsonPath("$.terminationReason").value("调度取消"));

        mvc.perform(post("/api/blackstart/plans/{id}/steps/1/confirm", planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("api-t1", 400.0, 50.0, "")))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/blackstart/plans/{id}", planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"))
                .andExpect(jsonPath("$.steps[0].attempts", hasSize(0)));
    }

    @Test
    void unknownPlanReturns404() throws Exception {
        mvc.perform(get("/api/blackstart/plans/{id}", 999999))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/blackstart/plans/{id}/steps/1/confirm", 999999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("api-x", 400.0, 50.0, "")))
                .andExpect(status().isNotFound());
    }
}
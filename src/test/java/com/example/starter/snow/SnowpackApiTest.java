package com.example.starter.snow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
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
 * HTTP 层测试：验证接口状态码（200/409/422）与响应体关键字段，
 * 包括合成最近三小时新增积雪 4、5、6 厘米时解除被拒的场景。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class SnowpackApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    private String region;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM unseal_decision");
        jdbc.update("DELETE FROM unseal_confirmation");
        jdbc.update("DELETE FROM unseal_review");
        jdbc.update("DELETE FROM reading_errata");
        jdbc.update("DELETE FROM snow_reading");
        jdbc.update("DELETE FROM closure_command");
        jdbc.update("DELETE FROM region_state");
        clock.setInstant(TestClockConfig.T0);
        region = "API-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String reqId() {
        return UUID.randomUUID().toString();
    }

    private void postReading(String hour, int newSnowCm) throws Exception {
        mvc.perform(post("/api/regions/{r}/readings", region)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + reqId() + "\",\"hourUtc\":\"" + hour
                                + "\",\"depthCm\":100,\"newSnowCm\":" + newSnowCm + "}"))
                .andExpect(status().isOk());
    }

    private void postClosure() throws Exception {
        mvc.perform(post("/api/regions/{r}/closures", region)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + reqId() + "\",\"reason\":\"storm\"}"))
                .andExpect(status().isOk());
    }

    private MvcResult postReview() throws Exception {
        return mvc.perform(post("/api/regions/{r}/unseal-reviews", region)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + reqId() + "\"}"))
                .andReturn();
    }

    @Test
    void windowFourFiveSixRejectedWith422() throws Exception {
        postClosure();
        postReading("2026-10-04T09:00:00Z", 4);
        postReading("2026-10-04T10:00:00Z", 5);
        postReading("2026-10-04T11:00:00Z", 6);
        mvc.perform(post("/api/regions/{r}/unseal-reviews", region)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + reqId() + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("OVER_LIMIT_IN_WINDOW"));
    }

    @Test
    void reviewOnOpenRegionReturns409() throws Exception {
        postReading("2026-10-04T09:00:00Z", 1);
        postReading("2026-10-04T10:00:00Z", 1);
        postReading("2026-10-04T11:00:00Z", 1);
        mvc.perform(post("/api/regions/{r}/unseal-reviews", region)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + reqId() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REGION_NOT_CLOSED"));
    }

    @Test
    void duplicateReviewerReturns422() throws Exception {
        postClosure();
        postReading("2026-10-04T09:00:00Z", 2);
        postReading("2026-10-04T10:00:00Z", 2);
        postReading("2026-10-04T11:00:00Z", 2);
        MvcResult created = postReview();
        long reviewId = ((Number) com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.reviewId")).longValue();
        mvc.perform(post("/api/unseal-reviews/{id}/confirmations", reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + reqId() + "\",\"reviewer\":\"alice\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/unseal-reviews/{id}/confirmations", reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + reqId() + "\",\"reviewer\":\"alice\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("DUPLICATE_REVIEWER"));
    }

    @Test
    void fullFlowOverHttpExposesStatusTimeline() throws Exception {
        postClosure();
        postReading("2026-10-04T09:00:00Z", 2);
        postReading("2026-10-04T10:00:00Z", 3);
        postReading("2026-10-04T11:00:00Z", 4);
        MvcResult created = postReview();
        long reviewId = ((Number) com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.reviewId")).longValue();
        mvc.perform(post("/api/unseal-reviews/{id}/confirmations", reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + reqId() + "\",\"reviewer\":\"alice\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/unseal-reviews/{id}/confirmations", reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + reqId() + "\",\"reviewer\":\"bob\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.decision.version").value(1));

        mvc.perform(get("/api/regions/{r}/status", region))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.consecutiveHourCount").value(3))
                .andExpect(jsonPath("$.latestReview.reviewers[0]").value("alice"))
                .andExpect(jsonPath("$.latestReview.reviewers[1]").value("bob"))
                .andExpect(jsonPath("$.timeline[0].type").value("CLOSED"))
                .andExpect(jsonPath("$.timeline[1].type").value("UNSEALED"));
    }

    @Test
    void requestIdConflictReturns409() throws Exception {
        String requestId = reqId();
        String body = "{\"requestId\":\"" + requestId
                + "\",\"hourUtc\":\"2026-10-04T09:00:00Z\",\"depthCm\":100,\"newSnowCm\":3}";
        mvc.perform(post("/api/regions/{r}/readings", region)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/regions/{r}/readings", region)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/regions/{r}/readings", region)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId
                                + "\",\"hourUtc\":\"2026-10-04T09:00:00Z\",\"depthCm\":100,\"newSnowCm\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
    }
}
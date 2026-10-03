package com.example.starter.well;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 接口层测试：走完整 HTTP 流程验证状态码、时间线与审核快照查询。
 */
@AutoConfigureMockMvc
class ApiTest extends WellTestSupport {

    @Autowired
    private MockMvc mvc;

    private static final String SEGMENT_BODY = """
            {"mode":"INJECTION","startUtc":"2026-10-03T22:00:00Z"}""";

    private String sampleBody(String requestId, String pressure, String sampledAt) {
        return "{\"requestId\":\"" + requestId + "\",\"pressureMpa\":" + pressure
                + ",\"sampledAt\":\"" + sampledAt + "\"}";
    }

    private void seedFourValid(String well) throws Exception {
        String[] times = {"2026-10-03T23:48:00Z", "2026-10-03T23:51:00Z",
                "2026-10-03T23:54:00Z", "2026-10-03T23:57:00Z"};
        String[] values = {"9.60", "10.20", "11.00", "10.40"};
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/wells/{well}/samples", well)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(sampleBody(well + "-s" + i, values[i], times[i])))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void fullSwitchFlowOverHttp() throws Exception {
        mvc.perform(post("/api/wells/{well}/segments", "W-API-1")
                        .contentType(MediaType.APPLICATION_JSON).content(SEGMENT_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode", is("INJECTION")));
        seedFourValid("W-API-1");

        String applyResponse = mvc.perform(post("/api/wells/{well}/switch-requests", "W-API-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-1\",\"toMode\":\"PRODUCTION\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andReturn().getResponse().getContentAsString();
        long requestId = Long.parseLong(
                applyResponse.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(post("/api/switch-requests/{id}/review", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":true,\"newSegmentStart\":\"2026-10-04T00:01:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.newSegmentStart", is("2026-10-04T00:01:00Z")));

        mvc.perform(get("/api/wells/{well}/timeline", "W-API-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments", hasSize(2)))
                .andExpect(jsonPath("$.segments[0].endUtc", notNullValue()))
                .andExpect(jsonPath("$.segments[1].mode", is("PRODUCTION")))
                .andExpect(jsonPath("$.switchRequests", hasSize(1)))
                .andExpect(jsonPath("$.switchRequests[0].request.status", is("APPROVED")))
                .andExpect(jsonPath("$.switchRequests[0].samples", hasSize(4)));
    }

    @Test
    void applyWithOutlierReadingReturns422() throws Exception {
        mvc.perform(post("/api/wells/{well}/segments", "W-API-2")
                        .contentType(MediaType.APPLICATION_JSON).content(SEGMENT_BODY))
                .andExpect(status().isCreated());
        String[] times = {"2026-10-03T23:48:00Z", "2026-10-03T23:51:00Z",
                "2026-10-03T23:54:00Z", "2026-10-03T23:57:00Z"};
        String[] values = {"9.60", "10.20", "12.10", "10.40"};
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/wells/{well}/samples", "W-API-2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(sampleBody("W-API-2-s" + i, values[i], times[i])))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/wells/{well}/switch-requests", "W-API-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-2\",\"toMode\":\"PRODUCTION\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", notNullValue()));
    }

    @Test
    void duplicateSegmentAndConflictingRequestIdReturn409() throws Exception {
        mvc.perform(post("/api/wells/{well}/segments", "W-API-3")
                        .contentType(MediaType.APPLICATION_JSON).content(SEGMENT_BODY))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/wells/{well}/segments", "W-API-3")
                        .contentType(MediaType.APPLICATION_JSON).content(SEGMENT_BODY))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/wells/{well}/samples", "W-API-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sampleBody("dup-1", "10.00", "2026-10-03T23:59:00Z")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/wells/{well}/samples", "W-API-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sampleBody("dup-1", "10.00", "2026-10-03T23:59:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId", is("dup-1")));
        mvc.perform(post("/api/wells/{well}/samples", "W-API-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sampleBody("dup-1", "11.00", "2026-10-03T23:59:00Z")))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectedReviewExposesReasonInTimeline() throws Exception {
        mvc.perform(post("/api/wells/{well}/segments", "W-API-4")
                        .contentType(MediaType.APPLICATION_JSON).content(SEGMENT_BODY))
                .andExpect(status().isCreated());
        seedFourValid("W-API-4");
        String applyResponse = mvc.perform(post("/api/wells/{well}/switch-requests", "W-API-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-4\",\"toMode\":\"PRODUCTION\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long requestId = Long.parseLong(applyResponse.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(post("/api/switch-requests/{id}/review", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":false,\"reason\":\"压力窗口复核未通过\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REJECTED")));

        mvc.perform(get("/api/wells/{well}/timeline", "W-API-4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.switchRequests[0].request.rejectReason",
                        is("压力窗口复核未通过")))
                .andExpect(jsonPath("$.segments", hasSize(1)));
    }
}
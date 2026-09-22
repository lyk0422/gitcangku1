package com.example.starter.firmware;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 发布单接口：创建、扩量（版本号并发控制、比例只增不减）、取消及幂等。
 */
class RolloutApiTest extends BaseIntegrationTest {

    private static final String URL = "/api/rollouts";

    private static String createBody(String requestId, String model, String from, String to, int ratio) {
        return """
                {"requestId":"%s","model":"%s","fromVersion":"%s","toVersion":"%s","ratio":%d}
                """.formatted(requestId, model, from, to, ratio);
    }

    private long createRollout(String requestId, String model, int ratio) throws Exception {
        String response = mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(requestId, model, "1.0.0", "2.0.0", ratio)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void createRolloutStartsAtVersion1() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("req-c1", "model-a", "1.0.0", "2.0.0", 10)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.model").value("model-a"))
                .andExpect(jsonPath("$.ratio").value(10))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void createRolloutWithSameFromAndToReturns400() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("req-c1", "model-a", "1.0.0", "1.0.0", 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SAME_VERSION"));
    }

    @Test
    void secondActiveRolloutForSameModelReturns409() throws Exception {
        createRollout("req-c1", "model-a", 10);
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("req-c2", "model-a", "1.0.0", "3.0.0", 20)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROLLOUT_ACTIVE_EXISTS"));
        // 不同型号不受影响
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("req-c3", "model-b", "1.0.0", "3.0.0", 20)))
                .andExpect(status().isCreated());
    }

    @Test
    void expandIncrementsVersionAndRatio() throws Exception {
        long id = createRollout("req-c1", "model-a", 10);
        mockMvc.perform(post(URL + "/" + id + "/expand").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-e1\",\"expectedVersion\":1,\"ratio\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ratio").value(50))
                .andExpect(jsonPath("$.version").value(2));
        // 同 requestId 重放返回首次结果
        mockMvc.perform(post(URL + "/" + id + "/expand").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-e1\",\"expectedVersion\":1,\"ratio\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void expandWithStaleExpectedVersionReturns409() throws Exception {
        long id = createRollout("req-c1", "model-a", 10);
        mockMvc.perform(post(URL + "/" + id + "/expand").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-e1\",\"expectedVersion\":1,\"ratio\":50}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(URL + "/" + id + "/expand").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-e2\",\"expectedVersion\":1,\"ratio\":60}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    @Test
    void expandWithDecreasedRatioReturns409() throws Exception {
        long id = createRollout("req-c1", "model-a", 50);
        mockMvc.perform(post(URL + "/" + id + "/expand").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-e1\",\"expectedVersion\":1,\"ratio\":30}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RATIO_DECREASED"));
    }

    @Test
    void cancelRolloutAndStateTransitions() throws Exception {
        long id = createRollout("req-c1", "model-a", 10);
        mockMvc.perform(post(URL + "/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-x1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.version").value(2));
        // 重复取消（新 requestId）409
        mockMvc.perform(post(URL + "/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-x2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROLLOUT_NOT_ACTIVE"));
        // 取消后扩量 409
        mockMvc.perform(post(URL + "/" + id + "/expand").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-e9\",\"expectedVersion\":2,\"ratio\":80}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROLLOUT_NOT_ACTIVE"));
        // 取消后同型号可再建发布单
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("req-c2", "model-a", "1.0.0", "3.0.0", 20)))
                .andExpect(status().isCreated());
    }

    @Test
    void cancelWithSameRequestIdReplays() throws Exception {
        long id = createRollout("req-c1", "model-a", 10);
        String body = "{\"requestId\":\"req-x1\"}";
        mockMvc.perform(post(URL + "/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post(URL + "/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void rolloutNotFoundReturns404() throws Exception {
        mockMvc.perform(post(URL + "/999/expand").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-e1\",\"expectedVersion\":1,\"ratio\":50}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROLLOUT_NOT_FOUND"));
        mockMvc.perform(post(URL + "/999/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-x1\"}"))
                .andExpect(status().isNotFound());
    }
}

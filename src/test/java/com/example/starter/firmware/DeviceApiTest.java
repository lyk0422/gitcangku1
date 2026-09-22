package com.example.starter.firmware;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设备登记接口：主流程、唯一约束、参数校验与幂等去重。
 */
class DeviceApiTest extends BaseIntegrationTest {

    private static final String URL = "/api/devices";

    private static String body(String requestId, String deviceId, String model,
                               String firmwareVersion, int bucketNo) {
        return """
                {"requestId":"%s","deviceId":"%s","model":"%s","firmwareVersion":"%s","bucketNo":%d}
                """.formatted(requestId, deviceId, model, firmwareVersion, bucketNo);
    }

    @Test
    void registerDeviceCreated() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body("req-1", "dev-1", "model-a", "1.0.0", 7)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceId").value("dev-1"))
                .andExpect(jsonPath("$.model").value("model-a"))
                .andExpect(jsonPath("$.firmwareVersion").value("1.0.0"))
                .andExpect(jsonPath("$.bucketNo").value(7));
    }

    @Test
    void registerSameRequestIdSameParamsReplaysOriginalResult() throws Exception {
        String body = body("req-replay", "dev-1", "model-a", "1.0.0", 7);
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        // 重放：返回首次成功结果（201 + 相同报文），不报错
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceId").value("dev-1"));
        // 数据库中仍只有一条设备记录
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM device", Integer.class);
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
    }

    @Test
    void registerSameRequestIdDifferentParamsReturns409() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body("req-conflict", "dev-1", "model-a", "1.0.0", 7)))
                .andExpect(status().isCreated());
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body("req-conflict", "dev-2", "model-a", "1.0.0", 7)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void registerDuplicateDeviceIdReturns409() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body("req-1", "dev-1", "model-a", "1.0.0", 7)))
                .andExpect(status().isCreated());
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body("req-2", "dev-1", "model-b", "2.0.0", 8)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DEVICE_ALREADY_EXISTS"));
    }

    @Test
    void registerInvalidBucketReturns400() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body("req-1", "dev-1", "model-a", "1.0.0", 100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body("req-2", "dev-1", "model-a", "1.0.0", -1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void failedRequestDoesNotOccupyRequestId() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body("req-1", "dev-1", "model-a", "1.0.0", 7)))
                .andExpect(status().isCreated());
        // req-2 触发业务失败（设备已存在），不应占用 req-2 这个键
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body("req-2", "dev-1", "model-a", "1.0.0", 7)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DEVICE_ALREADY_EXISTS"));
        // 同一 req-2 携带合法新参数可以成功
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body("req-2", "dev-2", "model-a", "1.0.0", 8)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceId").value("dev-2"));
    }
}

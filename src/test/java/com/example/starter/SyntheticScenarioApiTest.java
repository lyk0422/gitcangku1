package com.example.starter;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 合成场景接口测试（题干样例）：种子校准 2026-01-01 08:00Z 通过，
 * 次日 07:59 结束的任务可绑定，次日 08:00 开始的任务返回 422。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SyntheticScenarioApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void overnightTaskBindsAndLateTaskRejected() throws Exception {
        // 任务 1001：2026-01-01 12:00 至次日 07:59，完全落在有效期内。
        mockMvc.perform(post("/api/bindings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"SYN-RQ-OK\",\"taskId\":1001,\"calibrationId\":1001}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.calibrationId", is(1001)))
                .andExpect(jsonPath("$.azimuthDeviation", is(0.1)))
                .andExpect(jsonPath("$.elevationDeviation", is(-0.15)))
                .andExpect(jsonPath("$.calibrationValidUntil", is("2026-01-02T08:00:00Z")))
                .andExpect(jsonPath("$.status", is("BOUND")))
                .andExpect(jsonPath("$.voidedMark", is(false)));

        // 同参重放返回原绑定。
        mockMvc.perform(post("/api/bindings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"SYN-RQ-OK\",\"taskId\":1001,\"calibrationId\":1001}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId", is("SYN-RQ-OK")));

        // 异参重放返回 409。
        mockMvc.perform(post("/api/bindings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"SYN-RQ-OK\",\"taskId\":1002,\"calibrationId\":1001}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason", is("REQUEST_ID_CONFLICT")));

        // 任务 1002：次日 08:00 才开始，超出有效期，返回 422。
        mockMvc.perform(post("/api/bindings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"SYN-RQ-LATE\",\"taskId\":1002,\"calibrationId\":1001}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason", is("TASK_OUTSIDE_VALIDITY")));

        // 校准链查询展示有效期与绑定版本。
        mockMvc.perform(get("/api/antennas/ANT-01/calibration-chain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.antennaCode", is("ANT-01")))
                .andExpect(jsonPath("$.entries[0].calibration.validUntil",
                        is("2026-01-02T08:00:00Z")))
                .andExpect(jsonPath("$.entries[0].calibration.status", is("ACTIVE")))
                .andExpect(jsonPath("$.entries[0].bindings[0].binding.calibrationId", is(1001)));

        // 单条校准查询。
        mockMvc.perform(get("/api/calibrations/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed", is(true)))
                .andExpect(jsonPath("$.validUntil", notNullValue()));
    }

    @Test
    void invalidCalibrationRequestRejected() throws Exception {
        mockMvc.perform(post("/api/calibrations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"antennaCode\":\"\",\"calibratedAt\":\"2026-01-01T08:00:00Z\","
                        + "\"azimuthDeviation\":0.1,\"elevationDeviation\":0.1}"))
                .andExpect(status().isBadRequest());
    }
}
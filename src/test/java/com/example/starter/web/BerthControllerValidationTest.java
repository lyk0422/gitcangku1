package com.example.starter.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.starter.service.BerthAllocationService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 层参数校验测试：非法吃水、缺字段等由框架直接返回 422，不进入业务逻辑。
 */
@WebMvcTest(controllers = BerthController.class)
class BerthControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BerthAllocationService service;

    @Test
    void nonPositiveDraftReturns422() throws Exception {
        String body = """
                {
                  "requestId": "REQ-W1",
                  "vesselName": "合成船",
                  "draft": 0,
                  "safetyMargin": 0.20,
                  "berthCode": "B-01",
                  "startTime": "2026-10-01T08:00:00",
                  "endTime": "2026-10-01T10:00:00"
                }
                """;
        mockMvc.perform(post("/api/berths/applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void missingFieldsReturns422() throws Exception {
        mockMvc.perform(post("/api/berths/applications")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void validRequestPassesValidationToService() throws Exception {
        String body = """
                {
                  "requestId": "REQ-W2",
                  "vesselName": "合成船",
                  "draft": 9.20,
                  "safetyMargin": 0.20,
                  "berthCode": "B-01",
                  "startTime": "2026-10-01T08:00:00",
                  "endTime": "2026-10-01T10:00:00"
                }
                """;
        when(service.apply(any())).thenReturn(new ApplicationResponse(
                "REQ-W2", "合成船", null, null, "B-01", null, null,
                "CONFIRMED", null, null, null, 1L, null, null, null,
                null, null, null, null, null, null, null));
        mockMvc.perform(post("/api/berths/applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }
}

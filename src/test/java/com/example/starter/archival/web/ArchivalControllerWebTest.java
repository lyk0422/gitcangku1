package com.example.starter.archival.web;

import com.example.starter.archival.service.ArchivalService;
import com.example.starter.archival.web.dto.EnrollRequest;
import com.example.starter.archival.web.dto.EnrollResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口层测试：业务冲突映射 409、未找到映射 404、参数校验 400、成功路径 JSON 正常。
 */
@WebMvcTest(ArchivalController.class)
class ArchivalControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ArchivalService archivalService;

    @Test
    @DisplayName("跨批次占用等业务冲突返回409并带原因")
    void conflictMapsTo409() throws Exception {
        when(archivalService.enroll(any()))
                .thenThrow(new ApiConflictException("页面正被其他进行中批次占用，不能跨批次入组: P"));

        mockMvc.perform(post("/api/archival/enrollments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("batchNo", "B1", "accessionNo", "P", "requestId", "r1"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("页面正被其他进行中批次占用，不能跨批次入组: P"));
    }

    @Test
    @DisplayName("资源不存在返回404")
    void notFoundMapsTo404() throws Exception {
        when(archivalService.getHistory("NOPE"))
                .thenThrow(new ApiNotFoundException("档案页不存在: NOPE"));

        mockMvc.perform(get("/api/archival/pages/NOPE/history"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("档案页不存在: NOPE"));
    }

    @Test
    @DisplayName("缺少必填字段返回400")
    void validationMapsTo400() throws Exception {
        mockMvc.perform(post("/api/archival/enrollments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("batchNo", "B1", "accessionNo", "P"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("入组成功返回200与入组结果")
    void enrollSuccess() throws Exception {
        when(archivalService.enroll(any(new EnrollRequest("B1", "P", "r1").getClass())))
                .thenReturn(new EnrollResult("B1", "P", false, false));

        mockMvc.perform(post("/api/archival/enrollments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("batchNo", "B1", "accessionNo", "P", "requestId", "r1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchNo").value("B1"))
                .andExpect(jsonPath("$.accessionNo").value("P"))
                .andExpect(jsonPath("$.replayed").value(false));
    }
}

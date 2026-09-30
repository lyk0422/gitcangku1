package com.example.starter.web;

import com.example.starter.service.ConflictException;
import com.example.starter.service.ForbiddenException;
import com.example.starter.service.NotFoundException;
import com.example.starter.service.ObservationService;
import com.example.starter.service.ValidationException;
import com.example.starter.web.dto.ApplyRequest;
import com.example.starter.web.dto.CancelResponse;
import com.example.starter.web.dto.RequestView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 入口与状态码映射的切片测试（业务结论由真实 H2 集成测试覆盖）。
 */
@WebMvcTest(ObservationController.class)
@Import(GlobalExceptionHandler.class)
class ObservationControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ObservationService service;

    @Test
    @DisplayName("申请成功返回201")
    void applyReturns201() throws Exception {
        ApplyRequest body = new ApplyRequest("R-1", "P1", "T",
                Instant.parse("2026-09-30T20:00:00Z"),
                Instant.parse("2026-09-30T21:00:00Z"), 60);
        when(service.apply(eq("k1"), any(ApplyRequest.class))).thenReturn(
                new RequestView("R-1", "P1", "T",
                        body.startAt(), body.endAt(), 60, "PENDING",
                        Instant.parse("2026-09-01T00:00:00Z"), null, null));

        mockMvc.perform(post("/api/observations/requests")
                        .header("X-Request-Id", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestNo").value("R-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("Bean 校验失败返回422")
    void invalidBodyReturns422() throws Exception {
        String body = """
                {"requestNo":"","projectId":"P1","instrumentId":"T",
                 "startAt":"2026-09-30T21:00:00Z","endAt":"2026-09-30T20:00:00Z",
                 "durationMinutes":0}""";

        mockMvc.perform(post("/api/observations/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("ValidationException 映射422并带原因")
    void validationExceptionMapsTo422() throws Exception {
        when(service.approve(any(), any())).thenThrow(
                new ValidationException("SLOT_OVERLAP", "与已分配窗口重叠"));

        mockMvc.perform(post("/api/observations/requests/R-1/approve"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SLOT_OVERLAP"))
                .andExpect(jsonPath("$.message").value("与已分配窗口重叠"));
    }

    @Test
    @DisplayName("ConflictException 映射409")
    void conflictExceptionMapsTo409() throws Exception {
        when(service.cancel(any(), any(), any())).thenThrow(
                new ConflictException("ALREADY_CANCELLED", "申请已撤销，不能重复撤销"));

        mockMvc.perform(post("/api/observations/requests/R-1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestNo":"R-1","projectId":"P1"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CANCELLED"));
    }

    @Test
    @DisplayName("ForbiddenException 映射403")
    void forbiddenMapping() throws Exception {
        when(service.cancel(any(), any(), any())).thenThrow(
                new ForbiddenException("NOT_OWNER", "只能撤销本项目的申请"));

        mockMvc.perform(post("/api/observations/requests/R-1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestNo":"R-1","projectId":"P2"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_OWNER"));
    }

    @Test
    @DisplayName("NotFoundException 映射404")
    void notFoundMapping() throws Exception {
        when(service.cancel(any(), any(), any())).thenThrow(
                new NotFoundException("REQUEST_NOT_FOUND", "申请不存在"));

        mockMvc.perform(post("/api/observations/requests/R-X/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestNo":"R-X","projectId":"P1"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
    }

    @Test
    @DisplayName("撤销成功响应包含被撤销申请与接替候补")
    void cancelSuccessResponse() throws Exception {
        RequestView cancelled = new RequestView("R-1", "P1", "T",
                Instant.parse("2026-09-30T20:00:00Z"),
                Instant.parse("2026-09-30T21:00:00Z"), 60, "CANCELLED",
                Instant.parse("2026-09-01T00:00:00Z"), null, null);
        RequestView promoted = new RequestView("W1", "P2", "T",
                Instant.parse("2026-09-30T20:10:00Z"),
                Instant.parse("2026-09-30T20:50:00Z"), 40, "PROMOTED",
                Instant.parse("2026-09-01T00:05:00Z"), "R-1",
                Instant.parse("2026-09-01T00:10:00Z"));
        when(service.cancel(any(), eq("R-1"), eq("P1"))).thenReturn(
                new CancelResponse(cancelled, promoted,
                        Instant.parse("2026-09-30T20:00:00Z"),
                        Instant.parse("2026-09-30T21:00:00Z")));

        mockMvc.perform(post("/api/observations/requests/R-1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestNo":"R-1","projectId":"P1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled.status").value("CANCELLED"))
                .andExpect(jsonPath("$.promoted.requestNo").value("W1"))
                .andExpect(jsonPath("$.promoted.promotedFromRequestNo").value("R-1"))
                .andExpect(jsonPath("$.gapStartAt").value("2026-09-30T20:00:00Z"))
                .andExpect(jsonPath("$.gapEndAt").value("2026-09-30T21:00:00Z"));
    }
}

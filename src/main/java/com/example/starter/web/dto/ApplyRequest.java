package com.example.starter.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 窗口申请请求体。时间按 UTC 解析，窗口为 [windowStart, windowEnd)。
 */
public record ApplyRequest(
        @NotBlank String project,
        @NotBlank String instrumentId,
        @NotBlank String researchGroup,
        @NotNull Instant windowStart,
        @NotNull Instant windowEnd,
        @Min(1) int durationMinutes,
        String requestId
) {
}

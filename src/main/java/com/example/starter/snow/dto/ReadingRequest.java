package com.example.starter.snow.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 雪况上报请求。
 *
 * @param requestId 幂等键：同参重放返回原结果，异参返回 409
 * @param hourUtc 观测整点，ISO-8601（如 2026-10-04T10:00:00Z），分钟秒必须为 0
 * @param depthCm 雪深，厘米
 * @param newSnowCm 新增积雪，厘米
 */
public record ReadingRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank String hourUtc,
        @NotNull @Min(0) @Max(100000) Integer depthCm,
        @NotNull @Min(0) @Max(100000) Integer newSnowCm) {
}
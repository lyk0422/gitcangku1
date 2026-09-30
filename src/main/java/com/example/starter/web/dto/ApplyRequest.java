package com.example.starter.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 窗口申请请求。所有时刻为 UTC，窗口必须为左闭右开。
 *
 * @param requestNo 申请编号（调用方保证唯一）
 * @param projectId 项目（研究组）标识
 * @param instrumentId 仪器标识
 * @param startAt 窗口开始时刻（UTC，包含）
 * @param endAt 窗口结束时刻（UTC，不包含）
 * @param durationMinutes 所需观测分钟数
 */
public record ApplyRequest(
        @NotBlank String requestNo,
        @NotBlank String projectId,
        @NotBlank String instrumentId,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        @Min(1) int durationMinutes) {
}

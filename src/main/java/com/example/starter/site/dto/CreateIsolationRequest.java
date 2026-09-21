package com.example.starter.site.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 创建隔离记录请求。计划区间为 UTC 左闭右开区间。
 */
public record CreateIsolationRequest(
        @NotBlank String commandKey,
        @NotBlank String isolationKey,
        @NotBlank String deviceId,
        @NotNull Instant plannedStartUtc,
        @NotNull Instant plannedEndUtc,
        @NotBlank String lockedBy) {
}

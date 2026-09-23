package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建多跳回退计划请求：针对一次已结束投放，提交目标旧版本与完整设备集合。
 * sampleFloor 与 failureThresholdPercent 可选，缺省分别为 2 与 100。
 */
public record CreateRollbackPlanRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String planKey,
        @Min(1) long sourceReleaseId,
        @NotBlank @Size(max = 64) String targetVersion,
        @NotEmpty List<@NotBlank @Size(max = 64) String> deviceIds,
        @Min(2) @Max(100) Integer sampleFloor,
        @Min(1) @Max(100) Integer failureThresholdPercent) {

    public static final int DEFAULT_SAMPLE_FLOOR = 2;
    public static final int DEFAULT_FAILURE_THRESHOLD_PERCENT = 100;

    public int effectiveSampleFloor() {
        return sampleFloor == null ? DEFAULT_SAMPLE_FLOOR : sampleFloor;
    }

    public int effectiveFailureThresholdPercent() {
        return failureThresholdPercent == null ? DEFAULT_FAILURE_THRESHOLD_PERCENT : failureThresholdPercent;
    }
}

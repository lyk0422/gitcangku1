package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建多跳回退计划请求。针对一张已结束投放单，给出目标旧版本与完整设备集合，
 * 系统按各设备成功投放历史构造1～5跳的连续反向路径；设备集合换序视为同一请求。
 */
public record CreateRollbackPlanRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String planKey,
        @NotNull Long sourceReleaseId,
        @NotBlank @Size(max = 64) String targetVersion,
        @NotEmpty List<@NotBlank @Size(max = 64) String> devices,
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

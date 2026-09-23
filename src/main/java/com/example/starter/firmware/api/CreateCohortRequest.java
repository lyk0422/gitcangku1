package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建投放队列请求。sampleFloor 与 failureThresholdPercent 为可选，
 * 缺省分别为 2 与 100（即仅当本轮全部失败且样本达标时才自动暂停）。
 */
public record CreateCohortRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 64) String firmwareVersion,
        @NotBlank @Size(max = 64) String region,
        @Min(1) int regionQuota,
        @Min(1) int deviceCap,
        @Min(1) @Max(100) int grayPercent,
        @Min(2) @Max(100) Integer sampleFloor,
        @Min(1) @Max(100) Integer failureThresholdPercent) {

    public static final int DEFAULT_SAMPLE_FLOOR = 2;
    public static final int DEFAULT_FAILURE_THRESHOLD_PERCENT = 100;

    public int effectiveSampleFloor() {
        return sampleFloor == null ? DEFAULT_SAMPLE_FLOOR : sampleFloor;
    }

    public int effectiveFailureThresholdPercent() {
        return failureThresholdPercent == null ? DEFAULT_FAILURE_THRESHOLD_PERCENT
                : failureThresholdPercent;
    }
}

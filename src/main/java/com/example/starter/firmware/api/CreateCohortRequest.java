package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建投放队列请求。
 */
public record CreateCohortRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Min(1) long releaseId,
        @NotBlank @Size(max = 64) String cohortCode,
        @NotBlank @Size(max = 64) String firmwareVersion,
        @NotBlank @Size(max = 64) String regionCode,
        @Min(1) int deviceCap,
        @Min(0) @Max(100) int canaryPercent,
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

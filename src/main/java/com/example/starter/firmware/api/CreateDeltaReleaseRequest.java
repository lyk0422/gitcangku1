package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建增量发布单请求：声明基底摘要、补丁摘要与目标摘要（均64位小写十六进制）。
 * 仅管理元数据，不下载二进制，不要求真实密码学签名。
 * sampleFloor 与 failureThresholdPercent 为可选，缺省分别为 2 与 100。
 */
public record CreateDeltaReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String fromVersion,
        @NotBlank @Size(max = 64) String toVersion,
        @Min(0) @Max(100) int ratio,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$", message = "基底摘要须为64位小写十六进制")
        String baseDigest,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$", message = "补丁摘要须为64位小写十六进制")
        String patchDigest,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$", message = "目标摘要须为64位小写十六进制")
        String targetDigest,
        @Min(2) @Max(100) Integer sampleFloor,
        @Min(1) @Max(100) Integer failureThresholdPercent) {

    public int effectiveSampleFloor() {
        return sampleFloor == null ? CreateReleaseRequest.DEFAULT_SAMPLE_FLOOR : sampleFloor;
    }

    public int effectiveFailureThresholdPercent() {
        return failureThresholdPercent == null
                ? CreateReleaseRequest.DEFAULT_FAILURE_THRESHOLD_PERCENT : failureThresholdPercent;
    }
}
package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建发布单请求。sampleFloor 与 failureThresholdPercent 为可选，
 * 缺省分别为 2 与 100（即仅当本轮全部失败且样本达标时才自动暂停），旧客户端不传仍可创建。
 * packageBytes 与 imageBytes 为可选容量字段：必须成对提供，均为正整数；
 * 均不提供时保留旧模式，领取不做暂存空间核验。
 */
public record CreateReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String fromVersion,
        @NotBlank @Size(max = 64) String toVersion,
        @Min(0) @Max(100) int ratio,
        @Min(2) @Max(100) Integer sampleFloor,
        @Min(1) @Max(100) Integer failureThresholdPercent,
        @Min(1) Long packageBytes,
        @Min(1) Long imageBytes) {

    public static final int DEFAULT_SAMPLE_FLOOR = 2;
    public static final int DEFAULT_FAILURE_THRESHOLD_PERCENT = 100;

    /**
     * 兼容旧调用方：不配置监控参数与容量字段时使用默认值。
     */
    public CreateReleaseRequest(String requestId, String model, String fromVersion, String toVersion, int ratio) {
        this(requestId, model, fromVersion, toVersion, ratio, null, null, null, null);
    }

    /**
     * 兼容旧调用方：配置监控参数但不登记容量字段。
     */
    public CreateReleaseRequest(String requestId, String model, String fromVersion, String toVersion,
                                int ratio, Integer sampleFloor, Integer failureThresholdPercent) {
        this(requestId, model, fromVersion, toVersion, ratio, sampleFloor, failureThresholdPercent,
                null, null);
    }

    public int effectiveSampleFloor() {
        return sampleFloor == null ? DEFAULT_SAMPLE_FLOOR : sampleFloor;
    }

    public int effectiveFailureThresholdPercent() {
        return failureThresholdPercent == null ? DEFAULT_FAILURE_THRESHOLD_PERCENT : failureThresholdPercent;
    }
}

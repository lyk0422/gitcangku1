package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建发布单请求。sampleFloor 与 failureThresholdPercent 为可选，
 * 缺省分别为 2 与 100（即仅当本轮全部失败且样本达标时才自动暂停），旧客户端不传仍可创建。
 * releaseType 缺省 FULL（普通全量）；DELTA 增量发布必须声明基底、补丁、目标三个摘要，
 * 均为 64 位十六进制（统一落库为小写），仅管理元数据，不下载二进制。
 */
public record CreateReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String fromVersion,
        @NotBlank @Size(max = 64) String toVersion,
        @Min(0) @Max(100) int ratio,
        @Min(2) @Max(100) Integer sampleFloor,
        @Min(1) @Max(100) Integer failureThresholdPercent,
        String releaseType,
        @Size(max = 64) String baseDigest,
        @Size(max = 64) String patchDigest,
        @Size(max = 64) String targetDigest) {

    public static final int DEFAULT_SAMPLE_FLOOR = 2;
    public static final int DEFAULT_FAILURE_THRESHOLD_PERCENT = 100;

    /**
     * 兼容旧调用方：不配置监控参数时使用默认值，普通全量发布。
     */
    public CreateReleaseRequest(String requestId, String model, String fromVersion, String toVersion, int ratio) {
        this(requestId, model, fromVersion, toVersion, ratio, null, null, null, null, null, null);
    }

    /**
     * 兼容旧调用方：显式监控参数的普通全量发布。
     */
    public CreateReleaseRequest(String requestId, String model, String fromVersion, String toVersion, int ratio,
                                Integer sampleFloor, Integer failureThresholdPercent) {
        this(requestId, model, fromVersion, toVersion, ratio, sampleFloor, failureThresholdPercent,
                null, null, null, null);
    }

    public int effectiveSampleFloor() {
        return sampleFloor == null ? DEFAULT_SAMPLE_FLOOR : sampleFloor;
    }

    public int effectiveFailureThresholdPercent() {
        return failureThresholdPercent == null ? DEFAULT_FAILURE_THRESHOLD_PERCENT : failureThresholdPercent;
    }
}
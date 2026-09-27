package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建发布单请求。sampleFloor 与 failureThresholdPercent 为可选，
 * 缺省分别为 2 与 100（即仅当本轮全部失败且样本达标时才自动暂停），旧客户端不传仍可创建。
 * targetPolicy 可选：给出时按标签表达式在创建时刻解析目标设备快照，
 * 后续拉取仅对快照成员下发；快照为空则创建失败（422）。
 */
public record CreateReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String fromVersion,
        @NotBlank @Size(max = 64) String toVersion,
        @Min(0) @Max(100) int ratio,
        @Min(2) @Max(100) Integer sampleFloor,
        @Min(1) @Max(100) Integer failureThresholdPercent,
        @Valid TargetPolicyRequest targetPolicy) {

    public static final int DEFAULT_SAMPLE_FLOOR = 2;
    public static final int DEFAULT_FAILURE_THRESHOLD_PERCENT = 100;

    /**
     * 兼容旧调用方：不配置监控参数与目标策略时使用默认值。
     */
    public CreateReleaseRequest(String requestId, String model, String fromVersion, String toVersion,
                                int ratio) {
        this(requestId, model, fromVersion, toVersion, ratio, null, null, null);
    }

    /**
     * 兼容旧调用方：不配置目标策略。
     */
    public CreateReleaseRequest(String requestId, String model, String fromVersion, String toVersion,
                                int ratio, Integer sampleFloor, Integer failureThresholdPercent) {
        this(requestId, model, fromVersion, toVersion, ratio, sampleFloor, failureThresholdPercent,
                null);
    }

    public int effectiveSampleFloor() {
        return sampleFloor == null ? DEFAULT_SAMPLE_FLOOR : sampleFloor;
    }

    public int effectiveFailureThresholdPercent() {
        return failureThresholdPercent == null ? DEFAULT_FAILURE_THRESHOLD_PERCENT
                : failureThresholdPercent;
    }
}

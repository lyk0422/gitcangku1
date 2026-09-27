package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建发布单请求。sampleFloor 与 failureThresholdPercent 为可选，
 * 缺省分别为 2 与 100（即仅当本轮全部失败且样本达标时才自动暂停），旧客户端不传仍可创建。
 * target 为标签目标表达式；target 缺省或三组条件全空按 422 拒绝（目标必须显式表达）。
 * operator 为操作者标识，可缺省，进入幂等指纹。
 */
public record CreateReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String fromVersion,
        @NotBlank @Size(max = 64) String toVersion,
        @Min(0) @Max(100) int ratio,
        @Min(2) @Max(100) Integer sampleFloor,
        @Min(1) @Max(100) Integer failureThresholdPercent,
        @Valid LabelTargetRequest target,
        @Size(max = 64) String operator) {

    public static final int DEFAULT_SAMPLE_FLOOR = 2;
    public static final int DEFAULT_FAILURE_THRESHOLD_PERCENT = 100;

    /**
     * 兼容旧调用方：不配置监控参数与目标表达式时使用默认值（目标表达式缺省由服务端 422）。
     */
    public CreateReleaseRequest(String requestId, String model, String fromVersion, String toVersion, int ratio) {
        this(requestId, model, fromVersion, toVersion, ratio, null, null, null, null);
    }

    /**
     * 仅配置标签目标表达式，监控参数用默认值。
     */
    public CreateReleaseRequest(String requestId, String model, String fromVersion, String toVersion, int ratio,
                                LabelTargetRequest target) {
        this(requestId, model, fromVersion, toVersion, ratio, null, null, target, null);
    }

    /**
     * 兼容旧调用方：仅配置监控参数，不带目标表达式（服务端按空条件 422）。
     */
    public CreateReleaseRequest(String requestId, String model, String fromVersion, String toVersion, int ratio,
                                Integer sampleFloor, Integer failureThresholdPercent) {
        this(requestId, model, fromVersion, toVersion, ratio, sampleFloor, failureThresholdPercent, null, null);
    }

    /**
     * 配置监控参数与标签目标表达式，不指定操作者。
     */
    public CreateReleaseRequest(String requestId, String model, String fromVersion, String toVersion, int ratio,
                                Integer sampleFloor, Integer failureThresholdPercent, LabelTargetRequest target) {
        this(requestId, model, fromVersion, toVersion, ratio, sampleFloor, failureThresholdPercent, target, null);
    }

    public int effectiveSampleFloor() {
        return sampleFloor == null ? DEFAULT_SAMPLE_FLOOR : sampleFloor;
    }

    public int effectiveFailureThresholdPercent() {
        return failureThresholdPercent == null ? DEFAULT_FAILURE_THRESHOLD_PERCENT : failureThresholdPercent;
    }
}

package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建发布单请求。sampleFloor 与 failureThresholdPercent 为可选，
 * 缺省分别为 2 与 100（即仅当本轮全部失败且样本达标时才自动暂停），旧客户端不传仍可创建。
 * packageDigest 与 shards 同时为空表示无分片清单的遗留发布单（不做完整性闸门）；
 * 同时非空时登记规范排序的分片摘要与完整包摘要，序号0连续、摘要格式固定、聚合摘要必须匹配，否则422。
 */
public record CreateReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String fromVersion,
        @NotBlank @Size(max = 64) String toVersion,
        @Min(0) @Max(100) int ratio,
        @Min(2) @Max(100) Integer sampleFloor,
        @Min(1) @Max(100) Integer failureThresholdPercent,
        @Size(max = 64) String packageDigest,
        @Valid List<ShardInput> shards) {

    public static final int DEFAULT_SAMPLE_FLOOR = 2;
    public static final int DEFAULT_FAILURE_THRESHOLD_PERCENT = 100;

    /**
     * 兼容旧调用方：仅灰度参数，无分片清单。
     */
    public CreateReleaseRequest(String requestId, String model, String fromVersion, String toVersion, int ratio) {
        this(requestId, model, fromVersion, toVersion, ratio, null, null, null, null);
    }

    /**
     * 兼容旧调用方：灰度参数加监控参数，无分片清单。
     */
    public CreateReleaseRequest(String requestId, String model, String fromVersion, String toVersion, int ratio,
                                Integer sampleFloor, Integer failureThresholdPercent) {
        this(requestId, model, fromVersion, toVersion, ratio, sampleFloor, failureThresholdPercent, null, null);
    }

    public int effectiveSampleFloor() {
        return sampleFloor == null ? DEFAULT_SAMPLE_FLOOR : sampleFloor;
    }

    public int effectiveFailureThresholdPercent() {
        return failureThresholdPercent == null ? DEFAULT_FAILURE_THRESHOLD_PERCENT : failureThresholdPercent;
    }
}
